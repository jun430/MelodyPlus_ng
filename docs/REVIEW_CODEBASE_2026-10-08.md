# Code Review · MelodyPlus_ng 全面审查

> 审查日期：2026-10-08
> 范围：当前 `master` 源码、构建配置、Manifest、现有单元测试与主要运行链路。
> 基线：`df471b8`；工作区另有未跟踪计划文档 `docs/PLAN_UI_DynamicPanel_And_LiquidGlass_v1.md`，未审查/修改其外的用户改动。
> 本轮只审查，没有修改业务代码。

## 结论

`:app:compileDebugKotlin --offline` 通过。现有单元测试未能执行完成：离线环境缺少 `junit:4.13.2` 和 `hamcrest-core:1.3`，Gradle 在 `compileDebugUnitTestKotlin` 失败。当前测试覆盖 codec/映射/注册表策略较多，但没有直接覆盖 RFCOMM 超时、会话锁、设备切换后的异步回写、日志并发和宿主 Hook 生命周期。

以下先列确定或高置信度问题，再列需实机确认的风险。

## Findings

### P1 · SPP connect 超时不能可靠取消阻塞调用，可能长期占用全局会话锁

位置：`adapter/xiberia/XiberiaRfcommClient.kt:89-106`；`bridge/HeadsetSessionManager.kt:23-32, 65-120, 148-168`。

`BluetoothSocket.connect()` 是阻塞调用。当前 `withTimeoutOrNull(CONNECT_TIMEOUT_MS)` 在同一个 `Dispatchers.IO` 协程中直接执行 `s.connect()`；超时处理中的 `s.close()` 只有在 `s.connect()` 返回/抛异常、协程恢复后才会运行。因此无法保证 8 秒时限可以解除阻塞。会话管理器在同一把全局 Mutex 下执行 connect/read/write，某台设备卡住时，其他地址的状态读取、断开和命令也会排队。

建议：将阻塞 connect 放入可独立关闭 socket 的任务/线程；超时由外部定时器关闭已登记 socket，并等待任务结束后释放会话锁。按 MAC 使用会话锁，避免一台设备阻塞所有耳机。Sony 路径 `SonyRfcommClient.kt:43-64` 直接调用 `newSocket.connect()`，同样没有连接超时。

### P1 · 会话管理器以全局 Mutex 串行化所有设备，并在锁内做整段 I/O

位置：`bridge/HeadsetSessionManager.kt:21-24, 32-53, 65-93, 95-146, 148-173`。

所有地址共享一把 `Mutex`，且锁覆盖连接、状态读取、命令执行及命令后的 `adapter.readState()`。这不只是避免单个设备命令竞争，也把多设备完全串行化；周期电量补采/面板 GET/写命令可能互相排队。结合阻塞 connect 和每项查询超时，会放大 UI 卡顿和命令排队。

建议：保留每地址串行命令语义，改为 per-address Mutex；连接状态变更与会话替换保持原子，避免把慢 I/O 放在所有设备共用的锁里。

### P1 · 面板设备切换时，旧异步请求可能污染新设备缓存/UI

位置：`hook/MelodyPanelHook.kt:1700-1707, 2070-2128, 1912-2000, 2131-2155`。

`probeXiberiaDeviceStates()` 启动协程时捕获旧 `address/profileId/productId`，但响应回来后写入以 `item.key` 为键的全局 `xiberiaStateCache`/`xiberiaChoiceCache`，并在 UI 回调中直接刷新当前面板。设备切换只在型号 productId 不同时清缓存；同型号换 MAC 不会清缓存。即使型号不同，切换发生在旧查询过程中时，旧查询仍可能在清理之后写回旧值。结果是新设备面板短时显示旧设备状态。

建议：每次设备绑定/型号切换递增 generation；查询启动时捕获 `(MAC, profileId, productId, generation)`，回写前逐项比较当前 generation 和地址。缓存至少按 `(MAC, itemKey)` 隔离；在地址变化时清理 pending/cache/choice。写操作完成回调也需要同样校验，避免旧命令结果刷新新面板。

