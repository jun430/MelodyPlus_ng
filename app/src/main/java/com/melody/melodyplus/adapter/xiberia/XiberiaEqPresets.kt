// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaEqPresets.kt
// 说明: cchip(XIBERIA) 官方 App「均衡器」预设库 —— 25 个官方预设 × 10 段增益真值 + 按条件筛选。
//       真源 1:1 复刻，用于 EQ 子页的「预设列表」与官方默认值回填。
//
// 真源（npmcp workspace 36144653，com.cchip.desheng v1.9.26）：
//   - 预设枚举 : Lcom/cchip/desheng/rm/DefaultEqGain;                    （25 个子类）
//   - 单预设值 : Lcom/cchip/desheng/rm/DefaultEqGain$<NAME>;->getEqGain()[I（每预设固定 10 段）
//   - 构造器   : DefaultEqGain(String name,int ordinal,int command,int nameId,int iconId,int backgroundId)
//   - 按码查找 : DefaultEqGain$Companion;->findEqGainByCommandId(Ljava/lang/Integer;)LDefaultEqGain;
//   - 条件筛选 : Lcom/cchip/desheng/account/utils/LocalResData$Companion;->getEqDefault(14×Z) / getGameEq()
//   - 频段真值 : Lcom/cchip/desheng/account/utils/LocalResData$Companion;->getHzValues(Z)[Ljava/lang/String;
package com.melody.melodyplus.adapter.xiberia

object XiberiaEqPresets {

    /**
     * 一个官方 EQ 预设。
     *
     * @param command      官方 command 码（`DefaultEqGain.<NAME>.getCommand()`）。
     *                     自定义 EQ 下发时作为 `0x0806` payload 首字节的候选（见 CUSTOM_1=0x11）。
     * @param name         官方枚举名（与 `DefaultEqGain$<NAME>` 一致）。
     * @param label        中文显示名（对应官方 `R.string` 资源；硬编码避免宿主资源依赖）。
     * @param gains        10 段固定增益真值（官方 `getEqGain():IntArray`）。
     * @param officialStringId 官方 nameId 资源名（取证留档，运行时不用）。
     * @param bgIndex      官方 `eq_bg_<N>` 背景编号（1..12），来自子类 `<init>` 的 `R$mipmap` sget。
     * @param iconIndex    官方 `dev_eq_system_icon<N>_selector` 图标编号（0..11），同上。
     * @param officialLabel 官方 `R.string` 真实文案（真值；可能与历史 label 不同）。
     */
    data class Preset(
        val command: Int,
        val name: String,
        val label: String,
        val gains: IntArray,
        val officialStringId: String,
        /** 是否为「标准可调预设」（官方 getEqDefault 14 bool 可枚举的 14 项之一）。 */
        val standard: Boolean = false,
        /** 是否为游戏预设（官方 getGameEq() 返回的 4 项）。 */
        val game: Boolean = false,
        /** 是否为音效类（漏音抑制/空间音效/脚步增强/dongle）。 */
        val soundEffect: Boolean = false,
        /** 官方背景编号（eq_bg_N）；0 = 无（回退合成渐变）。 */
        val bgIndex: Int = 0,
        /** 官方图标编号（dev_eq_system_iconN_selector）；-1 = 无。 */
        val iconIndex: Int = -1,
        /** 官方文案（R.string 真值）。 */
        val officialLabel: String = "",
    ) {
        /** 卡片显示文案（优先官方真值）。 */
        val displayLabel: String get() = officialLabel.ifEmpty { label }

        override fun equals(other: Any?): Boolean =
            other is Preset && other.command == command && other.name == name

        override fun hashCode(): Int = command * 31 + name.hashCode()
    }

    // ==================================================================
    // 一、25 个官方预设（真值 1:1）
    // ==================================================================

    /** 默认（全 0，标准预设 #0）。官方：name=dev_eq_presuppose_0 / icon0 / bg_1 / 「Xiberia经典」。 */
    val DEFAULT = Preset(0x00, "DEFAULT", "默认", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "dev_eq_presuppose_0", standard = true,
        bgIndex = 1, iconIndex = 0, officialLabel = "Xiberia经典")

