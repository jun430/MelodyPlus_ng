package com.melody.melodyplus.ui.pages

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.melodyplus.bridge.DeviceNameRuleState
import com.melody.melodyplus.bridge.DeviceProfile
import com.melody.melodyplus.bridge.DeviceProfiles
import com.melody.melodyplus.bridge.DeviceRegistryStore
import com.melody.melodyplus.bridge.hasBluetoothConnectPermission
import com.melody.melodyplus.ui.components.GlassCard
import com.melody.melodyplus.ui.config.UiSettings
import java.util.Locale
import java.util.UUID
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备页：蓝牙权限、已配对设备、名称匹配规则。
 */
@Composable
fun DevicesPage(settings: UiSettings) {
    val context = LocalContext.current
    val cardOpacity = settings.cardOpacity
    var hasPermission by remember { mutableStateOf(context.hasBluetoothConnectPermission()) }
    var devices by remember { mutableStateOf<List<BondedDeviceInfo>>(emptyList()) }
    var rules by remember { mutableStateOf(DeviceRegistryStore.readNameRules(context)) }
    var exceptionName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        hasPermission = context.hasBluetoothConnectPermission()
        devices = if (hasPermission) loadBondedDevices(context) else emptyList()
        rules = DeviceRegistryStore.readNameRules(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        refresh()
        message = if (granted) "蓝牙权限已授权" else "蓝牙权限被拒绝"
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            NameRulesCard(
                cardOpacity = cardOpacity,
                rules = rules,
                input = exceptionName,
                onInputChange = { exceptionName = it },
                onAdd = {
                    val normalized = DeviceNameRuleState.normalizeDeviceName(exceptionName)
                    if (normalized.isBlank()) {
                        message = "请输入蓝牙名称"
                    } else if (DeviceNameRuleState.isAirPodsName(normalized)) {
                        message = "AirPods 名称不支持作为例外"
                    } else if (normalized in rules.defaultNames || normalized in rules.exceptionNames) {
                        message = "名称已存在"
                    } else {
                        DeviceRegistryStore.addExceptionName(context, exceptionName)
                        exceptionName = ""
                        refresh()
                        message = "已添加名称例外"
                    }
                },
                onRemove = { name ->
                    DeviceRegistryStore.removeExceptionName(context, name)
                    refresh()
                    message = "已移除名称例外"
                },
            )
        }

        item {
            StorageInfoCard(cardOpacity = cardOpacity)
        }

        item {
            DeviceScanHeader(
                hasPermission = hasPermission,
                onGrant = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                onRefresh = {
                    refresh()
                    message = "已刷新已配对设备"
                },
            )
        }

        if (hasPermission && devices.isNotEmpty()) {
            items(devices, key = { it.address }) { device ->
                val matchedProfile = DeviceProfiles.findByName(device.name)
                    ?: DeviceProfiles.get(rules.profileId).takeIf { rules.matches(device.name) }
                BondedDeviceCard(
                    cardOpacity = cardOpacity,
                    device = device,
                    profile = matchedProfile,
                )
            }
        } else {
            item {
                EmptyDeviceCard(cardOpacity = cardOpacity, hasPermission = hasPermission)
            }
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
private fun NameRulesCard(
    cardOpacity: Float,
    rules: DeviceNameRuleState,
    input: String,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    GlassCard(opacity = cardOpacity) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "蓝牙名称匹配",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "默认匹配内置 Sony 设备；改名后的设备可以添加例外名称。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = "例外蓝牙名称",
                singleLine = true,
            )
            TextButton(
                text = "添加名称",
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
            )

            SmallTitle(text = "当前规则")
            rules.defaultNames.forEach { name ->
                NameRuleRow(name = name, subtitle = "内置", removable = false, onRemove = onRemove)
            }
            rules.exceptionNames.forEach { name ->
                NameRuleRow(name = name, subtitle = "用户例外", removable = true, onRemove = onRemove)
            }
        }
    }
}

@Composable
private fun StorageInfoCard(cardOpacity: Float) {
    GlassCard(
        opacity = cardOpacity,
        contentPadding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "绑定存储",
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            Text(
                text = "MAC→型号 绑定保存在模块私有目录，无需存储权限；旧版公共目录的绑定会在读取时自动迁移。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun NameRuleRow(
    name: String,
    subtitle: String,
    removable: Boolean,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MiuixTheme.textStyles.body1)
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (removable) {
            TextButton(text = "移除", onClick = { onRemove(name) })
        }
    }
}

@Composable
private fun DeviceScanHeader(
    hasPermission: Boolean,
    onGrant: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已配对设备",
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.SemiBold,
        )
        if (!hasPermission) {
            TextButton(text = "授权蓝牙", onClick = onGrant)
        }
        TextButton(text = "刷新", onClick = onRefresh)
    }
}

@Composable
private fun BondedDeviceCard(
    cardOpacity: Float,
    device: BondedDeviceInfo,
    profile: DeviceProfile?,
) {
    val matched = profile != null
    GlassCard(
        opacity = cardOpacity,
        contentPadding = 14.dp,
        fillColor = if (matched) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (matched) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceContainer,
                )
                Text(
                    text = device.address,
                    style = MiuixTheme.textStyles.footnote1,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = profile?.modelId ?: "未匹配",
                style = MiuixTheme.textStyles.footnote1,
                color = if (matched) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun EmptyDeviceCard(cardOpacity: Float, hasPermission: Boolean) {
    GlassCard(opacity = cardOpacity) {
        Text(
            text = if (hasPermission) "未读取到已配对蓝牙设备" else "需要蓝牙权限才能读取已配对设备",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private data class BondedDeviceInfo(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val uuids: Set<UUID>,
)

@SuppressLint("MissingPermission")
private fun loadBondedDevices(context: Context): List<BondedDeviceInfo> {
    if (!context.hasBluetoothConnectPermission()) return emptyList()
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    val rules = DeviceRegistryStore.readNameRules(context)
    return adapter.bondedDevices
        .orEmpty()
        .map { device ->
            BondedDeviceInfo(
                device = device,
                name = device.name ?: "(unnamed)",
                address = DeviceRegistryStore.normalizeAddress(device.address),
                uuids = device.uuids?.map { it.uuid }?.toSet().orEmpty(),
            )
        }
        .sortedWith(
            compareByDescending<BondedDeviceInfo> { rules.matches(it.name) }
                .thenBy { it.name.lowercase(Locale.US) },
        )
}