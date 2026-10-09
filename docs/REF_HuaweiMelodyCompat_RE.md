# 参考模块 HuaweiMelodyCompat 逆向设计底稿（v2.4.0）

> 目标：自行逆向参考模块（APK，无源码），完整还原其源码逻辑，作为「XIBERIA 版等价模块」的编写依据。
> 逆向工具：npmcp（workspace 71313114）。逆向时间：2026-10-05。
> 结论：参考模块是 **legacy Xposed** 模块（`IXposedHookLoadPackage`），包名 `com.oai.huaweimelodycompat`，
> 作用域 `com.oplus.melody`，UI 用 Miuix 0.5.1 Compose，资产内嵌 3 份数据表。
> 架构 = 18 个 `install*Bridge` 分段桥 + 独立协议后端 `HuaweiFeatureBackend`（含 frame codec / ANC gate）。

---

## 0. 逆向坐标（可复现）

| 项 | 值 |
|---|---|
| APK workspace | npmcp `71313114` |
| 应用包名 | `com.oai.huaweimelodycompat` |
| dex 前缀 | `com/oai/huaweimelodycompat/` |
| xposed_init | `assets/xposed_init` → `com.oai.huaweimelodycompat.HookEntryV171MelodyBridge` |
| 入口 API | legacy Xposed：`de.robv.android.xposed.IXposedHookLoadPackage` / `IXposedHookZygoteInit` |
| UI | `SettingsActivity`（Compose + Miuix），单页 `Screen(...)` + LazyColumn 分区 |
| gradle user-home（原构建机） | `C:/Users/CHEN/Desktop/claude apk/tools/gradle/user-home`（源码**不在此设备**） |

---

## 1. 类清单（全部 `com.oai.huaweimelodycompat.*`）

### 1.1 入口与桥（HookEntryV171MelodyBridge：106 字段 / 357 方法）
| 类 | 职责 |
|---|---|
| `HookEntryV171MelodyBridge` | 入口 + 全部 hook 装配（18 个 install*Bridge）+ 大量状态缓存（静态 Map）+ `$1..$95` 匿名 `XC_MethodHook` |
| `HookEntryV13Stable` | 主进程专用兼容入口（`primaryProcess` 时 delegate 调用） |

`handleLoadPackage` 装配顺序（源码级还原）：
```
1  if packageName != "com.oplus.melody" → return
2  primaryProcess = (processName == "com.oplus.melody" || processName == null)
3  targetClassLoader = param.classLoader
4  PresentationResources.load(moduleApkPath)
5  NativeProfileCatalog.load(moduleApkPath)
6  installApplicationBridge()
7  installHuaweiConnectionLifecycle(cl)
8  installConnectedPopupBridge(cl)
9  installFunctionalTemplateBridge(cl)
10 installDeviceRegistryBridge(cl)
11 installControlCenterBatteryPolicy(cl)
12 installDetailActivity(cl)
13 installDetailViewModelBridge(cl)
14 installRepositoryBridge(cl)
15 installFitDetectionUi(cl)
16 installDtoAndVoBridge(cl)
17 installDetailControlUi(cl)
18 installDetailAncBridge(cl)
19 installAncEffectBridge(cl)
20 installLegacyAncReadbackGuard()
21 HuaweiFeatureBackend.installParserHook()
22 HuaweiFeatureBackend.setFitResultListener($2)
23 HuaweiFeatureBackend.setBatteryListener($3)
24 HuaweiFeatureBackend.setWearingListener($4)
25 installPresentationResourceBridge(cl)
26 if primaryProcess → new HookEntryV13Stable().handleLoadPackage(param)
27 log("X1720 MELODY 16.9.1 BRIDGE READY ... functionalDonor=OPPO_Enco_X3/067410 popupDefault=OPPO_Enco_Free4/068C10/color1 transport=HuaweiBackend")
```

