package com.melody.melodyplus.bridge
import com.melody.melodyplus.hook.modLogT

import com.melody.melodyplus.hook.modLog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 每型号「可替换」耳机图片仓库（全型号统一，单图模式）。
 *
 * 存储位置（**模块私有目录**，模块与用户均无需任何存储权限）：
 * ```
 * files/device_images/<profileId>/main.png
 * ```
 * 槽位（[DeviceImageAssets]）：`main` 主图（三合一合成图：左耳 + 右耳 + 耳机仓）。
 *
 * 读取路径有两条，因为两个进程的权限不同：
 *  - 模块 UI 进程：直接 File IO（本文件 [read] / [save] / [delete]）。
 *  - 宿主 melody 进程（hook 侧，无模块 filesDir 访问权）：
 *    经 `DeviceRegistryProvider` 的 `get_device_image` 中转（携带 slot 参数）。
 *
 * 旧版遗留图（公共目录 `Download/MelodyPlus/images/<profileId>/main.png`，MediaStore 管理）
 * 会在首次读取时**懒迁移**到私有目录并清理旧文件（[legacyRead]），保证旧用户平滑过渡。
 *
 * 未提供用户图片时，hook 侧回退到模块内置
 * `assets/device_images/<型号目录>/main.png`。
 *
 * 备份说明：`files/` 已在 backup_rules / data_extraction_rules 中整体排除，
 * 私有图不会随备份迁移（设备本地数据）。
 */
object DeviceImageStore {
    private const val TAG = "MelodyPlus"
    /** 模块私有目录下的图片子目录（files/device_images）。 */
    private const val SUBDIR = "device_images"
    /** 旧版公共目录（Download/MelodyPlus/images），仅用于懒迁移兼容。 */
    private const val LEGACY_RELATIVE_SUBDIR = "MelodyPlus/images"
    /** 旧公共目录展示提示（仅迁移日志/兼容说明用）。 */
    const val LEGACY_PUBLIC_DIR_HINT = "Download/MelodyPlus/images"
    /** 面向用户展示的存储说明（UI 文案用）。 */
    const val STORAGE_HINT = "模块私有目录"

    /**
     * 存储字节上限（Binder 单次事务约 1MB）：用户图经 Provider 回传，
     * 超过该阈值在落盘前先等比压缩（JPEG 质量阶梯），保证宿主侧始终能取到图。
     */
    private const val MAX_STORED_BYTES = 900_000
    private const val MAX_DIMENSION = 1600

    /**
     * 某槽位的文件名。
     *
     * 【单图模式】全链路只读写 `main.png` 一张（三合一合成图）。
     * `slot` 形参保留仅为兼容历史调用签名，内部恒等于 main，
     * 因此 [save] / [read] / [exists] / [delete] / [listSlots] 全部自动收口到单图。
     */
    fun fileNameFor(slot: String): String = "${DeviceImageAssets.SLOT_MAIN}.png"

    /** 该型号图片的私有目录：files/device_images/<profileId>。 */
    private fun privateDir(context: Context, profileId: String): File =
        File(context.filesDir, "$SUBDIR/$profileId")

    /** 该型号某槽位的私有图片文件。 */
    private fun privateFile(context: Context, profileId: String, slot: String): File =
        File(privateDir(context, profileId), fileNameFor(slot))

    /**
     * 保存（覆盖）某型号的用户图片。模块 UI 进程调用（Provider 进程读同一 filesDir），返回是否成功。
     * @param slot [DeviceImageAssets.SLOT_MAIN] / SLOT_LEFT / SLOT_RIGHT / SLOT_CASE
     */
    fun save(context: Context, profileId: String, slot: String, uri: Uri): Boolean = runCatching {
        if (profileId.isBlank() || !DeviceImageAssets.isValidSlot(slot)) return false
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        if (raw.isEmpty()) return false
        val bytes = normalizeForStorage(raw)
        val ok = writePrivate(context, profileId, slot, bytes)
        if (ok) {
            // 写入私有目录成功后清理旧公共目录同槽位遗留图，避免下次读取时重复迁移。
            runCatching { legacyDelete(context, profileId, slot) }
            modLog("I", "device image saved profile=$profileId slot=$slot ok=true size=${bytes.size}")
        } else {
            modLog("W", "device image saved profile=$profileId slot=$slot ok=false")
        }
        ok
    }.getOrElse {
        modLogT("W", "save device image failed profile=$profileId slot=$slot", it)
        false
    }

