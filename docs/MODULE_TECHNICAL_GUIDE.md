# MelodyPlus 模块技术说明

> 面向开发者/贡献者的**技术白皮书**：讲清「模块怎么把第三方耳机接管进 ColorOS 原生链路」。
> 覆盖三大机制：**① Hook 注入（hook add）** · **② 详情页动态面板注入** · **③ 后端多协议匹配**。
> 所有接口名 / 方法名 / 命令码均可与源码一一对应；标注 `⚙` 的是可改点，`⚠` 的是踩过的坑。
>
> - applicationId：`com.melody.melodyplus`
> - 模块类型：Legacy Xposed（`IXposedHookLoadPackage` + `IXposedHookZygoteInit`）
> - 入口：`com.melody.melodyplus.hook.HookEntry`
> - 宿主作用域：`com.oplus.melody` 系列 / `com.oplus.wirelesssettings` 系列 / `com.android.bluetooth` / `com.android.settings`
> - 跨进程通道：ContentProvider `com.melody.melodyplus.registry`

---

## 0. 整体架构

```
┌─────────────────────────── 模块自身进程（com.melody.melodyplus） ───────────────────────────┐
│  MainActivity / ui/*  : Jetpack Compose 配置界面（设备列表 / 外观 / 关于）                    │
│  DeviceRegistryStore  : 绑定关系、模块档案、日志     DeviceImageStore : 用户自定义图片         │
│  RegistryProvider ────┼─ ContentProvider（跨进程 RPC：注册表 / 日志 / 导出）                  │
└───────────────────────┼──────────────────────────────────────────────────────────────────┘
                        │  contentResolver.call(注册表 URI, METHOD_*, bundle)
      ┌─────────────────┴──────────────────┐
      │  宿主进程（被 hook 的 App 进程）      │
      │                                    │
      │  HookEntry.handleLoadPackage       │  ← 按包名分发
      │        │                           │
      │        ├─ MelodyPanelHook  ────────┼─┐ 详情页面板注入 / 设备伪装 / 弹窗
      │        ├─ MelodyCapsuleHook ───────┼─┤ 设备空间胶囊直构直发
      │        ├─ WirelessSettingsHook ────┼─┤ 无线设置页伪装
      │        ├─ BluetoothAudioConnectionHook ─┤ 蓝牙音频连接事件
      │        └─ SettingsLogFilterHook ───┼─┘ 设置页日志过滤
      │                                    │
      │  bridge / core / adapter（同进程内直接调用，非跨进程）│
      │        HeadsetSessionManager ──────┼─→ AdapterRegistry ─→ HeadsetAdapter
      │                                    │                         │
      │                                    │        SonyHeadsetAdapter / XiberiaHeadsetAdapter
      │                                    │                         │
      │                                    │              RFCOMM(SPP) 会话
      └────────────────────────────────────┴─────────────────────────┼──────────────
                                                                      ▼
                                                              物理耳机（Sony / XIBERIA …）
```

**三条主链路**：
1. **注入链**：zygote → `handleLoadPackage` → 各 Hook `onHook()`（Hook 注入，第 1 章）。
2. **UI 链**：宿主详情页原生 `PreferenceGroup` → 我们挂载的面板项 → 用户操作（动态面板注入，第 2 章）。
3. **协议链**：面板项 → `HeadsetCommand` → `HeadsetSessionManager` → `HeadsetAdapter` → RFCOMM 帧 → 耳机（后端多协议匹配，第 3 章）。

---

## 1. Hook 注入（hook add）

### 1.1 入口与分发

`hook/HookEntry.kt` 同时实现两个接口：

```kotlin
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ModuleAssets.modulePath = startupParam.modulePath      // ⚙ 关键：只在 zygote 阶段有值
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            pkg == "com.oplus.melody" || pkg.startsWith("com.coloros.melody") ||
                pkg.startsWith("com.oneplus.melody") || pkg.startsWith("com.oplus.melody.") ->
                loadHook(MelodyPanelHook, ...)       // 详情页 / 伪装 / 弹窗
                loadHook(MelodyCapsuleHook, ...)     // 系统设备空间胶囊
            pkg.startsWith("com.oplus.wirelesssettings") ||
                pkg.startsWith("com.coloros.wirelesssettings") ->
                loadHook(WirelessSettingsHook, ...)
            pkg == "com.android.bluetooth" ->
                loadHook(BluetoothAudioConnectionHook, ...)
            pkg == "com.android.settings" ->
                loadHook(SettingsLogFilterHook(), ...)
        }
    }
}
```

**设计要点**

| 点 | 说明 |
|---|---|
| `initZygote` 存 `modulePath` | `StartupParam.modulePath` 只在 zygote 阶段由框架传入。存进静态字段后，zygote fork 出的**每个 App 进程**都能读到 —— 这是宿主进程内定位模块 APK 最可靠的方式。`ModuleAssets` 据此用 `ZipFile` 直读模块内置 assets，**绕开 Android 11+ 包可见性对 ContentProvider 的封锁**。 |
| 包名前缀匹配 | melody 存在 `com.oplus.melody` / `com.coloros.melody` / `com.oneplus.melody` 多别名，用 `startsWith` 兜住。 |
| 一个 Hook 一个 `HookContext` | `loadHook()` 里先 `AutoResolver.init(classLoader)`，再 `hook.onHook()`，全程 `runCatching`；成功打 `HOOK_READY`，失败打 `Hook 安装失败`，**绝不因单个 Hook 抛异常影响其它 Hook**。 |
| 状态上报 | `reportSlotLog()` 通过 Provider 把启动结果回传模块 UI（`METHOD_APPEND_LOG`）。 |

