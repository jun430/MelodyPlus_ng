# PLAN · 详情页面板改造：清除原厂降噪操控 → 注入 XIBERIA MC05 官方功能

> 文档类型：可执行改造计划（AI 可执行硬性指令）
> 目标工程：`/root/MelodyPlus_ng`（LSPosed 模块，包名 `com.melody.melodyplus`）
> 目标宿主：`com.oplus.melody`（ColorOS 耳机 App，详情页 `DetailMainActivity`）
> 目标设备：XIBERIA MC05（cchip/BT_MATE，OWS，productId `0x118`）
> 官方协议真源：官方 APK `com.cchip.desheng`（workspace 55490541）
> 关联参考：`docs/REF_ChipDesheng_Protocol_And_Product.md`、`docs/REF_MelodyPlus_ng_Module.md`、`/root/xiberia_frida/PROTOCOL_CALIBRATED.md`
> 前置已完成：详情页三图渲染修复（跨 root 统一装配，`ThreeDeviceLayout.kt` + `MelodyPanelHook.kt`），已实测 `local=1 keep=true`、三图 `vis=0(VISIBLE)`。

---

## 0. 目标与验收

### 0.1 目标（两条，缺一不可）
1. **清除**：详情页顶部原厂「弱降噪 / 关闭 / 通透」模式条（噪声模式操控）不再显示。
2. **注入**：MC05 官方 App 的功能操控（游戏模式 / 空间音频 / LDAC / LHDC / 低音增强 / 双设备连接 / 触控功能 / 儿童模式 / 离线语音 / 音量档位 / 抗风噪 等）全部落在详情页可操作，写入走 MC05 真实 SPP 协议，回读态与真机一致。

### 0.2 验收标准（必须逐条实测）
| # | 验收项 | 判据 |
|---|---|---|
| A1 | 模式条消失 | 详情页顶部不再出现「弱降噪/关闭/通透」三项；`grep 'submitModeItems\|MODE_STRIP_REMOVED'` 日志出现移除打点 |
| A2 | 开关组出现 | 详情页设置区出现「耳机功能」分类及 ≥6 个开关项，文案为中文 |
| A3 | 写生效 | 点任一开关 → 日志 `MC05_FEATURE_SET cmd=0x???? value=? ok=true`，且耳机端音效实际变化 |
| A4 | 回读态正确 | 重新进入详情页，开关态与耳机实际态一致（不出现「开关是开、耳机是关」） |
| A5 | 无崩溃/无风控 | melody 进程无 crash，三图仍正常渲染（回归 A1 前状态） |

---

## 1. 现状定位（已取证，可直接引用）

### 1.1 模式条 = 三图下方那条 seg 控件
| 事实 | 证据 |
|---|---|
| 模式条由 `DeviceControlWidget` 渲染，数据来自模块注入的 `modeOrder` | `MelodyPanelHook.kt:3655` `submitModeItems()` → `callMethodOrNull(widget, "b", createModeItems(context))` |
| 注入内容 = 3 项降噪模式（OFF/降噪/通透） | `DetailPanelPresentationPolicy.kt:24` `modeOrder = listOf(AncMode.OFF, NOISE_CANCELLING, TRANSPARENCY)` |
| 每项 modeType：关闭=1 / 降噪=5 / 通透=2 | `DetailPanelPresentationPolicy.melodyModeType()`、`moduleModeForTag()` |
| 点击处理入口 | `MelodyPanelHook.kt:1791` `hookDetailNoiseItem()` → hook `NoiseReductionItem$a.c(...)`，最终 `executeAncCommand()` |
| 模式条 UI 文案回退值 | `MelodyPanelHook.kt:4161` `fallbackTitleForModeType()` → "关闭/降噪/通透" |

### 1.2 已有「注入开关」的成功先例（本次主手段）
| 事实 | 证据 |
|---|---|
| 模块已成功向详情页设置片段注入过开关（DSEE） | `MelodyPanelHook.kt:1316` `hookDetailDseePreference()` |
| 注入目标片段 | `DETAIL_PREFERENCE_FRAGMENT_CLASS = "O7.O"`（`MelodyPanelHook.kt:84`） |
| 注入锚点分类 | `DETAIL_DSEE_CATEGORY_KEY = "earphone"`（`MelodyPanelHook.kt:110`） |
| 控件类 | `COUI_SWITCH_PREFERENCE_CLASS = "com.coui.appcompat.preference.COUISwitchPreference"` |
| 注入时机链 | `DetailMainActivity.onCreate/onStart/onNewIntent` + `O7.G.onViewCreated` + `O7.O.r()` + `H8.a.onChanged` + `O7.O.onHiddenChanged`（多重冗余，抗时序） |
| **结论** | 注入 MC05 功能开关**不需要发明新机制**，照抄 `hookDetailDseePreference` 的骨架即可，风险最低 |

