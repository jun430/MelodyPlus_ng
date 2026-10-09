# PLAN · 动态耳机面板与液体玻璃 UI 优化 v1.1

> 状态：计划 v1.2，P1（A/B/C/D）与 P2 主要项已落地并编译通过；P3 与宿主自定义面板原型待实施
> 工作模式：按本计划执行；已修改业务代码并通过编译与单元测试（未做真机回归）。
> 范围：MelodyPlus 配置界面视觉、`com.oplus.melody` 详情页动态功能面板、相关状态、会话并发、日志链路与回归验证。
> 不在本计划范围：重做耳机协议、改变命令码/载荷、改产品型号能力矩阵、改变电量轮询间隔、一次性重写宿主详情页。
> 基线提交：`df471b8`（现有面板回读、SPP 连接看门狗、跨进程绑定兜底、日志双通道补全）。
> 重要校正：基线中的 SPP“超时看门狗”只代表已有超时保护意图，不等于阻塞 `BluetoothSocket.connect()` 已经可以被可靠取消；该问题纳入本计划 P1，必须以超时后其它设备仍可执行命令的实测证据作为完成条件。

---

## 1. 目标与设计原则

1. **先保证状态真实，再做视觉增强**：UI 的连接、查询、写入和错误状态必须来自现有会话/查询结果，禁止用本地默认值伪装设备状态。
2. **模块 UI 与宿主注入 UI 分层**：模块配置页由 Compose/Miuix 完整控制；宿主详情页受宿主 Preference 页面约束，优先实现稳定、原生兼容的 View，而不是把 ComposeView 强塞进未知宿主布局。
3. **渐进替换**：以 feature flag/回退路径分阶段替换单项 preference。确认布局、生命周期、点击与状态回填稳定后，再扩大到整组面板。
4. **保留当前功能真源**：`XiberiaProductCatalog.panelItems(productId)` 继续决定型号支持项；`HeadsetSessionManager` 继续负责 SPP 命令；UI 层不复制能力表或协议逻辑。
5. **液体玻璃是材质层，不是整体透明/模糊**：背景表面、描边、高光、阴影分别绘制；文字、图标和交互反馈保持清晰。模糊能力不可用时必须有清晰的不透明/半透明降级样式。

### 目标体验

- 用户进入详情页后，能区分“未连接 / 正在读取 / 已同步 / 部分不可读 / 命令失败”。
- 可操作项目不会因地址暂未解析或 SPP 等待而无限灰置；在途状态有超时和恢复结果。
- 功能列表有稳定分组、状态表达、行高、图标和触控反馈，窄屏及大字体下不挤压或截断。
- 配置页四个 Tab 使用一致的 Miuix 色彩、排版、页面边距与组件密度。
- 玻璃背景模糊只影响表面层；前景内容清晰，关闭模糊后视觉仍完整。

---

## 2. 当前代码事实与问题

| 区域 | 已确认现状 | 计划处理 |
|---|---|---|
| 宿主动态面板注入 | `MelodyPanelHook.injectDynamicPanelIntoCategory()` 按产品目录动态创建 `COUISwitchPreference`，写入后回读/刷新 | 保留型号目录与命令链，新增定制 `Preference`/`View` 展示适配层；先单型号/单类目灰度验证 |
| 宿主状态读写 | 有 `pendingXiberiaStates`、`xiberiaStateCache`、`refreshXiberiaPanelStates()`，写入设置 timeout 并复位状态 | 梳理成显式状态模型，确认查询失败、部分能力无 GET、地址缺失的展示语义 |
| 连接与查询入口 | 面板注入后调用 `probeXiberiaDeviceStates("panel_injected")`；状态方法依赖活动地址和 Profile | 进入页面触发一次按支持项过滤的查询；地址未就绪时显示等待/不可用原因并在连接状态到达后重试 |
| 电量轮询 | `BATTERY_POLL_INTERVAL_MS = 5 * 60 * 1000L`；按地址去重；周期调用 `scheduleDetailBatteryFetch(..., force=true)`，成功后刷新详情与弹窗 | 当前不改间隔。实机确认轮询链是否启动、是否确实发出请求、失败后是否恢复；不要另加重复轮询器 |
| MAC → 型号 | `resolvePanelProductId()` 按 Profile 的 modelId/displayName/id 反查目录；Profile 的地址关联由现有注册/绑定流程提供 | 核对 MAC、Profile ID、modelId、productId 的数据来源与切换/断连清理，补链路日志和测试，不复制另一份反查表 |
| 液体玻璃底栏 | `GlassNavigationBar` 对 Row 使用 `Modifier.blur()` | 修正为独立背景层模糊/透明表面与清晰前景分层；明确 API/性能降级 |
| 主题体系 | `ModuleUiTheme` 提供可配置 Material3 色板；页面组件主要使用 Miuix；另有旧 `MelodyPlusTheme` 与紫色默认色板 | 清理实际入口中未使用或冲突的主题路径，统一 token 来源；不要全局盲目替换颜色 |
| 外观预览 | `AppearancePage` 有样式选项与底栏预览，但预览是简化示例 | 让预览与最终底栏共用组件及真实材质参数；提供可感知但不重复的玻璃层次 |

### P0 已定位的 UI/状态缺陷

