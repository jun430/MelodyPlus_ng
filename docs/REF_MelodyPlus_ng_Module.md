# MelodyPlus_ng 模块实现文档（Module Implementation Reference）

> 本文档记录本模块的实现细节：写了什么、怎么写的、为什么这么写、当前卡在哪。
> 目标：把实现决策与现场结论一次性固化，避免后续维护时信息丢失。
>
> 生成时间：2026-10-06
> 对应工程：`/root/MelodyPlus_ng`
> 变更来源：XIBERIA MC05 耳机接入 ColorOS/OPPO「melody」原生弹窗链路 + 蓝牙进程崩溃修复

---

## 0. 一句话概览

MelodyPlus_ng 是一个 **LSPosed 模块**，把第三方蓝牙耳机（Sony 全系 / XIBERIA MC05 等）
**伪装成 OPPO 官方耳机（统一伪造为 Enco X3, productId=0x067410）**，骗过 ColorOS 的
`com.oplus.melody` 应用，使其弹出**原生的「连接弹窗 + 音频胶囊 + 降噪控制面板」**，
并复用 OPPO 的官方 UI 来操作第三方耳机的 ANC / 电量 / 模式。

核心手法 = **协议层 + UI 层双向伪装**（伪造白名单 DTO、伪造 DeviceInfo 注册表项、
覆写各 getter 返回值），全部通过 ART 层 Xposed hook 完成，不修改 melody 的 APK。

---

## 1. 模块元信息（Identity）

| 项 | 值 | 来源 |
|---|---|---|
| 应用包名 (applicationId) | `com.melody.melodyplus` | `app/build.gradle.kts` |
| namespace | `com.melody.melodyplus` | 同上 |
| 模块显示名 | MelodyPlus | `AndroidManifest` / strings |
| versionName / versionCode | `1.0` / `1` | `app/build.gradle.kts` |
| minSdk / targetSdk / compileSdk | 35 / 36 / 37 | 同上 |
| Xposed 入口类 | `com.melody.melodyplus.hook.HookEntry` | `assets/xposed_init` + `META-INF/xposed/java_init.list` |
| Loader 类型 | **Legacy Xposed**（`IXposedHookLoadPackage`） | `HookEntry.kt` |
| libxposed API | `minApiVersion=100` / `targetApiVersion=100` / `staticScope=true` | `META-INF/xposed/module.prop` |
| xposedminversion | 82 | `AndroidManifest` |
| xposeddescription | `MelodyPlus_ng - XIBERIA MC05 headset adaptation for ColorOS melody panel` | 同上 |
| 权限 | `BLUETOOTH`(≤30), `BLUETOOTH_ADMIN`(≤30), `BLUETOOTH_CONNECT` | `AndroidManifest` |
| 跨进程数据通道 | ContentProvider `com.melody.melodyplus.registry`（exported）<br>类 `.bridge.DeviceRegistryProvider` | `AndroidManifest` |
| 编译依赖 | dexkit 2.2.0（运行期 dex 解析）、Compose/Material3、miuix | `app/build.gradle.kts` |

### 1.1 LSPosed 作用域（scope.list）

```
com.oplus.melody            # melody 主应用（ColorOS）
com.coloros.melody
com.oneplus.melody
com.oplus.wirelesssettings  # 无线设置（蓝牙设置页）
com.coloros.wirelesssettings
com.android.settings        # 系统设置
com.android.bluetooth       # 蓝牙协议栈进程（关键：连接事件来源）
```

> ⚠️ **注意**：`com.android.bluetooth` 在作用域内，意味着模块会注入**蓝牙系统进程**。
> 这是本项目历史上最严重事故（蓝牙栈卡死 / 设备重启）的根源，见第 5 节。

---

## 2. Hook 入口与分发逻辑

入口：`hook/HookEntry.kt` → `handleLoadPackage()`，按包名分发：

```kotlin
when {
    pkg == "com.oplus.melody" || pkg.startsWith("com.coloros.melody") ||
    pkg.startsWith("com.oneplus.melody") || pkg.startsWith("com.oplus.melody.") -> {
        loadHook(MelodyPanelHook, lpparam.classLoader)      // 弹窗/面板/白名单/注册表注入
        loadHook(MelodyCapsuleHook, lpparam.classLoader)    // 音频胶囊触发
    }
    pkg.startsWith("com.oplus.wirelesssettings") ||
    pkg.startsWith("com.coloros.wirelesssettings") ->
        loadHook(WirelessSettingsHook, lpparam.classLoader)
    pkg == "com.android.bluetooth" ->
        loadHook(BluetoothAudioConnectionHook, lpparam.classLoader)   // ← 崩溃源
    pkg == "com.android.settings" ->
        loadHook(SettingsLogFilterHook(), lpparam.classLoader)
}
```

