package com.melody.melodyplus.hook

import android.util.Log

/**
 * SettingsLogFilterHook —— com.android.settings 作用域专用
 *
 * 移植自 MelodyCodecTweaker 的 SettingsHookInstaller 思路：
 * 只 hook Log.e 做定向日志降噪，不触碰 Settings 任何 Activity/Fragment/Preference
 * 结构与生命周期 —— 这是「设置作用域不闪退」的关键：零结构侵入。
 *
 * 降噪目标：ColorOS 开发者选项中，蓝牙扩展编解码（LHDC vendor 值）无 summary 条目
 * 导致 Settings 刷的无害错误日志（污染我们自己的诊断日志）。
 */
class SettingsLogFilterHook : HookContext() {

    companion object {
        private const val TAG = "MelodyPlus"
        private const val TAG_BASE_BLUETOOTH_DLG_PREF = "BaseBluetoothDlgPref"
        private const val TAG_BT_EXT_CODEC_CTR = "BtExtCodecCtr"
        // OPlus LHDC/扩展 codec vendor 质量值
        private val KNOWN_VENDOR_VALUES = arrayOf(
            "31774", "31776", "31777",
            "32774", "32776", "32777",
        )
    }

    override fun onHook() {
        hookLogError(String::class.java, String::class.java)
        hookLogError(String::class.java, String::class.java, Throwable::class.java)
        modLog("I", "SettingsLogFilterHook installed (log-only, no structural hooks)")
    }

    private fun hookLogError(vararg parameterTypes: Class<*>) {
        runCatching {
            val method = Log::class.java.getMethod("e", *parameterTypes)
            hookBefore(method) {
                val tag = args.getOrNull(0) as? String
                val msg = args.getOrNull(1) as? String
                if (shouldSuppress(tag, msg)) result = 0
            }
        }.onFailure { modLog("W", "Settings Log.e hook failed: $it") }
    }

    private fun shouldSuppress(tag: String?, msg: String?): Boolean {
        if (tag == null || msg == null) return false
        if (tag == TAG_BASE_BLUETOOTH_DLG_PREF) {
            return msg.startsWith("Unable to get summary of ") &&
                msg.contains(". Size is ") &&
                KNOWN_VENDOR_VALUES.any { msg.contains(it) }
        }
        return tag == TAG_BT_EXT_CODEC_CTR &&
            msg.contains("setupListPreference: List preference is null")
    }
}