### 1.2 协议后端（华为私有 SPP/TLV）
| 类 | 方法数 | 职责 |
|---|---|---|
| `HuaweiFeatureBackend` | 106 | 会话/session、帧编解码分发、`parseAnc/parseBattery/parseEqualizer/parseWearing/parseFitResult/...`、`setControlAndVerify/setEffectAndVerify/setFindDeviceAndVerify/setFitDetection`、`queryBattery/queryControls/queryWearing`、`updateFromBridge(...)`、`installParserHook()` |
| `HuaweiProtocolCodec` | ~20 | 纯 TLV 编解码：`command([BII)Z`、`appendTlv`、`parseBattery([B)[I`、`parseAnc([B)[I`、`parseEqualizer([B)[I`、`parseDualConnect`、`encodeExtendedEqualizerSet`、`mergeEqualizerIds`、`parseEqualizerCustomRecords` |
| `HuaweiAncCommandGate` | ~14 | ANC 命令闸状态机：`begin/complete/desiredMode/presentedMode/isInFlight/clear` + 内部类 `Start/Completion/State` |
| `HuaweiModelProfiles` | — | 型号目录：`BY_MAC`、`PRODUCTS[]`、`Profile`、`capabilities(...)`、能力位 `CAP_ANC/CAP_EQ/CAP_FIT/CAP_WEAR/CAP_FIND/CAP_DUAL/CAP_GAME/CAP_HIGH_QUALITY/CAP_BATTERY/CAP_CONTROL_CENTER/CAP_ANC_EFFECT/CAP_ZAAG_EQ` |
| `HuaweiModeMapping` | — | 模式映射（donor 模式 ↔ Huawei EQ index/协议） |
| `HuaweiWearState` | — | 佩戴态 |

### 1.3 数据/展示
| 类 | 职责 |
|---|---|
| `PresentationResources` / `PresentationRefresh` / `DetailPresentation` | 展示资源与刷新（模块 APK 内图片注入目标 app files 目录） |
| `NativeProfileCatalog` | 读 `assets/melody_template_profiles.json`，`get(id, cl)` 反射构造 `com.oplus.melody.common.data.WhitelistConfigDTO`（经 `com.oplus.melody.common.util.x.c`），正则校验 id `[0-9A-F]{6}` |
| `PopupModelCatalog`(+`Product/Variant`) / `PopupSelection` / `ControlTemplateCatalog`(+`Template`) / `ControlTemplateSelection` | 对应 3 份 assets tsv 的目录与选择 |
| `ConnectedEarphone` / `ConnectionCycleState`(+`AclTransition/PopupAttempt`) / `RuntimeStatus` | 连接周期状态机（弹窗重试、prewarm 节流） |
| `ModuleSettings` / `ModuleSettingsReceiver` / `ModuleEventReceiver` | 设置持久化 + provider/receiver 通信 |
| `DiagnosticProvider` / `DiagnosticStore` / `DiagnosticBuffer` / `OptionalDiagnostics` | 诊断日志 provider + 环形缓冲 |
| `EarphoneSettingsLauncher` | 从模块 UI 拉起目标 app 详情页 |

### 1.4 UI
| 类 | 方法数 | 职责 |
|---|---|---|
| `SettingsActivity` | 130 | Compose + Miuix 单页；`Screen(composer)` → `LazyColumn`：开关区（`change{ }`）、型号下拉（`PopupModelCatalog.Product/Variant`）、控制模板下拉（`ControlTemplateCatalog.Template`）、诊断日志区、导出 log、打开耳机设置 |
| `ComposableSingletons$SettingsActivityKt` | — | Compose 单例 lambda |

---

## 2. 目标 app（com.oplus.melody 16.9.1）hook 点（源码级还原）

### 2.1 设备注册桥 `installDeviceRegistryBridge`（已完整反出）
```
findClass("com.oplus.melody.btsdk.api.manager.DeviceInfoManager")   // managerClass
findClass("com.oplus.melody.btsdk.api.data.DeviceInfo")             // deviceInfoClass
hookAllMethods(managerClass, "f", $33(donorProductId))
hookAllMethods(managerClass, "d", $34(donorProductId))
hookAllMethods(managerClass, "c", $35(donorProductId))   // c(DeviceInfo) 入表
hookAllMethods(managerClass, "h", $36(donorProductId))
hookAllMethods(managerClass, "i", $37(donorProductId))
hookAllMethods(managerClass, "e", $38(donorProductId))
hookTargetInt(deviceInfoClass, "getDeviceConnectState", 2, false)
hookTargetInt(deviceInfoClass, "getDeviceAclConnectState", 2, false)
hookTargetInt(deviceInfoClass, "getDeviceHeadsetConnectState", 2, false)
hookTargetInt(deviceInfoClass, "getDeviceA2dpConnectState", 2, false)
hookTargetBool(deviceInfoClass, "isSupportSpp", true)
hookTargetConnectedBool(deviceInfoClass, "isInitCmdCompleted")
hookTargetConnectedBool(deviceInfoClass, "isConnected")
hookTargetConnectedBool(deviceInfoClass, "isA2dpActive")
hookTargetConnectedBool(deviceInfoClass, "isHeadsetActive")
log("X1722 Melody 16.9.1 DeviceInfoManager/DeviceInfo bridge installed")
```
> 关键常量样本：`const/16 v4, 0x2710`（=10000，传入 `$33..$38` 构造）。

