package com.melody.melodyplus.bridge
import android.os.SystemClock
import com.melody.melodyplus.hook.modLogT

import com.melody.melodyplus.hook.modLog

import android.bluetooth.BluetoothDevice
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale

enum class DevicePresentationPolicy {
    OFFICIAL_SUPPORTED,
    MODULE_SUPPORTED,
    UNSUPPORTED,
}

data class BoundDevice(
    val address: String,
    val profileId: String,
) {
    val profile: DeviceProfile?
        get() = DeviceProfiles.get(profileId)
}

data class DeviceNameRuleState(
    val defaultNames: Set<String>,
    val exceptionNames: Set<String>,
    val profileId: String,
) {
    val allNames: Set<String> = (defaultNames + exceptionNames)
        .mapTo(linkedSetOf()) { normalizeDeviceName(it) }
        .filterNotTo(linkedSetOf()) { isAirPodsName(it) }

    fun matches(name: String?): Boolean =
        normalizeDeviceName(name).let { normalized ->
            normalized.isNotBlank() && !isAirPodsName(normalized) && normalized in allNames
        }

    companion object {
        fun normalizeDeviceName(name: String?): String =
            name.orEmpty()
                .trim()
                .replace(Regex("\\s+"), " ")
                .uppercase(Locale.US)

        fun isAirPodsName(name: String?): Boolean =
            normalizeDeviceName(name).contains("AIRPODS")
    }
}

data class HostHookStatus(
    val packageName: String,
    val hookName: String,
    val updatedAtMillis: Long,
)

object DevicePresentationPolicyResolver {
    fun resolve(
        originalOfficialVisible: Boolean,
        moduleSupported: Boolean,
    ): DevicePresentationPolicy {
        return when {
            originalOfficialVisible -> DevicePresentationPolicy.OFFICIAL_SUPPORTED
            moduleSupported -> DevicePresentationPolicy.MODULE_SUPPORTED
            else -> DevicePresentationPolicy.UNSUPPORTED
        }
    }
}

object RegistryContract {
    private const val MODULE_PACKAGE = "com.melody.melodyplus"
    const val AUTHORITY = "com.melody.melodyplus.registry"
    const val METHOD_GET_BINDING = "get_binding"
    const val METHOD_GET_NAME_RULES = "get_name_rules"
    const val METHOD_REPORT_STATUS = "report_status"
    const val METHOD_GET_STATUS = "get_status"
    const val METHOD_APPEND_LOG = "append_log"
    const val METHOD_GET_LOGS = "get_logs"
    /**
     * Provider 自观测统计（调用耗时/拒绝原因/日志丢弃计数）。
     *
     * 用途：验证「Provider 被高频调用但日志被节流丢弃」这类问题有据可查；
     * 走宽松 caller（只读、无副作用），供 UI 或 adb 取证。
     */
    const val METHOD_GET_PROVIDER_STATS = "get_provider_stats"
    const val EXTRA_STATS_TOTAL = "stats_total"
    const val EXTRA_STATS_REJECTED = "stats_rejected"
    const val EXTRA_STATS_DROPPED_LOGS = "stats_dropped_logs"
    const val EXTRA_STATS_SLOW = "stats_slow"
    const val EXTRA_STATS_LAST_REASON = "stats_last_reason"
    const val EXTRA_STATS_MAX_ELAPSED_MS = "stats_max_elapsed_ms"
    const val METHOD_CLEAR_LOGS = "clear_logs"
    /** 一键导出：把全部模块日志写成 txt 文件，返回文件路径（便于排查问题）。 */
    const val METHOD_EXPORT_LOGS = "export_logs"
    const val EXTRA_EXPORT_PATH = "export_path"
    const val EXTRA_EXPORT_TEXT = "export_text"
    const val METHOD_INJECT_DEBUG_POPUP = "inject_debug_popup"
    /** 取某型号用户自定义图片（hook 侧无存储权限，经 Provider 中转）。 */
    const val METHOD_GET_DEVICE_IMAGE = "get_device_image"
    /**
     * MAC→型号 绑定落盘/反查（跨进程单一真源）。
     *
     * 关键修复：原实现用 `context.getSharedPreferences`，而 hook 侧运行在「蓝牙进程 / melody
     * 宿主进程 / melody :fg 进程」等不同进程，各进程私有 prefs 互不可见 → 绑定等于没落盘。
     * 改经 Provider（模块进程）统一落到**模块私有目录** `files/bindings/<型号>/<MAC>`，
     * 任意进程均可经 Provider 读到（与 [DeviceImageStore] 同范式）。
     */
    const val METHOD_SAVE_MAC_PROFILE = "save_mac_profile"
    const val METHOD_GET_MAC_PROFILE = "get_mac_profile"
    /**
     * 取模块「内置」图片（assets/device_images）。
     * hook 侧运行在宿主进程，其 ClassLoader 指向宿主 APK，读不到模块 assets；
     * 必须由 Provider（模块进程）用自身 AssetManager 读后回传。
     */
    const val METHOD_GET_BUILTIN_IMAGE = "get_builtin_image"
    const val EXTRA_DEVICE_IMAGE_BYTES = "device_image_bytes"
    const val EXTRA_DEVICE_IMAGE_FOUND = "device_image_found"
    /** 图片槽位：main/left/right/case（见 [com.melody.melodyplus.bridge.DeviceImageAssets]）。 */
    const val EXTRA_DEVICE_IMAGE_SLOT = "device_image_slot"
    const val EXTRA_MAC = "mac"
    const val EXTRA_MAC_PROFILE_ID = "mac_profile_id"
    const val EXTRA_MAC_PROFILE_FOUND = "mac_profile_found"
    const val EXTRA_DEBUG_NAME = "debug_name"
    const val EXTRA_DEBUG_ADDRESS = "debug_address"
    const val EXTRA_DEBUG_PROFILE_ID = "debug_profile_id"
    const val EXTRA_DEBUG_RESULT = "debug_result"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_PROFILE_ID = "profile_id"
    const val EXTRA_DEFAULT_NAMES = "default_names"
    const val EXTRA_EXCEPTION_NAMES = "exception_names"
    const val EXTRA_HOOK_NAME = "hook_name"
    const val EXTRA_UPDATED_AT = "updated_at"
    const val EXTRA_STATUS_PACKAGES = "status_packages"
    const val EXTRA_STATUS_HOOKS = "status_hooks"
    const val EXTRA_STATUS_TIMESTAMPS = "status_timestamps"
    const val EXTRA_LOG_LEVEL = "log_level"
    const val EXTRA_LOG_SOURCE = "log_source"
    const val EXTRA_LOG_MESSAGE = "log_message"
    const val EXTRA_LOG_TIMESTAMP = "log_timestamp"
    const val EXTRA_LOGS_JSON = "logs_json"
    const val PROFILE_SONY_WF1000XM3 = DeviceProfiles.SONY_WF1000XM3
    val URI: Uri = Uri.parse("content://$AUTHORITY")

