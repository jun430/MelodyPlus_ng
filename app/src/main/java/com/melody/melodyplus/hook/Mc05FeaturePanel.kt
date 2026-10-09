// 文件: app/src/main/java/com/melody/melodyplus/hook/Mc05FeaturePanel.kt
// 说明: XIBERIA MC05 详情页「耳机功能」面板清单（PLAN_MC05_Feature_Panel_v2 §2 块②）。
//
// 真源: 官方 APK com.cchip.desheng `Product$MC05` override 真值（isSupportXxx）。
//   MC05（OWS / cchip BT_MATE / productId 0x118）真实支持的功能子集：
//     游戏模式 LOW_DELAY  0x0C01 / 0x0C02     ✅
//     LDAC 高音质 LDAC    0x0E04 / 0x0E05     ✅（与游戏模式互斥，官方 show10SecondDialog）
//     低音增强 BASS_BOOST 0x0E11 / 0x0E12     ✅
//     双设备连接 DUAL     0x0E0B / 0x0E0C     ✅
//     触控锁 TOUCH        0x0E17 / 0x0E18     ✅
//     音量档位 VOLUME_GEAR 0x0E26 / 0x0E27    ✅（档位型，本面板先做布尔项，档位另接）
//     音效 SOUND_EFFECT   0x0E0D / 0x0E0E     ✅（KJ/LY 两项，另接）
//     自定义 EQ           0x0801 / 0x0802     ✅（子页面，另接）
//   ❌ 不支持（不得注入）：降噪/通透、空间音频、LHDC、儿童模式、离线语音、抗风噪、耳内检测。
//
// 本文件只承载「纯数据 + 纯判定」，不含 Android 视图逻辑；注入/写命令在 MelodyPanelHook
// 内实现（复用其 private helper addHostPreference / invokeHostMethod / hostMethods）。
package com.melody.melodyplus.hook

import com.melody.melodyplus.adapter.xiberia.XiberiaCommands

internal object Mc05FeaturePanel {

    /** 一个布尔功能项定义。 */
    data class Feature(
        /** preference key（melodyplus_ 前缀，避免与宿主 key 撞）。 */
        val key: String,
        /** 中文标题。 */
        val title: String,
        /** 摘要说明。 */
        val summary: String,
        /** 官方 SET 命令码（XiberiaCommands 真源）。 */
        val cmdSet: Int,
        /** 官方 GET 命令码（回读用；当前 GET 解析未校准，仅打点）。 */
        val cmdGet: Int,
        /** 互斥组（同组内至多一个为开；null = 不互斥）。 */
        val mutexGroup: String? = null,
    )

    /** LDAC ↔ 游戏模式 互斥组（官方 §0.3：开 LDAC 会提示「与游戏/双设备冲突」）。 */
    const val MUTEX_LDAC_GAME: String = "ldac_game"

    /** 面板项在「耳机功能」分类内的 order 基数（DSEE 用 1000，本面板排其后）。 */
    const val ORDER_BASE: Int = 2000

    /** 面板注入的目标分类 key（与 DSEE 同分类，实测该分类在详情页可达）。 */
    const val CATEGORY_KEY: String = "earphone"

    /** MC05 布尔功能清单（顺序即 UI 顺序）。 */
    val FEATURES: List<Feature> = listOf(
        Feature(
            key = "melodyplus_mc05_game",
            title = "游戏模式",
            summary = "降低传输延迟，适合游戏与视频",
            cmdSet = XiberiaCommands.GAME_MODE,        // 0x0C01 LOW_DELAY_SET
            cmdGet = XiberiaCommands.GAME_MODE_GET,    // 0x0C02 LOW_DELAY_GET
            mutexGroup = MUTEX_LDAC_GAME,
        ),
        Feature(
            key = "melodyplus_mc05_ldac",
            title = "LDAC 高音质",
            summary = "高解析音频传输（与游戏模式互斥）",
            cmdSet = XiberiaCommands.LDAC,             // 0x0E04 LDAC_SET
            cmdGet = XiberiaCommands.LDAC_GET,         // 0x0E05 LDAC_GET
            mutexGroup = MUTEX_LDAC_GAME,
        ),
        Feature(
            key = "melodyplus_mc05_bass",
            title = "低音增强",
            summary = "增强低频量感",
            cmdSet = XiberiaCommands.BASS_BOOST_SET,   // 0x0E11
            cmdGet = XiberiaCommands.BASS_BOOST_GET,   // 0x0E12
        ),
        Feature(
            key = "melodyplus_mc05_dual",
            title = "双设备连接",
            summary = "同时连接两台设备并快速切换",
            cmdSet = XiberiaCommands.TRI_STATE,        // 0x0E0B DUAL_DEVICE_SET
            cmdGet = XiberiaCommands.DUAL_DEVICE_GET,  // 0x0E0C
        ),
        Feature(
            key = "melodyplus_mc05_touch",
            title = "触控锁",
            summary = "锁定耳机触控，防止误触",
            cmdSet = XiberiaCommands.TOUCH_SET,        // 0x0E17 TOUCH_SET
            cmdGet = XiberiaCommands.TOUCH_GET,        // 0x0E18
        ),
    )

    /** 取同互斥组内的其它项（用于联动置灰/关断）。 */
    fun mutexSiblings(feature: Feature): List<Feature> =
        feature.mutexGroup
            ?.let { group -> FEATURES.filter { it.mutexGroup == group && it.key != feature.key } }
            ?: emptyList()

    fun featureByKey(key: String?): Feature? = FEATURES.firstOrNull { it.key == key }
}