    /** 读取某型号某槽位的用户图片字节；不存在返回 null。 */
    fun read(context: Context, profileId: String, slot: String): ByteArray? = runCatching {
        // ① 私有目录（新存储）
        val direct = runCatching {
            privateFile(context, profileId, slot).takeIf { it.isFile }?.readBytes()
        }.getOrNull()
        if (direct != null && direct.isNotEmpty()) return direct
        // ② 旧公共目录懒迁移：读到即转存私有目录 + 清理旧文件（失败静默，下次再试）。
        val legacy = legacyRead(context, profileId, slot) ?: return null
        val normalized = normalizeForStorage(legacy)
        runCatching { writePrivate(context, profileId, slot, normalized) }
        runCatching { legacyDelete(context, profileId, slot) }
        modLog("I", "device image migrated from legacy dir profile=$profileId slot=$slot size=${normalized.size}")
        normalized
    }.getOrElse {
        modLogT("W", "read device image failed profile=$profileId slot=$slot", it)
        null
    }

    /** 是否已有某型号某槽位的用户图片（私有目录优先，兼容未迁移的旧图）。 */
    fun exists(context: Context, profileId: String, slot: String): Boolean = runCatching {
        privateFile(context, profileId, slot).isFile || legacyExists(context, profileId, slot)
    }.getOrDefault(false)

    /**
     * 读取模块「内置」型号图片（assets/device_images/<dir>/<slot>.png）。
     *
     * 只应在【模块进程】调用（AssetManager 指向模块 APK）。hook 侧运行在宿主进程，
     * 其 ClassLoader/AssetManager 指向宿主 APK，读不到模块 assets，必须经
     * [RegistryContract.METHOD_GET_BUILTIN_IMAGE] 由本进程代读（或 ModuleAssets 直读 APK）。
     *
     * 找不到该槽位时返回 null（**不回退 main.png**：避免整机大图混入三图容器）。
     */
    fun readBuiltinAsset(context: Context, profileId: String, slot: String): ByteArray? = runCatching {
        if (profileId.isBlank() || !DeviceImageAssets.isValidSlot(slot)) return null
        val path = DeviceImageAssets.assetManagerPath(profileId, slot)
        context.assets.open(path).use { it.readBytes() }
    }.getOrElse {
        // 某些打不开的路径（不存在）属正常，静默返回 null 由调用方决定。
        null
    }

    /** 删除某型号某槽位的用户图片，返回删除条数；slot=null 时删除该型号全部槽位（含旧公共目录遗留）。 */
    fun delete(context: Context, profileId: String, slot: String? = null): Int = runCatching {
        val n = deletePrivate(context, profileId, slot) + legacyDelete(context, profileId, slot)
        if (n > 0) modLog("I", "device image deleted profile=$profileId slot=$slot count=$n")
        n
    }.getOrElse {
        modLogT("W", "delete device image failed profile=$profileId slot=$slot", it)
        0
    }

