// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaRfcommClient.kt
// 架构: ARM64 / Android / RFCOMM(SPP 标准UUID 00001101-...)
// 说明: 参考 SonyRfcommClient 骨架，帧判定改为 0xFF 头 + b[3] 长度域

package com.melody.melodyplus.adapter.xiberia

import com.melody.melodyplus.hook.modLog

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class XiberiaRfcommClient(
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        // cchip 系耳机用标准 SPP UUID（App 内 BTSppLink 反编译确认）
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val RESPONSE_TIMEOUT_MS = 1_500L
        /**
         * RFCOMM `socket.connect()` 超时（毫秒）。
         *
         * 必要性：Android 的 `BluetoothSocket.connect()` 是**阻塞调用**，某些半死场景
         * （对端在范围内但不响应寻呼 / 被官方 App 抢占 SPP 通道）会阻塞数十秒，
         * 期间 `HeadsetSessionManager` 的全局 Mutex 被占住 → 面板写入/电量补采全部排队卡死。
         * 此处用 `withTimeoutOrNull` 兜底：超时即关闭该 socket 并按连接失败返回，
         * 上层 [HeadsetSessionManager] 会把会话视为未连接，下次操作重新建链。
         */
        private const val CONNECT_TIMEOUT_MS = 8_000L
        /** 连续「零 RX 超时」达到该次数即判链路半死，主动关闭以触发重连。 */
        private const val STALE_LINK_SILENT_TIMEOUTS = 6
        /**
         * 连接宽限期（毫秒）：刚 connect 成功后耳机 SPP 服务可能仍在就绪中，
         * 此窗口内的「零 RX 超时」属正常，**不判死也不计数**。
         *
         * 修复：旧值 3 次即判死 + 无宽限期，导致首次连接时探测链前 3 条命令刚落空
         *   （耳机还没起来应答）就把 socket 关掉 → 后续命令全量失败 → 首连永远查不到电量。
         */
        private const val LINK_STALE_GRACE_MS = 5_000L

        /**
         * 电量探测链最大轮数：每轮 4 条命令；首连时耳机就绪慢，靠多轮覆盖。
         * 单轮最坏 4×1.5s=6s，多轮叠加可能较长，故 [queryBatteryPayload] 只在
         * 首连/补采链路（非 UI 主线程）调用，可接受。
         */
        private const val MAX_ROUNDS = 2

        /** 电量探测轮间退避（毫秒）：给耳机 SPP 服务就绪留时间。 */
        private const val ROUND_BACKOFF_MS = 400L
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    /** [修复·电量间歇错值] 电量查询串行化：connect 内首读、详情页补采、周期轮询
     *  三路会并发调用本方法，共用一条 waiter 队列，短帧/上报帧会被别的 waiter 认领
     *  → 偶发串值。用一把互斥锁把整条探测链原子化，同一时刻只允许一次电量请求在途。 */
    private val batteryMutex = Mutex()
    private val responseWaiters = mutableListOf<ResponseWaiter>()
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var running = false
    /** 最近一次收到任何字节的时间戳（链路活性判据）。 */
    @Volatile private var lastRxAt: Long = 0L
    /** 本链路 connect 成功时刻（毫秒）；0 = 尚未连过。用于「连接宽限期」判定。 */
    @Volatile private var connectedAtMs: Long = 0L
    /** 连续「已发出但无任何 RX」的命令计数；收到任意 RX 即清零。 */
    @Volatile private var consecutiveSilentTimeouts: Int = 0
    private val framer = XiberiaFrameCodec.StreamFramer()

    val isConnected: Boolean
        get() = socket?.isConnected == true && running

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            log("Connecting ${device.name ?: device.address} via SPP")
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            // [修复·SPP 连接卡死] 阻塞 connect 必须放进独立子协程执行，超时后**从外部 close socket**：
            //   Android 的 BluetoothSocket.connect() 阻塞且不响应协程取消 —— 若在同一协程里
            //   withTimeoutOrNull { s.connect() }，超时后协程仍卡在 connect 内，要等它自己返回
            //   才会执行 close，时限形同虚设，SessionManager 的地址锁会被长期占住。
            //   现改为：登记 socket → async 子协程内阻塞 connect → 外层 withTimeoutOrNull 计时；
            //   超时则 close(登记 socket) 迫使阻塞调用抛 IOException，再 await 子协程自然结束（不占线程）。
            socket = s
            val connectJob = scope.async { runCatching { s.connect() }.isSuccess }
            val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectJob.await() } ?: false
            if (!ok) {
                log("Connect timed out/failed after ${CONNECT_TIMEOUT_MS}ms, closing socket to unblock")
                // 外部 close 让阻塞中的 connect 抛异常退出；await 等它真正结束，避免残留线程/句柄。
                runCatching { s.close() }
                runCatching { connectJob.await() }
                connectJob.cancel()
                closeSocketOnly()
                return@withContext false
            }
            inputStream = s.inputStream
            outputStream = s.outputStream
            running = true
            lastRxAt = System.currentTimeMillis()
            connectedAtMs = System.currentTimeMillis()
            consecutiveSilentTimeouts = 0
            framer.reset()
            readJob = scope.launch { readLoop() }
            log("RFCOMM connected")
            true
        } catch (t: Throwable) {
            log("Connect failed: ${t.message}")
            closeSocketOnly()
            false
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            if (!running && socket == null) return@withContext
            running = false
            readJob?.cancel()
            readJob = null
            closeSocketOnly()
            failWaiters()
            lastRxAt = 0L
            connectedAtMs = 0L
            consecutiveSilentTimeouts = 0
            log("Disconnected")
        }
    }

    /** 发送命令并等待耳机应答（返回应答帧 payload；超时返回 null） */
    suspend fun sendCommand(
        cmd: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Long = RESPONSE_TIMEOUT_MS,
    ): ByteArray? {
        val accept: (ByteArray) -> Boolean = { frame ->
            XiberiaFrameCodec.isResponse(frame) && (
                XiberiaFrameCodec.keyOf(frame) == cmd ||
                    // 电量：查询/应答码族互通（0xA01/0xA02/0xA11/0xA12）
                    (XiberiaCommands.BATTERY_QUERY_CHAIN.contains(cmd) &&
                        XiberiaCommands.BATTERY_QUERY_CHAIN.contains(XiberiaFrameCodec.keyOf(frame)))
                )
        }
        return sendCommandForResponse(cmd, payload, timeoutMs, accept)
    }

    /**
     * 电量查询探测链：**多轮重试**直到拿到 ≥6 字节的三态真值，全程持 [batteryMutex]。
     *
     * 每轮按 0xA11 → 0xA01 → 0xA12 → 0xA02 顺序发；某轮拿到 ≥6 字节 payload 立即返回。
     * 首连时耳机 SPP 服务刚起来、首轮大概率落空（回短帧/无应答），故：
     *   - 轮数 [MAX_ROUNDS] 覆盖「连接后耳机就绪」的时间窗；轮间退避 [ROUND_BACKOFF_MS]；
     *   - 只要链路还在且处于连接宽限期，探测继续（不因零 RX 误判半死）。
     * 全部轮次都不足 6 字节 → 返回 null（绝不外泄短帧污染 L/R/C 槽）。
     */
    suspend fun queryBatteryPayload(timeoutMs: Long = RESPONSE_TIMEOUT_MS): ByteArray? =
        batteryMutex.withLock {
            var best: ByteArray? = null
            for (round in 1..MAX_ROUNDS) {
                for (cmd in XiberiaCommands.BATTERY_QUERY_CHAIN) {
                    val pl = sendCommand(cmd, XiberiaCommands.Payload.query(), timeoutMs) ?: continue
                    log("BATTERY_PROBE round=$round cmd=0x${cmd.toString(16)} plen=${pl.size} payload=${pl.toHexString()}")
                    if (pl.size >= 6) {
                        log("BATTERY_PROBE round=$round accepted cmd=0x${cmd.toString(16)}")
                        return@withLock pl
                    }
                    if (best == null || pl.size > best.size) best = pl
                }
                // 本轮无 ≥6 字节应答：若链路已死（且不在宽限期）则不必再等；否则退避后重试下一轮。
                if (!isConnected) {
                    log("BATTERY_PROBE round=$round aborted: link down")
                    break
                }
                if (round < MAX_ROUNDS) {
                    log("BATTERY_PROBE round=$round exhausted best=${best?.size ?: 0}, retry after ${ROUND_BACKOFF_MS}ms")
                    kotlinx.coroutines.delay(ROUND_BACKOFF_MS)
                }
            }
            val accepted = best?.takeIf { it.size >= 6 }
            log("BATTERY_PROBE exhausted rounds=$MAX_ROUNDS best=${best?.size ?: 0} accepted=${accepted != null}")
            accepted
        }

    /** 发送命令但不等应答（fire-and-forget，耳机主动上报场景） */
    suspend fun sendCommandNoAck(cmd: Int, payload: ByteArray = ByteArray(0)): Boolean {
        if (!isConnected) {
            log("Cannot send: not connected")
            return false
        }
        return sendRaw(XiberiaFrameCodec.encode(cmd, payload))
    }

    private suspend fun sendCommandForResponse(
        cmd: Int,
        payload: ByteArray,
        timeoutMs: Long,
        accept: (ByteArray) -> Boolean,
    ): ByteArray? {
        val waiter = ResponseWaiter(accept, CompletableDeferred())
        synchronized(responseWaiters) { responseWaiters += waiter }
        return try {
            if (!sendRaw(XiberiaFrameCodec.encode(cmd, payload))) {
                log("Send failed for cmd=0x${cmd.toString(16)}")
                return null
            }
            val rxBefore = lastRxAt
            val frame = withTimeoutOrNull(timeoutMs) { waiter.deferred.await() }
            if (frame == null) {
                log("Response timeout for cmd=0x${cmd.toString(16)}")
                // [修复·SPP 半死链路] 只判 socket.isConnected 无法识别「已发出但坐等超时」的死链路
                //   （耳机蓝牙空转/移远/被官方 App 抢占）。连续多次「本命令期间链路零 RX」即判死：
                //   running=false + 关 socket → isConnected 变 false → 上层 SessionManager 下次自动重连。
                // [修复·首连查不到电量] 连接后 [LINK_STALE_GRACE_MS] 为「就绪宽限期」：
                //   此窗口内耳机 SPP 服务可能仍在启动，零 RX 属正常，**不计数、不判死**，
                //   否则首连探测链前几条命令刚落空就把 socket 关掉，永远查不到电量。
                val inGrace = connectedAtMs > 0L && (System.currentTimeMillis() - connectedAtMs) < LINK_STALE_GRACE_MS
                if (lastRxAt == rxBefore) {
                    if (inGrace) {
                        log("SILENT_TIMEOUT cmd=0x${cmd.toString(16)} (in connect grace, not counting)")
                    } else {
                        val n = ++consecutiveSilentTimeouts
                        log("SILENT_TIMEOUT cmd=0x${cmd.toString(16)} consecutive=$n")
                        if (n >= STALE_LINK_SILENT_TIMEOUTS) {
                            log("Link stale (no RX for $n consecutive cmds), closing for reconnect")
                            markLinkStale()
                        }
                    }
                }
                return null
            }
            XiberiaFrameCodec.payloadOf(frame)
        } finally {
            synchronized(responseWaiters) { responseWaiters -= waiter }
        }
    }

    /** 判定链路为半死并主动关闭 socket（不置 closed；下次 sendCommand/refresh 会走重连）。 */
    private fun markLinkStale() {
        running = false
        closeSocketOnly()
        failWaiters()
        consecutiveSilentTimeouts = 0
    }

    private suspend fun sendRaw(packet: ByteArray): Boolean {
        val out = outputStream ?: return false
        return try {
            writeMutex.withLock {
                out.write(packet)
                out.flush()
            }
            log("TX ${packet.toHexString()}")
            true
        } catch (t: Throwable) {
            log("Write failed: ${t.message}")
            running = false
            closeSocketOnly()
            failWaiters()
            false
        }
    }

    private suspend fun readLoop() {
        val input = inputStream ?: return
        val buffer = ByteArray(512)
        try {
            while (running && scope.isActive) {
                val count = input.read(buffer)
                if (count <= 0) break
                // 收到任意字节即刷新链路活性并清零「静默超时」计数（半死检测的另一半）。
                lastRxAt = System.currentTimeMillis()
                consecutiveSilentTimeouts = 0
                val frames = framer.feed(buffer, count)
                frames.forEach { processFrame(it) }
            }
        } catch (t: Throwable) {
            if (running) log("Read loop failed: ${t.message}")
        } finally {
            if (running) {
                running = false
                closeSocketOnly()
                failWaiters()
                log("Read loop ended")
            }
        }
    }

    private suspend fun processFrame(frame: ByteArray) {
        val key = XiberiaFrameCodec.keyOf(frame)
        val pl = XiberiaFrameCodec.payloadOf(frame)
        // 诊断：整帧长度 + 声明 payload 长度 + payload 内容（用于确定电量帧真实布局）
        log(
            "RX full len=${frame.size} declLen=${frame[3].toInt() and 0xFF} cmd=0x${key.toString(16)} " +
                "plen=${pl.size} payload=${pl.toHexString()}"
        )
        log("RX_RAW ${frame.toHexString()}")
        val waiter = synchronized(responseWaiters) {
            val idx = responseWaiters.indexOfFirst { it.accept(frame) }
            if (idx >= 0) responseWaiters.removeAt(idx) else null
        }
        waiter?.deferred?.complete(frame)
        // key 未被 waiter 认领时，可在此加主动上报分发(电量推送等)
        if (waiter == null) {
            log("Unmatched RX key=0x${key.toString(16)}")
        }
    }

    private fun closeSocketOnly() {
        try { inputStream?.close() } catch (_: Throwable) {}
        try { outputStream?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        inputStream = null
        outputStream = null
        socket = null
        framer.reset()
    }

    private fun failWaiters() {
        synchronized(responseWaiters) {
            responseWaiters.forEach { it.deferred.cancel() }
            responseWaiters.clear()
        }
    }

    private fun log(msg: String) {
        onLog(msg)
        // 诊断：RFCOMM 细节（连接失败原因等）同时进 logcat，便于现场定位（onLog 默认为空）。
        modLog("I", msg)
    }

    private data class ResponseWaiter(
        val accept: (ByteArray) -> Boolean,
        val deferred: CompletableDeferred<ByteArray>,
    )
}

private fun ByteArray.toHexString(): String = joinToString(" ") { "%02x".format(it) }