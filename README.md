# 欧加耳机增强

> **作者：雨色** · **LSPosed 模块**：让第三方蓝牙耳机（Sony 全系 / XIBERIA 全系 / 可扩展更多品牌）在 ColorOS 上被 `com.oplus.melody`（OPPO 耳机 App）当作**官方耳机**接管，从而获得
> **原生连接弹窗 / 系统音频胶囊 / 原生详情页控制面板**。
>
> **一句话说明：让第三方耳机支持 OPPO 系统蓝牙弹窗。**
>
> 手法 = **协议层 + UI 层双向伪装**，全部运行在 ART 层（Xposed hook），**不改宿主的 APK**。
> 详情页控制面板按**连接耳机的型号动态自适配**，读写走耳机**真实私有蓝牙协议**（RFCOMM/SPP）。

---

## ✨ 功能特性

| 能力 | 说明 |
|---|---|
| **设备伪装** | 把任意品牌耳机伪装成 OPPO Enco X3（`productId 0x067410`）注入 melody 注册表，骗过识别链路。 |
| **原生弹窗 / 胶囊** | 触发 ColorOS 原生「发现新设备」弹窗与系统「设备空间」胶囊（耳机图片 + 电量 + 设置入口）。弹窗首次出现若缓存为空，**主动建会话补采三路电量**后就地刷新原生槽。 |
| **动态功能面板** | 在宿主详情页**原生「耳机设置」分类**内注入功能面板；项集合由耳机型号的官方能力真值决定，**非写死**。 |
| **原生主图替换** | 给宿主详情页**原生大图槽位**贴模块主图（单张三合一图：左耳 + 右耳 + 仓），并停宿主 Glide 回填，**不会被默认图覆盖**。 |
| **真实状态回读** | 面板注入后逐项发 GET 命令回读耳机真实状态；**无应答保持原值，绝不写假**（严格区分「设备说关」与「没问到」）。 |
| **链路自愈** | 5 分钟周期电量轮询（兼作 SPP 心跳）+ **半死链路检测**：连续 3 次「已发出零 RX」即判死关 socket，触发上层自动重连。 |
| **多协议后端** | `HeadsetAdapter` 抽象 + 运行期可注册路由表 `AdapterRegistry`，一套代码对接 Sony（v1/v2）与 XIBERIA/cchip（v1）。 |
| **命令互斥联动** | 如官方 LDAC ↔ 游戏模式互斥：开一项自动关同组其它项并逐个校验应答。 |
| **结构化日志** | 全链路打点（`HOOK_READY` / `PANEL_INJECTED` / `XIBERIA_PROBE*` / `XIBERIA_PANEL_SET` …），可一键导出。 |

**已适配型号**

- **XIBERIA（cchip / 西伯利亚）**：MC05 / MC03 / MC20 / MC01 MAX / MC01 / MC02 / DM03 / AS10 / W30 / DM02 系列 / AIR 系列 等 18 型。
- **Sony**：WF / WH / WI / LinkBuds 系列 20+ 型号（含 XM3–XM6）。

---

## 🧩 技术概览

| 项 | 值 |
|---|---|
| applicationId | `com.melody.melodyplus` |
| 模块类型 | Legacy Xposed（`IXposedHookLoadPackage` + `IXposedHookZygoteInit`） |
| Xposed 入口 | `com.melody.melodyplus.hook.HookEntry` |
| minSdk / targetSdk / compileSdk | 35 / 36 / 37 |
| 作用域 | melody 系列 + wirelesssettings 系列 + `com.android.bluetooth` + `com.android.settings` |
| 跨进程通道 | ContentProvider `com.melody.melodyplus.registry` |

**架构分层**

```
Hook 层 (hook/*)          注入宿主方法 / 动态面板 / 弹窗 / 胶囊
   │
Bridge 层 (bridge/*)      DeviceProfile 型号档案 · AdapterRegistry 协议路由 · HeadsetSessionManager 会话
   │
Core 层 (core/*)          HeadsetAdapter 接口 · HeadsetCommand 通用命令模型
   │
Adapter 层 (adapter/*)    Sony / XIBERIA(cchip) 帧编解码 + RFCOMM 会话
   │
传输层                    RFCOMM(SPP) ▸ 物理耳机
```

> 📖 **深入原理请看 [`docs/MODULE_TECHNICAL_GUIDE.md`](docs/MODULE_TECHNICAL_GUIDE.md)** —— Hook 注入（hook add）、详情页动态面板注入、后端多协议匹配三大机制的完整技术白皮书。

---

## 📚 文档导航（docs/）

