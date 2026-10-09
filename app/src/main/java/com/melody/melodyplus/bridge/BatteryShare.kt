package com.melody.melodyplus.bridge

import android.content.Context
import com.melody.melodyplus.core.BatteryState
import com.melody.melodyplus.hook.modLog
import java.io.File
import java.util.Locale

/**
 * [SPP 单所有者] 跨进程电量共享。
 *
 * 背景：宿主 `com.oplus.melody` 主进程与 `:fg` 弹窗进程同属一个 UID，各自加载一份模块。
 * 两边都走 [HeadsetSessionManager.refresh] 建 SPP 链时，内核蓝牙栈同一耳机只认一条 RFCOMM 链，
 * **后连者会把先连者的 socket 踢死**（实测：主进程 RFCOMM connected 后 0.5s 被 :fg 抢链 →
 * `Read loop failed: bt socket closed`），导致首次连接某一路电量缺失、槽序错乱。
 *
 * 方案：**仅主进程建链查询，结果落盘到宿主 App 私有目录**（同 UID 两进程可直接互读写，
 * 无需 Provider / 存储权限），`:fg` 弹窗进程只读不建链。
 *
 * 文件格式（一行，`,` 分隔，空档位写空串）：
 *   `left,right,case,single,leftChg,rightChg,caseChg,updatedAtMillis`
 */
object BatteryShare {
    private const val TAG = "MelodyPlus"
    private const val DIR = "melodyplus_battery"

    /** 共享目录：宿主私有 filesDir 下（两进程同 UID 可互访）。 */
    private fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 某 MAC 的共享文件；地址归一化为大写，与 DeviceBindingStore 一致。 */
    private fun fileFor(context: Context, address: String): File {
        val key = DeviceRegistryStore.normalizeAddress(address).uppercase(Locale.US)
            .replace(':', '_')
        return File(dir(context), key)
    }

    /** 主进程写入一趟真实电量（供 :fg 读取）。失败静默，不影响主流程。 */
    fun write(context: Context, address: String, state: BatteryState) {
        runCatching {
            if (state.left == null && state.right == null &&
                state.caseBattery == null && state.single == null
            ) return@runCatching
            val d = dir(context)
            if (!d.exists()) d.mkdirs()
            val line = listOf(
                state.left?.toString() ?: "",
                state.right?.toString() ?: "",
                state.caseBattery?.toString() ?: "",
                state.single?.toString() ?: "",
                state.leftCharging?.toString() ?: "",
                state.rightCharging?.toString() ?: "",
                state.caseCharging?.toString() ?: "",
                System.currentTimeMillis().toString(),
            ).joinToString(",")
            val f = fileFor(context, address)
            f.writeText(line)
        }.onFailure { modLog("I", "BATTERY_SHARE write failed: $it") }
    }

    /** 读取主进程落盘的电量（仅 :fg 使用）；无数据 / 过期 / 解析失败返回 null。 */
    fun read(context: Context, address: String, maxAgeMs: Long = 10 * 60 * 1000L): BatteryState? = runCatching {
        val f = fileFor(context, address)
        if (!f.exists()) return@runCatching null
        val parts = f.readText().trim().split(",")
        if (parts.size < 8) return@runCatching null
        val updatedAt = parts[7].toLongOrNull() ?: return@runCatching null
        if (System.currentTimeMillis() - updatedAt > maxAgeMs) return@runCatching null
        fun i(idx: Int): Int? = parts[idx].takeIf { it.isNotBlank() }?.toIntOrNull()
        fun b(idx: Int): Boolean? = parts[idx].takeIf { it.isNotBlank() }?.toBooleanStrictOrNull()
        BatteryState(
            single = i(3), left = i(0), right = i(1), caseBattery = i(2),
            leftCharging = b(4), rightCharging = b(5), caseCharging = b(6),
        )
    }.getOrNull()
}