    /** 流行。官方：dev_eq_presuppose_1 / icon1 / bg_2 / 「流行」。 */
    val FASHION = Preset(0x01, "FASHION", "流行", intArrayOf(
        -6, -6, -3, -1, 3, 3, 3, 4, 0, 0,
    ), "dev_eq_presuppose_1", standard = true,
        bgIndex = 2, iconIndex = 1, officialLabel = "流行")

    /** 摇滚。官方：dev_eq_presuppose_2 / icon2 / bg_3 / 「摇滚经典」。 */
    val ROCK = Preset(0x02, "ROCK", "摇滚", intArrayOf(
        3, 3, 3, 3, 0, 2, -3, -3, -3, -4,
    ), "dev_eq_presuppose_2", standard = true,
        bgIndex = 3, iconIndex = 2, officialLabel = "摇滚经典")

    /** 原声。官方：dev_eq_presuppose_3 / icon3 / bg_4 / 「原声」。 */
    val ORIGINAL_SOUND = Preset(0x03, "ORIGINAL_SOUND", "原声", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "dev_eq_presuppose_3", standard = true,
        bgIndex = 4, iconIndex = 3, officialLabel = "原声")

    /** 超重低音。官方：dev_eq_presuppose_mc01_4 / icon4 / bg_5 / 「超重低音」。 */
    val MEGA_BASS = Preset(0x04, "MEGA_BASS", "超重低音", intArrayOf(
        3, 3, -2, -3, 0, 0, 2, 3, 3, 3,
    ), "dev_eq_presuppose_mc01_4", standard = true,
        bgIndex = 5, iconIndex = 4, officialLabel = "超重低音")

    /** 超重低音 MAX。官方：dev_eq_presuppose_4 / icon4 / bg_5 / 「超重低音」（与 MEGA_BASS 同资源）。 */
    val MEGA_BASS_MAX = Preset(0x05, "MEGA_BASS_MAX", "超重低音 MAX", intArrayOf(
        2, 2, 7, 2, 0, -2, 0, -4, 0, -3,
    ), "dev_eq_presuppose_4", standard = true,
        bgIndex = 5, iconIndex = 4, officialLabel = "超重低音")

    /** DJ 音效。官方：dev_eq_presuppose_mc01_5 / icon5 / bg_6 / 「DJ音效」。 */
    val DJ = Preset(0x06, "DJ", "DJ音效", intArrayOf(
        2, 0, 7, 3, -5, 0, 0, -3, -4, 0,
    ), "dev_eq_presuppose_mc01_5", standard = true,
        bgIndex = 6, iconIndex = 5, officialLabel = "DJ音效")

    /** HiFi。官方：dev_eq_presuppose_mc01_6 / icon6 / bg_7 / 「HIFI现场」。 */
    val HIFI = Preset(0x07, "HIFI", "HiFi", intArrayOf(
        0, 0, 6, 3, 2, 0, 0, 0, 0, 2,
    ), "dev_eq_presuppose_mc01_6", standard = true,
        bgIndex = 7, iconIndex = 6, officialLabel = "HIFI现场")

    /** 高音增强。官方：dev_eq_presuppose_mc01_7 / icon7 / bg_8 / 「高音增强」。 */
    val TREBLE_BOOST = Preset(0x08, "TREBLE_BOOST", "高音增强", intArrayOf(
        0, 0, 5, 1, -2, 0, 0, 2, 2, 2,
    ), "dev_eq_presuppose_mc01_7", standard = true,
        bgIndex = 8, iconIndex = 7, officialLabel = "高音增强")

    /** 爵士。官方：dev_eq_presuppose_mc01_8 / icon8 / bg_9 / 「爵士」。 */
    val JAZZ = Preset(0x09, "JAZZ", "爵士", intArrayOf(
        1, 0, 4, -2, 2, 0, 0, 2, 0, 2,
    ), "dev_eq_presuppose_mc01_8", standard = true,
        bgIndex = 9, iconIndex = 8, officialLabel = "爵士")

    /** 影院音效（官方文案「影院音效」，非历史「电影」）。dev_eq_presuppose_mc01_9 / icon9 / bg_10。 */
    val MOVIE = Preset(0x0A, "MOVIE", "电影", intArrayOf(
        1, 0, 6, -2, -2, 0, 0, -2, 0, -2,
    ), "dev_eq_presuppose_mc01_9", standard = true,
        bgIndex = 10, iconIndex = 9, officialLabel = "影院音效")

