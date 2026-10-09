package com.melody.melodyplus.hook

import com.melody.melodyplus.bridge.DeviceImageAssets
import java.io.File
import java.lang.reflect.Field
import java.util.zip.ZipFile

/**
 * 模块自身资产（assets）读取器 —— 供 hook 侧（运行在宿主进程）读取模块 APK 内置图片。
 *
 * 背景 / 为什么不走 Provider：
 *  - hook 代码运行在宿主 `com.oplus.melody(:fg)` 进程，其 `Context.resources` / `AssetManager`
 *    指向**宿主 APK**，`MelodyPanelHook::class.java.classLoader.getResourceAsStream("assets/…")`
 *    读不到模块 assets。
 *  - 尝试经模块 ContentProvider（authority=`com.melody.melodyplus.registry`）中转，在 Android 11+
 *    **包可见性过滤**下宿主进程解析不到该 authority，抛
 *    `IllegalArgumentException: Unknown authority com.melody.melodyplus.registry`。
 *    （实测日志：`BUILTIN_IMG failed … Unknown authority …`）
 *
 * 方案：
 *  1) 首选 [modulePath]（由 `IXposedHookZygoteInit.initZygote` 的 `StartupParam.modulePath` 写入，
 *     zygote 静态状态 fork 后对全部 App 进程可见）；
 *  2) 回退：从模块 ClassLoader 的 `pathList → dexElements → path` 反推模块 APK 路径；
 *  3) 用 [ZipFile] 直接读 `assets/<relative>`（模块 APK 位于 /data/app，0644 世界可读，无需隐藏 API）。
 */
object ModuleAssets {

    /** 模块 APK 路径（zygote 阶段由 HookEntry.initZygote 写入）。 */
    @Volatile
    var modulePath: String? = null

    /**
     * 读取模块内置型号图片（相对路径形如 `device_images/xiberia_mc05/left.png`）。
     * @return 图片字节；读不到返回 null。
     */
    fun readBuiltinImage(profileId: String, slot: String): ByteArray? {
        if (!DeviceImageAssets.isValidSlot(slot)) return null
        val entryName = "assets/${DeviceImageAssets.assetManagerPath(profileId, slot)}"
        // ① 已知模块路径
        modulePath?.let { readFromZip(it, entryName)?.let { bytes -> return bytes } }
        // ② 反推模块 APK 路径
        moduleApkFromClassLoader()?.let { p ->
            if (p != modulePath) modulePath = p
            readFromZip(p, entryName)?.let { return it }
        }
        return null
    }

    /**
     * 通用模块 assets 读取（相对路径，如 `xiberia_eq/bg_2.png`）。
     *
     * 与 [readBuiltinImage] 同一套寻址（modulePath → ClassLoader 反推），
     * 供 EQ 素材等非型号图片使用。
     */
    fun readAsset(relativePath: String): ByteArray? {
        val entryName = "assets/$relativePath"
        modulePath?.let { readFromZip(it, entryName)?.let { bytes -> return bytes } }
        moduleApkFromClassLoader()?.let { p ->
            if (p != modulePath) modulePath = p
            readFromZip(p, entryName)?.let { return it }
        }
        return null
    }

    private fun readFromZip(apk: String, entryName: String): ByteArray? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull()

    /** 从本类 ClassLoader 的 pathList/dexElements 反推模块 APK 路径。 */
    private fun moduleApkFromClassLoader(): String? = runCatching {
        val cl = ModuleAssets::class.java.classLoader ?: return null
        val pathList = findField(cl.javaClass, "pathList")?.apply { isAccessible = true }?.get(cl) ?: return null
        val dexElements = findField(pathList.javaClass, "dexElements")?.apply { isAccessible = true }
            ?.get(pathList) as? Array<*> ?: return null
        var fallback: String? = null
        for (el in dexElements) {
            val f = runCatching {
                findField(el?.javaClass ?: continue, "path")?.apply { isAccessible = true }?.get(el) as? File
            }.getOrNull() ?: continue
            if (!f.name.endsWith(".apk")) continue
            if (f.path.contains("melodyplus")) return f.path
            if (fallback == null && !f.path.contains("com.oplus.melody")) fallback = f.path
        }
        fallback
    }.getOrNull()

    /** 沿继承链查找字段（`pathList` / `dexElements` 可能声明在父类）。 */
    private fun findField(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null) {
            runCatching { c.getDeclaredField(name) }.getOrNull()?.let { return it }
            c = c.superclass
        }
        return null
    }
}
