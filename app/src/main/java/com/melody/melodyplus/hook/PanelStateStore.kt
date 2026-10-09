package com.melody.melodyplus.hook

import android.content.Context
import android.content.SharedPreferences

/**
 * 动态功能面板状态的**跨会话持久化**（用户诉求：开启过的功能，下次进面板仍显示开启）。
 *
 * 背景：[MelodyPanelHook.xiberiaStateCache] / `xiberiaChoiceCache` 仅为**进程内内存缓存**，
 * melody 详情页重建或进程回收后即丢失 → 回读完成前（或无应答/去重跳过时）面板
 * 回退到「未勾选」→ 表现为「我明明开过，进来却是关的」。
 *
 * 本类把「已确认生效」的状态落盘（宿主进程 SharedPreferences），进面板时先回填，
 * 回读结果到达后再覆盖 —— 首帧即显示上次状态，仍以设备回读为准校正。
 */
internal object PanelStateStore {

    private const val PREFS = "melodyplus_panel_state"
    private const val SW_PREFIX = "sw|"
    private const val CH_PREFIX = "ch|"

    private fun prefs(context: Context): SharedPreferences? =
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull()

    /** 作用域键：MAC + productId（换设备/换型号不串状态）。 */
    private fun scope(mac: String?, productId: Int): String =
        "${mac?.uppercase() ?: "?"}|0x${productId.toString(16)}"

    private fun switchKey(mac: String?, productId: Int, key: String): String =
        "$SW_PREFIX${scope(mac, productId)}|$key"

    private fun choiceKey(mac: String?, productId: Int, key: String): String =
        "$CH_PREFIX${scope(mac, productId)}|$key"

    /** 落盘一个布尔项（写入/回读确认态）。 */
    fun saveSwitch(context: Context?, mac: String?, productId: Int, key: String, value: Boolean) {
        val ctx = context ?: return
        if (productId == 0) return
        runCatching { prefs(ctx)?.edit()?.putBoolean(switchKey(mac, productId, key), value)?.apply() }
    }

    /** 落盘一个档位项。 */
    fun saveChoice(context: Context?, mac: String?, productId: Int, key: String, value: Int) {
        val ctx = context ?: return
        if (productId == 0) return
        runCatching { prefs(ctx)?.edit()?.putInt(choiceKey(mac, productId, key), value)?.apply() }
    }

    /**
     * 批量读回该 (MAC, productId) 下的全部已落盘状态。
     * 返回 switch:key→Boolean、choice:key→Int。
     */
    fun load(context: Context?, mac: String?, productId: Int): Pair<Map<String, Boolean>, Map<String, Int>> {
        val ctx = context ?: return emptyMap<String, Boolean>() to emptyMap()
        if (productId == 0) return emptyMap<String, Boolean>() to emptyMap()
        val p = prefs(ctx) ?: return emptyMap<String, Boolean>() to emptyMap()
        val swPrefix = "$SW_PREFIX${scope(mac, productId)}|"
        val chPrefix = "$CH_PREFIX${scope(mac, productId)}|"
        val switches = LinkedHashMap<String, Boolean>()
        val choices = LinkedHashMap<String, Int>()
        runCatching {
            p.all.forEach { (k, v) ->
                when {
                    k.startsWith(swPrefix) -> (v as? Boolean)?.let { switches[k.removePrefix(swPrefix)] = it }
                    k.startsWith(chPrefix) -> (v as? Int)?.let { choices[k.removePrefix(chPrefix)] = it }
                }
            }
        }
        return switches to choices
    }
}