> `MelodyPanelHook` 与 `MelodyCapsuleHook` 都挂在 melody 进程；`BluetoothAudioConnectionHook` 挂在 `com.android.bluetooth` 进程 —— **同一个模块在多进程各自安装自己的 Hook 子集**。

### 1.2 HookContext：反射工具基类

所有 Hook 继承 `hook/HookContext.kt`，它把「找类/找方法/挂 Hook/取字段」统一封装：

| 方法 | 作用 |
|---|---|
| `findClass(name)` / `findClassOrNull(name)` | `Class.forName(name,false,appClassLoader)`；`OrNull` 版失败再走 `AutoResolver.resolveByAlias`（混淆名兜底）。 |
| `findMethod / findConstructor`（及 `OrNull`） | `getDeclaredMethod` + `isAccessible=true`。 |
| `findMethodByParamCount(className, name, n)` | **重载太多、拿不到参数类型时**按「名字 + 参数个数」定位。 |
| `hookAfter / hookBefore / hookConstructorAfter` | 包一层 `XC_MethodHook`，回调收进 `HookParam`。 |
| `safeHook(tag, name) { ... }` | 任何 hook 安装包 `runCatching`，失败只记 `skip hook xxx`，不炸进程。 |
| `reportHookStatus / reportLog` | Provider 路由上报（见第 4 章）。 |

### 1.3 ⚠ `XposedBridge.hookMethod` 的版本兼容坑

**不要直接调用 `XposedBridge.hookMethod(...)`**：

```kotlin
// HookContext.hookMember() 的正确做法 —— 反射解析，兼容所有 LSPosed 版本
val m = XposedBridge::class.java.declaredMethods.firstOrNull {
    it.name == "hookMethod" && it.parameterTypes.size == 2 &&
        Member::class.java.isAssignableFrom(it.parameterTypes[0])
} ?: throw NoSuchMethodError("runtime XposedBridge.hookMethod(Member,*) not found")
m.isAccessible = true
m.invoke(null, member, callback)
```

原因：不同 LSPosed 版本 `hookMethod` 的**返回类型不同**（旧版返回 `int`，新版返回 `XC_MethodHook.Unhook`）。编译期用固定描述符绑定会 `NoSuchMethodError`。**按名字 + 参数个数反射解析**是唯一跨版本稳的写法。

### 1.4 `HookParam`：入参写回的语义

```kotlin
class HookParam(private val param: XC_MethodHook.MethodHookParam<out XC_MethodHook>) {
    val args: List<Any?> = param.args?.toList() ?: emptyList()   // ⚠ 这是副本，改它无效
    val instance: Any? = param.thisObject
    var result: Any?  get() = param.result; set(v) { param.result = v }
    fun setArg(index: Int, value: Any?) {                        // ✅ 改入参必须走这里
        val array = param.args ?: return
        if (index in array.indices) array[index] = value
    }
}
```

> ⚠ **易错点**：`args` 是 `param.args` 的**拷贝**。在 `before` 阶段想改写被调方法的实参，必须用 `setArg()` 写回原始数组，改 `args` 无效。

### 1.5 通用反射助手

`HookContext.kt` 底部提供带缓存的快捷函数（`fieldCache` / `methodCache` 均为 `ConcurrentHashMap`，避免高频反射开销）：

```kotlin
getObjectField(instance, "fieldName")            // 沿继承链找字段
setObjectField(instance, "fieldName", value)
callMethod(instance, "methodName", *args)        // 按「名字 + 参数个数」找方法并 invoke
callMethodOrNull(instance, "methodName", *args)  // 失败返回 null，不抛
```

这些是 Panel Hook 里「调用宿主混淆方法」的主要手段（宿主方法名混淆成 `g` / `d` / `e` 等单字母）。

### 1.6 作用域与多进程 RPC

Hook 运行在**宿主进程**，模块 UI 运行在**模块进程**，两者靠 ContentProvider 通信：

```
宿主进程 HookContext.reportLog()  ──call()──▶  com.melody.melodyplus.registry (RegistryProvider)
模块进程 ui/pages/*                ──call()──▶  同上（读注册表 / 日志 / 导出）
```

`RegistryContract` 定义 URI / `METHOD_APPEND_LOG` / `METHOD_REPORT_STATUS` / `EXTRA_*`。调用前统一用 `RegistryContract.isProviderAvailable(context)` 探活，避免宿主进程早期 Provider 未就绪时报错。

---

## 2. 详情页动态面板注入

> 目标：在 ColorOS 耳机 App（`com.oplus.melody`）详情页的**原生「耳机设置」分类**里，追加一块「按当前耳机型号动态生成」的功能面板，操作走真实 SPP 协议。
> 实现位置：`hook/MelodyPanelHook.kt`（6300+ 行，注入相关方法集中在 1400–2200 行）。

### 2.1 注入锚点：Hook 原生 `addPreference`

**不新开页面**，而是挂到宿主自己往分类里加 preference 的时机上：

```kotlin
private fun hookNativeCategoryAdd() = safeHook(TAG, "native category add (PreferenceGroup.e)") {
    val groupClass = findClassOrNull(PREFERENCE_GROUP_CLASS) ?: return@safeHook
    val preferenceClass = findClassOrNull(PREFERENCE_CLASS) ?: return@safeHook

    // 找所有「参数是 Preference 子类、返回 void」的方法（宿主里是 addPreference 的混淆名）
    val addMethods = groupClass.declaredMethods.filter { candidate ->
        candidate.parameterTypes.size == 1 &&
            candidate.parameterTypes[0].isAssignableFrom(preferenceClass) &&
            candidate.returnType == Void.TYPE
    }
    addMethods.forEach { method ->
        method.isAccessible = true
        hookAfter(method) {
            val parent = instance as? Any ?: return@runCatching
            val preference = args.firstOrNull() as? Any ?: return@runCatching
            if (!isModuleScope(parent)) return@runCatching
            val key = callMethodOrNull(preference, "getKey") as? String
            if (key != DETAIL_DSEE_CATEGORY_KEY) return@runCatching   // 只在「耳机设置」分类上动手
            if (isOurPreference(preference)) return@runCatching        // 幂等：跳过自己注入的
            scheduleMc05PanelIntoCategory(preference)
        }
    }
}
```

