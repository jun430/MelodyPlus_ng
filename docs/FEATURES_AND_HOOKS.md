# MelodyPlus / 欧加耳机增强 —— 功能与 Hook 说明

> 作者：雨色
>
> 模块包名：`com.melody.melodyplus`　应用名：欧加耳机增强
> 目标：让第三方蓝牙耳机（XIBERIA 全系 / Sony 全系）在 ColorOS「设备空间（com.oplus.melody）」内获得
> 与官方 OPPO 耳机同等的连接弹窗、三路电量、详情页功能面板与系统连接胶囊。

---

## 一、总体结构

```
┌─ Xposed 注入层 (hook/) ──────────────────────────────────────┐
│  HookEntry            入口分派（按包名路由到各 Hook）           │
│  MelodyPanelHook      com.oplus.melody   详情页/弹窗/白名单    │
│  MelodyCapsuleHook    com.oplus.melody   系统连接胶囊（直发）   │
│  WirelessSettingsHook com.oplus.wirelesssettings  设置入口     │
│  BluetoothAudioConnectionHook com.android.bluetooth 连接边沿   │
│  SettingsLogFilterHook        com.android.settings 日志降噪    │
├─ 协议适配层 (adapter/) ──────────────────────────────────────┤
│  xiberia/  XIBERIA(cchip) SPP 协议栈：帧编解码 / 命令码 / 会话  │
│  sony/     Sony (MDR) 协议栈：帧编解码 / ANC 控制              │
├─ 跨进程桥 (bridge/) ─────────────────────────────────────────┤
│  HeadsetSessionManager 会话与地址锁；BatteryShare 电量共享      │
│  AdapterRegistry 品牌路由；DeviceRegistry Provider 数据落盘    │
│  BluetoothPopupContract 广播契约；DeviceImageStore 图片存储     │
├─ 数据真源 (adapter/xiberia) ─────────────────────────────────┤
│  XiberiaProductCatalog 18 型型号能力表（官方 Product$<NAME>）  │
│  XiberiaCommands 44 条命令码（官方 CommandId）                │
│  XiberiaEqPresets 官方 13 组 EQ 预设 + 视觉映射               │
└─ 模块界面 (ui/, Compose) ────────────────────────────────────┘
```

---

## 二、Hook 清单

### 2.1 注入入口 `hook/HookEntry.kt`

| 接口 | 说明 |
|---|---|
| `IXposedHookZygoteInit.initZygote(StartupParam)` | 记录 `startupParam.modulePath` 供 `ModuleAssets` 直读模块 APK（绕开 Android 11+ 包可见性对 Provider 的封锁）。zygote 阶段只走 LSP 日志通道，不做 binder 调用 |
| `IXposedHookLoadPackage.handleLoadPackage(lpparam)` | 按 `lpparam.packageName` 分派：melody / wirelesssettings / com.android.bluetooth / com.android.settings |

---

### 2.2 `com.oplus.melody` 进程 —— `hook/MelodyPanelHook.kt`（核心）

`onHook()` 依次安装下列 Hook 组：

