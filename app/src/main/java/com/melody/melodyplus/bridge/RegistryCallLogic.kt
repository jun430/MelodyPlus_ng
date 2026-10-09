package com.melody.melodyplus.bridge

import android.os.Bundle

/**
 * Provider 方法级授权策略（纯逻辑）。
 *
 * P3 拆分：把「哪些方法属于高权限、必须 strict caller」从 [DeviceRegistryProvider] 的
 * when 分支里抽出为单一真源，既可单测，也避免新增方法时忘记收紧授权。
 */
internal object RegistryMethodPolicy {
    /**
     * 高权限方法所需的 **signature 级权限**（静态第二道防线）。
     *
     * 白名单/UID 判定是「运行时第一道防线」，但 Provider `exported=true` 对全系统可见；
     * 一旦白名单漏判或 shared UID 被滥用，就无兜底。本权限 `protectionLevel=signature`：
     * 只有与本模块**同签名**的调用方才会被授予（模块自身持有该 uses-permission），
     * 宿主 App（com.oplus.melody / 蓝牙栈等）不含本权限 → 高权限方法对它们物理不可达。
     */
    const val HIGH_PRIV_PERMISSION = "com.melody.melodyplus.permission.ACCESS_REGISTRY"

    /**
     * 需要**严格** caller 的高权限方法集合：
     *  - 导出日志（落盘）
     *  - 清空日志（破坏性）
     *  - 调试弹窗注入（触发宿主 UI 行为）
     *
     * 其余方法（读绑定 / 读规则 / 上报状态 / 读日志 / 取图 / MAC 绑定读写）走宽松放行。
     */
    val strictCallerMethods: Set<String> = setOf(
        RegistryContract.METHOD_EXPORT_LOGS,
        RegistryContract.METHOD_CLEAR_LOGS,
        RegistryContract.METHOD_INJECT_DEBUG_POPUP,
    )

    fun requiresStrictCaller(method: String): Boolean = method in strictCallerMethods

    /**
     * 高权限方法的**总授权判定**（纯逻辑，可单测）。
     *
     * 两道条件同时满足才放行：
     *  ① `hasSignaturePermission`：调用方持有 [HIGH_PRIV_PERMISSION]（signature 级）；
     *  ② `strictPackage != null`：UID 对应包集合非空且**全部**在白名单。
     *
     * 非高权限方法恒返回 true（其授权由外层宽松判定负责）。
     */
    fun authorize(
        method: String,
        hasSignaturePermission: Boolean,
        strictPackage: String?,
    ): Boolean {
        if (!requiresStrictCaller(method)) return true
        return hasSignaturePermission && strictPackage != null
    }
}

/**
 * Provider 自观测统计（P1-D）。
 *
 * 目的：让「Provider 被频繁调用 / 授权被拒 / 日志被节流丢弃」这类问题可被量化查询，
 * 而不是靠猜。所有计数用 [java.util.concurrent.atomic.AtomicLong]，可被多个 Binder 线程并发更新。
 *
 * ⚠️ 禁止在此处再调用 modLog / append（会递归写入同一 Provider，正是计划文档禁止的行为）；
 * 只在 **logcat**（tag: MelodyPlus-Pvdr）打点，由 adb 或日志页过滤查看。
 */
internal object ProviderStats {
    /** Provider 调用总数。 */
    private val total = java.util.concurrent.atomic.AtomicLong()
    /** 被授权拒绝的调用数（含写日志原因）。 */
    private val rejected = java.util.concurrent.atomic.AtomicLong()
    /** 日志被丢弃数（空消息 / 节流 / 入库失败）。 */
    private val droppedLogs = java.util.concurrent.atomic.AtomicLong()
    /** 超过 [SLOW_MS] 的慢调用数。 */
    private val slow = java.util.concurrent.atomic.AtomicLong()
    private val maxElapsed = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastReason: String = ""
    /** 慢调用阈值：超过即计入 slow 并 logcat 告警。 */
    const val SLOW_MS = 50L
    const val TAG = "MelodyPlus-Pvdr"

    /** 记一次调用结果。[reason] 仅在拒绝时给出（如 "no-permission" / "not-whitelisted"）。 */
    fun record(method: String, elapsedMs: Long, rejectedFlag: Boolean, reason: String?) {
        total.incrementAndGet()
        if (rejectedFlag) {
            rejected.incrementAndGet()
            lastReason = "$method:${reason ?: "denied"}"
        }
        maxElapsed.updateAndGet { maxOf(it, elapsedMs) }
        if (elapsedMs >= SLOW_MS) {
            slow.incrementAndGet()
            // runCatching：纯 JVM 单测里 android.util.Log 是 stub（抛异常），
            // 不能让「打点失败」反过来打断正常调用路径。
            runCatching { android.util.Log.w(TAG, "slow call method=$method elapsed=${elapsedMs}ms") }
        }
    }

