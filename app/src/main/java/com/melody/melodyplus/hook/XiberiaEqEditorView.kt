// 文件: app/src/main/java/com/melody/melodyplus/hook/XiberiaEqEditorView.kt
// 说明: 官方式「均衡器」多段竖向滑块编辑控件（1:1 复刻官方 com.cchip.desheng `EqBarListAdapter` +
//       `LineView` 的交互与视觉：多条竖直轨道 + 圆点手柄 + 平滑频响曲线 + 频率刻度）。
//
// 设计约束（LSPosed 宿主进程内使用）：
//   - 只依赖 android.*（boot classpath），不引用 com.oplus.* / com.cchip.* 宿主类；
//   - 不 inflate 模块自带 layout，纯代码建树，避免宿主 Resources 找不到本模块资源。
//
// 值域：内部以「官方 UI 进度」存储（0..uiMax），落到下发时用
//   XiberiaEqMath.uiValuesToEqGain(...) 换算为耳机增益字节。
package com.melody.melodyplus.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.melody.melodyplus.adapter.xiberia.XiberiaEqMath
import com.melody.melodyplus.adapter.xiberia.XiberiaProductCatalog

/**
 * 多段竖向 EQ 滑块 + 频响曲线。
 *
 * @param segments 段数（MC05 = 6）
 * @param mode     官方 GainMode（决定默认值与上限）
 */
class XiberiaEqEditorView(
    context: Context,
    private val segments: Int,
    private val mode: XiberiaProductCatalog.GainMode,
) : View(context) {

    /** 每段 UI 进度（0..uiMax）。 */
    private val values: IntArray =
        XiberiaEqMath.resetUiValues(segments, mode).copyOf(segments)

    private val uiMax: Int = XiberiaEqMath.uiMaxOf(mode)
    private val hzLabels: List<String> = XiberiaEqMath.hzLabels(segments)

    private var dragging = -1

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A000000
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val activeBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3D6DFF.toInt()
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(dp(3f), 0f, dp(1f), 0x55000000)
    }
    private val knobStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3D6DFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3D6DFF.toInt()
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val curveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x223D6DFF
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // 阴影需要软件层
        isClickable = true
    }

    /** 当前 UI 进度快照。 */
    fun uiValues(): IntArray = values.copyOf()

    /** 重置为默认（全段居中）。 */
    fun reset() {
        XiberiaEqMath.resetUiValues(segments, mode).copyOf(segments).copyInto(values)
        invalidate()
    }

    /** 由外部回填（如从设备读回）。 */
    fun setUiValues(next: IntArray) {
        next.copyOf(minOf(next.size, segments)).copyInto(values)
        invalidate()
    }

    /** 换算后的耳机增益字节（下发给 0x0806）。 */
    fun toGains(): IntArray = XiberiaEqMath.uiValuesToEqGain(values, mode)

    // ---------- 布局计算 ----------

    private val labelH = dp(22f)
    private val padTop = dp(18f)
    private val padBottom get() = labelH + dp(8f)
    private val padH = dp(16f)

    private fun trackTop(): Float = paddingTop + padTop
    private fun trackBottom(): Float = height - paddingBottom - padBottom
    private fun trackHeight(): Float = (trackBottom() - trackTop()).coerceAtLeast(1f)

    private fun slotWidth(): Float =
        ((width - paddingLeft - paddingRight - 2 * padH) / segments).coerceAtLeast(1f)

    private fun centerXOf(index: Int): Float =
        paddingLeft + padH + slotWidth() * (index + 0.5f)

    private fun knobYOf(value: Int): Float {
        val ratio = value.toFloat() / uiMax.toFloat()
        return trackBottom() - ratio * trackHeight()
    }

    /** 0 dB 中线位置。 */
    private fun zeroY(): Float = knobYOf(uiMax / 2)

    private fun valueFromY(y: Float): Int {
        val ratio = ((trackBottom() - y) / trackHeight()).coerceIn(0f, 1f)
        return (ratio * uiMax).toInt().coerceIn(0, uiMax)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        drawCurve(canvas)
        drawBars(canvas)
        drawLabels(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        // 0dB 中线
        val zy = zeroY()
        val linePaint = gridPaint
        canvas.drawLine(padH, zy, width - padH, zy, linePaint)
        // 上下限虚线参考（±max）
        canvas.drawLine(padH, trackTop(), width - padH, trackTop(), linePaint)
        canvas.drawLine(padH, trackBottom(), width - padH, trackBottom(), linePaint)
    }

    private fun drawCurve(canvas: Canvas) {
        val path = Path()
        val fill = Path()
        val zy = zeroY()
        fill.moveTo(centerXOf(0), zy)
        for (i in 0 until segments) {
            val x = centerXOf(i)
            val y = knobYOf(values[i])
            if (i == 0) path.moveTo(x, y) else {
                // 用二次贝塞尔在中点处平滑相连（官方 LineView 同风格）
                val px = centerXOf(i - 1)
                val py = knobYOf(values[i - 1])
                val midX = (px + x) / 2f
                path.quadTo(px, py, midX, (py + y) / 2f)
                path.quadTo(x, y, x, y)
            }
            fill.lineTo(x, y)
        }
        fill.lineTo(centerXOf(segments - 1), zy)
        fill.close()
        canvas.drawPath(fill, curveFillPaint)
        canvas.drawPath(path, curvePaint)
    }

    private fun drawBars(canvas: Canvas) {
        val zy = zeroY()
        for (i in 0 until segments) {
            val x = centerXOf(i)
            val y = knobYOf(values[i])
            // 轨迹（整高，浅）
            canvas.drawLine(x, trackTop(), x, trackBottom(), barPaint)
            // 激活段（中线→手柄）
            canvas.drawLine(x, zy, x, y, activeBarPaint)
            // 手柄圆点
            val r = dp(7f)
            canvas.drawCircle(x, y, r, knobPaint)
            canvas.drawCircle(x, y, r, knobStrokePaint)
        }
    }

    private fun drawLabels(canvas: Canvas) {
        for (i in 0 until segments) {
            val x = centerXOf(i)
            canvas.drawText(hzLabels.getOrElse(i) { "" }, x, height - dp(6f), textPaint)
        }
    }

    // ---------- 交互 ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    dragging = nearestIndex(event.x)
                }
                val idx = dragging
                if (idx in 0 until segments) {
                    values[idx] = valueFromY(event.y)
                    invalidate()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestIndex(x: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in 0 until segments) {
            val d = kotlin.math.abs(centerXOf(i) - x)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}