| Hook 方法 | 目标类 / 方法 | 作用 |
|---|---|---|
| `hookAllDevicesConnected` | `com.oplus.melody.btsdk.api.data.DeviceInfo`、`MultiConnectInformationElement`、`EarphoneDTO`、`C9.y`（OneSpace 连接态 VO）、`Q8.a`（电量 VO）等 30+ 个 DTO 类 | 构造器与 getter 后置改值：连接态置已连接、布尔 getter 置 true、Map 连接态改写、电量 getter 回填模块电量 |
| ├ `hookConnectedTextFallback` | `Resources.getString/getText`、`Context.getString/getText`、`TextView.setText` | `melody_ui_unconnect` 资源与「未连接」文案统一替换为「已连接」 |
| ├ `hookSetCommandSuccess` | `com.oplus.melody.model.repository.earphone.O`（SetCommandState） | `getSetCommandStatus` 恒返回 0（成功），避免宿主判失败回滚 UI |
| ├ `hookDeviceControlWidgetEnable` | `DeviceControlWidget.setEnable/b/a`、`devicecontrol.c.setEnableState/setLoadingState` | 伪装设备下强制控件可用、模式项与按钮保持 enabled（防变灰） |
| └ `hookBatteryViews` / `hookBatteryDataClass` | `MelodyBatteryViews`、`MelodyStatusInfoViews`、`BatteryInfo`、`e9.b`、`O6.a` 等 | 电量视图与电量 DTO 回填三路真值 |
| `hookPanelIntent` | `com.oplus.melody.onespace.OneSpaceDetailActivity.onCreate` | 详情页进入时绑定当前设备地址 / 型号 / 会话，建立面板上下文 |
| `hookDiscoveryPopupTrigger` | 发现弹窗触发链 | 模块音频连接事件 → 拉起官方连接弹窗 |
| `hookBasicGate` | 宿主基础/隐私门槛链 | 绕过官方对第三方耳机的功能门槛判定 |
| `hookProductImages` | `MelodyDetailModelView`、`androidx.appcompat.widget.AppCompatImageView` | 详情页主图替换为模块内置/用户自定义型号图 |
| `hookOneSpacePanel` | `OneSpaceHeaderPreference`、`OneSpaceConnectPreference`、`OneSpaceListFragment` | 设备空间列表项状态与连接态 UI 接管 |
| `hookDetailDseePreference` | `I8.H`（DetailMainFragment）、`I8.Q`（DetailMainPreferenceFragment）、`H8.a` | 详情页「耳机功能」分类定位（DSEE / MC05 / 动态面板注入的挂载点） |
| `hookNativeCategoryAdd` | `androidx.preference.PreferenceGroup`（R8 后 `e`=addPreference / `g`=findPreference / `h`=getPreference） | 原生分类添加时拦截，把模块面板项插进宿主分类 |
| `hookNoisePreference` | `NoiseReductionItem`、`NoiseReductionItem$a` | 降噪项 UI 接管 |
| `hookWhitelistConfig` / `hookWhitelistFinderInject` | `WhitelistConfigDTO`、`WhitelistConfigDTO$Function`、`WhitelistConfigDTO$NoiseReductionMode` | 白名单注入：让第三方耳机被宿主「认定为支持设备」 |
| `hookNoiseData` / `hookOneSpaceNoiseVo` | `NoiseReductionInfoDTO`、`I8.x`、`s7.c`、`opsreduction.a` | 降噪数据与 VO 桥接 |
| `hookDetailNoiseItem` | `NoiseReductionItem`、`NoiseReductionButtonSeekBarView` | 详情页降噪条目交互接管 |
| `hookDetailConnectionInfo` | `ConnectionInfoItem`、`O7.b` | 详情页连接信息条目接管 |
| `hookUnsupportedFeatureViews` | 宿主不支持特性视图列表 | 隐藏官方不支持项 |
| `hookRepositoryNoops` | 宿主 repository 命令类 | 屏蔽宿主下发给第三方耳机的无效命令 |
| `hookDeviceRegistryInjection` | `DeviceInfoManager`、`DeviceInfo` | 向宿主设备信息管理器注入模块设备档案（型号 / 名称 / 电量能力） |
| `hookDiscoveryActionManagerServer` | `g6.j`（DiscoveryActionManagerServerImpl）、`com.oplus.melody.model.scan.a`（基类定位器） | 捕获发现弹窗执行入口，补齐弹窗内容 |
| `hookConnectedPopupBridge` | 连接弹窗桥 | 主进程 ↔ `:fg` 弹窗进程状态同步 |
| `hookFastProductImage` / `hookDiscoveryPopupMainImage` / `hookDiscoveryPopupImage` | `melody_app_discovery_image_view`(0x7f090377)、`MelodyCompatImageView` | 弹窗主图快速贴图（零闪帧），并隐藏宿主小图位 `melody_app_discovery_animation_iv` |
| `hookDiscoveryPopupBattery` | `melody_app_battery_tv`、`melody_app_battery_error` 等电量格资源 | 弹窗三路电量槽位写入（左耳/右耳/仓） |
| `hookDiscoveryPopupRewrite/GateDiagnostics/RouterSession/DialogLifecycle/DialogViewModel` | 发现弹窗生命周期与 ViewModel 链 | 弹窗复用与去重控制 |
| `registerDebugInjectReceiver` | 模块调试注入广播 | 支持「调试预览」拉起弹窗（默认身份 OPPO Enco X3） |