    /** 古典。官方：dev_eq_presuppose_mc01_10 / icon10 / bg_11 / 「古典」。 */
    val CLASSICAL = Preset(0x0B, "CLASSICAL", "古典", intArrayOf(
        1, 0, 5, -2, -4, 0, 0, 2, 0, 2,
    ), "dev_eq_presuppose_mc01_10", standard = true,
        bgIndex = 11, iconIndex = 10, officialLabel = "古典")

    /** 清澈人声。官方：dev_eq_presuppose_mc01_11 / icon11 / bg_12 / 「清澈人声」。 */
    val HUMAN_VOICE = Preset(0x0C, "HUMAN_VOICE", "清澈人声", intArrayOf(
        1, 0, 3, 3, 2, 3, 0, -2, 0, -2,
    ), "dev_eq_presuppose_mc01_11", standard = true,
        bgIndex = 12, iconIndex = 11, officialLabel = "清澈人声")

    /** 漏音抑制模式（官方 SOUND_EFFECT_LY）。 */
    val REDUCE_NOISE = Preset(0x0D, "REDUCE_NOISE", "漏音抑制模式", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "sound_effect_ly", soundEffect = true)

    /** 空间音效（官方 SOUND_EFFECT_KJ）。 */
    val SPATIAL_SOUND = Preset(0x0E, "SPATIAL_SOUND", "空间音效", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "sound_effect_kj", soundEffect = true)

    /** 脚步增强（官方 SOUND_EFFECT_FOOT）。 */
    val FOOT_EFFECT = Preset(0x0F, "FOOT_EFFECT", "脚步增强", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "foot_effect", soundEffect = true)

    /** 接收器默认（dongle）。 */
    val DONGLE_DEFAULT = Preset(0x10, "DONGLE_DEFAULT", "接收器默认", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "dongle_default_eq", soundEffect = true)

    /** 自定义（官方 `CUSTOM_1`；**自定义 EQ 下发 subCmd = 0x11**）。 */
    val CUSTOM_1 = Preset(0x11, "CUSTOM_1", "自定义", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "dev_eq_custom")

    /** 游戏 · CS。 */
    val GAME_CS = Preset(0x23, "GAME_CS", "CS", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "game_cs", game = true)

    /** 游戏 · 三角洲行动。 */
    val GAME_DELTA_FORCE = Preset(0x24, "GAME_DELTA_FORCE", "三角洲行动", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "game_delta_force", game = true)

    /** 游戏 · 无畏契约。 */
    val GAME_VALORANT = Preset(0x25, "GAME_VALORANT", "无畏契约", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "game_valorant", game = true)

    /** 游戏 · PUBG。 */
    val GAME_PUBG = Preset(0x26, "GAME_PUBG", "PUBG", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "game_pubg", game = true)

    /** 游戏 · 和平精英（⚠️ 官方 nameId 资源引用为 game_arena_breakout）。 */
    val GAME_PEACE_ELITE = Preset(0x27, "GAME_PEACE_ELITE", "和平精英", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "game_arena_breakout", game = true)