- `ui/components/GlassNavigationBar.kt` 中模糊 Modifier 当前落在包含文字内容的 `Row` 上。blur 会处理整层内容，造成标签一起变糊。改造必须把表面画在独立 `Box` 背景层，将 tab 内容放在其上层。
- 当前 `refreshXiberiaPanelStates()` 主要用 `activeModuleAddress != null` 判断可操作性；地址存在但 Profile、型号或会话尚未准备好时，界面可能显示可操作，点击后却在 `requestXiberiaPanelChange()` 静默返回。可操作条件应统一为“有效地址 + 有效 Profile + 当前 productId + 非预览态”。
- `requestXiberiaPanelChange()` 的主命令有整体超时，但互斥项关闭命令没有同等级超时保护。半死 SPP 下可能导致同组 pending 状态长期不清，必须复用统一的命令执行包装器。
- `showXiberiaChoiceDialog()` 没有标识当前档位；`LDAC` 子页在缓存未知时把未知态按关闭处理，可能在用户第一次点击时直接发送开启命令。未知态应先查询或明确提示，不能转换为默认布尔值。

另外，Compose `Modifier.blur()` 只会模糊它所在层的绘制内容，并不会自动采样后方页面。拆分背景层可以保证文字清晰，但不能单独形成真正的实时背景毛玻璃。实现上应分级：优先验证平台/宿主是否提供可用的 RenderEffect 或截图采样方案；不可行时采用半透明表面、色调混合、描边和高光作为稳定降级，不把自模糊宣传成实时背景模糊。

### 2.1 实施坐标与依赖关系

| 问题 | 首要源码入口 | 首要测试/夹具 | 前置条件 |
|---|---|---|---|
| SPP 阻塞连接 | `adapter/xiberia/XiberiaRfcommClient.kt`、`adapter/sony/SonyRfcommClient.kt` | 可控阻塞 socket fake、超时/关闭测试 | 先定义 adapter 的取消与资源释放契约 |
| 多设备会话锁 | `bridge/HeadsetSessionManager.kt` | 同 MAC/不同 MAC 并发调度测试 | 明确同一地址的命令顺序和会话替换原子性 |
| 面板旧结果污染 | `hook/MelodyPanelHook.kt` 的 probe/refresh/write/cache 路径 | generation、延迟回调、同型号换 MAC 测试 | 先固定快照键和 request token，不先改 View |
| 未知态/互斥写入 | `MelodyPanelHook.kt` 的 Choice、switch、sibling 写入路径 | 状态 reducer、事务 fake、失败矩阵 | 先区分 UNKNOWN/STALE/false，禁止默认值兜底 |
| 日志并发与 Provider | `bridge/HookLogStore.kt`、`bridge/DeviceRegistry.kt`、`hook/HookContext.kt` | 并发 append/clear、限长、权限测试 | 先确定兼容旧日志格式和迁移策略 |
| 轮询生命周期 | `MelodyPanelHook.kt` 的 battery scheduler | fake clock、取消与地址切换测试 | 先确定 owner 是详情页、地址会话还是宿主进程 |
| MAC 绑定唯一性 | `DeviceRegistry.kt` 的 binding/save/resolve | 重绑、迁移、冲突目录测试 | 先确定 MAC 归一化和唯一真源 |
| 壁纸预览 | `ui/pages/AppearancePage.kt`、`ui/components/WallpaperBackground.kt` | 慢 URI、坏图、权限失效 fake | 先确定共享缓存的大小和淘汰上限 |
| 玻璃底栏 | `ui/components/GlassNavigationBar.kt`、`AppearancePage.kt` | Compose screenshot/参数端点测试 | 先有静态材质降级，再评估平台 blur |
| 宿主自定义行 | `MelodyPanelHook.injectDynamicPanelIntoCategory()` | 目标宿主版本 UI/生命周期回归 | P1-B/P1-C 完成且原生 Preference 保底稳定 |

禁止跨坐标混合提交：例如不能在修 SPP 超时时同时重排面板 View，也不能用视觉改动掩盖状态回写问题。

---

## 3. 优先级路线图

本计划不按“先做视觉、再补功能”的顺序执行，而按用户可感知故障和回归风险排序。每个阶段完成后单独提交、单独构建、单独回归；未满足当前阶段退出条件，不进入下一阶段。

| 优先级 | 工作包 | 主要目标 | 允许的变更 | 退出条件 |
|---|---|---|---|---|
| P0 | 基线与证据 | 固定环境、复现入口、建立日志字段和状态快照 | 仅文档/测试夹具 | 能区分连接阻塞、查询失败、状态未知、UI 未刷新 |
| P1-A | SPP 与会话并发 | 连接超时可取消、按 MAC 串行、多设备互不阻塞 | SessionManager、RFCOMM adapter、测试 seam | 一台设备连接阻塞时，另一地址仍能断开/查询/执行；超时后 socket、任务、锁均释放 |
| P1-B | 面板代际与状态真值 | 防旧设备回写新面板，未知态不伪装为关闭 | Panel state/cache/controller | 快速切换 MAC、同型号切换和延迟回调均不污染当前面板 |
| P1-C | 写事务与恢复 | 互斥项只在主命令成功后处理，所有 pending 有界清理 | 写入协调器、统一 timeout/finally | 成功、失败、超时、断连四类路径均可重试且无永久灰置 |
| P1-D | 日志与 Provider 热路径 | 防丢日志、限制输入、避免同步高频全量写 | LogStore、Provider handler、HookContext | 并发追加不丢记录；热路径不因单条日志长时间阻塞 |
| P2 | 轮询、绑定与媒体 | 轮询可取消、MAC 绑定唯一、壁纸 IO 化 | scheduler、registry、AppearancePage | 切换/断连立即取消旧任务；绑定无多目录冲突；预览不阻塞主线程 |
| P3 | 结构拆分 | 降低 Hook/Provider 耦合，建立长期维护边界 | 纯逻辑抽取、依赖接口、handler | 行为不变、契约测试覆盖、可按模块回滚 |
| UI | 视觉与玻璃材质 | 清晰状态、清晰前景、低性能可降级 | Compose token、GlassNavigationBar、Preference adapter | 明暗主题、大字体、低 API/低性能路径均可读且无内容模糊 |

