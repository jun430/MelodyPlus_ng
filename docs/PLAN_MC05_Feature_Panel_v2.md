# PLAN v2 · 详情页面板改造：删原厂降噪条 → 注入 MC05 官方真实功能子集

> 文档类型：可执行改造计划（AI 可执行硬性指令）· **v2 已按官方 APK 逆向真值表校正**
> 目标工程：`/root/MelodyPlus_ng`（LSPosed 模块，`com.melody.melodyplus`）
> 目标宿主：`com.oplus.melody`（ColorOS 耳机 App，详情页）
> 目标设备：XIBERIA MC05（chip `BT_MATE`，ear `OWS`，productId `0x118`）
> **协议真源**：官方 APK `com.cchip.desheng` v1.9.26（workspace 54913688）
> 逆向证据：`Lcom/cchip/desheng/constant/CommandId;`、`Product$MC05`、`Product`、`LDACActivity`、`DeviceViewModel`、`bluetrum...LDACRequest`
> 实测交叉验证：`/root/xiberia_frida/sniffer_full.log`（官方 App PID 16905 全程 SPP）

---

## 0. 核心结论（先看这个）

### 0.1 「弱降噪/关闭/通透」必须删 —— 有硬证据
- 官方 `Product$MC05` **未 override `isSupportNoiseControl()`** → 继承基类 `const/4 v0,0x0; return v0` = **false**
- 官方 `Product$MC05` **未 override `getNoiseModeList()`** → 继承基类 **`return null`**
- 抓包全程 **`0x0B01`(NOISE_SET) 出现 0 次**、`0x0B02`(NOISE_GET) 0 次
- **结论**：MC05 无降噪能力。melodyplus 模块往详情页注入的降噪模式条=**错误注入**（对 OWS 型号发降噪命令，耳机不应答）。删掉是**纠正**，不是裁剪。

### 0.2 MC05 官方真实功能子集（唯一依据 `Product$MC05` override 真值）
| 功能 | MC05 支持 | SET 码 | GET 码 | 备注 |
|---|---|---|---|---|
| 低延迟 / 游戏模式 (`isSupportLowLatency`) | ✅ true | `0x0C01` | `0x0C02` | 报告 `0x0C03` |
| 低音增强 (`isSupportBassBoost`) | ✅ true | `0x0E11` | `0x0E12` | |
| 双设备连接 (`isSupportDualDevice`) | ✅ true | `0x0E0B` | `0x0E0C` | |
| 触控锁 (`isSupportTouchLockMode`) | ✅ true | `0x0E17` | `0x0E18` | |
| LDAC 高音质 (`isSupportLDAC=PAGE`) | ✅ **子页面** | `0x0E04` | `0x0E05` | ⚠️ 见 §0.3 |
| 音量档位 (`isSupportVolumeGear`) | ✅ true | `0x0E26` | `0x0E27` | 4 档，实测 `0x0E26 pl=01 val=00..03` |
| 音效档位 (`getSoundEffectItems=[KJ,LY]`) | ✅ 2 项 | `0x0E0D` | `0x0E0E` | `KJ=14, LY=13` |
| 自定义 EQ (`getCustomEqMode=VALUE_6_NEW`) | ✅ 6 段 | `0x0806` | `0x0807` | 开关 `0x0801/0x0802`，模式 `0x0803/0x0804` |
| Hi-Res (`isSupportHiRes`) | ✅ true | （同 LDAC 链路） | | |
| 排水 (`isSupportDrainWater`) | ✅ true |  | | |
| 电池仓显示 (`isSupportBatteryCase` + `showBatteryType=PAIR`) | ✅ true |  | | 电量 `0x0A11` 实测 |
| **降噪 / 通透** (`isSupportNoiseControl`) | ❌ **false** | — | — | **须删** |
| 空间音频 (`isSupportSpatialSound`) | ❌ false | — | — | 不注入 |
| LHDC (`isSupportLHDC`) | ❌ `NOT_SUPPORT` | — | — | 不注入 |
| 儿童模式 (`isSupportChildMode`) | ❌ false | — | — | 不注入 |
| 离线语音 (`isSupportOfflineVoice`) | ❌ false | — | — | 不注入 |
| 抗风噪 (`isSupportAntiWindNoise`) | ❌ false | — | — | 不注入 |
| 耳内检测 (`isSupportInEar`) | ❌ false | — | — | 不注入 |

### 0.3 LDAC ↔ 游戏模式 互斥（用户实测确认）
- 官方入口：`LDACActivity`（`ActivityLdacBinding.imgClLdac`），点击 → `DeviceViewModel.switchLDAC(!isSelected)` → `Protocol.sendData(dev, 0x0E04, Boolean)`
- 互斥逻辑：`if (getLdacMode()==true)` → 走 `show10SecondDialog()`（提示"LDAC 与双设备/游戏冲突"）+ `switchLDAC()`；否则 `openLDAC()`。
- 另存 `DeviceActivity.showCloseLDACHintDialog`（关 LDAC 也弹提示）。
- **实现要求**：面板里 LDAC 与游戏模式必须做**双向互斥 UI 联动**（开一个自动置灰另一个 + 发对应命令），否则违反 §0.4 A4。

