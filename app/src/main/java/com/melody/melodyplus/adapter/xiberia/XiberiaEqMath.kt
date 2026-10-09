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

    /** 各段频率标签（官方 `LocalResData.getHzValues`：6 段 / 12 段两套）。 */
    fun hzLabels(segments: Int): List<String> = when (segments) {
        6 -> listOf("64", "160", "400", "1K", "2.5K", "6K")
        12 -> listOf(
            "32", "64", "125", "250", "500", "1K",
            "2K", "4K", "8K", "12K", "16K", "20K",
        )
        else -> (1..segments).map { "B$it" }
    }
}