---

### 2.3 `com.oplus.melody` 进程 —— `hook/MelodyCapsuleHook.kt`

系统「连接胶囊」（ColorOS 设备空间，由 `com.heytap.mydevices` 承载）。

| 目标 | 说明 |
|---|---|
| `BluetoothBroadcastReceiver.onReceive` / `b(Context, Intent)` | 音频连接事件后置钩子，捕获设备地址与名称 |
| `z8.j`（MyDeviceInterfaceAgent）单例 `z8.j$e.a` | 取得 Agent 实例 |
| `z8.j.k(int, Bundle)` | 直发设备空间命令：`0x10007` SHOW_CAPSULE / `0x1000b` UPDATE_CAPSULE |
| 构造 `com.oplus.mydevices.sdk.linkage.CapsuleInfo`（9 参构造器）+ `device.BatteryInfo`（3 参） | 自建胶囊载荷：`deviceId = MD5(address)`、title、subTitle"已连接"、电量列表 |
| Gson `toJson` | 序列化 `CapsuleInfo` → `bundle["capsule"]` |

> 该路径**完全绕开** melody 的 11 道连接门槛链（白名单 / ssoid / RSSI / 间隔等），
> 直接向设备空间投递胶囊。混淆名随版本变化，优先走 `scope/AutoResolver` 语义指纹解析，失败回退硬编码。

---

### 2.4 `com.oplus.wirelesssettings` 进程 —— `hook/WirelessSettingsHook.kt`

| 目标 | 说明 |
|---|---|
| `C2.X`（DeviceProfilesSettings）`onCreate` / `onResume` / `onDeviceAttributesChanged` | 后置钩子按策略处理「耳机功能」入口可见性 |
| `C2.X.onPreferenceTreeClick(Preference)` | 点击 `enter_my_bluetooth_earphone_key` 时改跳转到 Melody 详情页（`com.oplus.melody.onespace.OneSpaceDetailActivity`），并按 `headphone_function_key` / `enter_my_bluetooth_earphone_key` 动态 add/hide 分类与入口 |

呈现策略：`DevicePresentationPolicy`（OFFICIAL_SUPPORTED / MODULE_SUPPORTED / UNSUPPORTED），
由 `DevicePresentationPolicyResolver.resolve(官方入口是否可见, 模块是否支持该设备名)` 决定。

---

### 2.5 `com.android.bluetooth` 进程 —— `hook/BluetoothAudioConnectionHook.kt`

| 目标 | 说明 |
|---|---|
| `com.android.bluetooth.a2dp.A2dpStateMachine` 的 nest 桥 `-$$Nest$mbroadcastConnectionState` | A2DP 连接状态跃迁边沿捕获（优先，跨类调用不被 ART 内联） |
| `A2dpStateMachine.broadcastConnectionState(int,int)` | 兜底路径（nest 桥不存在时） |
| `com.android.bluetooth.hfp.HeadsetStateMachine$HeadsetStateBase.broadcastConnectionState(BluetoothDevice,int,int)` | HFP 连接状态边沿捕获 |