依赖关系：`P0 → P1-A/P1-B/P1-C/P1-D → P2 → P3`；UI 的宿主自定义行依赖 `P1-B/P1-C`，液体玻璃可在 P0 后独立实施，但不得掩盖状态问题。

## 4. 状态模型与行为契约

实现前先审阅 `refreshXiberiaPanelStates()`、`probeXiberiaDeviceStates()`、地址解析与 Profile 注册调用链，产出调用关系和日志证据，再定最终状态模型。建议每个面板项表达以下状态：

| 状态 | 含义 | UI 表现与交互 |
|---|---|---|
| `UNAVAILABLE` | 型号不支持、没有对应 SET 命令，或明确不可操作 | 展示原因；不伪装成关闭态 |
| `WAITING_CONNECTION` | 当前没有有效 MAC/Profile | 显示未连接/等待连接；禁止提交设备命令 |
| `QUERYING` | 正在首次读取或主动刷新 | 显示轻量进度；限制重复查询；保留上次已确认值并标为旧数据 |
| `READY` | 有近期设备回读值，可正常操作 | 展示设备真实状态；支持开关/选项/子页 |
| `WRITE_PENDING` | 命令已提交但尚未确认 | 仅禁用当前互斥操作范围；提供进行中反馈；受现有写超时约束 |
| `ERROR` | 查询/写入失败或超时 | 保留上次确认值并标注失败；恢复可重试，不永久置灰 |
| `STALE` | 曾成功读取但当前读回失败/超时 | 以弱化时间/状态提示说明数据陈旧；不把缓存等同实时值 |

### 4.1 快照键与代际规则

面板状态快照必须以复合键和代际绑定，而不是只用功能项 `key`：

```text
PanelSnapshotKey = (normalizedMac, profileId, productId, itemKey)
PanelRequestToken = (normalizedMac, profileId, productId, generation, requestId)
```

规则如下：

- MAC 统一大写、去除分隔符差异前先保留可检索的原始输入；空地址、非法地址不得进入缓存。
- 每次当前设备地址、Profile 或 productId 发生变化，`generation += 1`，并取消/失效旧查询、旧写入回调和旧轮询。
- 异步结果回写前必须比较 `generation + normalizedMac + profileId + productId`；任一不匹配直接丢弃，并记录 `stale_result_dropped`，不得刷新当前 View。
- 同型号切换 MAC 也必须换代，不能只在 productId 变化时清理。
- `pending` 不作为设备真实状态缓存；页面重建只从快照重绘，不重发 SET。
- `CHOICE`、开关和电量缓存都必须带来源时间与状态标志，`null`/`UNKNOWN` 不得隐式转换为 `false` 或零。

### 4.2 连接与写入契约

连接、读取和写入都要有可观测的终态：`SUCCESS`、`FAILED`、`TIMEOUT`、`CANCELLED`、`STALE`。任何终态都必须完成 pending 清理、释放该地址锁、关闭临时 socket/流，并允许后续重试。

互斥写入按事务处理：先执行主命令；只有主命令明确成功，才执行兄弟项关闭；兄弟项任一失败时保留“部分成功/需重新读取”状态，不假装整组完成。事务结束后优先 GET 核实设备最终状态，再更新缓存和 UI。页面退出、设备切换、进程销毁时取消未完成事务，但不能把取消误记为设备已关闭。

状态迁移应由设备事件、查询结果、写入应答、超时和型号切换触发，不由 View 生命周期中的重复绑定触发。页面重建后允许从当前快照重绘，不应重发写命令。互斥组行为沿用 catalog 约束，只有主命令成功后才确认并更新关联项。

---

## 4. 视觉与交互方案

### 4.1 宿主动态面板

优先采用宿主 Preference 容器内可正常测量和回收的自定义 Preference 行/布局。候选实现：继承宿主可用的 `Preference` 基类并自定义 `onCreateViewHolder`/绑定 View；若宿主 R8 类、构造器或主题接口不稳定，则用标准 Android `Preference` 和 `LinearLayout`/`FrameLayout`，通过反射只调用必要的公开语义方法。

每行建议层级：左侧型号/功能图标或类别标记；中间标题、最多两行摘要/状态；右侧开关、当前档位或进入箭头。状态提示不应只靠颜色表达。列表顶部可加一条紧凑连接状态摘要；不要再嵌套大卡片，避免破坏宿主 Preference 页面节奏。

验收前对比三种方案：

| 方案 | 适用 | 代价/限制 |
|---|---|---|
| 保留 `COUISwitchPreference`，只完善标题/摘要/分组/状态 | 最稳健的短期方案 | 视觉控制有限，不能完全统一卡片和行高 |
| 自定义 Android Preference 行 | 推荐中期目标 | 需确认宿主 Preference 版本、主题属性、ViewHolder 生命周期和回调接口 |
| 在原分类中注入独立自定义 ViewGroup/ComposeView | 仅当 Preference 扩展能力不足 | 容易受宿主重建、滚动、嵌套滚动和 Context/主题影响；ComposeView 还需处理生命周期和 CompositionStrategy |

禁止一开始就叠加覆盖层或替换整个宿主详情页面。若自定义行无法稳定适配所有宿主版本，回退到原生 Preference 并保留状态语义改进。

### 4.2 模块配置页与液体玻璃

