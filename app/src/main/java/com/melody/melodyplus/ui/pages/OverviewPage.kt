package com.melody.melodyplus.ui.pages

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melody.melodyplus.bridge.DeviceNameRuleState
import com.melody.melodyplus.bridge.DeviceImageStore
import com.melody.melodyplus.bridge.DeviceProfiles
import com.melody.melodyplus.bridge.DeviceRegistryStore
import com.melody.melodyplus.bridge.BluetoothPopupContract
import com.melody.melodyplus.bridge.HookStatusStore
import com.melody.melodyplus.bridge.RegistryContract
import com.melody.melodyplus.ui.components.GlassCard
import com.melody.melodyplus.ui.config.UiSettings
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 概览页：模块运行状态、Hook 状态、当前匹配设备、作用域入口。
 */
@Composable
fun OverviewPage(settings: UiSettings) {
    val context = LocalContext.current
    val cardOpacity = settings.cardOpacity
    val profiles = DeviceProfiles.all
    var rules by remember { mutableStateOf(DeviceRegistryStore.readNameRules(context)) }
    var statuses by remember { mutableStateOf(readHookStatuses(context)) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        rules = DeviceRegistryStore.readNameRules(context)
        statuses = readHookStatuses(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCard(
                cardOpacity = cardOpacity,
                profiles = profiles,
                rules = rules,
                statuses = statuses,
                onRefresh = {
                    refresh()
                    message = "状态已刷新"
                },
                onRestartScope = {
                    message = openScopeManager(context)
                },
            )
        }
        item {
            DebugCard(
                cardOpacity = cardOpacity,
                onResult = { message = it },
            )
        }
        item {
            DeviceImagesCard(
                cardOpacity = cardOpacity,
                profiles = profiles,
                onResult = { message = it },
            )
        }
        if (message.isNotBlank()) {
            item {
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    cardOpacity: Float,
    profiles: List<com.melody.melodyplus.bridge.DeviceProfile>,
    rules: DeviceNameRuleState,
    statuses: List<UiHookStatus>,
    onRefresh: () -> Unit,
    onRestartScope: () -> Unit,
) {
    GlassCard(opacity = cardOpacity) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "运行状态",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "按蓝牙名称匹配模块设备",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                TextButton(text = "刷新", onClick = onRefresh)
            }

            InfoRow("启用配置", "${profiles.size} 款设备")
            InfoRow("匹配名称", "内置 ${rules.defaultNames.size} / 例外 ${rules.exceptionNames.size}")
            InfoRow("控制协议", "Sony RFCOMM / XIBERIA cchip")
            InfoRow(
                "电量",
                "左右耳 ${profiles.count { it.capabilities.supportsLeftRightBattery }} 款 / 充电盒 ${profiles.count { it.capabilities.supportsCaseBattery }} 款",
            )

            statuses.forEach { status ->
                InfoRow(status.title, status.summary)
            }

            TextButton(
                text = "重启作用域",
                onClick = onRestartScope,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DebugCard(cardOpacity: Float, onResult: (String) -> Unit) {
    val uiContext = LocalContext.current
    GlassCard(opacity = cardOpacity) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "调试工具",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "注入一台虚拟【官方耳机】（OPPO Enco X3），触发 OPPO 原生连接弹窗/面板链路，\n用于验证「注入官方数据看会不会弹窗」。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = "注入官方耳机弹窗",
                onClick = {
                    val result = injectDebugPopup(uiContext)
                    onResult(result)
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
/**
 * 型号图片卡片：为每一款模块设备（profile）替换/清除弹窗与详情页显示的耳机图片。
 *
 * 图片落盘于 Download/MelodyPlus/images/<profileId>.png（MediaStore，免存储权限），
 * hook 侧（melody 进程）经 Provider 中转读取，无需宿主获得任何权限。
 */
@Composable
private fun DeviceImagesCard(
    cardOpacity: Float,
    profiles: List<com.melody.melodyplus.bridge.DeviceProfile>,
    onResult: (String) -> Unit,
) {
    val uiContext = LocalContext.current
    var customIds by remember { mutableStateOf(DeviceImageStore.listProfileIds(uiContext)) }
    // 当前正在挑选图片的 (profileId, slot)（非空时由 launcher 回填保存）
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = pending
        pending = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val (targetProfile, targetSlot) = target
        val ok = DeviceImageStore.save(uiContext, targetProfile, targetSlot, uri)
        customIds = DeviceImageStore.listProfileIds(uiContext)
        onResult(if (ok) "已替换 $targetProfile 的 $targetSlot 图" else "保存失败：$targetProfile/$targetSlot")
    }

    GlassCard(opacity = cardOpacity) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "耳机图片资源",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "每个型号 1 张图（三合一主图：左耳 + 右耳 + 耳机仓已合成一张）。\n" +
                    "图片保存在${DeviceImageStore.STORAGE_HINT}（无需存储权限）。\n" +
                    "未提供自定义图时自动回退模块内置图片。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            val slotLabels = listOf(
                com.melody.melodyplus.bridge.DeviceImageAssets.SLOT_MAIN to "主图",
            )
            profiles.forEach { profile ->
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = profile.displayName, style = MiuixTheme.textStyles.body1)
                    Text(
                        text = if (profile.id in customIds) "已有自定义图片 · ${profile.id}" else "内置图片 · ${profile.id}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (profile.id in customIds) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        slotLabels.forEach { (slot, label) ->
                            TextButton(
                                text = label,
                                onClick = {
                                    pending = profile.id to slot
                                    picker.launch(arrayOf("image/*"))
                                },
                            )
                        }
                        if (profile.id in customIds) {
                            TextButton(
                                text = "清除",
                                onClick = {
                                    DeviceImageStore.delete(uiContext, profile.id, null)
                                    customIds = DeviceImageStore.listProfileIds(uiContext)
                                    onResult("已恢复 ${profile.id} 的内置图片")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun injectDebugPopup(context: Context): String {
    if (!RegistryContract.isProviderAvailable(context)) {
        return "模块未安装或 Provider 不可用"
    }
    return runCatching {
        val bundle = context.contentResolver.call(
            RegistryContract.URI,
            RegistryContract.METHOD_INJECT_DEBUG_POPUP,
            null,
            Bundle().apply {
                // 不传地址/名称：由 Provider 侧按当前【已绑定设备的真实 MAC】补齐，
                // 避免注入假的 02:00.. 地址导致 melody 注册表项与真实设备对不上。
                // 仅当系统里一台绑定设备都没有时，Provider 才退回占位地址做纯 UI 预览。
            },
        )
        val ok = bundle?.getBoolean(RegistryContract.EXTRA_DEBUG_RESULT, false) == true
        if (ok) "已注入虚拟连接弹窗（优先使用已绑定设备真实 MAC），注意看屏幕" else "注入广播未成功发出，请检查作用域"
    }.getOrElse {
        "注入失败: ${it.message}"
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.36f),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.64f),
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

internal data class UiHookStatus(
    val title: String,
    val summary: String,
)

internal fun readHookStatuses(context: Context): List<UiHookStatus> {
    val localStatuses = HookStatusStore.readAll(context)
    val providerStatuses = runCatching {
        val bundle = context.contentResolver.call(
            RegistryContract.URI,
            RegistryContract.METHOD_GET_STATUS,
            null,
            null,
        ) ?: return@runCatching localStatuses
        val packages = bundle.getStringArrayList(RegistryContract.EXTRA_STATUS_PACKAGES).orEmpty()
        val hooks = bundle.getStringArrayList(RegistryContract.EXTRA_STATUS_HOOKS).orEmpty()
        val times = bundle.getStringArrayList(RegistryContract.EXTRA_STATUS_TIMESTAMPS).orEmpty()
        packages.mapIndexed { index, packageName ->
            com.melody.melodyplus.bridge.HostHookStatus(
                packageName = packageName,
                hookName = hooks.getOrNull(index).orEmpty(),
                updatedAtMillis = times.getOrNull(index)?.toLongOrNull() ?: 0L,
            )
        }
    }.getOrDefault(localStatuses)

    return providerStatuses.map { status ->
        UiHookStatus(
            title = scopeLabel(status.packageName),
            summary = if (status.updatedAtMillis > 0L) {
                "${status.hookName.ifBlank { "已加载" }} / ${formatTime(status.updatedAtMillis)}"
            } else {
                "未上报，打开对应作用域后刷新"
            },
        )
    }
}

private fun openScopeManager(context: Context): String {
    val candidates = listOf(
        Intent().setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity"),
        context.packageManager.getLaunchIntentForPackage("org.lsposed.manager"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        },
    ).filterNotNull()

    for (intent in candidates) {
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "已打开管理器，请在 LSPosed 中重启作用域"
        }.recoverCatching { throwable ->
            if (throwable !is ActivityNotFoundException) throw throwable
        }
    }
    return "无法打开 LSPosed，请手动重启作用域"
}

private fun scopeLabel(packageName: String): String =
    when (packageName) {
        "com.oplus.melody" -> "无线耳机作用域"
        "com.oplus.wirelesssettings" -> "无线设置作用域"
        "com.android.bluetooth" -> "蓝牙作用域"
        else -> packageName
    }

private fun formatTime(timestamp: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))