    /** 游戏 · 竞技场突围（⚠️ 官方 nameId 资源引用为 game_peace_elite）。 */
    val GAME_ARENA_BREAKOUT = Preset(0x28, "GAME_ARENA_BREAKOUT", "竞技场突围", intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ), "game_peace_elite", game = true)

    /** 全量预设（按官方 command 升序）。 */
    val ALL: List<Preset> = listOf(
        DEFAULT, FASHION, ROCK, ORIGINAL_SOUND, MEGA_BASS, MEGA_BASS_MAX,
        DJ, HIFI, TREBLE_BOOST, JAZZ, MOVIE, CLASSICAL, HUMAN_VOICE,
        REDUCE_NOISE, SPATIAL_SOUND, FOOT_EFFECT, DONGLE_DEFAULT, CUSTOM_1,
        GAME_CS, GAME_DELTA_FORCE, GAME_VALORANT, GAME_PUBG,
        GAME_PEACE_ELITE, GAME_ARENA_BREAKOUT,
    )

    private val BY_COMMAND: Map<Int, Preset> = ALL.associateBy { it.command }
    private val BY_NAME: Map<String, Preset> = ALL.associateBy { it.name }

    // ==================================================================
    // 二、官方查询 API（1:1 对齐）
    // ==================================================================

    /** 对应官方 `DefaultEqGain$Companion.findEqGainByCommandId(Integer)`。 */
    fun byCommand(command: Int?): Preset? = command?.let { BY_COMMAND[it] }

    fun byName(name: String?): Preset? = name?.let { BY_NAME[it] }

    /** 官方 14 个「标准预设」（`getEqDefault` 可枚举的 14 项，按官方顺序）。 */
    val STANDARD: List<Preset> = listOf(
        DEFAULT, FASHION, ROCK, ORIGINAL_SOUND, MEGA_BASS, MEGA_BASS_MAX,
        DJ, HIFI, TREBLE_BOOST, JAZZ, MOVIE, CLASSICAL, HUMAN_VOICE, SPATIAL_SOUND,
    )

    /** 官方 `getGameEq()` 的 4 个游戏预设。 */
    val GAME: List<Preset> = listOf(
        GAME_DELTA_FORCE, GAME_VALORANT, GAME_PEACE_ELITE, GAME_ARENA_BREAKOUT,
    )

    /**
     * 对应官方 `LocalResData$Companion.getEqDefault(14×bool):ArrayList<EqDefault>`。
     *
     * 参数顺序 = 官方 `d2` 名称序：
     * `default, fashion, rock, originalSound, megaBass, megaBassMax, dj, hifi,
     *  trebleBoost, jazz, movie, classical, humanVoice, spatialSound`
     *
     * ⚠️ 官方 `$default` 合成方法在 bit 未设置时取默认值（前 4 位默认 true）。
     *    本函数为**显式传参**版本，不做默认值推断；调用方按需给全 14 位。
     */
    @Suppress("LongParameterList")
    fun getEqDefault(
        default: Boolean = true,
        fashion: Boolean = true,
        rock: Boolean = true,
        originalSound: Boolean = true,
        megaBass: Boolean = true,
        megaBassMax: Boolean = false,
        dj: Boolean = true,
        hifi: Boolean = true,
        trebleBoost: Boolean = true,
        jazz: Boolean = true,
        movie: Boolean = true,
        classical: Boolean = true,
        humanVoice: Boolean = true,
        spatialSound: Boolean = true,
    ): List<Preset> = buildList {
        if (default) add(DEFAULT)
        if (fashion) add(FASHION)
        if (rock) add(ROCK)
        if (originalSound) add(ORIGINAL_SOUND)
        if (megaBass) add(MEGA_BASS)
        if (megaBassMax) add(MEGA_BASS_MAX)
        if (dj) add(DJ)
        if (hifi) add(HIFI)
        if (trebleBoost) add(TREBLE_BOOST)
        if (jazz) add(JAZZ)
        if (movie) add(MOVIE)
        if (classical) add(CLASSICAL)
        if (humanVoice) add(HUMAN_VOICE)
        if (spatialSound) add(SPATIAL_SOUND)
    }

    /** 对应官方 `getGameEq()`。 */
    fun getGameEq(): List<Preset> = GAME

    // ==================================================================
    // 三、频段真值（对应官方 `LocalResData$Companion.getHzValues(Z)`）
    // ==================================================================

    /**
     * 官方频段标签真值：
     * - `isMc01Max == true`  → 7 段 `["120","250","500","1k","2k","4k","8k"]`
     * - `isMc01Max == false` → 10 段 `["30","60","120","250","500","1k","2k","4k","8k","16k"]`
     */
    fun hzValues(isMc01Max: Boolean): Array<String> =
        if (isMc01Max) {
            arrayOf("120", "250", "500", "1k", "2k", "4k", "8k")
        } else {
            arrayOf("30", "60", "120", "250", "500", "1k", "2k", "4k", "8k", "16k")
        }

    /** 取前 n 段频段标签（UI 按段数截取；n 超长补齐空串）。 */
    fun hzValuesFor(segments: Int, isMc01Max: Boolean = false): List<String> {
        val base = hzValues(isMc01Max)
        return List(segments) { base.getOrElse(it) { "" } }
    }
}
