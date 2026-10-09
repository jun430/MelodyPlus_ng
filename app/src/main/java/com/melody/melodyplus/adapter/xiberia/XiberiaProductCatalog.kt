// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaProductCatalog.kt
// 说明: cchip(XIBERIA) 官方 App 型号能力真源 —— 详情页「耳机功能」动态自适配的唯一数据基石。
//
// 真源（官方 APK com.cchip.desheng v1.9.26 / versionCode 109026）：
//   - 型号基类  : Lcom/cchip/desheng/constant/Product;              （22 个 isSupportXxx/getter）
//   - 型号子类  : Lcom/cchip/desheng/constant/Product$<NAME>;       （逐型号覆写真值）
//   - 命令码    : Lcom/cchip/desheng/constant/CommandId;            （44 条）
//   - 能力枚举  : SwitchType{BUTTON=0,PAGE=1,NOT_SUPPORT=2}
//                 NoiseMode{CLOSE=1,OPEN=2,TRANSPARENCY=3,ANTI_WIND_NOISE=4,COMFORT_MODE=5}
//                 SoundEffectMode{KJ=0x0E,LY=0x0D,FOOT=0x0F}
//                 GainMode{VALUE_12(12,120),VALUE_6(6,60),VALUE_6_NEW(6,90)}
//
// 提取通道: npmcp  workspaceId=46691997
//   np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/Product$MC05;
//
// ⚠️ 与旧表 XiberiaModelProfiles.CAP_* 的关系：旧表是「参考模块华为兼容版」的粗粒度能力位，
//    与本表的官方 22 位语义不同、且型号 productId 号段混用。本表为**官方真值**，
//    面板与门控应统一以本表为准；旧表保留仅为兼容既有调用点，不再扩展。
package com.melody.melodyplus.adapter.xiberia

object XiberiaProductCatalog {

    /** 官方互斥组：LDAC ↔ 游戏模式（`Product$MC05` 开 LDAC 弹「与游戏/双设备冲突」提示）。
     *  组内开启一项时必须关断其它项，否则设备端行为未定义（实测可出现两项同时"开"）。 */
    const val MUTEX_LDAC_GAME: String = "ldac_game"

    // ==================================================================
    // 一、能力枚举（对齐官方常量类真值）
    // ==================================================================

    /** 官方 `Lcom/cchip/desheng/constant/SwitchType;`：LDAC/LHDC 的呈现形态。 */
    enum class SwitchType { BUTTON, PAGE, NOT_SUPPORT }

    /** 官方 `Lcom/cchip/desheng/constant/ChipType;`。 */
    enum class ChipType { BT, BT_MATE, JL }

    /** 官方 `Lcom/cchip/desheng/constant/EarType;`。 */
    enum class EarType { TWS, OWS, HEADSET }

    /** 官方 `Lcom/cchip/desheng/rm/GainMode;`（自定义 EQ 档位数与增益上限）。 */
    enum class GainMode(val cardinal: Int, val maxValue: Int) {
        /** 12 段，±120（官方 clinit: const/16 0xc / 0x78）。 */
        VALUE_12(12, 120),
        /** 6 段，±60（0x3c）。 */
        VALUE_6(6, 60),
        /** 6 段，±90（0x5a）。 */
        VALUE_6_NEW(6, 90),
    }

    /**
     * 官方 `Lcom/cchip/desheng/constant/NoiseMode;`。
     * [command] 是写入 `NOISE_SET(0x0B01)` payload 的档位值。
     */
    enum class NoiseMode(val command: Int) {
        CLOSE(1),          // 关闭
        OPEN(2),           // 降噪
        TRANSPARENCY(3),   // 通透
        ANTI_WIND_NOISE(4),// 抗风噪
        COMFORT_MODE(5),   // 舒适
    }

