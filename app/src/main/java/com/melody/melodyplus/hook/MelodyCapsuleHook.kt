package com.melody.melodyplus.hook
import com.melody.melodyplus.hook.modLogT

import android.os.Bundle
import android.util.Log
import com.melody.melodyplus.bridge.BluetoothPopupContract
import com.melody.melodyplus.bridge.DeviceProfiles
import com.melody.melodyplus.bridge.DeviceRegistryStore
import com.melody.melodyplus.core.BatteryState
import com.melody.melodyplus.scope.AutoResolver
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.security.MessageDigest

/**
 * MelodyCapsuleHook —— 系统「连接胶囊」（设备空间 Capsule）直构直发
 *
 * 目标：第三方耳机连接时，让 ColorOS 设备空间（com.heytap.mydevices）弹出
 *      原生胶囊（耳机图片 + 电量 + 耳机设置入口），即用户要的「小弹窗」。
 *
 * 原理（已逆向确认，2026-10-05）：
 *   melody 内 z8.j（MyDeviceInterfaceAgent）持有 Binder（com.heytap.deviceinfo.MyDevicesInterface），
 *   通过 k(cmd, bundle) 发令给设备空间：
 *     - 0x10007 CODE_SHOW_CAPSULE
 *     - 0x1000b CODE_UPDATE_CAPSULE
 *   bundle["capsule"] = Gson(CapsuleInfo)，CapsuleInfo 为 SDK 类：
 *     com.oplus.mydevices.sdk.linkage.CapsuleInfo
 *     (deviceId=MD5(address), mac=address, iconUri, title, subTitle,
 *      CapsuleType, videoUri, List<BatteryInfo>, showType)
 *
 * 本 hook 完全绕开 melody 的 11 道门槛链（白名单/ssoid/RSSI/间隔…）。
 *
 * 兼容性：z8/j 混淆名随版本变，优先走 AutoResolver 语义指纹；失败回退硬编码。
 */
object MelodyCapsuleHook : HookContext() {
    private const val TAG = "MelodyPlus"

    // melody 侧混淆名（v16.9.1 已验证）
    private const val AGENT_CLASS = "z8.j"              // MyDeviceInterfaceAgent
    private const val AGENT_SINGLETON_CLASS = "z8.j\$e" // 静态单例持有者
    private const val CAPSULE_INFO_CLASS = "com.oplus.mydevices.sdk.linkage.CapsuleInfo"
    private const val CAPSULE_TYPE_CLASS = "com.oplus.mydevices.sdk.linkage.CapsuleType"
    private const val BATTERY_INFO_CLASS = "com.oplus.mydevices.sdk.device.BatteryInfo"
    private const val BATTERY_TYPE_CLASS = "com.oplus.mydevices.sdk.device.BatteryType"

    private const val CMD_SHOW_CAPSULE = 0x10007
    private const val CMD_UPDATE_CAPSULE = 0x1000b

    @Volatile private var agentClass: Class<*>? = null
    @Volatile private var capsuleInfoCtor: java.lang.reflect.Constructor<*>? = null
    @Volatile private var capsuleTypeConnected: Any? = null
    @Volatile private var capsuleTypeConnect: Any? = null
    @Volatile private var batteryInfoCtor: java.lang.reflect.Constructor<*>? = null
    @Volatile private var batteryTypeLeft: Any? = null
    @Volatile private var batteryTypeRight: Any? = null
    @Volatile private var batteryTypeCase: Any? = null
    @Volatile private var batteryTypeSingle: Any? = null
    @Volatile private var gsonInstance: Any? = null
    @Volatile private var gsonToJson: Method? = null

    override fun onHook() {
        modLog("I", "load hooks for melody capsule (system device-space popup)")
        safeHook(TAG, "capsule classes init") { initCapsuleClasses() }
        safeHook(TAG, "capsule trigger on receiver") { hookCapsuleTrigger() }
    }

