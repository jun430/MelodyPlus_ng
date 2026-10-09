package com.melody.melodyplus.bridge
import com.melody.melodyplus.hook.modLogT

import com.melody.melodyplus.hook.modLog

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.melody.melodyplus.adapter.sony.SonyHeadsetAdapter
import com.melody.melodyplus.core.AncMode
import com.melody.melodyplus.core.CommandResult
import com.melody.melodyplus.core.HeadsetAdapter
import com.melody.melodyplus.core.HeadsetCommand
import com.melody.melodyplus.core.HeadsetState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object HeadsetSessionManager {
    private const val TAG = "MelodyPlus"

    /**
     * [修复·多设备串行化] 原实现所有地址共用一把 [Mutex]，且锁覆盖阻塞 connect 与整段 SPP I/O：
     *   任一台耳机连接卡住，其它地址的状态读取/断开/命令全部排队等待。
     * 现按 normalized MAC 分配独立 Mutex：
     *   - 同一地址的 connect/read/write 仍严格串行（保留单设备命令顺序语义）；
     *   - 不同地址互不阻塞；
     *   - sessions 为 ConcurrentHashMap，同地址锁内做会话替换即保证该地址原子性。
     * 该映射只随实际连接的设备增长，规模受耳机数量约束。
     */
    private val addressLocks = ConcurrentHashMap<String, Mutex>()
    private val sessions = ConcurrentHashMap<String, Session>()

    /** [SPP 单所有者] 弹窗进程 `:fg` 不具备 SPP 所有权：建链会踢掉主进程 socket（实测乒乓互踢），
     *  故 :fg 内一律不建链，电量/状态由主进程经 BatteryShare 共享。 */
    private fun isPopupProcess(): Boolean =
        runCatching { android.app.Application.getProcessName() }.getOrNull()?.endsWith(":fg") == true

    private fun lockFor(normalizedAddress: String): Mutex =
        addressLocks.getOrPut(normalizedAddress) { Mutex() }

    @SuppressLint("MissingPermission")
    suspend fun connect(
        context: Context,
        address: String,
        profileId: String,
        device: BluetoothDevice? = null,
    ): Boolean = lockFor(DeviceRegistryStore.normalizeAddress(address)).withLock {
        if (isPopupProcess()) { modLog("I", "SPP_OWNERSHIP_GUARD connect-skip :fg"); return@withLock false }
        val profile = DeviceProfiles.get(profileId) ?: return false
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        val targetDevice = device ?: bluetoothDevice(context, normalized) ?: return false
        val session = sessions[normalized]
        if (session != null && session.connected) return true
        if (session != null) {
            // 链路已死：先释放残留 socket，否则同 UUID 二次 connect 必失败
            runCatching { session.adapter?.disconnect() }
            sessions.remove(normalized)
        }

        val adapter = createAdapter(profile)
        val connected = runCatching { adapter.connect(targetDevice) }
            .onFailure { modLogT("W", "connect $normalized failed", it) }
            .getOrDefault(false)
        if (connected) {
            sessions[normalized] = Session(profile, adapter)
            refreshStateLocked(normalized)
        }
        connected
    }

    suspend fun readState(address: String): HeadsetState? =
        lockFor(DeviceRegistryStore.normalizeAddress(address)).withLock {
            refreshStateLocked(DeviceRegistryStore.normalizeAddress(address))
        }

    /**
     * 读原始特征帧（面板真实状态回读用）。
     *
     * 与 [execute] 相同的懒连接语义：会话缺失时先 connect，再向 adapter 要原始应答。
     * adapter 不支持（默认返回 null）或未连接 → 返回 null（上层视为「状态未知」）。
     */
    suspend fun readFeatureRaw(
        context: Context,
        address: String,
        profileId: String,
        command: Int,
    ): ByteArray? = lockFor(DeviceRegistryStore.normalizeAddress(address)).withLock {
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        val profile = DeviceProfiles.get(profileId) ?: return null
        var session = sessions[normalized]
        if (session == null || !session.connected || session.adapter == null) {
            // 链路已死：释放残留 socket 后再重建（否则新 connect 会因旧连接占用而失败）
            if (session != null) runCatching { session.adapter?.disconnect() }
            val targetDevice = bluetoothDevice(context, normalized) ?: return null
            val adapter = createAdapter(profile)
            val connected = runCatching { adapter.connect(targetDevice) }
                .onFailure { modLogT("W", "read-connect $normalized failed", it) }
                .getOrDefault(false)
            if (!connected) return null
            session = Session(profile, adapter)
            sessions[normalized] = session
        }
        val adapter = session.adapter ?: return null
        runCatching { adapter.readFeatureRaw(command) }
            .onFailure { modLogT("W", "readFeatureRaw 0x${command.toString(16)} failed", it) }
            .getOrNull()
    }

    suspend fun execute(
        context: Context,
        address: String,
        profileId: String,
        command: HeadsetCommand,
    ): CommandResult = lockFor(DeviceRegistryStore.normalizeAddress(address)).withLock {
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        val profile = DeviceProfiles.get(profileId)
            ?: return CommandResult.Unsupported("Unknown profile $profileId")
        var session = sessions[normalized]
        if (session == null || !session.connected || session.adapter == null) {
            // 链路已死：释放残留 socket 后再重建（否则新 connect 会因旧连接占用而失败）
            if (session != null) runCatching { session.adapter?.disconnect() }
            val targetDevice = bluetoothDevice(context, normalized)
                ?: return CommandResult.Failed("Bluetooth device $normalized is unavailable")
            val adapter = createAdapter(profile)
            val connected = runCatching { adapter.connect(targetDevice) }
                .onFailure { modLogT("W", "lazy connect $normalized failed", it) }
                .getOrDefault(false)
            if (!connected) return CommandResult.Failed("Unable to connect to $normalized")
            session = Session(profile, adapter)
            sessions[normalized] = session
        }

        val adapter = session.adapter
            ?: return CommandResult.Failed("Adapter for $normalized is unavailable")
        val result = runCatching { adapter.execute(command) }
            .onFailure { modLogT("W", "execute $command failed", it) }
            .getOrElse { CommandResult.Failed("Command execution failed", it) }
        if (result is CommandResult.Success) {
            val readState = runCatching { adapter.readState() }
                .onFailure { modLogT("W", "read state after $command failed", it) }
                .getOrNull()
            session.lastState = when {
                command is HeadsetCommand.SetAncMode -> session.lastState.withAncMode(
                    address = normalized,
                    profile = profile,
                    mode = command.mode,
                    base = readState,
                )
                command is HeadsetCommand.SetDseeEnabled -> session.lastState.withDseeEnabled(
                    address = normalized,
                    profile = profile,
                    enabled = command.enabled,
                    base = readState,
                )
                readState != null -> readState
                else -> readState ?: session.lastState
            }
        }
        result
    }

    suspend fun refresh(
        context: Context,
        address: String,
        profileId: String,
    ): HeadsetState? = lockFor(DeviceRegistryStore.normalizeAddress(address)).withLock {
        if (isPopupProcess()) { modLog("I", "SPP_OWNERSHIP_GUARD refresh-skip :fg"); return@withLock null }
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        val profile = DeviceProfiles.get(profileId) ?: return null
        var session = sessions[normalized]
        if (session == null || !session.connected || session.adapter == null) {
            // 链路已死：释放残留 socket 后再重建（否则新 connect 会因旧连接占用而失败）
            if (session != null) runCatching { session.adapter?.disconnect() }
            val targetDevice = bluetoothDevice(context, normalized) ?: return session?.lastState
            val adapter = createAdapter(profile)
            val connected = runCatching { adapter.connect(targetDevice) }
                .onFailure { modLogT("W", "refresh connect $normalized failed", it) }
                .getOrDefault(false)
            if (!connected) return session?.lastState
            // [首连实时] 重连 = 新会话：**不继承上一会话的电量**（避免首连显示旧值/只出两路），
            //   仅保留 ANC 等控制态以免面板跳变；电量交由 adapter 重连后实时读取。
            session = Session(profile, adapter, lastState = session?.lastState?.withoutBattery())
            sessions[normalized] = session
        }
        refreshStateLocked(normalized)
    }

    suspend fun disconnect(address: String) = lockFor(DeviceRegistryStore.normalizeAddress(address)).withLock {
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        sessions.remove(normalized)?.adapter?.disconnect()
    }

    fun cachedState(address: String): HeadsetState? =
        sessions[DeviceRegistryStore.normalizeAddress(address)]?.lastState

    fun isSessionConnected(address: String): Boolean =
        sessions[DeviceRegistryStore.normalizeAddress(address)]?.connected == true

    fun rememberAncMode(address: String, profileId: String, mode: AncMode) {
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        val profile = DeviceProfiles.get(profileId) ?: return
        sessions.compute(normalized) { _, session ->
            val updatedState = session?.lastState.withAncMode(normalized, profile, mode)
            if (session != null) {
                session.copy(lastState = updatedState)
            } else {
                Session(profile, adapter = null, lastState = updatedState)
            }
        }
    }

    private fun createAdapter(profile: DeviceProfile): HeadsetAdapter =
        AdapterRegistry.resolve(profile.adapter)
            ?.invoke(profile)
            ?: throw IllegalArgumentException(
                "Unsupported adapter '${profile.adapter}' (register via AdapterRegistry.register)",
            )

    @SuppressLint("MissingPermission")
    private fun bluetoothDevice(context: Context, address: String): BluetoothDevice? =
        runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.getRemoteDevice(address)
        }.getOrNull()

    private suspend fun refreshStateLocked(address: String): HeadsetState? {
        val session = sessions[address] ?: return null
        val adapter = session.adapter ?: return session.lastState
        val state = runCatching { adapter.readState() }
            .onFailure { modLogT("W", "read state $address failed", it) }
            .getOrNull()
        if (state != null) session.lastState = state
        return state
    }

    private fun HeadsetState?.withoutBattery(): HeadsetState? = this?.copy(battery = null)

    private fun HeadsetState?.withAncMode(
        address: String,
        profile: DeviceProfile,
        mode: AncMode,
        base: HeadsetState? = null,
    ): HeadsetState =
        HeadsetState(
            address = base?.address?.takeIf { it.isNotBlank() } ?: this?.address?.takeIf { it.isNotBlank() } ?: address,
            name = base?.name ?: this?.name ?: profile.displayName,
            connected = base?.connected ?: this?.connected ?: false,
            battery = base?.battery ?: this?.battery,
            ancMode = mode,
            capabilities = base?.capabilities ?: this?.capabilities ?: profile.capabilities,
            updatedAtMillis = System.currentTimeMillis(),
            dseeEnabled = base?.dseeEnabled ?: this?.dseeEnabled,
        )

    private fun HeadsetState?.withDseeEnabled(
        address: String,
        profile: DeviceProfile,
        enabled: Boolean,
        base: HeadsetState? = null,
    ): HeadsetState =
        HeadsetState(
            address = base?.address?.takeIf { it.isNotBlank() } ?: this?.address?.takeIf { it.isNotBlank() } ?: address,
            name = base?.name ?: this?.name ?: profile.displayName,
            connected = base?.connected ?: this?.connected ?: false,
            battery = base?.battery ?: this?.battery,
            ancMode = base?.ancMode ?: this?.ancMode,
            capabilities = base?.capabilities ?: this?.capabilities ?: profile.capabilities,
            updatedAtMillis = System.currentTimeMillis(),
            dseeEnabled = enabled,
        )

    private data class Session(
        val profile: DeviceProfile,
        val adapter: HeadsetAdapter?,
        var lastState: HeadsetState? = null,
    ) {
        /**
         * 实时链路状态（**不再**是创建时快照）。
         *
         * 修复：RFCOMM/SPP 被对端关闭（或官方 App 抢占通道）后，快照恒 true →
         * execute/readFeatureRaw 永远复用死 socket，命令全量 `Send failed` 且从不重连。
         */
        val connected: Boolean get() = adapter?.isAlive == true
    }
}
