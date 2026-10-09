package com.melody.melodyplus.bridge

import android.content.Context
import android.os.Bundle
import org.json.JSONArray

/**
 * 模块主进程 UI 侧日志客户端：经 ContentProvider 读取/清空 Hook 日志。
 * Hook 子进程写入、UI 侧读取，跨进程解耦。
 */
object HookLogClient {
    fun read(context: Context): List<HookLogEntry> {
        if (!RegistryContract.isProviderAvailable(context)) return emptyList()
        return runCatching {
            val bundle = context.contentResolver.call(
                RegistryContract.URI,
                RegistryContract.METHOD_GET_LOGS,
                null,
                null,
            ) ?: return emptyList()
            val raw = bundle.getString(RegistryContract.EXTRA_LOGS_JSON).orEmpty()
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { HookLogEntry.fromJson(it) }?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        if (!RegistryContract.isProviderAvailable(context)) return
        runCatching {
            context.contentResolver.call(
                RegistryContract.URI,
                RegistryContract.METHOD_CLEAR_LOGS,
                null,
                null,
            )
        }
    }

    /**
     * 一键导出全部模块日志：Provider 侧汇总写入**模块私有目录** files/logs/，返回文件路径。
     * 失败时返回 null（调用方可退回展示本地 dump）。
     */
    fun export(context: Context): String? {
        if (!RegistryContract.isProviderAvailable(context)) return null
        return runCatching {
            val bundle = context.contentResolver.call(
                RegistryContract.URI,
                RegistryContract.METHOD_EXPORT_LOGS,
                null,
                null,
            ) ?: return null
            bundle.getString(RegistryContract.EXTRA_EXPORT_PATH)
        }.getOrNull()
    }
}