`handleConnectedEdge`：非 CONNECTED 跃迁直接返回；命中后经 `BluetoothAudioPopupGate` 去重（默认 10s 窗口、
AirPods 名称排除），再向 melody 的 `BluetoothBroadcastReceiver` 发送自定义广播
`com.melody.melodyplus.action.MODULE_AUDIO_CONNECTED`。

> 注意：**不伪造** `BluetoothDevice.ACTION_ACL_CONNECTED` 系统受保护广播——
> 该 action 会被同进程 OPPO 组件 `OplusBtAudioRouteMonitor` 消费，污染其 receiver 记账，
> 导致 `AdapterService.cleanup()` 时 `unregisterReceiver` 抛异常打崩蓝牙进程。

可选哨兵探针 `SENTINELS_ENABLED`（默认关闭）：`BluetoothDevice.getName` 与 `StateMachine.sendMessage`，
仅用于排查注入是否生效。

---

### 2.6 `com.android.settings` 进程 —— `hook/SettingsLogFilterHook.kt`

| 目标 | 说明 |
|---|---|
| `Log.e(String,String)` / `Log.e(String,String,Throwable)` | 前置换值：仅抑制 `BaseBluetoothDlgPref`（LHDC vendor 值无 summary）与 `BtExtCodecCtr`（List preference is null）两类无害错误日志，返回 0 |

零结构侵入 —— 不触碰 Settings 任何 Activity / Fragment / Preference，保证设置作用域不闪退。

---

## 三、后端协议

### 3.1 XIBERIA（cchip）SPP 协议栈 `adapter/xiberia/`

传输：标准 RFCOMM SPP，UUID `00001101-0000-1000-8000-00805F9B34FB`。

| 文件 | 职责 |
|---|---|
| `XiberiaCommands.kt` | 44 条命令码表（对齐官方 `CommandId`），payload 语义工厂 |
| `XiberiaOfficialCodec.kt` | 官方帧编解码（组帧 / 解析 / CRC16-Modbus / 流式组帧器） |
| `XiberiaFrameCodec.kt` | 帧判定 / 命令码提取 / payload 截取（内部转发） |
| `XiberiaRfcommClient.kt` | SPP 连接 / 读写循环 / 应答等待 / 半死链路检测 / 上报分发 |
| `XiberiaSession.kt` | 单设备会话封装（request / close） |
| `XiberiaFeatureBackend.kt` | 会话缓存 + 查询/设置并校验 + 快照发布 |
| `XiberiaFeatureQueryPlan.kt` | 按型号能力位生成查询计划（对齐官方 `DeviceActivity.readState()`） |
| `XiberiaReportDispatcher.kt` | 设备主动上报帧分发（`*REPORT` 族） |
| `XiberiaAncCommandGate.kt` | 降噪写命令去重 / 在途 / 终态收敛 |
| `XiberiaHeadsetAdapter.kt` | 实现 `core.HeadsetAdapter` 接口 |
| `XiberiaProtocolCodec.kt` | 电量 payload 解析（三态 / 单值） |
| `XiberiaEqMath.kt` / `XiberiaEqPresets.kt` | EQ 增益换算与官方 13 组预设 |
| `XiberiaOfficialCodec` + `XiberiaCatalog` / `XiberiaModelProfiles` / `XiberiaSpoof` | 型号目录与伪装身份 |

#### 帧格式

控制 / 查询帧（`Protocol.packData(II[B)`）：

```
FF 03 00 [size-8] 01 08 [cmdHi] [cmdLo] 00 [payloadLen] [payload ... 补零到 maxLen]
```

数据帧（`Protocol.packData(I[B)`，带 CRC16/Modbus 小端尾）：

```
FF [cmd8] [size-2] [payload...] [crcLo] [crcHi]
```

