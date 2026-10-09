package com.melody.melodyplus.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备注册表纯逻辑契约测试：锁定名称归一化/匹配、MAC 规范化、设备展示策略。
 * 均为无 Android 依赖的纯函数，此前仅有间接覆盖。
 */
class DeviceRegistryPureTest {

    @Test
    fun normalizeDeviceNameTrimsCollapsesAndUppercases() {
        assertEquals("OPPO ENCO X3", DeviceNameRuleState.normalizeDeviceName("  oppo   enco  x3 "))
        assertEquals("", DeviceNameRuleState.normalizeDeviceName(null))
        assertEquals("A B", DeviceNameRuleState.normalizeDeviceName("a\t\n b"))
    }

    @Test
    fun isAirPodsNameMatchesCaseInsensitively() {
        assertTrue(DeviceNameRuleState.isAirPodsName("AirPods Pro"))
        assertTrue(DeviceNameRuleState.isAirPodsName("my airpods 3"))
        assertFalse(DeviceNameRuleState.isAirPodsName("OPPO Enco X3"))
        assertFalse(DeviceNameRuleState.isAirPodsName(null))
    }

    @Test
    fun nameRulesNormalizeAndFilterAirPodsFromAllNames() {
        val state = DeviceNameRuleState(
            defaultNames = setOf("  oppo enco ", "AirPods Pro"),
            exceptionNames = setOf("enco x3"),
            profileId = "xiberia.mc05",
        )
        // allNames 归一化 + 剔除 AirPods
        assertTrue("OPPO ENCO" in state.allNames)
        assertTrue("ENCO X3" in state.allNames)
        assertFalse(state.allNames.any { it.contains("AIRPODS") })

        assertTrue(state.matches("oppo enco"))
        assertTrue(state.matches("  ENCO   X3 "))
        assertFalse(state.matches(""))          // 空白
        assertFalse(state.matches(null))
        assertFalse(state.matches("AirPods Pro")) // AirPods 永不算匹配
        assertFalse(state.matches("unknown device"))
    }

    @Test
    fun normalizeMacOrNullAcceptsColonDashAndRejectsBad() {
        assertEquals("AABBCCDDEEFF", normalizeMacOrNull("aa:bb:cc:dd:ee:ff"))
        assertEquals("AABBCCDDEEFF", normalizeMacOrNull("AA-BB-CC-DD-EE-FF"))
        assertEquals("AABBCCDDEEFF", normalizeMacOrNull("  aabbccddeeff  "))
        // 长度不足 / 非十六进制 → null
        assertNull(normalizeMacOrNull("AABBCC"))
        assertNull(normalizeMacOrNull("ZZBBCCDDEEFF"))
        assertNull(normalizeMacOrNull(""))
        assertNull(normalizeMacOrNull(null))
    }

    @Test
    fun presentationPolicyPrefersOfficialThenModule() {
        assertEquals(
            DevicePresentationPolicy.OFFICIAL_SUPPORTED,
            DevicePresentationPolicyResolver.resolve(originalOfficialVisible = true, moduleSupported = true),
        )
        assertEquals(
            DevicePresentationPolicy.MODULE_SUPPORTED,
            DevicePresentationPolicyResolver.resolve(originalOfficialVisible = false, moduleSupported = true),
        )
        assertEquals(
            DevicePresentationPolicy.UNSUPPORTED,
            DevicePresentationPolicyResolver.resolve(originalOfficialVisible = false, moduleSupported = false),
        )
    }
}