// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaCommands.kt
// 说明: cchip(XIBERIA) 命令码表 —— 已按官方 App 真值重写。
//
// 真源（workspace 55490541，com.cchip.desheng）：
//   - 命令码   : Lcom/cchip/desheng/constant/CommandId;        （44 条）
//   - 电量查询 : Protocol.readBattery() → sendData(addr, 0xA01, 0x0F, empty)
//   - 电量上报 : Protocol.parseReceiveData 分发 0xA11/0xA12 → parseBattery
//   - 帧格式   : 见 XiberiaOfficialCodec.kt / docs/REF_ChipDesheng_Frame_And_Codec.md
//
// ⚠️ 旧版本码表沿用华为兼容模块的码，与 cchip 官方多处冲突（例如旧 BATTERY=0x0804
//    实际是官方 EQ_MODE_GET；旧 EQ_PRESET_QUERY=0x0A11 实际是官方电量应答）。
//    本版按官方重排；无官方对应的项标注「⚠️ 无官方对应」。
package com.melody.melodyplus.adapter.xiberia

object XiberiaCommands {

    // ================= 电量 =================
    /** 电量查询（官方 readBattery 发的码，走 parseBattery 2 参旧路径）。 */
    const val BATTERY: Int = 0x0A01
    /** 备用查询码（官方 0xA02 → parseBattery 2 参）。 */
    const val BATTERY_ALT: Int = 0x0A02
    /**
     * 电量上报/新协议（官方 dispatch：0xA11/0xA12 → parseBatteryNew + parseBattery(3参)）。
     * 实测 MC05 对 0xA01 只回 1 字节 payload（11 字节整帧），不足官方 0x0C 门槛，
     * 故查询按 0xA01 → 0xA11 → 0xA02 → 0xA12 顺序取第一个 ≥6 字节 payload 的应答。
     */
    const val BATTERY_REPORT: Int = 0x0A11
    const val BATTERY_REPORT_ALT: Int = 0x0A12

    /** 电量查询探测序列（顺序 = 优先级）。
     *  实测：0xA11 直回 6 字节三态真值（16 字节整帧），故置首；
     *        0xA01 只回 1 字节（官方 parseBattery 会因 <14 字节丢弃），作退化兜底。 */
    val BATTERY_QUERY_CHAIN: IntArray = intArrayOf(BATTERY_REPORT, BATTERY, BATTERY_REPORT_ALT, BATTERY_ALT)

    // ================= 查询/通用 =================
    /**
     * 用户全量 EQ 查询（官方 `USER_ALL_EQ_GET`）。
     *
     * ⚠️ 旧名 `DEVICE_CAPS` 名不副实：`0x0807` 只回 EQ 预设/增益数据，
     *    **不含任何开关状态**。设备开关状态必须分项 GET：
     *    [GAME_MODE_GET] `0x0C02` / [LDAC_GET] `0x0E05` / [BASS_BOOST_GET] `0x0E12`
     *    / [DUAL_DEVICE_GET] `0x0E0C` / [TOUCH_GET] `0x0E18` / [SOUND_EFFECT_GET] `0x0E0E`。
     */
    const val USER_ALL_EQ_GET: Int = 0x0807

    @Deprecated(
        message = "0x0807 是 EQ 预设查询，不是设备状态查询。改用 USER_ALL_EQ_GET，或分项 GET。",
        replaceWith = ReplaceWith("USER_ALL_EQ_GET"),
    )
    const val DEVICE_CAPS: Int = 0x0807
    const val ALL_KEY_GET: Int = 0x0314

    // ================= 音量 / EQ =================
    const val VOLUME: Int = 0x0806               // EQ_CUSTOM_GAIN_SET
    const val EQ_ENABLE_SET: Int = 0x0801
    const val EQ_ENABLE_GET: Int = 0x0802
    const val EQ_MODE_SET: Int = 0x0803
    const val EQ_MODE_GET: Int = 0x0804
    const val EQ_CUSTOM: Int = 0x0806            // EQ_CUSTOM_GAIN_SET
    const val EQ_PRESET_QUERY: Int = 0x0807      // USER_ALL_EQ_GET
    const val EQ_VOLUME_DOMAIN: Int = 0x0804     // EQ_MODE_GET（音量域读取）
    /**
     * 自定义 EQ 下发的子命令字节（官方 `DefaultEqGain.CUSTOM_1.getCommand()`）。
     * 真源：`DefaultEqGain$CUSTOM_1.<init>` → `const/16 v3, 0x11`（构造第三参数）。
     * ⚠️ 待真机抓帧复核。
     */
    const val EQ_SUB_CMD_CUSTOM1: Int = 0x11

