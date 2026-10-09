package com.melody.melodyplus.hook
import com.melody.melodyplus.hook.modLogT

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.util.Log
import com.melody.melodyplus.bridge.BluetoothAudioPopupGate
import de.robv.android.xposed.XposedBridge
import com.melody.melodyplus.bridge.AdapterRegistry
import com.melody.melodyplus.bridge.BluetoothPopupContract
import com.melody.melodyplus.bridge.DeviceProfiles
import com.melody.melodyplus.bridge.DeviceRegistryStore
import com.melody.melodyplus.bridge.ModuleDeviceRegistry

object BluetoothAudioConnectionHook : HookContext() {
    private const val TAG = "MelodyPlus"

    /**
     * 哨兵探针总开关。默认关闭。
     * 开启时会在蓝牙进程 main 线程上高频打印 XposedBridge.log
     * （getName 每次调用 + StateMachine 每条消息），会造成日志风暴并
     * 拖慢状态机，放大 AdapterState 超时进入 OffState 的概率。
     * 仅在排查注入是否生效时临时置 true。
     */
    private const val SENTINELS_ENABLED = false

    private val popupGate = BluetoothAudioPopupGate()

    override fun onHook() {
        modLog("I", "load hooks for com.android.bluetooth")
        if (SENTINELS_ENABLED) hookSentinels()
        hookA2dpStateMachine()
        hookHfpStateMachine()
    }

    /**
     * 哨兵探针：验证 Xposed hook 引擎在蓝牙进程内是否真正生效。
     * 1) BluetoothDevice.getName —— 连接期间必被调用；
     * 2) StateMachine.sendMessage 高频路径。
     * 哨兵零回调 → 引擎/注入层问题；哨兵有回调而目标方法无 → ArtMethod 层被绕过。
     * ⚠️ 默认禁用（SENTINELS_ENABLED=false）：这两条都是高频路径，
     * 在蓝牙进程 main 线程上逐次 XposedBridge.log 会造成日志风暴。
     */
    private fun hookSentinels() = safeHook(TAG, "sentinel probes") {
        runCatching {
            hookAfter(BluetoothDevice::class.java.getDeclaredMethod("getName")) {
                modLog("I", "SENTINEL getName -> $result")
            }
            modLog("I", "SENTINEL getName hooked=true")
        }.onFailure { modLog("I", "SENTINEL getName hook FAILED: $it") }
        runCatching {
            val sm = findClass("com.android.internal.util.StateMachine")
            val sendMsg = sm.declaredMethods.firstOrNull {
                it.name == "sendMessage" && it.parameterTypes.size == 1
            }
            sendMsg?.let { m ->
                hookAfter(m) {
                    modLog("I", "SENTINEL SM.sendMessage what=${args.getOrNull(0)}")
                }
                modLog("I", "SENTINEL SM.sendMessage hooked=true")
            }
        }.onFailure { modLog("I", "SENTINEL SM hook FAILED: $it") }
    }

