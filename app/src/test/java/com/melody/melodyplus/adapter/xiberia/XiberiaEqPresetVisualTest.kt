// 文件: app/src/test/java/com/melody/melodyplus/adapter/xiberia/XiberiaEqPresetVisualTest.kt
// 说明: 官方 EQ 预设「视觉资源映射」真值断言（防回归）。
//
// 真源 = com.cchip.desheng v1.9.26 各 `DefaultEqGain$<NAME>.<init>` 的
//        R$drawable(icon) / R$mipmap(eq_bg) / R$string(nameId) sget 真值（npmcp 提取）。
package com.melody.melodyplus.adapter.xiberia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiberiaEqPresetVisualTest {

    @Test
    fun officialVisualMapping_matchesApkTruth() {
        // (name, iconIndex, bgIndex, officialLabel) —— 逐条对齐官方子类构造器
        val expected = listOf(
            Quad("DEFAULT", 0, 1, "Xiberia经典"),
            Quad("FASHION", 1, 2, "流行"),
            Quad("ROCK", 2, 3, "摇滚经典"),
            Quad("ORIGINAL_SOUND", 3, 4, "原声"),
            Quad("MEGA_BASS", 4, 5, "超重低音"),
            Quad("MEGA_BASS_MAX", 4, 5, "超重低音"),   // 与 MEGA_BASS 同资源（官方如此）
            Quad("DJ", 5, 6, "DJ音效"),
            Quad("HIFI", 6, 7, "HIFI现场"),
            Quad("TREBLE_BOOST", 7, 8, "高音增强"),
            Quad("JAZZ", 8, 9, "爵士"),
            Quad("MOVIE", 9, 10, "影院音效"),
            Quad("CLASSICAL", 10, 11, "古典"),
            Quad("HUMAN_VOICE", 11, 12, "清澈人声"),
        )
        expected.forEach { e ->
            val p = XiberiaEqPresets.byName(e.name)
            assertTrue("缺少预设 ${e.name}", p != null)
            assertEquals("${e.name} iconIndex", e.icon, p!!.iconIndex)
            assertEquals("${e.name} bgIndex", e.bg, p.bgIndex)
            assertEquals("${e.name} officialLabel", e.label, p.officialLabel)
            assertEquals("${e.name} displayLabel 应取官方文案", e.label, p.displayLabel)
        }
    }

    @Test
    fun bgIndex_withinAssetRange() {
        // 仅带视觉映射的标准预设（SPATIAL_SOUND 属音效类，官方网格中无卡片）
        XiberiaEqPresets.STANDARD.filter { it.iconIndex >= 0 }.forEach { p ->
            assertTrue("${p.name} bgIndex=${p.bgIndex} 应在 1..12", p.bgIndex in 1..12)
            assertTrue("${p.name} iconIndex=${p.iconIndex} 应在 0..11", p.iconIndex in 0..11)
        }
        // 有图标映射就必须有背景映射，反之亦然（成对出现）
        XiberiaEqPresets.STANDARD.forEach { p ->
            assertEquals(
                "${p.name} 图标/背景映射应成对: icon=${p.iconIndex} bg=${p.bgIndex}",
                p.iconIndex >= 0,
                p.bgIndex > 0,
            )
        }
    }

    @Test
    fun bgIndex_notSharedAcrossDistinctStandardPresets() {
        // 背景图除 MEGA_BASS/MAX 官方刻意共用外，其余应各不相同
        val sharedAllowed = setOf("MEGA_BASS", "MEGA_BASS_MAX")
        val byBg = XiberiaEqPresets.STANDARD
            .filterNot { it.name in sharedAllowed || it.bgIndex == 0 }
            .groupBy { it.bgIndex }
        byBg.forEach { (bg, list) ->
            assertEquals("bg_$bg 被多个预设占用: ${list.map { it.name }}", 1, list.size)
        }
    }

    @Test
    fun nonStandardPresets_haveNoVisualMapping() {
        // 音效类 / 游戏类不在官方预设网格里 → 不应带视觉映射
        listOf(
            XiberiaEqPresets.REDUCE_NOISE, XiberiaEqPresets.SPATIAL_SOUND,
            XiberiaEqPresets.FOOT_EFFECT, XiberiaEqPresets.DONGLE_DEFAULT,
            XiberiaEqPresets.CUSTOM_1, XiberiaEqPresets.GAME_CS,
            XiberiaEqPresets.GAME_PUBG,
        ).forEach { p ->
            assertEquals("${p.name} 不应映射背景", 0, p.bgIndex)
            assertEquals("${p.name} 不应映射图标", -1, p.iconIndex)
        }
    }

    @Test
    fun displayLabel_fallsBackToLabelWhenNoOfficial() {
        val p = XiberiaEqPresets.GAME_CS
        assertEquals("无官方文案时应回落 label", p.label, p.displayLabel)
    }

    private data class Quad(val name: String, val icon: Int, val bg: Int, val label: String)
}