| 文档 | 内容 |
|---|---|
| **[`MODULE_TECHNICAL_GUIDE.md`](docs/MODULE_TECHNICAL_GUIDE.md)** | **技术白皮书**：Hook 注入 / 动态面板注入 / 后端多协议匹配（推荐先读） |
| `REF_MelodyPlus_ng_Module.md` | 模块主文档：身份、Hook 分发、伪装机制、弹窗链路、源码地图 |
| `CHANGELOG_2026-10-07_PopupBattery_And_Link_Fix.md` | **最近修复**：弹窗电量兜底补采 / 周期轮询强制刷新 / SPP 半死链路自愈 |
| `CHANGELOG_PanelWrite_Battery_Image_Fix.md` | 面板写入失效 / 电量错值 / 详情页图片覆盖 三合一修复（含真机抓帧证据） |
| `CHANGELOG_MC05_Official_Alignment.md` | MC05 官方协议对齐 + 图片接入 |
| `REF_ChipDesheng_Frame_And_Codec.md` | cchip 帧格式 / CRC / 电量语义（官方真值） |
| `REF_ChipDesheng_Protocol_And_Product.md` | 官方命令码表 / 产品能力真值 |
| `PLAN_XIBERIA_AutoAdaptive_Panel_v3.md` | 按型号自适配面板方案 |
| `PLAN_MC05_Official_FullPanel_v4.md` | 面板 1:1 仿官方 + EQ 子页 |
| `PLAN_device_spoofing_multibrand.md` | 多品牌伪装方案 |
| `REF_Bluetooth_Stack_Incident.md` | 蓝牙栈事故取证（含根因与恢复） |

---

## 🛠 构建

**前置**：Android SDK、JDK 17、Gradle（随 wrapper）。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

**安装**（LSPosed / KernelSU 环境）：

```bash
# ⚠ 不能直接从 /sdcard 安装：SELinux 会拒绝 system_server 读 /sdcard
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/mp.apk
chmod 666 /data/local/tmp/mp.apk
pm install -r /data/local/tmp/mp.apk
# 生效：在 LSPosed 中勾选作用域并热重载模块；或 am force-stop 目标包后重启目标 App
# ⛔ 请勿软重启 / ctl.restart zygote / killall zygote
```

**配置**：在 LSPosed 中启用模块，作用域勾选 `com.oplus.melody`（＋ 蓝牙 / 无线设置 / 系统设置可选），重启目标 App。

---

## 🔍 调试

```bash
# 模块结构化日志
cat /data/data/com.melody.melodyplus/shared_prefs/module_hook_logs.xml
# 模块 App → 关于页 → 导出全部日志

# logcat
logcat -d | grep -E ' MelodyPlus:|MelodyPlusRFCOMM:|MelodyPlusPanel:'

# 蓝牙状态
dumpsys bluetooth_manager | head -30
```

---

## 🤝 参与贡献

欢迎提交 **Issue** 与 **Pull Request**！本项目为公开仓库，所有人均可 Fork 并提交 PR。

**贡献方向**：新增耳机型号 / 新增协议族（新品牌）/ 面板 UI 改进 / 文档修正。

**开发流程**：

1. Fork 本仓库并克隆到本地；
2. 新建分支：`git checkout -b feat/your-feature`；
3. 遵循现有代码风格（Kotlin，UTF-8 无 BOM），关键改动补充 `docs/` 说明；
4. 提交前跑通 `./gradlew :app:assembleDebug` 与 `./gradlew :app:testDebugUnitTest`；
5. 提交 PR，描述清楚**动了什么、为什么、如何验证**。

**扩展新品牌 / 新型号**（详见技术白皮书 §7）：

- **加 XIBERIA 新型号**：在 `XiberiaProductCatalog.PRODUCTS` 增加 `ProductCapabilities`，并在 `DeviceProfiles` 加 `xiberiaProfile(...)`。
- **加全新协议族**：新建 `adapter/<brand>/*HeadsetAdapter` 实现 `HeadsetAdapter` → 在 `DeviceProfiles` 建 `DeviceProfile` → `AdapterRegistry.register("<brand>") { ... }`。

**如需成为协作者（直接推送权限）**：请在 Issue 中留下你的 GitHub 用户名与贡献意向，维护者会邀请你加入。

---

## ⚠️ 免责声明

本项目仅供**个人在自有设备上进行技术研究与学习**之用，用于让你自有的第三方耳机获得更好的系统集成体验。
请勿将其用于任何违反当地法律法规或侵犯他人权益的场景。使用本项目产生的一切后果由使用者自行承担。

---

## 📄 许可证

本项目采用 **GNU General Public License v3.0 (GPL-3.0)** 开源，详见 [LICENSE](LICENSE)。
你可自由使用、修改、分发，但**衍生作品必须以相同许可（GPL-3.0）开源**。
