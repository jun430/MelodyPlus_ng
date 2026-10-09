package com.melody.melodyplus.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.melody.melodyplus.ui.config.UiSettings
import com.melody.melodyplus.ui.config.WallpaperScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 根据 [settings] 渲染配置页背景。
 *
 * [修复·白色遮罩挡住壁纸] 旧实现叠了一层 `background.copy(alpha = 1f - opacity)`：
 *   浅色主题下 `background ≈ #F7F7F8`，等于**往壁纸上刷白漆**，所以「透明度 100% 仍有白色挡着」。
 *   现改为：
 *     - 壁纸可见度直接由 [UiSettings.wallpaperOpacity] 控制（`graphicsLayer.alpha`）；
 *     - 不再使用主题背景色做遮罩；仅在 opacity < 1 时补一层**中性黑** scrim 压暗，
 *       保证文字可读性，且 opacity = 1 时 scrim 归零 → 壁纸完全不被遮挡。
 */
@Composable
fun WallpaperBackground(
    settings: UiSettings,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val background = MaterialTheme.colorScheme.background
    val bitmap: State<Bitmap?> = produceState<Bitmap?>(initialValue = null, settings.wallpaperUri) {
        value = settings.wallpaperUri
            ?.takeIf { it.isNotBlank() }
            ?.let { uri -> withContext(Dispatchers.IO) { decodeScaled(context, uri) } }
    }
    val scale = when (settings.wallpaperScale) {
        WallpaperScale.CROP -> ContentScale.Crop
        WallpaperScale.FIT -> ContentScale.Fit
    }
    val alpha = settings.wallpaperOpacity.coerceIn(0f, 1f)

    Box(modifier = modifier.background(background)) {
        val bmp = bitmap.value
        if (bmp != null && alpha > 0f) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // 壁纸可见度：opacity=1 完全不透明显示；=0 完全隐藏（由外层主题背景兜底）。
                    .graphicsLayer { this.alpha = alpha },
                contentScale = scale,
            )
            // 中性 scrim：只压暗、不刷白；opacity=1 时系数为 0，壁纸零遮挡。
            val scrim = 0.28f * (1f - alpha)
            if (scrim > 0.001f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrim))
                )
            }
        }
        content()
    }
}

private fun decodeScaled(context: Context, uri: String): Bitmap? = runCatching {
    val resolver = context.contentResolver
    // 只读约束：尽量原样解码，避免在 Hook/配置文件里做重图片处理
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
    val maxEdge = 2048
    var sample = 1
    while (opts.outWidth / sample > maxEdge || opts.outHeight / sample > maxEdge) sample *= 2
    val decode = BitmapFactory.Options().apply { inSampleSize = sample }
    resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, decode) }
}.getOrNull()