- 建立少量共享 UI token：页面水平边距、区块间距、卡片表面、标题/正文/辅助文字、状态色、描边、高光和圆角。
- 延续 Miuix 组件，避免 Miuix 与 Material3 控件在同一设置行混排；Material3 可继续承担 Compose 容器和主题适配，但可见控件由统一设计系统提供。
- `GlassNavigationBar` 拆成背景材质层、细描边/顶部高光层、清晰内容层；背景用低 alpha 表面和轻微色调混合。Android 12+ 可评估 RenderEffect/平台 blur；低 API 或设备性能不合适时回退到半透明纯色表面，不能模糊整个组件。
- 模糊强度映射到背景材质，不允许模糊半径为零时仍启用 blur；高刷新频率滚动时避免对大区域重复创建昂贵效果。
- `showDivider` 当前设置须核对是否实际生效。只保留有对应视觉效果的设置项，避免 UI 控件修改后无可见差异。
- 预览必须复用实际底栏 Composable；样式切换和透明度调整即时预览，暗色/动态色均需校验文字对比度。

### 4.3 动画规范

动画只用于表达状态变化和层级，不用于持续装饰。动态面板和模块 UI 共用以下规则：

| 场景 | 动画 | 建议参数 | 约束 |
|---|---|---|---|
| 页面/面板首次出现 | 淡入 + 轻微位移 | 160~220 ms，标准 easing | 不从屏幕外飞入，不影响首屏点击 |
| 查询中 | 小型进度指示器或状态图标旋转 | 仅作用于状态图标 | 不让整行持续闪烁，不用 shimmer 伪装真实数据 |
| 查询成功 | 状态图标/摘要短暂变更 | 120~180 ms | 不改变行高，避免列表跳动 |
| 写入 pending | 开关保持明确 pending 视觉 | 立即进入，结束后 150~220 ms | 禁止连续点击；失败必须有可见恢复 |
| 失败/超时 | 轻微强调 + 摘要更新 | 180~240 ms | 不使用强烈抖动；错误不能只靠红色表达 |
| Tab/底栏切换 | 选中指示器位移或淡入 | 160~220 ms | 不重复播放大面积 blur/透明度动画 |
| 玻璃材质参数调整 | 背景色、alpha、描边渐变 | 180~260 ms | 不动画化高成本实时模糊；必要时只动画 alpha |

实现要求：

- 优先使用 Compose 的可取消状态动画；状态快速连续变化时合并到最新目标值，不能堆积动画任务。
- `Reduced motion`/系统动画关闭时，所有过渡降为立即切换或不超过 80 ms 的淡化；功能状态不可依赖动画结束回调。
- 动画过程中保持固定行高、固定底栏高度和稳定点击区域；不能用缩放造成文字模糊或布局抖动。
- 动画状态与业务状态分离，进程恢复、屏幕旋转和 View 重建后不得把旧动画误认为未完成写入。
- 真机验收记录首屏耗时、动画掉帧和低性能降级结果；不以“看起来更炫”作为通过条件。

### 4.4 模块玻璃组件的视觉分层

模块端液体玻璃组件统一为四层：底色表面、弱色调混合、细描边/顶部高光、清晰前景内容。默认以克制的透明度和对比度为主，不叠加多层阴影、渐变光斑或大面积装饰性模糊。

- 底栏和设置区块使用同一套 surface/border/content token，但不把每个设置项都做成悬浮玻璃卡片，避免卡片套卡片。
- 选中态使用稳定的色块/指示器和轻微 alpha 变化；未选中态保持足够文字对比度，不依赖透明度把内容“洗掉”。
- 玻璃关闭、设备不支持或性能预算不足时，切换到不透明 surface；布局、文字和交互完全保持一致。
- 每个组件先做静态材质版本，再加可选 blur；静态版本必须单独可验收，避免把平台 RenderEffect 当成视觉真源。
- 以真实页面截图比较四种组合：浅色/深色 × blur 开/关；同时检查大字体、动态色和透明度极值。

---

## 5. MAC 与型号反查、定时读取策略

### 型号关联核对

追踪并记录以下链路的唯一真源与归一化规则：

`Bluetooth/Melody 设备事件 → MAC 归一化 → binding/Profile 选择 → modelId/displayName → XiberiaProductCatalog.byModel → productId → panelItems(productId)`

需要覆盖：重复连接事件、同名不同 MAC、MAC 格式大小写/分隔符、Profile 更新、设备切换、断开后旧地址残留、无法反查及非支持型号。型号解析失败时不猜测，不把上一个型号的面板状态带给新地址。新增日志只记录必要关联键，避免输出无关设备信息。

### 电量与功能状态读取

- 电量仍沿用现有 5 分钟轮询，计划不增加第二条周期任务。
- 面板功能状态采用进入面板/连接确认时的初始探测、用户主动刷新或必要的状态恢复探测。避免将所有 GET 命令固定塞进 5 分钟电量任务，除非设备能力和 SPP 时长测量证明可接受。
- 查询按产品能力和有效 `cmdGet` 过滤，串行复用当前会话；单项失败不得污染其它项，也不得把未知响应映射成 `false`。
- 记录查询耗时、成功项数、失败项数、超时项和地址/Profile/productId 关联结果，确认首屏时间和蓝牙链路负载。

---

## 6. 分阶段实施

### Phase 0 · 基线与定位