    /**
     * Provider 可用性判定。
     *
     * 【关键】不能在跨进程（宿主 hook 侧）用「查包 / resolveContentProvider」做预检：
     * Android 11+ 包可见性（package visibility）会让宿主进程 `getApplicationInfo(模块包)`
     * 抛 NameNotFoundException、`resolveContentProvider` 返回 null → 误判为不可用 →
     * 日志/绑定上报整段被跳过（历史模块日志页为空的真凶）。
     * 而 `contentResolver.call(...)` 由 system_server 解析 authority，**不受该限制**。
     * 故：① 模块自身进程必然可用；② 跨进程乐观放行，真实失败由调用点 runCatching 兜底。
     */
    fun isProviderAvailable(context: Context): Boolean {
        // ① 模块自身进程：直接可用。
        if (runCatching { context.packageName == MODULE_PACKAGE }.getOrDefault(false)) return true
        // ② 跨进程（宿主 hook 侧）：乐观放行（真实不可用会被调用方 runCatching 吞掉）。
        return true
    }
}

/**
 * Provider caller 身份与授权策略（纯逻辑）。
 *
 * P3 拆分：从 [DeviceRegistryProvider] 抽出白名单与「包集合 → 放行判定」的纯函数，
 * 使「UID/包集合读取（依赖 PackageManager）」之外的部分可脱离 Android 单测。
 * Provider 侧仅保留 UID 取值与 [RegistryCallerPolicy.specialUidLabel] 调用。
 */
internal object RegistryCallerPolicy {
    /** 允许调用 Provider 的白名单包（无线设置 / 官方耳机 App / 蓝牙栈 / 本模块）。 */
    val allowedCallers: Set<String> = setOf(
        "com.oplus.wirelesssettings",
        "com.coloros.wirelesssettings",
        "com.oplus.melody",
        "com.coloros.melody",
        "com.oneplus.melody",
        "com.android.bluetooth",
        "com.melody.melodyplus",
    )

    const val ROOT = "root"
    const val SHELL = "com.android.shell"
    const val SELF = "com.melody.melodyplus"

    /**
     * 特殊 UID 直接映射为身份标签；返回 null 表示需继续走包集合判定。
     * root(0) / shell(2000) 仅用于 adb/root 调试验证。
     */
    fun specialUidLabel(uid: Int, selfUid: Int): String? = when (uid) {
        0 -> ROOT
        2000 -> SHELL
        selfUid -> SELF
        else -> null
    }

    /** 宽松判定：包集合中任一命中白名单即放行，返回代表包名；否则 null。 */
    fun resolveLoose(packages: List<String>): String? =
        packages.firstOrNull { it in allowedCallers }

    /**
     * 严格判定（高权限操作：清空/导出日志、调试注入）：包集合非空且**全部**受控才放行，
     * 返回首个包名；否则 null。用于避免 shared UID 下「任取其一」代表身份。
     */
    fun resolveStrict(packages: List<String>): String? {
        if (packages.isEmpty()) return null
        if (!packages.all { it in allowedCallers }) return null
        return packages.first()
    }
}

object HookStatusStore {
    private const val PREFS = "module_hook_status"
    private const val KEY_HOOK_SUFFIX = "_hook"
    private const val KEY_TIME_SUFFIX = "_time"

    val scopePackages: List<String> = listOf(
        "com.oplus.melody",
        "com.coloros.melody",
        "com.oneplus.melody",
        "com.oplus.wirelesssettings",
        "com.coloros.wirelesssettings",
        "com.android.bluetooth",
    )

    fun report(context: Context, packageName: String, hookName: String, updatedAtMillis: Long = System.currentTimeMillis()) {
        if (packageName !in scopePackages) return
        prefs(context).edit()
            .putString(packageName + KEY_HOOK_SUFFIX, hookName)
            .putLong(packageName + KEY_TIME_SUFFIX, updatedAtMillis)
            .apply()
    }

    fun readAll(context: Context): List<HostHookStatus> =
        scopePackages.map { packageName ->
            HostHookStatus(
                packageName = packageName,
                hookName = prefs(context).getString(packageName + KEY_HOOK_SUFFIX, "").orEmpty(),
                updatedAtMillis = prefs(context).getLong(packageName + KEY_TIME_SUFFIX, 0L),
            )
        }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object DeviceRegistryStore {
    private const val PREFS = "module_device_registry"
    private const val KEY_ADDRESS = "address"
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_EXCEPTION_NAMES = "exception_names"
    /** MAC→型号 持久化缓存（独立 prefs，改名后仍能识别） */
    private const val PREFS_MAC_PROFILE = "mac_profile_cache"

    val defaultSupportedNames: Set<String> = DeviceProfiles.defaultNames

    fun save(context: Context, address: String, profileId: String) {
        prefs(context).edit()
            .putString(KEY_ADDRESS, normalizeAddress(address))
            .putString(KEY_PROFILE_ID, profileId)
            .apply()
    }

    fun saveExceptionNames(context: Context, names: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_EXCEPTION_NAMES, names.normalizedNames())
            .apply()
    }

    fun addExceptionName(context: Context, name: String) {
        val normalized = DeviceNameRuleState.normalizeDeviceName(name)
        if (normalized.isBlank() || DeviceNameRuleState.isAirPodsName(normalized)) return
        saveExceptionNames(context, readExceptionNames(context) + normalized)
    }