**关键决策**

| 点 | 做法 | 原因 |
|---|---|---|
| 锚点选谁 | 宿主**原生分类挂载**的那一刻（`addPreference` 之后） | 分类对象此时已存在，且是宿主自己会渲染的容器，天然融入原生 UI。 |
| 只认分类 key | `DETAIL_DSEE_CATEGORY_KEY`（`"earphone"`） | 避免污染其它页面。 |
| 幂等 | `isOurPreference`：自身注入项 key 以 `melodyplus_` 开头 | 面板会被多次触发（多 fragment / 多次 onResume），必须防重复添加。 |

### 2.2 ⚠ 时序问题：宿主分类内容是**异步填充**的

原生分类挂上时内容还是空的 —— 宿主用 `B8/e` Supplier 异步填。若立即注入，会被宿主的刷新覆盖。因此用**主线程多时机重试**：

```kotlin
listOf(0L, 120L, 400L, 900L).forEach { delayMs ->
    Handler(Looper.getMainLooper()).postDelayed({
        injectDynamicPanelIntoCategory(category, "native category add")
    }, delayMs)
}
```

> 同一策略也用于 DSEE 先行打样注入（`scheduleDetailDseeInjection`，延迟数组 `DETAIL_DSEE_RETRY_DELAYS_MS = [0,250,750,1500]`）。

### 2.3 面板项构造：反射创建宿主控件

用**宿主自己的控件类** `COUISwitchPreference` 实例化，保证视觉 100% 原生：

```kotlin
val preference = findConstructorOrNull(COUISwitch_PREFERENCE_CLASS, Context::class.java)
    ?.newInstance(context) ?: return@runCatching
invokeHostMethod(preference, "setKey",          String::class.java, item.key)
invokeHostMethod(preference, "setTitle",        CharSequence::class.java, item.title)
invokeHostMethod(preference, "setSummary",      CharSequence::class.java, item.summary)
invokeHostMethod(preference, "setPersistent",   Boolean::class.javaPrimitiveType!!, false)  // ⚠ 关持久化
invokeHostMethod(preference, "setOrder",        Int::class.javaPrimitiveType!!, ORDER_BASE + index)
installXiberiaChangeListener(preference, item)
addHostPreference(category, preference)
```

> ⚠ `setPersistent(false)`：我们的开关状态由设备**真实回读**决定，不能让 SharedPreferences 记忆成假状态。

### 2.4 监听：`Proxy` 动态代理原生监听接口

`setOnPreferenceChangeListener` 的参数是宿主自定义接口，无法编译期实现。用 `Proxy.newProxyInstance` 动态代理：

```kotlin
val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
    when {
        method.name == "toString"  -> "MelodyPlusXiberiaListener(${item.key})"
        method.name == "hashCode"  -> System.identityHashCode(proxy)
        method.name == "equals"    -> proxy === args?.firstOrNull()
        method.returnType == Boolean::class.javaPrimitiveType && args?.size == 2 -> {
            when (item.kind) {
                Kind.SWITCH -> { requestXiberiaPanelChange(preference, item, args[1] as Boolean, null); false }
                Kind.CHOICE -> { showXiberiaChoiceDialog(preference, item); false }
                Kind.PAGE   -> { showXiberiaSubPage(preference, item); false }
            }
        }
        else -> null
    }
}
```

**统一返回 `false`**：不让控件自行改 checked，而是**等命令真实结果回来后**由 `refreshXiberiaPanelStates()` 回填 —— 从根上杜绝「UI 显示开、耳机实际关」。

### 2.5 按型号自适配：`panelItems(productId)` 驱动

面板项**不写死**，由当前耳机的官方 productId 决定：

```kotlin
private fun resolvePanelProductId(source: Any?): Int? {
    val profile = moduleProfileFor(source) ?: DeviceProfiles.get(activeProfileId) ?: return null
    val candidates = buildList {
        add(profile.modelId)                                 // 如 "MC05"（最准）
        add(profile.displayName); add(profile.displayName.removePrefix("XIBERIA ").trim())
        add(profile.id.substringAfterLast('.'))
    }
    candidates.forEach { XiberiaProductCatalog.byModel(it)?.let { p -> return p.productId } }
    candidates.forEach { XiberiaModelProfiles.byModel(it)?.let { p -> return p.productId } }
    return null                                              // 解析不出 → 不猜、不注入
}

val items = XiberiaProductCatalog.panelItems(productId)      // ← 项集合的唯一来源
```

- `XiberiaProductCatalog`：官方 App（`com.cchip.desheng`）**22 能力位 × 18 型号**真值 + 命令码 → 面板项映射（详见 `docs/PLAN_XIBERIA_AutoAdaptive_Panel_v3.md`）。
- 产物：MC05 得到「提示音/均衡器/按键功能/漏音抑制模式·空间音效/智能AI/排水/游戏模式/低音增强/LDAC」共 9 项；AS10 会多出降噪+空间音频；W30 无游戏模式 —— **同一份代码，按型号自动裁剪**。
- **型号切换**：`activePanelProductId` 变化时清空 `xiberiaPanelPreferences / pendingXiberiaStates / xiberiaStateCache / xiberiaChoiceCache`，打 `PANEL_MODEL_SWITCH`。