- [ ] 保存当前工作区状态、构建基线与目标设备/宿主版本。
- [ ] 阅读完整的 `refreshXiberiaPanelStates()`、`probeXiberiaDeviceStates()`、定时电量轮询调用链及型号/Profile 绑定实现。
- [ ] 画出 MAC→Profile→modelId→productId 调用链，列出 xref/调用方和缓存生命周期。
- [ ] 抓取当前详情页进入、首次查询、一次开关写入、超时/断连日志；核实是否有“第一次进页无读状态”或灰态复现。
- [ ] 截取模块 UI 明暗主题与三种底栏样式基线；复现当前 blur 内容模糊。

### Phase 1 · 正确性与可观测性

- [ ] 先完成 P1-A：将 RFCOMM 阻塞连接放入可被外部关闭的执行单元；登记 socket/连接任务；超时定时器主动关闭 socket；统一 Xiberia/Sony 的终态与资源释放。
- [ ] 先完成 P1-A：将全局会话锁改为按 normalized MAC 串行；同一 MAC 的 connect/read/write 仍严格排队，不同 MAC 不互相阻塞；锁内不包跨设备无关的慢 I/O。
- [ ] 完成 P1-B：引入 `generation + requestId + (MAC, profileId, productId)` 回写校验；缓存、pending、choice 按 MAC/型号隔离；旧结果只记日志不刷新 UI。
- [ ] 完成 P1-C：统一 GET/SET 超时、`try/finally` 清理 pending；主命令失败不执行互斥兄弟项；成功后重新 GET，区分成功、部分成功、未知和取消。
- [ ] 统一每个面板项的读取/写入/未知/不可用状态表达；不改协议真值。
- [ ] 对连接未就绪、型号未解析、首次查询中、部分查询失败、写入超时、设备切换分别补可检索日志。
- [ ] 将首次进入面板的读取去重改为按 `(MAC, productId)`，确保延迟地址解析完成后会补发一次查询。
- [ ] 核对切换设备时缓存、pending 与 WeakReference 均按旧设备清理/隔离。
- [ ] 用真机日志证明五分钟轮询实际触发；不能只以常量存在作为通过标准。

### Phase 1-D · 日志与 Provider

- [x] 将 `HookLogStore` 的追加/清空改为 Provider 内串行事务；明确最大条数、单条字节上限、source/level 白名单和高频重复日志节流策略。
  - 已实施：`synchronized(lock)` 串行化 + `MAX_ENTRIES=500` + `MAX_MESSAGE_CHARS=2000` + `MAX_SOURCE_CHARS=120` + `ALLOWED_LEVELS={I,W,E,D}` + 1s 去重窗口。
- [x] 评估按行文件或 SQLite/WAL 替代全量 JSON 读改写；在迁移前保留旧格式读取和一次性迁移回滚路径。
  - 评估结论：**不引入 SQLite/WAL**（模块日志量级 < 500 条，迁移成本高于收益）。改良为**内存行缓冲 + 延迟批量落盘**（见下条），保留原 `logs_json`（JSON 数组）磁盘格式，旧数据天然可读、无迁移风险。
- [x] `append_log` 改为异步队列或批量入口，禁止 Hook 热路径等待全量 JSON 序列化；UI 读取支持最近 N 条/分页。
  - 已实施：`append` 改为 O(1) 内存追加 + `Handler` 800ms 合并窗口一次性落盘（`scheduleFlush`）；纯 JVM/无主线程 Looper 时同步落盘兜底。`readAll` 会先合并落盘保证一致；`readRecent(limit)` 供 UI 分页。
  - ⚠️ 权衡：进程被杀会丢最后 800ms 内未落盘日志（诊断日志可接受）。`flushHandler` 必须**懒加载**（否则纯 JVM 单测 `Looper.getMainLooper()` stub 抛异常会让整个 object 类初始化失败）。
- [x] `export_logs`、清空、调试广播与普通日志写入分开授权；校验 UID 对应的完整包集合，不以 `firstOrNull()` 作为强授权依据；优先使用 signature permission。
  - 已实施：`RegistryMethodPolicy.authorize()` 双闸 —— ① signature 级 `ACCESS_REGISTRY` 权限（manifest `<permission protectionLevel="signature">` + 模块 uses-permission）；② `resolveStrict`（UID 包集合非空且全部白名单）。非高权限方法不受影响。
  - ⚠️ 特意**不给 Provider 加 `android:permission`**（会连宿主调用的 `append_log`/`report_status` 一并拦掉，打断日志链）；signature 权限仅在 `call()` 内对高权限方法生效。
- [x] 记录 Provider 调用耗时、拒绝原因和丢弃计数，但日志自身不得再次递归写入同一 Provider。
  - 已实施：`ProviderStats`（`AtomicLong` 计数 + 慢调用 50ms 阈值告警），新增宽松可读方法 `get_provider_stats`；打点只走 **logcat**（tag `MelodyPlus-Pvdr`），**不**调用 `modLog`/`append`，避免递归。

### Phase 2 · 轮询、绑定、壁纸与液体玻璃

- [ ] 修正 `GlassNavigationBar` 层级，背景模糊不再作用于文字/图标。
- [ ] 将模糊能力做 API/性能降级，完成明暗主题和透明度端点测试。
- [ ] 外观页 Live Preview 复用生产组件，核对 `showDivider`、透明度、模糊开关都实际生效。
- [ ] 轮询保存明确的 Job/Runnable owner；地址切换、断连、详情页销毁时立即取消旧任务，不等待下一个 5 分钟周期自检退出。
- [ ] 面板探测去重键改为 `(normalizedMac, productId)`，并与 generation/cancellation 联动；不得让旧地址的 5 秒去重时间戳压住新设备首次查询。
- [ ] MAC 绑定保存时以 MAC 为唯一键原子替换旧 Profile 归属；迁移/解绑时清理其它 Profile 目录；发现历史冲突时记录冲突并禁止随机取 `listFiles()` 的第一个结果。
- [ ] 壁纸预览 Bitmap 改为 `produceState`/`LaunchedEffect + Dispatchers.IO`，支持取消、坏图尺寸保护、URI 权限失效提示；预览与背景采用共享缓存或明确的采样/淘汰策略。
- [ ] 清理旧主题定义中未被实际入口使用的冲突色板/字体 token；只改确认有调用关系的项。