### 2.2 其余桥（方法名级已确认，方法体待补录）
`installApplicationBridge` / `installHuaweiConnectionLifecycle` / `installConnectedPopupBridge` /
`installFunctionalTemplateBridge` / `installControlCenterBatteryPolicy` / `installDetailActivity` /
`installDetailViewModelBridge` / `installRepositoryBridge` / `installFitDetectionUi` /
`installDtoAndVoBridge` / `installDetailControlUi` / `installDetailAncBridge` / `installAncEffectBridge` /
`installLegacyAncReadbackGuard` / `installPresentationResourceBridge` /
`installDetailTemplateLookup(cl, id)` / `installPanelTemplateScope(cl)` / `installReactiveDetailTemplateLookup(cl)` /
`installEqualizerLabelBridge(cl)` / `hookRepositoryClass(cl,name,flag)` / `hookEqualizerModeList(cl)`

静态 hook 工具：`hookTargetBool/hookTargetInt/hookTargetConnectedBool/hookTargetControlBool/hookTargetControlInt/hookTargetBatteryBool/hookTargetBatteryInt/hookTargetMode/hookTargetNoiseMode/hookTargetEqualizerType`。

### 2.3 展示/弹窗
`showNativeConnectedPopup` / `isHuaweiConnectedPopupState` / `prepareDiscoveryIntent` / `confirmDiscoveryDelivery` /
`claimConnectionPrewarm` / `schedulePopupRetry` / `refreshPresentationActivities` / `requestSnapshotReplay` /
`repostForegroundLiveData` / `publishAuthoritativeAnc*` / `publishBatterySnapshot` / `publishDeviceCardBattery` /
`publishControlCenterBattery` / `publishNativeConnectionState` / `publishNativeWearing` / `publishFitResult`

### 2.4 协议命令派发
`executeHuaweiControl/executeHuaweiEffect/executeHuaweiFind/executeHuaweiFit`、
`queueHuaweiControl/queueHuaweiEffect/queueHuaweiMode`、`sendFeatureCommand`、`sendOperationCommand`、
`startHuaweiModeCommand`、`answerStateQuery`、`donorAncCommand(int)`、`donorModeTypeToHuaweiEq`、`donorProtocolToHuaweiEq`。

---

## 3. 资产（assets/）

| 资产 | 用途 |
|---|---|
| `melody_template_profiles.json`（38KB）| 注入 melody 白名单的原生 profile（如 OPPO Enco X3 `067410`，含 `function.noiseReductionMode/equalizerMode/callControl/...`、`supportSpp/uuid/rssi/minVersion`） |
| `melody_popup_models.tsv`（261 行）| 弹窗可用型号：`productId \t modelName \t colorId \t hasBootMp4` |
| `melody_control_templates.tsv`（17 行）| 控制中心模板：`productId \t modelName \t verifiedResourceColor` |
| `detect_music.ogg` | 佩戴检测（fit）播放音 |
| `xposed_init` | 入口类名 |
| `licenses/*` | Miuix 许可 |

---

## 4. 与 MelodyPlus_ng（本机可构建基座）的映射

> MelodyPlus_ng 已有：xiberia 协议 adapter（`XiberiaFrameCodec/XiberiaCommands/XiberiaRfcommClient/XiberiaHeadsetAdapter`）、
> 四 Tab Compose UI（`ui/pages/*` + `GlassNavigationBar`）、hook 基建（`hook/HookEntry` 等）、bridge 层（`AdapterRegistry/DeviceRegistry/DeviceProfile/HeadsetSessionManager`）。
> **它是可构建的基座**（`/opt/android-sdk` + gradlew；已产出过 `app-debug.apk`）。

| 参考模块 | → 本模块（XIBERIA 版） |
|---|---|
| `HookEntryV171MelodyBridge`（18 install*Bridge） | 保留同名分段，逐个移植；目标类字符串按 16.9.1 校准 |
| `HuaweiFeatureBackend` | → `XiberiaFeatureBackend` |
| `HuaweiProtocolCodec` | → `XiberiaFrameCodec`（已有，需对齐 command/parse 语义） |
| `HuaweiAncCommandGate` | → `XiberiaAncCommandGate` |
| `HuaweiModelProfiles` | → `XiberiaModelProfiles`（型号表来自 `XIBERIA_MODEL_TABLE.md`） |
| `HuaweiModeMapping/HuaweiWearState` | → `XiberiaModeMapping/XiberiaWearState` |
| `NativeProfileCatalog` | 保留（把 067410 白名单 profile 注入） |
| `PresentationResources` | 保留 |
| `SettingsActivity`（单页 Miuix） | → 换成 MelodyPlus 的**四 Tab** `MainTabsScaffold` |
| 3 份 assets | 复用（popup/control tsv + template json） |
| legacy Xposed API | 与 MelodyPlus_ng 现有 libxposed API 二选一（见 §5） |

