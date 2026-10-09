package com.melody.melodyplus.ui.pages

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.melody.melodyplus.ui.components.GlassCard
import com.melody.melodyplus.ui.components.GlassNavigationBar
import com.melody.melodyplus.ui.config.NavBarStyle
import com.melody.melodyplus.ui.config.ThemeMode
import com.melody.melodyplus.ui.config.UiSettings
import com.melody.melodyplus.ui.config.WallpaperScale
import com.melody.melodyplus.ui.navigation.MainTab
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 外观页：壁纸（选择/透明度/缩放）、卡片（玻璃透明度）、底栏（样式/液体玻璃/透明度/模糊）、主题。
 * 调速即改、即时预览，全部只作用在模块配置界面。
 *
 * [修复·卡片盖住壁纸] 各区块统一改用 [GlassCard]（半透明 + hairline 描边），
 *   不再使用 Miuix 不透明 `Card`——后者会整片遮住壁纸，导致「壁纸 100% 仍有白色挡着」。
 */
@Composable
fun AppearancePage(
    settings: UiSettings,
    onSettingsChange: (UiSettings) -> Unit,
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    // 卡片透明度统一由设置驱动，各区块保持一致。
    val cardOpacity = settings.cardOpacity

    // 持久化选图：ACTION_OPEN_DOCUMENT + persistable URI 权限，重启后仍可恢复壁纸
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // [修复·权限失败仍保存] 仅在成功取得持久化读权限后才写入 URI；失败则提示并保持原状。
        val granted = persistReadPermission(context, uri)
        if (granted) {
            onSettingsChange(settings.copy(wallpaperUri = uri.toString()))
        } else {
            android.widget.Toast.makeText(context, "无法获取图片读取权限，未保存壁纸", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 背景 ----
        SectionHeader("背景")
        WallpaperCard(
            settings = settings,
            cardOpacity = cardOpacity,
            onPick = { picker.launch(arrayOf("image/*")) },
            onRemove = { onSettingsChange(settings.copy(wallpaperUri = null)) },
            onOpacity = { onSettingsChange(settings.copy(wallpaperOpacity = it)) },
            onScale = { onSettingsChange(settings.copy(wallpaperScale = it)) },
        )

        // ---- 卡片 ----
        SectionHeader("卡片")
        CardStyleCard(
            settings = settings,
            cardOpacity = cardOpacity,
            onSettingsChange = onSettingsChange,
        )

        // ---- 导航栏 ----
        SectionHeader("导航栏")
        NavigationBarCard(
            settings = settings,
            cardOpacity = cardOpacity,
            onSettingsChange = onSettingsChange,
        )

        // ---- 主题 ----
        SectionHeader("主题")
        ThemeCard(
            settings = settings,
            cardOpacity = cardOpacity,
            onSettingsChange = onSettingsChange,
        )

        // ---- 实时预览 ----
        SectionHeader("实时预览")
        LivePreviewCard(settings = settings, cardOpacity = cardOpacity)
    }
}

@Composable
private fun SectionHeader(title: String) {
    top.yukonga.miuix.kmp.basic.Text(
        text = title,
        style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.title3,
        color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun WallpaperCard(
    settings: UiSettings,
    cardOpacity: Float,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onOpacity: (Float) -> Unit,
    onScale: (WallpaperScale) -> Unit,
) {
    val context = LocalContext.current
    // [修复·主线程同步解码] 旧实现用 remember {} 在 Composable 求值线程同步解码壁纸：
    //   大图/慢云端 provider 会阻塞主线程、造成首屏卡顿。改为 produceState + Dispatchers.IO，
    //   并随 URI 变化自动取消旧解码（协程取消 + 状态重置）。
    val preview by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = settings.wallpaperUri,
    ) {
        val uri = settings.wallpaperUri
        value = if (uri == null) {
            null
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                decodePreviewOnly(context, uri)
            }
        }
    }

    GlassCard(opacity = cardOpacity) {
        // 壁纸预览框（模拟 0..100% 透明度）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = preview
            if (bmp != null) {
                val scale = when (settings.wallpaperScale) {
                    WallpaperScale.CROP -> ContentScale.Crop
                    WallpaperScale.FIT -> ContentScale.Fit
                }
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = scale,
                )
                // [修复·预览遮罩刷白] 预览框内同样只做中性压暗，不用主题白底覆盖，与实机背景语义一致。
                val scrim = 0.28f * (1f - settings.wallpaperOpacity)
                if (scrim > 0.001f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = scrim))
                    )
                }
            } else {
                Text(
                    text = "未设置壁纸",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiuixTextButton("选择壁纸", onPick)
            if (settings.wallpaperUri != null) {
                MiuixTextButton("移除", onRemove)
            }
        }

        LabeledSlider(
            label = "壁纸透明度 ${(settings.wallpaperOpacity * 100).toInt()}%",
            value = settings.wallpaperOpacity,
            onValueChange = onOpacity,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "填充模式",
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.body2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption(
                    selected = settings.wallpaperScale == WallpaperScale.CROP,
                    text = "裁剪",
                    onClick = { onScale(WallpaperScale.CROP) },
                )
                SegmentedOption(
                    selected = settings.wallpaperScale == WallpaperScale.FIT,
                    text = "适应",
                    onClick = { onScale(WallpaperScale.FIT) },
                )
            }
        }
    }
}