每个 hook 用 `runCatching{}.onFailure{}` 包裹，失败只记日志不抛异常（避免打崩宿主）。

---

## 3. 设备伪装机制（"是否伪造了设备信息？" —— 是的，全套伪造）

### 3.1 伪造点总表

| # | 伪造内容 | 代码位置 | 具体实现 | 目的 |
|---|---|---|---|---|
| 1 | **产品型号 productId** | `bridge/AdapterRegistry.kt:15-64` | 把 sony / xiberia 统一映射为 `DEFAULT_SPOOF_PRODUCT_ID = 0x067410`（十进制 422416 = OPPO Enco X3） | 让 melody 认为这是官方支持型号 |
| 2 | **白名单 DTO** | `hook/MelodyPanelHook.kt:1421-1540` `hookWhitelistFinderInject()` + `forgeWhitelistDto()` | Hook 混淆类 `com.oplus.melody.common.util.Y` 中所有返回 `WhitelistConfigDTO` 的方法，未命中时**凭空构造 DTO**：`setName=设备名`、`setId=伪造hex`、`setType="T1"`、`setUuid=""`、`setBrand`、`setFuzzyMatchName=false`、`setSupportSpp=false` | 骗过 melody 的云端白名单校验（本地改 JSON/db 会被启动刷新覆盖，只能内存 hook） |
| 3 | **DeviceInfo 注册表注入** | `hook/MelodyPanelHook.kt:1891-2025` `hookDeviceRegistryInjection()` | ① hook `DeviceInfoManager.d(BluetoothDevice)`，查表未命中时用 `DeviceInfoManager.f(productId, device, addr, name)` 创建 + `c(info)` 注册入表；② 覆写 `setProductId` → Enco X3；③ 强制 `setDeviceConnectState/setDeviceHeadsetConnectState/setDeviceA2dpConnectState = 2`、`setA2dpActive=true`、`isConnected()=true` | melody 弹窗入口只在设备已存在于 `DeviceInfoManager` 时触发 |
| 4 | **真实 MAC 全程保留** | `hook/BluetoothAudioConnectionHook.kt:148`、`MelodyPanelHook.kt` 全部链路 | 连接边沿、弹窗投递、DeviceInfo 注册、面板控制**一律使用 `BluetoothDevice.address` 真实地址**；`02:00:00:00:00:42` 仅为「无任何真实设备时的调试预览占位」，已改为优先取绑定设备真实 MAC | **不丢弃、不伪造地址**，保证 melody 注册表/面板始终对得上真实设备 |
| 5 | **弹窗投递伪造** | `hook/MelodyPanelHook.kt:2522-2543` `tryInvokeDiscoveryPopup()` | 直接反射调用 melody 内部 `DiscoveryActionManagerServerImpl.p(address, "onForwardConnectedPopup", productIdHex, 0, color, false)` | 绕过正常发现流程，直接投递弹窗 |

### 3.2 逆向依据（melody 16.9.1，npmcp workspace 35147598 / 82003717）

- `HeadsetCoreService$e` 取 `dto.getId()` → `Integer.parseInt(id, 16)` → productId → `DeviceInfoManager.a(productId, device)`。
  **所以伪造的 id 必须是 16 进制字符串**（如 `"067410"`）。
- `WhitelistConfigDTO.fuzzyMatchName` 是 **boolean** 字段（不是前缀字符串）。
- `uuid` 置空可跳过「同名多候选」的二次 uuid 过滤分支。
- 弹窗真实入口：`Lg6/j;`（`DiscoveryActionManagerServerImpl`，父类 `Lcom/oplus/melody/model/scan/a`），
  关键方法 `p()`；server 未就绪时需把请求挂起（`pendingDiscoveryPopups`）等待 `hookDiscoveryActionManagerServer` 捕获。
- 多进程：melody 主进程持有 `g6.j` server 实例；`:fg` 等子进程只有 `g6.a` client，
  需用广播 `com.melody.melodyplus.action.POPUP_EXECUTE` **中继给主进程**执行 `p()`。

### 3.3 支持的设备 profile

