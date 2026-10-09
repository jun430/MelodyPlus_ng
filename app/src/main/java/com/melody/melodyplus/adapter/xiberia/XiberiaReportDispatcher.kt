// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaReportDispatcher.kt
// 说明: 设备主动上报帧（unsolicited REPORT）的分发 —— 对齐官方 `Protocol.parseReceiveData`。
//
// 官方真源（com.cchip.desheng v1.9.26，npmcp 提取）：
//   `CommandId` 中的 *REPORT / GET_OR_REPORT 系命令码由**设备主动推送**，非查询应答：
//     0x0A11 BATTERY_INFO_GET / 0x0A12 BATTERY_INFO_REPORT → 电量
//     0x0C03 LOW_DELAY_REPORT                               → 游戏模式开关
//     0x0B03 NOISE_REPORT                                   → 降噪档位
//     0x0E25 DONGLE_STATE_GET_OR_REPORT                     → 接收器状态
//   官方在 SPP 读循环中按 cmd 分发到对应 handler，刷新 UI（用户物理按键/摘戴耳机时
//   设备自行推送，避免「SET 后本地状态与设备不同步」）。
//
// 本模块的 [XiberiaRfcommClient.processFrame] 在「无响应 waiter 认领」时调用本分发器，
// 把上报帧投递给已注册的监听器（FeatureBackend / 面板 hook）。
package com.melody.melodyplus.adapter.xiberia

import com.melody.melodyplus.hook.modLog

/**
 * 上报帧分发器。
 *
 * 线程安全：注册/注销与分发可能来自不同线程（SPP 读循环 vs UI 线程），
 * 内部用快照 + CAS 列表保护。所有回调都包 try-catch，单个监听器异常不影响其它监听器。
 */
object XiberiaReportDispatcher {

    /** 上报帧种类（对齐官方 *REPORT 命令码族）。 */
    enum class ReportKind(val codes: IntArray) {
        /** 电量上报：0x0A11 / 0x0A12。 */
        BATTERY(intArrayOf(0x0A11, 0x0A12)),

        /** 游戏模式（低延迟）上报：0x0C03。 */
        GAME_MODE(intArrayOf(0x0C03)),

        /** 降噪档位上报：0x0B03。 */
        NOISE(intArrayOf(0x0B03)),

        /** 接收器状态上报：0x0E25。 */
        DONGLE(intArrayOf(0x0E25));

        companion object {
            fun of(command: Int): ReportKind? = entries.firstOrNull { command in it.codes }
        }
    }

    /** 一次上报事件。 */
    data class Report(
        val mac: String,
        val kind: ReportKind,
        val command: Int,
        val payload: ByteArray,
        val receivedAtMillis: Long,
    ) {
        /** 尾字节（与 SET/GET 对称的单字节语义）；空 payload 返回 null。 */
        val tailByte: Int? get() = payload.lastOrNull()?.toInt()?.and(0xFF)

        /** 布尔语义：尾字节 == 0x01。 */
        val asBool: Boolean get() = tailByte == 0x01

        /** 档位语义：尾字节原值。 */
        val asLevel: Int? get() = tailByte

        // ByteArray 的 equals/hashCode 需显式实现（data class 默认用引用比较）。
        override fun equals(other: Any?): Boolean =
            this === other || (other is Report && mac == other.mac && kind == other.kind &&
                command == other.command && payload.contentEquals(other.payload))

        override fun hashCode(): Int =
            ((mac.hashCode() * 31 + kind.hashCode()) * 31 + command) * 31 + payload.contentHashCode()

        override fun toString(): String =
            "Report(mac=$mac, kind=$kind, cmd=0x${command.toString(16)}, " +
                "payload=${payload.joinToString("") { "%02X".format(it) }})"
    }

    fun interface Listener {
        fun onReport(report: Report)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    /** 注册上报监听（返回注销句柄）。 */
    fun register(listener: Listener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun unregister(listener: Listener) {
        listeners -= listener
    }

    /**
     * 分发一帧上报。
     *
     * @return true = 该帧被识别为上报并已分发；false = 非上报码（调用方维持原「Unmatched RX」日志）。
     */
    fun dispatch(mac: String, command: Int, payload: ByteArray): Boolean {
        val kind = ReportKind.of(command) ?: return false
        val report = Report(mac, kind, command, payload, System.currentTimeMillis())
        modLog("I", "XIBERIA_REPORT $report listeners=${listeners.size}")
        listeners.forEach { l ->
            runCatching { l.onReport(report) }
                .onFailure { modLog("W", "XIBERIA_REPORT listener failed: ${it.message}") }
        }
        return true
    }

    /** 测试/收尾用：清空所有监听器。 */
    fun clear() {
        listeners.clear()
    }
}