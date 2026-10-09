// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaModeMapping.kt
// 来源: 参考模块 com.oai.huaweimelodycompat.HuaweiModeMapping + HuaweiWearState 的源码逻辑逆向复刻。
//
// 参考模块 HookEntryV171MelodyBridge 里的映射方法（逆向确认）：
//   donorModeTypeToHuaweiEq(int):int
//   donorProtocolToHuaweiEq(int):int
//   huaweiEqToDonorModeType(int):int
//   huaweiEqToDonorProtocol(int):int
//   findNoiseModeType(Collection,int):int / findAncChildProtocol / findTopProtocol / findAnyProtocol
//   authoritativeMode(String,int):int / currentMode():int / currentEffect():int
//
// 语义：melody（donor）用「modeType / protocolIndex」表达耳机状态，
//   第三方耳机用自家协议值表达。本类负责两套编号互转 + 佩戴态枚举。

package com.melody.melodyplus.adapter.xiberia

object XiberiaModeMapping {

    /** melody 侧 noiseReductionMode 的 modeType（取自真机 16.9.1 白名单 profile）。 */
    const val DONOR_MODE_TYPE_OFF = 1
    const val DONOR_MODE_TYPE_TRANSPARENCY = 2
    const val DONOR_MODE_TYPE_NOISE = 5

    /** melody 侧 protocolIndex（白名单 function.noiseReductionMode[].protocolIndex）。 */
    const val DONOR_PROTOCOL_INDEX_OFF = 0
    const val DONOR_PROTOCOL_INDEX_NOISE = 1
    const val DONOR_PROTOCOL_INDEX_TRANSPARENCY = 2

    /**
     * XIBERIA（cchip）模式值 —— 由于 MC05 无真实 ANC，
     * 这里把 melody 的三种展示语义映射到 cchip 的「能力开关组合」。
     * 值定义与 XiberiaHeadsetAdapter 的 currentMode() 保持一致：
     *   0 = 全关 / 1 = 漏音抑制 / 2 = 游戏低延迟
     */
    const val XIBERIA_MODE_OFF = 0
    const val XIBERIA_MODE_LEAK = 1
    const val XIBERIA_MODE_GAME = 2

    /** donor modeType → XIBERIA 模式值。 */
    fun donorModeTypeToXiberia(donorModeType: Int): Int = when (donorModeType) {
        DONOR_MODE_TYPE_NOISE -> XIBERIA_MODE_LEAK    // 语义就近映射：降噪 → 漏音抑制
        DONOR_MODE_TYPE_TRANSPARENCY -> XIBERIA_MODE_GAME
        else -> XIBERIA_MODE_OFF
    }

    /** donor protocolIndex → XIBERIA 模式值。 */
    fun donorProtocolToXiberia(protocolIndex: Int): Int = when (protocolIndex) {
        DONOR_PROTOCOL_INDEX_NOISE -> XIBERIA_MODE_LEAK
        DONOR_PROTOCOL_INDEX_TRANSPARENCY -> XIBERIA_MODE_GAME
        else -> XIBERIA_MODE_OFF
    }

    /** XIBERIA 模式值 → donor modeType（反向，供 UI 回显）。 */
    fun xiberiaToDonorModeType(mode: Int): Int = when (mode) {
        XIBERIA_MODE_LEAK -> DONOR_MODE_TYPE_NOISE
        XIBERIA_MODE_GAME -> DONOR_MODE_TYPE_TRANSPARENCY
        else -> DONOR_MODE_TYPE_OFF
    }

    /** XIBERIA 模式值 → donor protocolIndex（反向）。 */
    fun xiberiaToDonorProtocol(mode: Int): Int = when (mode) {
        XIBERIA_MODE_LEAK -> DONOR_PROTOCOL_INDEX_NOISE
        XIBERIA_MODE_GAME -> DONOR_PROTOCOL_INDEX_TRANSPARENCY
        else -> DONOR_PROTOCOL_INDEX_OFF
    }

    /** 对应参考模块 authoritativeMode(mac, mode)：在途请求优先，否则回落查询值。 */
    internal fun authoritativeMode(gate: XiberiaAncCommandGate, mac: String, queried: Int): Int =
        if (gate.isInFlight(mac)) gate.desiredMode(mac) else queried

    /** 对应参考模块 isControlFeature / isEffect 的判定位（用于区分「开关」与「档位」）。 */
    fun isSwitchCommand(command: Int): Boolean = when (command) {
        XiberiaCommands.SWITCH_A,
        XiberiaCommands.SWITCH_B,
        XiberiaCommands.GAME_MODE,
        XiberiaCommands.LDAC,
        XiberiaCommands.WEAR_DETECT,
        XiberiaCommands.SMART_PAUSE,
        XiberiaCommands.LEAK_SUPPRESS -> true
        else -> false
    }

    fun isLevelCommand(command: Int): Boolean = command == XiberiaCommands.TONE_LEVEL
}

/** 佩戴态（对应参考模块 HuaweiWearState）。 */
enum class XiberiaWearState {
    UNKNOWN,
    IN_EAR,
    OUT_OF_EAR,
}
