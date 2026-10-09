# CHANGELOG · 弹窗电量补采 + 轮询强制刷新 + SPP 半死链路自愈

> 日期：2026-10-07
> 实测机型：XIBERIA MC05（cchip / 西伯利亚）· 宿主 `com.oplus.melody` 16.9.1 · ColorOS
> 产出版本：`:app:assembleDebug`（46.17 MB，`BUILD SUCCESSFUL in 31s`）→ `/data/local/tmp/mp_battery_fix.apk`
> 关联文档：`docs/MODULE_TECHNICAL_GUIDE.md` · `docs/REF_ChipDesheng_Frame_And_Codec.md`

---

## 现象（用户侧）

| # | 现象 | 严重度 |
|---|---|---|
| 1 | **发现弹窗三路电量槽恒显示 `--`**：弹窗先于 SPP 会话 / 详情页出现时，宿主原生槽拿不到值 | P0 |
| 2 | 周期电量轮询**实际空转**：已有值后既不建会话也不刷新，电量长时间不更新 | P1 |
| 3 | SPP 链路**半死**（已发出但恒超时）时不触发重连，`socket.isConnected` 仍为 `true` | P1 |

---

## ① 弹窗原生电量槽「连接即查」兜底补采（P0）

### 根因

上一轮把弹窗电量从「模块自绘徽标」统一收口为**宿主原生三槽渲染**（`h(IZZIZZIZZ)`）。
但原生槽渲染依赖 `detailBatteryCache` 里**已有值**：

- 弹窗进程若先于 SPP 会话建立 / 详情页打开而出现，缓存恒空 → 三个原生槽停在 `--`；
- 弹窗没有详情页那套「贴图 / 轮询」触发时机，`hookBefore` 注入拿不到值就直接 `return`。

### 改点 `hook/MelodyPanelHook.kt`

**新增三件套（弹窗兜底补采 + 就地重放）：**

```kotlin
/** 最近一次弹窗三路电量 ViewHolder（WeakReference）；补采成功后据此在主线程重放 h(...)。 */
private var lastPopupBatteryHolder = java.lang.ref.WeakReference<Any>(null)

/** 弹窗电量补采在途地址集合（去重：避免每次 h() 回调都触发一次建会话）。 */
private val popupBatteryInFlight = ConcurrentHashMap.newKeySet<String>()

private fun requestPopupBatterySync(address: String, holder: Any, method: Method) { … }

private fun replayPopupBattery(holder: Any, method: Method, state: BatteryState) {
    val leftEar = state.left ?: state.single
    val rightEar = state.right ?: state.single
    val box = state.caseBattery
    val args = arrayOf<Any?>(
        leftEar ?: 0, state.leftCharging == true, leftEar != null,
        rightEar ?: 0, state.rightCharging == true, rightEar != null,
        box ?: 0, state.caseCharging == true, box != null,
    )
    method.isAccessible = true
    method.invoke(holder, *args)
}
```

**`hookDiscoveryPopupBattery` 渲染入口改「缓存不完整即主动查」：**

```diff
 val address = popupDeviceMac ?: activeModuleAddress
+// 缓存为空/不完整：弹窗先于 SPP 会话与详情页时缓存恒空（宿主原生槽停在 `--`）。
+//   主动建会话补采一次，成功后在主线程重放 h(...) 刷新三槽；后续 h() 再回调时缓存已就绪。
+if (address != null) {
+    val state = currentBatteryState(address)
+    if (state == null || !state.isComplete()) {
+        requestPopupBatterySync(address, instance ?: return@runCatching, method)
+    }
+}
 val state = currentBatteryState(address) ?: return@runCatching
```

`requestPopupBatterySync` 内部：`HeadsetSessionManager.refresh()` 建/复用会话补采 → 命中后写
`detailBatteryCache` + `postToUi { replayPopupBattery(...) }` 就地重放三槽，并顺带挂 5 分钟常驻轮询
（兼作 SPP 心跳）。arg 顺序与 `hookBefore` 注入**同源**（arg0..2=左耳 / arg3..5=右耳 / arg6..8=仓）。

---

## ② 周期轮询强制刷新（不再空转）（P1）

### 根因

`scheduleDetailBatteryFetch(address, profile, onApplied)` 在 `currentBatteryState` 已 `isComplete()`
时**直接 `onApplied()` 并 return**。周期轮询（`BATTERY_POLL_INTERVAL_MS` = 5 分钟）走的正是这条
路径 → 既不建会话也不查询 → 轮询**空转**，电量长期不更新。

### 改点

**`scheduleDetailBatteryFetch` 增加 `force` 参数：**

```kotlin
private fun scheduleDetailBatteryFetch(
    address: String,
    profile: DeviceProfile,
    force: Boolean = false,          // ← 新增
    onApplied: () -> Unit,
) {
    …
    val have = currentBatteryState(normalized)
    if (!force && have != null && have.isComplete()) {   // force=true 时忽略早返回
        onApplied(); return
    }
    …
}
```

**轮询 tick 改为 `force = true` + 时间戳节流：**

