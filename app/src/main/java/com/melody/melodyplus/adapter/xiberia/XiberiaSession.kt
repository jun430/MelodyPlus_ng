// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaSession.kt
// 来源: 参考模块 com.oai.huaweimelodycompat.HuaweiFeatureBackend 中 session 语义的对应实现。
//       负责：持有一条 XIBERIA RFCOMM 链路 + 串行化 request/response + 生命周期关闭。

package com.melody.melodyplus.adapter.xiberia

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.runBlocking

/**
 * 一次设备会话：包一层 [XiberiaRfcommClient]，对上层暴露阻塞式 request。
 *
 * 说明：参考模块的 HuaweiFeatureBackend 在 `:fg`/主进程之间用阻塞 Future 同步结果，
 * 这里后端（XiberiaFeatureBackend）跑在专用单线程 executor 上，故 session 暴露
 * runBlocking 的同步 API 更贴合参考语义。
 */
class XiberiaSession(
    private val device: BluetoothDevice,
    private val client: XiberiaRfcommClient = XiberiaRfcommClient(),
) {

    @Volatile private var closed = false

    val isConnected: Boolean get() = client.isConnected

    @SuppressLint("MissingPermission")
    fun connect(): Boolean = runBlocking {
        if (closed) return@runBlocking false
        client.connect(device)
    }

    /**
     * 发送命令并阻塞等待应答 payload（超时/未连接返回 null）。
     * 语义对应参考模块 `HuaweiFeatureBackend.writeFrame` + `queryXxx`。
     */
    fun request(command: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 1_500L): ByteArray? =
        runBlocking {
            if (closed || !client.isConnected) return@runBlocking null
            client.sendCommand(command, payload, timeoutMs)
        }

    /**
     * 电量探测链（阻塞）：按链序发，返回第一个 payload ≥ 6 字节的应答；全不足则 null。
     */
    fun queryBatteryPayload(timeoutMs: Long = 1_500L): ByteArray? =
        runBlocking {
            if (closed || !client.isConnected) return@runBlocking null
            client.queryBatteryPayload(timeoutMs)
        }

    fun close(reason: String) {
        if (closed) return
        closed = true
        runBlocking { runCatching { client.disconnect() } }
    }
}