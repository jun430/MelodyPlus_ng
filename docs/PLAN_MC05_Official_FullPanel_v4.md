# PLAN · v4 · 面板 1:1 仿官方 APP 列表 + EQ 滑块子页 + 漏音抑制模式/空间音效

> 生成时间：2026-10-07
> 目标：把宿主详情页「耳机功能」面板做成**官方 App（com.cchip.desheng）列表的等价物**，
>       并按连接型号动态加载；MC05 的 EQ 由「布尔开关」升级为**官方式多段竖向滑块**；
>       「音效模式」按官方截图改名为「**漏音抑制模式/空间音效**」。
> 真源：官方 APK v1.9.26 / versionCode 109026（npmcp workspace 10348326，本轮实读）
> 前置：v3（XiberiaProductCatalog + 动态注入）已实测生效，MC05 面板入屏成功。

---

## 0. 本轮三条新需求（用户定调）

| # | 需求 | 现状 | 目标 |
|---|---|---|---|
| 1 | **EQ 子页面** | 面板项是 `Kind.SWITCH`（开/关 0x0801） | 官方式「6 条竖向 SeekBar + 频响曲线」，点开子页编辑并下发 `0x0806` |
| 2 | **音效模式** | 名为「音效模式」，CHOICE 三选（KJ/LY/FOOT） | 改名「**漏音抑制模式/空间音效**」，MC05 仅 KJ/LY 两项互斥 |
| 3 | **面板列表** | 8 项，顺序/命名自拟 | **1:1 对齐官方列表**（见 §3），按型号动态裁剪 |

---

## 1. 官方列表（截图实拍，MC05）

```
提示音选项 ▸
均衡器 ▸                     ← 自定义 EQ（滑块子页）
按键功能 ▸
漏音抑制模式/空间音效  [漏音抑制模式] ▸   ← SOUND_EFFECT，MC05 专属双模式
  切换到均衡器模式，可关闭漏音抑制模式/空间音效
智能AI ▸
排水功能 ▸
游戏模式                 [开/关]
低音增强                 [开/关]
LDAC ▸
```

**结论**：官方列表 = 布尔开关（游戏模式/低音增强）+ **跳转子页项**（提示音/均衡器/按键/漏音·空间/AI/排水/LDAC）。

---

## 2. 官方协议真值（本轮新增提取 · 可复现）

### 2.1 `EQ_CUSTOM_GAIN_SET` 下发格式（真源 `Protocol.setCustomEq`）

```
Protocol.setCustomEq(dev, cmd=0x0806, subCmd, int[] data):
    bb = ByteBuffer.allocate(data.size + 1)
    bb.put((byte) subCmd)            // subCmd = DefaultEqGain.CUSTOM_1.getCommand()
    bb.put(byte[](data))             // 每分量截断为 1 字节
    sendData(dev.address, 0x0806, maxLen=0x0F, bb.array())
⇒ payload = [subCmd][gain0][gain1]…[gainN]   (整帧 maxLen 补齐 0x0F)
```

- `DefaultEqGain$CUSTOM_1` 构造第三参数 = **`0x11`（17）** ⇒ `subCmd = 0x11`（**待真机验证**）。
- 下发后再 `switchEq(CUSTOM_1.command)` + `Delay(100ms)`（见 `EqViewModel$setCustomEq$1`）。

### 2.2 EQ 段数与增益范围（真源 `Product$MC05.getCustomEqMode()` + `GainMode.<clinit>`）

| 型号 | GainMode | 段数(cardinal) | 增益上限(maxValue) |
|---|---|---|---|
| **MC05** | **VALUE_6_NEW** | **6** | **±90** |
| MC20 / W30 | VALUE_6_NEW | 6 | ±90 |
| DM03 | VALUE_12 | 12 | ±120 |
| MC01_MAX / AS03 / AS10 | VALUE_6 | 6 | ±60 |