    /** 官方 `Lcom/cchip/desheng/constant/SoundEffectMode;`（[modeValue] 为写入 `SOUND_EFFECT_SET` 的字节）。 */
    enum class SoundEffect(val modeValue: Int, val label: String) {
        /** 官方 SOUND_EFFECT_LY：文案「降低漏音 / 漏音抑制模式」。 */
        LY(0x0D, "漏音抑制模式"),
        /** 官方 SOUND_EFFECT_KJ：文案「空间音效」。 */
        KJ(0x0E, "空间音效"),
        /** 官方 SOUND_EFFECT_FOOT：文案「脚步增强」（MC05 不含）。 */
        FOOT(0x0F, "脚步增强"),
    }

    /**
     * 自定义 EQ 的官方配置（段数 / 增益上限 / 下发子命令）。
     *
     * - `cardinal` / `maxValue` 来自官方 `GainMode.<clinit>`（VALUE_6=6/60、VALUE_6_NEW=6/90、VALUE_12=12/120）。
     * - `subCmd` = 官方 `DefaultEqGain.CUSTOM_1.getCommand()` = `0x11`（见 `DefaultEqGain$CUSTOM_1.<init>`）。
     */
    data class EqConfig(val cardinal: Int, val maxValue: Int, val subCmd: Int = 0x11)

    /** 官方 `GainMode` → `EqConfig` 映射（真源 `Product$<NAME>.getCustomEqMode()`）。 */
    private fun eqConfigOf(mode: GainMode?): EqConfig? = when (mode) {
        null -> null
        GainMode.VALUE_6 -> EqConfig(6, 60)
        GainMode.VALUE_6_NEW -> EqConfig(6, 90)
        GainMode.VALUE_12 -> EqConfig(12, 120)
    }

    /** 官方 `showBatteryType()` 返回。 */
    enum class BatteryType { SINGLE, PAIR }

    // ==================================================================
    // 二、型号档案（官方 18 型 · 覆写真值）
    // ==================================================================

    /**
     * 一个型号的官方能力投影。
     *
     * 字段全部默认取**基类真值**（`Product.smali` 中非覆写方法的返回值），
     * 各型号只覆写自己有差异的项 —— 与官方 `Product$<NAME>` 的覆写结构一一对应。
     */
    data class ProductCapabilities(
        val productId: Int,
        val model: String,
        /** 官方 `getServerVersionType()`：用于握手/版本协商的型号串。 */
        val serverVersionType: String,
        /** 官方 `isSupport()`：false 表示该型号在官方 App 中整体不开放（MC01/MC02 等）。 */
        val supported: Boolean = false,
        val chipType: ChipType = ChipType.BT,
        val earType: EarType? = null,
        val eqMode: GainMode? = null,
        val batteryType: BatteryType? = null,
        /** 是否有「左右耳分体图」（官方 getLeftAndRightImg() 长度 2 即 true）。 */
        val leftAndRightImg: Boolean = false,

        // ---- 22 个能力位（默认 = 官方基类真值）----
        val ai: Boolean = true,                    // 基类默认 true（Product.smali isSupportAI → const/4 v0, 0x1）
        val antiWindNoise: Boolean = false,
        val bassBoost: Boolean = false,
        val batteryCase: Boolean = false,
        val childMode: Boolean = false,
        val dongle: Boolean = false,
        val drainWater: Boolean = false,
        val dualDevice: Boolean = false,
        val healthReminder: Boolean = false,
        val hiRes: Boolean = false,
        val inEar: Boolean = false,
        val ldac: SwitchType = SwitchType.NOT_SUPPORT,
        val lhdc: SwitchType = SwitchType.NOT_SUPPORT,
        val lowLatency: Boolean = false,
        val noiseControl: Boolean = false,
        val offlineVoice: Boolean = false,
        val qqMusicAudio: Boolean = false,
        val showDeviceDialog: Boolean = false,
        val spatialSound: Boolean = false,
        val supportNoiseStyle: Boolean = false,    // 官方 supportNoiseStyle() 仅 DM03 覆写
        val touchLockMode: Boolean = false,
        val volumeGear: Boolean = false,

        /** 官方 `getNoiseModeList()`：该型号可选降噪档位（null = 未覆写 = 无降噪列表）。 */
        val noiseModeCount: Int? = null,
        val showAddPage: Boolean = false,
        val showCleanRemindPop: Boolean = false,
        /** 官方 `getSoundEffectItems()` 覆写为非 null 即支持音效页。 */
        val soundEffect: Boolean = false,
        /**
         * 官方 `getSoundEffectItems()` 的具体项（真源 `Product$<NAME>.getSoundEffectItems()`）。
         * 空列表时由 [panelItems] 回落到 `[KJ, LY]`。
         * 例：MC05 = `[SOUND_EFFECT_KJ, SOUND_EFFECT_LY]`（无 FOOT）。
         */
        val soundEffectItems: List<SoundEffect> = emptyList(),
    ) {
        /** 该型号支持的降噪档位全集（按官方 NoiseMode 顺序取前 N 个）。 */
        fun noiseModes(): List<NoiseMode> =
            if (!noiseControl) emptyList()
            else NoiseMode.entries.take(noiseModeCount ?: NoiseMode.entries.size)
    }