    /** 已提供自定义图片的型号集合（去重；私有目录 + 未迁移旧图）。 */
    fun listProfileIds(context: Context): Set<String> = runCatching {
        val result = linkedSetOf<String>()
        File(context.filesDir, SUBDIR).listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.listFiles()?.any { it.isFile } == true) result.add(dir.name)
        }
        result.addAll(legacyListProfileIds(context))
        result
    }.getOrElse {
        modLogT("W", "list device images failed", it)
        emptySet()
    }

    /** 某型号已自定义的槽位集合（私有目录 + 未迁移旧图）。 */
    fun listSlots(context: Context, profileId: String): Set<String> = runCatching {
        val result = linkedSetOf<String>()
        privateDir(context, profileId).listFiles()?.forEach { f ->
            if (f.isFile) {
                f.name.removeSuffix(".png").takeIf { DeviceImageAssets.isValidSlot(it) }?.let { result.add(it) }
            }
        }
        result.addAll(legacyListSlots(context, profileId))
        result
    }.getOrElse { emptySet() }

    // ============================ 私有目录读写 ============================

    /** 原子写：先写 .tmp 再 rename，避免半截文件被读到。 */
    private fun writePrivate(context: Context, profileId: String, slot: String, bytes: ByteArray): Boolean {
        return try {
            val dir = privateDir(context, profileId)
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val target = File(dir, fileNameFor(slot))
            val tmp = File(dir, "${fileNameFor(slot)}.tmp")
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            val renamed = tmp.renameTo(target)
            if (!renamed) tmp.delete()
            renamed
        } catch (t: Throwable) {
            modLogT("W", "write private device image failed profile=$profileId slot=$slot", t)
            false
        }
    }

    private fun deletePrivate(context: Context, profileId: String, slot: String?): Int {
        val dir = privateDir(context, profileId)
        return if (slot != null && DeviceImageAssets.isValidSlot(slot)) {
            if (File(dir, fileNameFor(slot)).delete()) 1 else 0
        } else {
            var n = 0
            dir.listFiles()?.forEach { f -> if (f.isFile && f.delete()) n++ }
            dir.delete() // 空目录一并清掉（非空则删不掉，无害）
            n
        }
    }

    /**
     * 落盘前的尺寸守护：字节超过 [MAX_STORED_BYTES] 时等比压缩（JPEG 质量阶梯），
     * 保证经 Binder（Provider）回传不超限。任何失败都回落原始字节。
     */
    private fun normalizeForStorage(bytes: ByteArray): ByteArray {
        if (bytes.size <= MAX_STORED_BYTES) return bytes
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching bytes
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching bytes
            val scaled = scaleToMax(decoded, MAX_DIMENSION)
            val out = ByteArrayOutputStream()
            var quality = 88
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            while (out.size() > MAX_STORED_BYTES && quality > 55) {
                out.reset()
                quality -= 11
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            if (out.size() in 1..MAX_STORED_BYTES) out.toByteArray() else bytes
        }.getOrDefault(bytes)
    }

    private fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDim || height / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private fun scaleToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    // ============================ 旧公共目录（懒迁移兼容） ============================

    /** 旧公共目录相对路径：Download/MelodyPlus/images/<profileId>。 */
    private fun legacyRelativeDirFor(profileId: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/${DeviceImageAssets.userRelativeDir(profileId)}"

    private fun legacyQueryUri(context: Context, profileId: String, slot: String): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(fileNameFor(slot), "%${legacyRelativeDirFor(profileId)}%")
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(MediaStore.Downloads._ID)
            if (idx >= 0 && cursor.moveToFirst()) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(cursor.getLong(idx).toString()).build()
            } else null
        }
    }

    /** 读旧公共目录图；不存在 / 无权限（严格模式下）返回 null（静默）。 */
    private fun legacyRead(context: Context, profileId: String, slot: String): ByteArray? = runCatching {
        legacyQueryUri(context, profileId, slot)?.let { uri ->
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
    }.getOrNull()

    private fun legacyExists(context: Context, profileId: String, slot: String): Boolean =
        runCatching { legacyQueryUri(context, profileId, slot) != null }.getOrDefault(false)

    /** 删除旧公共目录遗留图，返回删除条数；slot=null 时删除该型号全部。 */
    private fun legacyDelete(context: Context, profileId: String, slot: String?): Int = runCatching {
        val dir = legacyRelativeDirFor(profileId)
        val (where, args) = if (slot != null && DeviceImageAssets.isValidSlot(slot)) {
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?" to
                arrayOf(fileNameFor(slot), "%$dir%")
        } else {
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?" to arrayOf("%$dir%")
        }
        context.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, where, args)
    }.getOrDefault(0)

    private fun legacyListProfileIds(context: Context): Set<String> = runCatching {
        val result = linkedSetOf<String>()
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$LEGACY_RELATIVE_SUBDIR%")
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.RELATIVE_PATH), selection, args, null,
        )?.use { cursor ->
            val relIdx = cursor.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)
            if (relIdx < 0) return@use
            while (cursor.moveToNext()) {
                val rel = cursor.getString(relIdx) ?: continue
                val tail = rel.substringAfter("$LEGACY_RELATIVE_SUBDIR/", "")
                val profileId = tail.substringBefore('/').takeIf { it.isNotBlank() } ?: continue
                result.add(profileId)
            }
        }
        result
    }.getOrDefault(emptySet())

    private fun legacyListSlots(context: Context, profileId: String): Set<String> = runCatching {
        val result = linkedSetOf<String>()
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%${legacyRelativeDirFor(profileId)}%")
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME), selection, args, null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = idx.takeIf { it >= 0 }?.let { cursor.getString(it) } ?: continue
                name.removeSuffix(".png").takeIf { DeviceImageAssets.isValidSlot(it) }?.let { result.add(it) }
            }
        }
        result
    }.getOrDefault(emptySet())
}
