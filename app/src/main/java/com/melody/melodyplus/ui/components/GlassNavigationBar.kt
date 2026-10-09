package com.melody.melodyplus.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur as modifierBlur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.melody.melodyplus.ui.config.NavBarStyle
import com.melody.melodyplus.ui.config.UiSettings
import com.melody.melodyplus.ui.navigation.MainTab

/**
 * 配置页底栏。三种样式：
 *  - [NavBarStyle.NORMAL]       普通全宽底栏（非玻璃）。
 *  - [NavBarStyle.FLOATING]     浮动胶囊底栏（非玻璃表面）。
 *  - [NavBarStyle.LIQUID_GLASS] 液体玻璃底栏。
 *
 * [真玻璃改造] 旧实现用 `Modifier.blur()` 模糊**自身纯色块**——对纯色没有任何视觉变化，
 *   所以看上去只是一块实心矩形，没有玻璃质感。现改为接入本地源码化的
 *   `com.kyant.backdrop`（Apache-2.0）：通过 `drawBackdrop()` 对**身后真实的
 *   壁纸/内容层**做 vibrancy + blur + lens（边缘折射），得到真正的液态玻璃。
 *
 * 关键点：`drawBackdrop` 必须能“看见”身后的内容，因此调用方（[com.melody.melodyplus.ui.MainTabsScaffold]）
 *   需要用 `Modifier.layerBackdrop(rememberLayerBackdrop { drawContent() })` 把背景内容
 *   录进一个 GraphicsLayer，再把该 backdrop 传进来。
 *   [backdrop] 为 null（例如未提供录制层/低版本降级路径）时退回旧的半透明+描边实现。
 *
 * 降级策略：Android 12（API 31）以下 `RenderEffect` 不可用，`drawBackdrop` 内部自动 no-op，
 *   此时仍渲染 onDrawSurface 的容器色，不会出现空白。
 */
@Composable
fun GlassNavigationBar(
    tabs: List<MainTab>,
    selected: MainTab,
    settings: UiSettings,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val isGlass = settings.navbarStyle == NavBarStyle.LIQUID_GLASS && settings.liquidGlassEnabled
    val isLiquid = isGlass && backdrop != null
    val surfaceAlpha = settings.surfaceOpacity.coerceIn(0f, 1f)

    val shape = when (settings.navbarStyle) {
        NavBarStyle.NORMAL -> RoundedCornerShape(0.dp)
        NavBarStyle.FLOATING -> RoundedCornerShape(24.dp)
        NavBarStyle.LIQUID_GLASS -> RoundedCornerShape(28.dp)
    }
    val horizontalPadding = if (settings.navbarStyle == NavBarStyle.NORMAL) 0.dp else 16.dp
    val contentAlpha =
        if (settings.navbarStyle == NavBarStyle.NORMAL) 1f else surfaceAlpha

    if (isLiquid) {
        // ── 真液体玻璃（背板折射）──
        // 底栏表面由 drawBackdrop 渲染：对身后内容做提纯(vibrancy)+模糊(blur)+边缘折射(lens)，
        // 再叠加一层半透明容器色(onDrawSurface)保证文字对比。
        val blurPx = if (settings.blurEnabled) settings.blurRadius.dp else 0.dp
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            vibrancy()
                            blur(blurPx.toPx())
                            // 折射只在胶囊/圆角边界可见，强度按圆角半径给到观感合适值。
                            lens(16f.dp.toPx(), 20f.dp.toPx())
                        },
                        highlight = { Highlight.Default },
                        onDrawSurface = {
                            drawRect(surfaceContainer.copy(alpha = contentAlpha))
                        },
                    )
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    NavTabItem(
                        tab = tab,
                        selected = selected == tab,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(tab) },
                    )
                }
            }
        }
        return
    }

    // ── 非玻璃 / 降级路径 ──
    val bgColor = if (isGlass) surfaceContainer.copy(alpha = surfaceAlpha) else surfaceContainer
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useBlur = isGlass && settings.blurEnabled && settings.blurRadius > 0f && blurSupported && backdrop == null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .clip(shape),
    ) {
        // [保留原注释] 背景材质层必须用 matchParentSize（不是 fillMaxSize）：
        //   作为 Scaffold 的 bottomBar，本节点约束 maxHeight = 整个可用高度，fillMaxSize 会
        //   把底栏撑到全屏、挤扁内容区；matchParentSize 不参与父尺寸计算，只跟随前景 Row。
        var surfaceModifier = Modifier
            .matchParentSize()
            .background(color = bgColor, shape = shape)
        if (useBlur) {
            surfaceModifier = surfaceModifier.modifierBlur(settings.blurRadius.dp)
        }
        Box(modifier = surfaceModifier)
        if (isGlass) {
            val accent = 0.35f.coerceAtLeast(surfaceAlpha)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.10f * accent),
                                Color.Transparent,
                            ),
                        ),
                        shape = shape,
                    )
                    .border(
                        width = 0.8.dp,
                        color = Color.White.copy(alpha = 0.18f * accent),
                        shape = shape,
                    ),
            )
        }
        if (settings.showDivider) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavTabItem(
                    tab = tab,
                    selected = selected == tab,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun NavTabItem(
    tab: MainTab,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val content = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = tab.label,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
        if (selected) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .width(24.dp)
                    .height(3.dp)
                    .background(primary, RoundedCornerShape(2.dp))
            )
        }
    }
}