    fun removeExceptionName(context: Context, name: String) {
        val normalized = DeviceNameRuleState.normalizeDeviceName(name)
        saveExceptionNames(context, readExceptionNames(context) - normalized)
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_ADDRESS)
            .remove(KEY_PROFILE_ID)
            .apply()
    }

    fun read(context: Context): BoundDevice? {
        val address = prefs(context).getString(KEY_ADDRESS, null)?.let(::normalizeAddress)
        val profileId = prefs(context).getString(KEY_PROFILE_ID, null)
        if (address.isNullOrBlank() || profileId.isNullOrBlank()) return null
        if (DeviceProfiles.get(profileId) == null) return null
        return BoundDevice(address, profileId)
    }

    fun readNameRules(context: Context): DeviceNameRuleState =
        DeviceNameRuleState(
            defaultNames = defaultSupportedNames.normalizedNames(),
            exceptionNames = readExceptionNames(context),
            profileId = DeviceProfiles.SONY_WF1000XM3,
        )

    fun readExceptionNames(context: Context): Set<String> =
        prefs(context)
            .getStringSet(KEY_EXCEPTION_NAMES, emptySet())
            .orEmpty()
            .normalizedNames()

    fun matchesSupportedName(context: Context?, name: String?): Boolean {
        if (DeviceNameRuleState.isAirPodsName(name)) return false
        val defaultMatch = DeviceProfiles.findByName(name) != null
            || DeviceNameRuleState.normalizeDeviceName(name) in defaultSupportedNames.normalizedNames()
        if (defaultMatch) return true
        if (context == null) return false
        return runCatching { readNameRules(context).matches(name) }.getOrDefault(false)
    }

    fun normalizeAddress(address: String): String =
        address.trim().uppercase(Locale.US)

    // ==================== MAC→型号 持久化绑定（跨进程单一真源） ====================
    // 首次连接按蓝牙名识别到型号后，把 MAC 落到「该型号目录」下（一个 MAC 一个文件）；
    // 之后无论蓝牙名怎么改，都靠 MAC 遍历型号目录反查型号（识别不再依赖名称）。
    //
    // 关键修复：原实现直接写 `context.getSharedPreferences`，而调用方全部运行在
    //   hook 侧进程（蓝牙进程 / melody 宿主进程 / wirelesssettings 进程），
    //   各进程私有 prefs 互不可见 → 绑定等于没落盘。
    //   现统一经 [DeviceRegistryProvider] 落到**模块私有目录**
    //   `files/bindings/<型号>/<MAC>`；Provider 由 forceQueryable 暴露，任意进程可读。

    /** 缓存某个已识别 MAC 对应的型号（profileId）。address 需为原始蓝牙地址。 */
    fun saveMacProfile(context: Context, address: String, profileId: String) {
        val mac = normalizeMacOrNull(address) ?: return
        if (DeviceProfiles.get(profileId) == null) return
        // 首选：经 Provider 落到共享目录（跨进程可见）。
        if (saveMacProfileViaProvider(context, mac, profileId)) return
        // 兜底：Provider 不可用时退回本进程 prefs（仅同进程可见）。
        macProfilePrefs(context).edit().putString(mac, profileId).apply()
    }

    /** 按 MAC 反查缓存型号（profileId），未命中返回 null。 */
    fun profileIdForMac(context: Context, address: String): String? {
        val mac = normalizeMacOrNull(address) ?: return null
        profileIdForMacViaProvider(context, mac)?.let { return it }
        // 兜底①：直接走 [DeviceBindingStore]（仅**同进程**有效——本进程能读模块 filesDir）。
        //   宿主进程（com.oplus.melody 等）读不到模块 filesDir，唯一跨进程通道是上面的 Provider；
        //   Provider 由 AndroidManifest 的 forceQueryable 暴露，不受包可见性限制。
        DeviceBindingStore.profileIdForMac(context, mac)?.let { hit ->
            if (DeviceProfiles.get(hit) != null) {
                // 回写本进程私有 prefs 做二级缓存（同进程下次免查询）。
                runCatching { macProfilePrefs(context).edit().putString(mac, hit).apply() }
                return hit
            }
        }
        // 兜底②：本进程私有 prefs（仅同进程可见）。
        val id = macProfilePrefs(context).getString(mac, null) ?: return null
        return id.takeIf { DeviceProfiles.get(it) != null }
    }

    /** 某型号目录下已绑定的 MAC 列表（供 UI / 调试展示），无则空集。 */
    fun boundMacsFor(context: Context, profileId: String): Set<String> =
        DeviceBindingStore.macsFor(context, profileId)

    private fun saveMacProfileViaProvider(context: Context, mac: String, profileId: String): Boolean =
        runCatching {
            if (!RegistryContract.isProviderAvailable(context)) return false
            val extras = Bundle().apply {
                putString(RegistryContract.EXTRA_MAC, mac)
                putString(RegistryContract.EXTRA_MAC_PROFILE_ID, profileId)
            }
            context.contentResolver.call(
                RegistryContract.URI,
                RegistryContract.METHOD_SAVE_MAC_PROFILE,
                null,
                extras,
            )?.getBoolean(RegistryContract.EXTRA_MAC_PROFILE_FOUND, false) ?: false
        }.getOrDefault(false)

    private fun profileIdForMacViaProvider(context: Context, mac: String): String? =
        runCatching {
            if (!RegistryContract.isProviderAvailable(context)) return null
            val extras = Bundle().apply { putString(RegistryContract.EXTRA_MAC, mac) }
            context.contentResolver.call(
                RegistryContract.URI,
                RegistryContract.METHOD_GET_MAC_PROFILE,
                null,
                extras,
            )?.getString(RegistryContract.EXTRA_MAC_PROFILE_ID)
        }.getOrNull()

    private fun macProfilePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_MAC_PROFILE, Context.MODE_PRIVATE)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Set<String>.normalizedNames(): Set<String> =
        mapNotNullTo(linkedSetOf()) { name ->
            DeviceNameRuleState.normalizeDeviceName(name)
                .takeIf { it.isNotBlank() && !DeviceNameRuleState.isAirPodsName(it) }
        }
}

