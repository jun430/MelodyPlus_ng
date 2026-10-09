# PLAN · 按型号自适配的官方全功能面板 v3

> 生成时间：2026-10-07
> 目标：把「西伯利亚官方 APP（com.cchip.desheng）的所有功能」按**连接耳机的型号**动态加载进 melody 详情页。
> 前置：v2（hook 原生 `PreferenceGroup.e` 动态 add）已实测生效，MC05 面板 5 项入屏成功。

---

## 0. 本轮根因（上一轮面板只显示 5 项的真正原因）

| # | 问题 | 证据 | 修复 |
|---|---|---|---|
| 1 | 面板项**写死**在 `Mc05FeaturePanel.FEATURES`（5 项） | 源码：`FEATURES = listOf(game, ldac, bass, dual, touch)` | 改为 `XiberiaProductCatalog.panelItems(productId)` 动态生成 |
| 2 | 官方能力真值从未被读取 | 模块只有「参考模块华为兼容版」的粗粒度 `CAP_*` 位 | 从官方 APK 提取 22 能力位 × 18 型号覆写真值 |
| 3 | 无 CHOICE（多选一）型控件 | 旧面板只有布尔开关 | 新增 `Kind.CHOICE` + 原生 `AlertDialog` 多选 |

---

## 1. 官方功能真源（已全量提取 · 可复现）

**提取通道**：npmcp，`workspaceId=46691997`（`com.cchip.desheng` v1.9.26 / versionCode 109026）

```text
np_apk_open apk_path=/data/app/.../com.cchip.desheng.../base.apk
np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/Product$MC05;  workspaceId=46691997
np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/CommandId;     workspaceId=46691997
np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/SwitchType;    workspaceId=46691997
np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/NoiseMode;     workspaceId=46691997
np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/SoundEffectMode;
np_apk_read_text locator=dex_class:Lcom/cchip/desheng/rm/GainMode;
```

### 1.1 官方 22 个能力位（`Product` 基类真值）

`ai=TRUE`(基类默认) 其余全 FALSE —— 每个型号覆写自己支持的部分。

```
isSupportAI / AntiWindNoise / BassBoost / BatteryCase / ChildMode / Dongle /
DrainWater / DualDevice / HealthReminder / HiRes / InEar / LDAC(SwitchType) /
LHDC(SwitchType) / LowLatency / NoiseControl / OfflineVoice / QQMusicAudio /
ShowDeviceDialog / SpatialSound / TouchLockMode / VolumeGear / supportNoiseStyle
```

### 1.2 枚举真值

| 枚举 | 值 |
|---|---|
| `SwitchType` | `BUTTON=0, PAGE=1, NOT_SUPPORT=2` |
| `ChipType` | `BT, BT_MATE, JL` |
| `EarType` | `TWS, OWS, HEADSET` |
| `GainMode` | `VALUE_12(12段,±120)`, `VALUE_6(6段,±60)`, `VALUE_6_NEW(6段,±90)` |
| `NoiseMode.command` | `CLOSE=1, OPEN=2, TRANSPARENCY=3, ANTI_WIND_NOISE=4, COMFORT_MODE=5` |
| `SoundEffectMode.modeValue` | `KJ=0x0E, LY=0x0D, FOOT=0x0F` |

### 1.3 18 型号能力矩阵（smali 逐方法解析所得）

| 型号 | pid | chip | ear | EQ | LDAC | LHDC | 低延迟 | 低音 | 双设备 | 降噪 | 空间音频 | 触控锁 | 音量档位 | 儿童 | 离线语音 | 抗风噪 | 入耳 | 健康 | 排水 | 仓电量 | 电池图 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **MC05** | 118 | BT_MATE | OWS | 6_NEW | PAGE | – | ✔ | ✔ | ✔ | – | – | ✔ | ✔ | – | – | – | – | – | ✔ | ✔ | PAIR |
| MC20 | 112 | BT_MATE | – | 6_NEW | PAGE | – | ✔ | ✔ | ✔ | – | – | – | ✔ | – | – | – | ✔ | ✔ | ✔ | ✔ | – |
| DM03 | 113 | BT_MATE | HEADSET | 12 | PAGE | – | ✔ | – | ✔ | 3档 | – | – | – | – | – | ✔ | – | – | – | – | SINGLE |
| MC01 MAX | 114 | BT | – | 6 | PAGE | PAGE | ✔ | ✔ | ✔ | – | – | ✔ | ✔ | ✔ | ✔ | – | – | – | – | ✔ | – |
| AS10 | 115 | JL | TWS | 6 | – | BUTTON | ✔ | – | – | 3档 | ✔ | – | – | – | – | – | ✔ | – | – | ✔ | – |
| MC03 | 117 | BT | – | 6 | PAGE | PAGE | ✔ | ✔ | ✔ | – | – | – | – | – | – | – | – | ✔ | ✔ | ✔ | – |
| W30 | 116 | JL | TWS | 6_NEW | BUTTON | – | – | – | ✔ | 列表空 | – | – | – | – | – | – | ✔ | – | ✔ | ✔ | PAIR |
| MC01 | 110 | BT | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – |
| MC02 | 111 | BT | OWS | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – |

DM02BA / DM02BA_TWO / DM25 / AIR_FIT / DM01_TWO / DM01_MAX / DM02 / AIR_CLIP / DM02_BASE：
官方子类仅 3~4 方法（无能力覆写）→ 全走后端默认；MC01 / MC02 官方 `isSupport()=false`（整体不开放）。