### 0.4 协议调用约定（官方真源，模块侧必须照抄）
```java
// 官方统一入口：cmdKey 来自 CommandId；布尔开关传 Boolean，档位传对应对象
Protocol.INSTANCE.sendData(BluetoothDevice dev, int cmdKey, Object value)
// 例：switchLDAC(Z) → sendData(dev, 0x0E04, Boolean.valueOf(z))
```
全量状态读取：`ALL_KEY_GET = 0x0314`（**不是 0x0807**；`0x0807` = `USER_ALL_EQ_GET`，仅 EQ）。

---

## 1. 完整 CommandId 官方真源表（v1.9.26，44 条）

```kotlin
// 模块侧 XiberiaCommands.kt 应以此为准（覆盖旧的错误码值）
const val ALL_KEY_GET = 0x0314
const val NOISE_SET = 0x0B01;  const val NOISE_GET = 0x0B02;  const val NOISE_REPORT = 0x0B03
const val LOW_DELAY_SET = 0x0C01; const val LOW_DELAY_GET = 0x0C02; const val LOW_DELAY_REPORT = 0x0C03
const val VERSION_GET = 0x0D01
const val LDAC_SET = 0x0E04;   const val LDAC_GET = 0x0E05
const val DUAL_DEVICE_SET = 0x0E0B; const val DUAL_DEVICE_GET = 0x0E0C
const val SOUND_EFFECT_SET = 0x0E0D; const val SOUND_EFFECT_GET = 0x0E0E
const val SPATIAL_SOUND_SET = 0x0E0F; const val SPATIAL_SOUND_GET = 0x0E10
const val BASS_BOOST_SET = 0x0E11; const val BASS_BOOST_GET = 0x0E12
const val LHDC_SET = 0x0E13;   const val LHDC_GET = 0x0E14
const val TOUCH_SET = 0x0E17;  const val TOUCH_GET = 0x0E18
const val CHILD_MODE_SET = 0x0E19; const val CHILD_MODE_GET = 0x0E1A
const val OFFLINE_VOICE_SET = 0x0E1B; const val OFFLINE_VOICE_GET = 0x0E1C
const val NOISE_STYLE_SET = 0x0E1F; const val NOISE_STYLE_GET = 0x0E20
const val ANTI_WIND_SET = 0x0E21; const val ANTI_WIND_GET = 0x0E22
const val DONGLE_STATE = 0x0E25
const val VOLUME_GEAR_SET = 0x0E26; const val VOLUME_GEAR_GET = 0x0E27
const val EQ_ENABLE_SET = 0x0801; const val EQ_ENABLE_GET = 0x0802
const val EQ_MODE_SET = 0x0803;   const val EQ_MODE_GET = 0x0804
const val EQ_CUSTOM_GAIN_SET = 0x0806; const val USER_ALL_EQ_GET = 0x0807
const val BATTERY_INFO_GET = 0x0A11; const val BATTERY_INFO_REPORT = 0x0A12
```
> 旧 `XiberiaCommands.kt` 的错误项：`GAME_MODE(原0x0E11→改0x0C01)`、`LDAC(原0x0E0B→改0x0E04)`、`BATTERY(原0x0804→改0x0A11)`、`TRI_STATE(0x0E27)`。改完全局 grep 引用点。

---

## 2. 改动清单（三块）

### 块 ① 摘除噪声模式条（依据 §0.1）
- 文件：`hook/DetailPanelPresentationPolicy.kt` → `modeOrder = emptyList()`
- 文件：`hook/MelodyPanelHook.kt` → `submitModeItems()` 内追加隐藏 + 打点：
```kotlin
private fun hideModeStrip(widget: Any?) {
    val v = widget as? View ?: return
    if (v.visibility == View.GONE) return
    v.visibility = View.GONE
    Log.i(TAG, "MODE_STRIP_REMOVED class=${v.javaClass.name}")
}
```
- 删除范围：**仅注入路径**。`AncMode` 枚举、`executeAncCommand` 若被其它设备类型共用则保留（grep 确认后在无人调用时再删）。

