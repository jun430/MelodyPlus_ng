// 文件: app/src/test/java/com/melody/melodyplus/adapter/xiberia/XiberiaCommandsCoverageTest.kt
// 说明: 官方 CommandId 全表覆盖断言。
// 真源 = com.cchip.desheng `Lcom/cchip/desheng/constant/CommandId;`（npmcp 逐字段提取，共 41 条）。
// 目的: 官方每新增/更名一条命令码，此测试立刻红灯，防止协议表静默漂移。
package com.melody.melodyplus.adapter.xiberia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiberiaCommandsCoverageTest {

    /** 官方 `CommandId` 全表真值（name → code）。顺序按官方字段序。 */
    private val officialCommandIds: Map<String, Int> = mapOf(
        "ALL_KEY_GET" to 0x0314,
        "ANTI_WIND_NOISE_GET" to 0x0E22,
        "ANTI_WIND_NOISE_SET" to 0x0E21,
        "BASS_BOOST_GET" to 0x0E12,
        "BASS_BOOST_SET" to 0x0E11,
        "BATTERY_INFO_GET" to 0x0A11,
        "BATTERY_INFO_REPORT" to 0x0A12,
        "CHILD_MODE_GET" to 0x0E1A,
        "CHILD_MODE_SET" to 0x0E19,
        "DONGLE_STATE_GET_OR_REPORT" to 0x0E25,
        "DUAL_DEVICE_GET" to 0x0E0C,
        "DUAL_DEVICE_SET" to 0x0E0B,
        "EQ_CUSTOM_GAIN_SET" to 0x0806,
        "EQ_ENABLE_GET" to 0x0802,
        "EQ_ENABLE_SET" to 0x0801,
        "EQ_MODE_GET" to 0x0804,
        "EQ_MODE_SET" to 0x0803,
        "LDAC_GET" to 0x0E05,
        "LDAC_SET" to 0x0E04,
        "LHDC_GET" to 0x0E14,
        "LHDC_SET" to 0x0E13,
        "LOW_DELAY_GET" to 0x0C02,
        "LOW_DELAY_REPORT" to 0x0C03,
        "LOW_DELAY_SET" to 0x0C01,
        "NOISE_GET" to 0x0B02,
        "NOISE_REPORT" to 0x0B03,
        "NOISE_SET" to 0x0B01,
        "NOISE_STYLE_GET" to 0x0E20,
        "NOISE_STYLE_SET" to 0x0E1F,
        "OFFLINE_VOICE_GET" to 0x0E1C,
        "OFFLINE_VOICE_SET" to 0x0E1B,
        "SOUND_EFFECT_GET" to 0x0E0E,
        "SOUND_EFFECT_SET" to 0x0E0D,
        "SPATIAL_SOUND_SWITCH_GET" to 0x0E10,
        "SPATIAL_SOUND_SWITCH_SET" to 0x0E0F,
        "TOUCH_GET" to 0x0E18,
        "TOUCH_SET" to 0x0E17,
        "USER_ALL_EQ_GET" to 0x0807,
        "VERSION_GET" to 0x0D01,
        "VOLUME_GEAR_GET" to 0x0E27,
        "VOLUME_GEAR_SET" to 0x0E26,
    )

    /** 从 XiberiaCommands 对象里收集所有 Int 常量值。 */
    private fun moduleCodes(): Set<Int> =
        XiberiaCommands::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .map { it.isAccessible = true; it.getInt(null) }
            .toSet()

    @Test
    fun officialTableHas41Entries() {
        assertEquals(41, officialCommandIds.size)
    }

    @Test
    fun everyOfficialCommandId_isPresentInModuleTable() {
        val module = moduleCodes()
        val missing = officialCommandIds.filterValues { it !in module }
        assertTrue("模块命令表缺少官方码: $missing", missing.isEmpty())
    }

    /** 官方 41 条码值互不重复（若有重复说明官方表本身解析出错）。 */
    @Test
    fun officialCodesAreUnique() {
        val values = officialCommandIds.values
        assertEquals(values.size, values.distinct().size)
    }

    /** 关键别名一致性：模块内一码多名时，值必须同一。 */
    @Test
    fun aliasConsistency() {
        assertEquals(XiberiaCommands.GAME_MODE, XiberiaCommands.SWITCH_A)
        assertEquals(XiberiaCommands.GAME_MODE_GET, XiberiaCommands.LE_AUDIO_MODE)
        assertEquals(XiberiaCommands.TONE_LEVEL, XiberiaCommands.VOLUME_GEAR_SET)
        assertEquals(XiberiaCommands.LEAK_SUPPRESS, XiberiaCommands.NOISE_SET)
        assertEquals(XiberiaCommands.LEAK_SUPPRESS_GET, XiberiaCommands.NOISE_GET)
    }
}