### 1.3 MC05 协议能力（官方为真源）
- 官方命令表真源：`Lcom/cchip/desheng/constant/CommandId;`（44 条）
- 模块现有 `adapter/xiberia/XiberiaCommands.kt` **多处码值错误**（详见 `REF_ChipDesheng_Protocol_And_Product.md §4`），本次必须按官方修正。
- 已实测 MC05 响应码（Frida 抓包，`/root/xiberia_frida/PROTOCOL_CALIBRATED.md`）：`0x0E04`(开关) `0x0E05`(回读) `0x0E0C` `0x0E26`(4档) `0x0E27`(3态) `0x0E17/0E18` `0x0C01/0C02` `0x0A11` `0x0D01`(版本 `V0.2.1`) `0x0E01`(序列号)。
- 帧结构（实测）：`[0]=FF [1]=03 [2]=00 [3]=len [4]=01 [5]=08 [6]=cmdHi [7]=cmdLo [8]=00(TX)/02(RX) [9]=00/01 [10..]=payload`，命令码 key = `(b[6]<<8)|b[7]`。

---

## 2. 改动清单（三块，按序执行）

### 块 ① 摘除噪声模式条（必做）

**文件**：`app/src/main/java/com/melody/melodyplus/hook/DetailPanelPresentationPolicy.kt`

**改法（二选一，推荐 A）**

- **方案 A（推荐，最小改动、语义最干净）**：把 `modeOrder` 置空，让 `submitModeItems` 提交 0 项 → 控件无内容；再在 hook 层把该控件容器 `View.GONE`。

```kotlin
// DetailPanelPresentationPolicy.kt
internal object DetailPanelPresentationPolicy {
    /** MC05 不再使用原厂降噪模式条；空列表 = 不生成任何 mode item。 */
    val modeOrder: List<AncMode> = emptyList()
    // 其余方法保留（moduleModeForTag / melodyModeType 等仍供旧路径回退，不删）
}
```

```kotlin
// MelodyPanelHook.kt · submitModeItems() 内，提交后追加隐藏
private fun submitModeItems(widget: Any?, context: Context? = contextFrom(widget), force: Boolean = false) {
    if (widget == null) return
    val selectedMode = currentAncMode(activeModuleAddress)
    if (!force && hasInjectedModeList(widget, selectedMode)) {
        forceDeviceControlWidgetEnabled(widget)
        hideModeStrip(widget)          // ← 新增
        return
    }
    submittingModeItems.set(true)
    try {
        callMethodOrNull(widget, "b", createModeItems(context))
        callMethodOrNull(widget, "setEnable", true)
        forceDeviceControlWidgetEnabled(widget)
        hideModeStrip(widget)          // ← 新增
    } finally {
        submittingModeItems.set(false)
    }
}

/** MC05：隐藏原厂降噪模式条（控件自身 GONE，不动父级其它子视图）。 */
private fun hideModeStrip(widget: Any?) {
    val v = widget as? View ?: return
    if (v.visibility == View.GONE) return
    v.visibility = View.GONE
    Log.i(TAG, "MODE_STRIP_REMOVED class=${v.javaClass.name}")
}
```

> ⚠️ 注意：`hasInjectedModeList()` 依赖 `isInjectedLayout`（要求 `items.size == modeOrder.size`）。`modeOrder` 置空后 `isInjectedLayout` 对 size 0 恒真 —— 这是可接受的（表示「已注入空」）。若发现控件反复重填，改为 `isInjectedLayout` 加 `if (modeOrder.isEmpty()) return items.isEmpty()`。

- **方案 B（可选，更彻底）**：同时 hook `NoiseReductionItem` 的挂载点，直接 `parent.removeView(this)`。仅在方案 A 出现布局残高（空白占位）时启用。

**禁止**：不要删除 `AncMode` 枚举、不要删 `executeAncCommand`（其它路径仍在用）。

---

### 块 ② 注入 MC05 功能开关组（必做，主手段）

**新文件**：`app/src/main/java/com/melody/melodyplus/hook/Mc05FeaturePanel.kt`

骨架照抄 `hookDetailDseePreference()`，只替换「开关项定义」与「点击写协议」。