    // ================= 音效模式（漏音抑制模式 / 空间音效）=================
    /** 官方 `SoundEffectMode.SOUND_EFFECT_LY.modeValue` —— 官方文案「降低漏音 / 漏音抑制模式」。 */
    const val SOUND_EFFECT_LY: Int = 0x0D
    /** 官方 `SoundEffectMode.SOUND_EFFECT_KJ.modeValue` —— 官方文案「空间音效」。 */
    const val SOUND_EFFECT_KJ: Int = 0x0E
    /** 官方 `SoundEffectMode.SOUND_EFFECT_FOOT.modeValue` —— 官方文案「脚步增强」（MC05 不含此项）。 */
    const val SOUND_EFFECT_FOOT: Int = 0x0F

    // ================= 按键 / 触控 =================
    const val KEY_MAP: Int = 0x0314              // ALL_KEY_GET
    const val KEY_FUNC_L: Int = 0x0E17           // TOUCH_SET
    const val KEY_FUNC_R: Int = 0x0E18           // TOUCH_GET

    // ================= 音效 / 低频 / 空间 =================
    const val SOUND_EFFECT_SET: Int = 0x0E0D
    const val SOUND_EFFECT_GET: Int = 0x0E0E
    const val BASS_BOOST_SET: Int = 0x0E11
    const val BASS_BOOST_GET: Int = 0x0E12

    // ================= 模式开关（官方三元组 SET/GET/REPORT） =================

    /** 低延迟 / 游戏模式（旧值 0x0E11 实为 BASS_BOOST_SET，已修正）。 */
    const val GAME_MODE: Int = 0x0C01            // LOW_DELAY_SET
    const val GAME_MODE_GET: Int = 0x0C02        // LOW_DELAY_GET
    const val GAME_MODE_REPORT: Int = 0x0C03     // LOW_DELAY_REPORT

    /** LDAC 高音质（旧值 0x0E0B 实为 DUAL_DEVICE_SET，已修正）。 */
    const val LDAC: Int = 0x0E04                 // LDAC_SET
    const val LDAC_GET: Int = 0x0E05             // LDAC_GET

    /** LHDC（官方 0x0E13/0x0E14）。 */
    const val LHDC_SET: Int = 0x0E13
    const val LHDC_GET: Int = 0x0E14

    /** 双设备连接（旧值 0x0E27 实为 VOLUME_GEAR_GET，已修正）。 */
    const val TRI_STATE: Int = 0x0E0B            // DUAL_DEVICE_SET
    const val DUAL_DEVICE_GET: Int = 0x0E0C

    /** 漏音抑制 → 官方无同名，就近映射到降噪写（MC05 无真实 ANC，语义近似）。 */
    const val LEAK_SUPPRESS: Int = 0x0B01        // NOISE_SET
    const val LEAK_SUPPRESS_GET: Int = 0x0B02    // NOISE_GET

    // ============ 官方语义别名（值同上表，仅命名对齐 CommandId，供 MC05 功能面板使用） ============
    /** 触控锁 / 触控功能（= KEY_FUNC_L/R）。 */
    const val TOUCH_SET: Int = 0x0E17
    const val TOUCH_GET: Int = 0x0E18
    /** 空间音频（官方 SPATIAL_SOUND_SWITCH_SET/GET；MC05 不支持，仅备查）。 */
    const val SPATIAL_SOUND_SET: Int = 0x0E0F
    const val SPATIAL_SOUND_GET: Int = 0x0E10
    /** 儿童模式（官方；MC05 不支持，仅备查）。 */
    const val CHILD_MODE_SET: Int = 0x0E19
    const val CHILD_MODE_GET: Int = 0x0E1A
    /** 离线语音（官方；MC05 不支持，仅备查）。 */
    const val OFFLINE_VOICE_SET: Int = 0x0E1B
    const val OFFLINE_VOICE_GET: Int = 0x0E1C
    /** 抗风噪（官方；MC05 不支持，仅备查）。 */
    const val ANTI_WIND_SET: Int = 0x0E21
    const val ANTI_WIND_GET: Int = 0x0E22
    /** 双设备连接官方命名（= TRI_STATE）。 */
    const val DUAL_DEVICE_SET: Int = 0x0E0B
    /** 音量档位官方命名（= TONE_LEVEL，4 档 0..3）。 */
    const val VOLUME_GEAR_SET: Int = 0x0E26