    /** 记录一次日志丢弃（空消息或去重节流）。 */
    fun recordLogDropped() {
        droppedLogs.incrementAndGet()
    }

    /** 快照（供 get_provider_stats 返回）。 */
    fun snapshot(): Snapshot = Snapshot(
        total = total.get(),
        rejected = rejected.get(),
        droppedLogs = droppedLogs.get(),
        slow = slow.get(),
        lastReason = lastReason,
        maxElapsedMs = maxElapsed.get(),
    )

    data class Snapshot(
        val total: Long,
        val rejected: Long,
        val droppedLogs: Long,
        val slow: Long,
        val lastReason: String,
        val maxElapsedMs: Long,
    )
}

/**
 * 设备图片请求解析（纯逻辑）。
 *
 * 兼容 profileId 既可由 `extras[EXTRA_DEBUG_PROFILE_ID]`、也可由 `arg` 传入；
 * slot 非法或缺失时回落到 [DeviceImageAssets.SLOT_MAIN]。
 */
internal data class DeviceImageRequest(
    val profileId: String,
    val slot: String,
) {
    companion object {
        /**
         * 纯逻辑核心：不依赖 Android Bundle，便于单测。
         *
         * profileId 优先取 extras，再回落 arg；两者皆空白则返回 null。
         * slot 非法或缺失回落 [DeviceImageAssets.SLOT_MAIN]。
         */
        fun resolveFrom(
            profileIdFromExtras: String?,
            profileIdFromArg: String?,
            slotFromExtras: String?,
        ): DeviceImageRequest? {
            val profileId = profileIdFromExtras?.takeIf { it.isNotBlank() }
                ?: profileIdFromArg?.takeIf { it.isNotBlank() }
                ?: return null
            val slot = slotFromExtras?.takeIf { DeviceImageAssets.isValidSlot(it) }
                ?: DeviceImageAssets.SLOT_MAIN
            return DeviceImageRequest(profileId, slot)
        }

        /** Bundle 适配入口。 */
        fun resolve(arg: String?, extras: Bundle?): DeviceImageRequest? = resolveFrom(
            profileIdFromExtras = extras?.getString(RegistryContract.EXTRA_DEBUG_PROFILE_ID),
            profileIdFromArg = arg,
            slotFromExtras = extras?.getString(RegistryContract.EXTRA_DEVICE_IMAGE_SLOT),
        )
    }
}

/**
 * 调试弹窗注入请求解析（纯逻辑）。
 *
 * 取值优先级：extras 显式值 → 真实绑定 MAC/占位常量。抽为纯函数后，
 * 「没有绑定 MAC 时回落到占位地址」这一行为可脱离 Android 单测锁定。
 */
internal data class DebugInjectRequest(
    val name: String,
    val address: String,
    val profileId: String,
) {
    companion object {
        /**
         * 纯逻辑核心：不依赖 Android Bundle，便于单测。
         * 取值优先级：extras 显式值 → 绑定 MAC → 占位常量。
         */
        fun resolveFrom(
            nameFromExtras: String?,
            addressFromExtras: String?,
            profileIdFromExtras: String?,
            boundAddress: String?,
        ): DebugInjectRequest {
            val name = nameFromExtras?.takeIf { it.isNotBlank() }
                ?: BluetoothPopupContract.DEBUG_DEFAULT_NAME
            val address = addressFromExtras?.takeIf { it.isNotBlank() }
                ?: boundAddress?.takeIf { it.isNotBlank() }
                ?: BluetoothPopupContract.DEBUG_DEFAULT_ADDRESS
            val profileId = profileIdFromExtras?.takeIf { it.isNotBlank() }
                ?: BluetoothPopupContract.DEBUG_DEFAULT_PROFILE_ID
            return DebugInjectRequest(name, address, profileId)
        }

        /** Bundle 适配入口。 */
        fun resolve(extras: Bundle?, boundAddress: String?): DebugInjectRequest = resolveFrom(
            nameFromExtras = extras?.getString(RegistryContract.EXTRA_DEBUG_NAME),
            addressFromExtras = extras?.getString(RegistryContract.EXTRA_DEBUG_ADDRESS),
            profileIdFromExtras = extras?.getString(RegistryContract.EXTRA_DEBUG_PROFILE_ID),
            boundAddress = boundAddress,
        )
    }
}
