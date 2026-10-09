// 文件: app/src/main/java/com/melody/melodyplus/hook/XiberiaEqDialog.kt
// 说明: 官方式「均衡器」子页 —— 全屏 Dialog 承载 XiberiaEqEditorView + 预设/重置/应用。
//
// 约束（LSPosed 宿主进程内）：
//   - 不用模块资源（宿主 Resources 不认识本模块 R.*），标题/文案走硬编码字符串；
//   - 纯 android.* 建树；主题用宿主自带 AlertDialog 主题或系统 Material 兜底；
//   - 应用时把 XiberiaEqEditorView.toGains() 交给回调，由 MelodyPanelHook 下发 0x0806。
package com.melody.melodyplus.hook

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(Color.WHITE)
        }

        // 标题
        root.addView(
            TextView(context).apply {
                text = "均衡器"
                setTextColor(0xFF111111.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
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
                ).apply { bottomMargin = dp(8) }
            },
        )

        // 滑块区（占满剩余）
        root.addView(
            editor,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        // 底部操作行
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(flatButton(context, "重置") { editor.reset() })
        actions.addView(flatButton(context, "应用") {
            val gains = editor.toGains()
            onApply(gains)
        })
        root.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        AlertDialog.Builder(context)
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
            .show()
    }

    private fun flatButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTextColor(0xFF3D6DFF.toInt())
            textSize = 15f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }
}