- `b[3]` = `size - 8`（整帧长度回推：`b[3] + 8`）
- `b[4]=0x01`、`b[8]=0x00`（取自官方 smali `aput-byte`，禁止改动）
- `maxLen` = `CommandConstant.BASE_SEND_PACKET_SIZE` = `0x0F`
- CRC16/Modbus 表多项式 `0xA001`，跳过末 2 字节

#### 命令码（节选）

| 域 | 命令 | 码 |
|---|---|---|
| 电量 | BATTERY / BATTERY_ALT / BATTERY_REPORT(_ALT) | `0x0A01` / `0x0A02` / `0x0A11` / `0x0A12` |
| EQ | EQ_ENABLE_SET/GET、EQ_MODE_SET/GET、EQ_CUSTOM、USER_ALL_EQ_GET | `0x0801` / `0x0802` / `0x0803` / `0x0804` / `0x0806` / `0x0807` |
| 游戏模式 | GAME_MODE / GET / REPORT | `0x0C01` / `0x0C02` / `0x0C03` |
| LDAC | LDAC / GET | `0x0E04` / `0x0E05` |
| LHDC | LHDC_SET / GET | `0x0E13` / `0x0E14` |
| 双设备 | DUAL_DEVICE_SET / GET | `0x0E0B` / `0x0E0C` |
| 低音增强 | BASS_BOOST_SET / GET | `0x0E11` / `0x0E12` |
| 音效 | SOUND_EFFECT_SET / GET | `0x0E0D` / `0x0E0E` |
| 降噪 | NOISE_SET / GET / REPORT、NOISE_STYLE_SET/GET | `0x0B01` / `0x0B02` / `0x0B03` / `0x0E1F` / `0x0E20` |
| 触控 | TOUCH_SET / GET | `0x0E17` / `0x0E18` |
| 音量档位 | VOLUME_GEAR_SET / GET | `0x0E26` / `0x0E27` |
| 空间音频 | SPATIAL_SOUND_SET / GET | `0x0E0F` / `0x0E10` |
| 儿童模式 | CHILD_MODE_SET / GET | `0x0E19` / `0x0E1A` |
| 离线语音 | OFFLINE_VOICE_SET / GET | `0x0E1B` / `0x0E1C` |
| 抗风噪 | ANTI_WIND_SET / GET | `0x0E21` / `0x0E22` |
| 接收器 | DONGLE_STATE_GET_OR_REPORT | `0x0E25` |
| 按键 | ALL_KEY_GET | `0x0314` |
| 版本 | FW_VERSION | `0x0D01` |

#### payload 语义

| 工厂 | 载荷 | 说明 |
|---|---|---|
| `Payload.switch(v)` | 单字节 `[v]` | 写开关。设备只取首字节；旧实现 `[0x01, v]` 会把首字节钉死导致关闭反被置开 |
| `Payload.query()` | **空** | 查询 payload（官方 `readBattery` 传空，由 `packData` 补零到 0x0F） |
| `Payload.level(v)` | 单字节 `[v]` | 写档位 |
| `Payload.eqCustom(subCmd, gains)` | `[subCmd][gain0]…[gainN]` | 自定义 EQ（`subCmd`=0x11，段数 6 或 12） |
| `Payload.soundEffect(mode)` | 单字节 `[mode]` | 音效模式 |

#### 电量

- 解析：payload = `[L.on][L.level][R.on][R.level][C.on][C.level]`（6 字节）
- 充电位语义：`flag == 0x01` 才算充电中（`0xFF` = 不在充/未知，不能按 `!= 0` 判）
- 探测链：`0xA11 → 0xA01 → 0xA12 → 0xA02`，取第一个 ≥6 字节 payload；单轮最多 4 条，最多 2 轮 + 400ms 退避
- `queryBatteryPayload` 全程持 `batteryMutex`，防止三路并发（首读 / 详情页补采 / 周期轮询）互相认领短帧串值
- 档位过滤：仅 `[0,100]` 视为有效，`0xFF`/`0xFE` 等占位置 null 后重试补齐

