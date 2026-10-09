package com.melody.melodyplus.hook
import com.melody.melodyplus.hook.modLogT

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.melody.melodyplus.bridge.DevicePresentationPolicy
import com.melody.melodyplus.bridge.DevicePresentationPolicyResolver
import com.melody.melodyplus.bridge.DeviceProfile
import com.melody.melodyplus.bridge.DeviceProfiles
import com.melody.melodyplus.bridge.DeviceRegistryStore
import com.melody.melodyplus.bridge.ModuleDeviceRegistry
import java.util.WeakHashMap

object WirelessSettingsHook : HookContext() {
    private const val TAG = "MelodyPlus"

    private const val DEVICE_PROFILES_CLASS = "C2.X"
    private const val HEADPHONE_CATEGORY_KEY = "headphone_function_key"
    private const val ENTER_EARPHONE_KEY = "enter_my_bluetooth_earphone_key"
    private val moduleForcedFragments = WeakHashMap<Any, Boolean>()

    override fun onHook() {
        modLog("I", "load hooks for com.oplus.wirelesssettings")
        hookDeviceProfilesPage()
    }

    private fun hookDeviceProfilesPage() = safeHook(TAG, "DeviceProfilesSettings") {
        findMethodOrNull(DEVICE_PROFILES_CLASS, "onCreate", Bundle::class.java)?.let { method ->
            hookAfter(method) {
                runCatching { applyPresentationPolicy(instance) }
                    .onFailure { modLogT("W", "apply entry policy after onCreate failed", it) }
            }
        }

        findMethodOrNull(DEVICE_PROFILES_CLASS, "onResume")?.let { method ->
            hookAfter(method) {
                runCatching { applyPresentationPolicy(instance) }
                    .onFailure { modLogT("W", "apply entry policy after onResume failed", it) }
            }
        }

        findMethodOrNull(DEVICE_PROFILES_CLASS, "onDeviceAttributesChanged")?.let { method ->
            hookAfter(method) {
                runCatching { applyPresentationPolicy(instance) }
                    .onFailure { modLogT("W", "apply entry policy after attributes changed failed", it) }
            }
        }

        val preferenceClass = findClass("androidx.preference.Preference")
        findMethodOrNull(DEVICE_PROFILES_CLASS, "onPreferenceTreeClick", preferenceClass)?.let { method ->
            hookBefore(method) {
                val preference = args.firstOrNull()
                val key = callMethodOrNull(preference, "getKey") as? String
                if (key == ENTER_EARPHONE_KEY) {
                    runCatching {
                        if (presentationPolicy(instance) == DevicePresentationPolicy.MODULE_SUPPORTED) {
                            forceEarphoneEntry(instance)
                            openMelodyPanel(instance)
                            result = true
                        }
                    }.onFailure { modLogT("W", "open melody panel failed", it) }
                }
            }
        }
    }

    private fun applyPresentationPolicy(fragment: Any?) {
        val ctx = getFragmentContext(fragment)
        reportHookStatus(ctx)
        val policy = presentationPolicy(fragment)
        modLog(ctx, "I", "无线设置页面呈现策略=${policy.name}")
        when (presentationPolicy(fragment)) {
            DevicePresentationPolicy.OFFICIAL_SUPPORTED -> Unit
            DevicePresentationPolicy.MODULE_SUPPORTED -> forceEarphoneEntry(fragment)
            DevicePresentationPolicy.UNSUPPORTED -> hideModuleEntry(fragment)
        }
    }

    private fun presentationPolicy(fragment: Any?): DevicePresentationPolicy {
        val wasModuleForced = fragment != null && moduleForcedFragments[fragment] == true
        val originalOfficialVisible = isEntryVisible(fragment) && !wasModuleForced
        val moduleSupported = isModuleSupportedByName(fragment)
        return DevicePresentationPolicyResolver.resolve(originalOfficialVisible, moduleSupported)
    }

    private fun forceEarphoneEntry(fragment: Any?) {
        if (fragment == null) return

        val screen = callMethodOrNull(fragment, "getPreferenceScreen")
        val category = getObjectField(fragment, "x")
            ?: callMethodOrNull(fragment, "findPreference", HEADPHONE_CATEGORY_KEY)
        val entry = getObjectField(fragment, "y")
            ?: callMethodOrNull(fragment, "findPreference", ENTER_EARPHONE_KEY)

        setPreferenceAvailable(category)
        setPreferenceAvailable(entry)
        moduleForcedFragments[fragment] = true

        if (screen != null && category != null) {
            val current = callMethodOrNull(screen, "findPreference", HEADPHONE_CATEGORY_KEY)
            if (current == null) {
                callMethodOrNull(screen, "addPreference", category)
            }
        }

        if (category != null && entry != null) {
            val current = callMethodOrNull(category, "findPreference", ENTER_EARPHONE_KEY)
            if (current == null) {
                callMethodOrNull(category, "addPreference", entry)
            }
        }
    }