### 块 ② 注入 MC05 功能开关组（照抄 `hookDetailDseePreference` 骨架）
新文件 `hook/Mc05FeaturePanel.kt`，清单 = §0.2 的 ✅ 项（**只做 true/PAGE 项**）：
```kotlin
val FEATURES = listOf(
    Feature("melodyplus_mc05_game",    "游戏模式",  0x0C01, 0x0C02, kind = SWITCH),
    Feature("melodyplus_mc05_bass",    "低音增强",  0x0E11, 0x0E12, kind = SWITCH),
    Feature("melodyplus_mc05_dual",    "双设备连接",0x0E0B, 0x0E0C, kind = SWITCH),
    Feature("melodyplus_mc05_touch",   "触控锁",    0x0E17, 0x0E18, kind = SWITCH),
    Feature("melodyplus_mc05_ldac",    "LDAC 高音质",0x0E04, 0x0E05, kind = MUTEX_WITH("melodyplus_mc05_game")),
    Feature("melodyplus_mc05_volgear", "音量档位",  0x0E26, 0x0E27, kind = GEAR(4)),
    Feature("melodyplus_mc05_soundfx", "音效",      0x0E0D, 0x0E0E, kind = GEAR(2, KJ=14, LY=13)),
    Feature("melodyplus_mc05_eq",      "自定义EQ",  0x0801, 0x0802, kind = SWITCH),
)
```
- 点击写协议：`XiberiaFeatureBackend.setControlAndVerify(mac, cmdSet, value)` → 打点 `MC05_FEATURE_SET cmd=0x.. value=.. ok=..`；`ok=false` 回弹（`return false`）。
- **初始化回读**：进入详情页先发 `ALL_KEY_GET(0x0314)` + 各 GET，按回读值 set 初始态（禁止默认 unchecked，A4 要求）。
- **互斥联动**：LDAC 与游戏模式互斥（§0.3），开一置灰另一并同步发命令。

### 块 ③ 协议层对齐（否则块②写不生效）
- `adapter/xiberia/XiberiaCommands.kt`：按 §1 全量校正。
- `adapter/xiberia/XiberiaFeatureBackend.kt`：`ControlSnapshot` 加 `lowLatency/bassBoost/dualDevice/touchLock/ldac/volumeGear/soundEffect/eq`；`queryControls()` 改发 `0x0314` 全量读；`setControlAndVerify` 按 §1 补 `when` 分支。

---

## 3. 验收（逐条实测）

| # | 验收项 | 判据 |
|---|---|---|
| A1 | 降噪条消失 | 详情页无「弱降噪/关闭/通透」；日志 `MODE_STRIP_REMOVED` |
| A2 | 开关组出现 | 出现 ≥5 项：游戏模式/低音增强/双设备/触控锁/LDAC |
| A3 | 写生效 | 点开关 → `MC05_FEATURE_SET ok=true` 且耳机端行为变化 |
| A4 | 回读态正确 | 重进页面态与真机一致；LDAC↔游戏互斥联动正确 |
| A5 | 无回归 | 三图仍渲染、melody 无 crash |

---

## 4. 执行步骤

```bash
# 构建
cd /root/MelodyPlus_ng && rm -f app/build/outputs/apk/debug/app-debug.apk \
  && ./gradlew :app:assembleDebug --offline -q > /tmp/build.log 2>&1; echo "EXIT=$?"; tail -20 /tmp/build.log
# 拷出+安装
cp /root/MelodyPlus_ng/app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/MelodyPlus_debug.apk
cp /sdcard/Download/MelodyPlus_debug.apk /data/local/tmp/mp.apk && chmod 644 /data/local/tmp/mp.apk
pm install -r /data/local/tmp/mp.apk
# 热重载（NOT zygote；★禁止重启 zygote）
am force-stop com.oplus.melody; sleep 2
# 验证
LOG=$(ls -t /data/adb/lspd/log/modules_*.log | head -1)
grep -aE 'MODE_STRIP_REMOVED|MC05_FEATURE_SET|MC05_FEATURE_INIT' $LOG | sed 's/.*XposedBridge,[^]]*] //' | tail -30
```

---

## 5. 风险 / 回滚
| 风险 | 处置 |
|---|---|
| 模式条隐藏留白 | 启用 `parent.removeView` 或 `layoutParams.height=0` |
| LDAC 切换触发断连 | 官方也是断连重连；保留 `show10SecondDialog` 式提示 |
| 开关 no-op | 查 `ok=false` → 核对 §1 码值 |
| 崩溃 | 全 `runCatching` 包裹，失败只打日志 |

**回滚**：`git -C /root/MelodyPlus_ng stash`；改动全在模块侧，宿主未改。

---

## 6. 交付物
- [ ] `DetailPanelPresentationPolicy.kt`（modeOrder 置空）
- [ ] `MelodyPanelHook.kt`（hideModeStrip + injectMc05Features 接线）
- [ ] `Mc05FeaturePanel.kt`（新建，§0.2 清单）
- [ ] `XiberiaCommands.kt`（§1 官方码校正）
- [ ] `XiberiaFeatureBackend.kt`（0x0314 全量读 + 分支补全）
- [ ] 日志证据 `MODE_STRIP_REMOVED` / `MC05_FEATURE_SET ok=true` / `MC05_FEATURE_INIT`
- [ ] 截图：降噪条消失 / 功能开关组出现 / 开关生效