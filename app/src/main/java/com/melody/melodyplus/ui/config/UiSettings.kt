package com.melody.melodyplus.ui.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块配置 UI 的视觉与导航设置。
 * 全部只作用于 MelodyPlus 自己的配置界面，不影响 Melody 系统面板 / 胶囊 UI。
 */
enum class ThemeMode(val raw: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
}

/** 底栏样式。透明度和模糊语义统一：只改变玻璃表面遮罩 alpha，保留模糊与色彩采样。 */
enum class NavBarStyle(val raw: Int) {
    NORMAL(0),        // 普通全宽底栏
    FLOATING(1),      // 浮动胶囊底栏
    LIQUID_GLASS(2),  // 液体玻璃
}

enum class WallpaperScale(val raw: Int) {
    CROP(0),
    FIT(1),
}

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val wallpaperUri: String? = null,
    val wallpaperOpacity: Float = 0.5f,
    val wallpaperScale: WallpaperScale = WallpaperScale.CROP,
    val navbarStyle: NavBarStyle = NavBarStyle.FLOATING,
    val liquidGlassEnabled: Boolean = true,
    // 底栏表面不透明度 0..1；默认 0.72 = 72%
    val surfaceOpacity: Float = 0.72f,
    val blurEnabled: Boolean = true,
    val blurRadius: Float = 18f,
    val showDivider: Boolean = false,
    // 配置卡片填充不透明度 0..1；默认 0.55 = 半透玻璃（能看到壁纸，同时保留可读性）
    val cardOpacity: Float = 0.55f,
    val useDynamicColor: Boolean = true,
    val accentKeyColor: Long = 0xFF2D7D9A,
) {
    val effectiveLiquidGlass: Boolean
        get() = navbarStyle == NavBarStyle.LIQUID_GLASS && liquidGlassEnabled
}

/**
 * 把 [UiSettings] 持久化到配置进程自身的 SharedPreferences。
 * 壁纸图片本身不写入 Preferences：只存持久化 URI + 运行时 Bitmap。
 * 透明度统一用 0..1 保存，UI 展示为百分比。
 */
object ModuleUiConfigStore {
    private const val PREFS = "module_ui_config"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_WALLPAPER_URI = "wallpaper_uri"
    private const val KEY_WALLPAPER_OPACITY = "wallpaper_opacity"
    private const val KEY_WALLPAPER_SCALE = "wallpaper_scale"
    private const val KEY_NAVBAR_STYLE = "navbar_style"
    private const val KEY_LIQUID_GLASS = "liquid_glass"
    private const val KEY_SURFACE_OPACITY = "surface_opacity"
    private const val KEY_BLUR_ENABLED = "blur_enabled"
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_SHOW_DIVIDER = "show_divider"
    private const val KEY_CARD_OPACITY = "card_opacity"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_ACCENT_COLOR = "accent_color"

    fun load(context: Context): UiSettings {
        val p = prefs(context)
        return UiSettings(
            themeMode = ThemeMode.entries.firstOrNull { it.raw == p.getInt(KEY_THEME, 0) } ?: ThemeMode.SYSTEM,
            wallpaperUri = p.getString(KEY_WALLPAPER_URI, null),
            wallpaperOpacity = p.getFloat(KEY_WALLPAPER_OPACITY, 0.5f).coerceIn(0f, 1f),
            wallpaperScale = WallpaperScale.entries.firstOrNull { it.raw == p.getInt(KEY_WALLPAPER_SCALE, 0) } ?: WallpaperScale.CROP,
            navbarStyle = NavBarStyle.entries.firstOrNull { it.raw == p.getInt(KEY_NAVBAR_STYLE, 1) } ?: NavBarStyle.FLOATING,
            liquidGlassEnabled = p.getBoolean(KEY_LIQUID_GLASS, true),
            surfaceOpacity = p.getFloat(KEY_SURFACE_OPACITY, 0.72f).coerceIn(0f, 1f),
            blurEnabled = p.getBoolean(KEY_BLUR_ENABLED, true),
            blurRadius = p.getFloat(KEY_BLUR_RADIUS, 18f).coerceIn(0f, 40f),
            showDivider = p.getBoolean(KEY_SHOW_DIVIDER, false),
            cardOpacity = p.getFloat(KEY_CARD_OPACITY, 0.55f).coerceIn(0f, 1f),
            useDynamicColor = p.getBoolean(KEY_DYNAMIC_COLOR, true),
            accentKeyColor = p.getLong(KEY_ACCENT_COLOR, 0xFF2D7D9A.toLong()),
        )
    }

    fun save(context: Context, settings: UiSettings) {
        prefs(context).edit()
            .putInt(KEY_THEME, settings.themeMode.raw)
            .putString(KEY_WALLPAPER_URI, settings.wallpaperUri)
            .putFloat(KEY_WALLPAPER_OPACITY, settings.wallpaperOpacity.coerceIn(0f, 1f))
            .putInt(KEY_WALLPAPER_SCALE, settings.wallpaperScale.raw)
            .putInt(KEY_NAVBAR_STYLE, settings.navbarStyle.raw)
            .putBoolean(KEY_LIQUID_GLASS, settings.liquidGlassEnabled)
            .putFloat(KEY_SURFACE_OPACITY, settings.surfaceOpacity.coerceIn(0f, 1f))
            .putBoolean(KEY_BLUR_ENABLED, settings.blurEnabled)
            .putFloat(KEY_BLUR_RADIUS, settings.blurRadius.coerceIn(0f, 40f))
            .putBoolean(KEY_SHOW_DIVIDER, settings.showDivider)
            .putFloat(KEY_CARD_OPACITY, settings.cardOpacity.coerceIn(0f, 1f))
            .putBoolean(KEY_DYNAMIC_COLOR, settings.useDynamicColor)
            .putLong(KEY_ACCENT_COLOR, settings.accentKeyColor)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}