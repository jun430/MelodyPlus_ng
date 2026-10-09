package com.melody.melodyplus.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.melody.melodyplus.ui.config.ThemeMode
import com.melody.melodyplus.ui.config.UiSettings

/**
 * 根据 [settings]（主题模式/动态取色/强调色）构建 Material3 主题。
 * 页面导航状态由外层持有，切换主题只触发重组、不重建页面状态。
 */
@Composable
fun ModuleUiTheme(
    settings: UiSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val keyAccent = Color(settings.accentKeyColor)

    val colorScheme = when {
        settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = keyAccent,
            background = Color(0xFF121212),
            surface = Color(0xFF1C1C1C),
        )
        else -> lightColorScheme(
            primary = keyAccent,
            background = Color(0xFFF7F7F8),
            surface = Color(0xFFFFFFFF),
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}