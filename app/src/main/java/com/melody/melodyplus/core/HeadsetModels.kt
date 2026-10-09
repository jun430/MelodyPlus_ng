package com.melody.melodyplus.core

import android.bluetooth.BluetoothDevice

enum class AncMode {
    OFF,
    NOISE_CANCELLING,
    TRANSPARENCY,
    ADAPTIVE,
    WIND_REDUCTION,
}

data class BatteryState(
    val single: Int? = null,
    val left: Int? = null,
    val right: Int? = null,
    val caseBattery: Int? = null,
    /** 左耳是否充电中；null = 未知/该型号不提供，UI 不显示充电指示。 */
    val leftCharging: Boolean? = null,
    /** 右耳是否充电中。 */
    val rightCharging: Boolean? = null,
    /** 耳机仓是否充电中。 */
    val caseCharging: Boolean? = null,
)

data class HeadsetCapabilities(
    val ancModes: Set<AncMode>,
    val supportsAmbientLevel: Boolean,
    val supportsSingleBattery: Boolean,
    val supportsLeftRightBattery: Boolean = false,
    val supportsCaseBattery: Boolean,
    val supportsDsee: Boolean = false,
)

data class HeadsetState(
    val address: String,
    val name: String?,
    val connected: Boolean,
    val battery: BatteryState?,
    val ancMode: AncMode?,
    val capabilities: HeadsetCapabilities,
    val updatedAtMillis: Long,
    val dseeEnabled: Boolean? = null,
)

sealed class HeadsetCommand {
    data class SetAncMode(val mode: AncMode) : HeadsetCommand()
    data class SetAmbientLevel(val level: Int) : HeadsetCommand()
    data class SetDseeEnabled(val enabled: Boolean) : HeadsetCommand()
    /**
     * 通用布尔开关写（MC05 功能面板专用）。
     *
     * 直接携带官方命令码（0x0C01 游戏模式 / 0x0E11 低音增强 / 0x0E0B 双设备 /
     * 0x0E17 触控锁 / 0x0E04 LDAC …），由各 adapter 决定是否支持。
     */
    data class SetSwitch(val command: Int, val enabled: Boolean) : HeadsetCommand()

    /**
     * 通用**档位/多选值**写（型号自适配面板的 `Kind.CHOICE` 项专用）。
     *
     * 与 [SetSwitch] 的区别：payload 第二字节不是 0/1，而是官方枚举档位值
     * （`NoiseMode.command` 1..5 / `SoundEffectMode.modeValue` 0x0D..0x0F /
     *  `VOLUME_GEAR` 0..3）。由 adapter 组装为 `[0x01, value]` 帧下发。
     */
    data class SetLevel(val command: Int, val level: Int) : HeadsetCommand()

    /**
     * 自定义 EQ 增益写（官方式多段滑块）。
     *
     * 走官方 `Protocol.setCustomEq(dev, 0x0806 EQ_CUSTOM_GAIN_SET, subCmd, gains)`：
     * payload = `[subCmd][gain0]…[gainN]`（见 `XiberiaCommands.Payload.eqCustom`）。
     *
     * @param subCmd 官方 `DefaultEqGain.<PRESET>.getCommand()`；自定义档见
     *               [com.melody.melodyplus.adapter.xiberia.XiberiaCommands.EQ_SUB_CMD_CUSTOM1]。
     * @param gains  段增益（MC05=6 段；DM03=12 段），值由官方 `EqComputeUtils.uiValuesToEqGain` 算出。
     */
    data class SetEqCustom(val subCmd: Int, val gains: IntArray) : HeadsetCommand() {
        override fun equals(other: Any?): Boolean =
            other is SetEqCustom && other.subCmd == subCmd && other.gains.contentEquals(gains)

        override fun hashCode(): Int = 31 * subCmd + gains.contentHashCode()
    }
}

sealed class CommandResult {
    data object Success : CommandResult()
    data class Unsupported(val reason: String) : CommandResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : CommandResult()
}

interface HeadsetAdapter {
    val capabilities: HeadsetCapabilities

    /**
     * 链路是否仍活着（**实时**）。
     *
     * ⚠️ 修复记录：此前 HeadsetSessionManager 用 `Session.connected`（创建时快照的 val）
     *    判会话有效性。RFCOMM/SPP 被对端关闭（或官方 App 抢通道）后 socket 已死，
     *    但快照恒为 true → execute/readFeatureRaw 永远走「复用已有会话」分支，
     *    命令全量落到死 socket 上（表现为 `Send failed`，从不重连）。
     *    现由 adapter 暴露实时状态，SessionManager 依此决定是否重建会话。
     */
    val isAlive: Boolean get() = true

    suspend fun connect(device: BluetoothDevice): Boolean
    suspend fun readState(): HeadsetState
    suspend fun execute(command: HeadsetCommand): CommandResult
    suspend fun disconnect()

    /**
     * 读原始特征帧（**可选能力**，默认不支持 = null）。
     *
     * 用于面板「真实状态回读」：发 GET 命令并返回应答 payload 原始字节，
     * 由上层按协议布局解析。不支持的 adapter 保持默认实现即可。
     */
    suspend fun readFeatureRaw(command: Int): ByteArray? = null
}
