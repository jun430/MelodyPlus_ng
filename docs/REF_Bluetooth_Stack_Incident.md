# 蓝牙栈卡死事故 · 调查报告（Bluetooth Stack Deadlock Incident Report）

> 文档类型：**事故调查 / 现场取证报告**（非实现文档）
> 工程：`/root/MelodyPlus_ng`
> 事故对象：`com.android.bluetooth`（蓝牙协议栈进程）
> 首次崩溃：**2026-10-05 21:58:02**
> 报告生成：**2026-10-06 09:15**（现场仍未恢复）
> 撰写：模块维护者
> 关联文档：`REF_MelodyPlus_ng_Module.md`（模块实现）。蓝牙恢复计划已删除，现场结论仅保留在本文。

---

## 0. 摘要（TL;DR）

| 项 | 结论 |
|---|---|
| **事故** | 蓝牙协议栈进程 `com.android.bluetooth` 崩溃，`BluetoothManagerService` 卡死在 `TURNING_OFF`，无法自愈 |
| **根因** | 旧版模块在蓝牙进程内**伪造系统受保护广播 `BluetoothDevice.ACTION_ACL_CONNECTED`**，污染同进程 OPPO 组件 `OplusBtAudioRouteMonitor` 的 receiver 记账 → 关闭蓝牙时 `AdapterService.cleanup()` 抛 `IllegalArgumentException` |
| **修复** | 已实施（禁伪造 ACL 广播 + 哨兵默认关闭），**新版已装到设备** |
| **当前状态** | ⛔ **仍未恢复**：`enabled=false / state=TURNING_OFF / pid 空`；`Bluetooth crashed 0 times`（AMS 未收到崩溃通知，误判为正常关闭） |
| **为什么修复没生效** | 蓝牙进程根本没被 fork 出来 → 模块的 hook 代码**没有执行机会** → 修复无法自愈，必须重置 Java 蓝牙栈或设备 |
| **当前恢复结论** | 本设备 `init.svc.bluetooth` 为空，init 配置中没有 `service bluetooth`；`setprop ctl.restart bluetooth` 不是有效 Java 栈恢复命令 |
| **下一步** | 用户手动重启设备；重启前先保存现场，重启后做蓝牙三轮开关回归 |

---

## 1. 实测时间线（证据驱动）

| 时间 | 事件 | 证据来源 |
|---|---|---|
| 2026-10-05 21:58:02.161 | `SENTINEL SM.sendMessage what=8`（AdapterState 关闭消息） | LSPosed verbose 日志 |
| 2026-10-05 21:58:02.169 | `E/LSPosedLogDaemon Crash unexpectedly: pkg=com.android.bluetooth` | 同上 |
| 2026-10-05 21:58:02 | `AdapterService.cleanup(AdapterService.java:1584)` ← `AdapterState$OffState.enter(AdapterState.java:199)` | 崩溃栈 |
| 2026-10-05 22:04:24 | `systemui requested to [Enable]`（用户/系统尝试恢复） | `dumpsys bluetooth_manager` Enable log |
| 2026-10-05 22:09:03 ~ 22:15:46 | 用户反复 `Disable`/`Enable` 共 8 次 | 同上 |
| 2026-10-05 22:08 | **修复版（去伪造 ACL）安装** | 模块安装记录 |
| 2026-10-06 07:55:16 ~ 08:00:48 | 又出现 5 次 `[Enable]` 请求 | Enable log |
| 2026-10-06 08:12:47 | **最新版（官方耳机注入 + 日志导出）安装** | `pm dump lastUpdateTime` |
| 2026-10-06 08:13:46 / 08:13:48 | 最后 2 次 `[Enable]` 请求，仍无效 | Enable log |
| 2026-10-06 08:15:28 | **复测**：`enabled=false / state=TURNING_OFF / pid 空` | 本报告现场实测 |