#### 链路健壮性

- 连接：阻塞 `socket.connect()` 放独立子协程，外层 `withTimeoutOrNull(8s)` 超时后**从外部 close socket** 迫使抛异常退出（防 Mutex 被长期占住）
- 半死检测：连续 6 次「已发出但链路零 RX」判死并关 socket 触发重连；连接后 5s 为就绪宽限期，期内不计数
- 应答等待：按 `accept(frame)` 匹配 waiter，未被认领的帧转 `XiberiaReportDispatcher`

#### 上报分发 `XiberiaReportDispatcher`

| 种类 | 码 |
|---|---|
| BATTERY | `0x0A11` / `0x0A12` |
| GAME_MODE | `0x0C03` |
| NOISE | `0x0B03` |
| DONGLE | `0x0E25` |

用途：用户按物理键 / 摘戴耳机时设备主动推帧，模块据此同步状态（对齐官方 `Protocol.parseReceiveData` 分发）。

---

### 3.2 Sony 协议栈 `adapter/sony/`

| 文件 | 职责 |
|---|---|
| `SonyProtocol.kt` / `SonyFrameCodec.kt` / `SonyConstants.kt` | Sony MDR 帧编解码、协议版本（V1/V2）、UUID、ANC 常量、功能/载荷类型 |
| `SonyRfcommClient.kt` | Sony SPP 传输 |
| `SonyHeadsetAdapter.kt` / `SonyAncController.kt` | 适配器实现与 ANC 控制 |

---

## 四、动态功能面板

### 4.1 数据真源 —— `XiberiaProductCatalog`

18 型型号能力表（官方 `Product$<NAME>` 覆写真值），productId 区间 `0x101–0x118`：

| productId | 型号 | 关键能力 |
|---|---|---|
| 0x101 / 0x102 | DM02BA / DM02BA_TWO | 基础 |
| 0x103 | DM25 | 基础 |
| 0x104 | AIR_FIT | 基础 |
| 0x105 / 0x106 | DM01_TWO / DM01_MAX | 基础 |
| 0x107 / 0x108 / 0x109 | DM02 / AIR_CLIP / DM02_BASE | 基础 |
| 0x110 / 0x111 | MC01 / MC02 | **官方不开放**（isSupport=false） |
| 0x112 | MC20 | 低延迟 / 低音 / 双设备 / 仓电量 / HiRes / 入耳 / 健康提醒 / LDAC=PAGE / 音量档位 |
| 0x113 | DM03 | 抗风噪 / 双设备 / LDAC=PAGE / 降噪 3 档 / 噪声风格（全表唯一）/ EQ 12 段 |
| 0x114 | MC01_MAX | 低延迟 / 低音 / 双设备 / 儿童 / 离线语音 / 触控锁 / LDAC+LHDC / 音量档位 |
| 0x115 | AS10 | JL 芯片 / 降噪 3 档 / 空间音频 / LHDC=BUTTON / 入耳 |
| 0x116 | W30 | JL 芯片 / 降噪（列表空）/ 双设备 / LDAC=BUTTON |
| 0x117 | MC03 | 低延迟 / 低音 / 双设备 / 排水 / 健康提醒 / LDAC+LHDC |
| 0x118 | MC05 | BT_MATE / OWS / 低延迟 / 低音 / 双设备 / 触控锁 / 音量档位 / QQ音乐 / EQ 6 段±90 |

能力枚举：`SwitchType{BUTTON,PAGE,NOT_SUPPORT}`、`NoiseMode{CLOSE=1,OPEN=2,TRANSPARENCY=3,ANTI_WIND_NOISE=4,COMFORT_MODE=5}`、
`SoundEffect{LY=0x0D,KJ=0x0E,FOOT=0x0F}`、`GainMode{VALUE_12(12,120),VALUE_6(6,60),VALUE_6_NEW(6,90)}`、
`BatteryType{SINGLE,PAIR}`、`ChipType{BT,BT_MATE,JL}`、`EarType{TWS,OWS,HEADSET}`。

