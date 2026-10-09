package com.melody.melodyplus.hook

import com.melody.melodyplus.core.AncMode
import com.melody.melodyplus.core.BatteryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPanelPresentationPolicyTest {
    @Test
    fun moduleModeTagsResolveToExpectedAncModes() {
        assertEquals(AncMode.OFF, DetailPanelPresentationPolicy.moduleModeForTag("1"))
        assertEquals(AncMode.NOISE_CANCELLING, DetailPanelPresentationPolicy.moduleModeForTag("5"))
        assertEquals(AncMode.TRANSPARENCY, DetailPanelPresentationPolicy.moduleModeForTag("2"))
        assertNull(DetailPanelPresentationPolicy.moduleModeForTag("0"))
    }

    @Test
    fun injectedLayoutMatchesEmptyStripAfterMc05Removal() {
        // 设计（见 DetailPanelPresentationPolicy.modeOrder KDoc）：MC05 已摘除原厂降噪条，
        //   modeOrder 为空 → 空清单即视为「已注入空」，selectedMode 无意义（不再参与判定）。
        val injected = snapshots(selectedMode = AncMode.NOISE_CANCELLING)
        assertTrue(injected.isEmpty())
        assertTrue(DetailPanelPresentationPolicy.isInjectedLayout(injected, AncMode.NOISE_CANCELLING))
        assertTrue(DetailPanelPresentationPolicy.isInjectedLayout(injected, AncMode.OFF))
        // 非空清单（旧宿主残留条目）不视为「已注入」。
        val hostLeftover = listOf(DetailModeItemSnapshot("1", true, AncMode.OFF))
        assertFalse(DetailPanelPresentationPolicy.isInjectedLayout(hostLeftover, AncMode.OFF))
    }

    @Test
    fun hostPositionTagsAreNotMistakenForInjectedLayout() {
        val hostItems = listOf(
            DetailModeItemSnapshot("0", false, null),
            DetailModeItemSnapshot("1", true, null),
            DetailModeItemSnapshot("2", false, null),
        )

        assertFalse(DetailPanelPresentationPolicy.isInjectedLayout(hostItems, AncMode.NOISE_CANCELLING))
    }

    @Test
    fun hostProtocolIndicesResolveThroughTheirProtocolValues() {
        assertEquals(AncMode.OFF, DetailPanelPresentationPolicy.modeForProtocolIndex(1))
        assertEquals(AncMode.NOISE_CANCELLING, DetailPanelPresentationPolicy.modeForProtocolIndex(2))
        assertEquals(AncMode.TRANSPARENCY, DetailPanelPresentationPolicy.modeForProtocolIndex(3))
        assertNull(DetailPanelPresentationPolicy.modeForProtocolIndex(5))
    }

    @Test
    fun pendingModeWinsAndRemovingItRollsBackToCachedMode() {
        assertEquals(
            AncMode.NOISE_CANCELLING,
            DetailPanelPresentationPolicy.resolvedMode(
                pending = AncMode.NOISE_CANCELLING,
                cached = AncMode.OFF,
                fallback = AncMode.TRANSPARENCY,
            ),
        )
        assertEquals(
            AncMode.OFF,
            DetailPanelPresentationPolicy.resolvedMode(
                pending = null,
                cached = AncMode.OFF,
                fallback = AncMode.TRANSPARENCY,
            ),
        )
    }

    @Test
    fun dualBatteryAndCaseUseAllThreeSlots() {
        val slots = DetailPanelPresentationPolicy.batterySlots(
            BatteryState(left = 70, right = 80, caseBattery = 55),
        )

        assertEquals(55, slots.primary)
        assertEquals(70, slots.left)
        assertEquals(80, slots.right)
        assertTrue(slots.hasAny)
    }

    @Test
    fun singleBatteryUsesPrimarySlotOnly() {
        val slots = DetailPanelPresentationPolicy.batterySlots(BatteryState(single = 60))

        assertEquals(60, slots.primary)
        assertNull(slots.left)
        assertNull(slots.right)
    }

    @Test
    fun missingAndOutOfRangeBatteryValuesStayHidden() {
        val partial = DetailPanelPresentationPolicy.batterySlots(
            BatteryState(single = 101, left = 42, right = -1, caseBattery = null),
        )
        val missing = DetailPanelPresentationPolicy.batterySlots(null)

        assertNull(partial.primary)
        assertEquals(42, partial.left)
        assertNull(partial.right)
        assertFalse(missing.hasAny)
    }

    private fun snapshots(selectedMode: AncMode): List<DetailModeItemSnapshot> =
        DetailPanelPresentationPolicy.modeOrder.map { mode ->
            DetailModeItemSnapshot(
                tag = DetailPanelPresentationPolicy.melodyModeType(mode).toString(),
                selected = mode == selectedMode,
                moduleMode = mode,
            )
        }
}