    private fun hookA2dpStateMachine() = safeHook(TAG, "A2DP connection state") {
        var hookedAny = false
        // 首选：nest 桥 public static 方法（跨类调用不会被 ART 内联，状态类实际调用点）
        runCatching {
            val smClass = findClass("com.android.bluetooth.a2dp.A2dpStateMachine")
            val bridge = smClass.declaredMethods.firstOrNull {
                it.name == "-\$\$Nest\$mbroadcastConnectionState"
            }
            modLog("I", "A2DP nest bridge found=${bridge != null}")
            bridge?.let { m ->
                hookedAny = true
                hookAfter(m) {
                    runCatching {
                        val sm = args.getOrNull(0)
                        val newState = args.getOrNull(1) as? Int
                        val prevState = args.getOrNull(2) as? Int
                        val device = sm?.let { getObjectField(it, "mDevice") as? BluetoothDevice }
                        modLog("I", "A2DP cb(bridge): $prevState -> $newState dev=${device?.name}")
                        handleConnectedEdge(sm, device, newState, prevState, "a2dp")
                    }.onFailure { modLogT("W", "A2DP bridge hook failed", it) }
                }
            }
        }
        // 兜底：原 private 方法
        val a2dpMethod = findMethodOrNull(
            "com.android.bluetooth.a2dp.A2dpStateMachine",
            "broadcastConnectionState",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
        modLog("I", "A2dpStateMachine.broadcastConnectionState(II) found=${a2dpMethod != null} hookedAny=$hookedAny")
        if (!hookedAny) {
            a2dpMethod?.let { method ->
            hookAfter(method) {
                runCatching {
                    val newState = args.getOrNull(0) as? Int
                    val prevState = args.getOrNull(1) as? Int
                    val device = getObjectField(instance, "mDevice") as? BluetoothDevice
                    modLog("I", "A2DP cb: $prevState -> $newState dev=${device?.name}")
                    handleConnectedEdge(instance, device, newState, prevState, "a2dp")
                }.onFailure { modLogT("W", "A2DP connected hook failed", it) }
            }
            }
        }
    }

    private fun hookHfpStateMachine() = safeHook(TAG, "HFP connection state") {
        val hfpMethod = findMethodOrNull(
            "com.android.bluetooth.hfp.HeadsetStateMachine\$HeadsetStateBase",
            "broadcastConnectionState",
            BluetoothDevice::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
        modLog("I", "HeadsetStateBase.broadcastConnectionState found=${hfpMethod != null}")
        hfpMethod?.let { method ->
            hookAfter(method) {
                runCatching {
                    val device = args.getOrNull(0) as? BluetoothDevice
                    val newState = args.getOrNull(1) as? Int
                    val prevState = args.getOrNull(2) as? Int
                    handleConnectedEdge(instance, device, newState, prevState, "hfp")
                }.onFailure { modLogT("W", "HFP connected hook failed", it) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedEdge(
        source: Any?,
        device: BluetoothDevice?,
        newState: Int?,
        prevState: Int?,
        transport: String,
    ) {
        if (newState != BluetoothProfile.STATE_CONNECTED || prevState == BluetoothProfile.STATE_CONNECTED) return
        val context = contextFromStateMachine(source)
        if (context == null) return
        reportHookStatus(context)
        val name = runCatching { device?.name }.getOrNull()
        val address = runCatching { device?.address }.getOrNull()
        modLog(
            context,
            "I",
            "BT_EDGE_CAPTURED transport=$transport state=$newState prev=$prevState name=$name address=$address",
        )
        val supported = runCatching {
            val macHit = address?.let { DeviceRegistryStore.profileIdForMac(context, it) } != null
            macHit || ModuleDeviceRegistry(context).isModuleSupportedName(name)
        }.onFailure {
            modLogT("W", "module name lookup failed for $transport", it)
        }.getOrDefault(false)
        val profileForEdge = address?.let { DeviceRegistryStore.profileIdForMac(context, it) }
            ?: ModuleDeviceRegistry(context).profileForName(name)?.id
        modLog(
            context,
            "I",
            "BT_SUPPORT_DECISION name=$name address=$address supported=$supported profile=${profileForEdge ?: "null"}",
        )
        if (!popupGate.shouldTrigger(address, name, supported)) {
            modLog(context, "I", "BT_GATE_DECISION allowed=false reason=dedupe name=$name address=$address")
            return
        }
        modLog(context, "I", "BT_GATE_DECISION allowed=true name=$name address=$address")

        sendMelodyPopupBroadcast(context, device, address, name, prevState ?: -1, transport)
    }

    private fun sendMelodyPopupBroadcast(
        context: Context,
        device: BluetoothDevice?,
        address: String?,
        name: String?,
        prevState: Int,
        transport: String,
    ) {
val profileForBroadcast = address?.let { DeviceRegistryStore.profileIdForMac(context, it) }
            ?: ModuleDeviceRegistry(context).profileForName(name)?.id
                ?.also { id -> runCatching { DeviceRegistryStore.saveMacProfile(context, address.orEmpty(), id) } }
            ?: DeviceProfiles.SONY_WF1000XM3
        val intent = Intent(BluetoothPopupContract.ACTION_MODULE_AUDIO_CONNECTED).apply {
            setClassName(
                BluetoothPopupContract.MELODY_PACKAGE,
                BluetoothPopupContract.MELODY_BLUETOOTH_RECEIVER,
            )
            putExtra(BluetoothPopupContract.EXTRA_MODULE_TRIGGER, true)
            putExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS, address)
            putExtra(BluetoothPopupContract.EXTRA_DEVICE_NAME, name)
            putExtra(
                BluetoothPopupContract.EXTRA_PROFILE_ID,
                profileForBroadcast,
            )
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
            putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState)
            putExtra("com.melody.melodyplus.extra.TRANSPORT", transport)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching {
            // 注意：禁止伪造 BluetoothDevice.ACTION_ACL_CONNECTED 系统受保护广播。
            // 该 action 会被同进程 OPPO 定制组件 OplusBtAudioRouteMonitor 消费，
            // 污染其 receiver 记账，导致 AdapterService.cleanup() 时
            // unregisterReceiver 抛 IllegalArgumentException 打崩蓝牙进程。
            // 弹窗链路只需定向到 melody 的自定义 action 即可。
            context.sendBroadcast(intent)
            modLog(
                context,
                "I",
                "BT_BROADCAST_SEND action=${BluetoothPopupContract.ACTION_MODULE_AUDIO_CONNECTED} " +
                    "component=${BluetoothPopupContract.MELODY_PACKAGE}/${BluetoothPopupContract.MELODY_BLUETOOTH_RECEIVER} " +
                    "address=$address profile=$profileForBroadcast " +
                    "hex=${DeviceProfiles.get(profileForBroadcast)?.let { AdapterRegistry.spoofIdHexFor(it) }} transport=$transport",
            )
            modLog("I", "forward module audio connection to Melody: $address / $name / $transport")
        }.onFailure {
            modLogT("W", "forward module audio connection failed", it)
        }
    }

    private fun contextFromStateMachine(source: Any?): Context? =
        findContext(source, 0) ?: currentApplication()

    private fun findContext(source: Any?, depth: Int): Context? {
        if (source == null || depth > 5) return null
        if (source is Context) return source.applicationContext ?: source
        return listOf(
            "mContext",
            "mService",
            "mA2dpService",
            "mHeadsetService",
            "mAdapterService",
            "this$0",
        ).firstNotNullOfOrNull { field ->
            runCatching { findContext(getObjectField(source, field), depth + 1) }.getOrNull()
        }
    }

    private fun currentApplication(): Application? =
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Application
        }.getOrNull()
}