- **Sony 全系**（`bridge/DeviceProfile.kt`）：LinkBuds / LinkBuds S / ULT / WF-1000XM3~XM6 /
  WF-C500/C510/C700N/C710N / WF-SP800N / WH-1000XM2~XM6 / WH-CH720N / WH-XB900N/XB910N / WI-C100 / WI-SP600N
  - 协议：`sony` adapter，RFCOMM，serviceUuid v1 `96cc203e-...` / v2 `956c7b26-...`
- **XIBERIA**：MC05（主目标）/ MC01 / MC02 / MC20
  - 协议：`xiberia` adapter，cchip SPP，serviceUuid `00001101-0000-1000-8000-00805F9B34FB`
  - MC05 无 ANC，复用枚举语义：`OFF`=普通 / `NOISE_CANCELLING`=漏音抑制 / `ADAPTIVE`=游戏低延迟
  - 能力位复用：`supportsDsee` → LDAC，`supportsAmbientLevel` → 提示音档位

### 3.4 MAC 处理原则（重要约束）

> **只伪装"型号身份"，绝不丢弃/伪造"设备地址"。**

- **productId / brand / whitelist DTO / DeviceInfo 型号字段** → 伪装成 OPPO Enco X3（这是"骗 melody 认设备"的必要手段）。
- **蓝牙 MAC（`BluetoothDevice.address`）→ 全程用真实值**，贯穿：
  连接边沿捕获 → 弹窗投递（`p(address,...)`）→ `DeviceInfoManager` 注册 → 面板/ANC 控制 → MAC→型号缓存。
  - 理由：melody 的 `DeviceInfoManager`、面板、SPP 回连都以 MAC 为主键；一旦换成假地址，
    注册表项与真实设备对不上，弹窗能弹但**控制/电量/回连全会错位或失效**。
- `DeviceRegistryStore.normalizeAddress()` 只做 `trim + uppercase`，**不改写地址**；
  `normalizeAddressOrNull()` 额外过 `BluetoothAdapter.checkBluetoothAddress()` 做格式校验。
- `02:00:00:00:00:42` 是**本地管理地址段占位符**，仅在"手动调试注入、且系统里没有任何绑定设备"时兜底；
  已改为**优先取 `DeviceRegistryStore.read(ctx).address`（真实绑定 MAC）**，只有无绑定时才用占位符。


---

## 4. 「连接弹窗」的完整触发链路

```
真实蓝牙连接
  → 蓝牙进程 A2dpStateMachine / HeadsetStateMachine 状态切到 STATE_CONNECTED
  → BluetoothAudioConnectionHook 的 hook 捕获「连接边沿」(handleConnectedEdge)
  → 用模块 profile 判断是否支持 (ModuleDeviceRegistry.isModuleSupportedName)
  → 去重 (BluetoothAudioPopupGate, 默认 10s 窗口)
  → 发送定向广播: action=com.melody.melodyplus.action.MODULE_AUDIO_CONNECTED
                 → 显式指向 com.oplus.melody / BluetoothBroadcastReceiver
  → melody 进程内：
       MelodyCapsuleHook 触发音频胶囊
       MelodyPanelHook.handleDiscoveryPopupIntent → 走真实弹窗入口
       → 若本进程无 g6.j server → 广播中继到主进程 → p() 投递弹窗
  → 弹窗展示（设备被伪装成 Enco X3，取 OPPO 官方 UI）
```

**调试注入通道**（无需真设备，用于验证 UI）：
- 模块 App 发广播 `ACTION_MODULE_AUDIO_CONNECTED`（带 `EXTRA_MODULE_TRIGGER=true`）
- melody 进程内 `registerDebugInjectReceiver()`（hook `Application.onCreate` 后注册）接收
- 也支持 ContentProvider `METHOD_INJECT_DEBUG_POPUP`（`bridge/DeviceRegistry.kt`）

### 4.1 「注入官方耳机」的虚拟注入机制（当前实现）

> 设计目标：调试注入**不注入索尼型号**，而是注入一台**官方耳机 OPPO Enco X3**，
> 直接验证 melody 对"白名单官方设备"的原生弹窗 UI，排除第三方适配因素干扰。

**身份定义**（`bridge/DeviceProfile.kt`，`DeviceProfiles.officialEncoX3`）：
```kotlin
val officialEncoX3 = DeviceProfile(
    id = "oppo.encox3",
    modelId = "EncoX3",
    displayName = "OPPO Enco X3",
    adapter = "official",
    protocolVersion = "official",
    serviceUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
    nameRegex = Regex("^OPPO Enco X3$", IGNORE_CASE),
    spoofProductId = 0x067410,          // 与模块统一伪装身份一致
    capabilities = ancModes(OFF/NC/TRANSPARENCY) + 左右耳/充电盒电量,
)
```
**当前源码状态**：`DeviceProfile.kt` 仍同时存在两个 `id="oppo.encox3"` 的对象：历史 `oppoEncoX3` 在 `all` 中，调试专用 `officialEncoX3` 不在 `all`。这与单一 profile 目标不一致，仍需单独清理，尚未宣称完成。

