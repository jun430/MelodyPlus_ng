package com.melody.melodyplus.adapter.sony

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SonyPayloadsTest {
    @Test
    fun buildsV1DseePayloads() {
        assertArrayEquals(
            byteArrayOf(0xE6.toByte(), 0x02),
            SonyPayloads.buildGetDseePayload(SonyProtocolVersion.V1),
        )
        assertArrayEquals(
            byteArrayOf(0xE8.toByte(), 0x02, 0x00, 0x01),
            SonyPayloads.buildSetDseePayload(SonyProtocolVersion.V1, enabled = true),
        )
    }

    @Test
    fun parsesV1DseeState() {
        assertEquals(
            true,
            SonyPayloads.parseDseeEnabled(
                byteArrayOf(0xE7.toByte(), 0x02, 0x00, 0x01),
                SonyProtocolVersion.V1,
            ),
        )
        assertEquals(
            false,
            SonyPayloads.parseDseeEnabled(
                byteArrayOf(0xE9.toByte(), 0x02, 0x00, 0x00),
                SonyProtocolVersion.V1,
            ),
        )
    }

    @Test
    fun buildsAndParsesV2DseePayloads() {
        assertArrayEquals(
            byteArrayOf(0xE6.toByte(), 0x01),
            SonyPayloads.buildGetDseePayload(SonyProtocolVersion.V2),
        )
        assertArrayEquals(
            byteArrayOf(0xE8.toByte(), 0x01, 0x00),
            SonyPayloads.buildSetDseePayload(SonyProtocolVersion.V2, enabled = false),
        )
        assertEquals(
            true,
            SonyPayloads.parseDseeEnabled(
                byteArrayOf(0xE7.toByte(), 0x01, 0x01),
                SonyProtocolVersion.V2,
            ),
        )
    }

    @Test
    fun v1BuildsNoiseCancelingPayloadWithWindCapability() {
        val payload = SonyPayloads.buildV1SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.NOISE_CANCELING,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            windSupported = true,
        )

        assertArrayEquals(
            byteArrayOf(0x68.asByte(), 0x02, 0x11, 0x02, 0x02, 0x01, 0x00, 0x00),
            payload,
        )
    }

    @Test
    fun v1BuildsAmbientPayloadWithLevelAndFocus() {
        val payload = SonyPayloads.buildV1SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.AMBIENT_SOUND,
                ambientLevel = 14,
                focusOnVoice = true,
            ),
            windSupported = true,
        )

        assertArrayEquals(
            byteArrayOf(0x68.asByte(), 0x02, 0x11, 0x02, 0x00, 0x01, 0x01, 0x0E),
            payload,
        )
    }

    @Test
    fun v2BuildsDualModeNoiseCancelingPayload() {
        val payload = SonyPayloads.buildV2SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.NOISE_CANCELING,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            asmType = SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        )

        assertArrayEquals(
            byteArrayOf(0x68.asByte(), 0x17, 0x01, 0x01, 0x00, 0x00, 0x0A),
            payload!!,
        )
    }

    @Test
    fun v2RejectsWindWhenAsmTypeDoesNotSupportWind() {
        val payload = SonyPayloads.buildV2SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.WIND_NOISE_REDUCTION,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            asmType = SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        )

        assertNull(payload)
    }

    @Test
    fun v2RejectsNoiseCancelingForAmbientOnlyAsmType() {
        val payload = SonyPayloads.buildV2SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.NOISE_CANCELING,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            asmType = SonyAsmType.ASM_SEAMLESS,
        )

        assertNull(payload)
    }

    @Test
    fun v2BuildsWindPayloadWhenAsmTypeSupportsWind() {
        val payload = SonyPayloads.buildV2SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.WIND_NOISE_REDUCTION,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            asmType = SonyAsmType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        )

        assertArrayEquals(
            byteArrayOf(0x68.asByte(), 0x15, 0x01, 0x01, 0x00, 0x03, 0x00, 0x0A),
            payload!!,
        )
    }

    @Test
    fun parsesV2AmbientState() {
        val state = SonyPayloads.parseV2AmbientState(
            payload = byteArrayOf(0x67, 0x17, 0x00, 0x01, 0x01, 0x01, 0x0C),
            asmType = SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        )

        assertEquals(SonyAncMode.AMBIENT_SOUND, state?.mode)
        assertEquals(true, state?.focusOnVoice)
        assertEquals(12, state?.ambientLevel)
    }

    @Test
    fun parsesDualBatteryState() {
        val battery = SonyPayloads.parseBatteryState(
            byteArrayOf(
                SonyPayloadTypeV1T1.COMMON_RET_BATTERY_LEVEL.asByte(),
                SonyBatteryType.DUAL.asByte(),
                70,
                0,
                90.toByte(),
                1,
            ),
        )

        assertEquals(70, battery?.left)
        assertEquals(90, battery?.right)
        assertNull(battery?.caseBattery)
    }

    @Test
    fun parsesCaseBatteryState() {
        val battery = SonyPayloads.parseBatteryState(
            byteArrayOf(
                SonyPayloadTypeV1T1.COMMON_RET_BATTERY_LEVEL.asByte(),
                SonyBatteryType.CASE.asByte(),
                55,
                0,
            ),
        )

        assertEquals(55, battery?.caseBattery)
        assertNull(battery?.left)
        assertNull(battery?.right)
    }

    @Test
    fun ignoresMissingBatterySlots() {
        val battery = SonyPayloads.parseBatteryState(
            byteArrayOf(
                SonyPayloadTypeV1T1.COMMON_RET_BATTERY_LEVEL.asByte(),
                SonyBatteryType.DUAL.asByte(),
                0,
                0,
                80.toByte(),
                0,
            ),
        )

        assertNull(battery?.left)
        assertEquals(80, battery?.right)
    }
}
