package com.melody.melodyplus.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePresentationPolicyTest {
    @Test
    fun officialVisibleWinsOverModuleSupport() {
        val policy = DevicePresentationPolicyResolver.resolve(
            originalOfficialVisible = true,
            moduleSupported = true,
        )

        assertEquals(DevicePresentationPolicy.OFFICIAL_SUPPORTED, policy)
    }

    @Test
    fun boundSonyWithoutOfficialEntryUsesModuleSupport() {
        val policy = DevicePresentationPolicyResolver.resolve(
            originalOfficialVisible = false,
            moduleSupported = true,
        )

        assertEquals(DevicePresentationPolicy.MODULE_SUPPORTED, policy)
    }

    @Test
    fun ordinaryUnsupportedDeviceDoesNotShowEntry() {
        val policy = DevicePresentationPolicyResolver.resolve(
            originalOfficialVisible = false,
            moduleSupported = false,
        )

        assertEquals(DevicePresentationPolicy.UNSUPPORTED, policy)
    }

    @Test
    fun wf1000xm3NameMatchesTemporaryModuleSupport() {
        assertEquals(true, DeviceProfiles.sonyWf1000Xm3.matchesName("WF-1000XM3"))
    }

    @Test
    fun airPodsNameDoesNotMatchTemporaryModuleSupport() {
        assertEquals(false, DeviceProfiles.sonyWf1000Xm3.matchesName("AirPods Pro"))
    }

    @Test
    fun defaultNameRuleMatchesWf1000xm3() {
        val rules = DeviceNameRuleState(
            defaultNames = DeviceRegistryStore.defaultSupportedNames,
            exceptionNames = emptySet(),
            profileId = DeviceProfiles.SONY_WF1000XM3,
        )

        assertEquals(true, rules.matches("WF-1000XM3"))
    }

    @Test
    fun exceptionNameRuleIsNormalized() {
        val rules = DeviceNameRuleState(
            defaultNames = DeviceRegistryStore.defaultSupportedNames,
            exceptionNames = setOf("  my   xm3  ", "AirPods Pro"),
            profileId = DeviceProfiles.SONY_WF1000XM3,
        )

        assertEquals(true, rules.matches("MY XM3"))
        assertEquals(false, rules.matches("AirPods"))
        assertEquals(false, rules.matches("AirPods Pro"))
    }

    @Test
    fun airPodsExceptionNamesAreFilteredFromRules() {
        val rules = DeviceNameRuleState(
            defaultNames = DeviceRegistryStore.defaultSupportedNames,
            exceptionNames = setOf("AirPods Max"),
            profileId = DeviceProfiles.SONY_WF1000XM3,
        )

        assertEquals(false, "AIRPODS MAX" in rules.allNames)
        assertEquals(false, rules.matches("AirPods Max"))
    }
}
