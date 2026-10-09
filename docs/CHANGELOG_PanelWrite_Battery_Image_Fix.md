# CHANGELOG · 面板写入失效 + 电量错值 + 详情页图片覆盖 三合一修复

> 日期：2026-10-07
> 实测机型：XIBERIA MC05（cchip / 西伯利亚）· 宿主 `com.oplus.melody` 16.9.1 · ColorOS
> 产出版本：`:app:assembleDebug`（46.17 MB）→ 安装于 `/data/local/tmp/mp.apk`
> 关联文档：`docs/REF_ChipDesheng_Frame_And_Codec.md` · `docs/MODULE_TECHNICAL_GUIDE.md`

---

## 现象（用户侧）

| # | 现象 | 严重度 |
|---|---|---|
| 1 | 自建面板开关「点了没反应 / 反了」——点关闭后设备仍开，点开启设备无变化 | P0 |
| 2 | 电量间歇错值：耳机仓显示 **97%**，实际约 **50%**（97 是耳机电量） | P0 |
| 3 | 详情页大图先显示模块贴的主图，**随后被宿主异步加载的 Sony 默认图覆盖** | P1 |
| 4 | 设备断连后后续所有操作**永不重连**（一直失败） | P1 |

---

## ① 命令 payload 多一字节 → 设备读首字节恒为 0x01（P0）

### 根因（真源：官方 `com.cchip.desheng` `Protocol` 逐条 smali 核对）

官方以下方法全部是 `new-array v1,0x1` → **payload 单字节**：

| 官方方法 | 命令码 | 有效载荷 |
|---|---|---|
| `setLowLatency` | `0x0C01` | 1 字节（0/1） |
| `setLDac` | `0x0E04` | 1 字节（0/1） |
| `setLHDC` | `0x0E13` | 1 字节（0/1） |
| `setVolume(dev,int)` | `0x0E26` | 1 字节（档位） |
| `soundEffect` | `0x0E0D` | 1 字节（modeValue） |

### 改点 `adapter/xiberia/XiberiaCommands.kt`

```diff
- fun switch(value: Boolean): ByteArray = byteArrayOf(0x01, if (value) 1 else 0)      // 2 字节 ❌
+ fun switch(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)             // 1 字节 ✅
- fun level(level: Int): ByteArray = byteArrayOf(0x01, level.toByte())                // ❌
+ fun level(level: Int): ByteArray = byteArrayOf(level.toByte())                      // ✅
- fun soundEffect(modeValue: Int): ByteArray = byteArrayOf(0x01, modeValue.toByte()) // ❌
+ fun soundEffect(modeValue: Int): ByteArray = byteArrayOf(modeValue.toByte())        // ✅
```

### 因果链

设备只取 payload **首字节**判定：

- 旧实现 `[0x01, value]` → 首字节恒 `0x01` → **关闭命令反被置开**；
- 且长度域 `b[9]=2` 与固件期望的 `1` 不符 → SET 不落地（官方 App 查询无变化）。

### 实测（修复后真机抓帧）

```
TX ff 03 00 07 01 08 0c 01 00 00 00 00 00 00 00   （游戏模式 置 0）
RX ff 03 00 03 01 08 0c 01 02 01 00               （设备 ACK = 0）
TX ff 03 00 07 01 08 0e 04 01 00 00 00 00 00 00   （LDAC 置 1）
RX ff 03 00 03 01 08 0e 04 02 01 01               （设备 ACK = 1）✅ 与官方 App 状态同步
```

---

## ② 电量探测三路并发 → 单值污染三态槽（P0）

### 根因

1. `queryBatteryPayload` 被 **connect 首读 / 详情页补采 / 周期轮询** 三路并发调用，共用同一条
   `responseWaiters` 队列 —— 短帧/上报帧会被**别的 waiter 认领**，偶发串值。
2. 退化路径把「不足 6 字节的单值应答」塞进 `left/right/caseBattery` 之一 → 单耳电量**污染仓位**
   → 仓显示 97%（= 耳机电量）。

### 改点

**`adapter/xiberia/XiberiaRfcommClient.kt`：** 新增 `batteryMutex`，把整条探测链**原子化**（同一时刻
只允许一次电量请求在途）；探测链全不足 6 字节 → **返回 null**（不再外泄短帧）。