---

## 5. XIBERIA 版落地情况（本次产出）

按参考模块源码逻辑，在本机 MelodyPlus_ng 基座上落地了「XIBERIA 版等价类」，全部编译通过。

### 5.1 新增源码（`app/src/main/java/com/melody/melodyplus/adapter/xiberia/`）

| 新文件 | 对应参考模块 | 说明 |
|---|---|---|
| `XiberiaAncCommandGate.kt` | `HuaweiAncCommandGate` | token 化在途命令闸，1:1 复刻 request/complete/desiredMode/presentedMode/isInFlight/clear + Start/Completion/State |
| `XiberiaFeatureBackend.kt` | `HuaweiFeatureBackend` | 会话缓存 + queryBattery/queryControls + setControlAndVerify/setToneLevelAndVerify + BatterySnapshot/ControlSnapshot/DeviceState + 监听器 |
| `XiberiaSession.kt` | （参考模块 session 语义） | 包一层 RfcommClient，暴露阻塞式 request/close |
| `XiberiaProtocolCodec.kt` | `HuaweiProtocolCodec` | parseBattery/parseDeviceCaps/parseEqualizer/command（字节替换为 cchip 协议） |
| `XiberiaModelProfiles.kt` | `HuaweiModelProfiles` | 18 型目录 + CAP_* 能力位 + BY_MAC + swap16(companyId) |
| `XiberiaModeMapping.kt` | `HuaweiModeMapping`+`HuaweiWearState` | donor modeType/protocolIndex ↔ xiberia 模式值互转 + XiberiaWearState |
| `XiberiaCatalog.kt` | `PopupModelCatalog`/`ControlTemplateCatalog`/`PopupSelection`/`ControlTemplateSelection`/`NativeProfileCatalog` | 读 3 份 assets 表 → 目录/选择；默认 popup=Enco Free4/068C10/color1，control=Enco X3/067410 |

### 5.2 移植资产（`app/src/main/assets/`）

| 资产 | 状态 |
|---|---|
| `melody_popup_models.tsv` | ✅ 261 行，与原模块一致 |
| `melody_control_templates.tsv` | ✅ 16 型号，与原模块一致 |
| `melody_template_profiles.json` | ✅ 38241 字符，与原模块**完全一致**（16 profile id，含 067410 Enco X3） |

### 5.3 复用（MelodyPlus_ng 原有）

- `adapter/xiberia/XiberiaFrameCodec.kt` / `XiberiaCommands.kt` / `XiberiaRfcommClient.kt` / `XiberiaHeadsetAdapter.kt`
- 四 Tab Compose UI：`ui/MainTabsScaffold.kt` + `ui/pages/{Overview,Devices,Appearance,About}Page.kt` + `ui/components/GlassNavigationBar.kt`
- hook 层：`hook/MelodyPanelHook.kt`（183KB，含 hookDeviceRegistryInjection / hookDiscoveryActionManagerServer / hookConnectedPopupBridge）+ `hook/HookEntry.kt`
- bridge 层：`AdapterRegistry`（DEFAULT_SPOOF_PRODUCT_ID=0x067410）+ `DeviceRegistry` + `BluetoothPopup`

### 5.4 构建状态

- SDK：`/root/Android`（build-tools 35.0.0/36.0.0 完整；`/opt/android-sdk` 的 34/36/37 均缺 aapt，勿用）
- 命令：`export ANDROID_HOME=/root/Android; ./gradlew :app:assembleDebug --offline`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（≈42MB）✅ BUILD SUCCESSFUL

### 5.5 待办（下一步）

1. ~~把 `XiberiaFeatureBackend` 接到 `HeadsetSessionManager` / `XiberiaHeadsetAdapter`~~ ✅ 已完成。
2. ~~在 `MelodyPanelHook` 里用 `XiberiaModelProfiles`（扫描命中判定）+ `XiberiaCatalog` 的 popup/control 选择替换硬编码~~ ✅ 已完成。
3. 装机实测：真机验证门禁 hook 生效 + 协议读写。
4. UI 页用 `XiberiaCatalog` 的型号下拉（对应参考模块 SettingsActivity 的 Product/Variant 下拉）。