### 2.6 ⚠ 真实状态回读：不能拿「全状态查询」当万能

早期踩坑：用 `0x0807`（其实是 `USER_ALL_EQ_GET`，EQ 预设查询）当"设备全状态查询"，把 EQ 增益字节误当开关位解析 → 全是垃圾值。

**现方案：分项 GET 逐项回读**：

```kotlin
private fun queryCommandFor(item: PanelItem): Int? = when (item.key) {
    "melodyplus_xi_game"         -> XiberiaCommands.GAME_MODE_GET       // 0x0C02
    "melodyplus_xi_ldac"         -> XiberiaCommands.LDAC_GET            // 0x0E05
    "melodyplus_xi_bass"         -> XiberiaCommands.BASS_BOOST_GET      // 0x0E12
    "melodyplus_xi_dual"         -> XiberiaCommands.DUAL_DEVICE_GET     // 0x0E0C
    "melodyplus_xi_touch"        -> XiberiaCommands.TOUCH_GET           // 0x0E18
    "melodyplus_xi_spatial"      -> XiberiaCommands.SPATIAL_SOUND_GET   // 0x0E10
    "melodyplus_xi_sound_effect" -> XiberiaCommands.SOUND_EFFECT_GET    // 0x0E0E（CHOICE 型）
    else -> null
}
```

`probeXiberiaDeviceStates()` 的硬性规则：

| 规则 | 实现 |
|---|---|
| 只探「登记了 GET 码」的项 | `panelItems.filter { queryCommandFor(it) != null }` |
| 每项**独立超时** | `withTimeoutOrNull(PROBE_TIMEOUT_MS)` 包 `readFeatureRaw`，互不阻塞 |
| **无应答 = 保持原值，绝不写 false** | `if (payload == null) { log("no-answer"); return@forEach }` —— 严格区分「设备说关」与「没问到」 |
| 走**与 SET 同一通道** | `HeadsetSessionManager.readFeatureRaw(...)`（含懒连接），保证同一 SPP 链路 |
| 去重 | `PROBE_DEDUP_MS`（5s）内只探一次，避免重复发帧 |
| 应答解析 | 单字节 payload，`0x01`=开；CHOICE 型按 `resolveChoiceValue()` |

**`resolveChoiceValue()` 的 SOUND_EFFECT 特例**（真机实测应答 = 单字节 `01`）：

```
官方 SoundEffectMode 声明序：KJ(0) / LY(1) / FOOT(2)
MC05 的 getSoundEffectItems() = [KJ, LY]
⇒ 单字节按 enum ordinal → choices 下标 解释：
   0x00 → choices[0]（空间音效）
   0x01 → choices[1]（漏音抑制模式）
兼容两字节帧（取尾字节）与「应答直接回 modeValue（0x0D/0x0E）」的固件变体。
```

### 2.7 写入链路与互斥联动

```kotlin
private fun requestXiberiaPanelChange(preference: Any, item: PanelItem, enabled: Boolean, level: Int?) {
    val cmd = item.cmdSet ?: return toast("该型号无对应命令")
    pendingXiberiaStates[item.key] = enabled                 // 乐观置 pending
    invokeHostMethod(preference, "setEnabled", Boolean::class.javaPrimitiveType!!, false)

    // 互斥联动（官方 LDAC ↔ 游戏模式）：开本项 → 关同组其它项
    val siblings = if (enabled && item.mutexGroup != null)
        panelItems(activePanelProductId).filter {
            it.mutexGroup == item.mutexGroup && it.key != item.key && it.cmdSet != null
        } else emptyList()
    siblings.forEach { pendingXiberiaStates[it.key] = false; xiberiaStateCache[it.key] = false }

    scope.launch {
        val command: HeadsetCommand = if (level != null) HeadsetCommand.SetLevel(cmd, level)
                                      else                 HeadsetCommand.SetSwitch(cmd, enabled)
        val result = HeadsetSessionManager.execute(appContext, address, profile.id, command)
        val ok = result is CommandResult.Success
        pendingXiberiaStates.remove(item.key)
        if (ok) { xiberiaStateCache[item.key] = enabled; level?.let { xiberiaChoiceCache[item.key] = it } }
        siblings.forEach { /* 逐个下发关断并校验应答，见 XIBERIA_PANEL_MUTEX_OFF 日志 */ }
        postToUi(appContext) { refreshXiberiaPanelStates(); if (!ok) toast("${item.title} 设置失败") }
    }
}
```

日志打点：`PANEL_INJECTED`（注入）/ `XIBERIA_PROBE*`（回读）/ `XIBERIA_PANEL_SET`（写入）/ `XIBERIA_PANEL_MUTEX_OFF`（互斥关断）。

### 2.8 子页（`Kind.PAGE`）

| `subPage` | 打开方式 |
|---|---|
| `SOUND_EFFECT` | CHOICE 对话框（KJ/LY 多选一） |
| `EQ` | `showXiberiaEqEditor` → `XiberiaEqDialog` / `XiberiaEqEditorView`（6/12 段竖向滑块） |
| `PROMPT_TONE` / `KEY_FUNCTION` / `SMART_AI` / `DRAIN_WATER` / `LDAC` | `showXiberiaSubPage` 按类分发 |

### 2.9 ⚠ 详情页主图与电量槽：贴「宿主原生槽位」而非自绘

