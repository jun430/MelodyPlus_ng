// 文件: app/src/main/java/com/melody/melodyplus/hook/XiberiaEqDialog.kt
// 说明: 官方式「均衡器」子页 —— 全屏 Dialog 承载 预设卡片网格 + XiberiaEqEditorView + 重置/应用。
//
// 官方视觉对齐（com.cchip.desheng v1.9.26，npmcp 提取）：
//   - 预设区 : 3 列渐变卡片（`eq_bg_N` 背景 + `dev_eq_system_iconN_selector` 图标 + 白字官方文案）
//              见 [XiberiaEqCardView] / [buildPresetGrid]，素材来自模块 assets/xiberia_eq。
//   - 编辑区 : 多段竖条 + 频响曲线（[XiberiaEqEditorView]）。
//   - 操作行 : 重置 / 应用（官方 `dev_eq_reset` 等）。
//
// 约束（LSPosed 宿主进程内）：
//   - 不用模块资源（宿主 Resources 不认识本模块 R.*），标题/文案走硬编码字符串；
//   - 纯 android.* 建树；主题用宿主自带 AlertDialog 主题或系统 Material 兜底；
//   - 应用时把 XiberiaEqEditorView.toGains() 交给回调，由 MelodyPanelHook 下发 0x0806。
package com.melody.melodyplus.hook

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.melody.melodyplus.adapter.xiberia.XiberiaEqMath
import com.melody.melodyplus.adapter.xiberia.XiberiaEqPresets
import com.melody.melodyplus.adapter.xiberia.XiberiaProductCatalog

object XiberiaEqDialog {

    /**
     * 弹出均衡器编辑页。
     *
     * @param eqConfig  官方 EQ 配置（段数 / 上限）
     * @param onApply   点「应用」时回调（参数为换算后的增益数组）
     * @param initial   可选初始增益（空 = 用官方默认居中值）
     */
    fun show(
        context: Context,
        eqConfig: XiberiaProductCatalog.EqConfig,
        initial: IntArray? = null,
        onApply: (gains: IntArray) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val mode = when (eqConfig.cardinal) {
            12 -> XiberiaProductCatalog.GainMode.VALUE_12
            else -> if (eqConfig.maxValue >= 90) {
                XiberiaProductCatalog.GainMode.VALUE_6_NEW
            } else {
                XiberiaProductCatalog.GainMode.VALUE_6
            }
        }

        val editor = XiberiaEqEditorView(context, eqConfig.cardinal, mode).apply {
            initial?.let { setUiValues(it) }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(0xFFF2F4F8.toInt())
        }

        // 标题
        root.addView(
            TextView(context).apply {
                text = "均衡器"
                setTextColor(0xFF111111.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(4) }
            },
        )
        // 副标题（段数 / 增益范围）
        root.addView(
            TextView(context).apply {
                text = "${eqConfig.cardinal} 段均衡器（±${eqConfig.maxValue}）"
                setTextColor(0x99000000.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12) }
            },
        )

        // 预设卡片网格：点击 → 回填滑块 + 刷新高亮（选中项描边 + 图标 press 态）
        var selectedCard: XiberiaEqCardView? = null
        val cards = mutableListOf<XiberiaEqCardView>()
        val cardGrid = buildPresetGrid(context, XiberiaEqPresets.STANDARD) { preset, card ->
            val adapted = XiberiaEqMath.adaptPresetGains(preset.gains, eqConfig.cardinal)
            editor.setUiValues(XiberiaEqMath.eqGainToUiValues(adapted, mode))
            selectedCard?.takeIf { it !== card }?.setSelectedState(false)
            card.setSelectedState(true)
            selectedCard = card
        }
        cards += collectCards(cardGrid)

        root.addView(
            cardGrid,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) },
        )

        // 滑块区（白底圆角卡，占满剩余）
        val editorCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
        }
        editorCard.addView(
            editor,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(230),
            ),
        )
        root.addView(
            editorCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )

        // 底部操作行
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(
            pillButton(context, "重置", primary = false) { editor.reset() },
            LinearLayout.LayoutParams(0, dp(42), 1f),
        )
        actions.addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
        actions.addView(
            pillButton(context, "应用", primary = true) { onApply(editor.toGains()) },
            LinearLayout.LayoutParams(0, dp(42), 1f),
        )
        root.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 内容可能超出屏高 → 包一层 ScrollView
        val scroller = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setView(scroller)
            .setNegativeButton("关闭", null)
            .create()
            .show()
    }

    /** 官方风格胶囊按钮（主操作蓝底白字 / 次操作浅灰）。 */
    private fun pillButton(
        context: Context,
        label: String,
        primary: Boolean,
        onClick: () -> Unit,
    ): Button {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        return Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) Color.WHITE else 0xFF3D6DFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(21).toFloat()
                setColor(if (primary) 0xFF3D6DFF.toInt() else 0x143D6DFF)
            }
            setOnClickListener { onClick() }
        }
    }
}