```kotlin
// [修复] 旧实现调 scheduleDetailBatteryFetch：电量为「完整」时该方法直接 return → 空转。
val now = System.currentTimeMillis()
val last = batteryLastProbedAt[address] ?: 0L
if (now - last >= BATTERY_POLL_INTERVAL_MS / 2) {
    batteryLastProbedAt[address] = now
    logChain("I", "BATTERY_POLL_TICK addr=$address profile=${profile.id}")
    scheduleDetailBatteryFetch(address, profile, force = true) {
        refreshDetailBatteryViews()
        refreshPopupBatteryIfShowing()
    }
}
Handler(Looper.getMainLooper()).postDelayed(this, BATTERY_POLL_INTERVAL_MS)
```

新增字段：`batteryLastProbedAt: ConcurrentHashMap<String, Long>`（每址最近一次主动探测时间戳，
抑制重复建会话 / 刷 UI 抖动）；新增 `refreshPopupBatteryIfShowing()`（轮询后重放最近一次弹窗 `h(...)`）。

---

## ③ SPP 半死链路检测与自愈（P1）

### 根因

`XiberiaRfcommClient.isConnected` 仅判 `socket.isConnected == true`。耳机蓝牙空转 / 移远 / 被官方
App 抢占时，socket 句柄仍「已连接」，但**命令已发出、恒无响应**——上层 `HeadsetSessionManager`
据此认为链路健康，**永不重连**，所有命令砸在死链路上。

### 改点 `adapter/xiberia/XiberiaRfcommClient.kt`

**新增链路活性字段 + 判死阈值：**

```kotlin
private const val STALE_LINK_SILENT_TIMEOUTS = 3     // 连续零 RX 超时次数阈值

/** 最近一次收到任何字节的时间戳（链路活性判据）。 */
@Volatile private var lastRxAt: Long = 0L
/** 连续「已发出但无任何 RX」的命令计数；收到任意 RX 即清零。 */
@Volatile private var consecutiveSilentTimeouts: Int = 0
```

**`readLoop` 收到字节即刷新活性并清零计数：**

```kotlin
val count = input.read(buffer)
if (count <= 0) break
// 收到任意字节即刷新链路活性并清零「静默超时」计数（半死检测的另一半）。
lastRxAt = System.currentTimeMillis()
consecutiveSilentTimeouts = 0
```

**`sendCommand` 超时分支判死：**

```kotlin
val rxBefore = lastRxAt
val frame = withTimeoutOrNull(timeoutMs) { waiter.deferred.await() }
if (frame == null) {
    log("Response timeout for cmd=0x${cmd.toString(16)}")
    // 只判 socket.isConnected 无法识别「已发出但坐等超时」的死链路。
    //   连续多次「本命令期间链路零 RX」即判死：running=false + 关 socket
    //   → isConnected 变 false → 上层 SessionManager 下次自动重连。
    if (lastRxAt == rxBefore) {
        val n = ++consecutiveSilentTimeouts
        log("SILENT_TIMEOUT cmd=0x${cmd.toString(16)} consecutive=$n")
        if (n >= STALE_LINK_SILENT_TIMEOUTS) {
            log("Link stale (no RX for $n consecutive cmds), closing for reconnect")
            markLinkStale()
        }
    }
    return null
}
```

**`markLinkStale()`：半死即主动关 socket 唤醒重连（不置 `closed`，下次走重连分支）：**

```kotlin
private fun markLinkStale() {
    running = false
    closeSocketOnly()
    failWaiters()
    consecutiveSilentTimeouts = 0
}
```

`connect()` 建立后即 `lastRxAt = 当前时间, consecutiveSilentTimeouts = 0`；`disconnect()` 复位两字段。

---

## 文档清理（本轮附带）

| 动作 | 位置 |
|---|---|
| 文件名去 AI 化：`AI_TASK_Three_Device_Layout_All_Models.md` → `TASK_Three_Device_Layout_All_Models.md` | `docs/` |
| 正文「AI 执行者 / 交接给 AI」等措辞改为中性表述 | `docs/REF_MelodyPlus_ng_Module.md`、`docs/REF_Bluetooth_Stack_Incident.md` |
| 失效链接同步 | `docs/REF_MelodyPlus_ng_Module.md` §10 changelog |

---

## 交付物

| 类型 | 路径 |
|---|---|
| 源码 | `app/src/main/java/com/melody/melodyplus/hook/MelodyPanelHook.kt` |
| 源码 | `app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaRfcommClient.kt` |
| APK | `/sdcard/Download/MelodyPlus_ng/mp_battery_fix.apk`（46.17 MB） |
| 文档 | `docs/CHANGELOG_2026-10-07_PopupBattery_And_Link_Fix.md`（本文件） |

## 验收

```bash
./gradlew :app:assembleDebug            # BUILD SUCCESSFUL in 31s
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/mp_battery_fix.apk
pm install -r /data/local/tmp/mp_battery_fix.apk     # Success
# LSPosed 作用域勾选 com.oplus.melody → am force-stop com.oplus.melody → 重连耳机触发宿主重启加载
```

观察点（日志关键字）：

- `POPUP_BATTERY_FETCH addr=… profile=…` / `POPUP_BATTERY_FETCHED … left=… right=… box=…`
  → 弹窗首次出现即补采三路电量，原生槽由 `--` 刷新为真实值；
- `BATTERY_POLL_TICK addr=… profile=…` → 5 分钟周期轮询**实际发起**（非空转）；
- `SILENT_TIMEOUT cmd=0x… consecutive=3` → `Link stale … closing for reconnect` → 半死链路自动重建。