**架构决策（2026-10-07 重构）**：早期用模块自绘容器（`ThreeDeviceLayout` + `BatteryBadgeView`）画
「三图 + 电量徽标」覆盖在宿主详情页上。问题：宿主 `MelodyDetailModelView` 内部用 Glide **异步**加载
默认（Sony）大图，**晚到**的回调会把模块自绘图覆盖掉 —— 表现为「先显示我的图，几秒后变成 Sony 图」。

**现方案：不自绘，直接给宿主原生 ImageView 贴「单张主图」，电量交给宿主原生控件。**

| 函数 | 职责 |
|---|---|
| `sweepMountProductImage(activity)` | 全窗口扫描宿主 `normal_image`（`0x7f090457`）ImageView，逐个贴图 |
| `replaceModuleProductImage(source)` | 由 hook 触发：判定 source 类型 → 定位目标 ImageView → 贴图 |
| `applyProductImageTo(imageView, profile)` | 明确传入目标 ImageView 贴图（弹窗卡片主图 / 其它非 `normal_image` 图位） |
| `stopDetailModelRendering(source, imageView)` | **停宿主 `MelodyDetailModelView` 内部大图渲染**，杜绝 Glide 回填 |
| `productDrawable(ctx, profile)` | 单张主图解析：用户自定义 `MelodyPlus/images/<id>/main.png` → 内置 `assets/device_images/<型号>/main.png` |
| `ensureDetailBatteryRefresh(profile)` | 贴图后即时触发宿主**原生**电量补采（`renderDetailStatusInfo` 注入 `b/g/u` 三路） |
| `scheduleBatteryPolling(ctx, addr, profile)` | `BATTERY_POLL_INTERVAL_MS`（5 分钟）周期兜底刷新；tick 走 `scheduleDetailBatteryFetch(force = true)` **强制**建会话查询（⚠ 旧实现电量为完整即早返回 → 空转） |
| `hookDiscoveryPopupBattery()` / `alignPopupBatterySlots()` | 发现弹窗三路电量槽平移到三合一图正下方 |
| `requestPopupBatterySync()` / `replayPopupBattery()` | 弹窗 `h(IZZIZZIZZ)` 回调时**缓存为空即主动建会话补采**三路电量，命中后就地重放刷新原生三槽（弹窗没有详情页的贴图/轮询触发时机，只能在这一刻兜底） |

**重入护栏**：`replacingProductImage`（`ThreadLocal<Boolean>`）—— 自己触发的 `setImageDrawable`
不再被 hook 二次处理，避免死循环。

```kotlin
private val replacingProductImage = ThreadLocal.withInitial { false }

private fun applyProductImageTo(imageView: ImageView, profile: DeviceProfile): Boolean {
    if (replacingProductImage.get()) return false          // 重入保护
    val drawable = productDrawable(imageView.context, profile) ?: return false
    replacingProductImage.set(true)
    try {
        imageView.animate().cancel(); imageView.clearAnimation()
        imageView.alpha = 1f; imageView.visibility = View.VISIBLE
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.imageTintList = null; imageView.clearColorFilter()
        val d = drawable.constantState?.newDrawable(imageView.resources) ?: drawable
        d.alpha = 255
        imageView.setImageDrawable(d)
        ensureImageHierarchyVisible(imageView)
    } finally { replacingProductImage.set(false) }
    imageView.invalidate()
    return true
}
```

**弹窗电量槽对齐（`alignPopupBatterySlots`）**：用 `imageMatrix.mapRect()` 把「图片原始像素矩形」映射到
ImageView 内实际绘制矩形 → 得内容区屏幕真实 `left` / 宽度，**不依赖 `scaleType` 假设**（FIT_CENTER /
CENTER_CROP 都算对）。映射表 `0x7f090363→左耳 / 0x7f090362→右耳 / 0x7f090365→充电仓`；每次回调先
`translationX = 0f` 清旧平移再量基准位，避免累积偏移。

**单图模式**：全链路统一只读写 `main.png`（三合一合成图：左耳 + 右耳 + 耳机仓已合成一张）。
`DeviceImageAssets.assetManagerPath()` / `DeviceImageStore.fileNameFor()` 内部恒等于 `SLOT_MAIN`，
`slot` 形参保留仅为兼容历史签名。已删除 `hook/ThreeDeviceLayout.kt`、`hook/BatteryBadgeView.kt`
及 `xiberia_mc05/{left,right,case}.png`。

---

## 3. 后端多协议匹配

> 一份代码同时对接 **Sony（v1/v2）** 与 **XIBERIA/cchip（v1）** 两大协议族，靠「设备档案匹配 → 适配器路由 → 会话管理 → 帧编解码」四层解耦实现。

### 3.1 层一：设备档案匹配（识别「这是谁」）

`bridge/DeviceProfile.kt`：

```kotlin
data class DeviceProfile(
    val id: String,                    // "xiberia.mc05" / "sony.wf1000xm5" / "oppo.encox3"
    val modelId: String,               // "MC05"
    val displayName: String,           // "XIBERIA MC05"
    val adapter: String,               // "xiberia" / "sony" / "official"  ← 协议路由键
    val transportType: String,         // "rfcomm"
    val protocolVersion: String,       // "cchip-v1" / "v1" / "v2"
    val serviceUuid: UUID,             // SPP UUID 或 Sony 私有 UUID
    val nameRegex: Regex,              // 设备名匹配
    val manualBindRequired: Boolean,
    val capabilities: HeadsetCapabilities,
    val uiFeatures: DeviceUiFeatures,
    val spoofProductId: Int? = null,   // 伪装进 melody 的官方型号号（十进制）
) {
    fun matchesForBinding(name: String?, serviceUuids: Set<UUID>): Boolean =
        nameRegex.matches(name.orEmpty()) && serviceUuid in serviceUuids   // 名 + UUID 双匹配
}
```

