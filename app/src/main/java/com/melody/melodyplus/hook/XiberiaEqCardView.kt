// 文件: app/src/main/java/com/melody/melodyplus/hook/XiberiaEqCardView.kt
// 说明: 官方 XIBERIA「均衡器」预设卡片 —— 1:1 复刻官方 `DefaultEqGain` 列表项视觉。
//
// 官方真值（com.cchip.desheng v1.9.26，npmcp 提取）：
//   布局 : `dev_eq_dm02_activity` / `dev_eq_custom_activity`（RecyclerView + GridLayoutManager spanCount=3）
//   背景 : `R.mipmap.eq_bg_<N>`（12 张渐变位图，见 XiberiaEqPresets.Preset.bgIndex）
//   图标 : `R.drawable.dev_eq_system_icon<N>_selector`（normal/selected 两态 PNG）
//   文案 : `R.string.dev_eq_presuppose_*`（白字，居中偏下）
//
// 素材取用：模块 assets/xiberia_eq/{bg_N.png, icon_N.png, icon_N_sel.png}
//   → 经 [XiberiaEqAssets] 直读模块 APK（宿主进程 Resources 不认识模块资源）。
package com.melody.melodyplus.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.melody.melodyplus.adapter.xiberia.XiberiaEqPresets

/**
 * 官方 EQ 预设卡片：渐变背景图 + 居中图标 + 底部白字，选中态高亮描边。
 *
 * 回退：素材缺失（bgIndex=0 / PNG 读不到）时按 command 取 HSV 纯色兜底，
 * 保证视觉仍整齐而不出现空白方块。
 */
class XiberiaEqCardView(
    context: Context,
    private val preset: XiberiaEqPresets.Preset,
    private val onPick: (XiberiaEqPresets.Preset, XiberiaEqCardView) -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val bg = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        XiberiaEqAssets.background(context, preset.bgIndex)?.let { setImageBitmap(it) }
    }
    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        XiberiaEqAssets.icon(context, preset.iconIndex, selected = false)?.let { setImageBitmap(it) }
    }
    private val label = TextView(context).apply {
        text = preset.displayLabel
        setTextColor(Color.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(dp(3f), 0f, dp(1f), 0x73000000)
    }
    private var selected = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        // 圆角裁切（clipToOutline 会连子 View 一起裁）
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(10f))
            }
        }
        addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (XiberiaEqAssets.background(context, preset.bgIndex) == null) {
            bg.setBackgroundColor(fallbackColor(preset.command))
        }
        addView(
            icon,
            LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.CENTER).apply {
                bottomMargin = (12 * density).toInt()
            },
        )
        addView(
            label,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = (8 * density).toInt()
            },
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onPick(preset, this) }
    }

    /** 选中态（官方 selector：图标切 press 图 + 高亮描边）。 */
    fun setSelectedState(value: Boolean) {
        if (selected == value) return
        selected = value
        XiberiaEqAssets.icon(context, preset.iconIndex, selected = value)?.let { icon.setImageBitmap(it) }
        foreground = if (value) {
            GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                cornerRadius = dp(10f)
                setColor(0x00000000)
                setStroke(dp(2.5f).toInt(), Color.WHITE)
            }
        } else {
            null
        }
    }

    private fun fallbackColor(command: Int): Int {
        val hue = (command * 47) % 360
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.92f))
    }
}

/** 深度优先收集网格内全部卡片（供外部统一管理选中态）。 */
internal fun collectCards(root: ViewGroup): List<XiberiaEqCardView> {
    val out = mutableListOf<XiberiaEqCardView>()
    fun walk(v: View) {
        if (v is XiberiaEqCardView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(root)
    return out
}

/** 3 列网格（官方 GridLayoutManager spanCount=3）。 */
internal fun buildPresetGrid(
    context: Context,
    presets: List<XiberiaEqPresets.Preset>,
    columns: Int = 3,
    onPick: (XiberiaEqPresets.Preset, XiberiaEqCardView) -> Unit,
): ViewGroup {
    val density = context.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    // 垂直 LinearLayout + 每行 Horizontal（不引 support 库；十余项规模无需回收）。
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var row: LinearLayout? = null
    presets.forEachIndexed { index, preset ->
        if (index % columns == 0) {
            row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = if (index == 0) 0 else dp(10) },
            )
        }
        val card = XiberiaEqCardView(context, preset, onPick)
        row!!.addView(
            card,
            LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                if (index % columns != columns - 1) rightMargin = dp(10)
            },
        )
    }
    // 末行补空位（保证每张卡等宽）
    val remainder = presets.size % columns
    if (remainder != 0) {
        repeat(columns - remainder) {
            row!!.addView(
                View(context),
                LinearLayout.LayoutParams(0, dp(92), 1f).apply { rightMargin = dp(10) },
            )
        }
    }
    return root
}