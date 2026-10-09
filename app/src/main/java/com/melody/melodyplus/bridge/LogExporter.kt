package com.melody.melodyplus.bridge

import com.melody.melodyplus.hook.modLog
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键导出：把「全部模块日志」汇总成一份纯文本，写入**模块私有目录**
 * `files/logs/MelodyPlus_logs_<时间戳>.txt`（不再是公共 `Download/`，含 MAC 等敏感信息不外泄），
 * 便于排查问题（蓝牙弹窗不弹 / 注入失败 / Hook 未生效等）。
 *
 * 说明：模块未申请 READ_LOGS，无法在 App 进程读取其它 uid 的 logcat，
 * 因此这里导出的是**模块自身记录**的结构化日志（HookLogStore）
 * + 各作用域 Hook 激活状态 + 当前设备/伪装配置快照。
 */
object LogExporter {
    private const val TAG = "MelodyPlus"
    private const val DIR_NAME = "Download"
    private const val PREFIX = "MelodyPlus_logs"

    /** 导出结果：文件路径（可能为 null，写入失败时）与完整文本。 */
    data class Result(val path: String?, val text: String)

    fun export(context: Context): Result {
        val text = buildDump(context)
        val path = writeFile(context, text)
        return Result(path, text)
    }

    /** 组装完整日志文本。 */
    fun buildDump(context: Context): String {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        sb.appendLine("===== MelodyPlus 模块日志导出 =====")
        sb.appendLine("导出时间 : ${fmt.format(Date(now))}")
        sb.appendLine("包名     : ${context.packageName}")
        sb.appendLine("版本     : ${versionName(context)} (${versionCode(context)})")
        sb.appendLine("设备     : ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        // 1) 当前绑定设备 / 伪装身份
        sb.appendLine("----- 设备绑定 / 伪装配置 -----")
        val bound = runCatching { DeviceRegistryStore.read(context) }.getOrNull()
        if (bound != null) {
            val productId = bound.profile?.let { runCatching { AdapterRegistry.spoofProductIdFor(it) }.getOrNull() }
            sb.appendLine("已绑定设备 : ${bound.profile?.displayName ?: bound.profileId}")
            sb.appendLine("真实 MAC   : ${bound.address}")
            sb.appendLine("adapter    : ${bound.profile?.adapter ?: "?"}")
            sb.appendLine("伪装 productId : ${productId?.toString() ?: "?"}" +
                productId?.let { " (0x" + String.format("%06X", it) + ")" }.orEmpty())
        } else {
            sb.appendLine("已绑定设备 : 无")
        }
        sb.appendLine()

        // 2) 各作用域 Hook 激活状态
        sb.appendLine("----- Hook 激活状态（作用域） -----")
        HookStatusStore.readAll(context).forEach { st ->
            val t = if (st.updatedAtMillis > 0) fmt.format(Date(st.updatedAtMillis)) else "-"
            sb.appendLine("${st.packageName.padEnd(34)} ${st.hookName.ifBlank { "未上报" }}  [$t]")
        }
        sb.appendLine()

        // 3) 全部日志（按时间排序）
        sb.appendLine("----- 模块日志（共 ${HookLogStore.readAll(context).size} 条，上限 ${HookLogStore.MAX_ENTRIES}） -----")
        val logs = HookLogStore.readAll(context).sortedBy { it.timestamp }
        if (logs.isEmpty()) {
            sb.appendLine("(无日志)")
        } else {
            val lf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            logs.forEach { e ->
                sb.appendLine("${lf.format(Date(e.timestamp))} [${e.level}] ${e.source}: ${e.message}")
            }
        }
        sb.appendLine()
        sb.appendLine("===== END =====")
        return sb.toString()
    }

    /**
     * 写入**模块私有目录** `files/logs/MelodyPlus_logs_<时间戳>.txt`，返回绝对路径。
     *
     * 为什么不用 `Download/`：Provider 侧导出的日志含真实 MAC、绑定配置等敏感信息，
     * 公共 Download 目录任何 App 可读。改为模块私有目录 `/data/data/<pkg>/files/logs/`
     * 后，其它 App 无访问权限，仅本模块「日志」页与导出入口可读。
     */
    private fun writeFile(context: Context, text: String): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${PREFIX}_${stamp}.txt"
        return runCatching {
            val dir = File(context.filesDir, "logs")
            dir.mkdirs()
            val f = File(dir, fileName)
            f.writeText(text)
            modLog("I", "日志已导出到 ${f.absolutePath}")
            f.absolutePath
        }.getOrNull()
    }

    private fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun versionCode(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrNull() ?: 0L
}