### P1 · HookLogStore 的读-改-写不是原子操作，多进程并发会丢日志

位置：`bridge/HookLogStore.kt:42-54`；调用入口 `hook/HookContext.kt:103-119, 210-257`，Provider `bridge/DeviceRegistry.kt:676-690`。

多个宿主进程可同时通过 Provider `append_log`。每次 append 都先读整个 JSON、追加、再 `SharedPreferences.apply()` 写回；并发调用可能都读到相同旧列表，后写覆盖先写，导致日志丢失。500 条日志每次都全量解析和序列化，也有随日志量增长的 Binder/Provider/CPU 开销。

建议：Provider 内串行化追加与清空；长期改为按行追加的文件/SQLite/WAL 或批量队列，限制单条 message 字节数并对高频重复日志节流。UI 拉取支持最近 N 条分页，避免每次 Binder 返回完整 JSON。

### P1 · Provider 的 caller 校验后仍开放高成本写入，日志调用在 Hook 热路径同步 Binder

位置：`app/src/main/AndroidManifest.xml:49-53`；`bridge/DeviceRegistry.kt:631-633, 676-729, 734-805, 853-877`；`hook/HookContext.kt:103-119, 211-256`。

Provider `exported=true` 且按包名白名单授权，基本有访问控制；但白名单调用方可提交任意长度日志消息，`append_log` 在 Provider Binder 线程同步读写 JSON SharedPreferences。Hook 的 `modLog` 每条都同步 `contentResolver.call`，高频协议 TX/RX 日志会把大量 Binder 往返和全量 JSON 写入放在调用路径。`export_logs`、`inject_debug_popup` 也由同一白名单授权，不限于模块 UI 自身调用。

建议：限制 message/source/level 长度和调用频率；将写日志改成异步队列或批量传输；区分只读/日志/调试操作的权限边界。调试注入和日志清空/导出最好限制为模块 UID 或带 signature permission。若本模块必须跨进程调用，采用 signature 级权限或校验 UID 下所有 package 都受控，而不是只取 UID packages 中首个白名单包。

### P2 · 日志按 UID 取第一个包名，shared UID 下可能把非白名单同 UID 包误认成授权包

位置：`bridge/DeviceRegistry.kt:857-877`。

`getPackagesForUid(uid).firstOrNull { it in ALLOWED_CALLERS }` 以 UID 的任意一个白名单包代表调用者。共享 UID 下，如果同 UID 还包含非白名单包，包列表顺序不应被当成调用身份认证。Android 现代版本一般不鼓励 sharedUserId，但兼容性/系统包仍应避免以此作强授权保证。

建议：使用 signature permission；或检查 UID 对应包集合的完整性/签名，并按操作类别授权。

### P2 · Choice 面板无状态回读时可能显示“关闭”，未知状态与真实 false 混淆

位置：`hook/MelodyPanelHook.kt:2131-2153`；点击行为 `1912-1932`；Choice Dialog `1885-1903`。

`checked` 默认回退为 `false`，所以查询尚未完成、未收到应答、没有 GET 或解析失败时，都可能以关闭态显示。`CHOICE` 对话框使用 `setItems`，没有标记当前选择。用户无法区分“设备当前关闭”和“状态未知”，第一次操作容易误解当前状态。写入失败时 switch 回滚用 `!enabled` 推定旧状态，也不一定等于设备真实状态。

建议：缓存值使用 nullable/显式状态而非默认 false；未知状态先发 GET 或保留“未读取”摘要。Choice 使用单选列表并标示当前项。失败回滚优先触发 GET；只有已知旧值时才回滚旧值。

### P2 · 互斥项 pending 状态缺少统一 finally/超时，且在主命令失败时仍继续关闭兄弟项

位置：`hook/MelodyPanelHook.kt:1945-2000`。