    private fun initCapsuleClasses() {
        agentClass = findClassOrNull(AGENT_CLASS)
            ?: AutoResolver.resolve("myDeviceAgent")
        if (agentClass == null) {
            modLog("I", "capsule: agent class not found (obfuscation changed?)")
            return
        }
        modLog("I", "capsule: agent class = ${agentClass!!.name}")

        val ciClass = findClassOrNull(CAPSULE_INFO_CLASS)
        val ctClass = findClassOrNull(CAPSULE_TYPE_CLASS)
        val biClass = findClassOrNull(BATTERY_INFO_CLASS)
        val btClass = findClassOrNull(BATTERY_TYPE_CLASS)
        if (ciClass == null || ctClass == null) {
            modLog("I", "capsule: SDK classes missing")
            return
        }
        capsuleInfoCtor = ciClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 9 }
            ?.apply { isAccessible = true }
        capsuleTypeConnected = runCatching { ctClass.getField("CONNECTED").get(null) }.getOrNull()
        capsuleTypeConnect = runCatching { ctClass.getField("CONNECT").get(null) }.getOrNull()
        if (biClass != null && btClass != null) {
            batteryInfoCtor = biClass.declaredConstructors
                .firstOrNull { it.parameterTypes.size == 3 }
                ?.apply { isAccessible = true }
            batteryTypeLeft = runCatching { btClass.getField("LEFT").get(null) }.getOrNull()
            batteryTypeRight = runCatching { btClass.getField("RIGHT").get(null) }.getOrNull()
            batteryTypeCase = runCatching { btClass.getField("BOX").get(null) }.getOrNull()
            batteryTypeSingle = runCatching { btClass.getField("SINGLE").get(null) }.getOrNull()
        }
        // Gson：melody 的 com.oplus.melody.common.util.x.i(Object) 就是 Gson.toJson
        runCatching {
            val gsonClass = findClass("com.google.gson.Gson")
            gsonInstance = gsonClass.getDeclaredConstructor().newInstance()
            gsonToJson = gsonClass.getMethod("toJson", Any::class.java)
        }.onFailure { modLogT("W", "capsule: gson init failed", it) }
        modLog("I", "capsule: classes ready ctor=${capsuleInfoCtor != null} gson=${gsonToJson != null}")
    }

    /** 在音频连接事件时（与模块发现弹窗同一触发链），向设备空间直发胶囊命令 */
    private fun hookCapsuleTrigger() = safeHook(TAG, "capsule on audio connected") {
        val receiverClass = "com.oplus.melody.app.bluetooth.BluetoothBroadcastReceiver"
        findMethodOrNull(receiverClass, "onReceive", android.content.Context::class.java, android.content.Intent::class.java)?.let { method ->
            hookAfter(method) {
                runCatching {
                    val ctx = args.getOrNull(0) as? android.content.Context ?: return@runCatching
                    val intent = args.getOrNull(1) as? android.content.Intent ?: return@runCatching
                    onAudioIntent(ctx, intent)
                }.onFailure { modLogT("W", "capsule trigger failed", it) }
            }
        }
        findMethodOrNull(receiverClass, "b", android.content.Context::class.java, android.content.Intent::class.java)?.let { method ->
            hookAfter(method) {
                runCatching {
                    val ctx = args.getOrNull(0) as? android.content.Context ?: return@runCatching
                    val intent = args.getOrNull(1) as? android.content.Intent ?: return@runCatching
                    onAudioIntent(ctx, intent)
                }.onFailure { modLogT("W", "capsule async trigger failed", it) }
            }
        }
    }

    private fun onAudioIntent(context: android.content.Context, intent: android.content.Intent) {
        val action = intent.action ?: return
        val isModuleIntent = action == BluetoothPopupContract.ACTION_MODULE_AUDIO_CONNECTED
        val isAudioEdge = action == android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED ||
            action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        if (!isModuleIntent && !isAudioEdge) return
        val address = intent.getStringExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS)
            ?: intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)?.address
            ?: return
        val name = intent.getStringExtra(BluetoothPopupContract.EXTRA_DEVICE_NAME)
            ?: intent.getStringExtra("device_name")
            ?: return
        val battery = com.melody.melodyplus.bridge.HeadsetSessionManager.cachedState(address)?.battery
        val profile = (address.let { DeviceRegistryStore.profileIdForMac(context, it) }
                ?.let { DeviceProfiles.get(it) }
                ?: com.melody.melodyplus.bridge.ModuleDeviceRegistry(context).profileForName(name))
                ?.also { p -> runCatching { DeviceRegistryStore.saveMacProfile(context, address, p.id) } }
        modLog("I", "capsule: audio edge detected $name/$address profile=${profile?.id}")
        pushCapsule(address, name, profile?.displayName ?: name, battery)
    }

    /**
     * 构造 CapsuleInfo 并发送给设备空间。
     * @param address   蓝牙 MAC（真实设备地址）
     * @param deviceName 原始设备名
     * @param title     胶囊标题（模块显示名）
     * @param battery   电量数据（可为 null → 不发电量列表）
     */
    fun pushCapsule(address: String, deviceName: String, title: String, battery: BatteryState?) {
        val agent = agentInstance() ?: return
        if (capsuleInfoCtor == null) initCapsuleClasses()
        val ctor = capsuleInfoCtor ?: return
        val ct = capsuleTypeConnected ?: return

        // 电量列表：LEFT/RIGHT/BOX/SINGLE → com.oplus.mydevices.sdk.device.BatteryInfo
        val batteryList: MutableList<Any> = ArrayList()
        val biCtor = batteryInfoCtor
        if (biCtor != null && battery != null) {
            fun add(type: Any?, value: Int?) {
                if (type == null || value == null || value !in 0..100) return
                runCatching { batteryList.add(biCtor.newInstance(type, value, false)) }
            }
            add(batteryTypeLeft, battery.left)
            add(batteryTypeRight, battery.right)
            add(batteryTypeCase, battery.caseBattery)
            add(batteryTypeSingle, battery.single)
        }

        // deviceId 传 address 的 MD5（z8/j.n() 内部用 fa.i.r 做 MD5，这里是 k() 直发路径，需自行做）
        val deviceId = md5(address) ?: address
        val info = runCatching {
            // CapsuleInfo(deviceId, mac, iconUri, title, subTitle, CapsuleType, videoUri, List, showType)
            ctor.newInstance(deviceId, address, null, title, "已连接", ct, null, batteryList, 0)
        }.onFailure { modLogT("W", "capsule: CapsuleInfo create failed", it) }.getOrNull() ?: return

        val json = runCatching { gsonToJson?.invoke(gsonInstance, info) as? String }.getOrNull() ?: return
        val bundle = Bundle().apply { putString("capsule", json) }

        // 判断是否已在显示 → update / show
        val showing = runCatching {
            val e = findMethodOrNull(agent.javaClass.name, "e", String::class.java)
            e?.invoke(agent, address) as? Boolean ?: false
        }.getOrNull() ?: false
        val cmd = if (showing) CMD_UPDATE_CAPSULE else CMD_SHOW_CAPSULE

        val kMethod = findMethodOrNull(agent.javaClass.name, "k", Int::class.javaPrimitiveType!!, Bundle::class.java)
        if (kMethod == null) {
            modLog("W", "capsule: k(IBundle) not found on ${agent.javaClass.name}")
            return
        }
        val result = runCatching { kMethod.invoke(agent, cmd, bundle) as? Bundle }.getOrNull()
        modLog("I", "capsule: sent cmd=0x${cmd.toString(16)} device=$deviceName result=${result != null}")
        modLog("I", "capsule sent cmd=0x${cmd.toString(16)} dev=$address result=${result != null}")
    }

    /** 获取 z8.j 单例：优先静态字段 z8.j$e.a，回退新建实例（bind 由 i() 触发） */
    private fun agentInstance(): Any? {
        val ac = agentClass ?: return null
        // 1) z8.j$e.a 静态单例
        runCatching {
            val holder = findClassOrNull(AGENT_SINGLETON_CLASS) ?: return@runCatching null
            val f = holder.getDeclaredField("a").apply { isAccessible = true }
            return f.get(null)
        }.getOrNull()?.let { return it }
        // 2) 兜底：反射 new
        return runCatching {
            val ctor = ac.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
            ctor?.newInstance()
        }.getOrNull()
    }

    private fun md5(input: String): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