    // ---- productId 常量（官方 Product 枚举真值，区间 0x101–0x118 连续）----

    const val PID_DM02BA = 0x101
    const val PID_DM02BA_TWO = 0x102
    const val PID_DM25 = 0x103
    const val PID_AIR_FIT = 0x104
    const val PID_DM01_TWO = 0x105
    const val PID_DM01_MAX = 0x106
    const val PID_DM02 = 0x107
    const val PID_AIR_CLIP = 0x108
    const val PID_DM02_BASE = 0x109
    const val PID_MC01 = 0x110
    const val PID_MC02 = 0x111
    const val PID_MC20 = 0x112
    const val PID_DM03 = 0x113
    const val PID_MC01_MAX = 0x114
    const val PID_AS10 = 0x115
    const val PID_W30 = 0x116
    const val PID_MC03 = 0x117
    const val PID_MC05 = 0x118

    /**
     * 18 型全量档案。能力位只写**该型号相对基类的差异**（等于官方的覆写集）。
     * 行内注释标出官方覆写依据（`Product$<NAME>` 对应方法）。
     */
    val PRODUCTS: List<ProductCapabilities> = listOf(
        // ---- 无能力覆写（官方子类仅 3~4 个方法 = 构造器 + getChipType + getServerVersionType + isSupport）----
        ProductCapabilities(
            productId = PID_DM02BA, model = "DM02BA", serverVersionType = "DM02BA",
            chipType = ChipType.BT,
        ),
        ProductCapabilities(
            productId = PID_DM02BA_TWO, model = "DM02BA_TWO", serverVersionType = "DM02BA_TWO",
            chipType = ChipType.BT,
        ),
        ProductCapabilities(
            productId = PID_DM25, model = "DM25", serverVersionType = "DM25",
            chipType = ChipType.BT,
        ),
        ProductCapabilities(
            productId = PID_AIR_FIT, model = "AIR_FIT", serverVersionType = "AIR_FIT",
            chipType = ChipType.BT,
        ),
        ProductCapabilities(
            productId = PID_DM01_TWO, model = "DM01_TWO", serverVersionType = "DM01_TWO",
            chipType = ChipType.BT,
        ),
        ProductCapabilities(
            productId = PID_DM01_MAX, model = "DM01_MAX", serverVersionType = "DM01_MAX",
            chipType = ChipType.BT,
        ),
        ProductCapabilities(
            productId = PID_DM02, model = "DM02", serverVersionType = "DM02",
            chipType = ChipType.BT,
        ),
        ProductCapabilities(
            productId = PID_AIR_CLIP, model = "AIR_CLIP", serverVersionType = "AIR_CLIP",
            chipType = ChipType.BT,
        ),
        ProductCapabilities(
            productId = PID_DM02_BASE, model = "DM02_BASE", serverVersionType = "DM02_BASE",
            chipType = ChipType.BT,
        ),
        // 官方整体不开放（isSupport()=false，子类真值）
        ProductCapabilities(
            productId = PID_MC01, model = "MC01", serverVersionType = "MC01",
            supported = false, chipType = ChipType.BT,
        ),
        ProductCapabilities(
            productId = PID_MC02, model = "MC02", serverVersionType = "MC02",
            supported = false, chipType = ChipType.BT, earType = EarType.OWS,
        ),

        // ---- 有真实能力覆写的 7 型（官方子类 16~24 个方法）----

        // Product$MC20; : BT_MATE / V6_NEW / 低延迟 / 低音 / 双设备 / 仓电量 / HiRes / 入耳 / 健康提醒 / LDAC=PAGE
        ProductCapabilities(
            productId = PID_MC20, model = "MC20", serverVersionType = "MC20",
            supported = true, chipType = ChipType.BT_MATE, eqMode = GainMode.VALUE_6_NEW,
            leftAndRightImg = true,
            bassBoost = true, batteryCase = true, drainWater = true, dualDevice = true,
            healthReminder = true, hiRes = true, inEar = true,
            ldac = SwitchType.PAGE, lowLatency = true,
            showDeviceDialog = false, volumeGear = true,
            showAddPage = true, showCleanRemindPop = true, soundEffect = true,
        ),

        // Product$DM03; : BT_MATE / HEADSET / V12 / 抗风噪 / 双设备 / LDAC=PAGE / 噪声风格 / 电池 SINGLE
        ProductCapabilities(
            productId = PID_DM03, model = "DM03", serverVersionType = "DM03",
            supported = true, chipType = ChipType.BT_MATE, earType = EarType.HEADSET,
            eqMode = GainMode.VALUE_12, batteryType = BatteryType.SINGLE,
            ai = false,
            antiWindNoise = true, dualDevice = true,
            ldac = SwitchType.PAGE, lowLatency = true,
            noiseControl = true, noiseModeCount = 3, supportNoiseStyle = true,
            showAddPage = true, soundEffect = true,
        ),

        // Product$MC01_MAX; : BT / V6 / 低延迟 / 低音 / 双设备 / 仓电量 / HiRes / 儿童 / 离线语音 / 触控锁 / 音量档位 / LDAC=PAGE / LHDC=PAGE
        ProductCapabilities(
            productId = PID_MC01_MAX, model = "MC01_MAX", serverVersionType = "MC01 MAX",
            supported = true, chipType = ChipType.BT, eqMode = GainMode.VALUE_6,
            leftAndRightImg = true,
            bassBoost = true, batteryCase = true, childMode = true, drainWater = false,
            dualDevice = true, hiRes = true,
            ldac = SwitchType.PAGE, lhdc = SwitchType.PAGE, lowLatency = true,
            offlineVoice = true, showDeviceDialog = true, touchLockMode = true,
            volumeGear = true, showAddPage = true, soundEffect = true,
        ),

        // Product$AS10_ANC; : JL / TWS / V6 / 降噪(3档) / 低延迟 / HiRes / 入耳 / 空间音频 / LHDC=BUTTON / 电池无覆写
        ProductCapabilities(
            productId = PID_AS10, model = "AS10", serverVersionType = "AS10",
            supported = true, chipType = ChipType.JL, earType = EarType.TWS,
            eqMode = GainMode.VALUE_6, leftAndRightImg = true,
            bassBoost = false, batteryCase = true, drainWater = false, dualDevice = false,
            hiRes = true, inEar = true,
            lhdc = SwitchType.BUTTON, lowLatency = true,
            noiseControl = true, noiseModeCount = 3,
            showDeviceDialog = true, spatialSound = true,
            showAddPage = true, soundEffect = true,
        ),

        // Product$MC03; : BT / V6 / 低延迟 / 低音 / 双设备 / 仓电量 / 排水 / 健康提醒 / HiRes / LDAC=PAGE / LHDC=PAGE
        ProductCapabilities(
            productId = PID_MC03, model = "MC03", serverVersionType = "MC03",
            supported = true, chipType = ChipType.BT, eqMode = GainMode.VALUE_6,
            leftAndRightImg = true,
            bassBoost = true, batteryCase = true, drainWater = true, dualDevice = true,
            healthReminder = true, hiRes = true,
            ldac = SwitchType.PAGE, lhdc = SwitchType.PAGE, lowLatency = true,
            showDeviceDialog = false,
            showAddPage = true, showCleanRemindPop = true, soundEffect = true,
        ),

        // Product$W30; : JL / TWS / V6_NEW / 降噪(列表空) / 双设备 / 仓电量 / 排水 / HiRes / 入耳 / LDAC=BUTTON / 电池 PAIR
        ProductCapabilities(
            productId = PID_W30, model = "W30", serverVersionType = "W30",
            supported = true, chipType = ChipType.JL, earType = EarType.TWS,
            eqMode = GainMode.VALUE_6_NEW, batteryType = BatteryType.PAIR,
            leftAndRightImg = true,
            antiWindNoise = false, bassBoost = false, batteryCase = true, drainWater = true,
            dualDevice = true, hiRes = true, inEar = true,
            ldac = SwitchType.BUTTON, lowLatency = false,
            noiseControl = true, noiseModeCount = 0,
            showDeviceDialog = true, spatialSound = false,
            showAddPage = true,
        ),

        // Product$MC05; : BT_MATE / OWS / V6_NEW / 降噪无 / 低延迟 / 低音 / 双设备 / 仓电量 / 排水 / HiRes
        //               / 触控锁 / 音量档位 / QQ音乐 / 电池 PAIR （无 inEar / 无空间音频）
        ProductCapabilities(
            productId = PID_MC05, model = "MC05", serverVersionType = "MC05",
            supported = true, chipType = ChipType.BT_MATE, earType = EarType.OWS,
            eqMode = GainMode.VALUE_6_NEW, batteryType = BatteryType.PAIR,
            leftAndRightImg = true,
            ai = true,
            bassBoost = true, batteryCase = true, drainWater = true, dualDevice = true,
            hiRes = true,
            ldac = SwitchType.PAGE, lowLatency = true,
            noiseControl = false,
            qqMusicAudio = true, showDeviceDialog = true, touchLockMode = true,
            volumeGear = true,
            showAddPage = true, showCleanRemindPop = true, soundEffect = true,
            // 官方 Product$MC05.getSoundEffectItems() 真值 = [SOUND_EFFECT_KJ, SOUND_EFFECT_LY]（无 FOOT）。
            soundEffectItems = listOf(SoundEffect.KJ, SoundEffect.LY),
        ),
    )