### Phase 3 · 宿主动态面板视觉原型

- [ ] 先做 `COUISwitchPreference` 保底增强：摘要状态、标题长度、顺序、禁用原因与等待提示。
- [ ] 在隔离分支或 feature flag 下实现一个自定义 Preference 原型，不改变现有命令监听与产品目录。
- [ ] 在 MC05/另一种能力矩阵不同的型号上比对宿主列表行高、主题色、开关点击、滚动、屏幕旋转/Activity 重建。
- [ ] 通过后再将其它面板项迁移；失败则保持保底实现。

### Phase 3-D · 结构拆分与备份策略

- [x] 先为 `MelodyPanelHook` 抽取纯状态模型、快照键、代际校验和写事务协调器，再移动反射/View 注入；每次只迁移一个职责。
  - 已完成（纯逻辑层）：抽出 `hook/PanelStateLogic.kt` —— `PanelContext`（代际+MAC+Profile+productId 纯模型 + `isCurrent` 纯比较）、`MelodyMemberNames`（成员名归一化 / 充电与电量键判定 / 电量槽位归类 / 连接状态名判定 / 连接布尔名判定）、`XiberiaPanelQuery`（GET 命令选择 + CHOICE 应答解析）。
  - Hook 侧保留同名 private 薄委托，**调用点零改动**，行为不变。
  - 反射/View 注入仍留在 Hook（未迁移）。
- [x] 将 `DeviceRegistryProvider.call()` 的输入校验、授权、绑定、日志、导出和调试广播委托给独立 handler；Binder 入口不得直接承载大块耗时 I/O。
  - 已完成（纯函数委托）：`append_log` 输入归一化 → `HookLogStore.normalizeAppendInput`；caller 授权白名单与判定 → `RegistryCallerPolicy`（`specialUidLabel` / `resolveLoose` / `resolveStrict`）；**高权限方法门禁 → 新增 `RegistryMethodPolicy`（单一真源，`call()` 入口统一拦截 export/clear/inject）**；调试注入/设备图请求解析 → `DebugInjectRequest` / `DeviceImageRequest`（`resolveFrom` 为纯函数）。
  - 仍未拆为独立 Handler 类（未新建 class），但 `call()` 的每个分支均已缩减为「取 context → 调纯函数/Store」的薄适配；跨设备函数体约 200 行 → 约 150 行。
- [x] 建立备份分类表：UI 偏好可迁移；MAC/Profile、设备状态、持久化 URI 权限、Hook 运行态和日志默认视为设备本地，按产品需求决定排除或迁移校验。
  - 已落地于 `res/xml/backup_rules.xml`（API <31）与 `res/xml/data_extraction_rules.xml`（API ≥31）：`module_ui_config` 列入 include（可迁移）；`module_device_registry` / `mac_profile_cache` / `module_hook_status` / `module_hook_logs` 全部 exclude；file/database/external 域排除。外部绑定目录 `/sdcard/Download/MelodyPlus/**` 不在沙箱，天然不参与。
- [x] 明确 Android 12+ cloud backup 与 device transfer 的规则差异；修改 backup/data extraction 规则前先写迁移测试和旧版本回滚方案。
  - `cloud-backup` 与 `device-transfer` 采用**同一分类**（本模块无跨版本待迁移 schema），差异已在 XML 注释中说明；规则文件头已写明「修改前先补迁移测试与回滚方案」。
  - 迁移**测试**未新增（备份规则属 manifest/资源级配置，本地 JVM 单测无法覆盖；留待真机 device-transfer 验证）。

### Phase 4 · 构建、真机与回归

- [ ] `:app:compileDebugKotlin`、单元测试、`assembleDebug` 通过。
- [ ] 若离线测试因依赖缺失未能执行，单独记录缺失坐标和失败阶段，不得写成“测试通过”；补依赖后重新执行断言。
- [ ] 安装更新 APK；只重启作用域内目标 App 进程，不触发 zygote/框架重启。
- [ ] 验证进入页面自动读取状态、点击开关 pending 有界、超时后可重试、设备断开后恢复。
- [ ] 验证电量连接即采与 5 分钟主动查询各有日志证据，三路数据缺失不会被 0/旧值伪装。
- [ ] 验证 MAC 切换后型号/面板项目同步切换，不残留上一个设备状态。
- [ ] 记录安装 APK 路径、构建输出、设备/宿主版本、关键日志、遗留项。

---

## 7. 测试矩阵与证据格式

### 7.1 单元/契约测试

