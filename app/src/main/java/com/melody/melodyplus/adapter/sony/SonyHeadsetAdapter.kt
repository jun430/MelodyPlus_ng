package com.melody.melodyplus.adapter.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.melody.melodyplus.bridge.DeviceProfile
import com.melody.melodyplus.bridge.DeviceProfiles
import com.melody.melodyplus.core.AncMode
import com.melody.melodyplus.core.BatteryState
import com.melody.melodyplus.core.CommandResult
import com.melody.melodyplus.core.HeadsetAdapter
import com.melody.melodyplus.core.HeadsetCapabilities
import com.melody.melodyplus.core.HeadsetCommand
import com.melody.melodyplus.core.HeadsetState

class SonyHeadsetAdapter(
    private val profile: DeviceProfile = DeviceProfiles.sonyWf1000Xm3,
    private val controller: SonyAncController = DefaultSonyAncController(),
) : HeadsetAdapter {
    private var device: BluetoothDevice? = null
    private var connected = false
    private var lastSonyState: SonyAncState? = null
    private var lastBatteryState: BatteryState? = null
    private var lastDseeEnabled: Boolean? = null

    override val capabilities: HeadsetCapabilities
        get() = profile.capabilities

    @SuppressLint("MissingPermission")
    override suspend fun connect(device: BluetoothDevice): Boolean {
        this.device = device
        connected = controller.connect(
            device = device,
            version = protocolVersionFor(device),
            windSupported = AncMode.WIND_REDUCTION in capabilities.ancModes,
        )
        if (connected) {
            if (capabilities.ancModes.isNotEmpty()) lastSonyState = controller.getAncState()
            lastBatteryState = mergeBatteryState(lastBatteryState, readBatteryState())
            if (capabilities.supportsDsee) lastDseeEnabled = controller.getDseeEnabled()
        }
        return connected
    }

    @SuppressLint("MissingPermission")
    override suspend fun readState(): HeadsetState {
        val currentDevice = device
        val state = if (connected && capabilities.ancModes.isNotEmpty()) controller.getAncState() else null
        val battery = if (connected) readBatteryState() else null
        val dseeEnabled = if (connected && capabilities.supportsDsee) controller.getDseeEnabled() else null
        if (state != null) lastSonyState = state
        if (battery != null) lastBatteryState = mergeBatteryState(lastBatteryState, battery)
        if (dseeEnabled != null) lastDseeEnabled = dseeEnabled
        return HeadsetState(
            address = currentDevice?.address.orEmpty(),
            name = currentDevice?.name,
            connected = connected,
            battery = lastBatteryState ?: battery,
            ancMode = state?.mode?.toCoreAncMode(),
            capabilities = capabilities,
            updatedAtMillis = System.currentTimeMillis(),
            dseeEnabled = lastDseeEnabled,
        )
    }

    override suspend fun execute(command: HeadsetCommand): CommandResult {
        if (!connected) return CommandResult.Failed("Sony headset is not connected")
        return when (command) {
            is HeadsetCommand.SetAncMode -> setAncMode(command.mode)
            is HeadsetCommand.SetAmbientLevel -> CommandResult.Unsupported("Ambient level command is not wired in phase one")
            is HeadsetCommand.SetDseeEnabled -> setDseeEnabled(command.enabled)
            is HeadsetCommand.SetSwitch -> CommandResult.Unsupported("SetSwitch is not wired for Sony adapter")
            is HeadsetCommand.SetLevel -> CommandResult.Unsupported("SetLevel is not wired for Sony adapter")
            is HeadsetCommand.SetEqCustom -> CommandResult.Unsupported("SetEqCustom is not wired for Sony adapter")
        }
    }

    override suspend fun disconnect() {
        controller.disconnect()
        connected = false
        lastSonyState = null
        lastBatteryState = null
        lastDseeEnabled = null
        device = null
    }

    private suspend fun readBatteryState(): BatteryState? =
        controller.getBatteryState(
            singleSupported = capabilities.supportsSingleBattery,
            dualSupported = capabilities.supportsLeftRightBattery,
            caseSupported = capabilities.supportsCaseBattery,
        )

    private fun mergeBatteryState(previous: BatteryState?, update: BatteryState?): BatteryState? {
        if (previous == null) return update
        if (update == null) return previous
        return BatteryState(
            single = update.single ?: previous.single,
            left = update.left ?: previous.left,
            right = update.right ?: previous.right,
            caseBattery = update.caseBattery ?: previous.caseBattery,
        )
    }

    private suspend fun setAncMode(mode: AncMode): CommandResult {
        if (mode !in capabilities.ancModes) {
            return CommandResult.Unsupported("$mode is not supported by ${profile.id}")
        }
        val sonyMode = mode.toSonyAncMode()
            ?: return CommandResult.Unsupported("$mode cannot be mapped to Sony V1")
        val base = lastSonyState
        val target = SonyAncState(
            mode = sonyMode,
            ambientLevel = base?.ambientLevel ?: DEFAULT_AMBIENT_LEVEL,
            focusOnVoice = base?.focusOnVoice ?: false,
        )
        return try {
            if (controller.setAncMode(target)) {
                lastSonyState = target
                CommandResult.Success
            } else {
                CommandResult.Failed("Sony protocol rejected $mode")
            }
        } catch (throwable: Throwable) {
            CommandResult.Failed("Sony command failed", throwable)
        }
    }

    private suspend fun setDseeEnabled(enabled: Boolean): CommandResult {
        if (!capabilities.supportsDsee) {
            return CommandResult.Unsupported("DSEE is not supported by ${profile.id}")
        }
        return try {
            if (controller.setDseeEnabled(enabled)) {
                lastDseeEnabled = enabled
                CommandResult.Success
            } else {
                CommandResult.Failed("Sony protocol rejected DSEE=$enabled")
            }
        } catch (throwable: Throwable) {
            CommandResult.Failed("Sony DSEE command failed", throwable)
        }
    }

    @SuppressLint("MissingPermission")
    private fun protocolVersionFor(device: BluetoothDevice): SonyProtocolVersion {
        val serviceUuids = runCatching { device.uuids.orEmpty().map { it.uuid }.toSet() }.getOrDefault(emptySet())
        return when {
            SonyUuids.V2 in serviceUuids -> SonyProtocolVersion.V2
            SonyUuids.V1 in serviceUuids -> SonyProtocolVersion.V1
            profile.protocolVersion.equals("v2", ignoreCase = true) -> SonyProtocolVersion.V2
            else -> SonyProtocolVersion.V1
        }
    }

    companion object {
        private const val DEFAULT_AMBIENT_LEVEL = 10
    }
}

fun SonyAncMode.toCoreAncMode(): AncMode =
    when (this) {
        SonyAncMode.OFF -> AncMode.OFF
        SonyAncMode.NOISE_CANCELING -> AncMode.NOISE_CANCELLING
        SonyAncMode.AMBIENT_SOUND -> AncMode.TRANSPARENCY
        SonyAncMode.WIND_NOISE_REDUCTION -> AncMode.WIND_REDUCTION
    }

fun AncMode.toSonyAncMode(): SonyAncMode? =
    when (this) {
        AncMode.OFF -> SonyAncMode.OFF
        AncMode.NOISE_CANCELLING -> SonyAncMode.NOISE_CANCELING
        AncMode.TRANSPARENCY -> SonyAncMode.AMBIENT_SOUND
        AncMode.WIND_REDUCTION -> SonyAncMode.WIND_NOISE_REDUCTION
        AncMode.ADAPTIVE -> null
    }