    // ================= 提示音 / 音量档位 =================
    /** 音量档位（旧名「提示音档位」，码值 0x0E26 与官方 VOLUME_GEAR_SET 一致）。 */
    const val TONE_LEVEL: Int = 0x0E26           // VOLUME_GEAR_SET
    const val VOLUME_GEAR_GET: Int = 0x0E27

    // ================= 设备信息 =================
    const val FW_VERSION: Int = 0x0D01           // VERSION_GET（payload=ASCII）
    /** ⚠️ 无官方对应：序列号不在 CommandId 表；保留占位，需实测校准。 */
    const val SERIAL: Int = 0x0E01
    /** ⚠️ 无官方对应：佩戴/触控读不在 CommandId 表；保留占位，需实测校准。 */
    const val WEAR_DETECT: Int = 0x0E17
    /** ⚠️ 无官方对应：智能暂停不在 CommandId 表；保留占位，需实测校准。 */
    const val SMART_PAUSE: Int = 0x0E0C

    // ================= 兼容别名（旧代码引用名保留，避免大面积改签名） =================
    /** 旧名，等价 GAME_MODE_GET。 */
    const val LE_AUDIO_MODE: Int = 0x0C02
    const val SWITCH_A: Int = 0x0C01             // = GAME_MODE（旧「未知开关 A」）
    const val SWITCH_B: Int = 0x0C02             // = GAME_MODE_GET（旧「未知开关 B」）

    // ================= payload 语义 =================
    object Payload {
        const val ON: Int = 0x01
        const val OFF: Int = 0x00

        /**
         * 写开关：**单字节 [value]**。
         *
         * 真源（官方 `Protocol`，npmcp workspace 反编译逐条核对）：
         *   `setLowLatency`(0xC01) / `setLDac`(0xE04) / `setLHDC`(0xE13) 均为
         *   `new-array v1, 0x1` → `v1[0]=value` → `copyOf(v1,1)` → `sendData(addr, cmd, 0xF, payload)`。
         * 即 payload = **1 字节**，设备只取首字节判定，长度域 b[9]=1。
         *
         * ⚠️ 旧实现 `[0x01, value]`（2 字节）是错误：设备读首字节恒得 0x01 → 关闭键反被置开、
         *    且 b[9]=2 与固件期望的 1 不符 → SET 不落地（官方 App 查询无变化）。
         */
        fun switch(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

        /**
         * 查询 payload。
         *
         * ⚠️ 官方 `readBattery()` 传的是**空 payload**（`sendData(addr, 0xA01, 0x0F, empty)`），
         * 由 `packData` 按 `maxLen=0x0F` 补零。旧实现的「5 字节全 0」是华为语义，已修正为空。
         */
        fun query(): ByteArray = ByteArray(0)

        /**
         * 档位写：**单字节 [level]**。
         *
         * 真源：官方 `Protocol.setVolume(dev,int)`（0xE26）与 `soundEffect`（0xE0D）同为
         *   `new-array v1,0x1; v1[0]=<档位值>` —— 单字节。旧 `[0x01, level]` 会把首字节钉死为 0x01。
         */
        fun level(level: Int): ByteArray = byteArrayOf(level.toByte())

        /**
         * 自定义 EQ 增益写（官方 `Protocol.setCustomEq(dev, 0x0806, subCmd, int[])`）。
         *
         * 官方原样：`bb.put((byte) subCmd); bb.put(byte[](gains))` →
         * payload = `[subCmd][gain0][gain1]…[gainN]`（整帧按 maxLen=0x0F 补零）。
         *
         * @param subCmd 官方 `DefaultEqGain.<PRESET>.getCommand()`；自定义档 = `CUSTOM_1` = 0x11。
         * @param gains  6 段（MC05 / VALUE_6_NEW）或 12 段（DM03 / VALUE_12）增益，每段截断为 1 字节。
         */
        fun eqCustom(subCmd: Int, gains: IntArray): ByteArray {
            val out = ByteArray(gains.size + 1)
            out[0] = subCmd.toByte()
            gains.forEachIndexed { i, g -> out[i + 1] = g.toByte() }
            return out
        }

        /**
         * 音效模式写（漏音抑制 / 空间音效）：**单字节 [modeValue]**。
         *
         * 真源：官方 `Protocol`（0xE0D）`new-array v1,0x1; v1[0]=SoundEffectMode.getModeValue()` —— 单字节。
         */
        fun soundEffect(modeValue: Int): ByteArray = byteArrayOf(modeValue.toByte())
    }
}