| 测试组 | 必测场景 | 预期 |
|---|---|---|
| RFCOMM connect | 成功、立即失败、阻塞后超时、取消、socket close 抛异常 | 在规定时间内返回；资源释放；后续连接不被旧任务占用 |
| SessionManager | 同 MAC 并发读写、不同 MAC 并发、连接失败后重试、断开期间读写 | 同 MAC 保序；不同 MAC 不互锁；失败后可重试；无幽灵会话 |
| Panel generation | A 查询后切到 B、A/B 同 productId、旧写入晚到、重复绑定事件 | 旧结果被丢弃；B 只显示 B 快照；pending 不跨设备残留 |
| Panel state | UNKNOWN、STALE、部分 GET 失败、无 GET 能力、解析异常 | 不把未知显示成关闭；可重试；单项失败不污染其它项 |
| Write transaction | 主命令失败、兄弟项失败、整体超时、取消、成功后 GET 不一致 | 不执行错误的互斥关闭；pending 必清；显示部分成功/需重读 |
| LogStore | 多线程/多进程追加、清空与追加竞争、超长消息、重复日志洪峰 | 不丢已接受记录；输入被限制；清空语义明确；有节流计数 |
| Registry binding | 同 MAC 重绑 Profile、迁移、解绑、重复连接、格式变体、历史冲突 | 只有一个有效归属；冲突可见且不随机选择 |
| Wallpaper | 慢 URI、坏图、权限失效、取消、重复重组、更换大图 | 不阻塞主线程；错误可恢复；旧任务取消；采样受限 |

测试 seam 要求：RFCOMM、BluetoothDevice、ContentResolver/Provider、时钟、Handler/CoroutineDispatcher、View 回调均通过接口或可替换 fake 注入；不得为了测试复制一套与生产不同的状态逻辑。

### 7.2 真机回归矩阵

| 维度 | 最低组合 |
|---|---|
| 设备 | 至少两个不同 MAC；至少一个支持多项互斥能力的型号；至少一个能力矩阵不同的型号 |
| 操作 | 进入详情、快速切换、连接/断开、连续点击、写入中退出页面、旋转/重建、进程被回收后重进 |
| 链路 | 正常 SPP、设备不可达、连接中断、连接阻塞/延迟、部分 GET 超时、SET 后无回读 |
| UI | 深色/浅色、大字体、系统动画开关、低性能/无 blur 降级、TalkBack 基础朗读 |
| 轮询 | 首次连接即采、5 分钟触发、切换后取消旧任务、断连后不再发请求 |
| 绑定 | 重绑型号、同名不同 MAC、MAC 格式差异、旧 Profile 残留、历史冲突 |

### 7.3 日志与证据字段

每个关键操作至少记录以下结构化字段，地址可按现有隐私策略做归一化/截断：

```text
event, normalizedMac, profileId, productId, itemKey,
generation, requestId, result, elapsedMs, errorClass, source
```

最低事件集合：`connect_start`、`connect_timeout`、`connect_cancelled`、`connect_end`、`session_lock_wait`、`probe_start`、`probe_end`、`stale_result_dropped`、`write_start`、`write_end`、`poll_scheduled`、`poll_cancelled`、`binding_conflict`、`provider_rejected`。

每次验收保存：构建命令与完整结果、APK 路径、设备/API/宿主版本、操作时间线、关键日志片段、截图/录屏（UI 项）、失败复现步骤和回滚提交。只把“代码改动 + 编译通过 + 真机行为符合”记为完成。

---

## 8. 验收标准

### 功能

- 首次打开支持型号的详情面板后，会触发一次受能力表约束的状态探测；已有设备状态正确回填，未知/失败不显示成关闭。
- 未连接或 MAC 暂未解析时能看见明确状态；地址就绪后触发补探测，不需要退出重进。
- 任一开关写入成功、失败、超时后均能退出 pending；失败后可重试，不永久变灰。
- 5 分钟电量轮询有实际请求与结果/错误日志；没有重复轮询链；断开/切换后旧链停止。
- MAC/Profile/productId 切换后面板内容、状态缓存和型号名称对应当前设备。

### 视觉

- Glass 模糊只作用于背景表面，文本边缘清晰；关闭 blur、透明度为 0/1、系统深浅色切换均可读。
- 模块配置四 Tab 使用一致排版与 Miuix 控件风格；关键操作、状态、空态有清晰层级。
- 宿主面板在目标 ColorOS/宿主版本中不出现重复添加、错序、点击区域错位、滚动跳动或 Activity 重建后失效。
- TalkBack 可区分标题、摘要、当前状态和开关；不以颜色作为唯一状态信号。

### 稳定性与性能

- 无主线程 SPP/磁盘读取；模糊/图片加载不阻塞首屏。
- 页面刷新和 Preference 重建不会重复下发命令。
- 单项查询失败不会卡住整组面板；超时和缓存都有明确时效/标记。
- 至少覆盖 Android 版本/API 下限、当前目标设备 Android 16、深色/浅色和大字体布局。

---

## 9. 变更边界与风险控制

- 一次只修改一个主要层面：先状态与日志，再玻璃背景，再宿主自定义行，便于回归归因。
- 不在本计划第一阶段改 `HeadsetCommand`、协议 payload、产品能力真值或电量轮询间隔。
- 自定义 Preference 是宿主耦合风险最高部分，必须有 feature flag/可回退路径；不允许一次性替换整页。
- 模糊在不同 GPU/Android API 上视觉和成本不同，必须有非模糊降级，不依赖单一平台能力。
- 任何一次修改无效，先重读改动并查完整调用链、缓存/重建和真实 View 绑定点；同一处连续两次无效后停止重复试值，回到定位阶段。
- Provider、日志、SPP 和 Hook 都属于跨进程/异步链路，新增诊断日志不得反过来阻塞或递归进入被诊断路径。
- 不把“常量存在”“代码编译通过”“日志打印过”当作行为完成；必须有对应的测试或真机证据。
- 未在设备上验证的项标注“代码已改、待真机验证”，不得记为已完成。

---

## 10. 当前状态

