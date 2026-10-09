// 文件: app/src/main/java/com/melody/melodyplus/hook/XiberiaEqAssets.kt
// 说明: 官方 XIBERIA EQ 素材（背景 / 图标）加载与缓存。
//
// 素材来源（npmcp 从 com.cchip.desheng v1.9.26 提取，官方编号 1:1）：
//   背景  eq_bg_1..12   → assets/xiberia_eq/bg_1.png .. bg_12.png   （12 张，xxhdpi 真值）
//   图标  icon0..11     → assets/xiberia_eq/icon_N.png / icon_N_sel.png（normal / selected 两态）
//
// 读取路径：hook 运行在宿主 `com.oplus.melody` 进程，其 Resources 不认识模块资源 →
//   用 [ModuleAssets.readAsset] 直读模块 APK 的 assets（ZipFile，世界可读）。
//   解码后的 Bitmap 进程内缓存（避免每次注入面板重复解码）。
package com.melody.melodyplus.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap

object XiberiaEqAssets {

    private val cache = ConcurrentHashMap<String, Bitmap?>()

    /** 背景位图（编号 1..12）；无则 null。 */
    fun background(context: Context, bgIndex: Int): Bitmap? {
        if (bgIndex !in 1..12) return null
        return load("xiberia_eq/bg_$bgIndex.png")
    }

    /** 预设图标位图（编号 0..11；selected=true 取官方 press 态）；无则 null。 */
    fun icon(context: Context, iconIndex: Int, selected: Boolean): Bitmap? {
        if (iconIndex !in 0..11) return null
        val name = if (selected) "icon_${iconIndex}_sel.png" else "icon_$iconIndex.png"
        return load("xiberia_eq/$name")
    }

    private fun load(relativePath: String): Bitmap? = cache.getOrPut(relativePath) {
        val bytes = ModuleAssets.readAsset(relativePath)
        if (bytes == null) {
            modLog("W", "XIBERIA_EQ_ASSET missing: $relativePath")
            null
        } else {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                .onFailure { modLog("W", "XIBERIA_EQ_ASSET decode failed: $relativePath ${it.message}") }
                .getOrNull()
        }
    }

    /** 清空缓存（型号切换 / 内存回收）。 */
    fun clear() {
        cache.clear()
    }
}