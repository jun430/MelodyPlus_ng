package com.melody.melodyplus.adapter.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.melody.melodyplus.core.BatteryState

class DefaultSonyAncController(
    private val onLog: (SonyLogEntry) -> Unit = {},
) : SonyAncController {
    private var client: SonyRfcommClient? = null
    private var protocol: SonyProtocol? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect(
        device: BluetoothDevice,
        version: SonyProtocolVersion,
        windSupported: Boolean,
    ): Boolean {
        disconnect()

        val newClient = SonyRfcommClient(onLog)
        if (!newClient.connect(device, version)) {
            return false
        }

        client = newClient
        protocol = SonyProtocol(newClient, version, windSupported)
        val initialized = protocol?.initialize() == true
        if (!initialized) {
            newClient.logInfo("Connected, but protocol init did not fully complete.")
        }
        return true
    }

    override suspend fun disconnect() {
        protocol = null
        client?.disconnect()
        client = null
    }

    override suspend fun getAncState(): SonyAncState? {
        return protocol?.getAncState()
    }

    override suspend fun getBatteryState(
        singleSupported: Boolean,
        dualSupported: Boolean,
        caseSupported: Boolean,
    ): BatteryState? {
        return protocol?.getBatteryState(
            singleSupported = singleSupported,
            dualSupported = dualSupported,
            caseSupported = caseSupported,
        )
    }

    override suspend fun getDseeEnabled(): Boolean? {
        return protocol?.getDseeEnabled()
    }

    override suspend fun setAncMode(state: SonyAncState): Boolean {
        return protocol?.setAncMode(state) == true
    }

    override suspend fun setDseeEnabled(enabled: Boolean): Boolean {
        return protocol?.setDseeEnabled(enabled) == true
    }
}