```kotlin
package com.melody.melodyplus.hook

/**
 * MC05 官方功能开关面板 —— 注入详情页「耳机功能」分类。
 * 机制复刻 hookDetailDseePreference（COUISwitchPreference 注入 O7.O + 多重时机冗余）。
 */
internal object Mc05FeaturePanel {

    /** 一个功能项定义。 */
    data class Feature(
        val key: String,        // preference key（melodyplus_mc05_ 前缀，避免与宿主撞）
        val title: String,      // 中文标题
        val cmdSet: Int,        // 官方 SET 命令码
        val cmdGet: Int,        // 官方 GET 命令码
    )

    /** MC05 功能清单（顺序即 UI 顺序）。 */
    val FEATURES: List<Feature> = listOf(
        Feature("melodyplus_mc05_game",      "游戏模式",    0x0C01, 0x0C02), // LOW_DELAY_SET/GET
        Feature("melodyplus_mc05_spatial",   "空间音频",    0x0E0F, 0x0E10), // SPATIAL_SOUND_SWITCH_SET/GET
        Feature("melodyplus_mc05_ldac",      "LDAC 高音质", 0x0E04, 0x0E05), // LDAC_SET/GET
        Feature("melodyplus_mc05_lhdc",      "LHDC 高音质", 0x0E13, 0x0E14), // LHDC_SET/GET
        Feature("melodyplus_mc05_bass",      "低音增强",    0x0E11, 0x0E12), // BASS_BOOST_SET/GET
        Feature("melodyplus_mc05_dual",      "双设备连接",  0x0E0B, 0x0E0C), // DUAL_DEVICE_SET/GET
        Feature("melodyplus_mc05_touch",     "触控功能",    0x0E17, 0x0E18), // TOUCH_SET/GET
        Feature("melodyplus_mc05_child",     "儿童模式",    0x0E19, 0x0E1A), // CHILD_MODE_SET/GET
        Feature("melodyplus_mc05_voice",     "离线语音",    0x0E1B, 0x0E1C), // OFFLINE_VOICE_SET/GET
        Feature("melodyplus_mc05_antiwind",  "抗风噪",      0x0E21, 0x0E22), // ANTI_WIND_NOISE_SET/GET
    )
}
```

**接线（`MelodyPanelHook.kt`）**：在 `hookDetailDseePreference()` 已有的每个时机回调里，追加一行 `injectMc05Features(fragment, reason)`；实现体复制 `rememberAndInjectDetailDsee` 的结构，只把「一个 DSEE 开关」换成「遍历 `Mc05FeaturePanel.FEATURES` 各建一个 COUISwitchPreference」。

**点击写协议（关键）**：
```kotlin
// onPreferenceChange 回调内
val ok = XiberiaFeatureBackend
    .setControlAndVerify(mac, feature.cmdSet, newValue)   // 复用现有 backend 写+校验
    .get(1200, TimeUnit.MILLISECONDS)
Log.i(TAG, "MC05_FEATURE_SET cmd=0x${feature.cmdSet.toString(16)} value=$newValue ok=$ok")
return@onPreferenceChange ok     // 失败回弹，避免 UI 与真机态不一致
```

**初始化回读**：进入详情页时对每个 Feature 跑一次 `queryControls` 拉全量态（一次性 `0x0807` 设备能力表 + 各 GET），用结果 set 初始 checked。禁止默认 unchecked（否则违反 A4）。

---

### 块 ③ 协议层补齐（必做，否则块 ② 写不生效）

**文件 A**：`adapter/xiberia/XiberiaCommands.kt` —— 补官方码（现有错误项按 `REF §4` 逐条修正）：
```kotlin
const val SPATIAL_SOUND_SET = 0x0E0F
const val SPATIAL_SOUND_GET = 0x0E10
const val BASS_BOOST_SET    = 0x0E11
const val BASS_BOOST_GET    = 0x0E12
const val LHDC_SET          = 0x0E13
const val LHDC_GET          = 0x0E14
const val CHILD_MODE_SET    = 0x0E19
const val CHILD_MODE_GET    = 0x0E1A
const val OFFLINE_VOICE_SET = 0x0E1B
const val OFFLINE_VOICE_GET = 0x0E1C
const val ANTI_WIND_SET     = 0x0E21
const val ANTI_WIND_GET     = 0x0E22
// 修正既有：
const val GAME_MODE   = 0x0C01   // 原 0x0E11（错）
const val LDAC        = 0x0E04   // 原 0x0E0B（错）
const val DUAL_DEVICE_SET = 0x0E0B
const val DUAL_DEVICE_GET = 0x0E0C
const val BATTERY     = 0x0A11   // 原 0x0804（错）
```
> ⚠️ 修正后必须全局 grep 所有引用点（`GAME_MODE`/`LDAC`/`TRI_STATE`/`BATTERY`），确认无遗漏旧语义。

**文件 B**：`adapter/xiberia/XiberiaFeatureBackend.kt` —— `ControlSnapshot` 扩展字段（`spatialSound/bassBoost/lhdc/childMode/offlineVoice/antiWind`），`queryControls()` 补 GET，`setControlAndVerify` 的 `when(command)` 分支补全。

---

## 3. 待确认项（执行前先跑，决定最终清单）