---

## 5.6 接线落地记录（待办 1、2 完成）

### A. backend ↔ adapter 接线（待办 1）

| 改动 | 文件 | 证据 |
|---|---|---|
| 新增 `attachConnectedSession(mac, device, client)` + `writeSwitch(mac, cmd, value)` | `XiberiaFeatureBackend.kt` | +18 行 |
| 新增进程内单例 `XiberiaFeatureBackendHolder` | `XiberiaHeadsetAdapter.kt` 末尾 | +5 行 |
| `connect()`：连上后 `boundMac = device.address.uppercase()` → `attachConnectedSession` | 同上 | 证据在 diff |
| `disconnect()`：`closeExistingSession(boundMac)` → `client.disconnect()` | 同上 | — |
| `writeSwitch/setToneLevel/readBattery` 优先走 backend（`setControlAndVerify` / `setToneLevelAndVerify` / `queryBattery`），backend 未挂会话回落直写 client | 同上 | — |
| `readState()` 的 `address` 由 `""` 改为 `boundMac`（修掉原硬编码空串缺陷） | 同上 | — |

### B. 硬编码替换（待办 2）

新增桥接层 `XiberiaSpoof.kt`（hook/bridge ↔ 数据目录唯一入口）：

| 方法 | 对应参考模块 | 常量真源 |
|---|---|---|
| `defaultModelName()` | `ControlTemplateSelection.DEFAULT_CONTROL_TEMPLATE_NAME` | "OPPO Enco X3" |
| `spoofProductIdHex()` | `ControlTemplateSelection.DEFAULT_CONTROL_TEMPLATE_ID` | "067410" |
| `spoofProductId()` | 同上（十进制） | 0x067410 |
| `isSupported/capabilitiesOf/profileOf` | `HuaweiModelProfiles.capabilities` | 18 型目录 |
| `resolveSpoof(deviceName)` | `XiberiaModelProfiles.byModel` | — |

`XiberiaModelProfiles` 补 `productIdOrNull()` + `toHex6()`。

**hook 中 6 处硬编码替换**（`MelodyPanelHook.kt`）：

| 行 | 原 | 现 |
|---|---|---|
| 1483 | `AdapterRegistry.DEFAULT_SPOOF_ID_HEX` | `XiberiaSpoof.spoofProductIdHex()`；并优先 `XiberiaModelProfiles.byModel(deviceName)` 路由 |
| 1952 | `AdapterRegistry.DEFAULT_SPOOF_PRODUCT_ID` | `XiberiaSpoof.spoofProductId()` |
| 2003 | `AdapterRegistry.DEFAULT_SPOOF_PRODUCT_ID` | `XiberiaSpoof.spoofProductId()` |
| 2014 | `"OPPO Enco X3"`（字面量） | `XiberiaSpoof.defaultModelName()` |
| 2563 | `Integer.toHexString(AdapterRegistry.DEFAULT_SPOOF_PRODUCT_ID)` | `XiberiaSpoof.spoofProductIdHex()`（修掉缺零填充的 16 进制缺陷） |

`AdapterRegistry` 保留 `DEFAULT_SPOOF_*` 常量（作为 DEX 兼容锚点），注释指向 `XiberiaSpoof` 单一真源。

### C. 编译与产物

```
export ANDROID_HOME=/root/Android
./gradlew :app:assembleDebug --offline
BUILD SUCCESSFUL in 29s
产物: app/build/outputs/apk/debug/app-debug.apk （42,019,026 B, 13:50）
校验: intermediates/built_in_kotlinc/.../xiberia/XiberiaSpoof.class 存在
      intermediates/project_dex_archive/.../xiberia/ 已产出 dex
```

---

## 6. 待决策 / 风险

1. **Xposed API 形态**：参考模块用 legacy `IXposedHookLoadPackage`；MelodyPlus_ng 用 libxposed（minApi101/targetApi102）。移植需统一。
2. **协议替换面**：HuaweiFeatureBackend 的 106 方法中，真正与「华为协议字节」相关的约 30 个（parse*/set*/query*），其余（session/cache/状态机）可整体照搬。
3. **字节级内容**：HuaweiProtocolCodec 的 command/parseBattery/parseAnc 需逐条读 smali 才能 100% 复刻 → XIBERIA 版只需保留**结构**，字节替换为 XIBERIA 协议。
4. **构建环境**：本机可 build（gradlew + /opt/android-sdk）。