### 4.2 面板项生成 —— `XiberiaProductCatalog.panelItems(productId)`

按能力位生成面板项，顺序对齐官方详情页：

```
① 提示音选项 ▸   ② 均衡器 ▸   ③ 按键功能 ▸   ④ 漏音抑制模式/空间音效 ▸
⑤ 智能音频 ▸     ⑥ 排水功能 ▸  ⑦ 游戏模式 [开关]  ⑧ 低音增强 [开关]  ⑨ LDAC ▸
（其后按能力追加：降噪模式 / 降噪风格 / 空间音频 / LHDC / 双设备 / 触控锁 /
  离线语音 / 儿童模式 / 抗风噪 / 接收器状态）
```

- `Kind.SWITCH` → 布尔开关（`Payload.switch`）
- `Kind.CHOICE` → 多选一（写档位值）
- `Kind.PAGE` → 跳转子页（`SubPage.{PROMPT_TONE,EQ,KEY_FUNCTION,SOUND_EFFECT,SMART_AI,DRAIN_WATER,LDAC}`）
- 互斥组 `MUTEX_LDAC_GAME`：LDAC ↔ 游戏模式，开一项关另一项
- 能力位为 false 的项**不出现**；未识别 / 未开放型号返回空列表

### 4.3 命令级门控 —— `supportsCommand(productId, command)`

SET / 查询通用门控：仅当型号 `supported` 且命令出现在 `panelItems`（或属 `GENERIC_COMMANDS`：
版本 / 电量族 / EQ 族 / `0x0314`）时才放行。

### 4.4 面板注入链

```
MelodyPanelHook.hookDetailDseePreference  定位 I8.H / I8.Q（DetailMainFragment / DetailMainPreferenceFragment）
        ↓
MelodyPanelHook.hookNativeCategoryAdd     拦截 PreferenceGroup（R8: e=addPreference）
        ↓
按 型号能力 生成 preference（key 前缀 melodyplus_xi_ / melodyplus_mc05_ / melodyplus_dsee）
        ↓
order 基数：DSEE 1000 / MC05 旧面板 2000 / XIBERIA 动态面板 3000
```

`Mc05FeaturePanel.kt`：MC05 布尔功能清单（游戏模式 / LDAC / 低音增强 / 双设备 / 触控锁），
含互斥组定义与 `mutexSiblings` 联动置灰。

### 4.5 状态回读与写入

| 环节 | 实现 |
|---|---|
| 查询计划 | `XiberiaFeatureQueryPlan.planFor(productId)` → 逐项 `cmdGet`，payload 语义 BOOL / LEVEL / RAW |
| 回读 | `XiberiaFeatureBackend.queryFeatureStates(mac, productId)`；`readSwitch`（尾字节==0x01）/ `readLevel`（尾字节原值） |
| 写入 | `setFeatureSwitchAndVerify` → `XiberiaAncCommandGate.request/complete` 收敛终态 |
| 超时 | 单项 GET 2.0s（内部响应 1.5s + 余量）；前置建链 10s；写入 6s，超时清 pending 并回滚 UI 状态 |
| 去重 | 回读按 `normalizedMac|productId` 隔离，窗口 5s |
| 代际校验 | `panelGeneration + MAC + profile + productId` 任一变化即丢弃陈旧回写（`stale_result_dropped`） |

### 4.6 均衡器子页

