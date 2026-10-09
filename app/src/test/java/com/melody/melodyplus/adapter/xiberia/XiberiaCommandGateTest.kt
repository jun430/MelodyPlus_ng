// 文件: app/src/test/java/com/melody/melodyplus/adapter/xiberia/XiberiaCommandGateTest.kt
// 说明: SET 侧能力位门控断言（对齐官方 DeviceActivity 的 isSupportXxx 过滤）。
package com.melody.melodyplus.adapter.xiberia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiberiaCommandGateTest {

    @Test
    fun unknownProduct_deniesEverything() {
        assertFalse(XiberiaProductCatalog.supportsCommand(0x999, XiberiaCommands.GAME_MODE))
        assertFalse(XiberiaProductCatalog.supportsCommand(0x999, XiberiaCommands.FW_VERSION))
    }

    @Test
    fun unsupportedProduct_deniesEverything() {
        // MC01(0x110) 官方 isSupport=false → 全拒
        assertFalse(XiberiaProductCatalog.supportsCommand(XiberiaProductCatalog.PID_MC01, XiberiaCommands.GAME_MODE))
        assertFalse(XiberiaProductCatalog.supportsCommand(XiberiaProductCatalog.PID_MC01, XiberiaCommands.FW_VERSION))
    }

    @Test
    fun genericCommands_allowedForEverySupportedModel() {
        val generics = listOf(
            XiberiaCommands.FW_VERSION,
            XiberiaCommands.BATTERY,
            XiberiaCommands.EQ_CUSTOM,
            XiberiaCommands.USER_ALL_EQ_GET,
            XiberiaCommands.ALL_KEY_GET,
        )
        XiberiaProductCatalog.supported().forEach { p ->
            generics.forEach { cmd ->
                assertTrue(
                    "${p.model} 通用码 0x${cmd.toString(16)} 应放行",
                    XiberiaProductCatalog.supportsCommand(p.productId, cmd),
                )
            }
        }
    }

    @Test
    fun mc05_allowsGameBassLdac_deniesNoiseSpatial() {
        val pid = XiberiaProductCatalog.PID_MC05
        assertTrue(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.GAME_MODE))
        assertTrue(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.BASS_BOOST_SET))
        assertTrue(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.LDAC))
        assertTrue(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.LDAC_GET))
        // MC05 无降噪（noiseControl=false）→ 拒 NOISE_SET
        assertFalse(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.NOISE_SET))
        // MC05 无空间音频 → 拒
        assertFalse(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.SPATIAL_SOUND_SET))
        // MC05 无 LHDC → 拒
        assertFalse(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.LHDC_SET))
    }

    @Test
    fun dm03_allowsNoiseStyle_othersDeny() {
        assertTrue(
            XiberiaProductCatalog.supportsCommand(
                XiberiaProductCatalog.PID_DM03, XiberiaCommands.NOISE_STYLE_SET,
            ),
        )
        assertTrue(
            XiberiaProductCatalog.supportsCommand(
                XiberiaProductCatalog.PID_DM03, XiberiaCommands.NOISE_STYLE_GET,
            ),
        )
        // MC05 无降噪风格能力 → 拒
        assertFalse(
            XiberiaProductCatalog.supportsCommand(
                XiberiaProductCatalog.PID_MC05, XiberiaCommands.NOISE_STYLE_SET,
            ),
        )
    }

    @Test
    fun mc01Max_allowsChildOfflineTouch_othersDeny() {
        val pid = XiberiaProductCatalog.PID_MC01_MAX
        assertTrue(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.CHILD_MODE_SET))
        assertTrue(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.OFFLINE_VOICE_SET))
        assertTrue(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.TOUCH_SET))
        assertTrue(XiberiaProductCatalog.supportsCommand(pid, XiberiaCommands.LHDC_SET))
        // MC05 无儿童模式 → 拒
        assertFalse(
            XiberiaProductCatalog.supportsCommand(
                XiberiaProductCatalog.PID_MC05, XiberiaCommands.CHILD_MODE_SET,
            ),
        )
    }

    @Test
    fun supportedCommands_areSelfConsistent() {
        XiberiaProductCatalog.supported().forEach { p ->
            val set = XiberiaProductCatalog.supportedCommands(p.productId)
            assertTrue("${p.model} 支持码集不应为空", set.isNotEmpty())
            set.forEach { cmd ->
                assertTrue(
                    "${p.model} 支持码集内的 0x${cmd.toString(16)} 必须通过门控",
                    XiberiaProductCatalog.supportsCommand(p.productId, cmd),
                )
            }
        }
    }
}