`DeviceProfiles` 对象持有全部档案（`all`），并提供 `findByName()`（**长名优先**，避免 `MC01 MAX` 被 `MC01` 误吞）、`get(profileId)`（含别名表）。

> **Sony**：按 `protocolVersion` 选 UUID（v1 `96cc203e-…` / v2 `956c7b26-…`），20+ 型号各自声明电量/降噪/DSEE 能力。
> **XIBERIA**：全系共用标准 SPP UUID `00001101-…`，`protocolVersion = "cchip-v1"`；18 型全建档，`isSupport` 由 `XiberiaProductCatalog` 判定。
> **official**（`oppo.encox3`）：**不进 `all`**，仅用于调试注入身份，避免把真·官方设备误判为模块设备。

### 3.2 层二：适配器路由（决定「用哪套协议」）

`bridge/AdapterRegistry.kt` —— 把「编译期 `when` 硬编码分派」升级为**运行期可注册路由表**：

```kotlin
object AdapterRegistry {
    typealias Factory = (DeviceProfile) -> HeadsetAdapter
    const val DEFAULT_SPOOF_PRODUCT_ID = 0x067410      // OPPO Enco X3
    const val DEFAULT_SPOOF_ID_HEX = "067410"

    private val factories = ConcurrentHashMap<String, Factory>()
    private val spoofProductIds = ConcurrentHashMap<String, Int>()

    init {
        register("sony")     { SonyHeadsetAdapter(it) }
        register("xiberia")  { XiberiaHeadsetAdapter(it) }
        spoofProductIds["sony"]     = DEFAULT_SPOOF_PRODUCT_ID
        spoofProductIds["xiberia"]  = DEFAULT_SPOOF_PRODUCT_ID
        spoofProductIds["official"] = DEFAULT_SPOOF_PRODUCT_ID
    }

    fun register(adapterId: String, factory: Factory)                    // ⚙ 新品牌零成本接入
    fun resolve(adapterId: String): Factory? = factories[adapterId]
    fun spoofProductIdFor(profile: DeviceProfile): Int =
        profile.spoofProductId ?: spoofProductIds[profile.adapter] ?: DEFAULT_SPOOF_PRODUCT_ID
    fun spoofIdHexFor(profile: DeviceProfile): String =
        String.format("%06X", spoofProductIdFor(profile))               // ⚠ 固定 6 位大写
}
```

> ⚠ `spoofIdHexFor` 必须补足 **6 位大写**：melody 原生 profile id 形如 `[0-9A-F]{6}`，若 `0x067410` 输出成 `"67410"`（少一位）会被下游 `parseInt(id,16)` 解析成错误型号。

### 3.3 层三：会话管理（串行化 + 懒连接 + 缓存）

`bridge/HeadsetSessionManager.kt`：

```kotlin
object HeadsetSessionManager {
    private val lock = Mutex()
    private val sessions = ConcurrentHashMap<String, Session>()     // key = 归一化 MAC

    suspend fun connect(context, address, profileId, device?): Boolean
    suspend fun readState(address): HeadsetState?
    suspend fun readFeatureRaw(context, address, profileId, command): ByteArray?   // 面板回读用
    suspend fun execute(context, address, profileId, command): CommandResult
    suspend fun refresh(context, address, profileId): HeadsetState?
    suspend fun disconnect(address)
    fun cachedState(address): HeadsetState?
    fun isSessionConnected(address): Boolean
}
```

| 特性 | 实现 |
|---|---|
| 线程安全 | 所有入口 `lock.withLock { }` 串行化，避免并发写 socket。 |
| **懒连接** | 会话不存在/断开时，`execute`/`readFeatureRaw`/`refresh` 内部先 `adapter.connect(device)` 再执行。 |
| **实时链路状态** | `Session.connected` 是**计算属性** `adapter?.isAlive == true`（⚠ 早年是创建时的 `val` 快照，断连后恒 true → 永远复用死 socket、从不重连；已在 `CHANGELOG_PanelWrite_Battery_Image_Fix.md` 修复）。 |
| **断连重连** | 链路已死时先 `session.adapter?.disconnect()` 释放残留 socket 并 `sessions.remove()`，再重建 —— 否则同 UUID 二次 `connect` 必失败。 |
| **半死链路自愈** | `isAlive` 仅判 `socket.isConnected`，识别不了「已发出但零 RX、恒超时」的半死链路（耳机空转/移远/被官方 App 抢占）。`XiberiaRfcommClient` 追加**链路活性判据**：连续 `STALE_LINK_SILENT_TIMEOUTS`(3) 次命令「本命令期间链路零 RX」即 `markLinkStale()` 关 socket、`running=false` → `isAlive` 变 false → 上层下次自动重连。 |
| 适配器创建 | `createAdapter(profile) = AdapterRegistry.resolve(profile.adapter)!!.invoke(profile)` |
| 状态回填 | `execute` 成功后 `adapter.readState()` 刷新 `session.lastState`，并对 `SetAncMode`/`SetDseeEnabled` 做增量合并（`withAncMode`/`withDseeEnabled`）。 |

### 3.4 层四：协议实现（帧编解码）

#### 3.4.1 统一适配器接口

`core/HeadsetModels.kt`：