**计划目标状态**：保留 `officialEncoX3` 作为唯一调试注入 profile；清理完成后不把 OPPO Enco X3 加入第三方模块名称白名单，`DeviceProfiles.get("oppo.encox3")` 只解析到官方调试档案。

**默认常量**（`bridge/BluetoothPopup.kt` / `bridge/AdapterRegistry.kt`）：
```kotlin
AdapterRegistry.OFFICIAL_PROFILE_ID = "oppo.encox3"

BluetoothPopupContract:
  DEBUG_DEFAULT_NAME       = "OPPO Enco X3"
  DEBUG_DEFAULT_PROFILE_ID = AdapterRegistry.OFFICIAL_PROFILE_ID
  DEBUG_DEFAULT_ADDRESS    = "02:00:00:00:00:42"   // 占位，优先用真实绑定 MAC
```

**放行逻辑（关键）**：`popupDeviceFromIntent()` 原本要求 `isModuleSupportedName(name)`，
官方耳机不在模块白名单里会被丢弃。改为：
```kotlin
val explicitProfile = DeviceProfiles.get(intent.getStringExtra(EXTRA_PROFILE_ID))
val debugPreview    = intent.getBooleanExtra(EXTRA_DEBUG_PREVIEW, false)
// 官方耳机等非模块设备：仅 debug 注入路径放行，不影响真机白名单
val supported = registry.isModuleSupportedName(name) || (debugPreview && explicitProfile != null)
if (!supported) return null
```
> 只有带 `EXTRA_DEBUG_PREVIEW=true` 且显式给了 profileId 的调试注入才能注入官方耳机；
> 真机连接路径（无从伪造这两个 extra）行为不变，白名单依旧严格。

**地址策略**：注入的 MAC 优先取「当前已绑定设备的真实 MAC」（`DeviceRegistryStore.read()`），
只有在系统里**一台绑定设备都没有**时才退回 `02:00:00:00:00:42` 占位（纯 UI 预览）。
→ 满足「不丢弃真实 MAC」的硬要求。

**触发按钮**：模块 App 首页 →「注入官方耳机弹窗」（`ui/pages/OverviewPage.kt`）。
不再向 Provider 传死地址，由 Provider 侧按真实绑定 MAC 补齐。
两处注入入口（Provider `METHOD_INJECT_DEBUG_POPUP` 与 melody 进程内 debug receiver）都已改为该规则。

**identity ↔ productId 一致性（重要）**：`AdapterRegistry.spoofIdHexFor()` 已改为
`String.format("%06X", ...)`，与 `ControlTemplateSelection.DEFAULT_CONTROL_TEMPLATE_ID = "067410"`（6 位）
及 melody 原生 profile id 约定 `[0-9A-F]{6}` 对齐；弹窗投递 `tryInvokeDiscoveryPopup()` 同样用 `%06X`。
（否则 0x067410 → `"67410"` 少位，白名单/弹窗可能解析成错误型号。）

### 4.2 一键导出全部模块日志（排查用）

**动机**：跨进程（蓝牙进程 / melody 进程 / 模块 App 进程）的 `XposedBridge.log` 无法在
模块 App 进程里读取（模块**未申请 `READ_LOGS`**，manifest 只有蓝牙三权限，也无法读别的 uid 的 logcat）。
因此模块改为**自己记录结构化日志**，再一键导出成文件。

**日志来源**（`bridge/HookLogStore.kt`）：
- `HookLogStore`：环形缓冲（`MAX_ENTRIES = 500`）+ SharedPreferences 持久化（`module_hook_logs`）。
- 子进程经 ContentProvider `METHOD_APPEND_LOG`（`append_log`）写入；
- `HookLogEntry{ level(I/W/E), source(来源类/进程), message, timestamp }`。

**导出实现**（`bridge/LogExporter.kt`，新增）：
```kotlin
object LogExporter {
    fun export(context) : Result(path, text)
    fun buildDump(context) : String      // 汇总文本
    private fun writeFile(...) : String? // MediaStore.Downloads → /sdcard/Download/
}
```
导出内容 = 头部信息（时间/包名/版本/机型）+ **设备绑定 / 伪装配置**（真实 MAC、adapter、伪装的 productId hex）
+ **各作用域 Hook 激活状态** + **全部模块日志**（按时间排序）。

