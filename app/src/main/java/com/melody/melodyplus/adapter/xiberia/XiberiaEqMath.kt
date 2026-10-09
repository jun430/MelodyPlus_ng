// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaEqMath.kt
// 说明: 官方 EQ 竖条进度 ↔ 耳机增益换算（真源 `Lcom/cchip/desheng/rm/EqComputeUtils$Companion;`）。
//
// 真源（官方 APK com.cchip.desheng v1.9.26，npmcp 实读 workspace）：
//   EqComputeUtils$Companion.uiValuesToEqGain([I, GainMode)[I
//     VALUE_6     : gain[i] = (ui[i] / 2 + cardinal) * 5
//     VALUE_6_NEW : gain[i] = (ui[i] / 2 + cardinal) * 5 + 0x1E
//     else(12)    : gain[i] = (ui[i] + cardinal) * 5
//   cardinal: VALUE_6=6, VALUE_6_NEW=6, VALUE_12=12
//   EqComputeUtils$Companion.resetEqData(GainMode)[I
//     默认 ui = (maxValue / 2)（VALUE_6 / VALUE_12）；VALUE_6_NEW 额外 +0x1E
//
// ⚠️ 官方这套算式含「+30」偏移（VALUE_6_NEW），逆向值域待真机抓帧复核；
//    本模块 UI 侧**照抄官方算式**，不自创映射，以保证下发字节与官方 App 完全一致。
package com.melody.melodyplus.adapter.xiberia

object XiberiaEqMath {

    /** 官方 `GainMode` 的段数（cardinal）。 */
    fun cardinalOf(mode: XiberiaProductCatalog.GainMode): Int = mode.cardinal

    /** 官方 `GainMode` 的增益上限（maxValue）。 */
    fun maxValueOf(mode: XiberiaProductCatalog.GainMode): Int = mode.maxValue

    /**
     * 竖条 UI 进度 → 耳机增益（对应官方 `uiValuesToEqGain`）。
     *
     * @param uiValues 每条竖条当前进度（官方 SeekBar progress）。
     */
    fun uiValuesToEqGain(
        uiValues: IntArray,
        mode: XiberiaProductCatalog.GainMode,
    ): IntArray {
        val cardinal = mode.cardinal
        return IntArray(uiValues.size) { i ->
            val ui = uiValues[i]
            when (mode) {
                XiberiaProductCatalog.GainMode.VALUE_6 -> (ui / 2 + cardinal) * 5
                XiberiaProductCatalog.GainMode.VALUE_6_NEW -> (ui / 2 + cardinal) * 5 + 0x1E
                XiberiaProductCatalog.GainMode.VALUE_12 -> (ui + cardinal) * 5
            }
        }
    }

    /**
     * 默认竖条进度（对应官方 `resetEqData`，全段居中 = 0 dB 位置）。
     *
     * @param segments 段数
     */
    fun resetUiValues(segments: Int, mode: XiberiaProductCatalog.GainMode): IntArray {
        val base = when (mode) {
            XiberiaProductCatalog.GainMode.VALUE_6,
            XiberiaProductCatalog.GainMode.VALUE_12,
            -> mode.maxValue / 2

            XiberiaProductCatalog.GainMode.VALUE_6_NEW -> mode.maxValue / 2 + 0x1E
        }
        return IntArray(segments) { base }
    }

    /** 官方 UI 进度范围：0 .. (maxValue*2)（SeekBar max 由 card 与 maxValue 推算，逆向待验证）。 */
    fun uiMaxOf(mode: XiberiaProductCatalog.GainMode): Int = mode.maxValue * 2

    /**
     * 耳机增益 → 竖条 UI 进度（对应官方 `uiValuesToEqGain` 的逆运算）。
     * 用于「选中官方预设 → 回填滑块」。
     *
     * 逆式（由 uiValuesToEqGain 反解）：
     *   VALUE_6     : ui = (gain/5 - cardinal) * 2
     *   VALUE_6_NEW : ui = ((gain - 0x1E)/5 - cardinal) * 2
     *   VALUE_12    : ui = gain/5 - cardinal
     */
    fun eqGainToUiValues(
        gains: IntArray,
        mode: XiberiaProductCatalog.GainMode,
    ): IntArray {
        val cardinal = mode.cardinal
        val uiMax = uiMaxOf(mode)
        return IntArray(gains.size) { i ->
            val g = gains[i]
            val ui = when (mode) {
                XiberiaProductCatalog.GainMode.VALUE_6 ->
                    (g / 5 - cardinal) * 2
                XiberiaProductCatalog.GainMode.VALUE_6_NEW ->
                    ((g - 0x1E) / 5 - cardinal) * 2
                XiberiaProductCatalog.GainMode.VALUE_12 ->
                    g / 5 - cardinal
            }
            ui.coerceIn(0, uiMax)
        }
    }

    /**
     * 把官方 10 段预设增益适配到当前段数（官方预设固定 10 段；本模块编辑器按 cardinal 段）。
     * 等距采样，保证两端保留、中间均匀。
     */
    fun adaptPresetGains(presetGains: IntArray, segments: Int): IntArray {
        if (presetGains.isEmpty() || segments <= 0) return IntArray(segments)
        if (segments == presetGains.size) return presetGains.copyOf()
        return IntArray(segments) { i ->
            val src = (i * presetGains.size / segments).coerceIn(0, presetGains.size - 1)
            presetGains[src]
        }
    }

    /**
     * 各段频率标签（官方 `LocalResData.getHzValues`：7 段 / 10 段两套为真源）。
     *
     * ⚠️ 官方只有 7 段（MC01_MAX）/ 10 段（其余）两套；本模块编辑器段数由 `GainMode.cardinal`
     *    决定（6 / 12）。此处：10 段直接对齐官方；6 / 12 段由官方 10 段等距采样（自造，标注待校准）。
     */
    fun hzLabels(segments: Int): List<String> = when (segments) {
        7, 10 -> XiberiaEqPresets.hzValuesFor(segments, isMc01Max = segments == 7)
        else -> {
            val base = XiberiaEqPresets.hzValues(isMc01Max = false)
            List(segments) { i ->
                base[(i * base.size / segments).coerceIn(0, base.size - 1)]
            }
        }
    }
}