| # | 待确认 | 探测方法 | 影响 |
|---|---|---|---|
| Q1 | MC05 对 `0x0E0F/0x0E10`（空间音频）是否响应 | 发 `0x0E10` 读，看是否回非空 payload | 不响应则从清单移除 |
| Q2 | 同上：`0x0E13/14`(LHDC) `0x0E19/1A`(儿童) `0x0E1B/1C`(离线语音) `0x0E21/22`(抗风噪) | 批量发 GET，记录应答 | 决定最终清单 |
| Q3 | `0x0E17`(触控) 是开关还是「按键映射子菜单」 | 发 `0x0E18` 读 payload 长度/结构 | 若为映射表 → 改为跳转子页，不做开关 |
| Q4 | 现有 melody 侧 MC05 profile 的 `spoofProductId` 是否与 cchip `0x118` 冲突 | 查 `AdapterRegistry.spoofIdHexFor` + `XiberiaModelProfiles.kt:86` | 影响伪装链，不改本次功能面板 |
| Q5 | 模式条隐藏后布局是否留下空白占位 | 改完截图看 | 决定是否启用块①方案 B |

**探测脚本（可写 `probe_mc05_features.sh`，走已有 SPP 会话发 GET 并抓应答日志）**：
```bash
LOG=$(ls -t /data/adb/lspd/log/modules_*.log | head -1)
grep -aE 'MC05_PROBE cmd=0x' $LOG | sed 's/.*XposedBridge,[^]]*] //' | tail -30
```

---

## 4. 执行步骤（严格按序）

```bash
# 4.1 块①+②+③ 改完后构建
cd /root/MelodyPlus_ng && rm -f app/build/outputs/apk/debug/app-debug.apk \
  && ./gradlew :app:assembleDebug --offline -q > /tmp/build.log 2>&1; echo "EXIT=$?"; tail -20 /tmp/build.log

# 4.2 拷出 + 安装
cp /root/MelodyPlus_ng/app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/MelodyPlus_debug.apk
cp /sdcard/Download/MelodyPlus_debug.apk /data/local/tmp/mp.apk && chmod 644 /data/local/tmp/mp.apk
pm install -r /data/local/tmp/mp.apk

# 4.3 热重载（force-stop 目标 App，不碰 zygote）★禁止重启 zygote
am force-stop com.oplus.melody; sleep 2
am start -n com.oplus.melody/com.oplus.melody.miniapp.MelodyMiniAppActivity >/dev/null 2>&1
# 用户路径：进入「无线耳机」详情页

# 4.4 验证（对照 §0.2 A1~A5）
LOG=$(ls -t /data/adb/lspd/log/modules_*.log | head -1)
grep -aE 'MODE_STRIP_REMOVED|MC05_FEATURE_SET|MC05_FEATURE_INIT' $LOG | sed 's/.*XposedBridge,[^]]*] //' | tail -30
```

**截图验收**：详情页顶部无模式条、设置区出现「耳机功能」开关组、点开关后耳机实际变化。

---

## 5. 风险与回滚

| 风险 | 触发条件 | 处置 |
|---|---|---|
| 模式条隐藏后仍有空白 | `DeviceControlWidget` 自身固定高度 | 启用块①方案 B（`parent.removeView`）或把容器 `layoutParams.height=0` |
| 新增开关点了无反应 | 命令码错 / MC05 不支持 | 查 `MC05_FEATURE_SET ok=false` → 跑 §3 探测 → 从清单剔除 |
| 开关态与真机不一致 | 未做初始化回读 | 补 `MC05_FEATURE_INIT` 回读 |
| 详情页打开变慢/闪 | 注入时机与宿主重填竞争 | 复用 DSEE 已有多重时机 + 节流（`DETAIL_DSEE_RETRY_DELAYS_MS` 同款） |
| melody 崩溃 | 反射字段错 / 控件构造异常 | 全程 `runCatching` 包裹，失败仅打日志不抛 |

**回滚**：`git -C /root/MelodyPlus_ng stash` 或还原 `DetailPanelPresentationPolicy.modeOrder` 为原三项 + 移除 `injectMc05Features` 调用行。改动均在模块侧，宿主 APK 未改。

---

## 6. 交付物

- [ ] `DetailPanelPresentationPolicy.kt`（modeOrder 置空）
- [ ] `MelodyPanelHook.kt`（`hideModeStrip` + `injectMc05Features` 接线）
- [ ] `Mc05FeaturePanel.kt`（新建，功能清单）
- [ ] `XiberiaCommands.kt`（官方码补齐/修正）
- [ ] `XiberiaFeatureBackend.kt`（ControlSnapshot 扩展 + GET/SET 补全）
- [ ] 验收截图（模式条消失 / 开关组出现 / 开关生效）
- [ ] 日志证据（`MODE_STRIP_REMOVED`、`MC05_FEATURE_SET ok=true`、`MC05_FEATURE_INIT`）