    private fun hideModuleEntry(fragment: Any?) {
        if (fragment == null) return
        val category = getObjectField(fragment, "x")
            ?: callMethodOrNull(fragment, "findPreference", HEADPHONE_CATEGORY_KEY)
        val entry = getObjectField(fragment, "y")
            ?: callMethodOrNull(fragment, "findPreference", ENTER_EARPHONE_KEY)

        setPreferenceHidden(entry)
        if (moduleForcedFragments[fragment] == true) {
            setPreferenceHidden(category)
            moduleForcedFragments.remove(fragment)
        }
    }

    private fun setPreferenceAvailable(preference: Any?) {
        if (preference == null) return
        callMethodOrNull(preference, "setVisible", true)
        callMethodOrNull(preference, "setEnabled", true)
        callMethodOrNull(preference, "setSelectable", true)
        callMethodOrNull(preference, "notifyChanged")
    }

    private fun setPreferenceHidden(preference: Any?) {
        if (preference == null) return
        callMethodOrNull(preference, "setVisible", false)
        callMethodOrNull(preference, "setEnabled", false)
        callMethodOrNull(preference, "notifyChanged")
    }

    private fun isEntryVisible(fragment: Any?): Boolean {
        val screen = callMethodOrNull(fragment, "getPreferenceScreen") ?: return false
        val category = callMethodOrNull(screen, "findPreference", HEADPHONE_CATEGORY_KEY) ?: return false
        val entry = callMethodOrNull(category, "findPreference", ENTER_EARPHONE_KEY)
            ?: callMethodOrNull(screen, "findPreference", ENTER_EARPHONE_KEY)
            ?: return false
        val categoryVisible = callMethodOrNull(category, "isVisible") as? Boolean ?: true
        if (!categoryVisible) return false
        return callMethodOrNull(entry, "isVisible") as? Boolean ?: true
    }

    private fun openMelodyPanel(fragment: Any?) {
        val context = getFragmentContext(fragment) ?: return
        val device = getBluetoothDevice(fragment)
        val cachedDevice = getObjectField(fragment, "k")
        val profile = profileForDeviceName(context, device, cachedDevice)
            ?: DeviceProfiles.sonyWf1000Xm3

        val mac = device?.address
            ?: callMethodOrNull(cachedDevice, "getAddress") as? String
            ?: callMethodOrNull(cachedDevice, "getCachedIdentityAddress") as? String
            ?: return
        val title = device?.name
            ?: callMethodOrNull(cachedDevice, "getName") as? String
            ?: callMethodOrNull(cachedDevice, "getCacheName") as? String
            ?: profile.displayName

        val intent = Intent("com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL").apply {
            setClassName("com.oplus.melody", "com.oplus.melody.onespace.OneSpaceDetailActivity")
            putExtra("device_mac_info", mac)
            putExtra("device_title", title)
            putExtra("device_name", title)
            putExtra("model_id", profile.modelId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        modLog("I", "open melody panel from wireless settings: $mac / $title")
        context.startActivity(intent)
    }

    private fun getFragmentContext(fragment: Any?): Context? =
        (callMethodOrNull(fragment, "getContext") as? Context)
            ?: (callMethodOrNull(fragment, "getActivity") as? Context)

    private fun getBluetoothDevice(fragment: Any?): BluetoothDevice? {
        val direct = getObjectField(fragment, "u") as? BluetoothDevice
        if (direct != null) return direct
        val cachedDevice = getObjectField(fragment, "k")
        return callMethodOrNull(cachedDevice, "getDevice") as? BluetoothDevice
    }

    private fun isModuleSupportedByName(fragment: Any?): Boolean {
        val device = getBluetoothDevice(fragment)
        val cachedDevice = getObjectField(fragment, "k")
        val context = getFragmentContext(fragment)
        return profileForDeviceName(context, device, cachedDevice) != null
    }

    private fun profileForDeviceName(
        context: Context?,
        device: BluetoothDevice?,
        cachedDevice: Any?,
    ): DeviceProfile? {
        if (context == null) return null
        // MAC→型号 缓存优先（改名容忍）
        val addr = runCatching { device?.address }.getOrNull()
        addr?.let { DeviceRegistryStore.profileIdForMac(context, it) }
            ?.let { DeviceProfiles.get(it) }
            ?.let { return it }
        return ModuleDeviceRegistry(context).profileForName(deviceDisplayName(device, cachedDevice))
            ?.also { profile ->
                if (addr != null) runCatching { DeviceRegistryStore.saveMacProfile(context, addr, profile.id) }
            }
    }

    private fun deviceDisplayName(device: BluetoothDevice?, cachedDevice: Any?): String? =
        device?.name
            ?: callMethodOrNull(cachedDevice, "getName") as? String
            ?: callMethodOrNull(cachedDevice, "getCacheName") as? String
}