| 工作项 | 状态 |
|---|---|
| 动态面板按型号注入 | 已有实现；本计划不重做能力目录（未改） |
| P1-A SPP 可取消超时 | 已实施：Xiberia/Sony 阻塞 connect 移入 `async` 子协程，超时后从外部 `close(socket)` 打破阻塞并 `await` 回收；`CONNECT_TIMEOUT_MS=8s`（Sony 新增）。编译通过 |
| P1-A 按 MAC 会话锁 | 已实施：`HeadsetSessionManager` 全局 `Mutex` 改为 `addressLocks: ConcurrentHashMap<String, Mutex>`，六处入口 `lockFor(normalize(address))`。编译通过 |
| P1-B generation/cache 隔离 | 已实施：新增 `panelGeneration`/`PanelContext`/`isPanelContextCurrent`/`bindActivePanelContext`；probe 与写入回写前校验，切换设备/型号后旧结果丢弃并记 `stale_result_dropped`；去重键改 `(MAC, productId)`。编译通过 |
| P1-C 写事务与未知态 | 已实施：写入改 `try/finally` 清 pending；互斥兄弟项仅在主命令成功后执行并统一超时；未知态不再伪装为关闭（summary 标注"未知/状态未读取"）；Choice 改单选并预勾选当前档位。编译通过 |
| P1-D 日志与 Provider | 已实施：`HookLogStore` 内存行缓冲 + 800ms 延迟批量落盘（O(1) 追加，去掉热路径全量 JSON 读改写）+ 长度上限 + 重复节流 + `readRecent`；`ProviderStats` 自观测（耗时/拒绝/丢弃 + 慢调用告警，新增 `get_provider_stats`）；`export/clear/inject` 收紧为 signature 权限 + `strictCallerPackage` 双闸。编译通过，90 项单测全绿 |
| 日志装载期落盘 | 已实施：`HookContext.reportToProvider` 在 `currentApplication()` 为 null 时回退 `getSystemContext()`，仍不可得则暂存（`HookContextLog`，上限 200）后续补发；`initZygote` 不再从 zygote 发 ContentResolver 调用。编译通过，待真机验证 |
| 日志导出目录 | 已实施：`LogExporter` 改写**模块私有目录** `files/logs/`（去掉公共 `Download/` 与 MediaStore 分支），保留真实 MAC（按用户要求不脱敏）|
| 面板初始状态探测 | 逻辑已增强（去重键、代际校验、未知态语义）；**首次进入/延迟地址/部分失败行为待真机验证** |
| 电量 5 分钟轮询 | 已实施取消能力：登记 `batteryPollRunnables`，`cancelBatteryPolling` 在设备切换/断连时 `removeCallbacks` 立即取消；间隔不改。是否持续运行待真机日志 |
| MAC→型号反查 | 已实施唯一化：`save` 前 `purgeOtherProfiles` 清理同 MAC 其它目录；`profileIdForMac` 全量收集 + 冲突记 `binding_conflict` + 决定性选择。编译通过 |
| Glass blur 内容层缺陷 | 已修复：`GlassNavigationBar` 拆为「背景材质层（唯一被 blur）+ 高光/描边 + 清晰前景内容层」；Android 12+ 才启用 blur，低版本降级 |
| 壁纸预览线程/缓存 | 已实施：`produceState` + `Dispatchers.IO`（可取消）；坏图尺寸保护；权限成功才落盘 URI，失败提示 |
| 卡片透明度全页统一 | 已实施：`GlassCard` 新增 `fillColor` 参数（alpha 统一由 `opacity` 控制）；概览/设备/关于三页从 Miuix 不透明 `Card` 全部替换为 `GlassCard(opacity = cardOpacity)`，`cardOpacity` 经 `MainTabsScaffold` 逐层传入；语义色卡片（存储权限/已匹配设备）改 `fillColor` 保留主色容器语义。真机验收：卡片不透明度滑块全页生效 |
| 新自定义宿主面板 | 未实现；仍按 Phase 0/1 证据决定是否采用 |
| 结构拆分（P3） | 已实施（纯逻辑层完整）：`hook/PanelStateLogic.kt`（`PanelContext`/`MelodyMemberNames`/`XiberiaPanelQuery`/**`ConnectedFieldKind`+`ReflectiveFieldClassifier`**）+ `HookLogStore.normalizeAppendInput` + `RegistryCallerPolicy` + **`RegistryMethodPolicy`/`DebugInjectRequest`/`DeviceImageRequest`**；反射字段分类规则已抽离，Hook 仅保留 `Field` 句柄装配 |
| 备份规则（P3-D） | 已实施：`backup_rules.xml`（<31）+ `data_extraction_rules.xml`（≥31），UI 偏好可迁移、设备绑定/状态/日志排除 |
| 单元测试 | **84 项全部通过（0 失败）**。累计新增 36 项：`PanelStateLogicTest`(10) + `HookLogStorePureTest`(8) + `DeviceRegistryPureTest`(5) + **`ReflectiveFieldClassifierTest`(7)** + **`RegistryCallLogicTest`(6)**；并修复 2 个基线 stale 断言 |
| 编译告警清理 | 移除 4 处多余 `?.`/`!!`（`MelodyPanelHook` / `ModuleAssets` / `BluetoothAudioConnectionHook`×2），行为不变 |
| APK 构建 | `:app:assembleDebug` 成功，产物 `app/build/outputs/apk/debug/app-debug.apk`（约 46 MB，2026-10-08）|
| 网络依赖测试 | 联网模式可拉取 `junit:4.13.2` / `hamcrest-core:1.3` 并执行；纯离线不可用 |
| APK 构建 | `:app:assembleDebug` 成功，产物 `app/build/outputs/apk/debug/app-debug.apk`（约 46 MB，2026-10-08）|
| 真机安装与回归 | 未执行（本环境未做安装/蓝牙真机验证）|