> **判读**：从 22:04 到次日 08:13，共 15+ 次 Enable 请求全部无效 → 证明该状态**不是"等待自愈"能解决的**，
> 而是 `BluetoothManagerService` 内部状态机死锁。

---

## 2. 现场快照（2026-10-06 08:15:28 实测）

```
$ date '+%F %T'
2026-10-06 08:15:28

$ uptime
 08:15:28 up 12:45,  load average: 13.58, 21.31, 17.95

$ dumpsys bluetooth_manager | head -6
Bluetooth Status
  enabled: false
  state: TURNING_OFF
  address: XX:XX:XX:XX:0A:FA
  name: 一加 12
  time since enabled: 00:00:24.870

$ pidof com.android.bluetooth
(空)

$ dumpsys bluetooth_manager | grep 'crashed'
Bluetooth crashed 0 times
```

**关键判据解释**：
- `state: TURNING_OFF` —— `BluetoothManagerService` 已发出关闭指令，等待底层 `AdapterService` 回 `OFF` 回调；
- `pid 空` —— 蓝牙进程不存在（崩溃后再未被拉起）；`time since enabled: 24s` 说明 manager **自认为刚 enable 过**；
- `Bluetooth crashed 0 times` —— **AMS 层面没收到进程崩溃通知**，因为崩溃发生在**正常关闭流程中**（`OffState.enter`），
  AMS 把进程退出当成"预期内的关闭" → 因此**不会自动重启蓝牙进程**，这就是死锁成因。

---

## 3. 崩溃证据链（LSPosed verbose 日志原文）

```
21:58:02.161  (com.android.bluetooth) SENTINEL SM.sendMessage what=8
21:58:02.169  E/LSPosedLogDaemon Crash unexpectedly:
                 pkg=com.android.bluetooth, prc=com.android.bluetooth
    java.lang.IllegalArgumentException
        at com.android.bluetooth.btservice.AdapterService.cleanup(AdapterService.java:1584)
        at com.android.bluetooth.btservice.AdapterState$OffState.enter(AdapterState.java:199)
```

- `what=8` = `AdapterState` 的关闭状态消息（`MESSAGE_...` / `OFF`）；
- `AdapterState$OffState.enter()` → 调用 `AdapterService.cleanup()` → 内部 `unregisterReceiver()` 抛异常；
- `IllegalArgumentException` 来自 `unregisterReceiver` 的典型语义：**unregister 一个从未 register、或已被 unregister 的 receiver**
  → 说明 receiver 记账被外部污染。

---

## 4. 根因分析

### 4.1 直接原因
旧版 `hook/BluetoothAudioConnectionHook.kt` 在**蓝牙进程内部**伪造发送：

```java
// ❌ 旧行为（已移除）
BluetoothDevice.ACTION_ACL_CONNECTED   // 系统受保护广播
```

该 action 是 **framework 受保护广播**，本不该由第三方模块在蓝牙进程内伪造。

### 4.2 传导链
```
模块伪造 ACTION_ACL_CONNECTED（蓝牙进程内）
  → 同进程 OPPO 定制组件 OplusBtAudioRouteMonitor 注册的 receiver 收到该广播
  → 该组件据此做「音频路由记账」（认为有一个 ACL 连接注册/注销）
  → 记账被污染（多记 / 错记）
  → 用户关闭蓝牙 → AdapterState 进入 OffState
  → AdapterService.cleanup() 统一 unregisterReceiver
  → 与污染后的记账状态不一致 → IllegalArgumentException
  → 异常上抛至 Bluetooth main 线程 → 进程崩溃
  → AMS 认为是"正常关闭"，不重启 → TURNING_OFF 死锁
```

### 4.3 加剧因素
旧版 `SENTINELS_ENABLED = true` 时，在蓝牙 **main 线程**高频 `XposedBridge.log`（每次 `getName()` + 每条 StateMachine 消息）
→ **日志风暴拖慢状态机** → 放大 `AdapterState` 超时 / 进 `OffState` 的概率。