---

## 2. 已落地代码

### 2.1 新增：`adapter/xiberia/XiberiaProductCatalog.kt`（565 行）

- 官方 22 能力位 + 4 枚举 + 18 型号覆写真值（`PRODUCTS`）
- `panelItems(productId): List<PanelItem>` —— **型号自适配的唯一判定入口**
- `PanelItem(key, title, summary, kind, cmdSet, cmdGet, choices)`，`kind ∈ {SWITCH, CHOICE}`

### 2.2 改造：`hook/MelodyPanelHook.kt`

| 新增/改动 | 说明 |
|---|---|
| `resolvePanelProductId(source)` | modelId → displayName → 旧表反查；解析不出**不猜** |
| `injectDynamicPanelIntoCategory(category, loc)` | 按 productId 生成项并 add 进原生分类（幂等、型号切换清理） |
| `installXiberiaChangeListener` | SWITCH 直接下发；CHOICE 弹 `AlertDialog` 多选后下发 |
| `requestXiberiaPanelChange` | 走 `HeadsetSessionManager.execute` → `SetSwitch(cmdSet)` 同通道 |
| `refreshXiberiaPanelStates` | 模块侧缓存回填 checked/enabled |
| `injectDynamicPanelIntoFragment` | Fragment 漏斗补注入入口（覆盖 DSEE 全部 6 个时机） |
| `scheduleMc05PanelIntoCategory` | 改调 `injectDynamicPanelIntoCategory`（弃用写死五项） |
| `XIBERIA_PANEL_ORDER_BASE = 3000` | DSEE 1000 / 旧面板 2000 / 本面板 3000 |

### 2.3 关键复用（未改动）

- `hookNativeCategoryAdd()`：hook `androidx.preference.PreferenceGroup` 的 `(Preference)→void`（R8 名 `e`）
- `addHostPreference()`：方法名候选 `{addPreference, e, f}`（上一轮已修）
- `DETAIL_PREFERENCE_FRAGMENT_CLASS = "I8.Q"`（上一轮已修，原 `O7.O` 不存在）
- 写入链：`SetSwitch(cmdSet)` → adapter → `XiberiaFeatureBackend` → `XiberiaOfficialCodec`（帧格式已官方对齐）

---

## 3. 各型号实际注入结果（Python 1:1 模拟校验）

```
型号         项数   生成的面板项（按 UI 顺序）
------------------------------------------------------------------
MC05       8    音效模式* | 低音增强 | 游戏模式 | LDAC | 双设备 | 触控锁 | 音量档位* | 自定义EQ
MC20       7    音效模式* | 低音增强 | 游戏模式 | LDAC | 双设备 | 音量档位* | 自定义EQ
DM03       7    降噪模式* | 音效模式* | 游戏模式 | LDAC | 双设备 | 抗风噪 | 自定义EQ
MC01MAX   11    音效模式* | 低音增强 | 游戏模式 | LDAC | LHDC | 双设备 | 触控锁 | 音量档位* | 离线语音 | 儿童模式 | 自定义EQ
AS10       6    降噪模式* | 音效模式* | 空间音频 | 游戏模式 | LHDC | 自定义EQ
MC03       7    音效模式* | 低音增强 | 游戏模式 | LDAC | LHDC | 双设备 | 自定义EQ
W30        3    LDAC | 双设备 | 自定义EQ
MC01/MC02  0    (官方 isSupport=false，不注入)
(* = CHOICE 多选一型，点击弹原生对话框)
```

---

## 4. 构建与部署

```
./gradlew :app:compileDebugKotlin --offline   → EXIT=0
./gradlew :app:assembleDebug      --offline   → EXIT=0
pm install -r -d /data/local/tmp/mp2.apk      → Success
```

（`/sdcard` 因 system_server 无 fuse 读权被拒 → 改走 `/data/local/tmp/`；全程**未触 zygote / 未软重启**。）

---

## 5. 待机型扩展（官方还有、当前未接 UI 的项）

| 能力 | 官方命令 | 现状 |
|---|---|---|
| `Dongle` 接收器状态 | `0x0E25` 仅查询 | catalog 已建项，`cmdSet=null` → 点击提示「无对应命令」 |
| `NoiseStyle` 降噪风格 | `0x0E1F/0x0E20` | 仅 DM03，未建 UI 项 |
| `HealthReminder` 健康提醒 | 无独立 SET | 未建项（展示型） |
| `DrainWater` 排水 | 无独立 SET（官方走专用页） | 未建项 |
| `QQMusicAudio` QQ音乐音效 | 无独立 SET | 未建项 |
| 自定义 EQ 增益曲线 | `0x0806 EQ_CUSTOM_GAIN_SET` | 现为开关；曲线编辑页待做 |
| 固件版本 | `0x0D01 VERSION_GET` | 未接（payload=ASCII） |

---

## 6. 验证关键字（logcat / LSPosed 模块日志）

```
hookNativeCategoryAdd: hooked N method(s) on androidx.preference.PreferenceGroup
NATIVE_CATEGORY_ADD key=earphone type=...
PANEL_INJECTED model=MC05 pid=0x118 items=8 created=8 refreshed=0 at native category add
XIBERIA_PANEL_SET key=melodyplus_xi_game cmd=0xc01 kind=SWITCH value=true level=null ok=true
PANEL_MODEL_SWITCH pid=... → AS10          （换耳机型号时）
PANEL_EMPTY: model=MC01 supported=false    （不开放型号）
```