/** MAC 规范化：兼容 "XX:XX:XX:XX:XX:XX" / "XX-XX-XX-XX-XX-XX"，非 12 位十六进制返回 null。 */
internal fun normalizeMacOrNull(address: String?): String? {
    val a = address.orEmpty().trim()
        .replace("-", "")
        .replace(":", "")
        .uppercase(Locale.US)
    return a.takeIf { it.length == 12 && it.all { c -> c.isDigit() || c in 'A'..'F' } }
}

/**
 * MAC→型号 绑定的「模块私有目录」实现（单一真源）。
 *
 * 存储位置（**模块私有目录**，模块与用户均无需任何存储权限）：
 * ```
 * files/bindings/<型号目录>/<MAC>
 * ```
 * 每个型号一个目录，目录内每个已绑定 MAC 一个文件（文件名即 MAC，无冒号大写）。
 * 首次连接按蓝牙名识别型号 → 写入该型号目录；此后改名也能靠 MAC 反查型号。
 *
 * 读取路径有两条，因两个进程权限不同：
 *  - 模块 UI / Provider 进程：直接 File IO（本文件 [save] / [profileIdForMac] / [macsFor]）。
 *  - 宿主 melody 进程（hook 侧，无模块 filesDir 访问权）：经 [DeviceRegistryProvider] 的
 *    save_mac_profile / get_mac_profile 中转；Provider 由 AndroidManifest 的 forceQueryable
 *    声明暴露，不受 Android 11+ 包可见性限制（与 [DeviceImageStore] 同范式）。
 *
 * 旧版遗留绑定（公共目录 `Download/MelodyPlus/bindings/<profileId>/<MAC>`，MediaStore 管理）
 * 在 [resolveOrMigrate] / [migrateAllLegacy] 中**懒迁移**到私有目录并清理旧文件，
 * 保证旧用户平滑过渡。
 */
object DeviceBindingStore {
    private const val TAG = "MelodyPlus"
    /** 模块私有目录下的绑定子目录（files/bindings）。 */
    private const val SUBDIR = "bindings"
    /** 旧版公共目录（Download/MelodyPlus/bindings），仅用于懒迁移兼容。 */
    private const val LEGACY_RELATIVE_SUBDIR = "MelodyPlus/bindings"
    /** 旧公共目录展示提示（仅迁移/兼容说明用）。 */
    const val LEGACY_PUBLIC_DIR_HINT = "Download/MelodyPlus/bindings"
    /** 面向用户展示的存储说明（UI 文案用）。 */
    const val STORAGE_HINT = "模块私有目录"

    /** 该型号绑定的私有目录：files/bindings/<profileId>。 */
    private fun privateDir(context: Context, profileId: String): File =
        File(context.filesDir, "$SUBDIR/$profileId")

    /** 绑定文件名：`<MAC>`（纯文件名即 MAC，无扩展名）。 */
    private fun fileNameFor(mac: String): String = mac.uppercase(Locale.US)

    /** 目录内匹配某 MAC 前缀的绑定文件（含历史 "(n)" 重名残留）。 */
    private fun fileEntries(context: Context, mac: String, profileId: String): List<File> = runCatching {
        val target = mac.uppercase(Locale.US)
        privateDir(context, profileId).listFiles()?.filter { f ->
            val base = f.name.removeSuffix(".txt").substringBefore(' ').uppercase(Locale.US)
            base == target || base.startsWith(target)
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** 私有目录下是否已有该 MAC 的绑定文件。 */
    private fun physicalBindingExists(context: Context, mac: String, profileId: String): Boolean =
        fileEntries(context, mac, profileId).isNotEmpty()

    /** 保存/幂等：写入私有目录绑定文件（已存在则不重复建立、不删改任何文件）。返回是否可用。 */
    fun save(context: Context, mac: String, profileId: String): Boolean = runCatching {
        val normalized = normalizeMacOrNull(mac) ?: return false
        if (profileId.isBlank() || DeviceProfiles.get(profileId) == null) return false
        // [同 MAC 多目录残留] 以 MAC 为唯一键：写入新归属前，清理该 MAC 在**其它型号目录**下的旧绑定。
        purgeOtherProfiles(context, normalized, profileId)
        val dir = privateDir(context, profileId)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        val f = File(dir, fileNameFor(normalized))
        if (f.exists() && f.length() > 0) {
            modLog("I", "mac binding saved(private) mac=$normalized profile=$profileId ok=true existing")
            return true
        }
        f.writeText(normalized)
        val ok = f.exists() && f.length() > 0
        modLog("I", "mac binding saved(private) mac=$normalized profile=$profileId ok=$ok")
        ok
    }.getOrElse {
        modLogT("W", "save mac binding failed mac=$mac profile=$profileId", it)
        false
    }

    /** 某型号目录下是否已绑定该 MAC（仅私有目录）。 */
    fun contains(context: Context, mac: String, profileId: String): Boolean = runCatching {
        val normalized = normalizeMacOrNull(mac) ?: return false
        physicalBindingExists(context, normalized, profileId)
    }.getOrDefault(false)

    /** 按 MAC 遍历私有型号目录反查型号（profileId），未命中返回 null。 */
    fun profileIdForMac(context: Context, mac: String): String? = runCatching {
        val normalized = normalizeMacOrNull(mac) ?: return null
        val hits = linkedSetOf<String>()
        File(context.filesDir, SUBDIR).listFiles()?.forEach { dir ->
            if (dir.isDirectory && DeviceProfiles.get(dir.name) != null &&
                fileEntries(context, normalized, dir.name).isNotEmpty()
            ) {
                hits.add(dir.name)
            }
        }
        when (hits.size) {
            0 -> null
            1 -> hits.first()
            else -> {
                val chosen = hits.sorted().first()
                modLog("W", "binding_conflict mac=$normalized hits=${hits.joinToString(",")} chosen=$chosen")
                chosen
            }
        }
    }.getOrNull()

    /**
     * 反查型号；私有目录未命中时，先尝试从旧公共目录**懒迁移**再反查。
     * 供 Provider（模块进程，可能持有 MANAGE_EXTERNAL_STORAGE）调用，平滑兼容旧绑定。
     */
    fun resolveOrMigrate(context: Context, mac: String): String? {
        profileIdForMac(context, mac)?.let { return it }
        if (migrateAllLegacy(context) > 0) return profileIdForMac(context, mac)
        return null
    }

    /** 某型号目录下已绑定的 MAC 集合（私有目录）。 */
    fun macsFor(context: Context, profileId: String): Set<String> = runCatching {
        val result = linkedSetOf<String>()
        privateDir(context, profileId).listFiles()?.forEach { f ->
            val base = f.name.removeSuffix(".txt").substringBefore(' ')
            normalizeMacOrNull(base)?.let { result.add(it) }
        }
        result
    }.getOrElse {
        modLogT("W", "list bound macs failed profile=$profileId", it)
        emptySet()
    }

    /** 清理某 MAC 在「除 [keepProfileId] 外」所有私有型号目录下的绑定。 */
    private fun purgeOtherProfiles(context: Context, mac: String, keepProfileId: String): List<String> {
        val normalized = normalizeMacOrNull(mac) ?: return emptyList()
        val stale = linkedSetOf<String>()
        File(context.filesDir, SUBDIR).listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name == keepProfileId) return@forEach
            if (DeviceProfiles.get(dir.name) == null) return@forEach
            if (fileEntries(context, normalized, dir.name).isNotEmpty()) {
                remove(context, normalized, dir.name)
                stale.add(dir.name)
            }
        }
        if (stale.isNotEmpty()) {
            modLog("I", "mac binding purged stale profiles mac=$normalized keep=$keepProfileId removed=${stale.joinToString(",")}")
        }
        return stale.toList()
    }

