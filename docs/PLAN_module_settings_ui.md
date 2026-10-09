# MelodyPlus 模块配置 UI — 任务确认与收口计划（v3）

> 依据 SonyPods 参考 + 用户三条反馈（后端路由 / 四 Tab 统一 / 关于页日志）更新。

## 1. 关于 SonyPods 参考（小米适配索尼）

已完整拉取到 `/root/SonyPods_ref`（GitHub: Mercury000/SonyPods，HyperOS 系统级 Sony 耳机控制模块）。

**其「小米适配索尼」核心打法（对我们的参考价值）：**

| SonyPods 特性 | 机制 | 对我们参考点 |
|---|---|---|
| 型号伪装 | 将 Sony 耳机伪装为受支持的小米耳机，接入系统耳机 UI（`SettingsHeadsetHook`、`BluetoothUpstreamHeadsetHook`、`MiBluetooth*`） | 我们已做 Melody(`com.oplus.melody`)面板注入，可借鉴其「伪装为白名单机型」策略 |
| 系统设置深度注入 | 蓝牙耳机详情页注入固件版本/音质徽标/精简降噪条（`SettingsRenderHook`、`SettingsSymbols`） | 我们的 `MelodyPanelHook` 可参考其 Symbol 反射定位方式 |
| 电量注入 | 电量实时同步系统蓝牙栈（`Bluetooth*` hook + `MiuiStrongToast`） | 我们的电量上报通道可对齐 |
| QuickAccess/手势/EQ | Tandem(GATT/RFCOMM) 协议实现 | 我们已有 SonyAdapter/XiberiaAdapter，可对照其协议引擎 |
| 型号图片自动匹配 | `SonyModelImageCatalog` + `PodImagePrefs`（不提供自定义入口） | 我们外观页壁纸是自定义入口，二者定位不同 |

**其 UI 层的统一做法（已采纳进本工程）：**
- 全 App 用 **Miuix** 组件体系，背景统一 `appBackground() = MiuixTheme.colorScheme.surface`，各 Tab 背景层共享这一个色值。
- 底部导航、主题/壁纸/底栏设置均有现成的分页实现，可对照。

## 2. 用户反馈①：后端路由有添加吗

**结论：跨进程「后端路由」已存在 = ContentProvider。**

- Manifest 已声明 `DeviceRegistryProvider`（authority `com.melody.melodyplus.registry`，exported=true）。
- `RegistryContract` 定义 URI + 方法：`GET_BINDING / GET_NAME_RULES / REPORT_STATUS / GET_STATUS`。
- `DeviceRegistryProvider.call()` 已实现方法分发；Hook 进程经 `contentResolver.call` 上报，UI 读取。
- ❗ 若你指的是「日志/更多数据的上报路由」——**尚未添加**，见 §4 计划。

## 3. 用户反馈②：四 Tab 界面不统一 —— ✅ 已修复

根因（排查确认）：三个背景本质一致，差异在**卡片色与组件体系错位**：
- 概览/设备/关于 → Miuix `Card` + `MiuixTheme.surfaceContainer`
- 外观 → Material3 `Card` + `MaterialTheme.surface`（纯白 0xFFFFFF）

**已修复**：外观页整体收口到 Miuix（`Card`/`Slider`/`Switch`/`TextButton`/`SegmentedOption`），卡片统一 `MiuixTheme.surfaceContainer`，四 Tab 现共用同一组件体系与色板。已编译 + 打包并推送手机。

## 4. 用户反馈③：关于页没有 Hook 日志功能 —— ✅ 已实现

已完成「Hook 日志」功能，跨进程路由 + UI 展示闭环：

1. **数据模型** `bridge/HookLogStore.kt`：`HookLogEntry(level/source/message/timestamp)`，Provider 侧 SharedPreferences 环形缓冲（上限 500），JSON 序列化持久化（重启保留）。
2. **路由扩展** `DeviceRegistry.kt`（`RegistryContract` + `DeviceRegistryProvider`）：新增 `append_log / get_logs / clear_logs` 三个 method。
3. **Hook 接入** `HookContext.reportLog()`：各 Hook 在关键路径上报
   - `BluetoothAudioConnectionHook`：蓝牙已连接
   - `MelodyPanelHook`：面板 Intent、发现/已连接弹窗
   - `WirelessSettingsHook`：页面呈现策略
4. **UI** `AboutPage.kt` 新增 `HookLogsCard`：级别筛选（全部/信息/警告/错误）+ 刷新 + 清空 + 来源/时间/级别徽标。
5. UI 侧客户端 `bridge/HookLogClient.kt`：读/清 Provider。

## 5. 本期收口任务状态

| 项 | 状态 |
|---|---|
| 页面骨架（Tab/Pager/底栏） | ✅ |
| 外观页（壁纸/透明度/缩放/液体玻璃/主题） | ✅ |
| 配置持久化 + 重启恢复 | ✅ |
| 后端路由（ContentProvider 跨进程） | ✅ 已具备 |
| **四 Tab 界面统一（→Miuix）** | ✅ |
| **关于页 Hook 日志功能** | ✅ 本版完成 |
| APK 构建 + 推送手机 | ✅ `/sdcard/Download/MelodyPlus-debug.apk` |

## 6. 文件改动清单（本版）

| 文件 | 改动 |
|---|---|
| `bridge/HookLogStore.kt` | 新增：日志数据模型 + Provider 侧存储（环形 500 + 持久化） |
| `bridge/HookLogClient.kt` | 新增：UI 侧读/清日志客户端 |
| `bridge/DeviceRegistry.kt` | `RegistryContract`+`Provider` 加 append_log/get_logs/clear_logs |
| `hook/HookContext.kt` | 新增 `reportLog()` 上报通道 |
| `hook/BluetoothAudioConnectionHook.kt` | 蓝牙连接上报 |
| `hook/MelodyPanelHook.kt` | 面板 Intent/弹窗上报 |
| `hook/WirelessSettingsHook.kt` | 呈现策略上报 |
| `ui/pages/AboutPage.kt` | 新增 `HookLogsCard`（筛选/刷新/清空/徽标） |
| `ui/pages/AppearancePage.kt` | 收口 Miuix + `surfaceContainer` 统一 |
| `ui/pages/MainTabsScaffold.kt` | （未改，沿用） |

## 7. 下一步
- 安装 `MelodyPlus-debug.apk`，在 LSPosed 重启作用域后回归：四 Tab 观感统一、外观页壁纸/透明度/底栏/主题生效、关于页 Hook 日志显示蓝牙连接/面板事件并持久化。
- 后续可对齐 SonyPods：型号伪装、Settings 深度注入、电量注入（见 §1 参考表）。