    private val BY_ID: Map<Int, ProductCapabilities> = PRODUCTS.associateBy { it.productId }
    private val BY_MODEL: Map<String, ProductCapabilities> =
        PRODUCTS.associateBy { it.model.uppercase().replace(Regex("[\\s_-]+"), "") }

    // ==================================================================
    // 三、查询 API
    // ==================================================================

    fun byProductId(productId: Int): ProductCapabilities? = BY_ID[productId]

    /** 归一化：去空格/下划线/连字符并大写（"MC01_MAX" ≡ "MC01 MAX" ≡ "mc01max"）。 */
    private fun norm(value: String?): String? =
        value?.trim()?.uppercase()?.replace(Regex("[\\s_-]+"), "")?.takeIf { it.isNotEmpty() }

    fun byModel(model: String?): ProductCapabilities? {
        val key = norm(model) ?: return null
        return BY_MODEL[key]
    }

    /** 只返回官方开放（isSupport=true）的型号。 */
    fun supported(): List<ProductCapabilities> = PRODUCTS.filter { it.supported }

    /** productId → 6 位大写 16 进制（melody 注册表 id 字段格式）。 */
    fun toHex6(productId: Int): String = String.format("%06X", productId and 0xFFFFFF)

    // ==================================================================
    // 四、能力 → 面板项映射（动态自适配的核心）
    // ==================================================================