主命令有 `PANEL_WRITE_TIMEOUT_MS`，但互斥 sibling 命令没有整体超时。若 sibling execute 长时间等待，兄弟项 `pendingXiberiaStates` 不会清理，UI 可能持续禁用。另一个行为问题是主命令失败后代码仍进入 sibling 关闭循环（注释写“主命令成功后执行”，实际未以 `ok` 条件包住）。开 LDAC 主命令失败时仍可能关闭游戏模式，导致用户的原状态被破坏。

建议：整组事务用 `try/finally` 清理所有 pending；主命令失败时不执行互斥关闭；所有 SET 使用统一 timeout。成功时若需要互斥，优先按协议确认设备端确实原子互斥，或下发后重新 GET 两项核实。

### P2 · MAC→型号绑定允许同一 MAC 留在多个 Profile 目录，反查结果不确定

位置：`bridge/DeviceRegistry.kt:284-312, 428-464, 537-576`。

保存新绑定只在目标 Profile 目录下幂等创建，没有删除同 MAC 在其它 Profile 下的旧绑定。之后反查遍历目录，纯文件路径按 `listFiles()` 顺序返回第一个命中；MediaStore 路径也没有排序/冲突判定。设备改名、用户改绑型号或历史迁移后，可能稳定地解析成旧型号或随机型号。

建议：把 MAC→Profile 存为单条映射记录；保存时以 MAC 为键原子替换旧 Profile，或反查发现多个 profile 命中时返回冲突并要求按当前绑定真源裁决。给迁移/解绑增加跨 Profile 清理。

### P2 · 壁纸预览 Bitmap 在 Compose 主线程同步解码

位置：`ui/pages/AppearancePage.kt:121-124, 416-427`。

`remember(settings.wallpaperUri) { decodePreviewOnly(...) }` 在 Composable 求值线程运行；解码两次打开 URI（bounds + bitmap），单张大图/慢云端 provider 会阻塞主线程。主背景 `WallpaperBackground.kt:36-40, 67-77` 已经正确放到 IO，但同一资源的预览路径没有。

建议：用 `produceState`/`LaunchedEffect` 加 `Dispatchers.IO`，加入 decode cancellation 和错误状态；限制尺寸时同时校验 bounds 正值，避免坏图尺寸导致采样计算异常。当前 `persistReadPermission()` 吞异常后仍保存 URI，权限获取失败时只会留下不可读 URI，建议只在权限成功或可验证读取后保存，并在读取失效时提供重新选择提示。

### P2 · Hook 中 5 分钟轮询用主线程 Handler，无法在作用域离开时主动取消

位置：`hook/MelodyPanelHook.kt:247-250, 5884-5911, 5914-5941`。

`batteryPollingActive` 只存地址，不存 `Runnable`；轮询每次触发后重新 `postDelayed`。只有 runnable 下一次执行时发现 `activeModuleAddress != address` 才停止。若用户切换到另一个地址，旧 runnable 最多还会存活一个轮询周期（5 分钟），占用引用并可能在切换竞态时触发旧设备请求。进程退出会由系统回收，但 Activity/详情页离开没有显式清理。

建议：保存每地址 Job/Runnable 并在地址切换、断连、详情页销毁时移除；若该轮询是有意随宿主进程存活，则改用有明确 owner 的 session-scoped scheduler，单地址切换立刻取消旧轮询。

### P2 · 面板查询去重是全局时间戳，不按设备/型号隔离

位置：`hook/MelodyPanelHook.kt:277-278, 2070-2082`。

`lastProbeAtMillis` 全局共享，5 秒内不同设备/型号的面板注入也会被去重跳过。快速切换耳机时，新型号初始 GET 可能被旧设备查询时间戳压掉；同时旧任务仍可继续回写（见 P1）。

建议：去重键至少使用 `(address, productId)`，并结合 generation/cancellation。

### P2 · 壁纸图片内存没有明确缓存与释放策略，预览和背景会重复解码