> ⚠️ 修正：旧文档 `REF_ChipDesheng_Protocol_And_Product.md §2.1` 写 MC05=VALUE_12 —— **错**。
> 官方 smali 真值 `sget-object GainMode->VALUE_6_NEW`。XiberiaProductCatalog 现有值正确。

### 2.3 竖条进度 ↔ 耳机增益换算（真源 `EqComputeUtils$Companion`）

```
uiValuesToEqGain(ui[], mode):
  VALUE_6     : gain[i] = (ui[i] / 2 + cardinal) * 5
  VALUE_6_NEW : gain[i] = (ui[i] / 2 + cardinal) * 5 + 0x1E(30)   ← MC05 走这条
  其他(VALUE_12): gain[i] = (ui[i] + cardinal) * 5
其中 cardinal: VALUE_6=6, VALUE_6_NEW=6, VALUE_12=12
```

**MC05 展开**：`gain = (ui/2 + 6)*5 + 30`；ui ∈ [0, 180] ⇒ gain ∈ [60, 510]？
（此式含偏移，逆向值域待真机校准；UI 侧直接照抄官方公式，不做自创。）

### 2.4 「漏音抑制模式/空间音效」= `SOUND_EFFECT`（`0x0E0D` / `0x0E0E`）

官方**没有**独立的「漏音抑制」命令。两者同属 `SoundEffectMode`：

| 枚举 | `modeValue` | 官方文案 | MC05 |
|---|---|---|---|
| `SOUND_EFFECT_LY` | **`0x0D`** | 降低漏音 / 漏音抑制模式 | ✅ |
| `SOUND_EFFECT_KJ` | **`0x0E`** | 空间音效 | ✅ |
| `SOUND_EFFECT_FOOT` | `0x0F` | 脚步增强 | ❌（MC05 不含） |

- `Product$MC05.getSoundEffectItems()` 真值 = `[SOUND_EFFECT_KJ, SOUND_EFFECT_LY]`（**仅 2 项**）。
- 关闭语义：官方文案「**切换到均衡器模式，可关闭漏音抑制模式/空间音效**」⇒ 关闭 = 切 EQ 模式，**不是** 独立开关。
  （具体关闭命令待真机抓帧确认；候选：`EQ_MODE_SET 0x0803` / `SOUND_EFFECT_SET 0x0E0D` 带关闭值。）

### 2.5 其余子页命令归属

| 官方列表项 | 命令 / 承载 |
|---|---|
| 提示音选项 | 待定（候选 `VOLUME_GEAR 0x0E26/0x0E27`，语义为「音量档位」；旧模块曾误名「提示音档位」） |
| 均衡器 | `EQ_ENABLE 0x0801/0x0802` + `EQ_MODE 0x0803/0x0804` + `EQ_CUSTOM_GAIN 0x0806` / `USER_ALL_EQ_GET 0x0807` |
| 按键功能 | `ALL_KEY_GET 0x0314`（全按键查询）；写入待定 |
| 漏音抑制模式/空间音效 | `SOUND_EFFECT_SET 0x0E0D` / `GET 0x0E0E` |
| 智能AI | `isSupportAI`（MC05=true）；无独立 SET，展示/联动型 |
| 排水功能 | `isSupportDrainWater`（MC05=true）；无独立 SET（官方走专用动画页，疑似纯本地） |
| 游戏模式 | `LOW_DELAY_SET 0x0C01` / `GET 0x0C02` |
| 低音增强 | `BASS_BOOST_SET 0x0E11` / `GET 0x0E12` |
| LDAC | `LDAC_SET 0x0E04` / `GET 0x0E05` |

### 2.6 官方 `CommandId` 全量表（44 条，本轮 smali 实读）