```kotlin
private val batteryMutex = Mutex()   // 电量查询串行化

suspend fun queryBatteryPayload(timeoutMs: Long = RESPONSE_TIMEOUT_MS): ByteArray? =
    batteryMutex.withLock {
        var best: ByteArray? = null
        for (cmd in XiberiaCommands.BATTERY_QUERY_CHAIN) {
            val pl = sendCommand(cmd, XiberiaCommands.Payload.query(), timeoutMs) ?: continue
            log("BATTERY_PROBE cmd=0x${cmd.toString(16)} plen=${pl.size} payload=${pl.toHexString()}")
            if (pl.size >= 6) return@withLock pl
            if (best == null || pl.size > best.size) best = pl
        }
        best?.takeIf { it.size >= 6 }      // 不足 6 字节 → null
    }
```

**`adapter/xiberia/XiberiaFeatureBackend.kt`：** 退化路径 `left/right/caseBattery` 一律置 `null`，
单值只落 `single`。

**`adapter/xiberia/XiberiaHeadsetAdapter.kt`：** `readBattery()` 单值不再塞 `left` 槽。

### 实测（修复后真机抓帧）

```
RX_RAW ff 03 00 08 01 08 0a 11 02 06 00 5c 00 5e ff 32
BATTERY_PROBE cmd=0xa11 plen=6 payload=00 5c 00 5e ff 32
```

官方 6 字节三态 `[L.on][L.level][R.on][R.level][C.on][C.level]`：

**左 0x5c = 92% / 右 0x5e = 94% / 仓 0x32 = 50%** —— 仓位 50% 与用户实测一致 ✅

---

## ③ 会话 `connected` 是创建时快照 → 断连后永不重连（P1）

### 根因

`bridge/HeadsetSessionManager.kt` 的 `Session.connected` 是**创建时的 `val` 快照**。RFCOMM/SPP 被对端
关闭（或官方 App 抢占通道）后 socket 已死，但快照恒 `true` → `execute`/`readFeatureRaw`/`refresh`
永远走「复用已有会话」分支，命令全量砸在死 socket 上（`Send failed`），**从不重连**。

### 改点

**`core/HeadsetModels.kt`：** `HeadsetAdapter` 新增实时能力 `val isAlive: Boolean get() = true`。

```kotlin
interface HeadsetAdapter {
    val capabilities: HeadsetCapabilities
    /** 链路是否仍活着（实时）。默认 true，支持者覆写。 */
    val isAlive: Boolean get() = true
    ...
}
```

**`adapter/xiberia/XiberiaHeadsetAdapter.kt`：** `override val isAlive get() = client.isConnected`。

**`bridge/HeadsetSessionManager.kt`：** `Session.connected` 由快照 `val` 改为**计算属性**，
并在三处懒重连前先释放残留 socket：

```kotlin
private data class Session(
    val profile: DeviceProfile,
    val adapter: HeadsetAdapter?,
    var lastState: HeadsetState? = null,
) {
    /** 实时链路状态（不再是创建时快照）。 */
    val connected: Boolean get() = adapter?.isAlive == true
}
```

```kotlin
if (session != null) {                                  // 链路已死
    runCatching { session.adapter?.disconnect() }       // 先释放残留 socket
    sessions.remove(normalized)                         // 否则同 UUID 二次 connect 必失败
}
```

---

## ④ 详情页大图被宿主异步回填覆盖（P1）

### 根因

宿主的 `MelodyDetailModelView` 内部用 Glide 异步加载默认（Sony）大图。模块贴图**早于**该回调，
于是「模块图 → 宿主默认图」覆盖。

### 改点（`hook/MelodyPanelHook.kt`，本轮 941 行重构）

**架构变更：从「模块自绘三图容器」改为「宿主原生槽位贴单张主图」。**

| 删除（自绘方案） | 新增（原生方案） |
|---|---|
| `hook/ThreeDeviceLayout.kt`（整文件） | `replaceModuleProductImage()`：定位宿主原生 `normal_image` ImageView 贴单张主图 |
| `hook/BatteryBadgeView.kt`（整文件） | `applyProductImageTo()`：弹窗卡片主图 / 其它非 `normal_image` 图位 |
| `sweepMountThreeDevice` / `populateThreeDeviceLayout` / `suppressHostPopupResidue` 等 | `sweepMountProductImage()`：全窗口扫描 `normal_image` 逐帧贴图 |
| 三图布局 / 电量徽标自绘 | `stopDetailModelRendering()`：停宿主 `MelodyDetailModelView` 内部大图渲染 |
| — | `productDrawable()` / `slotDrawable()`：单张主图解析（用户自定义 → 内置） |

