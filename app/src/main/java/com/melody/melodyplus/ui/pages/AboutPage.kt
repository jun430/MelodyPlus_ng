package com.melody.melodyplus.ui.pages

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.melody.melodyplus.bridge.DeviceProfiles
import com.melody.melodyplus.bridge.HookLogClient
import com.melody.melodyplus.bridge.HookLogEntry
import com.melody.melodyplus.bridge.HookStatusStore
import com.melody.melodyplus.ui.components.GlassCard
import com.melody.melodyplus.ui.config.UiSettings
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页：版本、运行环境、Hook 目标、协议 Adapter。
 */
@Composable
fun AboutPage(settings: UiSettings) {
    val context = LocalContext.current
    val cardOpacity = settings.cardOpacity
    val versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "?"
    val versionCode = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }
        .getOrNull() ?: 0L

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassCard(opacity = cardOpacity) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "欧加耳机增强",
                        style = MiuixTheme.textStyles.title3,
                    )
                    InfoLine("作者", "雨色")
                    InfoLine("版本", "$versionName ($versionCode)")
                    InfoLine("Android", Build.VERSION.RELEASE)
                    InfoLine("SDK", Build.VERSION.SDK_INT.toString())
                    InfoLine("厂商 / 型号", "${Build.MANUFACTURER} ${Build.MODEL}")
                }
            }
        }

        item {
            GlassCard(opacity = cardOpacity) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Hook 目标", style = MiuixTheme.textStyles.title3)
                    HookStatusStore.scopePackages.forEach { pkg ->
                        InfoLine("·", pkg)
                    }
                }
            }
        }

        item {
            GlassCard(opacity = cardOpacity) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("设备配置", style = MiuixTheme.textStyles.title3)
                    InfoLine("Sony", "${DeviceProfiles.all.count { it.adapter == "sony" }} 款")
                    InfoLine("XIBERIA", "${DeviceProfiles.all.count { it.adapter == "xiberia" }} 款")
                }
            }
        }

        item {
            HookLogsCard(cardOpacity = cardOpacity)
        }
    }
}

/**
 * Hook 运行日志：读取 Provider 侧存储的日志并展示，可刷新/清空/按级别筛选。
 */
@Composable
private fun HookLogsCard(cardOpacity: Float) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf<List<HookLogEntry>>(emptyList()) }
    var levelFilter by remember { mutableStateOf("全部") }
    var exportMsg by remember { mutableStateOf("") }


    fun refresh() {
        logs = HookLogClient.read(context)
    }

    // 进入页面时刷新一次；后续手动刷新或清空
    LaunchedEffect(Unit) { refresh() }

    GlassCard(opacity = cardOpacity) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Hook 日志",
                style = MiuixTheme.textStyles.title3,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LogFilterChip("全部", levelFilter == "全部") { levelFilter = "全部" }
                    LogFilterChip("信息", levelFilter == "I") { levelFilter = "I" }
                    LogFilterChip("警告", levelFilter == "W") { levelFilter = "W" }
                    LogFilterChip("错误", levelFilter == "E") { levelFilter = "E" }
                }
                TextButton(
                    text = "清空",
                    onClick = {
                        HookLogClient.clear(context)
                        logs = emptyList()
                    },
                )
            }

            val filtered = if (levelFilter == "全部") logs else logs.filter { it.level == levelFilter }
            if (filtered.isEmpty()) {
                Text(
                    text = "暂无日志",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                filtered.takeLast(60).forEach { log ->
                    LogRow(log)
                }
            }
            TextButton(
                text = "刷新",
                onClick = { refresh() },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = "导出全部日志",
                onClick = {
                    val path = HookLogClient.export(context)
                    exportMsg = if (path != null) {
                        "已导出：$path"
                    } else {
                        "导出失败：Provider 不可用或写入被拒"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (exportMsg.isNotBlank()) {
                Text(
                    text = exportMsg,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LogFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surface
    val fg = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = { onClick() })
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, color = fg, style = MiuixTheme.textStyles.footnote1)
    }
}

@Composable
private fun LogRow(log: HookLogEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .background(levelColor(log.level))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                text = when (log.level) {
                    "E" -> "E"
                    "W" -> "W"
                    else -> "I"
                },
                color = androidx.compose.ui.graphics.Color.White,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${log.source}  ${formatLogTime(log.timestamp)}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = log.message,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

private fun levelColor(level: String): androidx.compose.ui.graphics.Color = when (level) {
    "E" -> androidx.compose.ui.graphics.Color(0xFFE5484D)
    "W" -> androidx.compose.ui.graphics.Color(0xFFF5A524)
    else -> androidx.compose.ui.graphics.Color(0xFF3B82F6)
}

private fun formatLogTime(timestamp: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.32f),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.68f),
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}