---

## 5. 已实施修复（代码已落地并安装）

位置：`app/src/main/java/com/melody/melodyplus/hook/BluetoothAudioConnectionHook.kt`

| # | 修复项 | 具体做法 |
|---|---|---|
| 1 | **禁止伪造 `ACTION_ACL_CONNECTED`** | 弹窗链路改用**自定义 action** `com.melody.melodyplus.action.MODULE_AUDIO_CONNECTED`，并用 `setClassName()` 显式定向到 melody 的 receiver |
| 2 | **哨兵探针默认关闭** | `SENTINELS_ENABLED = false`，消除蓝牙 main 线程日志风暴 |

源码内已留警示注释：
```kotlin
// 注意：禁止伪造 BluetoothDevice.ACTION_ACL_CONNECTED 系统受保护广播。
// 该 action 会被同进程 OPPO 定制组件 OplusBtAudioRouteMonitor 消费，
// 污染其 receiver 记账，导致 AdapterService.cleanup() 时
// unregisterReceiver 抛 IllegalArgumentException 打崩蓝牙进程。
```

**验证状态**：⛔ **无法在当前设备上验证**（蓝牙进程不存在 → hook 无执行机会）。

---

## 6. 为什么"装了新模块"也救不回来

```
LSPosed hook 生效的前提 = 目标进程被 fork + 模块被注入
              ↓
蓝牙进程当前不存在（pid 空）→ 无从注入 → 修复代码永不执行
              ↓
BluetoothManagerService 卡在 TURNING_OFF，policy 层拒绝重新 enable
（`am force-stop com.android.bluetooth` 也无效，因为进程本就不存在）
              ↓
∴ 修复必须等**蓝牙栈重启**后才能被验证
```

---

## 7. 恢复方案（需要用户明确拍板，AI 不得自行触发）

### 7.1 本设备实测结论

本设备不存在可供 `ctl.restart` 使用的 Java 蓝牙 init service：

```text
getprop init.svc.bluetooth -> 空
/system/etc/init、/vendor/etc/init、/odm/etc/init
仅发现 vendor.bluetooth-1-1-qti（QTI HAL 服务）
```

`vendor.bluetooth-1-1-qti` 是 HAL，不是 `com.android.bluetooth` Java 进程；重启它不能解除
system_server 内 `BluetoothManagerService` 的 `TURNING_OFF` 状态。因此不执行、不推荐：

```bash
setprop ctl.restart bluetooth
setprop ctl.restart vendor.bluetooth-1-1-qti
```

### 7.2 当前有效恢复路径

| 方案 | 做法 | 影响面 | 结论 |
|---|---|---|---|
| **用户手动重启设备** | 用户自行执行 | 全系统；会丢失当前运行现场 | **当前唯一可靠恢复方案**，重置 system_server + Java 蓝牙栈 |
| 重启 zygote / LSPosed 框架 | 禁止 | 全局进程/框架 | 不执行、不作为恢复手段 |
| 只重启 QTI HAL | 不采用 | vendor 蓝牙 HAL | 无法重置 `BluetoothManagerService` |

重启前先执行事故报告 §10 的只读现场备份；重启后按 §8 做三轮开关验证。

⛔ **绝对禁止**（设备级硬约束）：
- `ctl.restart zygote` / `killall zygote` / `am restart`
- LSPosed 管理器「软重启 / 重启框架」按钮
- 任何 Magisk / KernelSU / Zygisk 全局重载

---

## 8. 恢复后的验证清单（Recovery Checklist）