    /** 面板项种类（决定 UI 控件形态与写入命令）。 */
    enum class Kind {
        /** 布尔开关（写 `Payload.switch`）。 */
        SWITCH,

        /** 多选一（写档位值，如降噪模式 / 音量档位 / 音效）。 */
        CHOICE,

        /** 跳转子页（官方列表里带 `▸` 的项；详情见 [SubPage]）。 */
        PAGE,
    }

    /** 跳转子页的种类（决定点击后打开哪个本模块页面）。 */
    enum class SubPage {
        /** 提示音选项。 */
        PROMPT_TONE,

        /** 均衡器（自定义 EQ 多段滑块）。 */
        EQ,

        /** 按键功能。 */
        KEY_FUNCTION,

        /** 漏音抑制模式 / 空间音效（MC05 官方命名）。 */
        SOUND_EFFECT,

        /** 智能 AI。 */
        SMART_AI,

        /** 排水功能。 */
        DRAIN_WATER,

        /** LDAC 子页。 */
        LDAC,
    }

    /**
     * 一个「按型号动态生成」的面板项定义。
     *
     * [cmdSet] 为官方 SET 命令码（null = 该型号有此能力但官方无独立命令码，仅展示/置灰）。
     */
    data class PanelItem(
        val key: String,
        val title: String,
        val summary: String,
        val kind: Kind,
        val cmdSet: Int? = null,
        val cmdGet: Int? = null,
        /** CHOICE 型的可选值（value 为写入 payload 的字节/档位）。 */
        val choices: List<Choice> = emptyList(),
        /** 是否需要连上设备才可操作。 */
        val requiresSession: Boolean = true,
        /** PAGE 型（[Kind.PAGE]）：点击打开的本模块子页。 */
        val subPage: SubPage? = null,
        /** PAGE 型（[SubPage.EQ]）：官方 EQ 配置（段数/上限/子命令）。 */
        val eqConfig: EqConfig? = null,
        /**
         * 互斥组（同组内至多一项为开）。
         *
         * 官方语义：LDAC ↔ 游戏模式（`Product$MC05` 开 LDAC 会弹「与游戏/双设备冲突」提示）。
         * 组内开启一项时必须关断其它项，否则设备端行为未定义（实测可出现两项同时"开"）。
         */
        val mutexGroup: String? = null,
    ) {
        data class Choice(val label: String, val value: Int)
    }

