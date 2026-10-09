// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaFeatureBackend.kt
// 来源: 参考模块 com.oai.huaweimelodycompat.HuaweiFeatureBackend 的源码逻辑逆向复刻。
//       保留其「会话缓存 + 查询/设置并校验 + 快照发布」的方法骨架，
//       协议侧整体替换为 XIBERIA（cchip）SPP 帧。
//
// 参考模块 HuaweiFeatureBackend 结构（逆向，workspace 71313114）：
//   session/cache : ensureSession/closeExistingSession/reconnectSession/existingSession/invalidateSession
//                   cachedBattery/cachedBatteryAgeMs/cachedControl/cachedControls/cachedWearing/cachedEffect
//                   clearBattery/clearWearing/state/key/safeMac
//   query        : queryBattery/queryControls/queryWearing/queryControl/queryControlSafely
//   set+verify   : setControlAndVerify/setEffectAndVerify/setFindDeviceAndVerify/setFitDetection
//   snapshot     : snapshot(DeviceState):ControlSnapshot / updateFromBridge / updateControlsFromBridge
//   listeners    : setBatteryListener/setWearingListener/setFitResultListener
//   nested       : BatterySnapshot/ControlSnapshot/EqPreset/DeviceState/*
//
// 本实现把「字节编解码」下压到 XiberiaProtocolCodec，把「会话」下压到 XiberiaSession，
// 只保留参考模块的编排语义（缓存、在途、校验、回调）。

package com.melody.melodyplus.adapter.xiberia

import com.melody.melodyplus.hook.modLog

