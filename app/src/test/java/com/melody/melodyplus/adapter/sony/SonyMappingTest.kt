package com.melody.melodyplus.adapter.sony

import com.melody.melodyplus.bridge.DeviceProfiles
import com.melody.melodyplus.core.AncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyMappingTest {
    @Test
    fun mapsSonyAncModesToCoreModes() {
        assertEquals(AncMode.OFF, SonyAncMode.OFF.toCoreAncMode())
        assertEquals(AncMode.NOISE_CANCELLING, SonyAncMode.NOISE_CANCELING.toCoreAncMode())
        assertEquals(AncMode.TRANSPARENCY, SonyAncMode.AMBIENT_SOUND.toCoreAncMode())
        assertEquals(AncMode.WIND_REDUCTION, SonyAncMode.WIND_NOISE_REDUCTION.toCoreAncMode())
    }

    @Test
    fun wf1000xm3ProfileAdvertisesSourceWindReductionCapability() {
        val capabilities = DeviceProfiles.sonyWf1000Xm3.capabilities

        assertTrue(AncMode.WIND_REDUCTION in capabilities.ancModes)
    }

    @Test
    fun wf1000xm3ProfileUsesDualAndCaseBattery() {
        val capabilities = DeviceProfiles.sonyWf1000Xm3.capabilities

        assertFalse(capabilities.supportsSingleBattery)
        assertTrue(capabilities.supportsLeftRightBattery)
        assertTrue(capabilities.supportsCaseBattery)
    }
}
