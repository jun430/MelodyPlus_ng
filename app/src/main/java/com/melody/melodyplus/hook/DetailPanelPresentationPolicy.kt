package com.melody.melodyplus.hook

import com.melody.melodyplus.core.AncMode
import com.melody.melodyplus.core.BatteryState

internal data class DetailModeItemSnapshot(
    val tag: String?,
    val selected: Boolean,
    val moduleMode: AncMode?,
)

internal data class DetailBatterySlots(
    val primary: Int?,
    val left: Int?,
    val right: Int?,
) {
    val hasAny: Boolean
        get() = primary != null || left != null || right != null
}

internal object DetailPanelPresentationPolicy {
    /**
     * MC05 详情页不再使用原厂降噪模式条。
     *
     * 依据官方 APK `com.cchip.desheng` 真值：`Product$MC05` 未 override
     * `isSupportNoiseControl()` → 继承基类 false；`getNoiseModeList()` → 返回 null；
     * 抓包全程 0x0B01(NOISE_SET)/0x0B02(NOISE_GET) 各 0 次。
     * 即 MC05（OWS）无降噪能力，原厂「弱降噪/关闭/通透」条是错误注入，须移除。
     *
     * 空列表 = 不生成任何 mode item；配套 [MelodyPanelHook.submitModeItems] 内的
     * hideModeStrip() 把控件容器整体 GONE。其它仍有降噪能力的型号如需恢复，回填
     * `listOf(AncMode.OFF, AncMode.NOISE_CANCELLING, AncMode.TRANSPARENCY)` 即可。
     */
    val modeOrder: List<AncMode> = emptyList()

    fun melodyModeType(mode: AncMode): Int =
        when (mode) {
            AncMode.OFF -> 1
            AncMode.NOISE_CANCELLING,
            AncMode.WIND_REDUCTION,
            -> 5

            AncMode.TRANSPARENCY,
            AncMode.ADAPTIVE,
            -> 2
        }

    fun moduleModeForTag(tag: String?): AncMode? =
        when (tag?.toIntOrNull()) {
            1 -> AncMode.OFF
            5 -> AncMode.NOISE_CANCELLING
            2 -> AncMode.TRANSPARENCY
            else -> null
        }

    fun modeForProtocolIndex(index: Int?): AncMode? =
        when (index) {
            0, 1 -> AncMode.OFF
            2 -> AncMode.NOISE_CANCELLING
            3 -> AncMode.TRANSPARENCY
            else -> null
        }

    fun resolvedMode(pending: AncMode?, cached: AncMode?, fallback: AncMode): AncMode =
        pending ?: cached ?: fallback

    fun isInjectedLayout(items: List<DetailModeItemSnapshot>, selectedMode: AncMode): Boolean {
        // 空清单（MC05 已摘除降噪条）：只要当前也是空，即视为「已注入空」，避免宿主反复重填。
        if (modeOrder.isEmpty()) return items.isEmpty()
        if (items.size != modeOrder.size) return false
        return items.zip(modeOrder).all { (item, expectedMode) ->
            item.moduleMode == expectedMode &&
                item.tag == melodyModeType(expectedMode).toString() &&
                item.selected == (expectedMode == selectedMode)
        }
    }

    fun batterySlots(state: BatteryState?): DetailBatterySlots {
        val left = state?.left.validBatteryLevel()
        val right = state?.right.validBatteryLevel()
        val caseBattery = state?.caseBattery.validBatteryLevel()
        val single = state?.single.validBatteryLevel()
        return DetailBatterySlots(
            primary = caseBattery ?: single.takeIf { left == null && right == null },
            left = left,
            right = right,
        )
    }

    private fun Int?.validBatteryLevel(): Int? = this?.takeIf { it in 0..100 }
}