| # | 验证项 | 命令 / 判据 | 预期 |
|---|---|---|---|
| 1 | 蓝牙进程存活 | `pidof com.android.bluetooth` | 非空 |
| 2 | 蓝牙可正常开关 | `dumpsys bluetooth_manager \| grep -E 'enabled\|state:'` | `enabled: true` / `state: ON` |
| 3 | 关闭蓝牙不崩 | 开关 3 次，查 `LSPosed 日志` / `Bluetooth crashed N times` | `crashed 0 times`，无 `AdapterService.cleanup` 栈 |
| 4 | 模块注入蓝牙进程 | 模块「关于页 → 导出全部日志」→ 查 Hook 激活状态 | `com.android.bluetooth` 上报已激活 |
| 5 | MC05 真机连接 | 连 XIBERIA MC05 → 看是否弹窗 | melody 原生弹窗出现 |
| 6 | 注入官方耳机（UI 验证） | App 首页「注入官方耳机弹窗」 | 弹出 OPPO Enco X3 官方 UI 弹窗 |
| 7 | 负载回落 | `uptime` | load average 回落到个位数 |

---

## 9. 预防措施（防止复发）

1. **永不伪造系统受保护广播**（`ACTION_ACL_*` / `ACTION_A2DP_*` / `ACTION_BOND_STATE_CHANGED` 等）。
   跨进程通信一律走**自定义 action + setClassName 显式定向**。
2. **哨兵 / 调试日志默认关闭**，需要时临时开启，且不得在蓝牙 main 线程做高频 I/O。
3. **蓝牙进程 hook 只做「只读观测 + 发自定义广播」**，不做任何会改变 receiver 记账的动作。
4. **冷启动约定**：每次改动蓝牙进程相关 hook 后，验证步骤必须包含「开→关→再开」三轮循环，
   确认关闭路径不崩。
5. 若必须获取 ACL 连接事件，改用：
   - hook `BluetoothDevice` 的 `isConnected()` / `getBondState()` 被动读取，或
   - hook `AdapterService` 内部状态回调（只读不写），**不伪造广播**。

---

## 10. 附：常用诊断命令速查

```bash
# 蓝牙状态
dumpsys bluetooth_manager | head -30
dumpsys bluetooth_manager | grep -E 'enabled|state:|crashed'
pidof com.android.bluetooth

# 崩溃证据
ls -lt /data/adb/lspd/log/ | head
grep -a 'Crash unexpectedly' /data/adb/lspd/log/modules_*.log | tail

# 模块日志（模块自身结构化日志）
cat /data/data/com.melody.melodyplus/shared_prefs/module_hook_logs.xml

# 恢复（本设备实测：没有 init service bluetooth；不要执行 ctl.restart bluetooth）
# 先只读备份：
dumpsys bluetooth_manager > /sdcard/Download/Operit/bluetooth_manager_before.txt
logcat -b all -d -v threadtime -t 3000 > /sdcard/Download/Operit/bluetooth_log_before.txt

# 可靠恢复：用户手动重启设备；重启后检查：
pidof com.android.bluetooth
dumpsys bluetooth_manager | grep -E 'enabled|state:|crashed'
```

> `vendor.bluetooth-1-1-qti` 是 HAL 服务，不等于 Java 蓝牙栈；不要用单独重启 HAL 代替重置 `BluetoothManagerService`。

---

## 11. 变更 / 版本记录

| 时间 | 事项 |
|---|---|
| 2026-10-05 21:58 | 蓝牙进程崩溃（事故起点） |
| 2026-10-05 22:08 | 修复版安装（禁伪造 ACL + 哨兵关闭） |
| 2026-10-06 08:09 | 官方耳机注入版安装 |
| 2026-10-06 08:12:47 | 官方耳机注入 + 日志导出 + hex 修正版安装 |
| 2026-10-06 09:15 | 更正恢复结论：本设备无 `init.svc.bluetooth`，不把 `ctl.restart bluetooth` 当作有效 Java 蓝牙栈恢复命令 | `docs/REF_Bluetooth_Stack_Incident.md` |
| 2026-10-06 09:15 | 记录 profile 重复定义与 productId hex 遗留问题，后续按模块主文档单独处理 | `docs/REF_MelodyPlus_ng_Module.md` |
