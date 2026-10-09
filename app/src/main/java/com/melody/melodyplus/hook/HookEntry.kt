package com.melody.melodyplus.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    /**
     * zygote 阶段记录模块 APK 路径。`StartupParam.modulePath` 只在 initZygote 阶段由框架传入，
     * 静态字段随 zygote fork 对全部 App 进程可见，是宿主进程内定位模块 APK 最可靠的方式
     * （供 [ModuleAssets] 用 ZipFile 直读模块内置 assets，绕开 Android 11+ 包可见性对 Provider 的封锁）。
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ModuleAssets.modulePath = startupParam.modulePath
        // 注意：本方法运行在 **zygote 进程**，绝不能在此经 ContentResolver 调 Provider
        // （zygote 做 binder 同步调用有死锁风险）。故此处仅走 LSP 通道（XposedBridge.log），
        // 真正面向用户/UI 的运行日志由各 App 进程内的 handleLoadPackage 起（见 loadHook）写双通道。
        runCatching {
            XposedBridge.log("[com.melody.melodyplus] initZygote modulePath=${startupParam.modulePath}")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        modLog("I", "handleLoadPackage: $pkg process=${lpparam.processName}")

        when {
            pkg == "com.oplus.melody" || pkg.startsWith("com.coloros.melody") ||
                pkg.startsWith("com.oneplus.melody") || pkg.startsWith("com.oplus.melody.") -> {
                loadHook(MelodyPanelHook, lpparam.classLoader, pkg, lpparam.processName)
                loadHook(MelodyCapsuleHook, lpparam.classLoader, pkg, lpparam.processName)
            }

            pkg.startsWith("com.oplus.wirelesssettings") ||
                pkg.startsWith("com.coloros.wirelesssettings") ->
                loadHook(WirelessSettingsHook, lpparam.classLoader, pkg, lpparam.processName)

            pkg == "com.android.bluetooth" ->
                loadHook(BluetoothAudioConnectionHook, lpparam.classLoader, pkg, lpparam.processName)

            pkg == "com.android.settings" ->
                loadHook(SettingsLogFilterHook(), lpparam.classLoader, pkg, lpparam.processName)
        }
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader, pkg: String, processName: String?) {
        modLog("I", "HOOK_BOOT package=$pkg process=$processName hook=${hook.javaClass.simpleName}")
        com.melody.melodyplus.scope.AutoResolver.init(classLoader)
        hook.appClassLoader = classLoader
        runCatching { hook.onHook() }
            .onSuccess {
                val msg = "HOOK_READY package=$pkg process=$processName hook=${hook.javaClass.simpleName}"
                modLog("=== [com.melody.melodyplus] $msg ===")
                reportSlotLog("I", msg)
            }
            .onFailure {
                val msg = "Hook 安装失败 ${hook.javaClass.simpleName}: $it"
                modLog("I", "$msg")
                reportSlotLog("E", msg)
            }
    }

    /** 上报一条 Hook 装载日志：直接走统一双通道 [modLog]（LSP + 模块内部）。 */
    private fun reportSlotLog(level: String, message: String) {
        modLog(level, message)
    }
}