**文件落点**：
- Android 10+ 优先 `MediaStore.Downloads`（**无需存储权限**）→ `/sdcard/Download/MelodyPlus_logs_<yyyyMMdd_HHmmss>.txt`
- 失败回退：`Android/data/com.melody.melodyplus/files/logs/`

**调用链**：
```
AboutPage「导出全部日志」按钮
  → HookLogClient.export(context)               // 模块 App 进程
  → ContentResolver.call(URI, "export_logs")    // → Provider 进程
  → DeviceRegistryProvider.call(METHOD_EXPORT_LOGS)
  → LogExporter.export(context) → 写文件，返回 path
```
Provider 契约新增：`METHOD_EXPORT_LOGS = "export_logs"` / `EXTRA_EXPORT_PATH` / `EXTRA_EXPORT_TEXT`。

**UI**：`ui/pages/AboutPage.kt` →「Hook 日志」卡片新增「导出全部日志」按钮，导出后显示文件路径。

---

## 5. ⚠️ 蓝牙进程崩溃事故（根因 + 修复）—— 本项目最关键的一段

### 5.1 事故现象
- 蓝牙无法打开/关闭，`dumpsys bluetooth_manager` 卡在 `state: TURNING_OFF`、`enabled: false`
- `pidof com.android.bluetooth` **为空**（进程不存活）
- `Bluetooth Service not connected`
- `Bluetooth crashed 0 times`（AMS 未收到崩溃通知，误判为正常关闭 → 不自动拉起进程）
- 从崩溃起 **12h45m 内设备未重启**（截至 2026-10-06 08:15），期间 15+ 次 Enable 请求全部无效，状态无法自愈

> 📄 **完整取证报告见 `REF_Bluetooth_Stack_Incident.md`**（时间线 / 证据链 / 恢复方案 / 验证清单）。

### 5.2 崩溃现场（LSPosed verbose 日志实锤）
```
21:58:02.161  (com.android.bluetooth) SENTINEL SM.sendMessage what=8   ← AdapterState 关闭消息
21:58:02.169  E/LSPosedLogDaemon Crash unexpectedly: pkg=com.android.bluetooth, prc=com.android.bluetooth
    at com.android.bluetooth.btservice.AdapterService.cleanup(AdapterService.java:1584)
    at com.android.bluetooth.btservice.AdapterState$OffState.enter(AdapterState.java:199)
```

### 5.3 根因（已确认）
旧版代码在蓝牙进程内**伪造发送系统受保护广播 `BluetoothDevice.ACTION_ACL_CONNECTED`**。
该 action 被同进程 OPPO 定制组件 `OplusBtAudioRouteMonitor` 消费，
**污染了它的 receiver 记账**，导致蓝牙关闭进入 `OffState` 时
`AdapterService.cleanup()` 内 `unregisterReceiver` 抛 `IllegalArgumentException` → **进程崩溃**。

### 5.4 已实施修复（`hook/BluetoothAudioConnectionHook.kt`）
1. **禁止伪造 `ACTION_ACL_CONNECTED`**。弹窗链路改为只发**自定义 action**
   `com.melody.melodyplus.action.MODULE_AUDIO_CONNECTED`，并用 `setClassName()` 显式定向到 melody 的 receiver。
   源码中有明确注释：
   ```kotlin
   // 注意：禁止伪造 BluetoothDevice.ACTION_ACL_CONNECTED 系统受保护广播。
   // 该 action 会被同进程 OPPO 定制组件 OplusBtAudioRouteMonitor 消费，
   // 污染其 receiver 记账，导致 AdapterService.cleanup() 时
   // unregisterReceiver 抛 IllegalArgumentException 打崩蓝牙进程。
   ```
2. **哨兵探针默认关闭**（`SENTINELS_ENABLED = false`）。
   旧版在蓝牙 main 线程高频 `XposedBridge.log`（每次 `getName()` + 每条 StateMachine 消息），
   日志风暴拖慢状态机，**放大 AdapterState 超时→进 OffState 的概率**。

### 5.5 当前遗留问题（截至 2026-10-06 09:15 实测）
- 设备**仍未重启**，system_server 内的 `BluetoothManagerService` 卡在 `TURNING_OFF`；
  期间多次 Enable 请求全部无效，`am force-stop com.android.bluetooth` 也无效（进程本就不存在）。