关键实现（节选）：

```kotlin
// 停宿主渲染 + 重贴 + 触发原生电量补采
if (sourceClassName == DETAIL_MODEL_VIEW_CLASS) {
    stopDetailModelRendering(source, imageView)         // 关掉宿主 Glide 回填
}
imageView.animate().cancel(); imageView.clearAnimation()
imageView.alpha = 1f; imageView.scaleType = FIT_CENTER
imageView.imageTintList = null; imageView.clearColorFilter()
imageView.setImageDrawable(replacementDrawable)
ensureImageHierarchyVisible(imageView)
...
runCatching { ensureDetailBatteryRefresh(profile) }      // 贴图后即时报电量
```

`replacingProductImage`（`ThreadLocal`）作**重入护栏**：自己触发的 `setImageDrawable` 不再被
hook 二次处理，避免死循环。

**电量上下文**：贴图后 `ensureDetailBatteryRefresh(profile)` 即时触发宿主原生电量补采；
`scheduleBatteryPolling()` 以 `BATTERY_POLL_INTERVAL_MS`（5 分钟）周期兜底刷新。

---

## ⑤ 弹窗三路电量槽与三合一图对齐（P1）

`alignPopupBatterySlots()`：发现弹窗（单设备大图版）三个电量槽需平移到三合一图（左耳 / 右耳 /
充电仓）正下方。

- 用 `imageMatrix.mapRect()` 把「图片原始像素矩形」映射到 ImageView 内实际绘制矩形 → 得到内容区
  屏幕真实 `left` / 宽度，**不依赖 `scaleType` 假设**（FIT_CENTER / CENTER_CROP 都算对）。
- 映射表：`0x7f090363→左耳` / `0x7f090362→右耳` / `0x7f090365→充电仓`。
- 每次回调先 `translationX = 0f` 清旧平移再量基准位，避免重复回调累积偏移。

---

## ⑥ 单图模式：4 槽收口为单张 `main.png`

全链路统一为「每个型号 1 张三合一主图（左耳 + 右耳 + 耳机仓已合成一张）」：

| 文件 | 改动 |
|---|---|
| `bridge/DeviceImageAssets.kt` | `assetManagerPath()` 内部恒等于 `SLOT_MAIN`，不再按槽位分图 |
| `bridge/DeviceImageStore.kt` | `fileNameFor()` 内部恒等于 `main`，`save/read/exists/delete/listSlots` 全自动收口 |
| `ui/pages/OverviewPage.kt` | 图片卡片文案改为「每个型号 1 张图」，槽位标签只剩「主图」 |
| `assets/device_images/xiberia_mc05/` | **删除** `left.png` / `right.png` / `case.png`（保留 `main.png`） |

> `slot` 形参保留仅为兼容历史调用签名。

---

## 交付物

| 类型 | 路径 |
|---|---|
| 源码 | `app/src/main/java/com/melody/melodyplus/**`（见上表改动文件） |
| 图片 | `app/src/main/assets/device_images/xiberia_mc05/main.png`（三合一合成图，1.16 MB） |
| 文档 | `docs/CHANGELOG_PanelWrite_Battery_Image_Fix.md`（本文件） |
| 文档 | `docs/MODULE_TECHNICAL_GUIDE.md`（§2.9 / §3.3 / §3.4.2 已同步） |

## 验收

```bash
./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/mp.apk
pm install -r /data/local/tmp/mp.apk
# LSPosed 作用域勾选 com.oplus.melody → 热重载模块 → am force-stop com.oplus.melody 后重连
```

观察点：

- 面板开关点击后**立即**下发，官方 App 查询状态**一致**；
- 弹窗 / 详情页三路电量：仓 = 50（与实际一致）、左 = 92、右 = 94；
- 详情页大图为模块主图，**不再被 Sony 默认图覆盖**；
- 断连后再次操作可自动重连（`isAlive` 触发会话重建）。