| 文件 | 说明 |
|---|---|
| `XiberiaEqDialog.kt` | 弹窗骨架：预设卡片网格 + 编辑卡 + 重置/应用胶囊按钮 |
| `XiberiaEqCardView.kt` | 官方卡片（圆角裁切 + 渐变背景图 + 图标 + 白字 + 选中描边），`buildPresetGrid` 3 列 |
| `XiberiaEqEditorView.kt` | 多段竖条 + 频响曲线编辑器（`setUiValues` / `toGains` / `reset`） |
| `XiberiaEqAssets.kt` | 素材加载与 Bitmap 缓存（经 `ModuleAssets.readAsset` 直读模块 APK） |
| `XiberiaEqPresets.kt` | 官方 13 组预设 + `bgIndex` / `iconIndex` / 官方文案映射 |
| `XiberiaEqMath.kt` | UI 进度 ↔ 增益换算、预设按段数自适应 |

素材位于 `app/src/main/assets/xiberia_eq/`：官方 12 张渐变背景 `bg_1..12` + 12 组图标
（normal / selected 两态），共 36 个文件。

---

## 五、跨进程桥

| 组件 | 说明 |
|---|---|
| `HeadsetSessionManager` | 按归一化 MAC 分配独立 Mutex（不同地址互不阻塞）；`:fg` 弹窗进程不建链，走 `BatteryShare` |
| `BatteryShare` | 主进程电量落盘 → 广播 `ACTION_BATTERY_UPDATED` → `:fg` 刷新弹窗原生槽 |
| `AdapterRegistry` | 运行期品牌路由表（`register(id){profile->adapter}`），统一伪装 productId `0x067410`（OPPO Enco X3） |
| `BluetoothPopupContract` | 广播契约：`MODULE_AUDIO_CONNECTED` / `POPUP_EXECUTE` / `BATTERY_UPDATED` / `POPUP_SHOWING` 及 extras |
| `BluetoothAudioPopupGate` | 弹窗去重（默认 10s 窗口 + AirPods 名称排除） |
| `DeviceRegistryProvider` | authority `com.melody.melodyplus.registry`：MAC↔型号绑定、名称规则、日志、图片、调试注入的跨进程单一真源 |
| `DeviceImageStore` / `DeviceImageAssets` | 型号内置图（`assets/device_images/<id>/{main,left,right,case}.png`）与用户自定义图（`Download/MelodyPlus/images/<profileId>/<slot>.png`） |
| `HookLogStore` / `HookLogClient` / `LogExporter` | 双通道日志（LSP + 模块内）与一键导出 txt |
| `AdapterRegistry.spoofIdHexFor` | 伪装 id 固定 6 位大写十六进制（`%06X`），避免少位被下游 `parseInt` 误解析 |

---

## 六、模块界面（Compose）

| 页面 | 文件 |
|---|---|
| 主框架 | `ui/MainTabsScaffold.kt`、`ui/navigation/MainTab.kt` |
| 概览 / 设备 / 外观 / 关于 | `ui/pages/OverviewPage.kt`、`DevicesPage.kt`、`AppearancePage.kt`、`AboutPage.kt` |
| 通用组件 | `ui/components/GlassCard.kt`、`GlassNavigationBar.kt`、`WallpaperBackground.kt` |
| 主题与设置 | `ui/theme/{Color,Theme,Type}.kt`、`ui/config/UiSettings.kt`（主题模式 / 导航栏样式 / 壁纸缩放持久化） |

---

## 七、数据落盘与日志

| 项 | 位置 |
|---|---|
| MAC ↔ 型号绑定 | 模块私有目录 `files/bindings/<型号>/<MAC>`（经 Provider 读写，跨进程可见） |
| 用户自定义图片 | `Download/MelodyPlus/images/<profileId>/<slot>.png` |
| 模块日志 | Provider `append_log` / `get_logs` / `export_logs`，Logcat tag `MelodyPlus` |
| 调试注入 | Provider `inject_debug_popup`（默认身份 OPPO Enco X3，`02:00:00:00:00:42`） |

---

*文档对应仓库最新源码。协议真值均取自官方 APK（com.cchip.desheng v1.9.26）逐条提取，非推测。*