```
ALL_KEY_GET=0x314        ANTI_WIND_NOISE_SET/GET=0xE21/0xE22
BASS_BOOST_SET/GET=0xE11/0xE12     BATTERY_INFO_GET/REPORT=0xA11/0xA12
CHILD_MODE_SET/GET=0xE19/0xE1A     DONGLE_STATE=0xE25
DUAL_DEVICE_SET/GET=0xE0B/0xE0C    EQ_CUSTOM_GAIN_SET=0x806
EQ_ENABLE_SET/GET=0x801/0x802      EQ_MODE_SET/GET=0x803/0x804
LDAC_SET/GET=0xE04/0xE05           LHDC_SET/GET=0xE13/0xE14
LOW_DELAY_SET/GET/REPORT=0xC01/0xC02/0xC03
NOISE_SET/GET/REPORT=0xB01/0xB02/0xB03
NOISE_STYLE_SET/GET=0xE1F/0xE20    OFFLINE_VOICE_SET/GET=0xE1B/0xE1C
SOUND_EFFECT_SET/GET=0xE0D/0xE0E   SPATIAL_SOUND_SWITCH_SET/GET=0xE0F/0xE10
TOUCH_SET/GET=0xE17/0xE18          USER_ALL_EQ_GET=0x807
VERSION_GET=0xD01                  VOLUME_GEAR_SET/GET=0xE26/0xE27
```

---

## 3. 改造清单（落地）

### 3.1 `XiberiaProductCatalog.kt`
- [ ] `PanelItem` 新增 `Kind.PAGE`（跳转子页）与 `subPage: SubPage?`
- [ ] `panelItems(productId)` 顺序/命名 1:1 对齐官方（§1）
- [ ] 新增 `EqConfig(cardinal, maxValue, subCmd)`，MC05 取 `VALUE_6_NEW` ⇒ `EqConfig(6, 90, 0x11)`
- [ ] `SoundEffect` 增加 `label`（LY=「漏音抑制模式」/ KJ=「空间音效」）
- [ ] 补 `promptTone`(提示音选项) / `keyFunction`(按键功能) / `ai`(智能AI) / `drainWater`(排水功能) 面板项

### 3.2 EQ 滑块子页（新增）
- [ ] `hook/EqEditorDialog.kt`：宿主进程内全屏 Dialog
  - 6 条竖向 SeekBar（`android.widget.SeekBar` + rotation，或自绘 View）
  - 频响曲线 `LineView`（自绘 Path）
  - 拖动结束 → `uiValuesToEqGain` → `SetEqCustom(subCmd=0x11, gains)` 下发 `0x0806`
  - 预设列表（`DefaultEqGain` 各枚举）可选
- [ ] `HeadsetCommand` 新增 `SetEqCustom(subCmd: Int, gains: IntArray)`（`[subCmd][gain…]` payload）

### 3.3 漏音抑制模式/空间音效
- [ ] 面板项改名 + `SoundEffect` 标签本地化
- [ ] CHOICE 弹窗（或子页）二选一：漏音抑制模式 / 空间音效
- [ ] 关闭语义待真机确认后补

### 3.4 注入层 `MelodyPanelHook.kt`
- [ ] `Kind.PAGE` 项 → 点击 `startActivity`/弹 Dialog（EQ 走 Dialog）
- [ ] 面板项构造：PAGE 项用 `COUIJumpPreference`（若无则 `COUISwitchPreference` 隐藏开关 + 点击监听）

---

## 4. 验证关键字
```
PANEL_INJECTED model=MC05 pid=0x118 items=<N> ...
XIBERIA_PANEL_SET key=melodyplus_xi_eq_custom cmd=0x806 kind=EQ level=null ok=true
EQ_DIALOG_OPEN cardinal=6 max=90 subCmd=0x11
EQ_APPLY gains=[..] ok=true
```

## 5. 待真机校准项
- [ ] `EQ_CUSTOM_GAIN_SET` 的 `subCmd=0x11`（推定）
- [ ] `uiValuesToEqGain` 值域（含 +30 偏移语义）
- [ ] 「关闭漏音抑制/空间音效」的确切命令
- [ ] 提示音选项 / 按键功能 的写入命令
