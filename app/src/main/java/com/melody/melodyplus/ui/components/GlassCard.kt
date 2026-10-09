package com.melody.melodyplus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 半透明「玻璃卡片」——替代 Miuix 不透明 Card，用于配置页各设置区块。
 *
 * [修复·卡片盖住壁纸] 旧实现用 `CardDefaults.defaultColors(color = surfaceContainer)`：
 *   该色为**不透明**填充，会整片遮住壁纸，导致「壁纸透明度 100% 仍有白色挡着」。
 *   本组件改为按 [opacity] 输出 `surfaceContainer.copy(alpha)`，并支持极薄描边。
 *
 * 可读性策略（不靠“洗白”实现）：
 *   - 透明度较高时（[opacity] 小）自动补一层 hairline 描边，用边界而非底色来区分层级；
 *   - 文字对比度由前景控件自身的 color token 保证，不依赖卡片底色。
 *
 * @param opacity 卡片填充不透明度 0..1；1=不透明白底，0=完全透明（仅描边）。
 * @param fillColor 卡片填充基色（默认 surfaceContainer；语义卡片可传 primaryContainer 等），
 *   实际 alpha 统一由 [opacity] 控制 —— 保证全部页面卡片透明度行为一致。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    opacity: Float = 0.5f,
    shape: Shape = RoundedCornerShape(16.dp),
    contentPadding: Dp = 16.dp,
    verticalSpacing: Dp = 12.dp,
    fillColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = fillColor
    val alpha = opacity.coerceIn(0f, 1f)
    // 透明度越低（越透），描边越明显，保证「有一点显示边界」。
    val borderAlpha = (0.16f * (1f - alpha)).coerceIn(0.04f, 0.16f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(base.copy(alpha = alpha))
            .border(
                width = 1.dp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = borderAlpha),
                shape = shape,
            )
            .padding(contentPadding),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
}