    /**
     * 按型号能力生成面板项列表（**这就是「按连接耳机型号自适配动态加载」的判定入口**）。
     *
     * 顺序 = **官方 App 详情页顺序**（1:1 对齐官方截图）：
     * ```
     * 提示音选项 ▸ / 均衡器 ▸ / 按键功能 ▸ / 漏音抑制模式·空间音效 ▸ /
     * 智能AI ▸ / 排水功能 ▸ / 游戏模式 [开关] / 低音增强 [开关] / LDAC ▸
     * ```
     * 其余型号专属能力（降噪 / 空间音频 / 双设备 / 触控锁 / 儿童 / 离线语音 / 抗风噪）
     * 追加在官方九项之后。任何能力位为 false 的项**不会出现**。
     */
    fun panelItems(productId: Int): List<PanelItem> {
        val c = byProductId(productId) ?: return emptyList()
        if (!c.supported) return emptyList()
        return buildList {
            // ============ 官方「▸ 跳转子页」区（官方列表上半部）============

            // ① 提示音选项（官方承载 = 音量档位 VOLUME_GEAR；仅在有该能力时出现）
            if (c.volumeGear) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_prompt_tone",
                        title = "提示音选项",
                        summary = "设置耳机提示音 / 音量档位",
                        kind = Kind.PAGE,
                        subPage = SubPage.PROMPT_TONE,
                        cmdSet = XiberiaCommands.VOLUME_GEAR_SET,        // 0x0E26
                        cmdGet = XiberiaCommands.VOLUME_GEAR_GET,        // 0x0E27
                        choices = (0..3).map { PanelItem.Choice("档位 $it", it) },
                    ),
                )
            }

            // ② 均衡器（自定义 EQ —— 官方式多段竖向滑块子页）
            if (c.eqMode != null) {
                val eq = eqConfigOf(c.eqMode)!!
                add(
                    PanelItem(
                        key = "melodyplus_xi_eq",
                        title = "均衡器",
                        summary = "${eq.cardinal} 段均衡器（±${eq.maxValue}）",
                        kind = Kind.PAGE,
                        subPage = SubPage.EQ,
                        cmdSet = XiberiaCommands.EQ_CUSTOM,              // 0x0806 EQ_CUSTOM_GAIN_SET
                        cmdGet = XiberiaCommands.EQ_PRESET_QUERY,        // 0x0807 USER_ALL_EQ_GET
                        eqConfig = eq,
                    ),
                )
            }

            // ③ 按键功能（官方 ALL_KEY_GET 0x0314）
            if (c.touchLockMode || c.eqMode != null) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_key_function",
                        title = "按键功能",
                        summary = "自定义耳机按键功能",
                        kind = Kind.PAGE,
                        subPage = SubPage.KEY_FUNCTION,
                        cmdSet = XiberiaCommands.KEY_MAP,                // 0x0314 ALL_KEY_GET（查询）
                    ),
                )
            }

            // ④ 漏音抑制模式 / 空间音效（官方 SOUND_EFFECT，MC05 专属双模式）
            if (c.soundEffect) {
                val items = c.soundEffectItems.ifEmpty { listOf(SoundEffect.KJ, SoundEffect.LY) }
                add(
                    PanelItem(
                        key = "melodyplus_xi_sound_effect",
                        title = "漏音抑制模式/空间音效",
                        summary = "切换到均衡器模式，可关闭漏音抑制模式/空间音效",
                        kind = Kind.CHOICE,
                        cmdSet = XiberiaCommands.SOUND_EFFECT_SET,       // 0x0E0D
                        cmdGet = XiberiaCommands.SOUND_EFFECT_GET,       // 0x0E0E
                        choices = items.map { PanelItem.Choice(it.label, it.modeValue) },
                    ),
                )
            }

            // ⑤ 智能AI
            if (c.ai) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_smart_ai",
                        title = "智能AI",
                        summary = "智能音频 / 通话算法",
                        kind = Kind.PAGE,
                        subPage = SubPage.SMART_AI,
                        cmdSet = null,                                   // 官方无独立 SET（展示/联动型）
                    ),
                )
            }

            // ⑥ 排水功能
            if (c.drainWater) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_drain_water",
                        title = "排水功能",
                        summary = "清除耳机内部积水",
                        kind = Kind.PAGE,
                        subPage = SubPage.DRAIN_WATER,
                        cmdSet = null,                                   // 官方无独立 SET（本地动画页）
                    ),
                )
            }

            // ⑦ 游戏模式（低延迟）
            if (c.lowLatency) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_game",
                        title = "游戏模式",
                        summary = "非游戏场景建议关闭，避免影响音乐/视频流畅播放",
                        kind = Kind.SWITCH,
                        cmdSet = XiberiaCommands.GAME_MODE,              // 0x0C01
                        cmdGet = XiberiaCommands.GAME_MODE_GET,          // 0x0C02
                        mutexGroup = MUTEX_LDAC_GAME,
                    ),
                )
            }

            // ⑧ 低音增强
            if (c.bassBoost) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_bass",
                        title = "低音增强",
                        summary = "关闭低音增强提升续航时间",
                        kind = Kind.SWITCH,
                        cmdSet = XiberiaCommands.BASS_BOOST_SET,         // 0x0E11
                        cmdGet = XiberiaCommands.BASS_BOOST_GET,         // 0x0E12
                    ),
                )
            }

            // ⑨ LDAC（官方式跳转子页）
            if (c.ldac != SwitchType.NOT_SUPPORT) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_ldac",
                        title = "LDAC",
                        summary = "高解析音频传输（与游戏模式互斥）",
                        kind = Kind.PAGE,
                        subPage = SubPage.LDAC,
                        cmdSet = XiberiaCommands.LDAC,                   // 0x0E04
                        cmdGet = XiberiaCommands.LDAC_GET,               // 0x0E05
                        mutexGroup = MUTEX_LDAC_GAME,
                    ),
                )
            }

            // ============ 其余型号专属能力（官方截图中无，按能力追加以保证全功能）============

            // 降噪组（多选一，NoiseMode 档位）
            if (c.noiseControl && c.noiseModeCount != 0) {
                val modes = c.noiseModes()
                if (modes.isNotEmpty()) {
                    add(
                        PanelItem(
                            key = "melodyplus_xi_noise",
                            title = "降噪模式",
                            summary = "切换降噪 / 通透等模式",
                            kind = Kind.CHOICE,
                            cmdSet = XiberiaCommands.LEAK_SUPPRESS,      // 0x0B01 NOISE_SET
                            cmdGet = XiberiaCommands.LEAK_SUPPRESS_GET,  // 0x0B02 NOISE_GET
                            choices = modes.map {
                                PanelItem.Choice(it.name, it.command)
                            },
                        ),
                    )
                }
            }
            if (c.spatialSound) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_spatial",
                        title = "空间音频",
                        summary = "营造空间感听觉体验",
                        kind = Kind.SWITCH,
                        cmdSet = XiberiaCommands.SPATIAL_SOUND_SET,      // 0x0E0F
                        cmdGet = XiberiaCommands.SPATIAL_SOUND_GET,      // 0x0E10
                    ),
                )
            }
            if (c.lhdc != SwitchType.NOT_SUPPORT) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_lhdc",
                        title = "LHDC 高音质",
                        summary = "高解析音频传输（LHDC 编解码）",
                        kind = Kind.SWITCH,
                        cmdSet = XiberiaCommands.LHDC_SET,               // 0x0E13
                        cmdGet = XiberiaCommands.LHDC_GET,               // 0x0E14
                    ),
                )
            }
            if (c.dualDevice) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_dual",
                        title = "双设备连接",
                        summary = "同时连接两台设备并快速切换",
                        kind = Kind.SWITCH,
                        cmdSet = XiberiaCommands.DUAL_DEVICE_SET,        // 0x0E0B
                        cmdGet = XiberiaCommands.DUAL_DEVICE_GET,        // 0x0E0C
                    ),
                )
            }
            if (c.touchLockMode) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_touch",
                        title = "触控锁",
                        summary = "锁定耳机触控，防止误触",
                        kind = Kind.SWITCH,
                        cmdSet = XiberiaCommands.TOUCH_SET,              // 0x0E17
                        cmdGet = XiberiaCommands.TOUCH_GET,              // 0x0E18
                    ),
                )
            }
            if (c.offlineVoice) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_offline_voice",
                        title = "离线语音",
                        summary = "离线语音助手唤醒",
                        kind = Kind.SWITCH,
                        cmdSet = XiberiaCommands.OFFLINE_VOICE_SET,      // 0x0E1B
                        cmdGet = XiberiaCommands.OFFLINE_VOICE_GET,      // 0x0E1C
                    ),
                )
            }
            if (c.childMode) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_child",
                        title = "儿童模式",
                        summary = "限制音量保护听力",
                        kind = Kind.SWITCH,
                        cmdSet = XiberiaCommands.CHILD_MODE_SET,         // 0x0E19
                        cmdGet = XiberiaCommands.CHILD_MODE_GET,         // 0x0E1A
                    ),
                )
            }
            if (c.antiWindNoise) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_anti_wind",
                        title = "抗风噪",
                        summary = "户外风噪抑制",
                        kind = Kind.SWITCH,
                        cmdSet = XiberiaCommands.ANTI_WIND_SET,          // 0x0E21
                        cmdGet = XiberiaCommands.ANTI_WIND_GET,          // 0x0E22
                    ),
                )
            }
            if (c.dongle) {
                add(
                    PanelItem(
                        key = "melodyplus_xi_dongle",
                        title = "接收器状态",
                        summary = "Dongle 接收器状态查询",
                        kind = Kind.SWITCH,
                        cmdSet = null,                                   // 官方仅 0x0E25 查询，无 SET
                    ),
                )
            }
        }
    }

    /** 面板项 key 属于本 catalog 的前缀（供幂等/自触判定）。 */
    const val PANEL_KEY_PREFIX: String = "melodyplus_xi_"

    fun isCatalogKey(key: String?): Boolean = key?.startsWith(PANEL_KEY_PREFIX) == true
}