```kotlin
interface HeadsetAdapter {
    val capabilities: HeadsetCapabilities
    /** 链路是否仍活着（**实时**）。默认 true；XIBERIA 覆写为 RFCOMM socket 实时状态。 */
    val isAlive: Boolean get() = true
    suspend fun connect(device: BluetoothDevice): Boolean
    suspend fun readState(): HeadsetState
    suspend fun execute(command: HeadsetCommand): CommandResult
    suspend fun disconnect()
    suspend fun readFeatureRaw(command: Int): ByteArray? = null      // 可选能力：面板真实回读
}

sealed class HeadsetCommand {
    data class SetAncMode(val mode: AncMode) : HeadsetCommand()
    data class SetAmbientLevel(val level: Int) : HeadsetCommand()
    data class SetDseeEnabled(val enabled: Boolean) : HeadsetCommand()
    data class SetSwitch(val command: Int, val enabled: Boolean) : HeadsetCommand()   // 面板布尔项
    data class SetLevel(val command: Int, val level: Int) : HeadsetCommand()          // 面板 CHOICE 项
    data class SetEqCustom(val subCmd: Int, val gains: IntArray) : HeadsetCommand()   // 自定义 EQ
}
```

**通用命令设计的意义**：`SetSwitch`/`SetLevel`/`SetEqCustom` 直接携带**官方命令码**，各 adapter 自行决定是否支持 —— 上层面板无需知道具体协议。

#### 3.4.2 XIBERIA / cchip 帧协议

`adapter/xiberia/XiberiaOfficialCodec.kt`（真源：官方 `com.cchip.desheng` 的 `Protocol.packData`）：

**控制/查询帧**（`packData(II[B)`）：

```
偏移:  0    1    2     3        4    5    6      7      8    9      10...
     FF | 03 | 00 | size-8 | 01 | 08 | cmdHi | cmdLo | 00 | plen | payload…（补零到 maxLen）
```

```kotlin
fun encode(cmd: Int, payload: ByteArray = ByteArray(0), maxLen: Int = 0x0F): ByteArray {
    var size = payload.size + 10
    if (size < maxLen) size = maxLen
    val out = ByteArray(size)
    out[0] = 0xFF.toByte(); out[1] = 0x03.toByte(); out[2] = 0x00.toByte()
    out[3] = ((size - 8) and 0xFF).toByte()
    out[4] = 0x01.toByte(); out[5] = 0x08.toByte()          // ⚠ 官方 smali aput-byte 还原，禁改
    out[6] = ((cmd shr 8) and 0xFF).toByte(); out[7] = (cmd and 0xFF).toByte()
    out[8] = 0x00.toByte()                                   // ⚠ 同上
    out[9] = payload.size.toByte()
    payload.copyInto(out, 10)
    return out
}
```

- **数据帧**（`packData(I[B)`）：`FF <cmd8> <len-2> [payload] <crcLo> <crcHi>`，`crc16()` = CRC16/Modbus（表 `0xA001`）。
- **命令码**：`keyOf(frame) = (b[6]<<8)|b[7]`。
- **应答 payload**：`payloadOf()` 按 `b[9]` 长度域取；真机开关应答 payload 为**单字节**（`00`/`01`）。
- **粘包拆分**：`StreamFramer`，帧边界 = `b[3] + 8`。
- **命令码表**：`XiberiaCommands`（44 条官方 `CommandId`），e.g. 游戏模式 `0x0C01/0x0C02`、LDAC `0x0E04/0x0E05`、低音增强 `0x0E11/0x0E12`、双设备 `0x0E0B/0x0E0C`、触控 `0x0E17/0x0E18`、音效 `0x0E0D/0x0E0E`、音量档位 `0x0E26/0x0E27`。
- **⚠ 开关/档位 SET 的 payload 是「单字节」**：官方 `setLowLatency`(0xC01) / `setLDac`(0xE04) / `setLHDC`(0xE13) / `setVolume`(0xE26) / `soundEffect`(0xE0D) 全部 `new-array v1,0x1` → payload 长度 1，`b[9]=1`。
  - 旧实现写 `[0x01, value]`（2 字节）→ 设备只取**首字节**判定恒得 `0x01` → **关闭命令反被置开**；且 `b[9]=2` 与固件期望 1 不符 → SET 不落地。已在 `XiberiaCommands.Payload.switch/level/soundEffect` 修正为单字节。
- **电量探测链**：`0xA11 → 0xA01 → 0xA12 → 0xA02`，取**第一个 payload ≥ 6 字节**者；解析 `[L.on][L.level][R.on][R.level][C.on][C.level]`。
  - ⚠ 探测链全程持 `batteryMutex` **串行化**（connect 首读 / 详情页补采 / 周期轮询三路会并发调用）；不足 6 字节的应答**返回 null**，上层 L/R/C 三槽一律置 `null`，**绝不把单值当槽位电量**（否则单耳电量污染仓位 → 仓显示成 97%）。

> ⚠ **旧实现两个致命差异**（已在 `CHANGELOG_MC05_Official_Alignment.md` 记录）：
> 1. 长度位错（旧 `b[9]` 无 payload 长度）→ 耳机会把长度当 0，**完全不应答**；
> 2. 命令码表沿用**华为兼容模块**的码，与 cchip 官方多处冲突（如旧 `BATTERY=0x0804` 实为 `EQ_MODE_GET`）。

#### 3.4.3 Sony 协议

`adapter/sony/` 下 `SonyHeadsetAdapter` + `SonyProtocol` + `SonyFrameCodec` + `SonyAncController` + `SonyRfcommClient`，按 `DeviceProfile.protocolVersion`（v1/v2 不同 UUID）分支。

### 3.5 多协议匹配决策树（一次连接的全过程）