位置：`ui/pages/AppearancePage.kt:122-124`；`ui/components/WallpaperBackground.kt:36-40`。

配置页预览和根背景各自解码同一个 URI，最高分别为 1024/2048 像素边长；重组中 URI 不变时各自持有 bitmap，内存可能叠加。更换图片后 Compose 引用释放不等于立即回收 Native bitmap 内存。不是必现泄漏，但对大图/低内存设备应有上限和共享缓存。

建议：统一图片加载器/缓存；预览用小图、背景按屏幕密度采样，切换时取消旧解码；必要时控制 Bitmap.Config 和缓存淘汰。

### P3 · `MelodyPanelHook.kt` 单文件过大，协议、状态、View 注入和图片渲染耦合

位置：`hook/MelodyPanelHook.kt`（约 6,352 行、约 322 KB）。

静态分析显示同一对象承载 Hook 注册、宿主反射、ANC、Preference 面板、EQ、弹窗电量、图片替换、轮询、缓存和全局上下文。缺乏组件边界会让状态竞争难以测试，增加误改和回归概率。

建议：按职责拆成 `PanelStateRepository`、`XiberiaPanelController`、`BatteryRefreshCoordinator`、`HostPreferenceAdapter`、`ProductImageReplacer`；先抽纯逻辑与依赖接口，再迁移 Hook 注册。拆分前补关键契约测试，不做一次性机械搬文件。

### P3 · `DeviceRegistryProvider` 将多种数据域、日志与调试 Intent 混在一个巨大 `call()` 分发中

位置：`bridge/DeviceRegistry.kt:628-829`，文件约 46 KB。

绑定、状态、日志、图片、导出、调试广播都共用一个 Provider 和同一 caller 策略。操作权限、耗时和数据大小不同，统一入口不易审计。尤其 Provider Binder 线程可能同步访问 MediaStore、文件和 SharedPreferences。

建议：把纯业务分发委托给小型 handler/service；Binder 入口只做输入校验与授权，耗时 I/O 下放到明确的异步路径或确认调用方可接受的线程语义。

### P3 · Android 备份规则仍为模板默认值，持久化配置迁移语义未明确

位置：`app/src/main/AndroidManifest.xml:31-32`；`res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml`。

应用启用 `allowBackup=true`，规则文件仍为 Android Studio 默认注释模板。SharedPreferences 包含 UI 配置、设备绑定/名称规则、Hook 状态/日志等；备份/设备迁移可能造成旧设备 MAC/Profile、持久化 URI 权限或 Hook 状态恢复到新设备。是否需要备份应由数据类型决定。

建议：明确哪些配置可迁移、哪些是设备本地状态；为 Android 12+ cloud/device transfer 分开规则，或关闭不需要的备份，并为外部共享文件和持久化 URI 建立迁移/校验策略。

## 建议处理顺序

1. 先修阻塞连接超时和全局会话锁（否则全功能受单设备连接问题牵连）。
2. 修面板 generation/cache 隔离、统一操作可用条件、Choice/未知态与互斥事务 finally。
3. 修日志并发写、Provider 热路径与输入限额。
4. 修 MAC 绑定唯一性和轮询取消生命周期。
5. 壁纸预览移到 IO 并完善 URI 权限失败反馈。
6. 建立 Xiberia/Sony RFCOMM、SessionManager、Provider、面板状态的测试，再拆分大文件。

## 验证记录

- `./gradlew :app:compileDebugKotlin --offline`：`BUILD SUCCESSFUL in 11s`（任务均 up-to-date）。
- `./gradlew :app:testDebugUnitTest --offline --no-daemon --console=plain`：失败于依赖解析，离线缓存缺少 `junit-4.13.2.jar`、`hamcrest-core-1.3.jar`；不是测试断言失败。
- 未运行设备端安装、LSPosed Hook 验证、蓝牙真机压力测试；相关行为列为待验证项。
