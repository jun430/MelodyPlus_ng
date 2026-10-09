// 文件: app/src/test/java/com/melody/melodyplus/adapter/xiberia/XiberiaEqPresetsTest.kt
// 说明: 官方 EQ 预设库真值断言（防回归）。真源 = com.cchip.desheng `DefaultEqGain$<NAME>`.
package com.melody.melodyplus.adapter.xiberia

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiberiaEqPresetsTest {

    @Test
    fun allPresetsHave10Segments() {
        XiberiaEqPresets.ALL.forEach { p ->
            assertEquals("预设 ${p.name} 段数应为 10", 10, p.gains.size)
        }
    }

    @Test
    fun allCommandCount_is24() {
        // 官方 DefaultEqGain 枚举共 24 个子类（ALL 含 24 项；0x00–0x11 + 0x23–0x28）
        assertEquals(24, XiberiaEqPresets.ALL.size)
    }

    @Test
    fun byCommand_matchesOfficial() {
        assertEquals("FASHION", XiberiaEqPresets.byCommand(0x01)?.name)
        assertEquals("CUSTOM_1", XiberiaEqPresets.byCommand(0x11)?.name)
        assertEquals("GAME_VALORANT", XiberiaEqPresets.byCommand(0x25)?.name)
        assertNull(XiberiaEqPresets.byCommand(0x99))
    }

    @Test
    fun fashionGains_realValue() {
        assertArrayEquals(
            intArrayOf(-6, -6, -3, -1, 3, 3, 3, 4, 0, 0),
            XiberiaEqPresets.FASHION.gains,
        )
    }

    @Test
    fun rockGains_realValue() {
        assertArrayEquals(
            intArrayOf(3, 3, 3, 3, 0, 2, -3, -3, -3, -4),
            XiberiaEqPresets.ROCK.gains,
        )
    }

    @Test
    fun megaBassGains_realValue() {
        assertArrayEquals(
            intArrayOf(3, 3, -2, -3, 0, 0, 2, 3, 3, 3),
            XiberiaEqPresets.MEGA_BASS.gains,
        )
    }

    @Test
    fun djGains_realValue() {
        assertArrayEquals(
            intArrayOf(2, 0, 7, 3, -5, 0, 0, -3, -4, 0),
            XiberiaEqPresets.DJ.gains,
        )
    }

    @Test
    fun custom1_isSubCmd0x11() {
        assertEquals(0x11, XiberiaEqPresets.CUSTOM_1.command)
    }

    @Test
    fun standardList_has14() {
        assertEquals(14, XiberiaEqPresets.STANDARD.size)
    }

    @Test
    fun gameList_has4() {
        assertEquals(4, XiberiaEqPresets.GAME.size)
        assertEquals("三角洲行动", XiberiaEqPresets.GAME[0].label)
    }

    @Test
    fun getEqDefault_14params_filterWorks() {
        // 只放行 fashion+rock
        val list = XiberiaEqPresets.getEqDefault(
            default = false, fashion = true, rock = true, originalSound = false,
            megaBass = false, megaBassMax = false, dj = false, hifi = false,
            trebleBoost = false, jazz = false, movie = false, classical = false,
            humanVoice = false, spatialSound = false,
        )
        assertEquals(listOf("FASHION", "ROCK"), list.map { it.name })
    }

    @Test
    fun getEqDefault_allTrue_has14() {
        val list = XiberiaEqPresets.getEqDefault(
            default = true, fashion = true, rock = true, originalSound = true,
            megaBass = true, megaBassMax = true, dj = true, hifi = true,
            trebleBoost = true, jazz = true, movie = true, classical = true,
            humanVoice = true, spatialSound = true,
        )
        assertEquals(14, list.size)
    }

    @Test
    fun hzValues_officialTruth() {
        assertArrayEquals(
            arrayOf("120", "250", "500", "1k", "2k", "4k", "8k"),
            XiberiaEqPresets.hzValues(isMc01Max = true),
        )
        assertArrayEquals(
            arrayOf("30", "60", "120", "250", "500", "1k", "2k", "4k", "8k", "16k"),
            XiberiaEqPresets.hzValues(isMc01Max = false),
        )
    }

    @Test
    fun hzValuesFor_truncates() {
        assertEquals(6, XiberiaEqPresets.hzValuesFor(6).size)
        assertEquals("30", XiberiaEqPresets.hzValuesFor(6)[0])
        assertEquals(7, XiberiaEqPresets.hzValuesFor(7, isMc01Max = true).size)
    }

    @Test
    fun adaptPresetGains_resamples() {
        val ten = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val six = XiberiaEqMath.adaptPresetGains(ten, 6)
        assertEquals(6, six.size)
        // 等距采样：i*10/6 = 0,1,3,5,6,8
        assertEquals("首段保留", 0, six[0])
        assertTrue("末段 >= 中间", six[5] >= six[0])
        val ten2 = XiberiaEqMath.adaptPresetGains(ten, 10)
        assertArrayEquals(ten, ten2)
    }

    @Test
    fun eqGainToUi_roundTrip_isStableAtCenter() {
        // VALUE_6_NEW 默认居中 ui = maxValue/2 + 0x1E = 45 + 30 = 75
        // 居中 ui → gain = (75/2+6)*5+30 = (37+6)*5+30 = 245（官方式整除，75/2 丢 1）
        // 逆运算 ui = ((245-30)/5-6)*2 = 74 → 与 75 差 1（整除损失，官方算式固有）
        val mode = XiberiaProductCatalog.GainMode.VALUE_6_NEW
        val ui = XiberiaEqMath.resetUiValues(6, mode)
        val gains = XiberiaEqMath.uiValuesToEqGain(ui, mode)
        val ui2 = XiberiaEqMath.eqGainToUiValues(gains, mode)
        // 允许 ±2 容差（整除损失；往返稳定 = 连续两次逆运算收敛）
        ui.indices.forEach { i ->
            assertTrue(
                "段 $i 往返偏差应 ≤2 (ui=${ui[i]} ui2=${ui2[i]})",
                kotlin.math.abs(ui[i] - ui2[i]) <= 2,
            )
        }
        // 二次往返应完全收敛（稳定点）
        val gains2 = XiberiaEqMath.uiValuesToEqGain(ui2, mode)
        val ui3 = XiberiaEqMath.eqGainToUiValues(gains2, mode)
        assertArrayEquals("二次往返应稳定", ui2, ui3)
    }
}