- 本设备 `init.svc.bluetooth` 为空，init 配置没有 Java 蓝牙 `service bluetooth`；只有 `vendor.bluetooth-1-1-qti` HAL 服务处于 running。
  因此 `setprop ctl.restart bluetooth` 不能作为本设备的 Java 蓝牙恢复命令，单独重启 HAL 也不能解锁 manager。
- **模块自身修复已安装**，但**整条连接弹窗链路仍无法在设备上验证**（蓝牙进程不存在 → hook 无执行机会 → 连接事件无法产生）。
- ✅ **重要实测收获**：模块日志实锤 **XIBERIA MC05 曾在 10-05 成功建立 HFP 连接**
  （`module_hook_logs.xml`: `蓝牙已连接 transport=hfp name=XIBERIA MC05`）。
  → 证明崩溃发生于**关闭蓝牙**阶段，**连接链路本身曾经是通的**。
- 当前可靠恢复路径是**用户手动重启设备**（需用户确认）；重启前先备份现场，重启后执行蓝牙三轮开关回归。
- ⛔ **严禁** `ctl.restart zygote` / LSPosed 软重启 / killall zygote（设备级硬约束）
- ⚠️ 设备 `load average` 仍偏高（09:15 时现场需复测），待蓝牙栈恢复后需复查残留状态机/日志风暴副作用。

---

## 6. 构建与安装流程（Linux proot Ubuntu + 设备）