@Composable
private fun CardStyleCard(
    settings: UiSettings,
    cardOpacity: Float,
    onSettingsChange: (UiSettings) -> Unit,
) {
    GlassCard(opacity = cardOpacity) {
        LabeledSlider(
            label = "卡片不透明度 ${(settings.cardOpacity * 100).toInt()}%",
            value = settings.cardOpacity,
            onValueChange = { onSettingsChange(settings.copy(cardOpacity = it)) },
        )
        Text(
            text = "调低可让壁纸透出来；卡片自带极细描边，保证边界可见。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun NavigationBarCard(
    settings: UiSettings,
    cardOpacity: Float,
    onSettingsChange: (UiSettings) -> Unit,
) {
    GlassCard(opacity = cardOpacity) {
        Text("底栏样式", style = MiuixTheme.textStyles.body2)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavBarStyle.entries.forEach { style ->
                SegmentedOption(
                    selected = settings.navbarStyle == style,
                    text = style.label(),
                    onClick = { onSettingsChange(settings.copy(navbarStyle = style)) },
                    weight = Modifier.weight(1f),
                )
            }
        }

        MiuixSwitchRow(
            label = "液体玻璃",
            checked = settings.liquidGlassEnabled && settings.navbarStyle == NavBarStyle.LIQUID_GLASS,
            onCheckedChange = { onSettingsChange(settings.copy(liquidGlassEnabled = it)) },
        )

        LabeledSlider(
            label = "底栏透明度 ${(settings.surfaceOpacity * 100).toInt()}%",
            value = settings.surfaceOpacity,
            onValueChange = { onSettingsChange(settings.copy(surfaceOpacity = it)) },
        )

        MiuixSwitchRow(
            label = "背景模糊",
            checked = settings.blurEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(blurEnabled = it)) },
        )
        LabeledSlider(
            label = "模糊强度 ${settings.blurRadius.toInt()} dp",
            value = settings.blurRadius,
            onValueChange = { onSettingsChange(settings.copy(blurRadius = it)) },
            valueRange = 0f..40f,
        )

        MiuixSwitchRow(
            label = "显示分割线",
            checked = settings.showDivider,
            onCheckedChange = { onSettingsChange(settings.copy(showDivider = it)) },
        )
    }
}

@Composable
private fun ThemeCard(
    settings: UiSettings,
    cardOpacity: Float,
    onSettingsChange: (UiSettings) -> Unit,
) {
    GlassCard(opacity = cardOpacity) {
        Text("主题模式", style = MiuixTheme.textStyles.body2)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeMode.entries.forEach { mode ->
                SegmentedOption(
                    selected = settings.themeMode == mode,
                    text = mode.label(),
                    onClick = { onSettingsChange(settings.copy(themeMode = mode)) },
                    weight = Modifier.weight(1f),
                )
            }
        }
        MiuixSwitchRow(
            label = "动态取色",
            checked = settings.useDynamicColor,
            onCheckedChange = { onSettingsChange(settings.copy(useDynamicColor = it)) },
        )
    }
}

@Composable
private fun LivePreviewCard(settings: UiSettings, cardOpacity: Float) {
    GlassCard(opacity = cardOpacity, contentPadding = 12.dp, verticalSpacing = 8.dp) {
        Text("预览", style = MiuixTheme.textStyles.title3)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "内容卡片示例",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        GlassNavigationBar(
            tabs = MainTab.entries,
            selected = MainTab.APPEARANCE,
            settings = settings,
            onSelect = {},
        )
    }
}

@Composable
private fun MiuixSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MiuixText(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Column {
        MiuixText(label)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MiuixText(text: String, modifier: Modifier = Modifier) {
    top.yukonga.miuix.kmp.basic.Text(
        text = text,
        modifier = modifier,
        style = MiuixTheme.textStyles.body2,
    )
}

@Composable
private fun MiuixTextButton(text: String, onClick: () -> Unit) {
    top.yukonga.miuix.kmp.basic.TextButton(
        text = text,
        onClick = onClick,
    )
}

/** 用 Miuix 卡芯片风格渲染的分段单选（模拟 FilterChip 但色板统一）。 */
@Composable
private fun SegmentedOption(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
    weight: Modifier = Modifier,
) {
    val bg = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.surface
    }
    val fg = if (selected) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    Box(
        modifier = weight
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = fg, style = MiuixTheme.textStyles.body2)
    }
}

/**
 * 持久化读权限，返回是否成功。
 *
 * [修复·权限失败仍保存 URI] 旧实现吞掉异常后无条件保存 URI，一旦授权失败就留下一个不可读 URI，
 * 预览/背景都拿不到图却无从提示。现返回结果，调用方仅在成功时才落盘 URI。
 */
private fun persistReadPermission(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.takePersistableUriPermission(
        uri,
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
    true
}.getOrDefault(false)

/**
 * 预览用小图解码（受 [maxEdge] 限制）。
 *
 * 优化：一次打开流用 `inJustDecodeBounds` 取尺寸，再开一次按 `inSampleSize` 解码；
 *   尺寸非法（<=0，坏图/非图片 provider）直接判失败，避免采样循环异常。
 */
private fun decodePreviewOnly(context: Context, uri: String, maxEdge: Int = 1024): android.graphics.Bitmap? = runCatching {
    val parsed = Uri.parse(uri)
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, opts)
    }
    // [修复·坏图尺寸] 解码失败或尺寸非正时直接返回 null，避免 while 采样死循环/异常。
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    var sample = 1
    while (opts.outWidth / sample > maxEdge || opts.outHeight / sample > maxEdge) sample *= 2
    val decode = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(parsed)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, decode)
    }
}.getOrNull()

private fun NavBarStyle.label(): String = when (this) {
    NavBarStyle.NORMAL -> "普通"
    NavBarStyle.FLOATING -> "浮动"
    NavBarStyle.LIQUID_GLASS -> "液体玻璃"
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}