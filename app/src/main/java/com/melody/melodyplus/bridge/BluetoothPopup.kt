package com.melody.melodyplus.bridge

import java.util.Locale

object BluetoothPopupContract {
    const val ACTION_MODULE_AUDIO_CONNECTED = "com.melody.melodyplus.action.MODULE_AUDIO_CONNECTED"
    /** 跨进程中继：发现弹窗必须由 melody 主进程（持有 g6.j server 实例）执行 p()。 */
    const val ACTION_POPUP_EXECUTE = "com.melody.melodyplus.action.POPUP_EXECUTE"
    /** [SPP 单所有者] 主进程电量落盘后广播，:fg 据此刷新弹窗原生槽（:fg 不建链）。 */
    const val ACTION_BATTERY_UPDATED = "com.melody.melodyplus.action.BATTERY_UPDATED"
    /**
     * [真值矫正] :fg 侧 DiscoveryDialogActivity 生命周期广播：跨进程同步「弹窗真实存活」状态。
     * 用于矫正主进程 g6.j.i() 可能残留的「正在显示」闩锁（:fg 进程被 force-stop/重启后
     * 回收信号丢失 → i() 恒 true → p() 永久不弹）。
     */
    const val ACTION_POPUP_SHOWING = "com.melody.melodyplus.action.POPUP_SHOWING"
    /** 载荷：弹窗是否真实存活（Boolean）。true=onResume 可见 / false=onDestroy 销毁/新连接复位。 */
    const val EXTRA_POPUP_SHOWING = "com.melody.melodyplus.extra.POPUP_SHOWING"
    /** 中继载荷：伪装 productId 的十六进制字符串（主进程据此投递 p()）。 */
    const val EXTRA_POPUP_PRODUCT_ID = "com.melody.melodyplus.extra.POPUP_PRODUCT_ID"
    const val EXTRA_MODULE_TRIGGER = "com.melody.melodyplus.extra.MODULE_TRIGGER"
    const val EXTRA_DEVICE_ADDRESS = "com.melody.melodyplus.extra.DEVICE_ADDRESS"
    const val EXTRA_DEVICE_NAME = "com.melody.melodyplus.extra.DEVICE_NAME"
    const val EXTRA_PROFILE_ID = "com.melody.melodyplus.extra.PROFILE_ID"
    const val EXTRA_DEBUG_PREVIEW = "com.melody.melodyplus.extra.DEBUG_PREVIEW"
    const val MELODY_PACKAGE = "com.oplus.melody"
    const val MELODY_BLUETOOTH_RECEIVER = "com.oplus.melody.app.bluetooth.BluetoothBroadcastReceiver"

    // 调试注入默认 = 官方耳机 OPPO Enco X3（模块统一伪装身份 0x067410）
    const val DEBUG_DEFAULT_NAME = "OPPO Enco X3"
    /** 调试注入：虚拟 MAC（本地管理地址段占位；优先用真实绑定 MAC，见注入实现）。 */
    const val DEBUG_DEFAULT_ADDRESS = "02:00:00:00:00:42"
    /** 调试注入默认 profileId（官方耳机 OPPO Enco X3，见 AdapterRegistry.OFFICIAL_PROFILE_ID）。 */
    const val DEBUG_DEFAULT_PROFILE_ID = AdapterRegistry.OFFICIAL_PROFILE_ID
}

class BluetoothAudioPopupGate(
    private val dedupeWindowMs: Long = DEFAULT_DEDUPE_WINDOW_MS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lastPopupByAddress = linkedMapOf<String, Long>()

    @Synchronized
    fun shouldTrigger(address: String?, name: String?, moduleSupported: Boolean): Boolean {
        if (!moduleSupported || DeviceNameRuleState.isAirPodsName(name)) return false
        val normalizedAddress = normalizeAddress(address) ?: return false
        val now = nowMillis()
        val last = lastPopupByAddress[normalizedAddress]
        if (last != null && now >= last && now - last < dedupeWindowMs) return false
        lastPopupByAddress[normalizedAddress] = now
        prune(now)
        return true
    }

    @Synchronized
    fun reset() {
        lastPopupByAddress.clear()
    }

    private fun prune(now: Long) {
        val iterator = lastPopupByAddress.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now >= entry.value && now - entry.value > dedupeWindowMs * 6) {
                iterator.remove()
            }
        }
    }

    private fun normalizeAddress(address: String?): String? =
        address
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_DEDUPE_WINDOW_MS = 10_000L
    }
}