```
① 扫描/绑定：DeviceProfile.matchesForBinding(name, serviceUuids)
      nameRegex + serviceUuid 双匹配 → 命中某 profile
② 会话建立：HeadsetSessionManager.connect(context, mac, profileId)
      DeviceProfiles.get(profileId) → profile
      AdapterRegistry.resolve(profile.adapter) → Factory
      Factory(profile) → HeadsetAdapter（Sony / Xiberia）
③ 协议执行：adapter.execute(HeadsetCommand)
      SetSwitch/SetLevel → 组帧 → RFCOMM write → 等应答 → 解析
④ UI 回显：adapter.readState() / readFeatureRaw() → HeadsetState / ByteArray
⑤ 伪装注入：AdapterRegistry.spoofIdHexFor(profile) → 6 位 hex → melody 注册表 DTO
```

---

## 4. 数据流总览（时序）

```
用户点面板开关
   │
   ▼ Proxy 监听（返回 false）
MelodyPanelHook.requestXiberiaPanelChange
   │  HeadsetCommand.SetSwitch(cmd, enabled)
   ▼
HeadsetSessionManager.execute  ── lock.withLock（懒连接）──▶  XiberiaHeadsetAdapter
   │                                                            │
   │                                       XiberiaFeatureBackend / XiberiaSession
   │                                                            │
   │                                          XiberiaRfcommClient.sendCommand
   │                                                            │ 组帧 encode
   ▼                                                            ▼
  pending → 成功则更新 cache ─────────────────────────────▶ RFCOMM ▸ 耳机
   │
   ▼ postToUi { refreshXiberiaPanelStates() }  → setChecked / setSummary
```

**回读时序**（注入/重进详情页触发）：

```
injectDynamicPanelIntoCategory（多时机 postDelayed）
   │
   ▼ probeXiberiaDeviceStates("panel_injected")   ← 5s 去重
   │  逐项 queryCommandFor → HeadsetSessionManager.readFeatureRaw（同通道，含懒连接）
   │  单项独立超时；无应答保持原值（不写 false）
   ▼
xiberiaStateCache / xiberiaChoiceCache  ← refreshXiberiaPanelStates()
```

---

## 5. 真源与证据索引

| 主题 | 真源 / 文档 |
|---|---|
| 模块总体架构 / Hook 分发 | `docs/REF_MelodyPlus_ng_Module.md` |
| cchip 帧格式 / CRC / 电量语义 | `docs/REF_ChipDesheng_Frame_And_Codec.md` |
| 官方命令码 / 产品能力 | `docs/REF_ChipDesheng_Protocol_And_Product.md` |
| 按型号自适配面板 | `docs/PLAN_XIBERIA_AutoAdaptive_Panel_v3.md` |
| 面板 1:1 仿官方 + EQ 子页 | `docs/PLAN_MC05_Official_FullPanel_v4.md` |
| 多品牌伪装 / 路由 | `docs/PLAN_device_spoofing_multibrand.md` |
| 蓝牙栈事故取证 | `docs/REF_Bluetooth_Stack_Incident.md` |
| 官方 APK 提取通道 | npmcp `np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/Product$MC05;` |
| 面板写入 / 电量 / 图片三合一修复 | `docs/CHANGELOG_PanelWrite_Battery_Image_Fix.md` |

---

## 6. 构建与安装

```bash
# 1) 构建（Linux proot Ubuntu，需 Android SDK）
cd /root/MelodyPlus_ng
./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk

# 2) 拷到设备（⚠ 不能直接从 /sdcard 装：SELinux 拒绝 system_server 读 /sdcard）
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/MelodyPlus_ng.apk
cp /sdcard/Download/MelodyPlus_ng.apk /data/local/tmp/mp.apk
chmod 666 /data/local/tmp/mp.apk
pm install -r /data/local/tmp/mp.apk

# 3) 生效：LSPosed 热重载模块 / am force-stop 目标包重启
#    ⛔ 严禁软重启 / ctl.restart zygote / killall zygote（本机 PJD110 会重启设备）
```

**结构化日志**（模块 UI → 关于页 → 导出全部日志，或直接读文件）：

```bash
cat /data/data/com.melody.melodyplus/shared_prefs/module_hook_logs.xml
logcat -d | grep -E ' MelodyPlus:|MelodyPlusRFCOMM:|MelodyPlusPanel:'
```

关键日志标签：`HOOK_READY` / `PANEL_INJECTED` / `PANEL_MODEL_SWITCH` / `XIBERIA_PROBE*` / `XIBERIA_PANEL_SET` / `XIBERIA_PANEL_MUTEX_OFF` / `XIBERIA_SPP: RX_RAW|TX`。

---

## 7. 扩展新品牌 / 新型号（贡献指引）

**加一个 XIBERIA 新型号**：在 `XiberiaProductCatalog.PRODUCTS` 增加 `ProductCapabilities`（官方真值），并在 `DeviceProfiles` 加对应 `xiberiaProfile(...)`。面板会按能力位自动生成。

**加一个全新协议族（如 JBL）**：
1. 新建 `adapter/jbl/JblHeadsetAdapter` 实现 `HeadsetAdapter`（含帧编解码 + 会话）；
2. 在 `DeviceProfiles` 增加 `DeviceProfile(adapter = "jbl", ...)`；
3. `AdapterRegistry.register("jbl") { profile -> JblHeadsetAdapter(profile) }`；
4. 若需专属 UI，在 `XiberiaProductCatalog.Kind`/`SubPage` 之外扩展面板映射（或以通用 `SWITCH`/`CHOICE` 表达）。

**改伪装目标型号**：改 `AdapterRegistry.DEFAULT_SPOOF_PRODUCT_ID`（默认 OPPO Enco X3 `0x067410`），或在 `DeviceProfile.spoofProductId` 逐型号覆盖。