    /** 解绑：移除某型号目录下的某 MAC 全部条目，返回删除条数。 */
    fun remove(context: Context, mac: String, profileId: String): Int {
        val normalized = normalizeMacOrNull(mac) ?: return 0
        var n = 0
        fileEntries(context, normalized, profileId).forEach { f ->
            if (runCatching { f.delete() }.getOrDefault(false)) n++
        }
        modLog("I", "mac binding removed(private) mac=$normalized profile=$profileId count=$n")
        return n
    }

    // ==================== 旧公共目录懒迁移（兼容） ====================

    /** 旧公共目录 Download/MelodyPlus/bindings/<profileId> 的物理 File。 */
    private fun legacyDir(profileId: String): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "$LEGACY_RELATIVE_SUBDIR/$profileId",
    )

    /**
     * 一次性懒迁移：把旧公共目录里 [profileId] 下的绑定搬进私有目录并清理旧文件。
     * 幂等（私有已存在同名即跳过保存但仍清旧文件）。无存储权限时静默返回 0。
     * @return 迁移的条目数
     */
    fun migrateLegacyForProfile(context: Context, profileId: String): Int = runCatching {
        // 旧公共目录为 0770（media_rw），读取需 MANAGE_EXTERNAL_STORAGE；未授权则跳过（新用户无遗留）。
        if (!context.hasAllFilesAccess()) return 0
        if (DeviceProfiles.get(profileId) == null) return 0
        val dir = legacyDir(profileId)
        if (!dir.isDirectory) return 0
        var migrated = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val mac = normalizeMacOrNull(f.name.substringBefore(' ').removeSuffix(".txt")) ?: return@forEach
            if (save(context, mac, profileId)) {
                migrated++
                runCatching { f.delete() }
            }
        }
        if (migrated > 0) modLog("I", "mac binding migrated legacy profile=$profileId count=$migrated")
        migrated
    }.getOrElse {
        modLogT("W", "migrate legacy bindings failed profile=$profileId", it)
        0
    }

    /**
     * 全量懒迁移：遍历旧公共目录 bindings 下所有**已知型号**目录，逐型号迁移。
     * @return 迁移的总条目数
     */
    fun migrateAllLegacy(context: Context): Int = runCatching {
        if (!context.hasAllFilesAccess()) return 0
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            LEGACY_RELATIVE_SUBDIR,
        )
        var total = 0
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && DeviceProfiles.get(dir.name) != null) {
                total += migrateLegacyForProfile(context, dir.name)
            }
        }
        if (total > 0) modLog("I", "mac binding migrated legacy total=$total")
        total
    }.getOrElse { 0 }
}

class DeviceRegistryProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var rejected = false
        var reason: String? = null
        try {
            return handleCall(method, arg, extras) { r, why -> rejected = true; reason = why }
        } finally {
            ProviderStats.record(method, android.os.SystemClock.elapsedRealtime() - startedAt, rejected, reason)
        }
    }

    /** 实际分派（原 `call` 主体），[onReject] 用于把拒绝原因回传给计时埋点。 */
    private fun handleCall(
        method: String,
        arg: String?,
        extras: Bundle?,
        onReject: (Boolean, String?) -> Unit,
    ): Bundle? {
        if (!isAllowedCaller()) {
            onReject(true, "not-whitelisted")
            return Bundle.EMPTY
        }
        // 高权限方法统一在此拦截（单一真源见 RegistryMethodPolicy）：
        //  ① 调用方必须持有 signature 级 ACCESS_REGISTRY 权限（静态第二道防线）；
        //  ② UID 包集合必须全部在白名单（运行时第一道防线）。
        // 两者缺一即拒，避免仅靠白名单「任取其一」被绕过。
        if (!RegistryMethodPolicy.authorize(
                method = method,
                hasSignaturePermission = hasSignaturePermission(),
                strictPackage = strictCallerPackage(),
            )
        ) {
            onReject(true, "high-priv-denied")
            return Bundle.EMPTY
        }
        if (method == RegistryContract.METHOD_GET_PROVIDER_STATS) {
            val s = ProviderStats.snapshot()
            return Bundle().apply {
                putLong(RegistryContract.EXTRA_STATS_TOTAL, s.total)
                putLong(RegistryContract.EXTRA_STATS_REJECTED, s.rejected)
                putLong(RegistryContract.EXTRA_STATS_DROPPED_LOGS, s.droppedLogs)
                putLong(RegistryContract.EXTRA_STATS_SLOW, s.slow)
                putString(RegistryContract.EXTRA_STATS_LAST_REASON, s.lastReason)
                putLong(RegistryContract.EXTRA_STATS_MAX_ELAPSED_MS, s.maxElapsedMs)
            }
        }
        return when (method) {
            RegistryContract.METHOD_GET_BINDING -> {
                val binding = context?.let(DeviceRegistryStore::read) ?: return Bundle.EMPTY
                Bundle().apply {
                    putString(RegistryContract.EXTRA_ADDRESS, binding.address)
                    putString(RegistryContract.EXTRA_PROFILE_ID, binding.profileId)
                }
            }
            RegistryContract.METHOD_GET_NAME_RULES -> {
                val rules = context?.let(DeviceRegistryStore::readNameRules) ?: return Bundle.EMPTY
                Bundle().apply {
                    putString(RegistryContract.EXTRA_PROFILE_ID, rules.profileId)
                    putStringArrayList(RegistryContract.EXTRA_DEFAULT_NAMES, ArrayList(rules.defaultNames))
                    putStringArrayList(RegistryContract.EXTRA_EXCEPTION_NAMES, ArrayList(rules.exceptionNames))
                }
            }
            RegistryContract.METHOD_REPORT_STATUS -> {
                val ctx = context ?: return Bundle.EMPTY
                val packageName = allowedCallerPackage() ?: return Bundle.EMPTY
                val hookName = extras?.getString(RegistryContract.EXTRA_HOOK_NAME).orEmpty()
                    .ifBlank { packageName }
                HookStatusStore.report(ctx, packageName, hookName)
                Bundle().apply {
                    putLong(RegistryContract.EXTRA_UPDATED_AT, System.currentTimeMillis())
                }
            }
            RegistryContract.METHOD_GET_STATUS -> {
                val statuses = context?.let(HookStatusStore::readAll).orEmpty()
                Bundle().apply {
                    putStringArrayList(
                        RegistryContract.EXTRA_STATUS_PACKAGES,
                        ArrayList(statuses.map { it.packageName }),
                    )
                    putStringArrayList(
                        RegistryContract.EXTRA_STATUS_HOOKS,
                        ArrayList(statuses.map { it.hookName }),
                    )
                    putStringArrayList(
                        RegistryContract.EXTRA_STATUS_TIMESTAMPS,
                        ArrayList(statuses.map { it.updatedAtMillis.toString() }),
                    )
                }
            }
            RegistryContract.METHOD_APPEND_LOG -> {
                val ctx = context ?: return Bundle.EMPTY
                val packageName = allowedCallerPackage()
                // [P3 拆分] 输入校验委托给 HookLogStore.normalizeAppendInput（纯函数、可单测）；
                // 该函数内已按 MAX_MESSAGE_CHARS 钳制并处理 level/source 默认值。
                val entry = HookLogStore.normalizeAppendInput(
                    level = extras?.getString(RegistryContract.EXTRA_LOG_LEVEL),
                    source = extras?.getString(RegistryContract.EXTRA_LOG_SOURCE),
                    message = extras?.getString(RegistryContract.EXTRA_LOG_MESSAGE),
                    defaultSource = packageName ?: "unknown",
                )
                if (entry != null) {
                    HookLogStore.append(ctx, entry)
                }
                Bundle.EMPTY
            }
            RegistryContract.METHOD_GET_LOGS -> {
                val ctx = context
                var logs = ctx?.let(HookLogStore::readAll).orEmpty()
                // 兜底：日志为空时，把已上报的 Hook 状态转成日志，保证关于页能看到历史
                if (logs.isEmpty() && ctx != null) {
                    val statuses = HookStatusStore.readAll(ctx)
                    val realStatuses = statuses.filter { it.hookName.isNotBlank() }
                    if (realStatuses.isNotEmpty()) {
                        realStatuses.sortedBy { it.updatedAtMillis }.forEach { st ->
                            HookLogStore.append(
                                ctx,
                                HookLogEntry(
                                    level = "I",
                                    source = st.hookName,
                                    message = "已激活 (${st.packageName})",
                                    timestamp = st.updatedAtMillis,
                                ),
                            )
                        }
                        logs = ctx.let(HookLogStore::readAll)
                    }
                }
                Bundle().apply {
                    putString(
                        RegistryContract.EXTRA_LOGS_JSON,
                        org.json.JSONArray().apply {
                            logs.forEach { put(it.toJson()) }
                        }.toString(),
                    )
                }
            }
            RegistryContract.METHOD_EXPORT_LOGS -> {
                val ctx = context ?: return Bundle.EMPTY
                val result = LogExporter.export(ctx)
                Bundle().apply {
                    result.path?.let { putString(RegistryContract.EXTRA_EXPORT_PATH, it) }
                    putString(RegistryContract.EXTRA_EXPORT_TEXT, result.text)
                }
            }
            RegistryContract.METHOD_CLEAR_LOGS -> {
                context?.let(HookLogStore::clear)
                Bundle.EMPTY
            }
            RegistryContract.METHOD_INJECT_DEBUG_POPUP -> {
                val ctx = context ?: return Bundle.EMPTY
                // 注入一台"官方耳机"OPPO Enco X3；地址优先用真实绑定 MAC，无绑定才退回占位。
                val request = DebugInjectRequest.resolve(
                    extras = extras,
                    boundAddress = DeviceRegistryStore.read(ctx)?.address,
                )
                val injectIntent = Intent(BluetoothPopupContract.ACTION_MODULE_AUDIO_CONNECTED)
                injectIntent.putExtra(BluetoothPopupContract.EXTRA_MODULE_TRIGGER, true)
                injectIntent.putExtra(BluetoothPopupContract.EXTRA_DEVICE_NAME, request.name)
                injectIntent.putExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS, request.address)
                injectIntent.putExtra(BluetoothPopupContract.EXTRA_PROFILE_ID, request.profileId)
                injectIntent.putExtra(BluetoothPopupContract.EXTRA_DEBUG_PREVIEW, true)
                val result = runCatching {
                    ctx.sendBroadcast(injectIntent)
                    true
                }.getOrElse {
                    modLogT("W", "debug popup inject broadcast failed", it)
                    false
                }
                HookLogStore.append(
                    ctx,
                    HookLogEntry(
                        level = if (result) "I" else "E",
                        source = "DebugInject",
                        message = if (result) {
                            "DEBUG_INJECT_SENT action=${BluetoothPopupContract.ACTION_MODULE_AUDIO_CONNECTED} " +
                                "name=${request.name} address=${request.address} " +
                                "profile=${request.profileId} debug=true"
                        } else {
                            "DEBUG_INJECT_SENT failed name=${request.name} address=${request.address}"
                        },
                    ),
                )
                Bundle().apply {
                    putBoolean(RegistryContract.EXTRA_DEBUG_RESULT, result)
                }
            }
            RegistryContract.METHOD_GET_DEVICE_IMAGE -> {
                val ctx = context ?: return Bundle.EMPTY
                val request = DeviceImageRequest.resolve(arg, extras) ?: return Bundle.EMPTY
                val bytes = DeviceImageStore.read(ctx, request.profileId, request.slot)
                Bundle().apply {
                    putBoolean(RegistryContract.EXTRA_DEVICE_IMAGE_FOUND, bytes != null)
                    bytes?.let { putByteArray(RegistryContract.EXTRA_DEVICE_IMAGE_BYTES, it) }
                }
            }
            RegistryContract.METHOD_GET_BUILTIN_IMAGE -> {
                val ctx = context ?: return Bundle.EMPTY
                val request = DeviceImageRequest.resolve(arg, extras) ?: return Bundle.EMPTY
                // 用「模块进程」自身的 AssetManager 读 assets/device_images（hook 侧读不到）。
                val bytes = DeviceImageStore.readBuiltinAsset(ctx, request.profileId, request.slot)
                Bundle().apply {
                    putBoolean(RegistryContract.EXTRA_DEVICE_IMAGE_FOUND, bytes != null)
                    bytes?.let { putByteArray(RegistryContract.EXTRA_DEVICE_IMAGE_BYTES, it) }
                }
            }
            RegistryContract.METHOD_SAVE_MAC_PROFILE -> {
                val ctx = context ?: return Bundle.EMPTY
                val mac = extras?.getString(RegistryContract.EXTRA_MAC)
                val profileId = extras?.getString(RegistryContract.EXTRA_MAC_PROFILE_ID)
                val ok = mac != null && profileId != null && DeviceBindingStore.save(ctx, mac, profileId)
                Bundle().apply { putBoolean(RegistryContract.EXTRA_MAC_PROFILE_FOUND, ok) }
            }
            RegistryContract.METHOD_GET_MAC_PROFILE -> {
                val ctx = context ?: return Bundle.EMPTY
                val mac = extras?.getString(RegistryContract.EXTRA_MAC)
                val profileId = mac?.let { DeviceBindingStore.resolveOrMigrate(ctx, it) }
                Bundle().apply {
                    if (profileId != null) {
                        putBoolean(RegistryContract.EXTRA_MAC_PROFILE_FOUND, true)
                        putString(RegistryContract.EXTRA_MAC_PROFILE_ID, profileId)
                    } else {
                        putBoolean(RegistryContract.EXTRA_MAC_PROFILE_FOUND, false)
                    }
                }
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun isAllowedCaller(): Boolean {
        return allowedCallerPackage() != null
    }

    private fun allowedCallerPackage(): String? {
        val ctx = context ?: return null
        val uid = Binder.getCallingUid()
        // root(0) / shell(2000) / 本模块自身：直接放行（策略见 RegistryCallerPolicy）。
        RegistryCallerPolicy.specialUidLabel(uid, android.os.Process.myUid())?.let { return it }
        val packages = ctx.packageManager.getPackagesForUid(uid).orEmpty().toList()
        return RegistryCallerPolicy.resolveLoose(packages)
    }

    /**
     * 严格 caller 校验（用于清空/导出日志、调试注入等高权限操作）。
     *
     * [allowedCallerPackage] 用 `firstOrNull` 取 UID 里任一命中包名代表调用者，shared UID 下
     * 同 UID 若还包含非白名单包，不应把身份认证建立在这个"任取其一"上。此处要求：
     *   - UID 对应的包集合非空，且**全部**属于 [RegistryCallerPolicy.allowedCallers]；
     *   - root / shell / 本模块自身直接放行。
     * 返回真实包名（全部受限时）或 root/shell/自身标识；不满足返回 null。
     */
    private fun strictCallerPackage(): String? {
        val ctx = context ?: return null
        val uid = Binder.getCallingUid()
        RegistryCallerPolicy.specialUidLabel(uid, android.os.Process.myUid())?.let { return it }
        val packages = ctx.packageManager.getPackagesForUid(uid).orEmpty().toList()
        return RegistryCallerPolicy.resolveStrict(packages)
    }

    /**
     * 调用方是否持有 signature 级 [RegistryMethodPolicy.HIGH_PRIV_PERMISSION]。
     *
     * 用于高权限方法的静态第二道防线：签名不同者即便 UID 命中白名单也无该权限。
     * root(0) 视为持有（adb/root 调试场景，与白名单里的 specialUidLabel 对齐）。
     */
    private fun hasSignaturePermission(): Boolean {
        val ctx = context ?: return false
        val uid = Binder.getCallingUid()
        if (uid == 0) return true
        return ctx.checkCallingPermission(RegistryMethodPolicy.HIGH_PRIV_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    // 白名单与判定逻辑已移至 [RegistryCallerPolicy]（P3 拆分，可单测）。
}

class ModuleDeviceRegistry(private val context: Context?) {
    fun getNameRules(): DeviceNameRuleState {
        val ctx = context ?: return defaultNameRules()
        if (RegistryCircuit.shouldSkip()) return defaultNameRules()
        if (!RegistryContract.isProviderAvailable(ctx)) return defaultNameRules()
        return runCatching {
            val bundle = ctx.contentResolver.call(
                RegistryContract.URI,
                RegistryContract.METHOD_GET_NAME_RULES,
                null,
                null,
            ) ?: return@runCatching null
            val profileId = bundle.getString(RegistryContract.EXTRA_PROFILE_ID) ?: DeviceProfiles.SONY_WF1000XM3
            if (DeviceProfiles.get(profileId) == null) return@runCatching null
            DeviceNameRuleState(
                defaultNames = bundle.getStringArrayList(RegistryContract.EXTRA_DEFAULT_NAMES).orEmpty().toSet(),
                exceptionNames = bundle.getStringArrayList(RegistryContract.EXTRA_EXCEPTION_NAMES).orEmpty().toSet(),
                profileId = profileId,
            )
        }.onFailure {
            RegistryCircuit.logFailure(it)
        }.getOrNull() ?: defaultNameRules()
    }

    fun getBoundDevice(): BoundDevice? {
        val ctx = context ?: return null
        if (RegistryCircuit.shouldSkip()) return null
        if (!RegistryContract.isProviderAvailable(ctx)) return null
        val result = runCatching {
            val bundle = ctx.contentResolver.call(
                RegistryContract.URI,
                RegistryContract.METHOD_GET_BINDING,
                null,
                null,
            ) ?: return null
            val address = bundle.getString(RegistryContract.EXTRA_ADDRESS)?.let(DeviceRegistryStore::normalizeAddress)
            val profileId = bundle.getString(RegistryContract.EXTRA_PROFILE_ID)
            if (address.isNullOrBlank() || profileId.isNullOrBlank()) return null
            if (DeviceProfiles.get(profileId) == null) return null
            BoundDevice(address, profileId)
        }.onFailure {
            RegistryCircuit.logFailure(it)
        }.getOrNull()
        if (result != null) RegistryCircuit.logSuccess()
        return result
    }

    fun isModuleSupported(device: BluetoothDevice?): Boolean {
        val name = bluetoothDeviceName(device)
        return isModuleSupportedName(name)
    }

    fun isModuleSupportedAddress(address: String?): Boolean {
        val normalized = address?.let(DeviceRegistryStore::normalizeAddress) ?: return false
        val binding = getBoundDevice() ?: return false
        return binding.address == normalized && binding.profile != null
    }

    fun boundProfileForAddress(address: String?): DeviceProfile? {
        val normalized = address?.let(DeviceRegistryStore::normalizeAddress) ?: return null
        val binding = getBoundDevice() ?: return null
        return if (binding.address == normalized) binding.profile else null
    }

    fun isModuleSupportedName(name: String?): Boolean {
        if (DeviceNameRuleState.isAirPodsName(name)) return false
        return DeviceProfiles.findByName(name) != null || getNameRules().matches(name)
    }

    fun profileForName(name: String?): DeviceProfile? =
        DeviceProfiles.findByName(name)
            ?: DeviceProfiles.get(getNameRules().profileId).takeIf { isModuleSupportedName(name) }

    companion object {
        private const val TAG = "MelodyPlus"

        private fun defaultNameRules(): DeviceNameRuleState =
            DeviceNameRuleState(
                defaultNames = DeviceRegistryStore.defaultSupportedNames,
                exceptionNames = emptySet(),
                profileId = DeviceProfiles.SONY_WF1000XM3,
            )

        private fun bluetoothDeviceName(device: BluetoothDevice?): String? =
            runCatching { device?.name }.getOrNull()
    }
}

fun Context.hasBluetoothConnectPermission(): Boolean =
    checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/**
 * 是否已授予「所有文件访问」（MANAGE_EXTERNAL_STORAGE）。
 *
 * 该权限不是运行时权限，无法用 requestPermissions 弹窗；Android 11+ 判定走
 * [Environment.isExternalStorageManager]。未授予时绑定落盘回退 MediaStore 路径。
 * Android 10 及以下恒为 true（该版本无此机制）。
 */
fun Context.hasAllFilesAccess(): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        true
    }

/**
 * Provider 调用熔断器（P1-1 修复）。
 *
 * 【背景】[RegistryContract.isProviderAvailable] 对跨进程 caller 采取「乐观放行」
 * （因 Android 11+ 包可见性会让 resolveContentProvider 误判），真实可达性只能由
 * contentResolver.call 的抛错体现。宿主 hook 热路径（isModuleInfoScope 被 6 个
 * DeviceInfo setter hook 调用）在 Provider 进程未存活时，每次 call 都会抛
 * `IllegalArgumentException: Unknown authority`，日志实测 459 条刷屏。
 *
 * 【策略】连续失败达 [FAIL_THRESHOLD] 次即熔断 [COOLDOWN_MS]，期间所有调用直接短路，
 * 不再发起跨进程 call、不再打日志；冷却后放行一次探测，成功即闭合。
 * 单次成功调用会清零失败计数（Provider 恢复后立即回到正常路径）。
 */
object RegistryCircuit {
    private const val FAIL_THRESHOLD = 8
    private const val COOLDOWN_MS = 15_000L

    private var consecutiveFailures = 0
    private var openUntilMs = 0L
    @Volatile private var lastLogAtMs = 0L

    /** 是否处于熔断冷却期（true = 调用方应直接短路返回，勿发起跨进程 call）。 */
    @Synchronized
    fun shouldSkip(): Boolean = android.os.SystemClock.elapsedRealtime() < openUntilMs

    /** 调用成功：清空失败计数并闭合熔断。 */
    @Synchronized
    fun logSuccess() {
        consecutiveFailures = 0
        openUntilMs = 0L
    }

    /** 调用失败：累计失败；达阈值则开启冷却，并最多每 [COOLDOWN_MS] 打一条降噪日志。 */
    @Synchronized
    fun logFailure(t: Throwable) {
        record(false)
        // 降噪：仅在熔断边缘打一次 W，避免每次失败一条日志。
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLogAtMs >= COOLDOWN_MS) {
            lastLogAtMs = now
            com.melody.melodyplus.hook.modLogT("W", "registry provider unreachable, circuit open", t)
        }
    }

    /** 通用记账（供日志上报等无异常对象的调用点使用）。 */
    @Synchronized
    fun record(ok: Boolean) {
        if (ok) {
            logSuccess()
            return
        }
        consecutiveFailures++
        if (consecutiveFailures >= FAIL_THRESHOLD) {
            openUntilMs = android.os.SystemClock.elapsedRealtime() + COOLDOWN_MS
            consecutiveFailures = 0
        }
    }
}