### 6.1 构建（在 Linux 环境 / Ubuntu 24 proot）
```bash
cd /root/MelodyPlus_ng
# SDK 位置见 local.properties（/opt/android-sdk 等）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 6.2 安装（跨环境，注意 SELinux）
```bash
# ❌ 直接从 /sdcard 安装会失败：system_server 无权读 /sdcard（SELinux）
# ✅ 正确：先拷到 /data/local/tmp
cp /sdcard/Download/MelodyPlus_ng_fix.apk /data/local/tmp/mp_fix.apk
chmod 644 /data/local/tmp/mp_fix.apk
/system/bin/pm install -r -d -g /data/local/tmp/mp_fix.apk
```

### 6.3 生效
- LSPosed 作用域变更后 → **热重载模块**（reload module / reload scope），
  或 `am force-stop <目标包>` 后重启该 App
- ⛔ 不允许软重启/重启框架（见全局硬约束）

### 6.4 当前已安装版本指纹
```
包名: com.melody.melodyplus
versionName: 1.0 / versionCode: 1
安装时间: 2026-10-06 08:12:47   ← 官方耳机注入 + 日志导出（最新）
```
历史版本（对比用）：
| 版本 | 文件 | md5 | 时间 |
|---|---|---|---|
| 蓝牙崩溃修复版 | `/sdcard/Download/MelodyPlus_ng_fix.apk` | 7961a55f5dddc3acc8199e3c2ae89747 | 2026-10-05 22:08 |
| 官方耳机注入版(v1) | `/sdcard/Download/MelodyPlus_ng_official.apk` | 26c2112b70d8f8fd96f02ddf11fc7b6c | 2026-10-06 08:09 |
| 官方耳机注入 + 日志导出 + hex 修正 | `/sdcard/Download/MelodyPlus_ng_official.apk` | **b302b184a1d4f010e2d87d2b3c701928** | 2026-10-06 08:12 |

> 源码工程：`/root/MelodyPlus_ng`；构建日志 `build_final.log`。

---

## 7. 关键源码地图

| 文件 | 职责 |
|---|---|
| `hook/HookEntry.kt` | 入口，按包名分发 hook |
| `hook/MelodyPanelHook.kt` | **核心**（~4000 行）：白名单伪造、DeviceInfo 注册表注入、弹窗投递、ActionManager hook、debug receiver |
| `hook/MelodyCapsuleHook.kt` | 音频胶囊触发 |
| `hook/BluetoothAudioConnectionHook.kt` | 蓝牙进程 hook，捕获连接边沿 → 发弹窗广播（**崩溃源已修**） |
| `hook/WirelessSettingsHook.kt` | 无线设置页定制 |
| `hook/HookContext.kt` | hook 基类（safeHook / hookAfter / findClass 等） |
| `bridge/AdapterRegistry.kt` | adapter ↔ 伪装 productId 路由表（单一真源：Enco X3） |
| `bridge/DeviceProfile.kt` | 设备 profile 定义（Sony / XIBERIA 全系） |
| `bridge/BluetoothPopup.kt` | 广播 action/extra 契约 + 弹窗去重 gate + 调试默认身份 |
| `bridge/DeviceRegistry.kt` | ContentProvider 实现 + 设备绑定持久化 + 注入/导出分支 |
| `bridge/HookLogStore.kt` | 结构化运行日志环形缓冲（500 条）+ prefs 持久化 |
| `bridge/HookLogClient.kt` | 模块 App 侧日志读取/清空/一键导出客户端 |
| `bridge/LogExporter.kt` | **新增**：汇总全部日志 → 导出 txt 到 /sdcard/Download/ |
| `bridge/HeadsetSessionManager.kt` | 会话管理（实时 `isAlive` + 断连重连 + 懒连接 + 缓存） |
| `bridge/DeviceImageAssets.kt` | 单图模式：`assetManagerPath()` 恒等于 `SLOT_MAIN` |
| `bridge/DeviceImageStore.kt` | 单图模式：`fileNameFor()` 恒等于 `main` |
| `adapter/sony/*` | Sony RFCOMM 协议编解码 / ANC 控制 |
| `adapter/xiberia/*` | XIBERIA cchip 协议 / 模式映射 / 伪装 |
| `scope/AutoResolver.kt` | 运行期类/方法名解析（抗混淆） |

> 已删除（2026-10-07）：`hook/ThreeDeviceLayout.kt`、`hook/BatteryBadgeView.kt`（自绘三图方案被「宿主原生槽位贴单图」替代）。

---

## 8. 相关文档

- `docs/TASK_Three_Device_Layout_All_Models.md` —— MC05 三图布局与三路电量改造指令
- `docs/PLAN_device_spoofing_multibrand.md` —— 多品牌伪装方案
- `docs/PLAN_module_settings_ui.md` —— 模块设置 UI 方案
- `docs/REF_Bluetooth_Stack_Incident.md` —— **蓝牙栈卡死事故调查报告**（时间线/证据链/已知根因/现场快照）
- `docs/REF_HuaweiMelodyCompat_RE.md` —— 华为 melody 兼容逆向
- `docs/references/sonypods/` —— Sony 耳机协议参考资料

---
## 9. 待办 / 下一步

1. 蓝牙事故现场与恢复结论保留在 `docs/REF_Bluetooth_Stack_Incident.md`；当前不再维护独立的蓝牙恢复执行计划。
2. 重启后验证蓝牙能正常开/关；执行至少 3 轮 ON/OFF，确认不再崩 `AdapterService.cleanup()`。
3. 核对 LSPosed 作用域：`com.android.bluetooth` 与 `com.oplus.melody` 都要有 Hook 激活记录。
4. **[P0]** 修复 `DeviceProfile.kt` 中两个同为 `oppo.encox3` 的 profile 重复定义，确保调试注入使用唯一的 `officialEncoX3`。
5. **[P0]** 修复 `MelodyPanelHook` 中剩余的 `Integer.toHexString()`，所有 productId hex 统一输出 6 位 `067410`。
6. **[P0]** 补齐连接弹窗链路结构化日志：连接边沿 → 广播发送 → melody Receiver → 设备解析 → `g6.j`/中继 → `p()` → Dialog 展示。
7. 用「注入官方耳机弹窗」按钮验证 melody 原生 UI；再验证 XIBERIA MC05 真机连接、弹窗和面板控制。
8. **排查手段**：模块 App「关于页 → Hook 日志 → 导出全部日志」→ 生成
   `/sdcard/Download/MelodyPlus_logs_<时间戳>.txt`，按计划文档的失败定位表判断最后一个成功节点。
9. **[可选增强]** 若需抓取真实 logcat（含蓝牙/melody 进程的 `XposedBridge.log`），
   当前模块无 `READ_LOGS` 权限；可由带 root 的外部脚本落盘，或在 Scope 进程内落盘到模块可访问目录。


---

## 10. 变更记录（Changelog）

| 时间 | 变更 | 位置 |
|---|---|---|
| 2026-10-05 22:08 | 蓝牙崩溃修复：移除伪造 `ACTION_ACL_CONNECTED`；哨兵默认关闭 | `hook/BluetoothAudioConnectionHook.kt` |
| 2026-10-06 | 新增官方耳机 profile `oppo.encox3`（OPPO Enco X3, 0x067410）并入 `all` | `bridge/DeviceProfile.kt` |
| 2026-10-06 | 调试注入默认身份改为官方耳机；新增 `DEBUG_DEFAULT_PROFILE_ID` | `bridge/BluetoothPopup.kt` |
| 2026-10-06 | 调试注入地址优先用真实绑定 MAC；默认 profileId=oppo.encox3 | `bridge/DeviceRegistry.kt` |
| 2026-10-06 | `popupDeviceFromIntent` 放行 debug 注入的官方耳机（不影响真机白名单） | `hook/MelodyPanelHook.kt` |
| 2026-10-06 | UI 调试按钮不再传死假地址；补占位图 `oppo_enco_x3.png` | `ui/pages/OverviewPage.kt`、`assets/device_images/` |
| 2026-10-06 | 调试注入改为**注入官方耳机**（oppo.encox3）+ debug 放行 | `bridge/DeviceProfile.kt`、`hook/MelodyPanelHook.kt` |
| 2026-10-06 | identity↔productId hex 统一为 `%06X`（`spoofIdHexFor` / `tryInvokeDiscoveryPopup`） | `bridge/AdapterRegistry.kt`、`hook/MelodyPanelHook.kt` |
| 2026-10-06 | **新增一键导出全部模块日志**（`LogExporter` + Provider `export_logs` + AboutPage 按钮） | `bridge/LogExporter.kt`、`bridge/DeviceRegistry.kt`、`bridge/HookLogClient.kt`、`ui/pages/AboutPage.kt` |
| 2026-10-06 | 构建并安装最新版（md5 b302b184…），设备 lastUpdateTime 08:12:47 | `/sdcard/Download/MelodyPlus_ng_official.apk` |
| 2026-10-06 | 主文档 §4.1 与真实代码对齐、新增 §4.2 日志导出、§5.5 现场更新 | `docs/REF_MelodyPlus_ng_Module.md` |
| 2026-10-06 | **新增执行指令**：MC05 左耳/右耳/耳机仓三图布局、三路电量绑定和截图验收 | `docs/TASK_Three_Device_Layout_All_Models.md` |
| 2026-10-06 | 蓝牙事故恢复结论归档到独立调查报告，不再保留单独执行计划 | `docs/REF_Bluetooth_Stack_Incident.md` |
| 2026-10-07 | **P0 命令 payload 修正**：`switch/level/soundEffect` 由 2 字节 `[0x01,value]` 改为 **单字节** `[value]`（官方 `new-array v1,0x1` 真值）→ 修复「关命令反被置开 / SET 不落地」 | `adapter/xiberia/XiberiaCommands.kt` |
| 2026-10-07 | **P0 电量间歇错值修复**：`batteryMutex` 串行化探测链 + 不足 6 字节应答返回 null + 单值不再污染 L/R/C 三槽 | `adapter/xiberia/XiberiaRfcommClient.kt`、`XiberiaFeatureBackend.kt`、`XiberiaHeadsetAdapter.kt` |
| 2026-10-07 | **P0 弹窗电量兜底补采**：弹窗发现时缓存为空即主动建会话补采三路电量，就地重放宿主 `h(IZZIZZIZZ)` 刷新原生三槽 | `hook/MelodyPanelHook.kt` |
| 2026-10-07 | **P1 周期轮询强制刷新**：`scheduleDetailBatteryFetch` 增 `force` 参数，轮询不再「电量为完整即早返回」空转 | `hook/MelodyPanelHook.kt` |
| 2026-10-07 | **P1 SPP 半死链路自愈**：零 RX 连续超时 3 次判死 → 主动关 socket 触发 `isAlive` 重连 | `adapter/xiberia/XiberiaRfcommClient.kt` |
| 2026-10-07 | **P1 会话永不重连修复**：`HeadsetAdapter` 新增实时 `isAlive`；`Session.connected` 由快照 `val` 改为计算属性；断连时先释放残留 socket 再重建 | `core/HeadsetModels.kt`、`bridge/HeadsetSessionManager.kt` |
| 2026-10-07 | **P1 详情页大图被覆盖修复**：架构改为「贴宿主原生 `normal_image` + 停宿主 Glide 回填」，删除自绘三图方案 | `hook/MelodyPanelHook.kt` |
| 2026-10-07 | **单图模式改造**：4 槽收口为单张 `main.png`（三合一图）；删除 `ThreeDeviceLayout.kt` / `BatteryBadgeView.kt` / `xiberia_mc05/{left,right,case}.png` | `bridge/DeviceImage*.kt`、`ui/pages/OverviewPage.kt`、`assets/` |
| 2026-10-07 | 本三合一修复完整记录归档 | `docs/CHANGELOG_PanelWrite_Battery_Image_Fix.md` |
