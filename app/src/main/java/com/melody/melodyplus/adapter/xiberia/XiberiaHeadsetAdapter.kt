// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaHeadsetAdapter.kt
// 说明: HeadsetAdapter 实现。
//   MC05 无 ANC，但模块面板的 ANC 枚举复用为「模式」语义：
//     OFF           -> 关闭特殊模式（所有开关复位，用 0x0E05 查询兜底）
//     NOISE_CANCELLING -> 漏音抑制(0x0E0E) 开
//     TRANSPARENCY  -> 通透模式（MC05 无此硬件，预留）
//     ADAPTIVE      -> 游戏低延迟模式(0x0E11) 开
//   DSEE 语义 -> LDAC(0x0E0B) 开关
//   模式互斥：开漏音抑制时自动关游戏模式，反之亦然

package com.melody.melodyplus.adapter.xiberia

import com.melody.melodyplus.hook.modLog

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.melody.melodyplus.bridge.DeviceProfile
import com.melody.melodyplus.core.AncMode
import com.melody.melodyplus.core.BatteryState
import com.melody.melodyplus.core.CommandResult
import com.melody.melodyplus.core.HeadsetCapabilities
import com.melody.melodyplus.core.HeadsetAdapter
import com.melody.melodyplus.core.HeadsetCommand
import com.melody.melodyplus.core.HeadsetState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class XiberiaHeadsetAdapter(
    private val profile: DeviceProfile,
    private val client: XiberiaRfcommClient = XiberiaRfcommClient(
        onLog = { msg -> modLog("I", "XIBERIA_SPP: $msg") },
    ),
) : HeadsetAdapter {
    private companion object {
        /** 连接后「查到三路齐全」的最长等待时间（毫秒）。 */
        private const val PRIME_BATTERY_MAX_WAIT_MS = 6_000L

        /** 连接后电量补齐的两次尝试间隔（毫秒）。 */
        private const val PRIME_BATTERY_RETRY_MS = 800L
    }
    private val commandMutex = Mutex()

    /** 本 adapter 绑定设备的 MAC（连接成功后写入），作为 backend 的会话键。 */
    @Volatile private var boundMac: String? = null

    @Volatile private var lastBattery: Int? = null
    @Volatile private var leakSuppress: Boolean = false
    @Volatile private var gameMode: Boolean = false
    @Volatile private var ldac: Boolean = false

    override val capabilities: HeadsetCapabilities =
        profile.capabilities

    /** 实时链路状态：直通 RFCOMM client（socket 断开即时反映，供 SessionManager 触发重连）。 */
    override val isAlive: Boolean
        get() = client.isConnected

    @SuppressLint("MissingPermission")
    override suspend fun connect(device: BluetoothDevice): Boolean {
        // [首连实时] 新会话开始：清空上一会话残留的单耳兜底值，保证首连读到的是本次链路真值。
        lastBattery = null
        val ok = client.connect(device)
        if (ok) {
            // 把已连接链路注册进 backend（对应参考模块 ensureSession 复用已有 session）
            val mac = device.address.uppercase()
            boundMac = mac
            XiberiaFeatureBackendHolder.backend.attachConnectedSession(
                mac = mac,
                device = device,
                client = client,
            )
            // 连接后拉一次电量（App 实测连接即查电量 0xA01）→ 直到三路齐全（L/R/C）。
            runCatching { primeBatteryAfterConnect() }
        }
        return ok
    }

    /**
     * 连接后「连接即查电量」：循环查询直到拿到**完整三态**（左/右/仓），或超时。
     *
     * 首连时耳机 SPP 服务刚就绪，第一轮查询常只得部分/单路；此循环配合
     * [XiberiaRfcommClient] 的多轮探测链与 backend 的重试/按槽合并，保证连接
     * 事件结束前 detailBatteryCache 已是完整三态，弹窗/详情页不再出现某路 `--`。
     */
    private suspend fun primeBatteryAfterConnect() {
        val mac = boundMac
        val deadline = System.currentTimeMillis() + PRIME_BATTERY_MAX_WAIT_MS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val st = runCatching { readBatteryState() }.getOrNull()
            if (st != null && st.left != null && st.right != null && st.caseBattery != null) {
                modLog("I", "XIBERIA_SPP: prime battery OK attempt=$attempt mac=$mac " +
                        "L=${st.left} R=${st.right} C=${st.caseBattery}")
                return
            }
            modLog("I", "XIBERIA_SPP: prime battery attempt=$attempt incomplete " +
                    "L=${st?.left} R=${st?.right} C=${st?.caseBattery}")
            if (System.currentTimeMillis() + PRIME_BATTERY_RETRY_MS >= deadline) break
            kotlinx.coroutines.delay(PRIME_BATTERY_RETRY_MS)
        }
        modLog("I", "XIBERIA_SPP: prime battery exhausted after $attempt attempt(s) mac=$mac")
    }

    override suspend fun readState(): HeadsetState {
        val battery = runCatching { readBatteryState() }.getOrNull()
        return HeadsetState(
            address = boundMac ?: "",
            name = profile.displayName,
            connected = client.isConnected,
            battery = battery,
            ancMode = currentMode(),
            capabilities = capabilities,
            updatedAtMillis = System.currentTimeMillis(),
            dseeEnabled = ldac,
        )
    }

    override suspend fun execute(command: HeadsetCommand): CommandResult = commandMutex.withLock {
        when (command) {
            is HeadsetCommand.SetAncMode -> setMode(command.mode)
            is HeadsetCommand.SetAmbientLevel ->
                // AmbientLevel 语义复用为提示音档位 0..3
                setToneLevel(command.level)
            is HeadsetCommand.SetDseeEnabled -> setLdac(command.enabled)
            is HeadsetCommand.SetSwitch -> setFeatureSwitch(command.command, command.enabled)
            is HeadsetCommand.SetLevel -> setFeatureLevel(command.command, command.level)
            is HeadsetCommand.SetEqCustom -> setEqCustom(command.subCmd, command.gains)
        }
    }

    override suspend fun disconnect() {
        boundMac?.let { runCatching { XiberiaFeatureBackendHolder.backend.closeExistingSession(it, "adapter disconnect") } }
        boundMac = null
        client.disconnect()
    }

    /**
     * 读原始特征帧：走与 SET 相同的通道顺序（backend 优先 → client 回退）。
     *
     * ⚠️ 修复记录：早期实现只走 `backend.queryRaw`，而 backend 的 session 在本模块里
     *    从未被建立过（SET 实际由 `client.sendCommand` 完成）→ 探测 100% no-answer。
     *    现改为 client 主通道（`sendCommand(cmd)` 空 payload 即查询），backend 作回退。
     */
    override suspend fun readFeatureRaw(command: Int): ByteArray? {
        val mac = boundMac
        if (mac != null) {
            val viaBackend = runCatching {
                XiberiaFeatureBackendHolder.backend.queryRaw(mac, command).get()
            }.getOrNull()
            if (viaBackend != null) return viaBackend
        }
        return runCatching { client.sendCommand(command, ByteArray(0)) }.getOrNull()
    }

    // ---------- 模式映射 ----------

    private fun currentMode(): AncMode? = when {
        leakSuppress -> AncMode.NOISE_CANCELLING
        gameMode -> AncMode.ADAPTIVE
        else -> AncMode.OFF
    }

    private suspend fun setMode(mode: AncMode): CommandResult {
        val mac = boundMac
        val result = when (mode) {
            AncMode.OFF -> {
                // 全关
                val a = writeSwitch(XiberiaCommands.LEAK_SUPPRESS, false)
                val b = writeSwitch(XiberiaCommands.GAME_MODE, false)
                if (a || b) {
                    leakSuppress = false; gameMode = false
                    CommandResult.Success
                } else CommandResult.Failed("No ack for mode off")
            }
            AncMode.NOISE_CANCELLING -> {
                // 漏音抑制开，游戏模式关（互斥）
                writeSwitch(XiberiaCommands.GAME_MODE, false)
                if (writeSwitch(XiberiaCommands.LEAK_SUPPRESS, true)) {
                    leakSuppress = true; gameMode = false
                    CommandResult.Success
                } else CommandResult.Failed("Leak suppress on failed")
            }
            AncMode.ADAPTIVE -> {
                // 游戏低延迟开，漏音抑制关（互斥）
                writeSwitch(XiberiaCommands.LEAK_SUPPRESS, false)
                if (writeSwitch(XiberiaCommands.GAME_MODE, true)) {
                    gameMode = true; leakSuppress = false
                    CommandResult.Success
                } else CommandResult.Failed("Game mode on failed")
            }
            AncMode.TRANSPARENCY, AncMode.WIND_REDUCTION ->
                CommandResult.Unsupported("MC05 has no $mode")
        }
        // 同步 backend 在途意图（对应参考模块 authoritativeMode）
        if (mac != null) {
            runCatching {
                XiberiaFeatureBackendHolder.backend.desiredMode(mac)
            }
        }
        return result
    }

    /**
     * 写开关：优先走 backend 的 set+verify 路径（有在途闸与缓存更新），
     * backend 未挂会话时回落直写 client。对应参考模块 setControlAndVerify。
     */
    private suspend fun writeSwitch(cmd: Int, value: Boolean): Boolean {
        val mac = boundMac
        if (mac != null) {
            val viaBackend = runCatching {
                XiberiaFeatureBackendHolder.backend.setControlAndVerify(mac, cmd, value)
                    .get(6, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrNull()
            if (viaBackend == true) return true
        }
        return client.sendCommand(cmd, XiberiaCommands.Payload.switch(value)) != null
    }

    // ---------- 子功能 ----------

    private suspend fun setToneLevel(level: Int): CommandResult {
        val clamped = level.coerceIn(0, 3)
        val mac = boundMac
        if (mac != null) {
            val viaBackend = runCatching {
                XiberiaFeatureBackendHolder.backend.setToneLevelAndVerify(mac, clamped)
                    .get(6, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrNull()
            if (viaBackend == true) return CommandResult.Success
        }
        val ack = client.sendCommand(
            XiberiaCommands.TONE_LEVEL,
            XiberiaCommands.Payload.level(clamped),
        )
        return if (ack != null) CommandResult.Success
        else CommandResult.Failed("Tone level ack timeout")
    }

    private suspend fun setLdac(enabled: Boolean): CommandResult {
        if (!writeSwitch(XiberiaCommands.LDAC, enabled)) {
            return CommandResult.Failed("LDAC switch failed")
        }
        ldac = enabled
        return CommandResult.Success
    }

    /**
     * MC05 功能面板「通用布尔开关」写：命令码由调用方携带（官方 CommandId 真源）。
     *
     * 走 [writeSwitch]（backend set+verify 优先，失败回落 client 直写并等应答），
     * 成功后同步对应本地态（游戏模式 / LDAC / 漏音抑制），供 [currentMode]/[readState] 复用。
     */
    private suspend fun setFeatureSwitch(cmd: Int, enabled: Boolean): CommandResult {
        // [官方对齐·能力位门控] 动态面板路径必须按型号能力位放行（官方 DeviceActivity 只对
        //   isSupportXxx()==true 的项发 SET）；未识别/未开放型号或该项不属于该型号 → 拒发。
        //   activeProductId 未知（0）时放行（兼容旧的直写路径，避免误伤未绑定型号的调用）。
        val pid = XiberiaProductCatalogHolder.activeProductId
        if (pid != 0 && !XiberiaProductCatalog.supportsCommand(pid, cmd)) {
            modLog("W", "XIBERIA_GATE reject SET cmd=0x${cmd.toString(16)} for productId=0x${pid.toString(16)}")
            return CommandResult.Unsupported("Command 0x${cmd.toString(16)} not supported by product 0x${pid.toString(16)}")
        }
        if (!writeSwitch(cmd, enabled)) {
            return CommandResult.Failed("Feature 0x${cmd.toString(16)} set failed")
        }
        when (cmd) {
            XiberiaCommands.GAME_MODE -> gameMode = enabled
            XiberiaCommands.LDAC -> ldac = enabled
            XiberiaCommands.LEAK_SUPPRESS -> leakSuppress = enabled
        }
        return CommandResult.Success
    }

/** 档位/多选值写（型号自适配面板 `Kind.CHOICE` 项）。
     *
     * 走 `session.request(cmd, [0x01, level])` —— 与官方 `Payload.level` 一致
     * （降噪 `NoiseMode.command` / 音效 `SoundEffectMode.modeValue` / 音量档位 0..3）。
     */
    private suspend fun setFeatureLevel(cmd: Int, level: Int): CommandResult {
        val mac = boundMac ?: return CommandResult.Failed("No session for level write")
        // [官方对齐·能力位门控] 同 setFeatureSwitch：动态面板的档位写也按型号能力位放行。
        val pid = XiberiaProductCatalogHolder.activeProductId
        if (pid != 0 && !XiberiaProductCatalog.supportsCommand(pid, cmd)) {
            modLog("W", "XIBERIA_GATE reject LEVEL cmd=0x${cmd.toString(16)} for productId=0x${pid.toString(16)}")
            return CommandResult.Unsupported("Command 0x${cmd.toString(16)} not supported by product 0x${pid.toString(16)}")
        }
        val session = XiberiaFeatureBackendHolder.backend.existingSession(mac)
            ?: return CommandResult.Failed("No session for 0x${cmd.toString(16)}")
        val ack = runCatching {
            kotlinx.coroutines.runBlocking {
                session.request(cmd, XiberiaCommands.Payload.level(level))
            }
        }.getOrNull()
        return if (ack != null) CommandResult.Success
        else CommandResult.Failed("Level 0x${cmd.toString(16)}=$level failed")
    }

    /**
     * 自定义 EQ 增益写（官方 `Protocol.setCustomEq(dev, 0x0806, subCmd, gains)`）。
     *
     * payload = `[subCmd][gain0]…[gainN]`（[XiberiaCommands.Payload.eqCustom]）。
     * 官方随后会 `switchEq` 切到自定义档并延迟 100ms 回读；模块侧只负责下发增益。
     */
    private suspend fun setEqCustom(subCmd: Int, gains: IntArray): CommandResult {
        if (gains.isEmpty()) return CommandResult.Failed("EQ gains empty")
        val mac = boundMac ?: return CommandResult.Failed("No session for EQ write")
        val session = XiberiaFeatureBackendHolder.backend.existingSession(mac)
            ?: return CommandResult.Failed("No session for EQ 0x0806")
        val payload = XiberiaCommands.Payload.eqCustom(subCmd, gains)
        val ack = runCatching {
            kotlinx.coroutines.runBlocking {
                session.request(XiberiaCommands.EQ_CUSTOM, payload)
            }
        }.getOrNull()
        return if (ack != null) CommandResult.Success
        else CommandResult.Failed("EQ custom subCmd=0x${subCmd.toString(16)} failed")
    }

    // ---------- 读取 ----------

    /** 电量读取优先用 backend 缓存/查询（官方三态 L/R/C：left/right/caseBattery）。 */
    private suspend fun readBatteryState(): BatteryState? {
        val mac = boundMac
        if (mac != null) {
            val snap = runCatching { XiberiaFeatureBackendHolder.backend.queryBattery(mac).get() }
                .getOrNull()
            if (snap != null &&
                (snap.left != null || snap.right != null || snap.caseBattery != null || snap.single != null)
            ) {
                lastBattery = snap.left ?: snap.single
                return BatteryState(
                    single = snap.single,
                    left = snap.left,
                    right = snap.right,
                    caseBattery = snap.caseBattery,
                    leftCharging = snap.leftCharging,
                    rightCharging = snap.rightCharging,
                    caseCharging = snap.caseCharging,
                )
            }
        }
        val payload = client.queryBatteryPayload()
            ?: return lastBattery?.let { BatteryState(single = it) }   // [修复] 单值不再塞 left 槽
        // 官方语义：payload = [L.on][L.level][R.on][R.level][C.on][C.level]
        val full = XiberiaProtocolCodec.parseBatteryFull(payload)
        if (full != null) {
            lastBattery = full.leftLevel
            return BatteryState(
                single = null,
                left = full.leftLevel,
                right = full.rightLevel,
                caseBattery = full.caseLevel,
                leftCharging = full.leftCharging,
                rightCharging = full.rightCharging,
                caseCharging = full.caseCharging,
            )
        }
        val value = XiberiaProtocolCodec.parseBattery(payload).firstOrNull()?.takeIf { it >= 0 }
            ?: return lastBattery?.let { BatteryState(single = it) }   // [修复] 同上，仅回 single
        lastBattery = value
        return BatteryState(single = value)   // [修复] 单值只落 single，不污染 left
    }
}

/** 进程内共享的 XIBERIA backend 单例（对应参考模块的全局 backend 持有）。 */
internal object XiberiaFeatureBackendHolder {
    val backend: XiberiaFeatureBackend by lazy { XiberiaFeatureBackend() }
}

/**
 * 进程内共享的「当前面板型号」持有者。
 *
 * 用途：adapter（写路径）需要按型号能力位做门控（[XiberiaProductCatalog.supportsCommand]），
 * 但型号由 hook 层（`MelodyPanelHook.activePanelProductId`）在面板注入时确定。
 * hook 层注入面板时同步写入本 holder，adapter 读取；
 * **0 = 未知型号**（不门控，兼容未绑定/未注入面板的旧路径）。
 */
internal object XiberiaProductCatalogHolder {
    @Volatile var activeProductId: Int = 0
}