import android.bluetooth.BluetoothDevice
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class XiberiaFeatureBackend(
    private val sessionFactory: (BluetoothDevice) -> XiberiaSession = { XiberiaSession(it) },
) {
    /** 电量链路日志（Logcat tag = MelodyPlus，与 hook 层同一过滤器）。 */
    private fun batteryLog(mac: String, msg: String) {
        modLog("I", "XIBERIA_BATT[$mac] $msg")
    }
    // ---------- 快照模型（对应参考模块 nested 类）----------

    data class BatterySnapshot(
        val single: Int?,
        val left: Int?,
        val right: Int?,
        val caseBattery: Int?,
        val receivedAtMillis: Long,
        val leftCharging: Boolean? = null,
        val rightCharging: Boolean? = null,
        val caseCharging: Boolean? = null,
    )

    data class ControlSnapshot(
        val mode: Int,
        val switchA: Boolean,
        val switchB: Boolean,
        val gameMode: Boolean,
        val ldac: Boolean,
        val toneLevel: Int,
        val receivedAtMillis: Long,
    )

    data class EqPreset(val id: Int, val name: String)

    public class DeviceState(
        val mac: String,
        var session: XiberiaSession? = null,
    )

    interface BatteryListener {
        fun onBattery(mac: String, snapshot: BatterySnapshot)
    }

    interface ControlListener {
        fun onControl(mac: String, snapshot: ControlSnapshot)
    }

    // ---------- 会话与缓存 ----------

    private val states = ConcurrentHashMap<String, DeviceState>()
    private val batteryCache = ConcurrentHashMap<String, BatterySnapshot>()
    private val batteryStamp = ConcurrentHashMap<String, Long>()
    private val controlCache = ConcurrentHashMap<String, ControlSnapshot>()
    private val gate = XiberiaAncCommandGate()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "XiberiaFeatureBackend").apply { isDaemon = true }
    }

    @Volatile private var batteryListener: BatteryListener? = null

    fun setBatteryListener(listener: BatteryListener?) {
        batteryListener = listener
    }

    fun key(mac: String): String = mac.uppercase()
    fun safeMac(mac: String?): String = (mac ?: "").uppercase()
    fun state(mac: String): DeviceState = states.getOrPut(key(mac)) { DeviceState(key(mac)) }

    fun attachSession(mac: String, session: XiberiaSession) {
        state(mac).session = session
    }

    /**
     * 绑定一条「已经 connect 成功」的链路（adapter 侧 client 复用），
     * 避免 backend 重新建连。对应参考模块 ensureSession 命中已有 session 的分支。
     */
    fun attachConnectedSession(mac: String, device: BluetoothDevice, client: XiberiaRfcommClient): XiberiaSession {
        val session = XiberiaSession(device, client)
        state(mac).session = session
        return session
    }

    /** 写开关（无校验版，供 adapter 的简单写路径复用）。 */
    fun writeSwitch(mac: String, command: Int, value: Boolean): Boolean {
        val session = state(mac).session ?: return false
        return kotlinx.coroutines.runBlocking {
            session.request(command, XiberiaCommands.Payload.switch(value)) != null
        }
    }

    fun existingSession(mac: String): XiberiaSession? = states[key(mac)]?.session

    fun closeExistingSession(mac: String, reason: String): Boolean {
        val s = states[key(mac)]?.session ?: return false
        s.close(reason)
        states.remove(key(mac))
        invalidateSession(mac, reason, null)
        return true
    }

    fun invalidateSession(mac: String, reason: String, failure: Throwable?) {
        gate.clear(mac)
        states.remove(key(mac))
    }

    // ---------- 查询 ----------

    fun cachedBattery(mac: String): BatterySnapshot? = batteryCache[key(mac)]

    fun cachedBatteryAgeMs(mac: String): Long =
        batteryStamp[key(mac)]?.let { System.currentTimeMillis() - it } ?: -1L

    fun cachedControl(mac: String): ControlSnapshot? = controlCache[key(mac)]

    fun clearBattery(mac: String) {
        batteryCache.remove(key(mac))
        batteryStamp.remove(key(mac))
    }

    /** 查询电量（对应参考模块 queryBattery）。
     *
     * 修复·首连拿不齐三路：旧实现只发一次探测链，首连耳机 SPP 未就绪时直接回 null
     *   → 弹窗/详情页某一路恒空。现改为**多次重试直到解析出完整三态**，并把结果按槽
     *   合并进缓存（某轮只得单路时不会丢已得值）。
     */
    fun queryBattery(mac: String): CompletableFuture<BatterySnapshot?> =
        supplyAsync {
            val session = state(mac).session ?: return@supplyAsync null
            queryBatterySnapshot(session, key(mac))
        }

    /** 有效电量档位判定：仅 [0,100] 视为有效，其余（0xFF 失效占位 / 越界）返回 null。 */
    private fun Int?.sanitizeLevel(): Int? = this?.takeIf { it in 0..100 }

    /**
     * 电量查询核心：最多 [QUERY_FULL_ATTEMPTS] 次，直到解析出**完整三态**（L/R/C 全非空）；
     * 拿不到完整值则返回**本次实时读取**的最佳快照（不与历史缓存合并，保证首连纯净）。
     */
    private fun queryBatterySnapshot(session: XiberiaSession, k: String): BatterySnapshot? {
        var best: BatterySnapshot? = null
        for (attempt in 1..QUERY_FULL_ATTEMPTS) {
            // 探测链：0xA11 → 0xA01 → 0xA12 → 0xA02，取第一个 ≥6 字节 payload（内含多轮）。
            val payload = session.queryBatteryPayload() ?: continue
            // 官方语义：payload = [L.on][L.level][R.on][R.level][C.on][C.level]
            val full = XiberiaProtocolCodec.parseBatteryFull(payload)
            val snapshot = if (full != null) {
                // [首连实时·有效档位] 级别必须落在有效档位 [0,100]；0xFF/0xFE 等为「该路未知/失效」
                //   占位（首连时某只耳常未上报）。若不过滤会被当成有效值 → 弹窗把 0xFF 显示成假电量，
                //   且 isComplete() 误判齐全提前停止重试。无效一律置 null，交给重试/后续轮询补齐。
                BatterySnapshot(
                    single = null,
                    left = full.leftLevel.sanitizeLevel(),
                    right = full.rightLevel.sanitizeLevel(),
                    caseBattery = full.caseLevel.sanitizeLevel(),
                    receivedAtMillis = System.currentTimeMillis(),
                    leftCharging = full.leftCharging.takeIf { full.leftLevel.sanitizeLevel() != null },
                    rightCharging = full.rightCharging.takeIf { full.rightLevel.sanitizeLevel() != null },
                    caseCharging = full.caseCharging.takeIf { full.caseLevel.sanitizeLevel() != null },
                )
            } else {
                // [修复·电量间歇错值] 退化路径：parseBattery 只会回单值（语义未知），
                //   绝不再把它同时当 left/right/case 三态之一，避免污染对应槽位。
                BatterySnapshot(
                    single = XiberiaProtocolCodec.parseBattery(payload).firstOrNull()?.sanitizeLevel(),
                    left = null,
                    right = null,
                    caseBattery = null,
                    receivedAtMillis = System.currentTimeMillis(),
                )
            }
            best = mergeSnapshots(snapshot, best)
            if (snapshot.left != null && snapshot.right != null && snapshot.caseBattery != null) {
                batteryLog(k, "QUERY_BATTERY_FULL attempt=$attempt")
                break
            }
            if (attempt < QUERY_FULL_ATTEMPTS) {
                batteryLog(k, "QUERY_BATTERY_PARTIAL attempt=$attempt " +
                    "L=${snapshot.left} R=${snapshot.right} C=${snapshot.caseBattery} → retry")
                runCatching { Thread.sleep(QUERY_FULL_RETRY_MS) }
            }
        }
        // [首连实时·去缓存] 不再用 batteryCache 兜底合并：首连必须以**本次实时链路读取**为准，
//   否则上一会话残留的旧值会掺入（真机表现为「首连某路显示上次电量/只出两路」）。
//   batteryCache 仅作为本次读取结果的落盘（供后续轮询/快照复用），绝不反向污染本次返回值。
        val result = best
        if (result != null) {
            batteryCache[k] = result
            batteryStamp[k] = result.receivedAtMillis
            batteryListener?.onBattery(k, result)
        }
        return result
    }

    /** 逐槽合并两份电量快照：任一路取非空的优先源（primary 优先，即较新）。 */
    private fun mergeSnapshots(primary: BatterySnapshot?, secondary: BatterySnapshot?): BatterySnapshot? {
        if (primary == null) return secondary
        if (secondary == null) return primary
        return BatterySnapshot(
            single = primary.single ?: secondary.single,
            left = primary.left ?: secondary.left,
            right = primary.right ?: secondary.right,
            caseBattery = primary.caseBattery ?: secondary.caseBattery,
            receivedAtMillis = maxOf(primary.receivedAtMillis, secondary.receivedAtMillis),
            leftCharging = primary.leftCharging ?: secondary.leftCharging,
            rightCharging = primary.rightCharging ?: secondary.rightCharging,
            caseCharging = primary.caseCharging ?: secondary.caseCharging,
        )
    }

    /**
     * 查询控制状态（对应参考模块 queryControls）。
     *
     * ⚠️ 修正记录：原实现用 `0x0807`（= USER_ALL_EQ_GET / EQ 预设查询）当"设备全状态查询"，
     *  `parseDeviceCaps` 把 EQ 增益字节误当 switchA/gameMode/ldac 位解析 → 结果是垃圾。
     *
     * 现改为**分项 GET 逐项回读**：
     *   游戏模式 `0x0C02` / LDAC `0x0E05` / 音量档位 `0x0E27`
     * 每项独立超时判定，任一项无应答只影响该项（null），不污染其它项。
     *
     * 应答 payload 约定（与 SET 帧对称）：`[0x01, value]` → 取**尾字节**；`0x01`=开。
     *  ⚠️ 待真机抓帧复核：已加 XIBERIA_QUERY_RAW 日志 dump 原始 hex，校准后改 [readSwitch] 取值即可。
     */
    fun queryControls(mac: String): CompletableFuture<ControlSnapshot?> =
        supplyAsync {
            val session = state(mac).session ?: return@supplyAsync null
            val game = readSwitch(session, XiberiaCommands.GAME_MODE_GET)
            val ldacValue = readSwitch(session, XiberiaCommands.LDAC_GET)
            val tone = readLevel(session, XiberiaCommands.VOLUME_GEAR_GET)
            val snapshot = ControlSnapshot(
                mode = 0,
                switchA = game == true,
                switchB = ldacValue == true,
                gameMode = game == true,
                ldac = ldacValue == true,
                toneLevel = tone ?: 0,
                receivedAtMillis = System.currentTimeMillis(),
            )
            controlCache[key(mac)] = snapshot
            snapshot
        }

    /**
     * 原始查询通道（抓帧调试 + 面板真实状态回读基础）。
     *
     * 发 [command] 并返回原始应答 payload（无应答/超时 = null）。不解析、不缓存，
     * 供上层做「探测式回读」：先用它 dump 各 GET 命令的原始帧，确认 payload 布局。
     */
    fun queryRaw(mac: String, command: Int): CompletableFuture<ByteArray?> =
        supplyAsync {
            val session = state(mac).session ?: return@supplyAsync null
            val payload = runCatching { session.request(command) }.getOrNull()
            modLog("I", "XIBERIA_QUERY_RAW cmd=0x${command.toString(16)} " +
                    "payload=${payload?.joinToString("") { "%02X".format(it) } ?: "null"}")
            payload
        }

    /**
     * 读布尔开关。
     *
     * @return true/false = 设备真实状态；null = 无应答/未连接（**与 false 严格区分**，
     *         上层用它决定「显示关」还是「状态未知」）。
     */
    private fun readSwitch(session: XiberiaSession, command: Int): Boolean? =
        runCatching { session.request(command) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                modLog("I", "XIBERIA_READ_SWITCH cmd=0x${command.toString(16)} " +
                        "raw=${it.joinToString("") { b -> "%02X".format(b) }}")
            }
            ?.let { (it[it.size - 1].toInt() and 0xFF) == 0x01 }

    /** 读档位值（0..3）；null = 无应答。 */
    private fun readLevel(session: XiberiaSession, command: Int): Int? =
        runCatching { session.request(command) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { it[it.size - 1].toInt() and 0xFF }

    // ---------- 设置（写 + 校验，对应 setXxxAndVerify）----------

    /** 写开关并校验应答（对应参考模块 setControlAndVerify）。 */
    fun setControlAndVerify(mac: String, command: Int, value: Boolean): CompletableFuture<Boolean> {
        val k = key(mac)
        val start = gate.request(k, command, "setControl 0x${command.toString(16)}")
        return supplyAsync {
            val session = state(k).session ?: return@supplyAsync false
            val ack = session.request(command, XiberiaCommands.Payload.switch(value))
            val ok = ack != null
            val completion = gate.complete(k, start.token, if (ok) 0 else -1)
            if (XiberiaAncCommandGate.finalIntentSucceeded(completion, if (ok) 0 else -1, null)) {
                controlCache[k]?.let { prev ->
                    controlCache[k] = when (command) {
                        XiberiaCommands.SWITCH_A -> prev.copy(switchA = value)
                        XiberiaCommands.SWITCH_B -> prev.copy(switchB = value)
                        XiberiaCommands.GAME_MODE -> prev.copy(gameMode = value)
                        XiberiaCommands.LDAC -> prev.copy(ldac = value)
                        else -> prev
                    }
                }
            }
            ok
        }
    }

    /** 写档位并校验（对应参考模块 setEffectAndVerify）。 */
    fun setToneLevelAndVerify(mac: String, level: Int): CompletableFuture<Boolean> {
        val k = key(mac)
        val clamped = level.coerceIn(0, 3)
        return supplyAsync {
            val session = state(k).session ?: return@supplyAsync false
            val ack = session.request(XiberiaCommands.TONE_LEVEL, XiberiaCommands.Payload.level(clamped))
            ack != null
        }
    }

    fun desiredMode(mac: String): Int = gate.desiredMode(mac)
    fun presentedMode(mac: String, fallback: Int): Int = gate.presentedMode(mac, fallback)
    fun isInFlight(mac: String): Boolean = gate.isInFlight(mac)

    // ---------- 生命周期 ----------

    fun shutdown() {
        states.values.forEach { runCatching { it.session?.close("backend shutdown") } }
        states.clear()
        executor.shutdownNow()
    }

    // ---------- 工具 ----------

    private fun <T> supplyAsync(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ block() }, executor)

    companion object {
        private const val TAG = "XiberiaFeatureBackend"

        /** 电量查询最大尝试次数：直到解析出完整三态 L/R/C 或耗尽。 */
        private const val QUERY_FULL_ATTEMPTS = 2

        /** 两次完整查询尝试之间的等待（毫秒）：给耳机就绪留时间。 */
        private const val QUERY_FULL_RETRY_MS = 600L
    }
}