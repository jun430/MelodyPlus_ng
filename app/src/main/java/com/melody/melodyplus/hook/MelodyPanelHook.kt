package com.melody.melodyplus.hook
import com.melody.melodyplus.hook.modLogT

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.res.Resources
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import android.widget.Toast
import com.melody.melodyplus.adapter.xiberia.XiberiaCommands
import com.melody.melodyplus.adapter.xiberia.XiberiaModelProfiles
import com.melody.melodyplus.adapter.xiberia.XiberiaProductCatalog
import com.melody.melodyplus.adapter.xiberia.XiberiaSpoof
import com.melody.melodyplus.bridge.AdapterRegistry
import com.melody.melodyplus.bridge.BluetoothAudioPopupGate
import com.melody.melodyplus.bridge.BluetoothPopupContract
import com.melody.melodyplus.bridge.BoundDevice
import com.melody.melodyplus.bridge.DeviceNameRuleState
import com.melody.melodyplus.bridge.DeviceProfile
import com.melody.melodyplus.bridge.DeviceProfiles
import com.melody.melodyplus.bridge.DeviceRegistryStore
import com.melody.melodyplus.bridge.BatteryShare
import com.melody.melodyplus.bridge.DeviceImageAssets
import com.melody.melodyplus.bridge.DeviceImageStore
import com.melody.melodyplus.bridge.RegistryCircuit
import com.melody.melodyplus.bridge.RegistryContract
import com.melody.melodyplus.bridge.HeadsetSessionManager
import com.melody.melodyplus.bridge.ModuleDeviceRegistry
import com.melody.melodyplus.core.AncMode
import com.melody.melodyplus.core.BatteryState
import com.melody.melodyplus.core.CommandResult
import com.melody.melodyplus.core.HeadsetCommand
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object MelodyPanelHook : HookContext() {
    private const val TAG = "MelodyPlus"
    // 弹窗三合一主图（assets: device_images/*/main.png）原始像素尺寸，用于 FIT_CENTER 反推绘制矩形。
    private const val POPUP_MAIN_IMAGE_WIDTH = 2368f
    private const val POPUP_MAIN_IMAGE_HEIGHT = 793f
    // 三合一图内「左耳 / 右耳 / 充电仓」三段图案的横向中心占比（x_center / imageWidth）。
    private const val POPUP_SEGMENT_CENTER_LEFT = 0.1486f
    private const val POPUP_SEGMENT_CENTER_RIGHT = 0.4565f
    private const val POPUP_SEGMENT_CENTER_CASE = 0.8247f
    // [槽位微调] 三槽横向占比偏移（正=右移、负=左移、0=严格对齐图案中心）。
    //   需求：左耳/右耳电量往左挪一点，仓电量往右挪一点。
    //   0.01f ≈ 5px @ contentW≈520px；需更大幅度直接调这三个值即可。
    private const val POPUP_SLOT_NUDGE_LEFT = -0.02f
    private const val POPUP_SLOT_NUDGE_RIGHT = -0.02f
    private const val POPUP_SLOT_NUDGE_CASE = 0.03f

    private const val MODE_ITEM_CLASS = "L4.a"
    private const val WHITELIST_CLASS = "com.oplus.melody.common.data.WhitelistConfigDTO"
    private const val FUNCTION_CLASS = "com.oplus.melody.common.data.WhitelistConfigDTO\$Function"
    private const val NOISE_MODE_CLASS = "com.oplus.melody.common.data.WhitelistConfigDTO\$NoiseReductionMode"
    private const val NOISE_INFO_CLASS = "com.oplus.melody.model.repository.earphone.NoiseReductionInfoDTO"
    private const val EARPHONE_DTO_CLASS = "com.oplus.melody.model.repository.earphone.EarphoneDTO"
    private const val NOISE_VO_CLASS = "I8.x"
    private const val ONESPACE_NOISE_VO_CLASS = "s7.c"
    private const val ONESPACE_HEADER_CLASS = "com.oplus.melody.onespace.items.OneSpaceHeaderPreference"
    private const val ONESPACE_CONNECT_CLASS = "com.oplus.melody.onespace.items.OneSpaceConnectPreference"
    private const val DETAIL_MAIN_ACTIVITY_CLASS = "com.oplus.melody.ui.component.detail.DetailMainActivity"
    private const val APP_COMPAT_IMAGE_VIEW_CLASS = "androidx.appcompat.widget.AppCompatImageView"
    private const val DETAIL_MODEL_VIEW_CLASS = "com.oplus.melody.ui.widget.MelodyDetailModelView"
    private const val DETAIL_MODEL_GLIDE_LISTENER_CLASS =
        "com.oplus.melody.ui.widget.MelodyDetailModelView\$b"
    private const val DETAIL_MODEL_TIMEOUT_CALLBACK_CLASS = "B8.g"
    private const val DETAIL_MODEL_LIFECYCLE_CLASS = "F8.b"
    private const val DETAIL_NOISE_ITEM_CLASS = "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem"
    private const val DETAIL_NOISE_LISTENER_CLASS = "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem\$a"
    private const val DETAIL_CONNECTION_ITEM_CLASS =
        "com.oplus.melody.ui.component.detail.connectioninfo.ConnectionInfoItem"
    // [修复·Melody 16.9.1] 旧版类名 O7.G / O7.O 在当前宿主已不存在 → findMethodOrNull 恒为 null
    //   → hookDetailDseePreference 整体不注册 → DSEE / MC05 面板注入彻底不触发。
    // 真身（host_decoded app / source 双确认）：
    //   DetailMainFragment.java           → LI8/H;
    //   DetailMainPreferenceFragment.java → LI8/Q;  （其 r() 内 q(0x7f14000f) 装载 detail_main_preference.xml）
    private const val DETAIL_MAIN_FRAGMENT_CLASS = "I8.H"
    private const val DETAIL_PREFERENCE_FRAGMENT_CLASS = "I8.Q"
    private const val DETAIL_SETTINGS_OBSERVER_CLASS = "H8.a"
    private const val CONNECTION_INFO_VO_CLASS = "O7.b"
    private const val BATTERY_INFO_VO_CLASS = "W7.a"
    private const val SET_COMMAND_STATE_CLASS = "com.oplus.melody.model.repository.earphone.O"
    private const val DEVICE_INFO_MANAGER_CLASS = "com.oplus.melody.btsdk.api.manager.DeviceInfoManager"
    private const val DEVICE_INFO_CLASS = "com.oplus.melody.btsdk.api.data.DeviceInfo"
    /** 发现弹窗入口 g6.j = DiscoveryActionManagerServerImpl（含 p()，真正 startActivity）。 */
    private const val SERVER_IMPL_CLASS = "g6.j"
    /** 发现弹窗基类 g6.j / g6.a 的父类 = com.oplus.melody.model.scan.a，含 static a() 定位器。 */
    private const val BASE_CLASS = "com.oplus.melody.model.scan.a"
    private const val DEVICE_CONTROL_WIDGET_CLASS = "com.oplus.melody.ui.widget.devicecontrol.DeviceControlWidget"
    private const val DEVICE_CONTROL_MODE_BUTTON_CLASS = "com.oplus.melody.ui.widget.devicecontrol.c"
    private const val BATTERY_VIEWS_CLASS = "com.oplus.melody.ui.widget.MelodyBatteryViews"
    private const val STATUS_INFO_VIEWS_CLASS = "com.oplus.melody.ui.widget.MelodyStatusInfoViews"
    private const val OPS_NOISE_VO_CLASS = "com.oplus.melody.ui.component.detail.opsreduction.a"
    private const val OPS_SEEKBAR_CLASS =
        "com.oplus.melody.ui.component.detail.opsreduction.buttonseekbar.NoiseReductionButtonSeekBarView"
    private const val MYDEVICES_CONNECT_STATE_CLASS = "com.oplus.mydevices.sdk.device.ConnectState"
    private const val MYDEVICES_CONNECTION_CLASS = "com.oplus.mydevices.sdk.device.Connection"
    private const val COUI_SWITCH_PREFERENCE_CLASS =
        "com.coui.appcompat.preference.COUISwitchPreference"
    private const val DSEE_PREFERENCE_KEY = "melodyplus_dsee"
    private const val DETAIL_PREFERENCE_FRAGMENT_TAG = "DetailMainPreferenceFragment"
    private const val DETAIL_DSEE_CATEGORY_KEY = "earphone"
    /** 动态面板项在「耳机功能」分类内的 order 基数（DSEE 1000 / MC05 旧面板 2000，本面板排更后）。 */
    private const val XIBERIA_PANEL_ORDER_BASE = 3000
    /**
     * 面板真实状态回读：单项 GET 的超时（毫秒）。超时按"未应答"处理，不写 false。
     *
     * [修复·首项必落空] 旧值 800ms 小于 XiberiaRfcommClient.RESPONSE_TIMEOUT_MS（1500ms）：
     *   外层 withTimeoutOrNull 会先于内部响应等待触发，且首项还要承担懒连接建链成本
     *   → 实测 probed=1 answered=0、面板停在「状态未读取」。
     *   现 2000ms = 内部 1500ms + 500ms 余量；连接成本已由 probe 前置 connect 剥离。
     */
    private const val PROBE_TIMEOUT_MS = 2_000L
    /** 面板回读前置建链超时（毫秒）：懒连接（SPP connect）不计入单项 GET 超时预算。 */
    private const val PROBE_CONNECT_TIMEOUT_MS = 10_000L
    /** 回读去重窗口（毫秒）：面板多次注入时，窗口内只真正探测一次。 */
    private const val PROBE_DEDUP_MS = 5_000L
    /**
     * 面板**写入**（SetSwitch/SetLevel）整体超时（毫秒）。
     *
     * 必要性：`HeadsetSessionManager` 用**单一全局 Mutex** 串行化所有会话操作，且
     * `XiberiaRfcommClient.connect()` 的 `socket.connect()` 自身**不带超时**——SPP 半死
     * 链路（耳机移远/被官方 App 抢占/蓝牙空转）时 connect/execute 会长时间阻塞。
     * 若这里不设超时，`requestXiberiaPanelChange` 的协程永不返回 →
     * `pendingXiberiaStates[key]` 永不清除 → `refreshXiberiaPanelStates()` 一直
     * `setEnabled(false)` → **开关永久变灰卡死**（用户实测现象）。
     * 超时后按失败处理：清除 pending、写回原 checked、恢复 enabled，并提示失败。
     */
    private const val PANEL_WRITE_TIMEOUT_MS = 6_000L
    private val DETAIL_DSEE_RETRY_DELAYS_MS = longArrayOf(0L, 250L, 750L, 1_500L)
    /** androidx.preference 基类（宿主 melody 面板框架真源，R8 混淆后方法名：e=addPreference / g=findPreference / h=getPreference）。 */
    private const val PREFERENCE_GROUP_CLASS = "androidx.preference.PreferenceGroup"
    private const val PREFERENCE_CLASS = "androidx.preference.Preference"

    private const val RES_MELODY_UI_CONNECTED = 0x7f1104e1
    private const val RES_MELODY_UI_UNCONNECT = 0x7f1107f5
    private const val RES_DETAIL_NORMAL_IMAGE = 0x7f090457
    private const val DETAIL_NORMAL_IMAGE_NAME = "normal_image"

    // ===== 单主图（宿主原生图位直贴；全型号统一）=====
    // 图片资源统一走 `DeviceImageAssets`（`<型号目录>/{main,left,right,case}.png`），
    // 用户自定义图片统一走 `Download/MelodyPlus/images/<profileId>/<slot>.png`。
    // 主图位只取 `main` 单图；三路电量交回宿主原生控件渲染，模块不再自绘容器。
    /** 宿主连接发现弹窗「主图位」容器 resource 名（crop_container 内仅 dress/video，无主图本体）。 */
    private const val POPUP_HOST_CROP_CONTAINER_ID = "melody_app_discovery_crop_container"
    /**
     * 宿主连接发现弹窗「真·主图位」resource 名 / id。
     * 实测 `res/layout/melody_app_dialog_discovery.xml`：
     *   melody_app_discovery_image_container(0x7f090376) → melody_app_discovery_image_view(0x7f090377)。
     * 注意：本文件旧常量 [POPUP_MAIN_IMAGE_ID]=0x7f090370 实际指向 `melody_app_discovery_full_background`
     *   （match_parent 全屏背景图），并非主图位 —— 这是「主图位采样失败 / 三图装不上 / 隐藏错误视图」的根因。
     */
    private const val POPUP_HOST_MAIN_IMAGE_NAME = "melody_app_discovery_image_view"
    private const val POPUP_HOST_MAIN_IMAGE_ID = 0x7f090377
    /**
     * 弹窗「小图位」（宿主设备图/动画，与主图位同屏叠加 → 用户端「两张耳机图」）。
     * 宿主 `DiscoveryBatteryDefaultViewHolder`(b) 字段 e 持有它，`b.d(...)` 加载设备图。
     * 单设备弹窗里它只会与主图位重叠，故一律清空隐藏。
     */
    private const val POPUP_HOST_SUB_IMAGE_NAME = "melody_app_discovery_animation_iv"
    private const val POPUP_HOST_SUB_IMAGE_ID = 0x7f09035e
    /** 弹窗主图快速贴图（零闪帧）用的宿主 ImageView 类名（覆写了 setImageDrawable）。 */
    private const val MELODY_COMPAT_IMAGE_VIEW_CLASS = "com.oplus.melody.ui.widget.MelodyCompatImageView"
    /** 快速贴图 Drawable 缓存有效期：短，仅用于压掉 Glide 连续回填时的重复 Provider IPC。 */
    private const val FAST_IMAGE_CACHE_MS = 1_200L
    /** 宿主连接发现弹窗「电量图标」resource 名（ImageView，36x32dp，破损设备图标）。 */
    private const val POPUP_HOST_BATTERY_ERROR_ID = "melody_app_battery_error"
    /**
     * 宿主连接发现弹窗「电量数字」文本 resource 名。
     * 实测 `melody_ui_battery_style1.xml`：每个电量格 = device_icon + progress + melody_app_battery_tv(12dp 数字)。
     * 用户看到的「两个裸数字 100 / 97」（无 %、浮在图上）即该 TextView ——
     *   旧清理名单只含 melody_app_battery_error（破损图标），从未命中它，故残留。
     */
    private const val POPUP_HOST_BATTERY_TV_ID = "melody_app_battery_tv"
    /** 弹窗电量格/容器 resource 名（ViewStub 展开宿主），一并隐藏，避免残留整格电量。 */
    private val POPUP_HOST_BATTERY_EXTRA_IDS = setOf(
        "melody_app_discovery_battery_left",
        "melody_app_discovery_battery_center",
        "melody_app_discovery_battery_right",
        "melody_app_discovery_connected_battery_buds",
        "melody_app_discovery_connected_battery_box",
        "melody_app_discovery_connected_battery_neck",
        "melody_app_battery_device_icon",
        "melody_app_battery_progress",
    )
    /** 详情页电量补采挂起的哨兵键（用于 detailRefreshPosted 去重）。 */
    private const val DETAIL_REFRESH_PENDING_KEY = "__pending__"
    private const val TEXT_CONNECTED = "\u5df2\u8fde\u63a5"
    private const val TEXT_UNCONNECTED = "\u672a\u8fde\u63a5"

    private const val MELODY_MODE_OFF = 1
    private const val MELODY_MODE_NOISE_REDUCTION = 5
    private const val MELODY_MODE_TRANSPARENCY = 2

    private const val PROTOCOL_OFF = 1
    private const val PROTOCOL_NOISE_REDUCTION = 2
    private const val PROTOCOL_TRANSPARENCY = 3

    private const val NOISE_UI_VERSION_BASIC = 1
    private const val SET_COMMAND_SUCCESS = 0
    private const val STATE_REFRESH_DELAY_MS = 650L
    private const val STATE_REFRESH_THROTTLE_MS = 15_000L
    /**
     * 宿主原生电量轮询周期（5 分钟）。
     *
     * 伪装耳机场景下宿主不会主动回调电量，而真实耳机电量变化很慢，5 分钟足以保持同步，
     * 又不至于频繁建 SPP 会话。轮询只在「模块作用域已激活（activeModuleAddress 非空）」时自续期。
     */
    private const val BATTERY_POLL_INTERVAL_MS = 5 * 60 * 1000L
    private val AUDIO_CONNECTION_ACTIONS = setOf(
        BluetoothDevice.ACTION_ACL_CONNECTED,
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED",
    )
    private val SUPPORTED_PROTOCOL_INDICES = setOf(
        PROTOCOL_OFF,
        PROTOCOL_NOISE_REDUCTION,
        PROTOCOL_TRANSPARENCY,
    )

    private val submittingModeItems = ThreadLocal.withInitial { false }
    private val replacingDisconnectedText = ThreadLocal.withInitial { false }
    private val replacingProductImage = ThreadLocal.withInitial { false }
    private val forcingWidgetEnable = ThreadLocal.withInitial { false }
    private val scope = CoroutineScope(Dispatchers.IO)
    private val discoveryPopupGate = BluetoothAudioPopupGate()
    private val connectedFieldPlans = ConcurrentHashMap<Class<*>, List<ConnectedFieldPlan>>()
    private val batteryFieldPlans = ConcurrentHashMap<Class<*>, List<BatteryFieldPlan>>()
    private val refreshInFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastRefreshScheduledAt = ConcurrentHashMap<String, Long>()
    /** 详情页电量读取在途集合（addr）：详情页无宿主电量回调，需模块主动建 SPP 会话补采。 */
    private val detailBatteryInFlight = ConcurrentHashMap.newKeySet<String>()
    /** 详情页已取到的最新电量（addr → BatteryState），用于解析出地址后快速回填与去重。 */
    private val detailBatteryCache = ConcurrentHashMap<String, BatteryState>()
    /**
     * [修复·渲染早于补采] 发现弹窗最近一次成功渲染用的电量快照（addr → BatteryState）。
     *
     * 背景：:fg 进程的某一次 h() 渲染可能早于主进程补采写回（currentBatteryState 此刻仍缺仓/某路），
     * 旧实现直接用残缺 state 注入 → 宿主按空值隐藏对应槽 → 表现为「首连正常、刷新后仓电量消失」。
     * 现按槽与快照合并（实时值优先，缺槽用上次成功值补齐），保证渲染永不退化。连接时清空，
     * 避免上一会话残留掺入。
     */
    private val popupBatteryLastGood = ConcurrentHashMap<String, BatteryState>()
    /** 已排入主线程队列等待「地址就绪」的详情页补采任务（addr），避免重复排。 */
    private val detailRefreshPosted = ConcurrentHashMap.newKeySet<String>()
    /** 详情页地址尚未解析时的挂起补采请求；等 moduleProfileForAddress 解析出 MAC 后统一触发。 */
    private val pendingDetailRefresh =
        java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** 已启动 5 分钟电量轮询链的地址集合（同址只保留一条）。 */
    private val batteryPollingActive = ConcurrentHashMap.newKeySet<String>()
    /** 每地址已排定的轮询 Runnable（用于地址切换/断连时立即 removeCallbacks 取消）。 */
    private val batteryPollRunnables = ConcurrentHashMap<String, Runnable>()
    /** 每地址最近一次主动电量轮询的时间戳（用于抑制重复建会话/刷 UI 的抖动）。 */
    private val batteryLastProbedAt = ConcurrentHashMap<String, Long>()
    private val loggedProductImageTargets = ConcurrentHashMap.newKeySet<String>()
    /** 快速贴图 Drawable 缓存（key=profileId#slot → ConstantState to 时间戳），避免 Glide 连续回填重复走 Provider。 */
    private val fastImageCache = ConcurrentHashMap<String, Pair<android.graphics.drawable.Drawable.ConstantState?, Long>>()
    private val pendingAncModes = ConcurrentHashMap<String, AncMode>()
    private val pendingDseeStates = ConcurrentHashMap<String, Boolean>()
    // ===== MC05 功能面板状态 =====
    /** 已注入的 MC05 开关（WeakReference：随详情页销毁回收）。 */
    private val mc05Preferences = mutableListOf<WeakReference<Any>>()
    /** 在途写入态（key = cmdSet）。 */
    private val pendingMc05FeatureStates = ConcurrentHashMap<Int, Boolean>()
    /** 已确认生效态（key = cmdSet），用于开关回填。 */
    private val mc05FeatureCache = ConcurrentHashMap<Int, Boolean>()
    // ===== 按型号自适配的 XIBERIA 全功能面板状态 =====
    /** 当前已注入面板所对应的官方 productId（用于型号切换时判定是否需要重建面板）。 */
    @Volatile private var activePanelProductId: Int = 0
    /** 已注入的动态面板项（key → WeakReference），随详情页销毁回收。 */
    private val xiberiaPanelPreferences = ConcurrentHashMap<String, WeakReference<Any>>()
    /** 动态面板项在途写入态（key = item key）。 */
    private val pendingXiberiaStates = ConcurrentHashMap<String, Boolean>()
    /** 动态面板项已确认生效态。 */
    private val xiberiaStateCache = ConcurrentHashMap<String, Boolean>()
    /** CHOICE 型项的当前档位值缓存（key → modeValue），由面板回读填充。 */
    private val xiberiaChoiceCache = ConcurrentHashMap<String, Int>()
    /** EQ 编辑器每段竖条 UI 进度的最近一次值（key = item key → IntArray），用于再次打开时回填。 */
    private val xiberiaEqUiCache = ConcurrentHashMap<String, IntArray>()
    /**
     * [修复·旧设备回写污染新面板] 面板绑定代际：当前设备地址 / Profile / 型号任一变化即自增。
     *
     * 背景：`probeXiberiaDeviceStates` 与 `requestXiberiaPanelChange` 都会在协程里捕获启动时的
     *   `activeModuleAddress/profileId/productId`，异步结果回来时当前面板可能已切到别的耳机；
     *   旧实现直接以 `item.key` 为键写全局 `xiberiaStateCache`，且只在 productId 变化时清缓存
     *   （同型号换 MAC 不清）→ 新设备面板短时显示旧设备状态。
     * 现在每次回写前用 [isPanelContextCurrent] 校验 `generation + MAC + profile + productId`，
     *   任一不匹配即丢弃（记 `stale_result_dropped`），不刷新当前 View。
     */
    @Volatile private var panelGeneration: Long = 0L
    private val moduleModeItems = mutableListOf<ModuleModeItemRef>()
    @Volatile private var activeModuleAddress: String? = null
    @Volatile private var activeProfileId: String? = null
    /**
     * 面板状态回读去重窗口时间戳，键 = `normalizedMac|productId`。
     *
     * [修复·去重串设备] 原为单一全局 `lastProbeAtMillis`：快速切换耳机时，新型号首次进入
     *   仍会被旧设备 5s 内的探测时间戳压掉 → 新设备面板拿不到初始 GET。改为按设备/型号隔离。
     */
    private val lastProbeAtByKey = ConcurrentHashMap<String, Long>()
    /** 弹窗进程内最近一次解析出的物理设备 MAC（供主图覆盖复用，避免回退伪装档案）。 */
    @Volatile private var popupDeviceMac: String? = null
    @Volatile private var activeDebugPreview: Boolean = false
    @Volatile private var activeAncMode: AncMode = AncMode.OFF
    // 图片直贴宿主原生 ImageView；电量交宿主原生控件渲染，模块不再自绘容器/缓存单图。
    @Volatile private var cachedModeItemConstructor: Constructor<*>? = null
    @Volatile private var cachedHostModeFactoryMethod: Method? = null
    @Volatile private var cachedFunctionConstructor: Constructor<*>? = null
    @Volatile private var cachedNoiseModeConstructor: Constructor<*>? = null
    @Volatile private var cachedNoiseInfoConstructor: Constructor<*>? = null
    @Volatile private var cachedSetCommandStateConstructor: Constructor<*>? = null
    @Volatile private var cachedEmptySetCommandStateConstructor: Constructor<*>? = null
    private var lastContext = WeakReference<Context>(null)
    private var lastDetailFragment = WeakReference<Any>(null)
    private var lastDetailPreferenceFragment = WeakReference<Any>(null)
    private var lastDseePreference = WeakReference<Any>(null)
    private var lastConnectionItem = WeakReference<Any>(null)
    private var lastConnectionVo = WeakReference<Any>(null)

    private data class ConnectedFieldPlan(
        val field: Field,
        val kind: ConnectedFieldKind,
    )

    private data class BatteryFieldPlan(
        val field: Field,
        val batteryKey: String?,
        val charging: Boolean,
    )

    private data class ModuleModeItemRef(
        val item: WeakReference<Any>,
        val mode: AncMode,
    )

    private data class DiscoveryPopupDevice(
        val address: String,
        val name: String,
        val profile: DeviceProfile,
        /** 是否来自调试注入（决定 profile 解析优先级与中继载荷）。 */
        val debugPreview: Boolean = false,
    )

    override fun onHook() {
        modLog("I", "load hooks for com.oplus.melody")
        hookAllDevicesConnected()
        hookPanelIntent()
        hookDiscoveryPopupTrigger()
        hookBasicGate()
        hookProductImages()
        hookOneSpacePanel()
        // [编译修复·弹窗恢复] 移除非法的 hookOneSpaceConnectStateUi() 调用：
        //   该函数从未实现（半成品重构残留，全库无定义，也从未进过任何已安装构建）。
        //   OneSpace 连接态 UI 的隐藏/接管由 hookOneSpacePanel() 内的
        //   scheduleOneSpaceConnectedUi（container initView/onResume → hideOneSpaceConnectUi）负责。
        hookDetailDseePreference()
        hookNativeCategoryAdd()
        hookNoisePreference()
        hookWhitelistConfig()
        hookWhitelistFinderInject()
        hookNoiseData()
        hookOneSpaceNoiseVo()
        hookDetailNoiseItem()
        hookDetailConnectionInfo()
        hookUnsupportedFeatureViews()
        hookRepositoryNoops()
        hookDeviceRegistryInjection()
        hookDiscoveryActionManagerServer()
        hookConnectedPopupBridge()
        registerDebugInjectReceiver()
    }

    private fun hookAllDevicesConnected() = safeHook(TAG, "all Melody devices connected") {
        hookConnectedTextFallback()
        hookSetCommandSuccess()
        hookDeviceControlWidgetEnable()
        hookBatteryViews()
        listOf(
            "com.oplus.melody.btsdk.api.data.DeviceInfo",
            "com.oplus.melody.btsdk.api.data.MultiConnectInformationElement",
            "com.oplus.melody.app.discovery.E0",
            "O6.a",
            "com.oplus.melody.ui.component.control.b",
            EARPHONE_DTO_CLASS,
            "com.oplus.melody.model.repository.earphone.K",
            "com.oplus.melody.model.repository.multidevicesconnect.MultiConnectStateDTO\$Element",
            "g5.j",
            "D8.i",
            NOISE_VO_CLASS,
            ONESPACE_NOISE_VO_CLASS,
            "L8.a",
            CONNECTION_INFO_VO_CLASS,
            "Q7.a",
            "R7.b",
            "V8.e",
            BATTERY_INFO_VO_CLASS,
            "Y8.b",
            "c9.b",
            OPS_NOISE_VO_CLASS,
            "d8.v",
            "e9.b",
            "j8.v",
            "s9.c",
            "t8.b",
            "w8.d",
            "com.oplus.mydevices.sdk.device.DeviceInfo",
            MYDEVICES_CONNECTION_CLASS,
            // [修复·16.9.1 类名漂移] OneSpace 连接状态 VO（C9.y）与电量 VO（Q8.a）：
            //   面板「已连接」状态与三路电量的真源。旧列表缺失两者 → 面板状态查询恒「未连接」、
            //   电量注入链断裂（宿主真实类名 Q8.a；W7.a 为旧版残留，findMethodOrNull 恒 null）。
            "C9.y",
            "Q8.a",
        ).forEach(::hookConnectedDataClass)
        listOf(
            EARPHONE_DTO_CLASS,
            BATTERY_INFO_VO_CLASS,
            // [修复·16.9.1] 实际电量 VO = Q8.a（W7.a 为旧版遗留，不再出现在渲染链上）。
            "Q8.a",
            "e9.b",
            "O6.a",
            "com.oplus.melody.app.discovery.E0",
            "d6.f",
            "z6.g",
            "com.oplus.melody.btsdk.api.data.BatteryInfo",
            "com.oplus.mydevices.sdk.device.BatteryInfo",
        ).forEach(::hookBatteryDataClass)
    }

    private fun hookConnectedTextFallback() {
        listOf(
            runCatching { Resources::class.java.getMethod("getString", Int::class.javaPrimitiveType!!) }.getOrNull(),
            runCatching { Resources::class.java.getMethod("getText", Int::class.javaPrimitiveType!!) }.getOrNull(),
            runCatching { Context::class.java.getMethod("getString", Int::class.javaPrimitiveType!!) }.getOrNull(),
            runCatching { Context::class.java.getMethod("getText", Int::class.javaPrimitiveType!!) }.getOrNull(),
        ).filterNotNull().forEach { method ->
            hookAfter(method) {
                if (args.firstOrNull() == RES_MELODY_UI_UNCONNECT) {
                    result = connectedText(instance)
                }
            }
        }

        runCatching {
            TextView::class.java.getMethod("setText", CharSequence::class.java)
        }.getOrNull()?.let { method ->
            hookBefore(method) {
                val text = args.firstOrNull()?.toString() ?: return@hookBefore
                if (text != TEXT_UNCONNECTED || replacingDisconnectedText.get()) return@hookBefore
                val view = instance as? TextView ?: return@hookBefore
                replacingDisconnectedText.set(true)
                try {
                    view.text = connectedText(view.context)
                    result = null
                } finally {
                    replacingDisconnectedText.set(false)
                }
            }
        }
    }

    private fun hookConnectedDataClass(className: String) {
        val clazz = findClassOrNull(className) ?: return
        clazz.declaredConstructors.forEach { constructor ->
            runCatching {
                constructor.isAccessible = true
                hookConstructorAfter(constructor) {
                    forceConnectedMembers(result)
                    forceDisplayBatteryMembers(result)
                }
            }.onFailure { modLogT("W", "hook connected constructor $className skipped", it) }
        }

        clazz.declaredMethods.forEach { method ->
            runCatching {
                method.isAccessible = true
                val name = method.name
                when {
                    method.parameterTypes.isEmpty() &&
                        isIntReturn(method.returnType) &&
                        isConnectionStateName(name) -> hookAfter(method) {
                            result = 2
                        }

                    method.parameterTypes.isEmpty() &&
                        isBooleanReturn(method.returnType) &&
                        isConnectedBooleanName(name) -> hookAfter(method) {
                            result = true
                        }

                    method.parameterTypes.isEmpty() &&
                        Map::class.java.isAssignableFrom(method.returnType) &&
                        isConnectionStateName(name) -> hookAfter(method) {
                            forceConnectedMap(result)
                        }

                    method.parameterTypes.isEmpty() &&
                        isConnectStateEnum(method.returnType) -> hookAfter(method) {
                            result = connectedEnumValue(method.returnType)
                        }

                    method.parameterTypes.isEmpty() &&
                        method.returnType.name == MYDEVICES_CONNECTION_CLASS -> hookAfter(method) {
                            forceConnectedMembers(result)
                        }

                    method.parameterTypes.size == 1 &&
                        method.returnType == Void.TYPE &&
                        isIntReturn(method.parameterTypes[0]) &&
                        isConnectionStateName(name) -> hookBefore(method) {
                            forceConnectedMembers(instance)
                            result = null
                        }

                    method.parameterTypes.size == 1 &&
                        method.returnType == Void.TYPE &&
                        isConnectStateEnum(method.parameterTypes[0]) &&
                        isConnectionStateName(name) -> hookBefore(method) {
                            forceConnectedMembers(instance)
                            result = null
                        }

                    method.parameterTypes.size == 1 &&
                        method.returnType == Void.TYPE &&
                        isBooleanReturn(method.parameterTypes[0]) &&
                        isConnectedBooleanName(name) -> hookBefore(method) {
                            forceConnectedMembers(instance)
                            result = null
                        }
                }
            }.onFailure { modLogT("W", "hook connected member $className#${method.name} skipped", it) }
        }
    }

    private fun hookSetCommandSuccess() {
        val clazz = findClassOrNull(SET_COMMAND_STATE_CLASS) ?: return
        clazz.declaredConstructors.forEach { constructor ->
            runCatching {
                constructor.isAccessible = true
                hookConstructorAfter(constructor) {
                    forceSetCommandSuccess(result, null)
                }
            }.onFailure { modLogT("W", "hook set command constructor skipped", it) }
        }
        findMethodOrNull(SET_COMMAND_STATE_CLASS, "getSetCommandStatus")?.let { method ->
            hookAfter(method) {
                forceSetCommandSuccess(instance, null)
                result = SET_COMMAND_SUCCESS
            }
        }
        findMethodOrNull(SET_COMMAND_STATE_CLASS, "setSetCommandStatus", Int::class.javaPrimitiveType!!)?.let { method ->
            hookBefore(method) {
                forceSetCommandSuccess(instance, null)
                result = null
            }
        }
    }

    private fun hookDeviceControlWidgetEnable() {
        val widgetClass = findClassOrNull(DEVICE_CONTROL_WIDGET_CLASS) ?: return
        findMethodOrNull(DEVICE_CONTROL_WIDGET_CLASS, "setEnable", Boolean::class.javaPrimitiveType!!)?.let { method ->
            hookBefore(method) {
                if (!isModuleScope(instance)) return@hookBefore
                val enable = args.firstOrNull() as? Boolean ?: return@hookBefore
                if (enable || forcingWidgetEnable.get()) return@hookBefore
                forcingWidgetEnable.set(true)
                try {
                    if (!hasInjectedModeList(instance, currentAncMode())) {
                        submitModeItems(instance, contextFrom(instance), force = true)
                    } else {
                        forceModeItemsEnabled(callMethodOrNull(instance, "getModeList"))
                    }
                    forceDeviceControlWidgetEnabled(instance)
                    result = null
                } finally {
                    forcingWidgetEnable.set(false)
                }
            }
        }
        findMethodOrNull(DEVICE_CONTROL_WIDGET_CLASS, "b", ArrayList::class.java)?.let { method ->
            hookBefore(method) {
                if (!isModuleScope(instance)) return@hookBefore
                forceModeItemsEnabled(args.firstOrNull())
            }
            hookAfter(method) {
                if (isModuleScope(instance)) forceDeviceControlWidgetEnabled(instance)
            }
        }
        findMethodOrNull(DEVICE_CONTROL_WIDGET_CLASS, "a", findClass(MODE_ITEM_CLASS), View::class.java)?.let { method ->
            hookBefore(method) {
                if (!isModuleScope(instance)) return@hookBefore
                forceModeItemEnabled(args.firstOrNull())
            }
            hookAfter(method) {
                if (!isModuleScope(instance)) return@hookAfter
                forceModeItemEnabled(args.firstOrNull())
                forceModeButtonEnabled(args.getOrNull(1))
            }
        }
        widgetClass.declaredConstructors.forEach { constructor ->
            runCatching {
                constructor.isAccessible = true
                hookConstructorAfter(constructor) {
                    if (isModuleScope(result)) forceDeviceControlWidgetEnabled(result)
                }
            }
        }
        findMethodOrNull(DEVICE_CONTROL_MODE_BUTTON_CLASS, "setEnableState", Boolean::class.javaPrimitiveType!!)?.let { method ->
            hookBefore(method) {
                if (!isModuleScope(instance)) return@hookBefore
                val enable = args.firstOrNull() as? Boolean ?: return@hookBefore
                if (enable) return@hookBefore
                forceModeButtonEnabled(instance)
                result = null
            }
            hookAfter(method) {
                if (isModuleScope(instance)) forceModeButtonEnabled(instance)
            }
        }
        findMethodOrNull(DEVICE_CONTROL_MODE_BUTTON_CLASS, "setLoadingState", Boolean::class.javaPrimitiveType!!)?.let { method ->
            hookBefore(method) {
                if (!isModuleScope(instance)) return@hookBefore
                val loading = args.firstOrNull() as? Boolean ?: return@hookBefore
                if (!loading) return@hookBefore
                setObjectField(instance, "a", false)
                forceModeButtonEnabled(instance)
                result = null
            }
            hookAfter(method) {
                if (isModuleScope(instance)) forceModeButtonEnabled(instance)
            }
        }
        findClassOrNull(DEVICE_CONTROL_MODE_BUTTON_CLASS)?.declaredConstructors?.forEach { constructor ->
            runCatching {
                constructor.isAccessible = true
                hookConstructorAfter(constructor) {
                    if (isModuleScope(result)) forceModeButtonEnabled(result)
                }
            }
        }
    }

    private fun hookBatteryViews() {
        val batteryInfoClass = findClassOrNull(BATTERY_INFO_VO_CLASS) ?: return
        findMethodOrNull(BATTERY_VIEWS_CLASS, "a", String::class.java, batteryInfoClass)?.let { method ->
            hookBefore(method) {
                if (!isModuleScope(instance)) return@hookBefore
                val vo = args.getOrNull(1)
                forceConnectedMembers(vo)
                forceDisplayBatteryMembers(vo)
            }
            hookAfter(method) {
                if (isModuleScope(instance)) renderDetailBatteryView(instance)
            }
        }
        findMethodOrNull(STATUS_INFO_VIEWS_CLASS, "b", String::class.java, batteryInfoClass)?.let { method ->
            hookBefore(method) {
                if (!isModuleScope(instance)) return@hookBefore
                val vo = args.getOrNull(1)
                forceConnectedMembers(vo)
                forceDisplayBatteryMembers(vo)
            }
            hookAfter(method) {
                if (isModuleScope(instance)) renderDetailStatusInfo(instance)
            }
        }
        findMethodOrNull(BATTERY_VIEWS_CLASS, "onFinishInflate")?.let { method ->
            hookAfter(method) {
                if (isModuleScope(instance)) renderDetailBatteryView(instance)
            }
        }
        findMethodOrNull(STATUS_INFO_VIEWS_CLASS, "onFinishInflate")?.let { method ->
            hookAfter(method) {
                if (isModuleScope(instance)) renderDetailStatusInfo(instance)
            }
        }
    }

    private fun hookBatteryDataClass(className: String) {
        val clazz = findClassOrNull(className) ?: return
        clazz.declaredConstructors.forEach { constructor ->
            runCatching {
                constructor.isAccessible = true
                hookConstructorAfter(constructor) {
                    if (isModuleScope(result)) forceDisplayBatteryMembers(result)
                }
            }.onFailure { modLogT("W", "hook battery constructor $className skipped", it) }
        }
        clazz.declaredMethods.forEach { method ->
            runCatching {
                method.isAccessible = true
                val name = method.name
                when {
                    method.parameterTypes.isEmpty() && isIntReturn(method.returnType) -> hookAfter(method) {
                        if (isModuleScope(instance)) {
                            batteryLevelForMemberName(name, instance)?.let { result = it }
                        }
                    }

                    method.parameterTypes.isEmpty() &&
                        isBooleanReturn(method.returnType) &&
                        isChargingName(name) -> hookAfter(method) {
                            if (isModuleScope(instance)) result = false
                        }

                        method.parameterTypes.size == 1 &&
                        method.returnType == Void.TYPE &&
                        isIntReturn(method.parameterTypes[0]) &&
                        isBatteryMemberKey(normalizedMemberKey(name)) -> hookBefore(method) {
                            if (!isModuleScope(instance)) return@hookBefore
                            forceDisplayBatteryMembers(instance)
                            result = null
                        }
                }
            }.onFailure { modLogT("W", "hook battery member $className#${method.name} skipped", it) }
        }
    }

    private fun isIntReturn(type: Class<*>): Boolean =
        type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java

    private fun isBooleanReturn(type: Class<*>): Boolean =
        type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java

    private fun isConnectStateEnum(type: Class<*>): Boolean =
        type.name == MYDEVICES_CONNECT_STATE_CLASS

    private fun connectedEnumValue(type: Class<*>): Any? =
        runCatching { type.getField("CONNECTED").get(null) }.getOrNull()

    private fun isConnectionStateName(name: String): Boolean = MelodyMemberNames.isConnectionStateName(name)

    private fun isConnectedBooleanName(name: String): Boolean = MelodyMemberNames.isConnectedBooleanName(name)

    private fun forceConnectedMembers(target: Any?) {
        if (target == null) return
        connectedFieldPlansFor(target.javaClass).forEach { plan ->
            runCatching {
                when (plan.kind) {
                    ConnectedFieldKind.INT_STATE -> plan.field.set(target, 2)
                    ConnectedFieldKind.BOOLEAN_CONNECTED -> plan.field.set(target, true)
                    ConnectedFieldKind.CONNECT_STATE_ENUM ->
                        plan.field.set(target, connectedEnumValue(plan.field.type))
                    ConnectedFieldKind.CONNECTION_MAP -> forceConnectedMap(plan.field.get(target))
                    ConnectedFieldKind.CONNECTION_OBJECT -> forceConnectedMembers(plan.field.get(target))
                }
            }
        }
    }

    private fun forceDisplayBatteryMembers(target: Any?) {
        if (target == null) return
        if (currentBatteryState(extractAddress(target) ?: activeModuleAddress) == null) return
        batteryFieldPlansFor(target.javaClass).forEach { plan ->
            runCatching {
                if (plan.charging) {
                    plan.field.set(target, false)
                } else {
                    plan.batteryKey?.let { key ->
                        batteryLevelForMemberKey(key, target)?.let { plan.field.set(target, it) }
                    }
                }
            }
        }
    }

    private fun connectedFieldPlansFor(type: Class<*>): List<ConnectedFieldPlan> =
        connectedFieldPlans.getOrPut(type) {
            val plans = mutableListOf<ConnectedFieldPlan>()
            var clazz: Class<*>? = type
            while (clazz != null) {
                clazz.declaredFields.forEach { field ->
                    if (!Modifier.isStatic(field.modifiers)) {
                        // 归类规则见 [ReflectiveFieldClassifier]（纯逻辑、可单测）。
                        val kind = ReflectiveFieldClassifier.connectedKind(
                            fieldName = field.name,
                            fieldTypeName = field.type.name,
                            isInt = isIntReturn(field.type),
                            isBoolean = isBooleanReturn(field.type),
                            isMap = Map::class.java.isAssignableFrom(field.type),
                            connectStateClassName = MYDEVICES_CONNECT_STATE_CLASS,
                            connectionClassName = MYDEVICES_CONNECTION_CLASS,
                        )
                        if (kind != null) {
                            runCatching { field.isAccessible = true }
                            plans += ConnectedFieldPlan(field, kind)
                        }
                    }
                }
                clazz = clazz.superclass
            }
            plans
        }

    private fun batteryFieldPlansFor(type: Class<*>): List<BatteryFieldPlan> =
        batteryFieldPlans.getOrPut(type) {
            val plans = mutableListOf<BatteryFieldPlan>()
            var clazz: Class<*>? = type
            while (clazz != null) {
                clazz.declaredFields.forEach { field ->
                    if (!Modifier.isStatic(field.modifiers)) {
                        // 归类规则见 [ReflectiveFieldClassifier]（纯逻辑、可单测）。
                        val classification = ReflectiveFieldClassifier.batteryClassification(
                            fieldName = field.name,
                            isInt = isIntReturn(field.type),
                            isBoolean = isBooleanReturn(field.type),
                        )
                        if (classification != null) {
                            runCatching { field.isAccessible = true }
                            plans += BatteryFieldPlan(field, classification.batteryKey, classification.charging)
                        }
                    }
                }
                clazz = clazz.superclass
            }
            plans
        }

    private fun forceSetCommandSuccess(target: Any?, address: String?) {
        if (target == null) return
        runCatching { setObjectField(target, "setCommandStatus", SET_COMMAND_SUCCESS) }
        val resolvedAddress = address?.takeIf { it.isNotBlank() } ?: activeModuleAddress
        if (!resolvedAddress.isNullOrBlank()) {
            runCatching { setObjectField(target, "address", resolvedAddress) }
        }
    }

    private fun completedSetCommandFuture(address: String?): CompletableFuture<Any?> {
        val resolvedAddress = address?.takeIf { it.isNotBlank() } ?: activeModuleAddress.orEmpty()
        val state = runCatching {
            setCommandStateConstructor().newInstance(resolvedAddress, SET_COMMAND_SUCCESS)
        }.getOrElse {
            emptySetCommandStateConstructor().newInstance().also { dto ->
                forceSetCommandSuccess(dto, resolvedAddress)
            }
        }
        forceSetCommandSuccess(state, resolvedAddress)
        return CompletableFuture.completedFuture(state)
    }

    private fun setCommandStateConstructor(): Constructor<*> =
        cachedSetCommandStateConstructor ?: findConstructor(
            SET_COMMAND_STATE_CLASS,
            String::class.java,
            Int::class.javaPrimitiveType!!,
        ).also {
            cachedSetCommandStateConstructor = it
        }

    private fun emptySetCommandStateConstructor(): Constructor<*> =
        cachedEmptySetCommandStateConstructor ?: findConstructor(SET_COMMAND_STATE_CLASS).also {
            cachedEmptySetCommandStateConstructor = it
        }

    private fun forceModeItemsEnabled(items: Any?) {
        (items as? Iterable<*>)?.forEach(::forceModeItemEnabled)
    }

    private fun forceModeItemEnabled(item: Any?) {
        if (item == null) return
        setObjectField(item, "g", true)
        setObjectField(item, "h", true)
        setObjectField(item, "f", false)
        setObjectField(item, "i", false)
    }

    private fun forceDeviceControlWidgetEnabled(widget: Any?) {
        val view = widget as? View ?: return
        view.isEnabled = true
        view.alpha = 1f
        forceModeItemsEnabled(callMethodOrNull(widget, "getModeList"))
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                forceModeButtonEnabled(view.getChildAt(index))
            }
        }
    }

    private fun forceModeButtonEnabled(button: Any?) {
        val view = button as? View ?: return
        setObjectField(button, "a", false)
        view.isEnabled = true
        view.alpha = 1f
        val actionView = getObjectField(button, "b") as? View
        actionView?.isEnabled = true
        actionView?.isClickable = true
        actionView?.alpha = 1f
        (getObjectField(button, "d") as? View)?.visibility = View.GONE
        if (view.javaClass.name != DEVICE_CONTROL_MODE_BUTTON_CLASS || actionView == null) return

        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener {
            if (view.isEnabled && getObjectField(button, "a") != true) {
                actionView.performClick()
            }
        }
        view.setOnTouchListener { _, event ->
            if (!view.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val inside = event.x >= 0f && event.x < view.width &&
                        event.y >= 0f && event.y < view.height
                    view.isPressed = false
                    if (inside) view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    true
                }

                else -> true
            }
        }
    }

    private fun renderDetailStatusInfo(view: Any?) {
        val batteryView = getObjectField(view, "b")
        val slots = DetailPanelPresentationPolicy.batterySlots(currentBatteryState())
        (view as? View)?.visibility = if (slots.hasAny) View.VISIBLE else View.GONE
        (getObjectField(view, "c") as? View)?.visibility = View.GONE
        (batteryView as? View)?.visibility = if (slots.hasAny) View.VISIBLE else View.GONE
        renderDetailBatteryView(batteryView, slots)
    }

    private fun renderDetailBatteryView(
        view: Any?,
        slots: DetailBatterySlots = DetailPanelPresentationPolicy.batterySlots(
            currentBatteryState(extractAddress(view) ?: activeModuleAddress),
        ),
    ) {
        (view as? View)?.visibility = if (slots.hasAny) View.VISIBLE else View.GONE
        listOf(
            "b" to slots.primary,
            "g" to slots.left,
            "u" to slots.right,
        ).forEach { (field, level) ->
            (getObjectField(view, field) as? View)?.visibility = if (level != null) View.VISIBLE else View.GONE
            if (level != null) {
                when (field) {
                    "b" -> setBatteryTextAndIndicators(view, "d", "e", "f", level)
                    "g" -> setBatteryTextAndIndicators(view, "i", "j", "t", level)
                    "u" -> setBatteryTextAndIndicators(view, "w", "x", "y", level)
                }
            }
        }
    }

    private fun setBatteryTextAndIndicators(
        owner: Any?,
        textField: String,
        chargingIconField: String,
        progressField: String,
        level: Int,
    ) {
        (getObjectField(owner, textField) as? TextView)?.text = level.toString()
        (getObjectField(owner, chargingIconField) as? View)?.visibility = View.GONE
        val progress = getObjectField(owner, progressField) as? android.widget.ProgressBar
        progress?.progress = level
        progress?.visibility = if (level <= 20) View.VISIBLE else View.GONE
    }

    private fun isChargingName(name: String): Boolean = MelodyMemberNames.isChargingName(name)
    private fun isBatteryMemberKey(key: String): Boolean = MelodyMemberNames.isBatteryMemberKey(key)

    private fun batteryLevelForMemberName(name: String, source: Any?): Int? =
        batteryLevelForMemberKey(normalizedMemberKey(name), source)

    private fun batteryLevelForMemberKey(key: String, source: Any?): Int? {
        val battery = currentBatteryState(extractAddress(source) ?: activeModuleAddress) ?: return null
        return when (MelodyMemberNames.batterySlotForMemberKey(key)) {
            BatterySlot.LEFT -> battery.left ?: battery.single
            BatterySlot.RIGHT -> battery.right ?: battery.single
            BatterySlot.CASE -> battery.caseBattery ?: battery.single
            BatterySlot.SINGLE -> battery.single ?: battery.left ?: battery.right
            null -> null
        }
    }

    private fun normalizedMemberKey(name: String): String = MelodyMemberNames.normalizedMemberKey(name)

    @Suppress("UNCHECKED_CAST")
    private fun forceConnectedMap(value: Any?) {
        val map = value as? MutableMap<Any?, Any?> ?: return
        runCatching {
            map.keys.toList().forEach { key -> map[key] = 2 }
        }
    }

    private fun connectedText(source: Any?): String {
        val context = source as? Context ?: (source as? View)?.context
        return runCatching {
            context?.getString(RES_MELODY_UI_CONNECTED)
        }.getOrNull() ?: TEXT_CONNECTED
    }

    private fun hookDiscoveryPopupTrigger() = safeHook(TAG, "module discovery popup trigger") {
        val receiverClass = "com.oplus.melody.app.bluetooth.BluetoothBroadcastReceiver"
        findMethodOrNull(receiverClass, "onReceive", Context::class.java, Intent::class.java)?.let { method ->
            hookAfter(method) {
                runCatching {
                    handleDiscoveryPopupIntent(args.getOrNull(0) as? Context, args.getOrNull(1) as? Intent)
                }.onFailure { modLogT("W", "handle receiver onReceive popup failed", it) }
            }
        }
        findMethodOrNull(receiverClass, "b", Context::class.java, Intent::class.java)?.let { method ->
            hookAfter(method) {
                runCatching {
                    handleDiscoveryPopupIntent(args.getOrNull(0) as? Context, args.getOrNull(1) as? Intent)
                }.onFailure { modLogT("W", "handle receiver async popup failed", it) }
            }
        }
    }

    private fun hookPanelIntent() = safeHook(TAG, "OneSpaceDetailActivity.onCreate") {
        listOf(
            "com.oplus.melody.onespace.OneSpaceDetailActivity",
            "com.oplus.melody.ui.component.detail.DetailMainActivity",
        ).forEach { className ->
            findMethodOrNull(className, "onCreate", Bundle::class.java)?.let { method ->
                hookBefore(method) {
                    runCatching {
                        normalizePanelIntent(instance as? Activity)
                    }.onFailure { modLogT("W", "normalize panel intent failed", it) }
                }
            }
            findMethodOrNull(className, "onNewIntent", Intent::class.java)?.let { method ->
                hookBefore(method) {
                    runCatching {
                        val activity = instance as? Activity
                        val intent = args.firstOrNull() as? Intent
                        if (activity != null && intent != null) {
                            activity.intent = intent
                            normalizePanelIntent(activity)
                        }
                    }.onFailure { modLogT("W", "normalize panel new intent failed", it) }
                }
            }
        }
        findMethodOrNull(DETAIL_MAIN_FRAGMENT_CLASS, "onActivityCreated", Bundle::class.java)?.let { method ->
            hookBefore(method) {
                runCatching {
                    normalizeDetailFragment(instance)
                }.onFailure { modLogT("W", "normalize detail fragment failed", it) }
            }
            hookAfter(method) {
                runCatching {
                    normalizeDetailFragment(instance)
                    replaceModuleProductImage(callMethodOrNull(instance, "getView"))
                }.onFailure { modLogT("W", "sync detail fragment failed", it) }
            }
        }
        // 统一贴图兜底：新 OneSpace 详情面板 / 设备空间全部由「主图直贴」入口接管，
        //   不再依赖具体绑定方法。多轮延迟覆盖宿主异步 inflate / 动画结束后的时序。
        //   泛化到「任意 melody Activity 的 onResume」：设备空间卡片等 surface 的宿主
        //   Activity 类名未逐一确认，故不再局限单个类，凡属本包即扫。
        findMethodOrNull("android.app.Activity", "onResume")?.let { method ->
            hookAfter(method) {
                runCatching {
                    val activity = instance as? Activity ?: return@runCatching
                    if (!activity.javaClass.name.startsWith("com.oplus.melody")) return@runCatching
                    val handler = Handler(Looper.getMainLooper())
                    listOf(0L, 150L, 400L, 900L, 1600L).forEach { delayMs ->
                        handler.postDelayed(
                            { runCatching { sweepMountProductImage(activity) } },
                            delayMs,
                        )
                    }
                }.onFailure { modLogT("W", "schedule sweep mount failed", it) }
            }
        }
    }

    private fun hookBasicGate() = safeHook(TAG, "basic/privacy gate") {
        findMethodOrNull("l7.n", "i")?.let { method ->
            hookBefore(method) {
                if (isModuleScope(instance)) result = true
            }
        }
    }

    private fun hookProductImages() = safeHook(TAG, "module product images") {
        findClassOrNull(DETAIL_MAIN_FRAGMENT_CLASS)?.declaredMethods
            ?.filter { method ->
                method.name == "onCreateView" && View::class.java.isAssignableFrom(method.returnType)
            }
            ?.forEach { method ->
                runCatching {
                    method.isAccessible = true
                    hookAfter(method) {
                        runCatching { replaceModuleProductImage(result) }
                            .onFailure { modLogT("W", "replace detail fragment image after onCreateView failed", it) }
                    }
                }.onFailure { modLogT("W", "hook detail fragment onCreateView failed", it) }
            }

        val appCompatImageViewClass = findClassOrNull(APP_COMPAT_IMAGE_VIEW_CLASS)
        appCompatImageViewClass?.declaredConstructors?.forEach { constructor ->
            runCatching {
                constructor.isAccessible = true
                hookConstructorAfter(constructor) {
                    runCatching { replaceModuleProductImage(result) }
                        .onFailure { modLogT("W", "replace normal image after construction failed", it) }
                }
            }.onFailure { modLogT("W", "hook AppCompatImageView constructor failed", it) }
        }
        val imageSetterNames = setOf("setImageBitmap", "setImageDrawable", "setImageResource", "setImageURI")
        appCompatImageViewClass?.declaredMethods
            ?.filter { method -> method.name in imageSetterNames && method.parameterTypes.size == 1 }
            ?.forEach { method ->
                runCatching {
                    method.isAccessible = true
                    hookAfter(method) {
                        runCatching { replaceModuleProductImage(instance) }
                            .onFailure { modLogT("W", "restore normal image after ${method.name} failed", it) }
                    }
                }.onFailure { modLogT("W", "hook AppCompatImageView ${method.name} failed", it) }
            }

        findMethodOrNull(DETAIL_MAIN_ACTIVITY_CLASS, "onCreate", Bundle::class.java)?.let { method ->
            hookAfter(method) {
                runCatching { replaceModuleProductImage(instance) }
                    .onFailure { modLogT("W", "replace detail activity image after onCreate failed", it) }
            }
        }
        findMethodOrNull(DETAIL_MAIN_ACTIVITY_CLASS, "onResume")?.let { method ->
            hookAfter(method) {
                runCatching { replaceModuleProductImage(instance) }
                    .onFailure { modLogT("W", "replace detail activity image after onResume failed", it) }
            }
        }

        findMethodOrNull(
            ONESPACE_HEADER_CLASS,
            "onBindViewHolder",
            findClass("androidx.preference.m"),
        )?.let { method ->
            hookAfter(method) {
                runCatching { replaceModuleProductImage(instance) }
                    .onFailure { modLogT("W", "replace header image after bind failed", it) }
            }
        }
        listOf("i", "j", "onShowAnimationEnd").forEach { methodName ->
            findClassOrNull(ONESPACE_HEADER_CLASS)?.declaredMethods
                ?.filter { it.name == methodName }
                ?.forEach { method ->
                    method.isAccessible = true
                    hookAfter(method) {
                        runCatching { replaceModuleProductImage(instance) }
                            .onFailure { modLogT("W", "replace header image after $methodName failed", it) }
                    }
                }
        }

        findMethodOrNull(
            ONESPACE_CONNECT_CLASS,
            "onBindViewHolder",
            findClass("androidx.preference.m"),
        )?.let { method ->
            hookAfter(method) {
                runCatching { replaceModuleProductImage(instance) }
                    .onFailure { modLogT("W", "replace connect image after bind failed", it) }
            }
        }
        findMethodOrNull(ONESPACE_CONNECT_CLASS, "onShowAnimationEnd")?.let { method ->
            hookAfter(method) {
                runCatching { replaceModuleProductImage(instance) }
                    .onFailure { modLogT("W", "replace connect image after animation failed", it) }
            }
        }

        val detailModelViewClass = findClassOrNull(DETAIL_MODEL_VIEW_CLASS)
        detailModelViewClass?.declaredMethods
            ?.filter { it.name == "b" && it.parameterTypes.size == 1 }
            ?.forEach { method ->
                method.isAccessible = true
                hookBefore(method) {
                    runCatching {
                        if (replaceModuleProductImage(instance)) result = null
                    }.onFailure { modLogT("W", "replace detail model image before data bind failed", it) }
                }
            }

        listOf("onFinishInflate", "a", "c", "setViewModel", "setWebpSource").forEach { methodName ->
            detailModelViewClass?.declaredMethods
                ?.filter { it.name == methodName }
                ?.forEach { method ->
                    method.isAccessible = true
                    hookAfter(method) {
                        runCatching { replaceModuleProductImage(instance) }
                            .onFailure { modLogT("W", "replace detail model image after $methodName failed", it) }
                    }
                }
        }

        findClassOrNull(DETAIL_MODEL_GLIDE_LISTENER_CLASS)?.declaredMethods
            ?.filter { it.name == "d" && it.returnType == Boolean::class.javaPrimitiveType }
            ?.forEach { method ->
                method.isAccessible = true
                hookBefore(method) {
                    runCatching {
                        val modelView = getObjectField(instance, "a")
                        if (replaceModuleProductImage(modelView)) result = true
                    }.onFailure { modLogT("W", "replace detail image before Glide completion failed", it) }
                }
            }

        findMethodByParamCountOrNull(DETAIL_MODEL_TIMEOUT_CALLBACK_CLASS, "accept", 2)?.let { method ->
            hookBefore(method) {
                runCatching {
                    if (getObjectField(instance, "a") != 9) return@runCatching
                    val timer = getObjectField(instance, "b")
                    val modelView = getObjectField(timer, "a")
                    if (replaceModuleProductImage(modelView)) result = null
                }.onFailure { modLogT("W", "replace detail image before timeout callback failed", it) }
            }
        }

        findMethodOrNull(DETAIL_MODEL_LIFECYCLE_CLASS, "start")?.let { method ->
            hookAfter(method) {
                runCatching {
                    replaceModuleProductImage(getObjectField(instance, "mModelView"))
                }.onFailure { modLogT("W", "replace detail image after lifecycle start failed", it) }
            }
        }
    }
    /**
     * 新详情页 / 设备空间兜底贴图：递归找宿主「设备图位」并直接贴模块单张主图。
     *
     * 背景：新 OneSpace 详情面板（`OneSpaceDetailActivity` → `ContentFragmentNew` → head fragment
     * `n8.e` → `OneSpaceHeaderPreference.device_image`）与侧栏形态不同，宿主绑定/动画路径多样，
     * 逐个 hook 绑定方法脆弱（历史多次漏挂 → 用户反馈「详情页没有耳机图」）。
     *
     * 本入口不依赖具体绑定方法：按 resource-name 白名单（normal_image / device_image）定位图位，
     * 交给 [replaceModuleProductImage] 贴图（内部走用户自定义 main → 内置 main）。
     * 幂等：重复贴同一 Drawable 无副作用。
     */
    private fun sweepMountProductImage(activity: android.app.Activity) {
        val decor = activity.window?.decorView ?: return
        val candidates = ArrayList<android.widget.ImageView>()
        val seen = HashSet<Int>()
        fun consider(v: android.view.View?) {
            if (v == null) return
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) runCatching { consider(v.getChildAt(i)) }
                return
            }
            if (v !is android.widget.ImageView) return
            val name = resourceNameOf(v)
            if (name != DETAIL_NORMAL_IMAGE_NAME && name != "device_image") return
            if (seen.add(System.identityHashCode(v))) candidates.add(v)
        }
        runCatching { consider(decor) }
        if (candidates.isEmpty()) return
        var applied = 0
        candidates.forEach { iv ->
            runCatching { if (replaceModuleProductImage(iv)) applied++ }
        }
        if (applied > 0) {
            logChain("I", "SWEEP_MAINIMAGE applied=$applied cand=${candidates.size}")
        }
    }


    private fun hookOneSpacePanel() = safeHook(TAG, "OneSpaceListFragment") {
        val fragmentClass = "com.oplus.melody.onespace.d"
        findMethodOrNull(fragmentClass, "w")?.let { method ->
            hookBefore(method) {
                runCatching { ensureDetailNoiseVo(instance) }
                    .onFailure { modLogT("W", "ensure noise vo before panel update failed", it) }
            }
        }
        findMethodOrNull(fragmentClass, "r")?.let { method ->
            hookAfter(method) {
                runCatching { scheduleOneSpacePanelRefresh(instance) }
                    .onFailure { modLogT("W", "force panel after r failed", it) }
            }
        }
        findMethodOrNull(fragmentClass, "onResume")?.let { method ->
            hookAfter(method) {
                runCatching { scheduleOneSpacePanelRefresh(instance) }
                    .onFailure { modLogT("W", "force panel after resume failed", it) }
            }
        }
        findMethodOrNull(fragmentClass, "onViewCreated", android.view.View::class.java, Bundle::class.java)
            ?.let { method ->
                hookAfter(method) {
                    runCatching { scheduleOneSpacePanelRefresh(instance) }
                        .onFailure { modLogT("W", "force panel after view created failed", it) }
                }
            }

        val containerClass = "com.oplus.melody.onespace.b"
        findMethodOrNull(containerClass, "initView", View::class.java)?.let { method ->
            hookAfter(method) { scheduleOneSpaceConnectedUi(instance) }
        }
        findMethodOrNull(containerClass, "onResume")?.let { method ->
            hookAfter(method) { scheduleOneSpaceConnectedUi(instance) }
        }
    }

    private fun hookDetailDseePreference() = safeHook(TAG, "DetailMain DSEE preference") {
        findMethodOrNull(DETAIL_MAIN_ACTIVITY_CLASS, "onCreate", Bundle::class.java)?.let { method ->
            hookAfter(method) {
                scheduleDetailDseeInjection(instance as? Activity)
            }
        }
        findMethodOrNull(DETAIL_MAIN_ACTIVITY_CLASS, "onStart")?.let { method ->
            hookAfter(method) {
                scheduleDetailDseeInjection(instance as? Activity)
            }
        }
        findMethodOrNull(DETAIL_MAIN_ACTIVITY_CLASS, "onNewIntent", Intent::class.java)?.let { method ->
            hookAfter(method) {
                scheduleDetailDseeInjection(instance as? Activity)
            }
        }
        findMethodOrNull(
            DETAIL_MAIN_FRAGMENT_CLASS,
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
        )?.let { method ->
            hookAfter(method) {
                runCatching {
                    normalizeDetailFragment(instance)
                    injectDseeFromDetailMainFragment(instance)
                    scheduleDetailDseeInjection(callMethodOrNull(instance, "getActivity") as? Activity)
                }.onFailure { modLogT("W", "inject detail DSEE after main view creation failed", it) }
            }
        }
        findMethodOrNull(DETAIL_PREFERENCE_FRAGMENT_CLASS, "r")?.let { method ->
            hookAfter(method) {
                runCatching {
                    rememberAndInjectDetailDsee(instance, "earbud settings creation")
                }.onFailure { modLogT("W", "inject detail DSEE after preference creation failed", it) }
            }
        }
        findMethodOrNull(DETAIL_SETTINGS_OBSERVER_CLASS, "onChanged", Object::class.java)?.let { method ->
            hookAfter(method) {
                runCatching {
                    if (getObjectField(instance, "a") != 5) return@runCatching
                    val fragment = getObjectField(instance, "b") ?: return@runCatching
                    if (fragment.javaClass.name != DETAIL_PREFERENCE_FRAGMENT_CLASS) return@runCatching
                    rememberAndInjectDetailDsee(fragment, "earbud settings refresh")
                }.onFailure { modLogT("W", "inject detail DSEE after setting list update failed", it) }
            }
        }
        findMethodOrNull(
            DETAIL_PREFERENCE_FRAGMENT_CLASS,
            "onHiddenChanged",
            Boolean::class.javaPrimitiveType!!,
        )?.let { method ->
            hookAfter(method) {
                runCatching {
                    val hidden = args.firstOrNull() as? Boolean ?: return@runCatching
                    if (!hidden) {
                        rememberAndInjectDetailDsee(instance, "earbud settings visibility")
                    }
                }.onFailure { modLogT("W", "restore detail DSEE preference failed", it) }
            }
        }
    }

    /**
     * ★ 核心注入链路（PLAN_MC05_Feature_Panel_v2 §2 块② · hook 原生动态 add）★
     *
     * 宿主详情页「耳机设置」等分类的装配真源：
     *   `LB9/a.onChanged(Object)`（LifecycleObserver）遍历 key 建 `PreferenceCategory`，
     *   在 :goto_17 汇合点 setTitle/setKey 后调用 `Landroidx/preference/PreferenceGroup;->e(...)`
     *   （= androidx 的 addPreference，R8 后名为 e）把分类挂到 PreferenceScreen，再用 `B8/e`
     *   Supplier 异步填充分类内容。
     *
     * 这里不再依赖任何已死的旧 class 常量，直接 hook 框架基类 `PreferenceGroup.e`：
     *   ① 判断 parent 是否为本次目标详情页（Fragment 为 I8.H/I8.Q 或 Activity 为 DetailMainActivity）
     *   ② 只看 key=="earphone" 的分类（自建面板的首选锚点）
     *   ③ afterHookedMethod（分类已挂屏）→ post 到主线程 post 一帧，等宿主 B8/e 异步填完，
     *      再向该分类 add 自建 COUISwitchPreference（游戏模式/LDAC/低音增强/双设备/触控锁）
     *
     * 幂等：既按 key=="melodyplus_mc05_*" 判重，也在 `mc05Preferences` 中记录 WeakReference。
     */
    private fun hookNativeCategoryAdd() = safeHook(TAG, "native category add (PreferenceGroup.e)") {
        val groupClass = findClassOrNull(PREFERENCE_GROUP_CLASS) ?: return@safeHook
        val preferenceClass = findClassOrNull(PREFERENCE_CLASS) ?: return@safeHook
        val addMethods = groupClass.declaredMethods.filter { candidate ->
            candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0].isAssignableFrom(preferenceClass) &&
                candidate.returnType == Void.TYPE
        }
        if (addMethods.isEmpty()) {
            modLog("W", "hookNativeCategoryAdd: no addPreference(Preference)V on ${groupClass.name}")
            return@safeHook
        }
        addMethods.forEach { method ->
            method.isAccessible = true
            hookAfter(method) {
                runCatching {
                    val parent = instance as? Any ?: return@runCatching
                    val preference = args.firstOrNull() as? Any ?: return@runCatching
                    // 只想在「模块设备」的详情页上做，避免污染其它页面
                    if (!isModuleScope(parent)) return@runCatching
                    val key = callMethodOrNull(preference, "getKey") as? String
                    // 目标：原生「耳机设置」分类被挂上（key=earphone）→ 在它内部填自建 MC05 面板
                    if (key != DETAIL_DSEE_CATEGORY_KEY) return@runCatching
                    if (isOurPreference(preference)) return@runCatching
                    // 分类已挂屏：延后一帧，等宿主 B8/e Supplier 异步填充原生内容后再追加
                    scheduleMc05PanelIntoCategory(preference)
                    modLog("I", "NATIVE_CATEGORY_ADD key=$key type=${preference.javaClass.name}")
                }.onFailure { modLogT("W", "native category add hook failed", it) }
            }
        }
        modLog("I", "hookNativeCategoryAdd: hooked ${addMethods.size} method(s) on ${groupClass.name}")
    }
/** 判断某个容器/片段是否已是「模块自建」preference（用于幂等/防自触）。 */
    private fun isOurPreference(preference: Any?): Boolean =
        (callMethodOrNull(preference, "getKey") as? String)?.startsWith("melodyplus_") == true


    /**
     * 在原生分类挂屏后，向该分类追加**按型号自适配**的官方功能面板。
     * 走主线程 postDelayed（对齐宿主 B8/e Supplier 异步填充分类内容的时机）。
     *
     * 注意：不再注入旧的写死 MC05 五项（[Mc05FeaturePanel]），
     * 改为 [injectDynamicPanelIntoCategory] —— 项集合由当前耳机 productId 决定。
     */
    private fun scheduleMc05PanelIntoCategory(category: Any?) {
        if (category == null) return
        val handler = Handler(Looper.getMainLooper())
        listOf(0L, 120L, 400L, 900L).forEach { delayMs ->
            handler.postDelayed(
                {
                    runCatching { injectDynamicPanelIntoCategory(category, "native category add") }
                        .onFailure { modLogT("W", "inject dynamic panel into native category failed", it) }
                    // 同时触发 DSEE 兜底（若 fragment 已就绪）
                    runCatching {
                        lastDetailPreferenceFragment.get()?.let {
                            rememberAndInjectDetailDsee(it, "native category add (dsee)")
                        }
                    }.onFailure { modLogT("W", "inject DSEE from native category failed", it) }
                },
                delayMs,
            )
        }
    }

    /**
     * 把 [Mc05FeaturePanel.FEATURES] 全部开关 add 进给定的原生分类（[injectMc05Features] 的
     * 无 fragment 版本——分类对象即装配目标，背景信息从 lastDetailPreferenceFragment 反查）。
     */
    private fun injectMc05FeaturesIntoCategory(category: Any?, location: String) {
        if (category == null) return
        val fragment = lastDetailPreferenceFragment.get()
        val profile = moduleProfileFor(fragment) ?: DeviceProfiles.get(activeProfileId) ?: return
        val context = (callMethodOrNull(category, "getContext") as? Context)
            ?: contextFrom(fragment) ?: return
        var created = 0
        var refreshed = 0
        Mc05FeaturePanel.FEATURES.forEachIndexed { index, feature ->
            runCatching {
                val existing = callMethodOrNull(category, "g", feature.key)
                    ?: callMethodOrNull(category, "findPreference", feature.key)
                if (existing != null) {
                    rememberMc05Preference(existing)
                    refreshed++
                    return@runCatching
                }
                val preference = findConstructorOrNull(COUI_SWITCH_PREFERENCE_CLASS, Context::class.java)
                    ?.newInstance(context)
                    ?: return@runCatching
                invokeHostMethod(preference, "setKey", String::class.java, feature.key)
                invokeHostMethod(preference, "setTitle", CharSequence::class.java, feature.title)
                invokeHostMethod(preference, "setSummary", CharSequence::class.java, feature.summary)
                invokeHostMethod(preference, "setPersistent", Boolean::class.javaPrimitiveType!!, false)
                invokeHostMethod(
                    preference,
                    "setOrder",
                    Int::class.javaPrimitiveType!!,
                    Mc05FeaturePanel.ORDER_BASE + index,
                )
                installMc05ChangeListener(preference, feature)
                if (!addHostPreference(category, preference)) return@runCatching
                rememberMc05Preference(preference)
                created++
            }.onFailure { modLogT("W", "inject MC05(category) feature ${feature.key} failed", it) }
        }
        if (created > 0 || refreshed > 0) {
            callMethodOrNull(category, "setVisible", true)
            callMethodOrNull(category, "setEnabled", true)
            callMethodOrNull(category, "notifyChanged")
            modLog("I", "MC05_CATEGORY_PANEL injected=$created refreshed=$refreshed at $location profile=${profile.id}")
        }
        refreshMc05Preferences()
    }

    private fun scheduleDetailDseeInjection(activity: Activity?) {
        if (activity?.javaClass?.name != DETAIL_MAIN_ACTIVITY_CLASS) return
        normalizePanelIntent(activity)
        val handler = Handler(Looper.getMainLooper())
        DETAIL_DSEE_RETRY_DELAYS_MS.forEach { delayMs ->
            handler.postDelayed(
                {
                    runCatching {
                        normalizePanelIntent(activity)
                        val preferenceFragment = resolveDetailPreferenceFragment(activity)
                        if (preferenceFragment != null) {
                            rememberAndInjectDetailDsee(preferenceFragment, "DetailMainActivity")
                        }
                    }.onFailure { modLogT("W", "inject detail DSEE from activity failed", it) }
                },
                delayMs,
            )
        }
    }

    private fun resolveDetailPreferenceFragment(activity: Activity): Any? {
        lastDetailPreferenceFragment.get()?.let { fragment ->
            val owner = callMethodOrNull(fragment, "getActivity") as? Activity
            if (owner === activity && fragment.javaClass.name == DETAIL_PREFERENCE_FRAGMENT_CLASS) return fragment
        }
        val detailFragment = lastDetailFragment.get() ?: return null
        if ((callMethodOrNull(detailFragment, "getActivity") as? Activity) !== activity) return null
        return detailPreferenceFragmentFrom(detailFragment)
    }

    private fun injectDseeFromDetailMainFragment(detailFragment: Any?): Boolean {
        val preferenceFragment = detailPreferenceFragmentFrom(detailFragment) ?: return false
        rememberAndInjectDetailDsee(preferenceFragment, "earbud settings child fragment")
        return true
    }

    private fun detailPreferenceFragmentFrom(detailFragment: Any?): Any? {
        if (detailFragment?.javaClass?.name != DETAIL_MAIN_FRAGMENT_CLASS) return null
        val childFragmentManager = callMethodOrNull(detailFragment, "getChildFragmentManager") ?: return null
        return callMethodOrNull(childFragmentManager, "D", DETAIL_PREFERENCE_FRAGMENT_TAG)
            ?.takeIf { it.javaClass.name == DETAIL_PREFERENCE_FRAGMENT_CLASS }
    }

    private fun rememberAndInjectDetailDsee(fragment: Any?, location: String) {
        if (fragment?.javaClass?.name != DETAIL_PREFERENCE_FRAGMENT_CLASS) return
        lastDetailPreferenceFragment = WeakReference(fragment)
        injectDseePreference(
            fragment = fragment,
            categoryKeys = listOf(DETAIL_DSEE_CATEGORY_KEY),
            order = 1_000,
            location = location,
        )
        // 功能面板：复用同一「耳机设置片段」漏斗（DSEE 的 6 个注入时机全部经此函数，
        //   故这里一行即覆盖 activity.onCreate/onStart/onNewIntent、onViewCreated、r()、
        //   onChanged、onHiddenChanged 全部时机）。
        // [按型号自适配] 项集合由当前耳机官方 productId 决定，不再写死 MC05 五项。
        injectDynamicPanelIntoFragment(fragment, location)
    }

    /**
     * 从「详情页 Fragment」漏斗注入按型号自适配的面板：先定位原生分类（PreferenceScreen 下
     * key=="earphone" 的 PreferenceCategory），再走 [injectDynamicPanelIntoCategory]。
     *
     * 该入口保证即使 `PreferenceGroup.e` 钩子未触发（宿主缓存复用分类）也能补注入。
     */
    private fun injectDynamicPanelIntoFragment(fragment: Any?, location: String) {
        if (fragment?.javaClass?.name != DETAIL_PREFERENCE_FRAGMENT_CLASS) return
        runCatching {
            val screen = callMethodOrNull(fragment, "getPreferenceScreen") ?: return@runCatching
            val category = callMethodOrNull(screen, "g", DETAIL_DSEE_CATEGORY_KEY)
                ?: callMethodOrNull(screen, "findPreference", DETAIL_DSEE_CATEGORY_KEY)
                ?: return@runCatching
            injectDynamicPanelIntoCategory(category, "$location (fragment funnel)")
        }.onFailure { modLogT("W", "inject dynamic panel from fragment failed", it) }
    }

    // ==================== MC05 功能面板（PLAN_MC05_Feature_Panel_v2 §2 块②） ====================

    /**
     * 向详情页「耳机设置」片段注入 MC05 官方功能开关（游戏模式/LDAC/低音增强/双设备/触控锁）。
     *
     * 机制 1:1 复刻 [injectDseePreference]（COUISwitchPreference + Proxy 监听 + 分类锚点），
     * 只把「单个 DSEE 开关」换成遍历 [Mc05FeaturePanel.FEATURES] 建多个开关。
     * 幂等：已存在（同 key）则只刷新态，不重复添加。
     */
    // ==================================================================
    // ★ 按型号自适配的官方全功能面板（XiberiaProductCatalog 驱动）★
    //
    // 与旧 Mc05FeaturePanel 的差别：项集合**不再写死**，而是按当前连接耳机的
    // 官方 productId → XiberiaProductCatalog.panelItems(productId) 动态生成。
    // 因此 MC05 看到 8 项、AS10 看到降噪+空间音频、W30 无游戏模式，全部自动适配。
    // ==================================================================

    /**
     * 解析当前连接设备的官方 productId —— 型号自适配的唯一入口。
     *
     * 顺序：① 模块档案的 modelId（最准，如 "MC05"）→ ② displayName（去 XIBERIA 前缀）
     *      → ③ XiberiaModelProfiles 旧表反查 → ④ 解析失败返回 null（不做兜底猜测）。
     */
    private fun resolvePanelProductId(source: Any?): Int? {
        val profile = runCatching { moduleProfileFor(source) }.getOrNull()
            ?: DeviceProfiles.get(activeProfileId)
            ?: return null
        val candidates = buildList {
            add(profile.modelId)
            add(profile.displayName)
            add(profile.displayName.removePrefix("XIBERIA ").trim())
            add(profile.id.substringAfterLast('.'))
        }.filter { it.isNotBlank() }
        candidates.forEach { name ->
            XiberiaProductCatalog.byModel(name)?.let { return it.productId }
        }
        candidates.forEach { name ->
            XiberiaModelProfiles.byModel(name)?.let { return it.productId }
        }
        return null
    }

    /** 当前面板绑定的 (代际, MAC, Profile, productId) 上下文，供异步回写校验。 */
    // P3 拆分：数据类型移至 PanelStateLogic.kt（纯模型 + isCurrent 纯比较）。

    /**
     * [修复·旧设备回写] 绑定当前面板设备：地址 / Profile 任一变化即递增 [panelGeneration]。
     * 所有「设置 activeModuleAddress/activeProfileId」的设备绑定点统一走这里，
     * 使切换前的旧 probe/写入协程回写时被 [isPanelContextCurrent] 判为过期而丢弃。
     */
    private fun bindActivePanelContext(mac: String?, profileId: String?) {
        val changed = mac != activeModuleAddress || profileId != activeProfileId
        activeModuleAddress = mac
        activeProfileId = profileId
        if (changed) panelGeneration++
    }

    /** 取样当前面板上下文（在发起异步查询/写入前调用，用于回写时判断是否已切设备）。 */
    private fun currentPanelContext(): PanelContext? {
        val mac = activeModuleAddress ?: return null
        val profileId = activeProfileId ?: return null
        return PanelContext(panelGeneration, mac, profileId, activePanelProductId)
    }

    /**
     * 异步结果回写前的上下文校验：`generation + MAC + Profile + productId` 全等才算当前面板。
     * 不匹配 → 调用方应丢弃结果（记 `stale_result_dropped`），不得刷新当前 View / 污染缓存。
     */
    private fun isPanelContextCurrent(ctx: PanelContext): Boolean =
        ctx.isCurrent(panelGeneration, activeModuleAddress, activeProfileId, activePanelProductId)

    /**
     * 按型号动态注入面板项到指定分类（[injectMc05FeaturesIntoCategory] 的自适配版本）。
     *
     * 幂等：已存在的 key 只刷新引用；[activePanelProductId] 变化时记录型号切换。
     */
    private fun injectDynamicPanelIntoCategory(category: Any?, location: String) {
        if (category == null) return
        val fragment = lastDetailPreferenceFragment.get()
        val productId = resolvePanelProductId(fragment ?: category) ?: run {
            modLog("I", "PANEL_SKIP: cannot resolve xiberia productId at $location")
            return
        }
        val caps = XiberiaProductCatalog.byProductId(productId) ?: return
        val items = XiberiaProductCatalog.panelItems(productId)
        if (items.isEmpty()) {
            modLog("I", "PANEL_EMPTY: model=${caps.model} supported=${caps.supported} at $location")
            return
        }
        val context = (callMethodOrNull(category, "getContext") as? Context)
            ?: contextFrom(fragment) ?: return
        val previousPid = activePanelProductId
        if (previousPid != 0 && previousPid != productId) {
            xiberiaPanelPreferences.clear()
            pendingXiberiaStates.clear()
            xiberiaStateCache.clear()
            xiberiaChoiceCache.clear()
            modLog("I", "PANEL_MODEL_SWITCH pid=$previousPid → ${caps.model}")
        }
        // [修复·旧设备回写] 型号切换时递增代际：旧 probe/写入协程即便晚到回写，
        //   也会因代际不符被 isPanelContextCurrent 丢弃。
        // [修复·代际风暴] 旧实现「每次注入无条件 ++」：详情页会以百毫秒级频率重复调用本注入链，
        //   每次 ++ 都把正在回读的 probe 协程判成 context changed
        //   （实测日志：probed=1 answered=0 + ABORT×4 + REFRESH_SKIP），面板永远停在「状态未读取」。
        //   现仅当 productId 真正变化（含首次 0→X）时递增；同型号换 MAC 的失效语义
        //   由 bindActivePanelContext（mac 变化递增）保持不变。
        if (previousPid != productId) panelGeneration++
        activePanelProductId = productId
        var created = 0
        var refreshed = 0
        items.forEachIndexed { index, item ->
            runCatching {
                val existing = callMethodOrNull(category, "g", item.key)
                    ?: callMethodOrNull(category, "findPreference", item.key)
                if (existing != null) {
                    xiberiaPanelPreferences[item.key] = WeakReference(existing)
                    refreshed++
                    return@runCatching
                }
                val preference = findConstructorOrNull(COUI_SWITCH_PREFERENCE_CLASS, Context::class.java)
                    ?.newInstance(context)
                    ?: return@runCatching
                invokeHostMethod(preference, "setKey", String::class.java, item.key)
                invokeHostMethod(preference, "setTitle", CharSequence::class.java, item.title)
                invokeHostMethod(preference, "setSummary", CharSequence::class.java, item.summary)
                invokeHostMethod(preference, "setPersistent", Boolean::class.javaPrimitiveType!!, false)
                invokeHostMethod(
                    preference,
                    "setOrder",
                    Int::class.javaPrimitiveType!!,
                    XIBERIA_PANEL_ORDER_BASE + index,
                )
                installXiberiaChangeListener(preference, item)
                if (!addHostPreference(category, preference)) return@runCatching
                xiberiaPanelPreferences[item.key] = WeakReference(preference)
                created++
            }.onFailure { modLogT("W", "inject xiberia panel item ${item.key} failed", it) }
        }
        if (created > 0 || refreshed > 0) {
            callMethodOrNull(category, "setVisible", true)
            callMethodOrNull(category, "setEnabled", true)
            callMethodOrNull(category, "notifyChanged")
            modLog("I", "PANEL_INJECTED model=${caps.model} pid=0x${caps.productId.toString(16)} " +
                    "items=${items.size} created=$created refreshed=$refreshed at $location")
        }
        refreshXiberiaPanelStates()
        // 面板注入完成 → 异步向设备回读真实状态（替换「未点过=显示关」的假状态）。
        // 未连设备时 probe 内部直接返回，无副作用。
        probeXiberiaDeviceStates("panel_injected")
    }

    /** 安装面板项监听：SWITCH 直接下发；CHOICE 弹多选对话框后下发。统一返回 false 由命令结果回填。 */
    private fun installXiberiaChangeListener(
        preference: Any,
        item: XiberiaProductCatalog.PanelItem,
    ) {
        val setter = hostMethods(preference.javaClass)
            .firstOrNull { it.name == "setOnPreferenceChangeListener" && it.parameterTypes.size == 1 }
            ?: return
        val listenerType = setter.parameterTypes[0]
        val listener = Proxy.newProxyInstance(
            listenerType.classLoader,
            arrayOf(listenerType),
        ) { proxy, method, args ->
            when {
                method.name == "toString" -> "MelodyPlusXiberiaListener(${item.key})"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.returnType == Boolean::class.javaPrimitiveType && args?.size == 2 -> {
                    when (item.kind) {
                        XiberiaProductCatalog.Kind.SWITCH -> {
                            val enabled = args[1] as? Boolean
                            if (enabled != null) requestXiberiaPanelChange(preference, item, enabled, null)
                        }
                        XiberiaProductCatalog.Kind.CHOICE ->
                            showXiberiaChoiceDialog(preference, item)
                        XiberiaProductCatalog.Kind.PAGE ->
                            showXiberiaSubPage(preference, item)
                    }
                    false
                }
                else -> null
            }
        }
        runCatching {
            setter.isAccessible = true
            setter.invoke(preference, listener)
        }.onFailure { modLogT("W", "install xiberia listener ${item.key} failed", it) }
    }

    /** CHOICE 型：弹原生多选对话框，选中后下发档位命令。 */
    /**
     * PAGE 型：打开本模块子页（官方列表里带 `▸` 的项）。
     *
     * 当前实现：
     *   - [XiberiaProductCatalog.SubPage.EQ] → 弹出官方式多段竖向滑块编辑器；
     *   - [XiberiaProductCatalog.SubPage.SOUND_EFFECT] / 其余 → 直接退化为多选/开关弹窗；
     *   - 无 SET 命令的展示型（智能AI / 排水）→ 仅提示。
     */
    private fun showXiberiaSubPage(
        preference: Any,
        item: XiberiaProductCatalog.PanelItem,
    ) {
        when (item.subPage) {
            XiberiaProductCatalog.SubPage.EQ -> showXiberiaEqEditor(preference, item)
            XiberiaProductCatalog.SubPage.PROMPT_TONE,
            XiberiaProductCatalog.SubPage.KEY_FUNCTION,
            -> if (item.choices.isNotEmpty()) {
                showXiberiaChoiceDialog(preference, item)
            } else {
                toastFor(preference, "${item.title}：暂不支持写入")
            }
            XiberiaProductCatalog.SubPage.SOUND_EFFECT ->
                if (item.choices.isNotEmpty()) showXiberiaChoiceDialog(preference, item)
                else toastFor(preference, "${item.title}：暂不支持写入")
            XiberiaProductCatalog.SubPage.LDAC -> {
                // 官方 LDAC 是「进入子页后切换」。原实现写死 `true` → 永远只能开、无法关
                // （面板右侧圆圈样式异常 + 互斥联动失效的根因）。
                // 改为读取当前态后**反转下发**，形成真正的可切换开关。
                val current = xiberiaStateCache[item.key] == true
                requestXiberiaPanelChange(preference, item, !current, null)
            }
            XiberiaProductCatalog.SubPage.SMART_AI,
            XiberiaProductCatalog.SubPage.DRAIN_WATER,
            null,
            -> toastFor(preference, "${item.title}：该型号无对应命令")
        }
    }

    /** 弹出均衡器滑块编辑器，并在「应用」时下发 `0x0806 EQ_CUSTOM_GAIN_SET`。 */
    private fun showXiberiaEqEditor(
        preference: Any,
        item: XiberiaProductCatalog.PanelItem,
    ) {
        val eqConfig = item.eqConfig ?: return
        val context = contextFrom(preference) ?: currentContext() ?: return
        postToUi(context) {
            runCatching {
                modLog("I", "EQ_DIALOG_OPEN cardinal=${eqConfig.cardinal} max=${eqConfig.maxValue} subCmd=0x${eqConfig.subCmd.toString(16)}")
                XiberiaEqDialog.show(
                    context = context,
                    eqConfig = eqConfig,
                    initial = xiberiaEqUiCache[item.key],
                    onApply = { gains -> applyXiberiaEq(preference, item, gains) },
                )
            }.onFailure { modLogT("W", "open EQ dialog ${item.key} failed", it) }
        }
    }

    /** 下发 EQ 增益（`HeadsetCommand.SetEqCustom` → adapter.setEqCustom → `0x0806`）。 */
    private fun applyXiberiaEq(
        preference: Any,
        item: XiberiaProductCatalog.PanelItem,
        gains: IntArray,
    ) {
        val context = contextFrom(preference) ?: currentContext() ?: return
        val address = activeModuleAddress ?: return
        val profile = DeviceProfiles.get(activeProfileId) ?: return
        if (activeDebugPreview) {
            Toast.makeText(context, "调试预览不会发送设备命令", Toast.LENGTH_SHORT).show()
            return
        }
        val eqConfig = item.eqConfig ?: return
        val appContext = context.applicationContext ?: context
        scope.launch {
            val result = runCatching {
                HeadsetSessionManager.execute(
                    context = appContext,
                    address = address,
                    profileId = profile.id,
                    command = HeadsetCommand.SetEqCustom(eqConfig.subCmd, gains),
                )
            }.getOrElse { CommandResult.Failed("execute threw", it) }
            val ok = result is CommandResult.Success
            modLog("I", "EQ_APPLY subCmd=0x${eqConfig.subCmd.toString(16)} gains=${gains.joinToString(",")} ok=$ok result=$result")
            postToUi(appContext) {
                if (!ok) Toast.makeText(appContext, "均衡器下发失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toastFor(preference: Any, text: String) {
        val context = contextFrom(preference) ?: currentContext() ?: return
        postToUi(context) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun showXiberiaChoiceDialog(
        preference: Any,
        item: XiberiaProductCatalog.PanelItem,
    ) {
        if (item.choices.isEmpty()) return
        val context = contextFrom(preference) ?: currentContext() ?: return
        postToUi(context) {
            runCatching {
                val labels: Array<CharSequence> = item.choices.map { it.label as CharSequence }.toTypedArray()
                // [修复·Choice 无当前档位标识] 原用 setItems（普通列表），用户看不到当前选中项，
                //   首次点击容易误判当前状态。改为单选列表 setSingleChoiceItems 并预勾选缓存档位；
                //   未知（无缓存）时不预勾选，用户能区分"当前关闭/未知/已是该项"。
                val current = xiberiaChoiceCache[item.key]
                val checkedIndex = item.choices.indexOfFirst { it.value == current }
                val listener = android.content.DialogInterface.OnClickListener { dialog, which ->
                    val choice = item.choices.getOrNull(which)
                    if (choice != null) {
                        requestXiberiaPanelChange(preference, item, true, choice.value)
                    }
                    runCatching { dialog?.dismiss() }
                }
                android.app.AlertDialog.Builder(context)
                    .setTitle(if (checkedIndex < 0) "${item.title}（当前档位未知）" else item.title)
                    .setSingleChoiceItems(labels, checkedIndex, listener)
                    .setNegativeButton("取消", null)
                    .show()
            }.onFailure { modLogT("W", "show choice dialog ${item.key} failed", it) }
        }
    }

    /**
     * 下发面板项命令：走 [HeadsetSessionManager.execute]（与 DSEE 同通道）→
     * adapter → `XiberiaFeatureBackend` → cchip SPP 帧（[cmdSet] 由 catalog 给出）。
     *
     * @param level CHOICE 型的档位值；SWITCH 型传 null。
     */
    private fun requestXiberiaPanelChange(
        preference: Any,
        item: XiberiaProductCatalog.PanelItem,
        enabled: Boolean,
        level: Int?,
    ) {
        val context = contextFrom(preference) ?: currentContext() ?: return
        val address = activeModuleAddress ?: return
        val profile = DeviceProfiles.get(activeProfileId) ?: return
        if (activeDebugPreview) {
            Toast.makeText(context, "调试预览不会发送设备命令", Toast.LENGTH_SHORT).show()
            return
        }
        val cmd = item.cmdSet ?: run {
            Toast.makeText(context, "${item.title}：该型号无对应命令", Toast.LENGTH_SHORT).show()
            return
        }
        val appContext = context.applicationContext ?: context
        // [修复·旧设备回写] 写入前锁定当前面板上下文；设备/型号切换后的旧写入结果一律不回写缓存。
        val panelCtx = currentPanelContext()
        if (panelCtx == null) {
            pendingXiberiaStates.remove(item.key)
            return
        }
        pendingXiberiaStates[item.key] = enabled
        invokeHostMethod(preference, "setEnabled", Boolean::class.javaPrimitiveType!!, false)
        // 互斥联动：开本项 → 关同组其它项（官方 LDAC ↔ 游戏模式互斥）。
        // 先算出待关项，与主命令一起提交，避免两项同时为开。
        val siblings = if (enabled && item.mutexGroup != null) {
            XiberiaProductCatalog.panelItems(activePanelProductId)
                .filter { it.mutexGroup == item.mutexGroup && it.key != item.key && it.cmdSet != null }
        } else {
            emptyList()
        }
        // [修复·互斥事务] 互斥项只置 pending（UI 显示为关），**不**乐观改写已确认缓存：
        //   主命令失败时清掉 pending 即自动回退到真实旧值，不会把「没执行」记成「已关」。
        siblings.forEach { sibling ->
            pendingXiberiaStates[sibling.key] = false
        }
        scope.launch {
            var ok = false
            var result: CommandResult = CommandResult.Failed("not executed")
            try {
                result = runCatching {
                    // CHOICE 型 → SetLevel（payload=[0x01, level]）；SWITCH 型 → SetSwitch
                    val command: HeadsetCommand = if (level != null) {
                        HeadsetCommand.SetLevel(cmd, level)
                    } else {
                        HeadsetCommand.SetSwitch(cmd, enabled)
                    }
                    // [修复·开关灰卡死] 整体超时：SPP 半死 + 会话锁 + socket.connect 无超时
                    //   → execute 可能长期阻塞；无超时则 pending 永不清除、开关永久灰。
                    //   超时返回 null → ok=false → 走失败分支（复位 pending / checked / enabled）。
                    withTimeoutOrNull(PANEL_WRITE_TIMEOUT_MS) {
                        HeadsetSessionManager.execute(
                            context = appContext,
                            address = address,
                            profileId = profile.id,
                            command = command,
                        )
                    } ?: CommandResult.Failed("面板写入超时（${PANEL_WRITE_TIMEOUT_MS}ms，SPP 链路可能半死）")
                }.getOrElse { CommandResult.Failed("execute threw", it) }
                ok = result is CommandResult.Success
                val stillCurrent = isPanelContextCurrent(panelCtx)
                if (!stillCurrent) {
                    modLog("I", "XIBERIA_PANEL_SET_DROP key=${item.key} (stale_result_dropped)")
                }
                if (stillCurrent) {
                    if (ok) {
                        xiberiaStateCache[item.key] = enabled
                        PanelStateStore.saveSwitch(appContext, address, activePanelProductId, item.key, enabled)
                        // CHOICE 型：记住刚下发的档位，供 summary 回显。
                        if (level != null) {
                            xiberiaChoiceCache[item.key] = level
                            PanelStateStore.saveChoice(appContext, address, activePanelProductId, item.key, level)
                        }
                    } else {
                        // 失败/超时：回写原状态，避免选中态停留在"点了没生效"的假象。
                        xiberiaStateCache[item.key] = !enabled
                        if (level != null) xiberiaChoiceCache.remove(item.key)
                    }
                }
                // [修复·主命令失败仍关兄弟项] 互斥关断**只在主命令成功**后执行；
                //   否则「开 LDAC 失败」会顺带把用户的游戏模式关掉，破坏原状态。
                if (ok) {
                    siblings.forEach { sibling ->
                        val sc = sibling.cmdSet ?: return@forEach
                        val offResult = runCatching {
                            // 兄弟项同样套统一超时，避免半死 SPP 下 pending 长期不清。
                            withTimeoutOrNull(PANEL_WRITE_TIMEOUT_MS) {
                                HeadsetSessionManager.execute(
                                    context = appContext,
                                    address = address,
                                    profileId = profile.id,
                                    command = HeadsetCommand.SetSwitch(sc, false),
                                )
                            } ?: CommandResult.Failed("互斥关闭超时（${PANEL_WRITE_TIMEOUT_MS}ms）")
                        }.getOrElse { CommandResult.Failed("mutex off threw", it) }
                        val offOk = offResult is CommandResult.Success
                        if (offOk && isPanelContextCurrent(panelCtx)) {
                            xiberiaStateCache[sibling.key] = false
                        }
                        modLog("I", "XIBERIA_PANEL_MUTEX_OFF key=${sibling.key} cmd=0x${sc.toString(16)} " +
                                "ok=$offOk result=$offResult")
                    }
                }
                modLog("I", "XIBERIA_PANEL_SET key=${item.key} cmd=0x${cmd.toString(16)} kind=${item.kind} " +
                        "value=$enabled level=$level ok=$ok addr=$address pid=${profile.id} result=$result")
            } finally {
                // [修复·pending 泄漏] 无论成功/失败/超时/取消，都清理本项与兄弟项的 pending，
                //   保证开关不会永久变灰、且再次点击可重试。
                pendingXiberiaStates.remove(item.key)
                siblings.forEach { pendingXiberiaStates.remove(it.key) }
            }
            if (isPanelContextCurrent(panelCtx)) {
                postToUi(appContext) {
                    refreshXiberiaPanelStates()
                    if (!ok) {
                        Toast.makeText(appContext, "${item.title} 设置失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                modLog("I", "XIBERIA_PANEL_REFRESH_SKIP key=${item.key} (stale_result_dropped)")
            }
        }
    }

    /** 面板项 key → 该型号支持的「查询命令码」（用于真实状态回读）。
     *
     *  仅登记**已确认 GET 码**的项；未登记项保持"仅本地记忆"行为，不做假回读。
     *  证据：`XiberiaCommands` 中对应的 `*_GET` 常量；官方能力真值见 `Product$MC05`。
     */
    private fun queryCommandFor(item: XiberiaProductCatalog.PanelItem): Int? =
        // P3 拆分：实现移至 XiberiaPanelQuery（纯逻辑，可单测）。
        // 单一真源：直接取自 catalog 的 [PanelItem.cmdGet] 字段。
        // 旧实现用 `when (item.key)` 硬编码 7 项，与 catalog 形成「双真源」并漂移：
        //   catalog 早已为 noise(0x0B02)/lhdc/child/offline_voice/anti_wind 等填好 cmdGet，
        //   但 when 未登记 → 被 probeXiberiaDeviceStates 的 `queryCommandFor(it) != null` 过滤掉，
        //   → 这些项**永远不发查询**，面板停在假态（用户看到「点进去不主动查询」）。
        // 只对 SWITCH / CHOICE 型探测：PAGE 型无布尔/档位态，应答尾字节会误判污染缓存。
        XiberiaPanelQuery.queryCommandFor(item)

    /**
     * 从 CHOICE 型应答里解析出档位值。
     *
     * **SOUND_EFFECT（`0x0E0E`）专用规则**（真机实测应答 = 单字节 `01`）：
     *  官方 `SoundEffectMode` 声明序为 `KJ(0) / LY(1) / FOOT(2)`，MC05 的
     *  `getSoundEffectItems() = [KJ, LY]`，故单字节按 **enum ordinal → choices 下标** 解释：
     *  - `0x00` → `choices[0]`（KJ 空间音效）
     *  - `0x01` → `choices[1]`（LY 漏音抑制模式）
     *  兼容两字节帧（取尾字节同义）与"应答直接回 modeValue（0x0D/0x0E）"的固件变体。
     *
     * 通用 CHOICE 规则（其余项）：在 payload 首/次/尾字节里找已知值域成员。
     * 全不匹配返回 null（视为"没问到"，不污染缓存）。
     */
    private fun resolveChoiceValue(
        item: XiberiaProductCatalog.PanelItem,
        payload: ByteArray,
    ): Int? =
        // P3 拆分：实现移至 XiberiaPanelQuery（纯逻辑，可单测）。规则说明见该类 KDoc。
        XiberiaPanelQuery.resolveChoiceValue(item, payload)

    /**
     * 探测式真实状态回读：向设备逐项发 GET 命令，回填 [xiberiaStateCache] 后刷新面板。
     *
     * 设计要点：
     *  - **只对登记了 GET 码的项发查询**，未登记项不动（避免不可靠值污染界面）；
     *  - 每项**独立超时**（`queryTimeoutMs`），逐项失败不互相阻塞；
     *  - 无应答 = 保持原值，**不写 false**（严格区分"设备说关"与"没问到"）；
     *  - 全程 IO 线程 + try-catch，任何异常只记日志，不影响面板。
     *
     * 应答 payload 约定：**单字节 `0x00`/`0x01`**（真机实测 MC05 五命令全部命中，
     *  例：`0xe05 → 01` LDAC 开 / `0xc02 → 00` 游戏模式关）。取**尾字节**判定，`0x01` = 开。
     */
    private fun probeXiberiaDeviceStates(reason: String) {
        val address = activeModuleAddress ?: return
        val profileId = activeProfileId ?: return
        val productId = activePanelProductId
        if (productId == 0) return
        // 去重：面板会被注入多次（多 fragment/view），同设备同型号 5s 内只探一次。
        // [修复·去重串设备] 去重键按 (MAC, productId) 隔离，避免旧设备时间戳压掉新设备首次 GET。
        val dedupKey = "$address|$productId"
        val now = System.currentTimeMillis()
        val last = lastProbeAtByKey[dedupKey]
        if (last != null && now - last < PROBE_DEDUP_MS) {
            modLog("I", "XIBERIA_PROBE_SKIP reason=$reason (dedup ${now - last}ms key=$dedupKey)")
            return
        }
        lastProbeAtByKey[dedupKey] = now
        val items = XiberiaProductCatalog.panelItems(productId)
            .filter { queryCommandFor(it) != null }
        if (items.isEmpty()) return
        val ctx = currentContext() ?: return
        val appContext = ctx.applicationContext ?: ctx
        // [修复·旧设备回写] 捕获发起时的面板上下文，回写前逐项校验：切设备/型号后旧结果一律丢弃。
        val panelCtx = currentPanelContext() ?: return
        scope.launch {
            runCatching {
                // [修复·首项必落空] 前置建链：把懒连接成本（SPP connect 最坏数秒）
                //   从单项 GET 超时预算中剥离；连接失败即中止本轮（无连接时逐项探测纯属浪费）。
                val connected = runCatching {
                    withTimeoutOrNull(PROBE_CONNECT_TIMEOUT_MS) {
                        HeadsetSessionManager.connect(appContext, address, profileId)
                    }
                }.getOrNull() ?: false
                if (!connected) {
                    modLog("I", "XIBERIA_PROBE_CONNECT_FAIL reason=$reason mac=$address")
                    return@runCatching
                }
                var probed = 0
                var answered = 0
                items.forEach { item ->
                    val cmd = queryCommandFor(item) ?: return@forEach
                    if (!isPanelContextCurrent(panelCtx)) {
                        modLog("I", "XIBERIA_PROBE_ABORT key=${item.key} reason=$reason (context changed)")
                        return@forEach
                    }
                    probed++
                    // 走 HeadsetSessionManager（与 SET 同一通道，含懒连接）。
                    val payload = runCatching {
                        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                            HeadsetSessionManager.readFeatureRaw(appContext, address, profileId, cmd)
                        }
                    }.getOrNull()
                    if (payload == null || payload.isEmpty()) {
                        modLog("I", "XIBERIA_PROBE key=${item.key} cmd=0x${cmd.toString(16)} no-answer")
                        return@forEach
                    }
                    // 每次 await 后重新校验上下文：设备可能恰在这一项查询期间被切换。
                    if (!isPanelContextCurrent(panelCtx)) {
                        modLog("I", "XIBERIA_PROBE_DROP key=${item.key} reason=$reason (stale_result_dropped)")
                        return@forEach
                    }
                    val raw = payload.joinToString("") { "%02X".format(it) }
                    if (item.kind == XiberiaProductCatalog.Kind.CHOICE) {
                        // CHOICE 型：解析档位值（不是布尔）。
                        val value = resolveChoiceValue(item, payload)
                        if (value != null) {
                            xiberiaChoiceCache[item.key] = value
                            PanelStateStore.saveChoice(appContext, address, productId, item.key, value)
                            answered++
                            val label = item.choices.firstOrNull { it.value == value }?.label
                            modLog("I", "XIBERIA_PROBE key=${item.key} cmd=0x${cmd.toString(16)} " +
                                    "choice=$value label=$label raw=$raw")
                        } else {
                            modLog("I", "XIBERIA_PROBE key=${item.key} cmd=0x${cmd.toString(16)} " +
                                    "choice-unresolved raw=$raw")
                        }
                    } else {
                        val on = (payload[payload.size - 1].toInt() and 0xFF) == 0x01
                        xiberiaStateCache[item.key] = on
                        // [修复·跨会话丢状态] 回读确认值落盘，供下次进面板首帧回填。
                        PanelStateStore.saveSwitch(appContext, address, productId, item.key, on)
                        answered++
                        modLog("I", "XIBERIA_PROBE key=${item.key} cmd=0x${cmd.toString(16)} on=$on raw=$raw")
                    }
                }
                modLog("I", "XIBERIA_PROBE_DONE reason=$reason mac=$address probed=$probed answered=$answered")
            }.onFailure { modLogT("W", "probe xiberia states failed ($reason)", it) }
            // 仅当面板仍为发起时的那台设备才刷新 UI；否则旧结果不得覆盖新面板。
            if (isPanelContextCurrent(panelCtx)) {
                postToUi(appContext) { refreshXiberiaPanelStates() }
            } else {
                modLog("I", "XIBERIA_PROBE_REFRESH_SKIP reason=$reason (stale_result_dropped)")
            }
        }
    }

    /** 用模块侧缓存回填动态面板项的 checked / enabled（CHOICE 型附当前档位文案）。 */
    private fun refreshXiberiaPanelStates() {
        // [修复·可操作性判定过宽] 原用 activeModuleAddress != null 判断可操作：
        //   地址存在但 Profile/型号/会话未就绪时，界面显示可操作，点击后却在 requestXiberiaPanelChange 静默返回。
        //   现统一为「有效地址 + 有效 Profile + 有效 productId + 非调试预览」才允许交互。
        val address = activeModuleAddress
        val ready = address != null &&
            activeProfileId != null &&
            activePanelProductId != 0 &&
            !activeDebugPreview
        val itemsByKey = XiberiaProductCatalog.panelItems(activePanelProductId).associateBy { it.key }
        // [修复·跨会话丢状态] 内存缓存为空（详情页重建/进程回收）时回退到落盘值：
        //   用户「开过一次」的功能，下次进面板首帧即显示开机态，回读到达后再校正。
        val persisted = runCatching {
            PanelStateStore.load(currentContext() ?: currentApplication(), address, activePanelProductId)
        }.getOrNull()
        val persistedSwitches = persisted?.first ?: emptyMap()
        val persistedChoices = persisted?.second ?: emptyMap()
        xiberiaPanelPreferences.forEach { (key, ref) ->
            val preference = ref.get() ?: return@forEach
            val item = itemsByKey[key]
            val pending = pendingXiberiaStates.containsKey(key)
            // [修复·未知态伪装成关闭] 原 checked 回退到 false（未查询/无应答/无 GET 都显示"关"）。
            //   现改为：pending 优先；否则有已确认缓存才勾选；完全未知则不勾选，并在 summary 标注"状态未知"。
            // [修复·跨会话丢状态] 内存无缓存时用落盘值回退。
            val known = xiberiaStateCache[key] ?: persistedSwitches[key]
            val checked = pendingXiberiaStates[key] ?: known ?: false
            runCatching {
                invokeHostMethod(preference, "setChecked", Boolean::class.javaPrimitiveType!!, checked)
                if (item != null && item.kind == XiberiaProductCatalog.Kind.CHOICE && item.choices.isNotEmpty()) {
                    // CHOICE 型：把回读到的当前档位写进 summary（"…（当前：空间音效）"）。
                    val cur = xiberiaChoiceCache[key] ?: persistedChoices[key]
                    val label = item.choices.firstOrNull { it.value == cur }?.label
                    val summary = when {
                        label != null -> "${item.summary}（当前：$label）"
                        // 无回读值时明确标注未知，而不是留空让用户误以为"已关闭/无此功能"。
                        ready -> "${item.summary}（当前：未知）"
                        else -> item.summary
                    }
                    invokeHostMethod(preference, "setSummary", CharSequence::class.java, summary)
                } else if (item != null && known == null && ready) {
                    // SWITCH 型未知态：summary 附"状态未读取"，配合不勾选的状态表达不确定性。
                    invokeHostMethod(
                        preference,
                        "setSummary",
                        CharSequence::class.java,
                        "${item.summary}（状态未读取）",
                    )
                } else if (item != null) {
                    invokeHostMethod(preference, "setSummary", CharSequence::class.java, item.summary)
                }
                invokeHostMethod(preference, "setEnabled", Boolean::class.javaPrimitiveType!!, ready && !pending)
                callMethodOrNull(preference, "notifyChanged")
            }.onFailure { modLogT("W", "refresh xiberia panel $key failed", it) }
        }
    }

    private fun injectMc05Features(fragment: Any?, location: String) {
        if (fragment?.javaClass?.name != DETAIL_PREFERENCE_FRAGMENT_CLASS) return
        val profile = moduleProfileFor(fragment) ?: DeviceProfiles.get(activeProfileId) ?: return
        val context = contextFrom(fragment) ?: return
        val category = callMethodOrNull(fragment, "d", Mc05FeaturePanel.CATEGORY_KEY)
            ?: callMethodOrNull(fragment, "d", DETAIL_DSEE_CATEGORY_KEY)
            ?: return
        var created = 0
        var refreshed = 0
        Mc05FeaturePanel.FEATURES.forEachIndexed { index, feature ->
            runCatching {
                val existing = callMethodOrNull(fragment, "d", feature.key)
                if (existing != null) {
                    rememberMc05Preference(existing)
                    refreshed++
                    return@runCatching
                }
                val preference = findConstructorOrNull(COUI_SWITCH_PREFERENCE_CLASS, Context::class.java)
                    ?.newInstance(context)
                    ?: return@runCatching
                invokeHostMethod(preference, "setKey", String::class.java, feature.key)
                invokeHostMethod(preference, "setTitle", CharSequence::class.java, feature.title)
                invokeHostMethod(preference, "setSummary", CharSequence::class.java, feature.summary)
                invokeHostMethod(preference, "setPersistent", Boolean::class.javaPrimitiveType!!, false)
                invokeHostMethod(
                    preference,
                    "setOrder",
                    Int::class.javaPrimitiveType!!,
                    Mc05FeaturePanel.ORDER_BASE + index,
                )
                installMc05ChangeListener(preference, feature)
                if (!addHostPreference(category, preference)) return@runCatching
                rememberMc05Preference(preference)
                created++
            }.onFailure { modLogT("W", "inject MC05 feature ${feature.key} failed", it) }
        }
        if (created > 0 || refreshed > 0) {
            callMethodOrNull(category, "setVisible", true)
            callMethodOrNull(category, "setEnabled", true)
            callMethodOrNull(category, "notifyChanged")
            modLog("I", "MC05_FEATURE_PANEL injected=$created refreshed=$refreshed at $location profile=${profile.id}")
        }
        refreshMc05Preferences()
        modLog("I", "MC05_FEATURE_INIT profile=${profile.id} (GET 回读解析未校准，暂用模块侧缓存态)")
    }

    private fun rememberMc05Preference(preference: Any?) {
        if (preference == null) return
        synchronized(mc05Preferences) {
            mc05Preferences.removeAll { it.get() == null || it.get() === preference }
            mc05Preferences += WeakReference(preference)
        }
    }

    /** 安装 onPreferenceChange 监听：返回 false（不自动改 UI），由命令结果回填 checked。 */
    private fun installMc05ChangeListener(preference: Any, feature: Mc05FeaturePanel.Feature) {
        val setter = hostMethods(preference.javaClass)
            .firstOrNull { it.name == "setOnPreferenceChangeListener" && it.parameterTypes.size == 1 }
            ?: return
        val listenerType = setter.parameterTypes[0]
        val listener = Proxy.newProxyInstance(
            listenerType.classLoader,
            arrayOf(listenerType),
        ) { proxy, method, args ->
            when {
                method.name == "toString" -> "MelodyPlusMc05ChangeListener(${feature.key})"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.returnType == Boolean::class.javaPrimitiveType && args?.size == 2 -> {
                    val enabled = args[1] as? Boolean
                    if (enabled != null) requestMc05FeatureChange(preference, feature, enabled)
                    false
                }
                else -> null
            }
        }
        runCatching {
            setter.isAccessible = true
            setter.invoke(preference, listener)
        }.onFailure { modLogT("W", "install MC05 listener ${feature.key} failed", it) }
    }

    /**
     * 写 MC05 功能开关：走 [HeadsetSessionManager.execute]（与 DSEE 同通道，自动建/复用 SPP 会话），
     * 命令经 adapter → `XiberiaFeatureBackend.setControlAndVerify` → cchip SPP 帧。
     * 互斥组（LDAC ↔ 游戏模式）：开本项时同步关闭同组其它项。
     */
    private fun requestMc05FeatureChange(
        preference: Any,
        feature: Mc05FeaturePanel.Feature,
        enabled: Boolean,
    ) {
        val context = contextFrom(preference) ?: currentContext() ?: return
        val address = activeModuleAddress ?: return
        val profile = DeviceProfiles.get(activeProfileId) ?: return
        if (activeDebugPreview) {
            Toast.makeText(context, "调试预览不会发送设备命令", Toast.LENGTH_SHORT).show()
            return
        }
        val appContext = context.applicationContext ?: context
        pendingMc05FeatureStates[feature.cmdSet] = enabled
        invokeHostMethod(preference, "setEnabled", Boolean::class.javaPrimitiveType!!, false)
        // 互斥联动：开本项 → 关同组其它项（官方 LDAC↔游戏模式互斥）。
        val siblings = if (enabled) Mc05FeaturePanel.mutexSiblings(feature) else emptyList()
        scope.launch {
            val result = runCatching {
                HeadsetSessionManager.execute(
                    context = appContext,
                    address = address,
                    profileId = profile.id,
                    command = HeadsetCommand.SetSwitch(feature.cmdSet, enabled),
                )
            }.getOrElse { CommandResult.Failed("execute threw", it) }
            val ok = result is CommandResult.Success
            pendingMc05FeatureStates.remove(feature.cmdSet)
            if (ok) mc05FeatureCache[feature.cmdSet] = enabled
            modLog("I", "MC05_FEATURE_SET key=${feature.key} cmd=0x${feature.cmdSet.toString(16)} " +
                    "value=$enabled ok=$ok")
            // 互斥项关断（命令 + UI 同步）
            siblings.forEach { sibling ->
                runCatching {
                    HeadsetSessionManager.execute(
                        context = appContext,
                        address = address,
                        profileId = profile.id,
                        command = HeadsetCommand.SetSwitch(sibling.cmdSet, false),
                    )
                    mc05FeatureCache[sibling.cmdSet] = false
                    pendingMc05FeatureStates.remove(sibling.cmdSet)
                }.onFailure { modLogT("W", "MC05 mutex off ${sibling.key} failed", it) }
            }
            postToUi(appContext) {
                refreshMc05Preferences()
                if (!ok) {
                    Toast.makeText(
                        appContext,
                        "${feature.title} 设置失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    /** 用模块侧缓存（成功写入值）回填所有 MC05 开关的 checked / enabled。 */
    private fun refreshMc05Preferences() {
        val address = activeModuleAddress
        val busy = address != null
        synchronized(mc05Preferences) {
            mc05Preferences.removeAll { it.get() == null }
            mc05Preferences.forEach { ref ->
                val preference = ref.get() ?: return@forEach
                val key = callMethodOrNull(preference, "getKey") as? String ?: return@forEach
                val feature = Mc05FeaturePanel.featureByKey(key) ?: return@forEach
                val checked = pendingMc05FeatureStates[feature.cmdSet]
                    ?: mc05FeatureCache[feature.cmdSet]
                    ?: false
                runCatching {
                    invokeHostMethod(preference, "setChecked", Boolean::class.javaPrimitiveType!!, checked)
                    val pending = pendingMc05FeatureStates.containsKey(feature.cmdSet)
                    invokeHostMethod(preference, "setEnabled", Boolean::class.javaPrimitiveType!!, busy && !pending)
                    callMethodOrNull(preference, "notifyChanged")
                }.onFailure { modLogT("W", "refresh MC05 ${feature.key} failed", it) }
            }
        }
    }

    private fun hookNoisePreference() = safeHook(TAG, "OneSpaceNoisePreference") {
        val holderClass = findClass("androidx.preference.m")
        findMethodOrNull(
            "com.oplus.melody.onespace.items.OneSpaceNoisePreference",
            "onBindViewHolder",
            holderClass,
        )?.let { method ->
            hookAfter(method) {
                runCatching {
                    if (!isModuleScope(instance)) return@runCatching
                    setPreferenceUnlocked(instance)
                    ensureOneSpaceNoisePreferenceVo(instance)
                    injectNoisePreference(instance)
                }.onFailure { modLogT("W", "inject noise preference failed", it) }
            }
        }

        val modeItemClass = findClass(MODE_ITEM_CLASS)
        findMethodOrNull(
            "com.oplus.melody.onespace.items.OneSpaceNoisePreference\$a",
            "c",
            modeItemClass,
            Boolean::class.javaPrimitiveType!!,
        )?.let { method ->
            hookBefore(method) {
                runCatching {
                    val preference = getObjectField(instance, "a") ?: instance
                    val profile = moduleProfileFor(preference) ?: return@runCatching
                    val context = contextFrom(preference) ?: currentContext() ?: return@runCatching
                    val address = extractAddress(preference) ?: activeModuleAddress ?: return@runCatching
                    val mode = ancModeFromModeItem(args.firstOrNull()) ?: return@runCatching
                    activeAncMode = mode
                    postToUi(context) {
                        refreshOneSpaceNoisePreference(preference, mode)
                    }
                    executeAncCommand(context, address, profile, mode)
                        .thenAccept {
                            postToUi(context) {
                                refreshOneSpaceNoisePreference(preference, mode)
                            }
                        }
                        .exceptionally {
                            modLogT("W", "one-space noise command failed", it)
                            null
                        }
                    result = null
                }.onFailure { modLogT("W", "one-space noise command failed", it) }
            }
        }

        findMethodOrNull(
            "com.oplus.melody.ui.widget.devicecontrol.DeviceControlWidget",
            "b",
            ArrayList::class.java,
        )?.let { method ->
            hookBefore(method) {
                runCatching {
                    if (submittingModeItems.get() == true) return@runCatching
                    if (!isModuleScope(instance)) return@runCatching
                    if (!isOneSpaceStack(instance)) return@runCatching
                    submitModeItems(instance, contextFrom(instance))
                    result = null
                }.onFailure { modLogT("W", "override widget mode list failed", it) }
            }
        }
    }

    private fun hookWhitelistConfig() = safeHook(TAG, "WhitelistConfigDTO") {
        findMethodOrNull(WHITELIST_CLASS, "getFunction")?.let { method ->
            hookAfter(method) {
                runCatching {
                    if (!isModuleScope(instance)) return@runCatching
                    val function = result ?: createFunction().also {
                        setObjectField(instance, "function", it)
                    }
                    augmentFunction(function)
                    result = function
                }.onFailure { modLogT("W", "augment whitelist function failed", it) }
            }
        }

        findMethodOrNull(WHITELIST_CLASS, "getSupportSpp")?.let { method ->
            hookAfter(method) { if (isModuleScope(instance)) result = true }
        }
        findMethodOrNull(WHITELIST_CLASS, "getSupportRlmDeviceFunction")?.let { method ->
            hookAfter(method) { if (isModuleScope(instance)) result = true }
        }

        val functionClass = findClassOrNull(FUNCTION_CLASS) ?: return@safeHook
        functionClass.declaredMethods
            .filter { it.parameterTypes.isEmpty() && (it.name.startsWith("get") || it.name.startsWith("is")) }
            .forEach { method ->
                method.isAccessible = true
                when (method.returnType) {
                    Int::class.javaPrimitiveType -> hookAfter(method) {
                        if (isModuleScope(instance)) {
                            result = functionIntValue(method.name, moduleProfileFor(instance))
                        }
                    }

                    Boolean::class.javaPrimitiveType -> hookAfter(method) {
                        if (isModuleScope(instance)) result = false
                    }

                    java.lang.Integer::class.java -> hookAfter(method) {
                        if (isModuleScope(instance)) result = functionIntValue(method.name, moduleProfileFor(instance))
                    }

                    java.util.List::class.java -> if (method.name == "getNoiseReductionMode") {
                        hookAfter(method) {
                            if (isModuleScope(instance)) result = createNoiseModes()
                        }
                    }
                }
            }
    }

    /**
     * 兜底：Melody 运行时白名单来自云端同步的内存集合（WhitelistProvider），
     * 本地任何落盘修改（JSON/db）都会被启动时刷新覆盖。
     * 直接 hook WhitelistUtils（混淆类 com.oplus.melody.common.util.Y）中
     * 所有返回 WhitelistConfigDTO 的查询方法：对 XIBERIA MC05 未命中时伪造命中，
     * 让 Melody 走原生连接弹窗链路。
     */
    private fun hookWhitelistFinderInject() = safeHook(TAG, "whitelist finder inject") {
        val yClass = findClassOrNull("com.oplus.melody.common.util.Y")
        if (yClass == null) {
            modLog("W", "MPWhitelist: class Y not found, abort")
            return@safeHook
        }
        val dtoClass = findClassOrNull(WHITELIST_CLASS)
        if (dtoClass == null) {
            modLog("W", "MPWhitelist: DTO class not found, abort")
            return@safeHook
        }
        modLog("I", "MPWhitelist: init Y=${yClass.name} methods=${yClass.declaredMethods.size} dto=${dtoClass.name}")
        var hooked = 0
        yClass.declaredMethods.forEach { m ->
            if (m.returnType != dtoClass) return@forEach
            runCatching {
                m.isAccessible = true
                hookAfter(m) {
                    runCatching {
                        if (result != null) return@runCatching
                        val btDevice = args.filterIsInstance<android.bluetooth.BluetoothDevice>().firstOrNull()
                        val name = runCatching { btDevice?.name }.getOrNull()
                            ?: args.filterIsInstance<String>().firstOrNull()
                        if (name.isNullOrEmpty()) return@runCatching
                        if (!name.contains("MC05", ignoreCase = true) &&
                            !name.contains("XIBERIA", ignoreCase = true)) return@runCatching
                        result = forgeWhitelistDto(dtoClass, name)
                        // [降噪] 宿主会高频轮询白名单 finder（同一设备反复命中），原先每次打 2 行。
                        //   仍每次伪造 DTO（语义不变），仅日志按「方法名|设备名」去重只打首次。
                        val logKey = "${m.name}|$name"
                        if (mpWhitelistLoggedKeys.size > 64) mpWhitelistLoggedKeys.clear()
                        if (mpWhitelistLoggedKeys.add(logKey)) {
                            modLog("I", "MPWhitelist: forged DTO for $name via ${m.name} ok=${result != null}")
                        }
                    }.onFailure { modLogT("W", "MPWhitelist forge failed", it) }
                }
                hooked++
            }
        }
        modLog("I", "MPWhitelist: hooked $hooked finder methods on Y")
    }

    private fun forgeWhitelistDto(dtoClass: Class<*>, deviceName: String): Any? {
        val ctor = dtoClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
            ?: dtoClass.declaredConstructors.firstOrNull { it.parameterTypes.all { t -> t == String::class.java || t == Int::class.javaPrimitiveType || t == Boolean::class.javaPrimitiveType } }
            ?: return null
        ctor.isAccessible = true
        val dto = if (ctor.parameterTypes.isEmpty()) ctor.newInstance()
        else {
            val argv: List<Any> = ctor.parameterTypes.map { t ->
                when (t) { Int::class.javaPrimitiveType -> 0; Boolean::class.javaPrimitiveType -> false; else -> "" }
            }
            ctor.newInstance(*argv.toTypedArray())
        }
        // 优先 setter
        // 逆向实锤（Melody 16.9.1）：
        // - 下游 HeadsetCoreService$e 取 dto.getId() → Integer.parseInt(id, 16) → productId
        //   → DeviceInfoManager.a(productId, device)。id 必须是 16 进制字符串！
        // - 统一伪装为 OPPO Enco X3：0x067410 = 十进制 422416（用户拍板，多品牌全用 Enco X3）
        // - fuzzyMatchName 是 boolean 字段（不是前缀字符串），Y.p 精确匹配时用它
        // - uuid 置空：多候选同名时才会用 uuid 二次过滤，置空直接跳过该分支
        // - 伪装 key 随 deviceName 路由：优先 XIBERIA 型号表（XiberiaModelProfiles），
        //   其次模块 profile（AdapterRegistry），未知兜底 Enco X3（XiberiaSpoof 单一真源）
        val spoofIdHex = runCatching {
            XiberiaModelProfiles.byModel(deviceName)?.let { XiberiaModelProfiles.toHex6(it.productId) }
                ?: DeviceProfiles.findByName(deviceName)?.let { AdapterRegistry.spoofIdHexFor(it) }
        }.getOrNull() ?: XiberiaSpoof.spoofProductIdHex()
        val spoofBrand = runCatching {
            DeviceProfiles.findByName(deviceName)?.adapter?.uppercase()
        }.getOrNull() ?: "XIBERIA"
        val setters = mapOf(
            "setName" to deviceName,
            "setId" to spoofIdHex,
            "setType" to "T1",
            "setUuid" to "",
            "setBrand" to spoofBrand,
        )
        setters.forEach { (mn, v) ->
            runCatching {
                val m = dtoClass.methods.firstOrNull { it.name == mn && it.parameterTypes.size == 1 }
                m?.isAccessible = true
                when (m?.parameterTypes?.get(0)) {
                    Int::class.javaPrimitiveType -> m!!.invoke(dto, v.toIntOrNull() ?: 0)
                    Boolean::class.javaPrimitiveType -> m!!.invoke(dto, v.toBoolean())
                    else -> m?.invoke(dto, v)
                }
            }
        }
        // fuzzyMatchName 是 boolean：false=精确 equals(deviceName)，我们 setName 已用精确名
        runCatching {
            val m = dtoClass.methods.firstOrNull {
                it.name == "setFuzzyMatchName" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            m?.isAccessible = true
            m?.invoke(dto, false)
        }
        // supportSpp=false：避免 HeadsetCoreService 对假白名单设备发起 SPP 连接
        runCatching {
            val m = dtoClass.methods.firstOrNull {
                it.name == "setSupportSpp" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            m?.isAccessible = true
            m?.invoke(dto, false)
        }
        // setter 不存在时按字段名直接写
        val fields = mapOf(
            "name" to deviceName,
            "id" to spoofIdHex,
            "type" to "T1",
            "uuid" to "",
        )
        fields.forEach { (fn, v) ->
            runCatching {
                val f = dtoClass.getDeclaredField(fn)
                f.isAccessible = true
                f.set(dto, v)
            }
        }
        return dto
    }

    private fun hookNoiseData() = safeHook(TAG, "noise dto and vo") {
        hookGetterResult(NOISE_VO_CLASS, "getConnectionState", 2, global = true)
        hookGetterResult(NOISE_VO_CLASS, "getHeadsetConnectionState", 2, global = true)
        hookGetter(NOISE_VO_CLASS, "getCurrentNoiseReductionModeIndex", global = true) {
            currentProtocolIndex(extractAddress(instance) ?: activeModuleAddress)
        }
        hookGetter(NOISE_VO_CLASS, "getIntelligentNoiseReductionModeIndex", global = true) {
            currentProtocolIndex(extractAddress(instance) ?: activeModuleAddress)
        }
        hookGetterResult(NOISE_VO_CLASS, "getNoiseReductionUIVersion", NOISE_UI_VERSION_BASIC, global = true)
        hookGetterResult(NOISE_VO_CLASS, "isCapabilityReady", true, global = true)
        hookGetterResult(NOISE_VO_CLASS, "isSupportEarStatus", false, global = true)
        hookGetterResult(NOISE_VO_CLASS, "supportStrongNoiseReductionRealTime", false, global = true)
        hookGetter(NOISE_VO_CLASS, "getSupportNoiseReductionInfo", global = true) { createNoiseInfo() }
        hookGetter(NOISE_VO_CLASS, "getNoiseReductionModeList", global = true) { createNoiseModes() }

        hookGetterResult(EARPHONE_DTO_CLASS, "getConnectionState", 2, global = true)
        hookGetterResult(EARPHONE_DTO_CLASS, "getHeadsetConnectionState", 2, global = true)
        hookGetterResult(EARPHONE_DTO_CLASS, "getAclConnectionState", 2, global = true)
        hookGetterResult(EARPHONE_DTO_CLASS, "getA2dpConnectionState", 2, global = true)
        hookGetterResult(EARPHONE_DTO_CLASS, "getSppOverGattConnectionState", 2, global = true)
        hookGetter(EARPHONE_DTO_CLASS, "getNoiseReductionModeIndex", global = true) {
            currentProtocolIndex(extractAddress(instance) ?: activeModuleAddress)
        }
        hookGetter(EARPHONE_DTO_CLASS, "getIntelligentNoiseReductionModeIndex", global = true) {
            currentProtocolIndex(extractAddress(instance) ?: activeModuleAddress)
        }
        listOf("getLeftBattery", "getHeadsetLeftBattery").forEach { methodName ->
            hookGetter(EARPHONE_DTO_CLASS, methodName) {
                currentBatteryState(extractAddress(instance) ?: activeModuleAddress)?.left ?: result
            }
        }
        listOf("getRightBattery", "getHeadsetRightBattery").forEach { methodName ->
            hookGetter(EARPHONE_DTO_CLASS, methodName) {
                currentBatteryState(extractAddress(instance) ?: activeModuleAddress)?.right ?: result
            }
        }
        listOf("getBoxBattery", "getHeadsetBoxBattery").forEach { methodName ->
            hookGetter(EARPHONE_DTO_CLASS, methodName) {
                currentBatteryState(extractAddress(instance) ?: activeModuleAddress)?.caseBattery ?: result
            }
        }
        listOf("isLeftCharging", "isRightCharging", "isBoxCharging").forEach {
            hookGetterResult(EARPHONE_DTO_CLASS, it, false)
        }
        listOf(
            "isActive",
            "isCapabilityReady",
            "isDeviceBonded",
            "isInitCmdCompleted",
            "isSupportSpp",
        ).forEach { hookGetterResult(EARPHONE_DTO_CLASS, it, true, global = true) }
        listOf(
            "getSupportBindAccount",
            "getSupportCustomEq",
            "getSupportMultiDeviceConnect",
            "getSupportSmartBluetooth",
            "isSupportEarStatus",
            "supportStrongNoiseReductionRealTime",
        ).forEach { hookGetterResult(EARPHONE_DTO_CLASS, it, false, global = true) }
        listOf(
            "getSupportNoiseReductionInfo",
            "getSwitchNoiseReductionInfo",
            "getSwitchLeftEarNoiseReductionInfo",
            "getSwitchRightEarNoiseReductionInfo",
        ).forEach { hookGetter(EARPHONE_DTO_CLASS, it, global = true) { createNoiseInfo() } }
        hookGetter(EARPHONE_DTO_CLASS, "getProductId") {
            val original = result as? String
            val profile = moduleProfileFor(instance)
            if (original.isNullOrBlank()) profile?.modelId else original
        }
        hookGetter(EARPHONE_DTO_CLASS, "getName") {
            val original = result as? String
            val profile = moduleProfileFor(instance)
            if (original.isNullOrBlank()) profile?.displayName else original
        }

        hookGetterResult(NOISE_INFO_CLASS, "isNoiseReductionModeInfo", true, global = true)
        findMethodOrNull(NOISE_INFO_CLASS, "isSupportNoiseReductionModeValue", Int::class.javaPrimitiveType!!)
            ?.let { method ->
                hookAfter(method) {
                    val modeValue = args.firstOrNull() as? Int
                    result = modeValue in SUPPORTED_PROTOCOL_INDICES
                }
            }
    }

    private fun hookOneSpaceNoiseVo() = safeHook(TAG, "OneSpaceNoiseVO") {
        findConstructorOrNull(ONESPACE_NOISE_VO_CLASS, findClass(EARPHONE_DTO_CLASS))?.let { constructor ->
            hookConstructorAfter(constructor) {
                runCatching { applyOneSpaceNoiseVo(result) }
                    .onFailure { modLogT("W", "apply one-space noise vo failed", it) }
            }
        }
        hookOneSpaceGetterResult("getMConnectState") { 2 }
        hookOneSpaceGetterResult("getMCurrentNoiseMode") { currentMelodyModeType(activeModuleAddress) }
        hookOneSpaceGetterResult("getMNoiseReductionUIVersion") { NOISE_UI_VERSION_BASIC }
        hookOneSpaceGetterResult("getMNoiseReductionModeList") { createNoiseModes() }
        hookOneSpaceGetterResult("getMSupportNoiseReductionInfo") { createNoiseInfo() }
        hookGetter(ONESPACE_NOISE_VO_CLASS, "getMProductId") {
            moduleProfileFor(instance)?.modelId ?: result
        }
        hookGetter(ONESPACE_NOISE_VO_CLASS, "getMProductName") {
            moduleProfileFor(instance)?.displayName ?: result
        }
        hookGetter(ONESPACE_NOISE_VO_CLASS, "getMAddress") {
            activeModuleAddress ?: result
        }
    }

    private fun hookDetailNoiseItem() = safeHook(TAG, "DetailMain noise item") {
        findMethodOrNull(
            DETAIL_NOISE_ITEM_CLASS,
            "onEarphoneDataChanged",
            findClass(NOISE_VO_CLASS),
        )?.let { method ->
            hookBefore(method) {
                runCatching {
                    if (!isModuleScope(instance)) return@runCatching
                    val address = extractAddress(instance) ?: activeModuleAddress
                    normalizeNoiseVo(args.firstOrNull(), mode = currentAncMode(address))
                }.onFailure { modLogT("W", "normalize detail noise vo failed", it) }
            }
        }

        findMethodOrNull(
            DETAIL_NOISE_ITEM_CLASS,
            "updateActionView",
            findClass("com.oplus.melody.ui.widget.devicecontrol.DeviceControlWidget"),
            findClass(NOISE_VO_CLASS),
            Int::class.javaPrimitiveType!!,
        )?.let { method ->
            hookBefore(method) {
                runCatching {
                    if (!isModuleScope(instance)) return@runCatching
                    val address = extractAddress(instance) ?: activeModuleAddress
                    val mode = currentAncMode(address)
                    normalizeNoiseVo(args.getOrNull(1), mode)
                }.onFailure { modLogT("W", "normalize detail update action failed", it) }
            }
            hookAfter(method) {
                runCatching {
                    if (!isModuleScope(instance)) return@runCatching
                    val address = extractAddress(instance) ?: activeModuleAddress
                    val mode = currentAncMode(address)
                    normalizeNoiseVo(args.getOrNull(1), mode)
                    val widget = args.firstOrNull()
                    if (widget != null && args.getOrNull(1) != null) {
                        submitModeItems(widget, contextFrom(instance), force = true)
                    }
                }.onFailure { modLogT("W", "refresh detail update action failed", it) }
            }
        }

        val modeItemClass = findClass(MODE_ITEM_CLASS)
        findMethodOrNull(
            DETAIL_NOISE_LISTENER_CLASS,
            "c",
            modeItemClass,
            Boolean::class.javaPrimitiveType!!,
        )?.let { method ->
            hookBefore(method) {
                runCatching {
                    val owner = getObjectField(instance, "a") ?: return@runCatching
                    if (!isModuleScope(owner)) return@runCatching
                    val context = contextFrom(owner) ?: currentContext() ?: return@runCatching
                    val address = extractAddress(owner) ?: activeModuleAddress ?: return@runCatching
                    val profile = moduleProfileFor(owner) ?: return@runCatching
                    val previousMode = currentAncMode(address)
                    val mode = ancModeFromDetailModeItem(owner, args.firstOrNull())
                        ?: ancModeFromModeItem(args.firstOrNull())
                        ?: return@runCatching
                    val normalizedAddress = DeviceRegistryStore.normalizeAddress(address)
                    pendingAncModes[normalizedAddress] = mode
                    activeAncMode = mode
                    postToUi(context) {
                        refreshDetailNoiseItem(owner, mode)
                    }
                    val future = executeAncCommand(context, address, profile, mode)
                    setObjectField(owner, "mSetCommandFuture", future)
                    future
                        .thenAccept {
                            pendingAncModes.remove(normalizedAddress, mode)
                            if (getObjectField(owner, "mSetCommandFuture") !== future) return@thenAccept
                            activeAncMode = HeadsetSessionManager.cachedState(normalizedAddress)?.ancMode ?: mode
                            postToUi(context) {
                                refreshDetailNoiseItem(owner, mode)
                                refreshDetailBatteryViews()
                            }
                        }
                        .exceptionally {
                            modLogT("W", "detail noise command failed", it)
                            pendingAncModes.remove(normalizedAddress, mode)
                            if (getObjectField(owner, "mSetCommandFuture") === future) {
                                val rollbackMode = HeadsetSessionManager.cachedState(normalizedAddress)?.ancMode ?: previousMode
                                activeAncMode = rollbackMode
                                postToUi(context) {
                                    refreshDetailNoiseItem(owner, rollbackMode)
                                }
                            }
                            null
                        }
                    result = null
                }.onFailure { modLogT("W", "detail noise click hook failed", it) }
            }
        }
    }

    private fun hookDetailConnectionInfo() = safeHook(TAG, "DetailMain connection info") {
        val connectionVoClass = findClassOrNull(CONNECTION_INFO_VO_CLASS) ?: return@safeHook
        findMethodOrNull(
            DETAIL_CONNECTION_ITEM_CLASS,
            "onBindViewHolder",
            findClass("androidx.preference.m"),
        )?.let { method ->
            hookAfter(method) {
                runCatching {
                    lastConnectionItem = WeakReference(instance)
                    setPreferenceUnlocked(instance)
                    refreshConnectionInfoItem(instance)
                }.onFailure { modLogT("W", "bind connection info failed", it) }
            }
        }
        findClassOrNull(DETAIL_CONNECTION_ITEM_CLASS)
            ?.declaredMethods
            ?.filter { it.parameterTypes.size == 1 && it.parameterTypes[0] == connectionVoClass }
            ?.forEach { method ->
                method.isAccessible = true
                hookBefore(method) {
                    runCatching {
                        lastConnectionItem = WeakReference(instance)
                        val vo = args.firstOrNull()
                        lastConnectionVo = WeakReference(vo)
                        normalizeConnectionVo(vo)
                    }.onFailure { modLogT("W", "normalize connection info failed", it) }
                }
                hookAfter(method) {
                    runCatching {
                        setPreferenceUnlocked(instance)
                    }.onFailure { modLogT("W", "unlock connection info failed", it) }
                }
            }
        hookConnectionInfoVoAccessors(connectionVoClass)
    }

    private fun hookUnsupportedFeatureViews() = safeHook(TAG, "unsupported feature views") {
        findConstructorOrNull(
            OPS_SEEKBAR_CLASS,
            Context::class.java,
            android.util.AttributeSet::class.java,
        )?.let { constructor ->
            hookConstructorAfter(constructor) {
                runCatching { hideNoiseStrengthViewIfUnsupported(result) }
                    .onFailure { modLogT("W", "hide ops seekbar after constructor failed", it) }
            }
        }
        listOf("i", "d", "h", "setDisabled").forEach { methodName ->
            findClassOrNull(OPS_SEEKBAR_CLASS)?.declaredMethods
                ?.filter { it.name == methodName }
                ?.forEach { method ->
                    method.isAccessible = true
                    hookAfter(method) {
                        runCatching { hideNoiseStrengthViewIfUnsupported(instance) }
                            .onFailure { modLogT("W", "hide ops seekbar after $methodName failed", it) }
                    }
                }
        }
        hookGetterResult(OPS_NOISE_VO_CLASS, "getConnectionState", 2)
        hookGetter(OPS_NOISE_VO_CLASS, "getCurrentNoiseReductionModeIndex") {
            currentProtocolIndex(extractAddress(instance) ?: activeModuleAddress)
        }
        hookGetter(OPS_NOISE_VO_CLASS, "getNoiseReductionModeList") {
            if (moduleProfileFor(instance)?.uiFeatures?.noiseReductionStrength == true) createNoiseModes() else emptyList<Any>()
        }
        hookGetter(OPS_NOISE_VO_CLASS, "getSupportNoiseReductionInfo") { createNoiseInfo() }
        hookGetter(OPS_NOISE_VO_CLASS, "getSwitchNoiseReductionInfo") { createNoiseInfo() }
    }

    private fun hookRepositoryNoops() = safeHook(TAG, "repository commands") {
        listOf(
            "com.oplus.melody.model.repository.earphone.b",
            "com.oplus.melody.model.repository.earphone.EarphoneRepositoryClientImpl",
            "com.oplus.melody.model.repository.earphone.J",
        ).forEach { className ->
            findMethodOrNull(className, "s0", Int::class.javaPrimitiveType!!, String::class.java)
                ?.takeUnless { Modifier.isAbstract(it.modifiers) }
                ?.let { method ->
                hookBefore(method) {
                    val context = contextFrom(instance) ?: currentContext()
                    val address = args.getOrNull(1) as? String ?: activeModuleAddress
                    val mode = ancModeFromProtocolIndex(args.getOrNull(0) as? Int) ?: return@hookBefore
                    activeAncMode = mode
                    val profile = if (context != null && address != null) {
                        moduleProfileForAddress(context, address, instance)
                    } else {
                        null
                    }
                    if (context != null && address != null && profile != null) {
                        HeadsetSessionManager.rememberAncMode(address, profile.id, mode)
                        result = executeAncCommand(context, address, profile, mode)
                    } else {
                        result = completedSetCommandFuture(address)
                    }
                }
            }
            findMethodOrNull(className, "d", String::class.java)
                ?.takeUnless { Modifier.isAbstract(it.modifiers) }
                ?.let { method ->
                hookBefore(method) {
                    if (!isModuleScope(instance)) return@hookBefore
                    val address = activeModuleAddress ?: return@hookBefore
                    scope.launch {
                        runCatching { HeadsetSessionManager.disconnect(address) }
                    }
                    result = null
                }
            }
        }

        val preferenceClass = findClassOrNull("androidx.preference.Preference") ?: return@safeHook
        findMethodOrNull(
            "com.oplus.melody.model.repository.earphone.C",
            "i",
            preferenceClass,
            Any::class.java,
        )?.let { method ->
            hookBefore(method) {
                runCatching {
                    if (isModuleScope(instance) && getObjectField(instance, "a") == 0x15) {
                        result = true
                    }
                }.onFailure { modLogT("W", "noise menu no-op failed", it) }
            }
        }
    }

    /**
     * 设备注册入表（弹窗根因修复，对齐 MelodyLink）。
     *
     * melody 的弹窗入口（checkShowConnectCapsule / discovery）只在设备已存在于
     * DeviceInfoManager 注册表时触发。MelodyPlus 之前只伪造了"已连接后各 getter"，
     * 漏了 hook DeviceInfoManager.d(BluetoothDevice) 把目标设备塞进注册表。
     *
     * 这一步：对【已绑定的第三方耳机】在 melody 查表(d)未命中时，用 f() 创建
     * DeviceInfo + c() 注册，把设备主动写进注册表；同时统一伪装为 OPPO Enco X3。
     */
    private fun hookDeviceRegistryInjection() = safeHook(TAG, "DeviceInfoManager registry injection") {
        val mgrClass = findClassOrNull(DEVICE_INFO_MANAGER_CLASS)
            ?: run {
                modLog("W", "registry: DeviceInfoManager not found, abort")
                return@safeHook
            }
        val infoClass = findClassOrNull(DEVICE_INFO_CLASS) ?: return@safeHook

        // 1) melody 每次查表 d(BluetoothDevice)：目标已绑定但表里没有 → 注册入表
        findMethodOrNull(
            DEVICE_INFO_MANAGER_CLASS,
            "d",
            BluetoothDevice::class.java,
        )?.let { lookupMethod ->
            hookAfter(lookupMethod) {
                runCatching {
                    val device = args.getOrNull(0) as? BluetoothDevice ?: return@runCatching
                    val addr = normalizeAddressOrNull(device.address) ?: return@runCatching
                    // 只处理模块已绑定的设备
                    val ctx = currentContext() ?: return@runCatching
                    val bound = ModuleDeviceRegistry(ctx).getBoundDevice() ?: return@runCatching
                    if (bound.address != addr) return@runCatching
                    if (result != null) return@runCatching // melody 表里已有，不重复注册
                    val created = createMelodyDeviceInfo(device, bound)
                    if (created != null) {
                        result = created
                        modLog("I", "registry: injected DeviceInfo for $addr (${bound.profile?.displayName})")
                    }
                }.onFailure { modLogT("W", "registry inject d() failed", it) }
            }
        }

        // 2) 对已注册的目标设备，状态 setter 强制置为已连接
        listOf(
            "setDeviceConnectState",
            "setDeviceHeadsetConnectState",
            "setDeviceA2dpConnectState",
        ).forEach { setter ->
            findMethodByParamCountOrNull(DEVICE_INFO_CLASS, setter, 1)?.let { m ->
                hookAfter(m) {
                    runCatching {
                        if (isModuleInfoScope(instance)) {
                            result = 2
                        }
                    }.onFailure { modLogT("W", "registry override $setter failed", it) }
                }
            }
        }
        findMethodByParamCountOrNull(DEVICE_INFO_CLASS, "setDeviceLeAudioConnectState", 2)?.let { m ->
            hookAfter(m) { runCatching { if (isModuleInfoScope(instance)) result = 2 } }
        }
        findMethodByParamCountOrNull(DEVICE_INFO_CLASS, "setDeviceLeAudioConnectState", 1)?.let { m ->
            hookAfter(m) { runCatching { if (isModuleInfoScope(instance)) result = 2 } }
        }

        // 3) 伪装型号字段统一为 Enco X3 productId
        findMethodByParamCountOrNull(DEVICE_INFO_CLASS, "setProductId", 1)?.let { m ->
            hookAfter(m) {
                runCatching {
                    if (isModuleInfoScope(instance)) {
                        result = XiberiaSpoof.spoofProductId()
                    }
                }.onFailure { modLogT("W", "registry setProductId override failed", it) }
            }
        }
        findMethodByParamCountOrNull(DEVICE_INFO_CLASS, "setA2dpActive", 1)?.let { m ->
            hookAfter(m) {
                runCatching { if (isModuleInfoScope(instance)) result = true }
            }
        }
        findMethodOrNull(DEVICE_INFO_CLASS, "isConnected")?.let { m ->
            hookAfter(m) {
                runCatching { if (isModuleInfoScope(instance)) result = true }
            }
        }

        modLog("I", "registry: DeviceInfoManager/DeviceInfo hooks registered")
    }

    /** 是否本次 hook 上下文指向模块已绑定设备（通过 DeviceInfo 自带地址判定）。 */
    private fun isModuleInfoScope(info: Any?): Boolean {
        if (info == null) return false
        val addr = normalizeAddressOrNull(extractDeviceInfoAddress(info)) ?: return false
        val ctx = currentContext() ?: return false
        return ModuleDeviceRegistry(ctx).getBoundDevice()?.address == addr
    }

    /** 从 DeviceInfo 提取蓝牙地址（兼容 getAddress/getMAddress/address/mC 等混淆形态）。 */
    private fun extractDeviceInfoAddress(info: Any?): String? =
        directStringMethod(
            info,
            "getAddress",
            "getMAddress",
            "getDeviceAddress",
            "getMac",
            "getMacAddress",
        ) ?: directStringField(
            info,
            "mAddress",
            "mMacAddress",
            "macAddress",
            "address",
            "C",
            "b",
            "j",
        )

    /** 用 DeviceInfoManager.f(productId, device, addr, name) 创建并 c() 注册，返回 DeviceInfo。 */
    private fun createMelodyDeviceInfo(device: BluetoothDevice, bound: BoundDevice): Any? {
        val mgrClass = findClassOrNull(DEVICE_INFO_MANAGER_CLASS) ?: return null
        val infoClass = findClassOrNull(DEVICE_INFO_CLASS) ?: return null
        val spoofId = bound.profile?.let(AdapterRegistry::spoofProductIdFor) ?: XiberiaSpoof.spoofProductId()
        val f: Method = findMethod(
            DEVICE_INFO_MANAGER_CLASS,
            "f",
            Int::class.javaPrimitiveType!!,
            BluetoothDevice::class.java,
            String::class.java,
            String::class.java,
        )
        f.isAccessible = true
        val addr = device.address
        val created = f.invoke(null, spoofId, device, addr, device.name ?: bound.profile?.displayName ?: XiberiaSpoof.defaultModelName())
        created ?: return null
        // c(info) 注册入表
        val c = findMethod(
            DEVICE_INFO_MANAGER_CLASS,
            "c",
            infoClass,
        )
        c.isAccessible = true
        c.invoke(mgrClass, created)
        return created
    }

    private fun hookGetterResult(className: String, methodName: String, value: Any?, global: Boolean = false) {
        hookGetter(className, methodName, global) { value }
    }

    private fun hookConnectionInfoVoAccessors(connectionVoClass: Class<*>) {
        val intMethods = connectionVoClass.declaredMethods
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.name != "hashCode" &&
                    (method.returnType == Int::class.javaPrimitiveType || method.returnType == java.lang.Integer::class.java)
            }
        val namedIntMethods = intMethods.filter { method ->
            method.name.contains("connection", ignoreCase = true) ||
                method.name.contains("connect", ignoreCase = true)
        }
        (namedIntMethods.takeIf { it.isNotEmpty() } ?: intMethods).forEach { method ->
            method.isAccessible = true
            hookAfter(method) {
                runCatching {
                    result = connectionStateFor(extractAddress(instance) ?: activeModuleAddress)
                }.onFailure { modLogT("W", "override connection info int ${method.name} failed", it) }
            }
        }

        val booleanMethods = connectionVoClass.declaredMethods
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    (method.returnType == Boolean::class.javaPrimitiveType || method.returnType == java.lang.Boolean::class.java)
            }
        val namedBooleanMethods = booleanMethods.filter { method ->
            method.name.contains("spp", ignoreCase = true) ||
                method.name.contains("connect", ignoreCase = true)
        }
        (namedBooleanMethods.takeIf { it.isNotEmpty() } ?: booleanMethods).forEach { method ->
            method.isAccessible = true
            hookAfter(method) {
                runCatching {
                    result = true
                }.onFailure { modLogT("W", "override connection info boolean ${method.name} failed", it) }
            }
        }
    }

    private fun hookGetter(
        className: String,
        methodName: String,
        global: Boolean = false,
        valueFactory: HookParam.() -> Any?,
    ) {
        findMethodOrNull(className, methodName)?.let { method ->
            hookAfter(method) {
                runCatching {
                    if (global || isModuleScope(instance)) result = valueFactory()
                }.onFailure { modLogT("W", "override getter $className#$methodName failed", it) }
            }
        }
    }

    private fun hookOneSpaceGetterResult(methodName: String, valueFactory: () -> Any?) {
        findMethodOrNull(ONESPACE_NOISE_VO_CLASS, methodName)?.let { method ->
            hookAfter(method) {
                if (activeModuleAddress != null && DeviceProfiles.get(activeProfileId) != null) {
                    result = valueFactory()
                }
            }
        }
    }

    private fun normalizePanelIntent(activity: Activity?) {
        if (activity == null) return
        lastContext = WeakReference(activity)
        reportHookStatus(activity)
        modLog(activity, "I", "处理面板 Intent ${activity.intent?.action}")
        val intent = activity.intent ?: return
        val mac = normalizeAddressOrNull(addressFromIntent(intent))
        if (mac == null) {
            if (activeModuleAddress == null) {
                bindActivePanelContext(null, null)
                activeDebugPreview = false
            }
            return
        }
        val profile = profileForPanel(activity, mac, intent) ?: run {
            bindActivePanelContext(null, null)
            activeDebugPreview = false
            return
        }
        // 切换设备：立即取消旧地址的 5 分钟轮询链，避免旧链残留一个周期内触发旧设备请求。
        activeModuleAddress?.takeIf { it != mac }?.let { cancelBatteryPolling(it) }
        bindActivePanelContext(mac, profile.id)
        activeDebugPreview = intent.getBooleanExtra(BluetoothPopupContract.EXTRA_DEBUG_PREVIEW, false)
        intent.putExtra("device_mac_info", mac)
        activeAncMode = currentAncMode(mac)
        if (!activeDebugPreview) scheduleStateRefresh(activity, mac, profile)
        if (intent.getStringExtra("device_title").isNullOrBlank()) {
            intent.putExtra("device_title", profile.displayName)
        }
        intent.putExtra("device_name", profile.displayName)
        intent.putExtra("product_id", profile.modelId)
        if (activity.javaClass.name == "com.oplus.melody.onespace.OneSpaceDetailActivity") {
            val colorId = intent.getStringExtra("product_color")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val modelId = intent.getStringExtra("model_id")
            if (modelId.isNullOrBlank() || '&' !in modelId) {
                intent.putExtra("model_id", "${profile.modelId}&$colorId")
            }
            intent.putExtra("product_color", colorId.toString())
        } else if (intent.getStringExtra("model_id").isNullOrBlank()) {
            intent.putExtra("model_id", profile.modelId)
        }
    }

    private fun normalizeDetailFragment(fragment: Any?) {
        if (fragment == null) return
        val context = contextFrom(fragment) ?: currentContext()
        val activity = callMethodOrNull(fragment, "getActivity") as? Activity
        if (activity != null) normalizePanelIntent(activity)
        val args = callMethodOrNull(fragment, "getArguments") as? Bundle
        val intent = activity?.intent
        val rawName = nameFromBundle(args)
            ?: intent?.let(::nameFromIntent)
            ?: directStringField(fragment, "t")
            ?: extractDeviceName(getObjectField(fragment, "a"))
        if (DeviceNameRuleState.isAirPodsName(rawName)) return

        val mac = normalizeAddressOrNull(addressFromBundle(args))
            ?: normalizeAddressOrNull(directStringField(fragment, "i"))
            ?: intent?.let(::addressFromIntent)?.let(::normalizeAddressOrNull)
            ?: launcherAddress(context)
            ?: addressForSupportedName(context, rawName)
            ?: return

        val profile = DeviceProfiles.get(activeProfileId)
            ?.takeIf { activeModuleAddress == mac }
            ?: profileFromBundle(args)
            ?: intent?.let { profileForPanel(context ?: activity ?: return, mac, it) }
            ?: ModuleDeviceRegistry(context ?: activity).profileForName(rawName)
            ?: return

        // 切换设备：立即取消旧地址轮询链，再绑定新设备。
        activeModuleAddress?.takeIf { it != mac }?.let { cancelBatteryPolling(it) }
        bindActivePanelContext(mac, profile.id)
        lastDetailFragment = WeakReference(fragment)
        val displayName = rawName?.takeIf { it.isNotBlank() } ?: profile.displayName
        args?.putString("device_mac_info", mac)
        args?.putString("device_name", displayName)
        args?.putString("device_title", displayName)
        args?.putString("product_id", profile.modelId)
        args?.putString("model_id", profile.modelId)
        args?.putString("device_product_id", profile.modelId)
        args?.putString("product_type", "earphone")
        setObjectField(fragment, "i", mac)
        setObjectField(fragment, "t", displayName)
        syncDetailViewModel(getObjectField(fragment, "a"), mac, displayName, profile)
        if (!activeDebugPreview) scheduleStateRefresh(context ?: activity ?: return, mac, profile)
        refreshDetailBatteryViews(fragment)
    }

    private fun syncDetailViewModel(viewModel: Any?, address: String, name: String, profile: DeviceProfile) {
        if (viewModel == null) return
        setObjectField(viewModel, "b", address)
        setObjectField(viewModel, "c", name)
        setObjectField(viewModel, "d", "earphone")
        setObjectField(viewModel, "e", profile.modelId)
        setObjectField(viewModel, "f", 0)
    }

    private fun handleDiscoveryPopupIntent(context: Context?, intent: Intent?) {
        if (context == null || intent == null || !isConnectedPopupIntent(intent)) {
            logChain(
                "I",
                "POPUP_INTENT_REJECT reason=ctxOrIntentOrGate action=${intent?.action} ctxNull=${context == null}",
            )
            return
        }
        lastContext = WeakReference(context)
        migrateLegacyMacBindings(context)
        reportHookStatus(context)
        logChain("I", "POPUP_INTENT_ACCEPT action=${intent.action} debug=${intent.getBooleanExtra(BluetoothPopupContract.EXTRA_DEBUG_PREVIEW, false)}")
        val popupDevice = popupDeviceFromIntent(context, intent) ?: run {
            logChain("I", "POPUP_INTENT_REJECT reason=popupDeviceFromIntent_null action=${intent.action}")
            return
        }
        if (!discoveryPopupGate.shouldTrigger(popupDevice.address, popupDevice.name, moduleSupported = true)) {
            logChain("I", "BT_GATE_DECISION allowed=false reason=dedupe addr=${popupDevice.address} name=${popupDevice.name}")
            return
        }
        logChain("I", "BT_GATE_DECISION allowed=true addr=${popupDevice.address} name=${popupDevice.name}")
        // [真值矫正] 新一次连接事件 = 全新弹窗会话：复位真实存活标志。
        //   旧弹窗（若 :fg 未广播销毁）不残留 → i() 出口矫正会强制放行，保证本次一定走 p() 启动路由。
        if (popupRealShowing) {
            logChain("I", "POPUP_REAL_STATE_RESET (new connect) addr=${popupDevice.address}")
        }
        popupRealShowing = false
        // 切换设备：立即取消旧地址轮询链。
        activeModuleAddress?.takeIf { it != popupDevice.address }?.let { cancelBatteryPolling(it) }
        bindActivePanelContext(popupDevice.address, popupDevice.profile.id)
        activeAncMode = HeadsetSessionManager.cachedState(popupDevice.address)?.ancMode ?: activeAncMode
        // 【连接即查】连接事件时刻主动建 SPP 会话查询三路电量并写入缓存（对齐官方 App「连接即查电量」）。
        //   不等弹窗渲染、不等缓存命中：此刻就查，弹窗 h(...) 渲染时缓存已就绪 → 首次连接不再有某一路缺失。
        primePopupBatteryOnConnect(popupDevice)
        postToUi(context) {
            routeDiscoveryPopup(context, popupDevice)
        }
    }

    /**
     * 连接事件时刻电量主动查询（「连接即查」）。
     *
     * 与官方 App 语义一致：**设备一连上就主动查询电量并注入**，而不是「等弹窗渲染 → 读缓存 → 缓存为空才补」。
     * 关键差异：
     *   - 首次连接（该地址从未采过）无条件立即查询，不受任何节流窗口限制；
     *   - 查询在 IO 线程建/复用 SPP 会话（`HeadsetSessionManager.refresh` 内含懒连接），
     *     完成后写 [detailBatteryCache] 并在弹窗在场时重放 `h(...)`；
     *   - 缓存按槽位合并，某一路偶发落空时保留上一轮已得值，不会「新值覆盖旧值把某路抹掉」。
     */
    private fun primePopupBatteryOnConnect(popupDevice: DiscoveryPopupDevice) {
        val normalized = runCatching { DeviceRegistryStore.normalizeAddress(popupDevice.address) }.getOrNull()
            ?: popupDevice.address
        // 弹窗档案若 adapter 未注册（伪装捐赠档案），回退到可用的真实档案。
        val profile = popupDevice.profile.takeIf { AdapterRegistry.resolve(it.adapter) != null }
            ?: resolvePopupBatteryProfile(normalized)
            ?: return
        val context = currentContext() ?: currentApplication() ?: return
        val appContext = context.applicationContext ?: context
        if (!popupBatteryInFlight.add(normalized)) return
        popupBatteryPrimedAt[normalized] = System.currentTimeMillis()
        // [首连实时] 连接事件 = 全新会话：先清空本模块电量缓存，保证首连**从零实时读**，
        //   绝不掺入上一会话残留（真机表现为「隔几分钟首连只出两路/某路陈旧」）。
        detailBatteryCache.remove(normalized)
        // [修复·渲染早于补采] 连接 = 全新会话：同步清空「最近成功渲染快照」，避免上一会话残留电量掺入。
        popupBatteryLastGood.remove(normalized)
        scope.launch {
            logChain("I", "POPUP_BATTERY_PRIME_CONNECT addr=$normalized profile=${profile.id}")
            var fetched: BatteryState? = null
            // 连接即查内部重试：SPP 会话刚建立时首查有概率落空（对端未就绪），
            //   最多 3 次、间隔 600ms，直到三路齐全（对齐官方 App 连接后短暂延迟才出电量）。
            for (attempt in 1..PRIME_QUERY_MAX_ATTEMPTS) {
                val result = runCatching {
                    HeadsetSessionManager.refresh(appContext, normalized, profile.id)?.battery
                }.onFailure { logChain("E", "POPUP_BATTERY_PRIME_FAILED addr=$normalized attempt=$attempt err=$it") }
                    .getOrNull()
                if (result != null && result.hasAnyLevel()) {
                    // [首连实时·去缓存] 首连补采结果**直接覆盖**缓存，不与旧值合并，
                    //   避免上一会话残留电量掺入（真机表现为「首连某路显示上次/只出两路」）。
                    detailBatteryCache[normalized] = result
                    publishBatteryShare(appContext, normalized, result)
                    fetched = result
                    logChain(
                        "I",
                        "POPUP_BATTERY_PRIMED addr=$normalized attempt=$attempt " +
                            "left=${result.left} right=${result.right} box=${result.caseBattery} single=${result.single}",
                    )
                    if (result.isComplete()) break
                } else {
                    logChain("E", "POPUP_BATTERY_PRIME_EMPTY addr=$normalized attempt=$attempt")
                }
                if (attempt < PRIME_QUERY_MAX_ATTEMPTS) kotlinx.coroutines.delay(PRIME_QUERY_RETRY_DELAY_MS)
            }
            val best = currentBatteryState(normalized) ?: fetched
        publishBatteryShare(appContext, normalized, best)
            if (best != null && best.hasAnyLevel()) {
                postToUi(appContext) {
                    val h = lastPopupBatteryHolder.get()
                    if (h != null) {
                        val m = runCatching {
                            h.javaClass.declaredMethods.firstOrNull {
                                it.name == "h" && it.parameterTypes.size == 9
                            }
                        }.getOrNull()
                        if (m != null) {
                            runCatching { replayPopupBattery(h, m, currentBatteryState(normalized) ?: best) }
                                .onFailure { logChain("E", "POPUP_BATTERY_PRIME_REPLAY failed: $it") }
                        }
                    }
                }
                runCatching { scheduleBatteryPolling(appContext, normalized, profile) }
            }
            popupBatteryInFlight.remove(normalized)
        }
    }

    /**
     * 一次性迁移：把「旧绑定真源」里已识别过的 MAC→型号 提升到新的跨进程共享目录。
     *
     * 旧实现把绑定写在各进程私有 prefs；其中 melody 宿主进程的
     * `mac_profile_cache.xml` 历史数据最可能含有用户真机 MAC。迁移后即便不再重连，
     * MAC 反查也 immediately 生效（免用户重连一次）。
     */
    private fun migrateLegacyMacBindings(context: Context) {
        runCatching {
            val legacy = context.getSharedPreferences("mac_profile_cache", Context.MODE_PRIVATE)
            @Suppress("UNCHECKED_CAST")
            val entries = legacy.all.entries
                .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
                .filter { (mac, profileId) ->
                    mac.length == 12 && DeviceProfiles.get(profileId) != null
                }
            if (entries.isEmpty()) return@runCatching
            var migrated = 0
            entries.forEach { (mac, profileId) ->
                // hook 侧运行在宿主进程（melody / 蓝牙进程）：context.filesDir 指向**宿主**目录，
                // 直接 DeviceBindingStore.save 会写错位置——实测污染 /data/data/com.oplus.melody/files/bindings。
                // 统一经 DeviceRegistryStore.saveMacProfile：Provider 优先落到模块私有目录，与本包其它写入点一致。
                DeviceRegistryStore.saveMacProfile(context, mac, profileId)
                migrated++
            }
            logChain("I", "MAC_BINDING_MIGRATE legacy=${entries.size} migrated=$migrated")
        }.onFailure { modLogT("W", "migrate legacy mac bindings failed", it) }
    }

    private fun isConnectedPopupIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false
        if (action == BluetoothPopupContract.ACTION_MODULE_AUDIO_CONNECTED &&
            intent.getBooleanExtra(BluetoothPopupContract.EXTRA_MODULE_TRIGGER, false)
        ) {
            return true
        }
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) return true
        if (action !in AUDIO_CONNECTION_ACTIONS) return false
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        val prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
        return state == BluetoothProfile.STATE_CONNECTED && prevState != BluetoothProfile.STATE_CONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun popupDeviceFromIntent(context: Context, intent: Intent): DiscoveryPopupDevice? {
        val device = bluetoothDeviceExtra(intent)
        val rawAddress = intent.getStringExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS)
            ?: runCatching { device?.address }.getOrNull()
            ?: return null
        val address = runCatching { DeviceRegistryStore.normalizeAddress(rawAddress) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val name = intent.getStringExtra(BluetoothPopupContract.EXTRA_DEVICE_NAME)
            ?: intent.getStringExtra("device_name")
            ?: intent.getStringExtra("device_title")
            ?: runCatching { device?.name }.getOrNull()
            ?: return null
        val registry = ModuleDeviceRegistry(context)
        val explicitProfile = DeviceProfiles.get(intent.getStringExtra(BluetoothPopupContract.EXTRA_PROFILE_ID))
        val debugPreview = intent.getBooleanExtra(BluetoothPopupContract.EXTRA_DEBUG_PREVIEW, false)
        // 修复（改名反查）：MAC→型号 绑定优先于蓝牙名。用户改了蓝牙名后，
        //   isModuleSupportedName(name) 会失配 → 弹窗被丢弃，即「改名后不弹窗」。
        //   这里先按 MAC 反查绑定型号；命中则直接放行并按该型号装配。
        val macProfile = DeviceRegistryStore.profileIdForMac(context, address)
            ?.let { DeviceProfiles.get(it) }
        // 官方耳机（如 OPPO Enco X3）等非模块设备：仅在 debug 注入路径放行，不影响真机白名单。
        val supported = macProfile != null || registry.isModuleSupportedName(name) || (debugPreview && explicitProfile != null)
        if (!supported) return null
        // debug 注入优先使用显式 profileId（officialEncoX3），
        // 避免先按名字解析而在同名同 id 的多个 profile 间取错对象（profile 单一真源）。
        val profile = if (debugPreview && explicitProfile != null) {
            explicitProfile
        } else {
            macProfile
                ?: registry.profileForName(name)
                ?: explicitProfile
                ?: DeviceProfiles.sonyWf1000Xm3
        }
        // 首次按名称识别成功 → 立即把 MAC 绑定落盘（供改名后反查）；已绑定则幂等跳过。
        if (!debugPreview && macProfile == null && registry.isModuleSupportedName(name)) {
            runCatching { DeviceRegistryStore.saveMacProfile(context, address, profile.id) }
        }
        modLog(
            context,
            "I",
            "POPUP_DEVICE_RESOLVED addr=$address name=$name profile=${profile.id} adapter=${profile.adapter} " +
                "productIdHex=${AdapterRegistry.spoofIdHexFor(profile)} debug=$debugPreview",
        )
        return DiscoveryPopupDevice(address, name, profile, debugPreview)
    }

    @Suppress("DEPRECATION")
    private fun bluetoothDeviceExtra(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    // ============================ DiscoveryActionManager（弹窗真实入口） ============================
    // melody 16.9.1 逆向后确认（npmcp workspace 35147598）：
    //   Lg6/j;  .source "DiscoveryActionManagerServerImpl.java"  .super Lcom/oplus/melody/model/scan/a;
    //           → 含 p(String,String,String,int,int,boolean)，内部即 startActivity(showDiscoveryActivity)
    //   Lg6/a;  .source "DiscoveryActionManagerClientImpl.java"  .super 同上
    //           → 无 p()，仅 bindService 转发（跨进程 client 桩）
    //   p 的真实调用点（Lg6/e; 第 190~201 行）：
    //     p(getAddress, "onForwardConnectedPopup", getProductId, 0, getProductColor, false)
    // 结论：必须拿到 g6.j 的 server 实例；model/scan/a;->a() 服务定位器在后台进程返回的是 client 桩，
    //       找不到 p 就静默跳过 → 这正是原「弹窗不弹 / NoSuchMethod p」的根因。
    // 姿势对齐参考模块（HuaweiMelodyCompat HookEntryV171MelodyBridge）：
    //   XposedHelpers.findClass("g6.j") + hookAllConstructors 捕获 server 实例。
    @Volatile
    private var discoveryManagerServer: Any? = null
    private val pendingDiscoveryPopups = ConcurrentHashMap<String, DiscoveryPopupDevice>()
    /** 中继/server 未就绪的有限重试计数（address → 已尝试次数）。 */
    private val discoveryRetryAttempts = ConcurrentHashMap<String, Int>()

    /** 中继重试退避序列（ms）：0 / 100 / 500 / 1s / 2s，用尽即 DAMS_RETRY_EXHAUSTED。 */
    private val discoveryRetryDelays = longArrayOf(0, 100, 500, 1000, 2000)

    /** 链路结构化日志：单次调用即双通道（LSP + 模块内部），见 [modLog]。 */
    private fun logChain(level: String, message: String) {
        modLog(level, message)
    }

    /** 当前进程名（API28+ 用 Application.getProcessName，否则退回 ActivityManager）。 */
    /** [SPP 单所有者] 当前是否弹窗进程 `:fg`（不拥有 SPP，一律不建链）。 */
    private fun isPopupProcessNow(): Boolean =
        runCatching { android.app.Application.getProcessName() }.getOrNull()?.endsWith(":fg") == true

    /** [SPP 单所有者] 主进程把新查到的电量落盘共享，并广播通知 :fg 刷新弹窗（:fg 不建链）。 */
    private fun publishBatteryShare(context: Context?, address: String, state: BatteryState?) {
        if (isPopupProcessNow() || context == null) return
        val st = state?.takeIf { it.hasAnyLevel() } ?: return
        runCatching {
            BatteryShare.write(context, address, st)
            val i = Intent(BluetoothPopupContract.ACTION_BATTERY_UPDATED).apply {
                setPackage(context.packageName)
                putExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS, address)
            }
            context.sendBroadcast(i)
        }.onFailure { modLog("I", "BATTERY_PUBLISH failed: " + it) }
    }

    private fun currentProcessName(context: Context?): String? =
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else null
        }.getOrNull() ?: runCatching {
            val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }.getOrNull() ?: context?.packageName

    /** 捕获 g6.j（DiscoveryActionManagerServerImpl）构造实例，作为弹窗投递目标。 */
    private fun hookDiscoveryActionManagerServer() = safeHook(TAG, "DiscoveryActionManager server capture") {
        val serverClass = findClassOrNull(SERVER_IMPL_CLASS) ?: findClassOrNull("g6.j") ?: run {
            modLog("I", "DAMS: g6.j not found, skip")
            return@safeHook
        }
        // 1) 构造期捕获 server 单例（melody 启动即创建）
        runCatching {
            serverClass.declaredConstructors.forEach { ctor ->
                ctor.isAccessible = true
                hookConstructorAfter(ctor) {
                    runCatching {
                        val inst = instance ?: return@runCatching
                        discoveryManagerServer = inst
                        modLog("I", "DAMS: server captured ${inst.javaClass.name}")
                        flushPendingDiscoveryPopups()
                    }
                }
            }
        }.onFailure { modLog("I", "DAMS: hook ctor failed: $it") }

        // 2) hook p 观测真实弹窗调用，同时兜底捕获实例
        findMethodOrNull(SERVER_IMPL_CLASS, "p", String::class.java, String::class.java, String::class.java,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)?.let { m ->
            hookAfter(m) {
                runCatching {
                    if (discoveryManagerServer == null) discoveryManagerServer = instance
                    modLog("I", "DAMS: p() observed args=$args")
                }
            }
        }
        // 3) hook 基类 a() 定位器：主进程首次返回 server 时缓存
        findMethodOrNull(BASE_CLASS, "a")?.let { m ->
            hookAfter(m) {
                runCatching {
                    val inst = result ?: return@runCatching
                    if (discoveryManagerServer == null && findDiscoveryPopupMethod(inst.javaClass) != null) {
                        discoveryManagerServer = inst
                        modLog("I", "DAMS: server captured via a()")
                    }
                }
            }
        }
        modLog("I", "DAMS: hooks installed on $SERVER_IMPL_CLASS")
    }

    // ============================ 已连接发现弹窗桥（对齐参考模块 installConnectedPopupBridge） ============================
    // 参考模块 HuaweiMelodyCompat#installConnectedPopupBridge 在 16.9.1 上装了 5 组 hook，我们逐组对齐：
    //   $16  g6.j.p(6参) before → 改写 args[2]=伪装 productId hex、args[4]=color
    //   $17  com.oplus.melody.ui.base.g.b(Context) before → 往路由 Intent 塞 hmc_popup_session
    //   $18  DiscoveryDialogActivity.onCreate before → 置空 savedInstanceState（强制走新 Intent）
    //   $19  DiscoveryDialogActivity.onResume after → 交付确认
    //   $20  Activity.onNewIntent after → 若是本弹窗则 setIntent + recreate（重复弹窗可用）
    //   $21  DiscoveryDialogActivity.onDestroy after → 清理跟踪
    //   $22  DiscoveryDialogViewModel.d(String,EarphoneDTO,H0) after → 对 result(H0) 注入型号/颜色/电量
    private const val DISCOVERY_DIALOG_ACTIVITY = "com.oplus.melody.app.discovery.DiscoveryDialogActivity"
    private const val DISCOVERY_DIALOG_VIEW_MODEL = "com.oplus.melody.app.discovery.DiscoveryDialogViewModel"
    /** 发现弹窗卡片 ViewHolder（K0）：字段 a 为 MelodyCompatImageView，a(MelodyResourceDO,B1,VO) 负责绑定图片。 */
    private const val DISCOVERY_RECYCLE_HOLDER_CLASS = "com.oplus.melody.app.discovery.K0"
    /** 发现弹窗 RecyclerView 适配器：字段 d 为 List<DiscoveryRecycleItemVO>。 */
    private const val DISCOVERY_RECYCLE_ADAPTER_CLASS = "com.oplus.melody.app.discovery.DiscoveryRecycleAdapter"
    /**
     * 发现弹窗电量 ViewHolder = `DiscoveryBatteryDefaultViewHolder`（混淆名 b，字段 f/g/h/i:Integer + j/k:boolean）。
     *
     * 真正的渲染入口是底层 `h(IZZIZZIZZ)V`：
     *   p1..p3 = left :  (值, 充电, 有效)
     *   p4..p6 = center: (值, 充电, 有效)   ← 仓电量槽
     *   p7..p9 = right : (值, 充电, 有效)
     * 内部逐个调 `k(值, 槽id, 充电, 有效)` → 槽 0x7f090363=left / 0x7f090362=center / 0x7f090365=right；
     * `k` 对「值<=0 或 值>100 或 无效」的槽直接隐藏（这就是宿主 box 无值时只出现 2 个槽的原因）。
     *
     * 上层 `g(IZIZIZ)V` = (left,leftChg,box,boxChg,right,rightChg) 只是计算有效性后转 h；
     * 当前版本弹窗经 xref 证实**直接调 h**（362/363/365 仅被 h 引用），故 hook 必须落在 h。
     */
    private const val DISCOVERY_RECYCLE_HOLDER_VIEW_CLASS = "com.oplus.melody.app.discovery.b"
    private const val ROUTER_CLASS = "com.oplus.melody.ui.base.g"
    /** 弹窗配色（参考模块 popupDonorColor=1）。 */
    private const val POPUP_DONOR_COLOR = 1
    /**
     * 弹窗主图 ImageView 的资源 id = `melody_app_discovery_image_view`（0x7f090377）。
     * 修正：旧值 2131297136(0x7f090370) 实测为 `melody_app_discovery_full_background`（match_parent 全屏背景），
     *   导致主图位采样失败 / 误选全屏背景 / 三图装配落空 → 宿主 X3 图与裸数字电量残留。
     */
    private const val POPUP_MAIN_IMAGE_ID = 0x7f090377
    /**
     * 连接发现弹窗内「三图布局」整体垂直位移（dp，负值=上移）。
     * 弹窗复用了主图位的父容器高度且列重力为 CENTER_VERTICAL → 图文偏下、徽标压到「已连接 XX」文案。
     * 详情页不传此值（默认 0），互不影响。
     */
    /** 会话标记 key（参考模块 hmc_popup_session）。 */
    private const val POPUP_SESSION_EXTRA = "hmc_popup_session"
    /** 本次注入会话 id（每个进程一次，用于区分本模块投递的弹窗）。 */
    private val popupSessionId: String = java.util.UUID.randomUUID().toString()
    /**
     * [真值矫正] 弹窗是否真的活着（由 :fg 侧 DiscoveryDialogActivity 生命周期广播实时同步）。
     * 主进程 g6.j.i() 的闩锁在 :fg 进程重启后可能残留 true；以本标志为准在 i() 出口矫正。
     * @Volatile 保证跨线程可见；新一次连接事件会先复位为 false。
     */
    @Volatile
    private var popupRealShowing = false
    private val attachedDialogActivities: MutableSet<Any> = ConcurrentHashMap.newKeySet()
    private val recreatedDialogActivities: MutableSet<Any> = ConcurrentHashMap.newKeySet()

    private fun hookConnectedPopupBridge() = safeHook(TAG, "connected discovery popup bridge") {
        hookDiscoveryPopupRewrite()
        hookDiscoveryRouterSession()
        hookDiscoveryDialogLifecycle()
        hookDiscoveryDialogViewModel()
        hookDiscoveryPopupBattery()
        hookPopupGateDiagnostics()
        hookDiscoveryPopupImage()
        hookDiscoveryPopupMainImage()
        hookFastProductImage()
    }

    /**
     * P0-I：弹窗「图片零闪帧」快速通道 —— 在图片真正落到 ImageView 的同一帧替换。
     *
     * 问题（用户实测）：连接发现弹窗弹出后先显示 1~2 秒宿主官方 X3 图，随后才被模块图替换。
     *
     * 根因（16.9.1 实读 smali）：
     *   - 弹窗主图位 = `DiscoveryDialogActivity.v`（ViewStub 0x7f090361 inflate 的 ImageView），
     *     由 `M(ImageView,B1,s)` → `Glide.with(act).load(file).into(v)` **异步**加载官方图；
     *   - 列表卡片 = `K0.a(...)` → `MelodyCompatImageView.d(res,root,fb,false)` 内部同样 Glide `into(this)`；
     *   - `MelodyCompatImageView` **覆写**了 `setImageDrawable`（super 调用后再处理 animationDrawable 字段），
     *     因此本模块挂在 `AppCompatImageView.setImageDrawable` 的旧 hook **在弹窗上根本不触发**；
     *   - `M()`/`K0.a()` 的 hookAfter 跑在 Glide 异步回填**之前** → 官方 X3 先入画，
     *     模块 `onResume` 延迟任务（200ms 起）之后才覆盖 → 即那段 1~2 秒闪帧。
     *
     * 解法：hook `MelodyCompatImageView.setImageDrawable(Drawable)` 的 **before**，
     *   直接把入参 drawable 换成模块单张主图（`setArg(0, moduleDrawable)`）。
     *   宿主 Glide 回调调用 `setImageDrawable(x3)` 的同一调用帧内即被替换，X3 从不进入绘制。
     *   缓存 1.2s 的 ConstantState，避免每帧重复走 Provider IPC 取图。
     *
     * 门控：仅当 ImageView 属于 `DiscoveryDialogActivity`（弹窗主图位 / 弹窗列表卡片）时生效，
     *   不干扰详情页（详情页仍走各自既有 hook）。
     */
    private fun hookFastProductImage() = safeHook(TAG, "fast product image (no-flash)") {
        val ivClass = findClassOrNull(MELODY_COMPAT_IMAGE_VIEW_CLASS) ?: run {
            logChain("E", "FASTIMG class not found: $MELODY_COMPAT_IMAGE_VIEW_CLASS")
            return@safeHook
        }
        val setter = ivClass.declaredMethods.firstOrNull {
            it.name == "setImageDrawable" && it.parameterTypes.size == 1 &&
                android.graphics.drawable.Drawable::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: run {
            logChain("E", "FASTIMG setImageDrawable(Drawable) not found")
            return@safeHook
        }
        setter.isAccessible = true
        hookBefore(setter) {
            runCatching {
                val iv = instance as? android.widget.ImageView ?: return@runCatching
                // 小图位（animation_iv 0x35e）：清空+隐藏，避免与主图位同屏叠加成「两张耳机图」。
                if (isPopupSubImageSlot(iv)) {
                    setArg(0, null)
                    iv.visibility = View.GONE
                    // [修复·StackOverflow→ANR] 旧实现在此额外调用 iv.setImageDrawable(null)，
                    //   而本回调 hook 的正是 MelodyCompatImageView.setImageDrawable：
                    //   在 before 内再调它 = 无限递归 → StackOverflowError → UI 线程卡死
                    //   → DiscoveryDialogActivity ANR 被 Force finishing 并杀进程（用户端：弹窗闪一下/不显示）。
                    //   setArg(0, null) 已把本次入参置空，宿主方法体执行时本身就是
                    //   setImageDrawable(null)，无需也不可再显式调用，故删除该行。
                    val key = "FASTSUB_HIDE#${iv.id}"
                    if (loggedProductImageTargets.add(key)) {
                        logChain("I", "FASTIMG_HIDE sub-slot iv=${iv.id} (清空宿主设备图)")
                    }
                    return@runCatching
                }
                if (!isPopupImageView(iv)) return@runCatching
                val profile = resolveActivePopupProfile() ?: run {
                    // [修复·弹窗误杀] 档案解析失败时不做任何拦截：
                    //   放行宿主原图（可能稍后被 M()/onResume 延迟覆盖）。
                    //   旧实现在此直接 setArg(0,null)+INVISIBLE，把「档案暂时不可用」
                    //   变成「弹窗图全灭」，是用户端「弹窗失效」的直接原因之一。
                    val key = "FASTPASS0#${iv.id}"
                    if (loggedProductImageTargets.add(key)) {
                        logChain("I", "FASTIMG_PASS profile=null iv=${iv.id} (放行宿主图)")
                    }
                    return@runCatching
                }
                val drawable = fastProductDrawable(iv.context, profile)
                if (drawable != null) {
                    // 同帧顶替：宿主本次 set 的官方图（X3）被入参替换，从不进入下一帧绘制。
                    setArg(0, drawable)
                    iv.alpha = 1f
                    iv.visibility = View.VISIBLE
                    val key = "FAST#${profile.id}#${iv.id}"
                    if (loggedProductImageTargets.add(key)) {
                        logChain("I", "FASTIMG_SWAP profile=${profile.id} iv=${iv.id} ${iv.width}x${iv.height}")
                    }
                } else {
                    // [修复·弹窗误杀] 档案有但模块图未就绪：同样放行宿主图。
                    //   模块后续的延迟覆盖任务（200ms 起）会兜底替换，不再永久隐藏。
                    val key = "FASTPASS2#${profile.id}#${iv.id}"
                    if (loggedProductImageTargets.add(key)) {
                        logChain("I", "FASTIMG_PASS profile=${profile.id} iv=${iv.id} drawable=null (放行宿主图)")
                    }
                }
            }.onFailure { logChain("E", "FASTIMG apply failed: $it") }
        }
        logChain("I", "FASTIMG hooked $MELODY_COMPAT_IMAGE_VIEW_CLASS.setImageDrawable")
    }

    /**
     * 该 ImageView 是否命中「主图位」——FASTIMG 顶替的唯一目标。
     *
     * [修复·双图叠加] 旧实现只要 ImageView 的 context 链在 DiscoveryDialogActivity 内即返回 true，
     *   弹窗内所有 MelodyCompatImageView 都会被顶替 + 强制 VISIBLE
     *   （实测日志：FASTIMG_SWAP iv=2131297144[0x378 iv_tags] / 2131297118[0x35e animation_iv] /
     *   2131297110[0x356 battery_error]），用户端表现为「弹窗出现两张模块耳机图」。
     *   现收紧为仅主图位（0x7f090377 / melody_app_discovery_image_view）：小图一律放行宿主原图，
     *   不贴模块图、不改可见性。多设备列表卡片仍由 POPUP_IMG adapter/holder hook 独立接管。
     */
    private fun isPopupImageView(iv: android.widget.ImageView): Boolean = isPopupMainImageSlot(iv)

    /** 弹窗「主图位」判定：resource 名 `melody_app_discovery_image_view`（id 0x7f090377）命中。 */
    private fun isPopupMainImageSlot(iv: android.widget.ImageView): Boolean {
        if (iv.id == POPUP_HOST_MAIN_IMAGE_ID) return true
        val ctx = iv.context ?: return false
        val resolved = runCatching {
            ctx.resources.getIdentifier(POPUP_HOST_MAIN_IMAGE_NAME, "id", ctx.packageName)
        }.getOrDefault(0)
        return resolved != 0 && iv.id == resolved
    }

    /**
     * 弹窗「小图位」判定：resource 名 `melody_app_discovery_animation_iv`（id 0x7f09035e）命中。
     * 该位与主图位同屏重叠，是「两张耳机图」的第二张（宿主设备图），需清空隐藏。
     */
    private fun isPopupSubImageSlot(iv: android.widget.ImageView): Boolean {
        if (iv.id == POPUP_HOST_SUB_IMAGE_ID) return true
        val ctx = iv.context ?: return false
        val resolved = runCatching {
            ctx.resources.getIdentifier(POPUP_HOST_SUB_IMAGE_NAME, "id", ctx.packageName)
        }.getOrDefault(0)
        return resolved != 0 && iv.id == resolved
    }

    /** 快速贴图用的主图 Drawable（带 1.2s ConstantState 缓存，避免每帧 Provider IPC）。 */
    private fun fastProductDrawable(context: Context, profile: DeviceProfile): Drawable? {
        val key = "${profile.id}#main"
        val now = System.currentTimeMillis()
        fastImageCache[key]?.let { (cs, ts) ->
            if (now - ts < FAST_IMAGE_CACHE_MS) {
                return cs?.newDrawable(context.resources)?.also { it.alpha = 255 }
            }
        }
        val drawable = productDrawable(context, profile)
        fastImageCache[key] = drawable?.constantState to now
        return drawable
    }

    /**
     * P0-H：弹窗主图（单设备大图）覆盖。
     *
     * 实测定位（16.9.1 反编译）：DiscoveryDialogActivity.M(ImageView, B1, s) 是弹窗主图唯一加载入口：
     *   if (B1.getStrangeRes() == null) return;   // <- 伪造型号无资源包 -> 主图根本不加载（白屏）
     *   File f = s.E(ctx, B1.getRootPath(), res.getUrl());
     *   if (f == null) { log "loadStrangeResStaticImage file not found"; return; }
     *   Glide.with(ctx).load(f).into(p1)
     * 多设备列表走 RecyclerView(Adapter/K0)，单设备弹窗主图不走那里 —— 旧 hook 位置错误。
     */
    private fun hookDiscoveryPopupMainImage() = safeHook(TAG, "discovery popup main image") {
        val activityClass = findClassOrNull(DISCOVERY_DIALOG_ACTIVITY) ?: run {
            logChain("E", "POPUP_MAINIMG activity not found")
            return@safeHook
        }
        // (1) M(ImageView, B1, s) after：精确接管 strangeRes==null 的白屏分支
        runCatching {
            activityClass.declaredMethods
                .filter {
                    it.name == "M" && it.parameterTypes.size == 3 &&
                        android.widget.ImageView::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                .forEach { method ->
                    method.isAccessible = true
                    hookAfter(method) {
                        runCatching {
                            val iv = args.getOrNull(0) as? android.widget.ImageView ?: return@runCatching
                            // 小图位（animation_iv 0x35e）：清空+隐藏，杜绝与主图位叠加。
                            if (isPopupSubImageSlot(iv)) {
                                runCatching {
                                    iv.animate().cancel()
                                    iv.clearAnimation()
                                    iv.setImageDrawable(null)
                                    iv.setBackground(null)
                                    iv.visibility = View.GONE
                                }
                                logChain("I", "POPUP_MAINIMG M-HIDE sub-slot iv=${iv.id}")
                                return@runCatching
                            }
                            val b1 = args.getOrNull(1)
                            val strange = b1?.let { callMethodOrNull(it, "getStrangeRes") }
                            logChain("I", "POPUP_MAINIMG M called strangeRes=${strange != null} activeAddr=$activeModuleAddress activeProfile=$activeProfileId")
                            // 无论 strangeRes 是否为 null，主图位优先由本模块贴图接管（避免宿主把
                            //   被重写 productId 后加载到的 X3 大图铺回主图位）。
                            // [修复·弹窗误杀] 关键边界：档案不可用 / 贴图失败时**不再隐藏主图位**。
                            //   旧实现此处 setImageDrawable(null)+INVISIBLE 把「暂时性解析失败」
                            //   放大成「弹窗图全灭」（用户端 = 弹窗失效）。
                            //   新语义：能贴则贴；不能贴则原样放行宿主加载链（含其兜底图），
                            //   延迟覆盖任务仍会在 200ms+ 后重试贴模块图。
                            val profile = resolveActivePopupProfile()
                            if (profile != null && applyProductImageTo(iv, profile)) {
                                logChain("I", "POPUP_MAINIMG M-main profile=${profile.id}")
                            } else {
                                logChain("I", "POPUP_MAINIMG M-pass profile=${profile?.id} (放行宿主图，仅清动画)")
                                iv.animate().cancel()
                                iv.clearAnimation()
                            }
                        }.onFailure { logChain("E", "POPUP_MAINIMG M apply failed: $it") }
                    }
                    logChain("I", "POPUP_MAINIMG hooked M()")
                }
        }.onFailure { logChain("E", "POPUP_MAINIMG hook M failed: $it") }
        // (2) onResume after：多次扫描视图树（含 content view），覆盖主图
        findMethodOrNull(DISCOVERY_DIALOG_ACTIVITY, "onResume")?.let { method ->
            hookAfter(method) {
                runCatching {
                    val activity = instance as? android.app.Activity ?: return@runCatching
                    logChain("I", "MAINIMG_ONRESUME enter")
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    val applyOverlay = Runnable {
                        runCatching {
                            val collected = ArrayList<android.widget.ImageView>()
                            val allViews = ArrayList<android.view.View>()
                            fun walk(v: android.view.View?) {
                                if (v == null) return
                                allViews.add(v)
                                if (v is android.widget.ImageView) collected.add(v)
                                if (v is android.view.ViewGroup) {
                                    for (i in 0 until v.childCount) runCatching { walk(v.getChildAt(i)) }
                                }
                            }
                            val decor = activity.window?.decorView
                            logChain("I", "MAINIMG_DECOR cls=${decor?.javaClass?.name} children=${(decor as? android.view.ViewGroup)?.childCount}")
                            walk(decor)
                            walk(activity.findViewById<android.view.View>(android.R.id.content))
                            // 全量 dump 视图树（单行），确认宿主真实布局
                            runCatching {
                                val sb = StringBuilder()
                                allViews.take(60).forEach { v ->
                                    sb.append(" | ").append(v.javaClass.simpleName)
                                        .append("(").append(v.id).append(")")
                                        .append(v.width).append("x").append(v.height)
                                        .append(" vis=").append(v.visibility)
                                }
                                logChain("I", "MAINIMG_TREE total=${allViews.size}$sb")
                            }.onFailure { logChain("E", "MAINIMG_TREE failed: $it") }
                            // 反射 WindowManagerGlobal：覆盖独立窗口（Dialog 等）中的主图
                            runCatching {
                                val wmg = Class.forName("android.view.WindowManagerGlobal")
                                val inst = wmg.getMethod("getInstance").invoke(null)
                                val rootsField = wmg.getDeclaredField("mRoots").apply { isAccessible = true }
                                val roots = rootsField.get(inst) as? List<*>
                                logChain("I", "MAINIMG_WMG roots=${roots?.size}")
                                roots?.forEach { root ->
                                    runCatching {
                                        val f = root?.javaClass?.getDeclaredField("mView")?.apply { isAccessible = true }
                                        walk(f?.get(root) as? android.view.View)
                                    }
                                }
                            }.onFailure { logChain("E", "MAINIMG_WMG failed: $it") }
                            // 反射 Activity 字段：直接找 ImageView 类型字段
                            runCatching {
                                var cls: Class<*>? = activity.javaClass
                                while (cls != null && cls != android.app.Activity::class.java) {
                                    cls.declaredFields.forEach { f ->
                                        runCatching {
                                            f.isAccessible = true
                                            val value = f.get(activity)
                                            if (value is android.widget.ImageView) {
                                                logChain(
                                                    "I",
                                                    "MAINIMG_FIELD ${f.name} ${value.width}x${value.height} vis=${value.visibility} drawable=${value.drawable != null}",
                                                )
                                                collected.add(value)
                                            }
                                        }
                                    }
                                    cls = cls.superclass
                                }
                            }.onFailure { logChain("E", "MAINIMG_FIELD failed: $it") }
                            // [降噪] 原先对每个 ImageView 逐条打印 MAINIMG_VIEW（每轮 walk 数十条，日志实测 254 条）。
                            //   命中主图时由下方 POPUP_MAIN_APPLIED / walk-pass 各汇总一行即可，无需逐 View 打印。
                            // 小图位（animation_iv 0x35e）持续压制：宿主可能在其加载链里回填，
                            //   每轮 walk 都清空+隐藏，保证弹窗只剩模块单张主图。
                            collected.filter { isPopupSubImageSlot(it) }.forEach { iv ->
                                runCatching {
                                    iv.animate().cancel()
                                    iv.clearAnimation()
                                    iv.setImageDrawable(null)
                                    iv.setBackground(null)
                                    iv.visibility = View.GONE
                                }
                            }
                            val profile = resolveActivePopupProfile()
                            val all = collected.distinct()
                            val main = all
                                .filter { it.width > 80 && it.height > 80 && it.visibility == View.VISIBLE }
                                .maxByOrNull { if (it.id == POPUP_MAIN_IMAGE_ID) Int.MAX_VALUE else it.width * it.height }
                            var hidden = 0
                            // 弹窗主图位：直接贴模块单张主图（profile 有效时）。
                            // [修复·弹窗误杀] 删除「清空其它大图」循环：它会误伤弹窗正常 UI
                            //   （全屏背景 melody_app_discovery_full_background 等 ≥200x200 带 drawable 的 view
                            //   会被一并清空隐藏）。X3 的实际出现位置（主图位/列表卡片）已由
                            //   FASTIMG / M() / adapter-hook 三处专用接管覆盖，无需无差别清洗。
                            val applied = profile != null && main != null && applyProductImageTo(main, profile)
                            if (applied) {
                                logChain("I", "POPUP_MAIN_APPLIED main=${main.id} profile=${profile.id} hidden=$hidden")
                            } else {
                                // [修复·弹窗误杀] 档案/图未就绪时不再隐藏主图位、不再空白化弹窗：
                                //   延迟覆盖任务（200/700/1500/2500ms）会重试贴模块图；
                                //   期间放行宿主绘制的图，宁可见 X3 也不让弹窗内容全灭。
                                logChain("I", "POPUP_MAINIMG walk-pass main=${main?.id} profile=${profile?.id} (保留宿主图)")
                            }
                            logChain(
                                "I",
                                "POPUP_MAINIMG views=${all.size} applied=$applied main=${main?.id} hidden=$hidden",
                            )
                        }.onFailure { logChain("E", "POPUP_MAINIMG walk failed: $it") }
                    }
                    listOf(200L, 700L, 1500L, 2500L).forEach { handler.postDelayed(applyOverlay, it) }
                }.onFailure { logChain("E", "POPUP_MAINIMG onResume failed: $it") }
            }
            logChain("I", "POPUP_MAINIMG hooked onResume()")
        }
    }
    /** 当前活跃伪装型号（模块投递弹窗才有；否则回退官方伪装目标 Enco X3）。 */
    /**
     * 弹窗主图使用的档案：优先解析物理设备档案（弹窗进程内缓存的 mac → 注册表/名称），
     * 使主图 = 用户为该物理型号替换的图片；无物理档案时才回退官方伪装目标。
     */
    private fun resolveActivePopupProfile(): DeviceProfile? {
        // 复用物理档案解析链（注册表绑定 → 蓝牙名反查 → active），确保主图 = 该物理型号的用户替换图。
        popupDeviceMac?.let { mac -> resolvePopupBatteryProfile(mac)?.let { return it } }
        // [修复·偶发贴不上] popupDeviceMac 仅在 applyPopupHolderSelection 赋值，
        //   主图接管三入口（M() / onResume walk / FASTIMG）可能早于它 → 解析失败 → 放行宿主 X3 图。
        //   补 activeModuleAddress 回退（连接期已绑定），与电量注入同源，降低「偶发贴不上」。
        activeModuleAddress?.let { mac -> resolvePopupBatteryProfile(mac)?.let { return it } }
        // 修复：不再硬编码回退官方 X3 档案（old: ?: DeviceProfiles.get(OFFICIAL_PROFILE_ID)）。
        //   回退 X3 会让「无档案」场景主动渲染 X3 单图/三图，正是用户反馈的 X3 残留来源。
        //   仅在活跃档案「不是官方伪装目标」时才用它（真实设备即用户自己的型号，如 xiberia.mc05）；
        //   若活跃档案就是 X3（虚拟注入场景），返回 null，由调用方执行「接管失败即彻底屏蔽」。
        return activeProfileId
            ?.takeIf { it != AdapterRegistry.OFFICIAL_PROFILE_ID }
            ?.let { DeviceProfiles.get(it) }
    }
    /**
     * P0-G：发现弹窗「型号图片」覆盖。
     *
     * 宿主渲染链（16.9.1 实读）：
     *   DiscoveryRecycleAdapter.onBindViewHolder(holder=K0, pos)
     *     → DiscoveryDialogViewModel.i(colorId, productId, mac) : CompletableFuture<MelodyResourceDO>
     *     → thenAcceptAsync(J0) → K0.a(MelodyResourceDO, B1, DiscoveryRecycleItemVO)
     *   K0.a 末尾：
     *     if (MelodyResourceDO != null) imageView.d(resource, rootPath, fallbackRes, false)
     *     else                           imageView.setImageResource(fallbackRes)   // ← 伪造型号无资源 → 白卡
     *
     * 因此：hook K0.a()，在系统渲染完成后，用模块自带的「型号图片」
     * （用户图片 > 内置 assets/device_images）覆盖该 ViewHolder 的
     * MelodyCompatImageView(K0.a)，解决弹窗空白。
     */
    private fun hookDiscoveryPopupImage() = safeHook(TAG, "discovery popup image") {
        // ① 主路径：Adapter.onBindViewHolder after 直接覆盖卡片图片。
        //    伪造型号拿不到 MelodyResourceDO → ViewModel.i() 的 future 不完成/异常
        //    → K0.a() 从不被调用（实测无 POPUP_IMG 日志），故必须挂在 Adapter 层。
        val adapter = findClassOrNull(DISCOVERY_RECYCLE_ADAPTER_CLASS) ?: run {
            logChain("E", "POPUP_IMG adapter not found: $DISCOVERY_RECYCLE_ADAPTER_CLASS")
            return@safeHook
        }
        adapter.declaredMethods
            .filter { it.name == "onBindViewHolder" && it.parameterTypes.size == 2 }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    hookAfter(method) {
                        runCatching {
                            val holder = args.getOrNull(0)
                            val pos = args.getOrNull(1) as? Int
                            val list = getObjectField(instance, "d") as? List<*>
                            val vo = list?.let { p -> pos?.let { it1 -> p.getOrNull(it1) } }
                            val imageView = holder?.let { getObjectField(it, "a") as? android.widget.ImageView }
                            val profile = popupImageProfile(vo)
                            logChain(
                                "I",
                                "POPUP_IMG bind pos=$pos listSize=${list?.size} holder=${holder?.javaClass?.simpleName} " +
                                    "iv=${imageView != null} profile=${profile?.id} vo=${vo?.javaClass?.simpleName}",
                            )
                            if (imageView != null && profile != null) {
                                applyImageDrawable(imageView, profile, "popup", vo)
                            } else if (imageView != null) {
                                // 修复：解析不出档案时，必须清空卡片图 —— 否则宿主会保留被重写 productId
                                //   （0x067410 / X3）后加载到的 X3 资源图，即「还是有 X3」。
                                runCatching {
                                    imageView.animate().cancel()
                                    imageView.clearAnimation()
                                    imageView.setImageDrawable(null)
                                    imageView.setBackground(null)
                                    imageView.visibility = View.GONE
                                }
                            }
                        }.onFailure { logChain("E", "POPUP_IMG apply failed: $it") }
                    }
                }.onFailure { modLog("I", "POPUP_IMG hook adapter failed: $it") }
            }

        // ② 备路径：ViewHolder K0.a(MelodyResourceDO, B1, VO) after。
        //    仅当宿主确实拿到资源包（真机型号）时才会走到；作为二次兜底。
        findClassOrNull(DISCOVERY_RECYCLE_HOLDER_CLASS)?.declaredMethods
            ?.filter { it.name == "a" && it.parameterTypes.size == 3 }
            ?.forEach { method ->
                runCatching {
                    method.isAccessible = true
                    hookAfter(method) {
                        runCatching {
                            val imageView = getObjectField(instance, "a") as? android.widget.ImageView
                                ?: return@runCatching
                            val vo = args.getOrNull(2)
                            val profile = popupImageProfile(vo)
                            if (profile != null) {
                                applyImageDrawable(imageView, profile, "popup-holder", vo)
                            } else {
                                // 修复：同样必须清掉宿主拿到的 X3 资源图，绝不保留。
                                runCatching {
                                    imageView.animate().cancel()
                                    imageView.clearAnimation()
                                    imageView.setImageDrawable(null)
                                    imageView.setBackground(null)
                                    imageView.visibility = View.GONE
                                }
                            }
                        }.onFailure { logChain("E", "POPUP_IMG apply failed(holder): $it") }
                    }
                }.onFailure { modLog("I", "POPUP_IMG hook holder failed: $it") }
            }

        logChain("I", "POPUP_IMG hooks installed on $DISCOVERY_RECYCLE_ADAPTER_CLASS + $DISCOVERY_RECYCLE_HOLDER_CLASS")
    }

    /**
     * 弹窗卡片主图位处理（主线程调用）。
     *
     * 模块只使用三图容器：**不再铺单张主图**。旧实现 `setImageDrawable(productDrawable(...))`
     * 会在图片未就绪 / 加载卡顿时闪现整机大图（伪装兜底身份 Enco X3 的 main.png）——即用户反馈的
     * 「连接发现弹窗还是会出现 X3 单图」。此处改为：卡片主图位一律交给三图容器接管；
     * 装配失败则彻底停渲染 + 隐藏（宁缺勿错，绝不回退单图）。
     */
    private fun applyImageDrawable(
        imageView: android.widget.ImageView,
        profile: DeviceProfile,
        tag: String,
        vo: Any?,
    ) {
        replacingProductImage.set(true)
        try {
            // 【单主图改造】弹窗卡片主图位：直接贴模块单张主图（不再装配模块三图容器）。
            //   电量走宿主原生控件（弹窗自带电量格），本函数只负责换图。
            applyProductImageTo(imageView, profile)
        } finally {
            replacingProductImage.set(false)
        }
        imageView.invalidate()
        val type = callMethodOrNull(vo, "getType")
        val pid = callMethodOrNull(vo, "getProductId")
        val key = "$tag#${profile.id}#$type"
        if (loggedProductImageTargets.add(key)) {
            logChain("I", "POPUP_IMG three-device-only tag=$tag profile=${profile.id} productId=$pid type=$type")
        }
    }

    /** 弹窗 VO → 模块 DeviceProfile：优先 MAC 绑定，其次 productId 反查，最后按设备名。 */
    private fun popupImageProfile(vo: Any?): DeviceProfile? {
        if (vo == null) return null
        val mac = callMethodOrNull(vo, "getMacAddress") as? String
        profileForMacOrNull(mac)?.let { return it }
        val name = callMethodOrNull(vo, "getProductName") as? String
        DeviceProfiles.findByName(name)?.let { return it }
        // 修复：不再回退官方 X3 档案（old: return DeviceProfiles.get(OFFICIAL_PROFILE_ID)）。
        //   回退 X3 会让未命中档案的卡片渲染 X3 图。
        //   改为回退「当前活跃设备档案」（真实设备即用户自己的型号，如 xiberia.mc05），
        //   使弹窗卡片显示用户自己的图；仅当活跃档案本身即官方伪装目标时才返回 null（不做覆盖）。
        activeProfileId
            ?.takeIf { it != AdapterRegistry.OFFICIAL_PROFILE_ID }
            ?.let { id -> DeviceProfiles.get(id)?.let { return it } }
        return null
    }

    /**
     * P0-E：弹窗门控诊断。
     *
     * 宿主 `g6.j.p()` = `showDiscoveryActivity`，真正启动分支要求：
     *   T.y(app) == true && j(WhitelistConfigDTO.getFastDiscovery()&1) == true && i(address) == false
     * 否则统一落到 `:cond_17b`（日志文案误导性地写作 "dialog is showing"，与字段实际含义无关）。
     * 另有前置：`T.s && m7.d.d`（诊断 UI）与 `this.q && T.r`（HeyScan）。
     *
     * 通过 hook h/i/j 与路由导航 `ui.base.g.b(Context)`，直接拿到每道门的真实布尔值，
     * 从而把「p() 已调用但无弹窗」精确定位到具体门控，避免盲调参数。
     */
    private fun hookPopupGateDiagnostics() = safeHook(TAG, "popup gate diagnostics") {
        val whitelistDtoClass = findClassOrNull("com.oplus.melody.common.data.WhitelistConfigDTO")

        // 1) h(String)：胶囊是否正在显示（true → p() 直接 return）
        findMethodOrNull(SERVER_IMPL_CLASS, "h", String::class.java)?.let { m ->
            hookAfter(m) {
                runCatching { logChain("I", "POPUP_GATE_H addr=${args.getOrNull(0)} result=$result") }
            }
        }

        // 2) i(String)：discovery 是否已在显示（true → p() 直接 return）。
        //    [真值矫正] 宿主闩锁会卡死：弹窗 Activity 在 :fg 进程，被 force-stop/重启后主进程
        //    收不到销毁回调 → i() 残留 true → 后续 p() 永久落到 :cond_17b 不弹。
        //    以 :fg 侧生命周期同步来的 popupRealShowing（唯一真源）为准：
        //    只有弹窗真的活着时才允许 i()=true 拦住 p()，否则强制 false 放行。
        findMethodOrNull(SERVER_IMPL_CLASS, "i", String::class.java)?.let { m ->
            hookAfter(m) {
                runCatching {
                    val host = result as? Boolean ?: false
                    val corrected = popupRealShowing
                    if (host != corrected) {
                        logChain(
                            "I",
                            "POPUP_GATE_I_CORRECT addr=${args.getOrNull(0)} host=$host real=$corrected",
                        )
                    } else {
                        logChain("I", "POPUP_GATE_I addr=${args.getOrNull(0)} result=$host real=$corrected")
                    }
                    result = corrected
                }
            }
        }

        // 3) j(WhitelistConfigDTO)：白名单 fastDiscovery 位（false → p() 不启动）。
        // 实测：本机对伪造 productId 067410 的白名单条目 fastDiscovery 位为 0，
        // 导致 p() 在 `if-eqz v5, :cond_17b` 直接 return-void。此处强制放行并转储 DTO 明细。
        if (whitelistDtoClass != null) {
            findMethodOrNull(SERVER_IMPL_CLASS, "j", whitelistDtoClass)?.let { m ->
                hookAfter(m) {
                    runCatching {
                        val dto = args.getOrNull(0)
                        val detail = dto?.let { d ->
                            val id = callMethodOrNull(d, "getId") ?: getObjectField(d, "id")
                            val pid = callMethodOrNull(d, "getProductId") ?: getObjectField(d, "productId")
                            val name = callMethodOrNull(d, "getName") ?: getObjectField(d, "name")
                            val type = callMethodOrNull(d, "getType") ?: getObjectField(d, "type")
                            val fn = callMethodOrNull(d, "getFunction") ?: getObjectField(d, "function")
                            val fast = fn?.let { callMethodOrNull(it, "getFastDiscovery") ?: getObjectField(it, "fastDiscovery") }
                            "id=$id pid=$pid name=$name type=$type fn=$fn fast=$fast"
                        } ?: "dto=null"
                        logChain("I", "POPUP_GATE_J raw=$result detail=$detail")
                        result = true
                        logChain("I", "POPUP_GATE_J_FORCED result=true")
                    }
                }
            }
        } else {
            logChain("E", "POPUP_GATE_J WhitelistConfigDTO not found")
        }

        // 4) 路由导航 ui.base.g.b(Context)：p() 真正发起 startActivity 的入口
        findMethodOrNull(ROUTER_CLASS, "b", Context::class.java)?.let { m ->
            var dumped = false
            hookBefore(m) {
                runCatching {
                    if (!dumped) {
                        dumped = true
                        val fields = instance?.javaClass?.declaredFields?.joinToString(",") { it.name } ?: "none"
                        logChain("I", "POPUP_ROUTE_FIELDS $fields")
                    }
                    val route = getObjectField(instance, "e") ?: getObjectField(instance, "f")
                    logChain("I", "POPUP_ROUTE_ENTER route=$route pc=${instance?.javaClass?.name}")
                }
            }
            hookAfter(m) {
                runCatching { logChain("I", "POPUP_ROUTE_DONE ok") }
            }
        } ?: logChain("E", "POPUP_ROUTE_B ui.base.g.b(Context) not found")
    }

    /** $16：改写 g6.j.p() 入参中的 productId / color（仅对本模块识别的设备生效）。 */
    private fun hookDiscoveryPopupRewrite() {
        val p = findDiscoveryPopupMethod(findClassOrNull(SERVER_IMPL_CLASS)) ?: run {
            modLog("I", "POPUP: p() not found, skip rewrite")
            return
        }
        hookBefore(p) {
            runCatching {
                if (args.size < 5) return@runCatching
                val mac = args[0] as? String ?: return@runCatching
                val profile = profileForMacOrNull(mac) ?: return@runCatching
                // 单一真源：固定 6 位大写 hex（0x067410 -> "067410"）。
                // 禁止 Integer.toHexString（0x067410 -> "67410" 少位，被下游 parseInt(id,16) 解析成错误型号）。
                val hex = AdapterRegistry.spoofIdHexFor(profile)
                setArg(2, hex)
                setArg(4, POPUP_DONOR_COLOR)
                modLog(
                    "[com.melody.melodyplus] POPUP: p() rewrite mac=$mac action=${args[1]} productId=$hex color=$POPUP_DONOR_COLOR"
                )
            }
        }
    }

    /** $17：发现路由 Intent 注入会话标记（HeyRouter 发起，弹窗 Activity 据此识别）。 */
    private fun hookDiscoveryRouterSession() {
        findMethodOrNull(ROUTER_CLASS, "b", Context::class.java)?.let { method ->
            hookBefore(method) {
                runCatching {
                    // 诊断：dump 路由参数（发现弹窗空白的根因取证，纯只读）。
                    val routePath = getObjectField(instance, "a") as? String
                    if (routePath?.contains("discovery") == true) {
                        val routeArgs = getObjectField(instance, "c")
                        val bIntent0 = getObjectField(instance, "b") as? Intent
                        val keys0 = bIntent0?.extras?.keySet()?.joinToString(",") ?: "null"
                        logChain(
                            "I",
                            "ROUTE_DEBUG path=$routePath args=$routeArgs intentExtras=[$keys0]"
                        )
                    }
                    val intent = getObjectField(instance, "b") as? Intent ?: return@runCatching
                    intent.putExtra(POPUP_SESSION_EXTRA, popupSessionId)
                }
            }
        } ?: modLog("I", "POPUP: $ROUTER_CLASS.b(Context) not found")
    }

    /** $18/$19/$20/$21：弹窗 Activity 生命周期桥。 */
    private fun hookDiscoveryDialogLifecycle() {
        findClassOrNull(DISCOVERY_DIALOG_ACTIVITY) ?: run {
            modLog("I", "POPUP: $DISCOVERY_DIALOG_ACTIVITY not found")
            return
        }
        // $18 onCreate before：跟踪 + 清空 savedInstanceState
        findMethodOrNull(DISCOVERY_DIALOG_ACTIVITY, "onCreate", Bundle::class.java)?.let { method ->
            hookBefore(method) {
                runCatching {
                    val activity = instance ?: return@runCatching
                    attachedDialogActivities.add(activity)
                    // [真值矫正] 弹窗 Create = 真实显示 → 跨进程同步主进程放行闩锁。
                    publishPopupShowing(true)
                    // 诊断：dump 弹窗 Intent extras（白屏根因取证，纯只读）。
                    runCatching {
                        val intent = getObjectField(activity, "mIntent") as? Intent
                            ?: callMethodOrNull(activity, "getIntent") as? Intent
                        val extras = intent?.extras
                        val keys = extras?.keySet()?.joinToString(",") ?: "no-extras"
                        val dump = extras?.keySet()?.joinToString(" | ") { k ->
                            val v = runCatching { extras.get(k) }.getOrNull()
                            "$k=${v?.toString()?.take(40)}"
                        } ?: ""
                        logChain("I", "DIALOG_INTENT keys=[$keys] $dump")
                    }
                    if (args.size == 1) setArg(0, null)
                }
            }
        }
        // $19 onResume after：交付确认（recreate 后跳过，避免重复上报）
        findMethodOrNull(DISCOVERY_DIALOG_ACTIVITY, "onResume")?.let { method ->
            hookAfter(method) {
                runCatching {
                    val activity = instance ?: return@runCatching
                    if (recreatedDialogActivities.contains(activity)) return@runCatching
                    logChain("I", "DISCOVERY_DIALOG_ON_RESUME activity=${activity.javaClass.name}")
                }
            }
        }
        // $20 Activity.onNewIntent after：本弹窗复用实例时 setIntent + recreate
        findMethodOrNull("android.app.Activity", "onNewIntent", Intent::class.java)?.let { method ->
            hookAfter(method) {
                runCatching {
                    val activity = instance as? Activity ?: return@runCatching
                    if (activity.javaClass.name != DISCOVERY_DIALOG_ACTIVITY) return@runCatching
                    val intent = args.firstOrNull() as? Intent ?: return@runCatching
                    activity.intent = intent
                    recreatedDialogActivities.add(activity)
                    callMethodOrNull(activity, "recreate")
                }
            }
        }
        // $21 onDestroy after：清理跟踪
        findMethodOrNull(DISCOVERY_DIALOG_ACTIVITY, "onDestroy")?.let { method ->
            hookAfter(method) {
                runCatching {
                    val activity = instance ?: return@runCatching
                    attachedDialogActivities.remove(activity)
                    recreatedDialogActivities.remove(activity)
                    // [真值矫正] 弹窗销毁 = 真实不再显示 → 通知主进程清闩锁，允许下次重连再弹。
                    publishPopupShowing(false)
                }
            }
        }
    }

    /**
     * [真值矫正] 发布「弹窗真实存活」到 melody 包内（显式 setPackage）。
     * :fg 侧调用，主进程接收后写入 popupRealShowing 用于矫正 g6.j.i() 闩锁。
     */
    private fun publishPopupShowing(showing: Boolean) {
        runCatching {
            val ctx = currentContext() ?: return@runCatching
            val i = Intent(BluetoothPopupContract.ACTION_POPUP_SHOWING).apply {
                setPackage(ctx.packageName)
                putExtra(BluetoothPopupContract.EXTRA_POPUP_SHOWING, showing)
            }
            ctx.sendBroadcast(i)
            logChain("I", "POPUP_SHOWING_PUBLISH showing=$showing process=${currentProcessName(ctx)}")
        }.onFailure { logChain("E", "POPUP_SHOWING_PUBLISH failed: $it") }
    }
    /** $22：对 DiscoveryDialogViewModel.d() 返回的 holder(H0) 注入型号/颜色/电量。 */
    private fun hookDiscoveryDialogViewModel() {
        val vmClass = findClassOrNull(DISCOVERY_DIALOG_VIEW_MODEL) ?: run {
            modLog("I", "POPUP: $DISCOVERY_DIALOG_VIEW_MODEL not found")
            return
        }
        vmClass.declaredMethods
            .filter { it.name == "d" && it.parameterTypes.size == 3 }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    hookAfter(method) {
                        runCatching {
                            val mac = args.getOrNull(0) as? String
                            applyPopupHolderSelection(result, mac)
                        }
                    }
                }.onFailure { modLog("I", "POPUP: hook ViewModel.d failed: $it") }
            }
    }

    /** 对弹窗 holder(H0) 注入伪装型号 / 配色 / 电量（setter 名对齐 16.9.1 H0）。 */
    private fun applyPopupHolderSelection(holder: Any?, mac: String?) {
        if (holder == null) return
        val resolvedMac = mac ?: getObjectField(holder, "mId") as? String
        resolvedMac?.let { popupDeviceMac = it }
        profileForMacOrNull(resolvedMac)?.let { profile ->
            // H0.setProductId 承载的是 melody 侧 productId（6 位大写 hex 字符串，非型号名）。
            // 单一真源：禁止 Integer.toHexString（会把 0x067410 变成少位 "67410"）。
            val hex = AdapterRegistry.spoofIdHexFor(profile)
            callMethodOrNull(holder, "setProductId", hex)
            callMethodOrNull(holder, "setProductColorId", POPUP_DONOR_COLOR)
        }
        // 电量不在此注入：图片由模块贴到宿主原生 ImageView，电量交宿主原生控件自行渲染；
        //   不往宿主 holder 写 setLeftBattery/setRightBattery/setBoxBattery，避免叠加一份灰字电量。
        //   型号/配色注入（setProductId/setProductColorId）保留，供档案解析与图资源匹配。
    }
    /**
     * 发现弹窗（单设备大图版）三路电量覆盖。
     *
     * 定位（16.9.1）：弹窗 ViewHolder `com.oplus.melody.app.discovery.b` 的
     *   `g(IZIZIZ)V` 是「三路电量 → 原生槽」的唯一渲染入口：
     *     params = (left, leftCharging, box, boxCharging, right, rightCharging)
     *     内部 `h(IZZIZZIZZ)` 把三路喂给槽 0x7f090363=left / 0x7f090362=center(仓) / 0x7f090365=right；
     *     `if (value<=0 || value>100) → 不显示该槽`（center 无值即被隐藏）。
     *
     * 为何在入参处注入而非改 H0 / DTO：
     *   ① 调用方 `discovery.f` 从 H0 读三路；H0 由 `DiscoveryDialogViewModel.d()` 从 EarphoneDTO 拷贝，
     *      拷贝受 isSupportSpp/连接态等分支限制，伪造型号下 box 走不到有效值。
     *   ② 直接改渲染入参 → 无视上游全部分支，模块真实电量稳定落到三个原生槽，不叠加任何自绘控件。
     */
    private fun hookDiscoveryPopupBattery() = safeHook(TAG, "discovery popup battery") {
        val holderClass = findClassOrNull(DISCOVERY_RECYCLE_HOLDER_VIEW_CLASS) ?: run {
            modLog("I", "POPUP_BATT: $DISCOVERY_RECYCLE_HOLDER_VIEW_CLASS not found")
            return@safeHook
        }
        val intType = Int::class.javaPrimitiveType!!
        val boolType = Boolean::class.javaPrimitiveType!!
        // descriptor = h(IZZIZZIZZ)V → (int,boolean,boolean)×3 = (left,leftChg,leftValid, box,boxChg,boxValid, right,rightChg,rightValid)
        val expected = arrayOf(
            intType, boolType, boolType,
            intType, boolType, boolType,
            intType, boolType, boolType,
        )
        val method = holderClass.declaredMethods.firstOrNull {
            it.name == "h" && it.parameterTypes.contentEquals(expected)
        } ?: run {
            val available = holderClass.declaredMethods.filter { it.name == "h" }
                .joinToString { it.parameterTypes.joinToString(",", prefix = "h(", postfix = ")") }
            modLog("I", "POPUP_BATT: h(IZZIZZIZZ) not found; candidates=$available")
            return@safeHook
        }
        method.isAccessible = true
        hookBefore(method) {
            runCatching {
                val address = popupDeviceMac ?: activeModuleAddress
                val normalizedAddr = address?.let {
                    runCatching { DeviceRegistryStore.normalizeAddress(it) }.getOrNull()
                } ?: address
                // 主路径：连接时刻（handleDiscoveryPopupIntent）已主动建会话补采，此处缓存通常已就绪 → 直接注入。
                // 兜底：若此刻仍缺失（首次渲染早于补采返回 / 采集中途），requestPopupBatterySync 会后台补采
                //   并重放 h(...)；同时 armPopupBatteryFallback 挂 0.4/1.2/2.5s 三次重试，确保首次连接
                //   「偶发某一路查不到」也能在弹窗存活期内自动补齐。
                if (normalizedAddr != null) {
                    val holder = instance
                    if (holder != null) {
                        // [修复·:fg 重试链失效] 无条件记录 holder。
                        //   旧实现只在 requestPopupBatterySync 内部赋值 lastPopupBatteryHolder，而 :fg 进程
                        //   （弹窗进程）在 requestPopupBatterySync 首行 isPopupProcessNow()==true 即 return，
                        //   导致 lastPopupBatteryHolder 恒为 null → armPopupBatteryFallback / PRIME 完成后的
                        //   replay 全部拿不到 holder → 一旦某次渲染漏了仓电量，之后无人补，仓位永久消失。
                        lastPopupBatteryHolder = java.lang.ref.WeakReference(holder)
                        requestPopupBatterySync(
                            normalizedAddr, holder, method,
                            force = currentBatteryState(normalizedAddr) == null,
                        )
                        armPopupBatteryFallback(normalizedAddr, holder, method)
                    }
                }
                // [修复·渲染早于补采] 实时快照缺槽（:fg 渲染早于主进程补采写回）时，
                //   用最近一次成功渲染的快照按槽补齐（实时值优先，仅补 null 槽），避免渲染退化。
                val rawState = currentBatteryState(normalizedAddr)
                val lastGood = normalizedAddr?.let { popupBatteryLastGood[it] }
                val state = mergeBattery(rawState, lastGood) ?: return@runCatching
                // 记录「最近成功快照」：三路齐全时更新，供后续残缺渲染兜底。
                if (normalizedAddr != null && state.isComplete()) popupBatteryLastGood[normalizedAddr] = state
                // [修复·首连单值冒充] 双耳型号下 single 为退化读数，不得兜底 left/right。
                val ignoreSingle = shouldIgnoreSingle()
                val left = if (ignoreSingle) state.left else (state.left ?: state.single)
                val right = if (ignoreSingle) state.right else (state.right ?: state.single)
                val box = state.caseBattery
                if (left == null && right == null && box == null) return@runCatching
                // 槽序铁证：host h(p1..p3)→k(槽0x7f090363)=屏幕左、h(p4..p6)→k(槽0x7f090362)=屏幕中、h(p7..p9)→k(槽0x7f090365)=屏幕右。
                // 模块 MC05 自绘主图从左到右 = 「左耳 | 右耳 | 仓」，故数值须按图序注入：
                //   组1(屏幕左)=左耳、组2(屏幕中)=右耳、组3(屏幕右)=仓。
                // 缺值槽保持宿主原值（0/false → 宿主自动隐藏该槽）。
                // [修复·槽序回落] 宿主原生槽序为「组1=left / 组2=box / 组3=right」，
                //   旧实现只对「有值槽」setArg → 缺值槽保留宿主原值 → 该槽显示的是宿主对位
                //   数据（如缺 box 时屏幕右槽残留宿主 right 值），表现为「偶发变回宿主原顺序」。
                //   现改为三槽**恒写**：有值按模块图序（左耳|右耳|仓）写入；缺值显式置无效（0/false），
                //   宿主按 value<=0 自动隐藏该槽，槽序永不回落。
                val leftEar = left
                val rightEar = right
                // 组1（屏幕左 = 图上「左耳」）
                setArg(0, leftEar ?: 0)
                setArg(1, leftEar != null && state.leftCharging == true)
                setArg(2, leftEar != null)
                // 组2（屏幕中 = 图上「右耳」）
                setArg(3, rightEar ?: 0)
                setArg(4, rightEar != null && state.rightCharging == true)
                setArg(5, rightEar != null)
                // 组3（屏幕右 = 图上「仓」）
                setArg(6, box ?: 0)
                setArg(7, box != null && state.caseCharging == true)
                setArg(8, box != null)
                val logKey = "$leftEar/$rightEar/$box/${rawState?.let { "${it.left}/${it.right}/${it.caseBattery}" } ?: "null"}"
                val addrKey = address ?: "?"
                // [止血·降噪] 同值不重复打印（原实现 60/s 刷屏且淹没其它诊断日志）。
                if (popupBattLogLast[addrKey] != logKey) {
                    popupBattLogLast[addrKey] = logKey
                    logChain(
                        "I",
                        "POPUP_BATT inject addr=$address L=$leftEar R=$rightEar box=$box " +
                            "chg=${state.leftCharging}/${state.rightCharging}/${state.caseCharging} " +
                            "raw=${rawState?.let { "${it.left}/${it.right}/${it.caseBattery}" } ?: "null"} " +
                            "lg=${lastGood?.let { "${it.left}/${it.right}/${it.caseBattery}" } ?: "null"}",
                    )
                }
            }.onFailure { logChain("E", "POPUP_BATT inject failed: $it") }
        }
        // 运行时对齐：宿主把三槽等距平铺，未与三合一图案的「左耳 / 右耳 / 仓」分段对齐。
        // 在 h() 执行后发布一个延迟任务，待布局完成后读取图片 View 与三槽的真实屏幕坐标，
        // 用 translationX 把每格平移到对应图案中心正下方（图片由 assets 提供，三段中心占比固定）。
        hookAfter(method) {
                val holder = instance ?: return@hookAfter
                // [止血·防雪崩] h() 高频回调（≈60/s）时只允许同一 holder 建一条对齐链，
                //   否则 5 秒 lock 链会叠加数千条，主线程消息队列雪崩 → 后续弹窗 Activity 起不来。
                // [修复·hashCode 复用] 原用 System.identityHashCode(holder) 作 key：int 值在旧 holder
                //   被 GC 后可能被新 holder 复用 → 新弹窗的对齐链被误判为「已武装」而跳过（偶发漏对齐）。
                //   现以对象身份为 key 的弱引用映射：holder 回收后条目自动消失，无需手工清理。
                val nowMs = System.currentTimeMillis()
                val armedAt = popupAlignArmedAt[holder]
                if (armedAt != null && nowMs - armedAt < ALIGN_ARM_WINDOW_MS) {
                    return@hookAfter
                }
                popupAlignArmedAt[holder] = nowMs
                // [2026-10-09 根因修复·锚点错位]
                //   旧实现用 holder.e(0x7f09035e = melody_app_discovery_animation_iv，副图/动画图)当主图锚点，
                //   其 contentW 仅 ~520px → 主图三段中心全部算错 → 三槽被推到一块、互相重叠。
                //   主图真身 = 0x7f090377(melody_app_discovery_image_view)。
                //   从 holder 所在视图树根查找主图；取不到再回退 holder.e。
                val imgView = run {
                    var root: android.view.View? = getObjectField(holder, "d") as? android.view.View
                    while (root?.parent is android.view.View) root = root.parent as android.view.View
                    (root?.findViewById<android.view.View>(POPUP_MAIN_IMAGE_ID) as? ImageView)
                        ?: (getObjectField(holder, "e") as? ImageView)
                } ?: return@hookAfter
            // 布局/测量未完成时槽宽可能为 0；带重试直到三槽都拿到有效宽度（或 8 次后放弃）。
            fun attempt(tryIndex: Int) {
                if (tryIndex >= 8) return
                val done = runCatching { alignPopupBatterySlots(holder, imgView, tryIndex) }
                    .onFailure { logChain("E", "POPUP_ALIGN failed: $it") }
                    .getOrDefault(true)
                if (!done) imgView.postDelayed({ attempt(tryIndex + 1) }, 90L)
            }
            imgView.postDelayed({ attempt(0) }, 120L)
            // [修复·稳定锁定] 平移可能在 apply 后被宿主重布局/重绑抹掉（h() 每帧回调会重置槽的
            //   translationX），表现为「首次 OK、再次连接某两槽重叠且画面跳动」。
            //   此处按 250ms 复测，一旦检测到某槽实际中心偏离目标（drift>2px）就重新对齐，
            //   持续 5 秒（弹窗首屏存活窗口），之后停止，不再干预用户停留后的静置态。
            val lockEndAt = System.currentTimeMillis() + 5000L
            val lockRunnable = object : Runnable {
                override fun run() {
                    val alive = runCatching { getObjectField(holder, "d") as? android.view.View }.getOrNull()
                    if (alive == null || imgView.width <= 0) return
                    runCatching { alignPopupBatterySlots(holder, imgView, -1) }
                    if (System.currentTimeMillis() < lockEndAt) {
                        imgView.postDelayed(this, 250L)
                    } else {
                        logChain("I", "POPUP_ALIGN_LOCK done")
                    }
                }
            }
            imgView.postDelayed(lockRunnable, 400L)
        }
        logChain("I", "POPUP_BATT hooked discovery.b.h(IZZIZZIZZ)")
    }
    /**
     * 运行时把发现弹窗的三个电量槽平移到三合一图案（左耳 / 右耳 / 充电仓）正下方。
     *
     * 宿主把三个槽等距平铺在图片下方，而图是固定资源的三合一合成图，
     * 三段图案横心占比为常量。图片 View 用 FIT_CENTER 显示，
     * 故按图片真实宽高比即可反推绘制矩形，再据此算出每段图案中心的屏幕 x，
     * 把对应槽的 translationX 设成 (目标中心 - 槽当前中心)。
     */
    /**
     * 双耳型号（catalog batteryType=PAIR 或 leftAndRightImg）下是否忽略 single 退化值。
     *
     * [修复·首连单值冒充] 首连时 SPP 未就绪，refresh 常返回「只有 single」的退化快照。
     *   旧实现用 `left ?: single` 兜底，把 1 个值同时冒充左右耳（只显示一个耳机电量）。
     *   现对双耳型号禁用该兜底：读不到就置无效（宿主隐藏该槽），等 fallback 补齐三态。
     */
    private fun shouldIgnoreSingle(): Boolean = runCatching {
        val fromPid = XiberiaProductCatalog.byProductId(activePanelProductId)
        val fromProfile = (popupDeviceMac ?: activeModuleAddress)
            ?.let { resolvePopupBatteryProfile(it)?.modelId }
            ?.let { XiberiaProductCatalog.byModel(it) }
        val caps = fromPid ?: fromProfile
        caps != null &&
            (caps.batteryType == XiberiaProductCatalog.BatteryType.PAIR || caps.leftAndRightImg)
    }.getOrDefault(false)

    private fun alignPopupBatterySlots(holder: Any, imgView: ImageView, tryIndex: Int): Boolean {
        val imgW = imgView.width
        val imgH = imgView.height
        if (imgW <= 0 || imgH <= 0) return false
        val slots = getObjectField(holder, "a") as? SparseArray<*> ?: return true
        val imgLoc = IntArray(2)
        imgView.getLocationOnScreen(imgLoc)

        // 用 imageMatrix 把「图片原始像素矩形」映射到 imgView 内的实际绘制矩形，
        // 再叠加 imgView 屏幕左边界 → 得到内容区在屏幕上的真实 left/宽度。
        // 这样不依赖 scaleType 假设（FIT_CENTER / CENTER_CROP 都能算对）。
        var contentLeft = Float.NaN
        var contentW = Float.NaN
        val drawable = imgView.drawable
        if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            val rect = android.graphics.RectF(
                0f, 0f,
                drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat(),
            )
            imgView.imageMatrix.mapRect(rect)
            contentLeft = imgLoc[0] + rect.left
            contentW = rect.width()
        }
        if (contentLeft.isNaN() || contentW <= 0f || contentW > 100000f) {
            // 兜底：按 FIT_CENTER 与内嵌三合一图原始比例反推。
            val srcRatio = POPUP_MAIN_IMAGE_WIDTH / POPUP_MAIN_IMAGE_HEIGHT
            val viewRatio = imgW.toFloat() / imgH.toFloat()
            val w = if (viewRatio > srcRatio) imgH * srcRatio else imgW.toFloat()
            contentW = w
            contentLeft = imgLoc[0] + (imgW - w) / 2f
        }

        // [槽序·固定映射] 宿主槽 id ↔ 语义为固定绑定，资源名铁证：
        //   0x7f090363 = melody_app_discovery_battery_left   → 屏幕左
        //   0x7f090362 = melody_app_discovery_battery_center → 屏幕中
        //   0x7f090365 = melody_app_discovery_battery_right  → 屏幕右
        // 宿主 h() smali 组序：组1(p1..p3)→k(363)、组2(p4..p6)→k(362)、组3(p7..p9)→k(365)。
        // 模块自绘主图三段从左到右 = 左耳 | 右耳 | 仓，故槽→图段固定映射：
        //   363→「左耳」、362→「右耳」、365→「仓」。
        // 缺槽时宿主自动隐藏该槽，其余槽各归其位；**不得改用「按屏幕 x 排序动态分配」**——
        //   过渡帧（部分槽宽为 0）会把「仓」排到最左，正是「顺序变回宿主原样」的复现根因。
        // 三槽各叠加横向微调 POPUP_SLOT_NUDGE_*（左耳/右耳略左、仓略右）。
        val mapping = listOf(
            Triple(0x7f090363, POPUP_SEGMENT_CENTER_LEFT + POPUP_SLOT_NUDGE_LEFT, "L"),
            Triple(0x7f090362, POPUP_SEGMENT_CENTER_RIGHT + POPUP_SLOT_NUDGE_RIGHT, "R"),
            Triple(0x7f090365, POPUP_SEGMENT_CENTER_CASE + POPUP_SLOT_NUDGE_CASE, "B"),
        )
        val sb = StringBuilder()
        var allReady = true
        for ((id, frac, tag) in mapping) {
            val slot = slots.get(id)
            if (slot == null) {
                allReady = false
                sb.append(" id=").append(Integer.toHexString(id)).append(" slot=null")
                continue
            }
            val v = getObjectField(slot, "a") as? View
            if (v == null || v.width <= 0) {
                allReady = false
                sb.append(" id=").append(Integer.toHexString(id)).append(" w=").append(v?.width)
                continue
            }
            // [取证·平移是否被重置] 清零前先测一次真实位置：若它 ≈ 上轮 target，说明平移被保留；
            //   若它 ≈ 基础位置，说明宿主已把 translationX 重置（这就是"算对却仍重叠"的根因）。
            val preLoc = IntArray(2)
            v.getLocationOnScreen(preLoc)
            val preCenter = preLoc[0] + v.width / 2f
            // 先清掉旧平移再量布局基准位置（避免重复回调累积偏移）。
            v.translationX = 0f
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val curCenter = loc[0] + v.width / 2f
            val targetX = contentLeft + contentW * frac
            val dx = targetX - curCenter
            v.translationX = dx
            sb.append(" id=").append(Integer.toHexString(id)).append(tag)
                .append(" vh=").append(Integer.toHexString(System.identityHashCode(v)))
                .append(" pre=").append(preCenter.toInt())
                .append(" target=").append(targetX.toInt())
                .append(" cur=").append(curCenter.toInt())
                .append(" dx=").append(dx.toInt())
                .append(" txt=").append(
                    runCatching {
                        (getObjectField(slot, "d") as? android.widget.TextView)
                            ?.text?.toString() ?: "?"
                    }.getOrDefault("err"),
                )
        }
        logChain(
            "I",
            "POPUP_ALIGN try=$tryIndex contentLeft=" + contentLeft.toInt() +
                " contentW=" + contentW.toInt() + " imgLoc=(" + imgLoc[0] + "," + imgLoc[1] + ")" + sb,
        )
        return allReady
    }

    // 弹窗 holder 电量注入（injectPopupBattery）与弹窗进程自建会话补采
    // （schedulePopupBatteryFetch）已移除：电量统一交宿主原生控件渲染。
    // 但「宿主原生控件渲染」需要缓存里先有值——弹窗先于 SPP 会话/详情页时缓存恒空，
    //   故新增下方 requestPopupBatterySync：弹窗发现时主动建会话补采并就地重放渲染。

    /** 最近一次弹窗三路电量 ViewHolder（WeakReference）；补采成功后据此在主线程重放 h(...)。 */
    private var lastPopupBatteryHolder = java.lang.ref.WeakReference<Any>(null)

    /** 弹窗电量补采在途地址集合（去重：避免每次 h() 回调都触发一次建会话）。 */
    private val popupBatteryInFlight = ConcurrentHashMap.newKeySet<String>()

    /** 每地址最近一次弹窗电量主动补采时间戳（时间节流，防止每帧重采）。 */
    private val popupBatteryPrimedAt = ConcurrentHashMap<String, Long>()

    /** 弹窗电量「连接即查」补采时间节流窗口。
     *
     * 首次连接（该地址从未采过）不受此窗口限制，立即采；此后同一地址的重复触发
     * 在窗口内合并为一次，避免 h() 每帧回调都建会话。
     */
    private const val POPUP_BATTERY_PRIME_MIN_INTERVAL_MS = 15_000L

    /** 「连接即查」单次查询的最大尝试次数（会话刚建立时首查可能落空）。 */
    private const val PRIME_QUERY_MAX_ATTEMPTS = 3

    /** 「连接即查」两次尝试之间的等待（毫秒）。 */
    private const val PRIME_QUERY_RETRY_DELAY_MS = 600L

    /** 弹窗存活期内兜底重试的延时序列（首次连接「偶发某一路查不到」时自动补齐）。 */
    private val POPUP_BATTERY_FALLBACK_DELAYS = longArrayOf(400L, 1200L, 2500L)

    /** 已排入兜底重试链的地址（同址只保留一条链，防叠加）。 */
    private val popupBatteryFallbackArmed = ConcurrentHashMap.newKeySet<String>()

    /**
     * [止血·防雪崩] 对齐链「已武装」时刻表（key = holder 对象身份，弱引用）。
     *
     * 宿主 `h()` 在高频刷新循环里被反复调用（日志实测 ≈60 次/秒）。旧实现每次调用都
     * post 一条 attempt 链 + 一条 5 秒 lock 链 → 5 秒内累积数千条 runnable，主线程
     * 消息队列涨到 5w+ 条 → DiscoveryDialogActivity 起不来（有 show 日志、无 onResume）
     * → 表现为「只有第一次弹窗，第二次以后弹窗不出现」。
     * 现按 holder 去重：同一 holder 在 ALIGN_ARM_WINDOW_MS 内只建一条链。
     */
    /** [降噪] 白名单伪造日志已打印过的 (方法名|设备名) 集合，避免宿主轮询重复刷屏。 */
    private val mpWhitelistLoggedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val popupAlignArmedAt: MutableMap<Any, Long> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Long>())

    /** 同一 holder 对齐链的最小重建间隔（覆盖单条 lock 链的 5 秒存活窗）。 */
    private const val ALIGN_ARM_WINDOW_MS = 5_000L

    /** [止血·日志降噪] 最近一次 inject 日志内容（同值不重复打印，避免 60/s 刷屏）。 */
    private val popupBattLogLast = ConcurrentHashMap<String, String>()

    /**
     * 「连接即查」主动电量补采（弹窗渲染入口 + 连接事件共用）。
     *
     * 对齐官方 App 语义：**连接时刻主动查询电量并注入**，而不是「先读缓存、缓存为空才补」。
     * - 首次连接（该地址从未采过）→ 立即建 SPP 会话查询三路电量；
     * - 已有完整缓存且非 force → 直接返回（由 hookBefore 同步注入）；
     * - 缓存不完整 / force=true → 重建会话查询并重放 h(...) 刷新三槽；
     * - 时间节流 [POPUP_BATTERY_PRIME_MIN_INTERVAL_MS] 抑制每帧重复建会话。
     */
    private fun requestPopupBatterySync(
        address: String,
        holder: Any,
        method: Method,
        force: Boolean = false,
    ) {
        // [SPP 单所有者] :fg 不建链，电量由主进程共享落盘 + 广播推送。
        if (isPopupProcessNow()) return
        val normalized = runCatching { DeviceRegistryStore.normalizeAddress(address) }.getOrNull() ?: address
        val now = System.currentTimeMillis()
        val lastPrimed = popupBatteryPrimedAt[normalized] ?: 0L
        // 时间节流：窗口内且非 force 直接跳过（首次连接 lastPrimed=0 → 必然放行，立即采）。
        if (!force && lastPrimed != 0L && now - lastPrimed < POPUP_BATTERY_PRIME_MIN_INTERVAL_MS) return
        // 已有完整缓存且非 force：无需再采（hookBefore 会用缓存同步注入）。
        if (!force && currentBatteryState(normalized)?.isComplete() == true) return
        // 在途去重：同一地址同一时刻只允许一次补采。
        if (!popupBatteryInFlight.add(normalized)) return
        popupBatteryPrimedAt[normalized] = now
        val profile = resolvePopupBatteryProfile(normalized)
        val context = currentContext() ?: currentApplication()
        if (profile == null || context == null) {
            logChain("I", "POPUP_BATTERY_SKIP addr=$normalized profile=${profile?.id} ctx=${context != null}")
            popupBatteryInFlight.remove(normalized)
            return
        }
        lastPopupBatteryHolder = java.lang.ref.WeakReference(holder)
        val appContext = context.applicationContext ?: context
        scope.launch {
            logChain("I", "POPUP_BATTERY_FETCH addr=$normalized profile=${profile.id} force=$force")
            // 连接即查：直接建/复用会话读取（refresh 内部已含 connect，等价官方「连接即查电量」）。
            runCatching { HeadsetSessionManager.refresh(appContext, normalized, profile.id)?.battery }
                .onSuccess { fetched ->
                    if (fetched != null && fetched.hasAnyLevel()) {
                        // [首连实时·去缓存] 实时读取结果直接覆盖，不与旧缓存合并。
                        detailBatteryCache[normalized] = fetched
                        publishBatteryShare(appContext, normalized, fetched)
                        logChain(
                            "I",
                            "POPUP_BATTERY_FETCHED addr=$normalized profile=${profile.id} " +
                                "left=${fetched.left} right=${fetched.right} box=${fetched.caseBattery} " +
                                "single=${fetched.single}",
                        )
                        postToUi(appContext) {
                            val h = lastPopupBatteryHolder.get()
                            if (h != null && h.javaClass == method.declaringClass) {
                                runCatching { replayPopupBattery(h, method, currentBatteryState(normalized) ?: fetched) }
                                    .onFailure { logChain("E", "POPUP_BATTERY_REPLAY failed: $it") }
                            }
                        }
                        // 补采即视为「连接态已激活」：接着挂 5 分钟常驻轮询（兼作 SPP 心跳）。
                        runCatching { scheduleBatteryPolling(appContext, normalized, profile) }
                    } else {
                        logChain("E", "POPUP_BATTERY_EMPTY addr=$normalized profile=${profile.id}")
                    }
                }
                .onFailure { logChain("E", "POPUP_BATTERY_FAILED addr=$normalized err=$it") }
            popupBatteryInFlight.remove(normalized)
        }
    }

    /**
     * 弹窗存活期内的电量兜底重试链。
     *
     * 首次连接时 SPP 会话刚建立，第一条查询命令有概率落空（对端未就绪 / 缓冲区竞争），
     * 表现为「某一路电量查不到」。这里在弹窗渲染后按 [POPUP_BATTERY_FALLBACK_DELAYS] 多次重试：
     * 每次检查当前缓存，仍不完整就重新发起一次**强制**补采并重放三槽，直至补齐或弹窗消失。
     */
    private fun armPopupBatteryFallback(address: String, holder: Any, method: Method) {
        val normalized = runCatching { DeviceRegistryStore.normalizeAddress(address) }.getOrNull() ?: address
        if (!popupBatteryFallbackArmed.add(normalized)) return
        val handler = Handler(Looper.getMainLooper())
        POPUP_BATTERY_FALLBACK_DELAYS.forEachIndexed { index, delayMs ->
            handler.postDelayed({
                val stillAlive = lastPopupBatteryHolder.get()
                // 弹窗已消失（holder 被回收）→ 终止重试链。
                if (stillAlive == null || stillAlive.javaClass != method.declaringClass) {
                    if (index == POPUP_BATTERY_FALLBACK_DELAYS.lastIndex) popupBatteryFallbackArmed.remove(normalized)
                    return@postDelayed
                }
                val state = currentBatteryState(normalized)
                if (state?.isComplete() == true) {
                    // [修复·渲染早于补采] 状态已补齐，但可能存在某次 h() 渲染早于补采写回、
                    //   导致弹窗 UI 缺槽（典型：仓电量初次渲染时还没拿到 → 槽未 inflate → 永久空缺）。
                    //   此处在弹窗存活期内主动 replay 一次，把 UI 拉回与已补齐状态一致。
                    runCatching { replayPopupBattery(stillAlive, method, state) }
                        .onFailure { logChain("E", "POPUP_BATTERY_FALLBACK_REPLAY failed: $it") }
                    popupBatteryFallbackArmed.remove(normalized)
                    return@postDelayed
                }
                logChain("I", "POPUP_BATTERY_RETRY addr=$normalized try=${index + 1} state=${state?.let { "${it.left}/${it.right}/${it.caseBattery}" }}")
                requestPopupBatterySync(normalized, stillAlive, method, force = true)
                if (index == POPUP_BATTERY_FALLBACK_DELAYS.lastIndex) popupBatteryFallbackArmed.remove(normalized)
            }, delayMs)
        }
    }

    /**
     * 用模块真实电量重放宿主弹窗三槽渲染 `h(IZZIZZIZZ)`。
     *
     * arg 顺序与 [hookDiscoveryPopupBattery] 的 hookBefore 注入保持一致
     * （arg0..2=左耳，arg3..5=右耳，arg6..8=仓），保证「补采重放」与「后续 hookBefore 注入」同源。
     */
    private fun replayPopupBattery(holder: Any, method: Method, state: BatteryState) {
        // [修复·首连单值冒充] 重放路径与 hookBefore 同源，同样按双耳型号禁用 single 兜底。
        val ignoreSingle = shouldIgnoreSingle()
        val leftEar = if (ignoreSingle) state.left else (state.left ?: state.single)
        val rightEar = if (ignoreSingle) state.right else (state.right ?: state.single)
        val box = state.caseBattery
        if (leftEar == null && rightEar == null && box == null) return
        val args = arrayOf<Any?>(
            leftEar ?: 0, state.leftCharging == true, leftEar != null,
            rightEar ?: 0, state.rightCharging == true, rightEar != null,
            box ?: 0, state.caseCharging == true, box != null,
        )
        method.isAccessible = true
        method.invoke(holder, *args)
    }

    /** 周期轮询后刷新发现弹窗原生电量槽（若弹窗在场）：重放最近一次 `h(...)`。 */
    private fun refreshPopupBatteryIfShowing() {
        val holder = lastPopupBatteryHolder.get() ?: return
        val state = currentBatteryState(popupDeviceMac ?: activeModuleAddress) ?: return
        val method = runCatching {
            holder.javaClass.declaredMethods.firstOrNull {
                it.name == "h" && it.parameterTypes.size == 9
            }
        }.getOrNull() ?: return
        runCatching { replayPopupBattery(holder, method, state) }
    }


    /**
     * 弹窗进程内解析「物理设备」档案（用于解析弹窗主图/三图档案）。
     *
     * 关键：绝不能返回伪装捐赠档案（如 `oppo.encox3`，其 adapter=`official` 未注册，
     * 建适配器会抛 IllegalArgumentException）。必须解析出带可用 adapter 的真实设备档案。
     * 优先级：① MAC 注册表绑定 → ② 蓝牙反查设备名 findByName → ③ activeProfileId（仅当 adapter 已注册）。
     */
    private fun resolvePopupBatteryProfile(address: String): DeviceProfile? {
        profileForMacOrNull(address)?.takeIf { AdapterRegistry.resolve(it.adapter) != null }?.let { return it }
        val name = runCatching {
            val ctx = currentContext() ?: currentApplication() ?: return@runCatching null
            @Suppress("MissingPermission")
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter
                ?.getRemoteDevice(address)
                ?.name
        }.getOrNull()
        name?.let { DeviceProfiles.findByName(it) }?.let { return it }
        return DeviceProfiles.get(activeProfileId)?.takeIf { AdapterRegistry.resolve(it.adapter) != null }
    }

    /** mac → 模块 DeviceProfile（仅返回本模块识别的设备，未识别返回 null）。 */
    private fun profileForMacOrNull(mac: String?): DeviceProfile? {
        if (mac.isNullOrBlank()) return null
        val normalized = runCatching { DeviceRegistryStore.normalizeAddress(mac) }.getOrNull() ?: mac
        if (normalized == activeModuleAddress) {
            DeviceProfiles.get(activeProfileId)?.let { return it }
        }
        val context = currentContext() ?: currentApplication() ?: return null
        return DeviceRegistryStore.profileIdForMac(context, normalized)?.let { DeviceProfiles.get(it) }
    }

    /** 在父链上查找弹窗入口方法 p(String,String,String,int,int,boolean)。 */
    private fun findDiscoveryPopupMethod(clazz: Class<*>?): Method? =
        generateSequence(clazz) { it.superclass }.mapNotNull { c ->
            runCatching {
                c.getDeclaredMethod(
                    "p",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
            }.getOrNull()
        }.firstOrNull()

    /** 解析 server 实例：优先已捕获的 g6.j；否则用 基类 a() 定位并校验含 p()。 */
    private fun discoveryManagerServerOrNull(): Any? {
        discoveryManagerServer?.let { return it }
        return runCatching {
            findClassOrNull(BASE_CLASS)
                ?.getDeclaredMethod("a")
                ?.apply { isAccessible = true }
                ?.invoke(null)
                ?.takeIf { findDiscoveryPopupMethod(it.javaClass) != null }
        }.getOrNull()?.also { discoveryManagerServer = it }
    }

    /** server 就绪后补发被挂起的弹窗请求。 */
    private fun flushPendingDiscoveryPopups() {
        if (pendingDiscoveryPopups.isEmpty()) return
        val snapshot = pendingDiscoveryPopups.toMap()
        pendingDiscoveryPopups.clear()
        logChain("I", "DAMS_FLUSH pending=${snapshot.size}")
        snapshot.values.forEach { device -> runCatching { tryInvokeDiscoveryPopup(device) } }
    }

    /** 直接向 server 实例投递弹窗；server 未就绪时挂起并返回 false。 */
    private fun tryInvokeDiscoveryPopup(popupDevice: DiscoveryPopupDevice): Boolean {
        val productId = AdapterRegistry.spoofProductIdFor(popupDevice.profile)
        // 单一真源：固定 6 位大写 hex（melody 下游 Integer.parseInt(id,16) 按 6 位解析）。
        val productIdHex = AdapterRegistry.spoofIdHexFor(popupDevice.profile)
        val manager = discoveryManagerServerOrNull()
        if (manager == null) {
            // 本进程没有 g6.j server（如 :fg 只有 g6.a client）→ 中继给主进程执行
            logChain("I", "DAMS_LOCAL_LOOKUP addr=${popupDevice.address} localServer=false hasP=false")
            relayPopupToMainProcess(popupDevice, productIdHex)
            logChain("I", "DAMS_RELAY_SEND addr=${popupDevice.address} profile=${popupDevice.profile.id} hex=$productIdHex")
            return false
        }
        val p = findDiscoveryPopupMethod(manager.javaClass)
        if (p == null) {
            logChain("I", "DAMS_SERVER_CAPTURED class=${manager.javaClass.name} hasP=false")
            relayPopupToMainProcess(popupDevice, productIdHex)
            logChain("I", "DAMS_RELAY_SEND addr=${popupDevice.address} profile=${popupDevice.profile.id} hex=$productIdHex")
            return false
        }
        logChain("I", "DAMS_SERVER_CAPTURED class=${manager.javaClass.name} hasP=true process=${currentProcessName(currentContext())}")
        return runCatching {
            p.isAccessible = true
            // 参数语义对齐 g6.e 真实调用：address, "onForwardConnectedPopup", productIdHex, 0, color, false
            p.invoke(manager, popupDevice.address, "onForwardConnectedPopup", productIdHex, 0, POPUP_DONOR_COLOR, false)
            logChain("I", "DAMS_P_INVOKE addr=${popupDevice.address} action=onForwardConnectedPopup hex=$productIdHex color=$POPUP_DONOR_COLOR")
            true
        }.onFailure {
            logChain("E", "DAMS_P_FAILED addr=${popupDevice.address} err=$it")
        }.getOrDefault(false)
    }

    /** 把弹窗请求经显式广播中继给 melody 主进程（g6.j server 所在进程），载荷保留完整 profile 身份。 */
    private fun relayPopupToMainProcess(popupDevice: DiscoveryPopupDevice, productIdHex: String) {
        runCatching {
            val ctx = currentContext() ?: currentApplication() ?: return@runCatching
            val relay = Intent(BluetoothPopupContract.ACTION_POPUP_EXECUTE).apply {
                // 显式指定宿主包，避免被外部拦截；主进程 receiver 为动态注册
                setPackage(ctx.packageName)
                putExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS, popupDevice.address)
                putExtra(BluetoothPopupContract.EXTRA_DEVICE_NAME, popupDevice.name)
                putExtra(BluetoothPopupContract.EXTRA_PROFILE_ID, popupDevice.profile.id)
                putExtra(BluetoothPopupContract.EXTRA_POPUP_PRODUCT_ID, productIdHex)
                putExtra(BluetoothPopupContract.EXTRA_DEBUG_PREVIEW, popupDevice.debugPreview)
            }
            ctx.sendBroadcast(relay)
        }.onFailure { modLog("I", "DAMS_RELAY_SEND failed: $it") }
    }

    /** 主进程侧：接收 :fg 的中继请求，用本地 g6.j server 执行 p()。 */
    private fun handleRelayedPopup(context: Context?, intent: Intent?) {
        val address = intent?.getStringExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS) ?: return
        val productIdHex = intent.getStringExtra(BluetoothPopupContract.EXTRA_POPUP_PRODUCT_ID)
            ?: XiberiaSpoof.spoofProductIdHex()
        val name = intent.getStringExtra(BluetoothPopupContract.EXTRA_DEVICE_NAME) ?: address
        val debugPreview = intent.getBooleanExtra(BluetoothPopupContract.EXTRA_DEBUG_PREVIEW, false)
        // 中继载荷携带真实 profileId：不再固定回退 Sony（调试官方注入 / XIBERIA / Sony 不串身份）。
        val profile = DeviceProfiles.get(intent.getStringExtra(BluetoothPopupContract.EXTRA_PROFILE_ID))
            ?: DeviceProfiles.sonyWf1000Xm3
        logChain(
            "I",
            "DAMS_RELAY_RECEIVE addr=$address profile=${profile.id} hex=$productIdHex " +
                "debug=$debugPreview process=${currentProcessName(context)}",
        )
        runCatching {
            val manager = discoveryManagerServerOrNull()
            if (manager == null) {
                // server 尚未构造：挂起（保留真实 profile），待 hookDiscoveryActionManagerServer 捕获后 flush
                pendingDiscoveryPopups[address] = DiscoveryPopupDevice(address, name, profile, debugPreview)
                scheduleDiscoveryRetry(context, address, "server_pending")
                logChain("I", "DAMS_RELAY_QUEUED addr=$address profile=${profile.id} reason=server_pending")
                return@runCatching
            }
            val p = findDiscoveryPopupMethod(manager.javaClass)
            if (p == null) {
                logChain("E", "DAMS_P_FAILED addr=$address err=NoSuchMethod_p on ${manager.javaClass.name}")
                scheduleDiscoveryRetry(context, address, "no_p")
                return@runCatching
            }
            p.isAccessible = true
            p.invoke(manager, address, "onForwardConnectedPopup", productIdHex, 0, POPUP_DONOR_COLOR, false)
            logChain("I", "DAMS_P_INVOKE addr=$address action=onForwardConnectedPopup hex=$productIdHex (relayed)")
            modLog("I", "relayed discovery popup: $address")
        }.onFailure {
            logChain("E", "DAMS_P_FAILED addr=$address err=$it")
        }
    }

    /** server 未就绪 / 无 p() 时的有限重试：0/100/500/1s/2s，用尽记 DAMS_RETRY_EXHAUSTED。 */
    private fun scheduleDiscoveryRetry(context: Context?, address: String, reason: String) {
        val attempt = (discoveryRetryAttempts[address] ?: 0) + 1
        if (attempt >= discoveryRetryDelays.size) {
            discoveryRetryAttempts.remove(address)
            pendingDiscoveryPopups.remove(address)
            logChain("E", "DAMS_RETRY_EXHAUSTED addr=$address reason=$reason attempts=$attempt")
            return
        }
        discoveryRetryAttempts[address] = attempt
        val delay = discoveryRetryDelays[attempt]
        Handler(Looper.getMainLooper()).postDelayed({
            val pending = pendingDiscoveryPopups[address]
            if (pending != null) {
                logChain("I", "DAMS_RETRY addr=$address attempt=$attempt delay=$delay reason=$reason")
                runCatching { tryInvokeDiscoveryPopup(pending) }
            } else {
                discoveryRetryAttempts.remove(address)
            }
        }, delay)
    }

    private fun routeDiscoveryPopup(context: Context, popupDevice: DiscoveryPopupDevice) {
        logChain("I", "DAMS_P_ATTEMPT addr=${popupDevice.address} profile=${popupDevice.profile.id} debug=${popupDevice.debugPreview}")
        runCatching {
            if (tryInvokeDiscoveryPopup(popupDevice)) {
                modLog("I", "show module discovery popup: ${popupDevice.address} / ${popupDevice.name}")
            }
        }.onFailure {
            logChain("E", "DAMS_P_FAILED addr=${popupDevice.address} err=$it")
            modLogT("W", "route module discovery popup failed", it)
        }
    }

    private fun forcePanelPreferences(fragment: Any?) {
        if (fragment == null) return
        val profile = moduleProfileFor(fragment) ?: return
        val address = activeModuleAddress ?: return
        setStringFieldIfBlank(fragment, "C", address)
        setStringFieldIfBlank(fragment, "D", profile.displayName)
        setStringFieldIfBlank(fragment, "E", profile.modelId)
        setObjectField(fragment, "G", true)
        setObjectField(fragment, "H", profile.uiFeatures.noiseReductionStrength)
        ensureDetailNoiseVo(fragment)
        setObjectField(fragment, "J", null)

        listOf(
            "pref_noise_switch_category",
            "pref_noise_switch",
            "pref_disconnect",
        ).forEach { key ->
            setPreferenceUnlocked(callMethodOrNull(fragment, "d", key))
        }

        listOf("t", "u", "v", "w", "z", "A", "B").forEach { field ->
            setPreferenceUnlocked(getObjectField(fragment, field))
        }

        callMethodOrNull(fragment, "u", createNoiseModes(), false)
        if (!profile.uiFeatures.noiseReductionStrength) {
            listOf("pref_noise_menu_category", "pref_noise_menu").forEach { key ->
                setPreferenceHidden(callMethodOrNull(fragment, "d", key))
            }
            listOf("x", "y").forEach { field ->
                setPreferenceHidden(getObjectField(fragment, field))
            }
        }
        val noisePreference = getObjectField(fragment, "w")
        ensureOneSpaceNoisePreferenceVo(noisePreference)
        injectNoisePreference(noisePreference)
        callMethodOrNull(fragment, "w")
        modLog("I", "forced one-space controls for $address / ${profile.id}")
    }

    private fun scheduleOneSpacePanelRefresh(fragment: Any?) {
        if (fragment == null) return
        val handler = Handler(Looper.getMainLooper())
        listOf(0L, 100L, 300L, 700L).forEach { delayMs ->
            handler.postDelayed(
                {
                    runCatching { forcePanelPreferences(fragment) }
                        .onFailure { modLogT("W", "delayed one-space refresh failed", it) }
                },
                delayMs,
            )
        }
    }

    private fun scheduleOneSpaceConnectedUi(panel: Any?) {
        if (panel == null) return
        val handler = Handler(Looper.getMainLooper())
        listOf(0L, 100L, 300L, 700L).forEach { delayMs ->
            handler.postDelayed({ hideOneSpaceConnectUi(panel) }, delayMs)
        }
    }

    private fun hideOneSpaceConnectUi(panel: Any?) {
        if (panel == null || !isModuleScope(panel)) return
        listOf("x", "y", "z").forEach { field ->
            (getObjectField(panel, field) as? View)?.visibility = View.GONE
        }
    }

    private fun ensureDetailNoiseVo(fragment: Any?) {
        if (fragment == null) return
        val vo = getObjectField(fragment, "I")
            ?: allocateHostObject(NOISE_VO_CLASS)?.also { setObjectField(fragment, "I", it) }
            ?: return
        normalizeNoiseVo(vo)
    }

    private fun ensureOneSpaceNoisePreferenceVo(preference: Any?) {
        if (preference == null) return
        val vo = getObjectField(preference, "j")
            ?: allocateHostObject(ONESPACE_NOISE_VO_CLASS)?.also { setObjectField(preference, "j", it) }
            ?: return
        applyOneSpaceNoiseVo(vo)
    }

    private fun allocateHostObject(className: String): Any? = runCatching {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, findClass(className))
    }.onFailure {
        modLogT("W", "allocate host object $className failed", it)
    }.getOrNull()

    private fun injectDseePreference(
        fragment: Any?,
        categoryKeys: List<String>,
        order: Int,
        location: String,
    ) {
        val dseeProfile = moduleProfileFor(fragment)
        // [修复·面板错标] XIBERIA(cchip/BT_MATE) 型号无 DSEE 功能，档案里 supportsDsee 位被
        //   复用为 LDAC；若按其注入会生成「标题 DSEE、实写 LDAC(0x0E04)」的错标开关，
        //   MC05 实测点它必失败。XIBERIA 型号统一不再注入 DSEE，改由动态面板暴露 LDAC 项。
        if (fragment == null || dseeProfile?.adapter == "xiberia") return
        if (dseeProfile?.capabilities?.supportsDsee != true) return
        val context = contextFrom(fragment) ?: return
        val existing = callMethodOrNull(fragment, "d", DSEE_PREFERENCE_KEY)
        if (existing != null) {
            lastDseePreference = WeakReference(existing)
            refreshDseePreference(existing)
            return
        }

        val category = categoryKeys.firstNotNullOfOrNull { key ->
            callMethodOrNull(fragment, "d", key)
        } ?: return
        val preference = findConstructorOrNull(COUI_SWITCH_PREFERENCE_CLASS, Context::class.java)
            ?.newInstance(context)
            ?: return
        invokeHostMethod(preference, "setKey", String::class.java, DSEE_PREFERENCE_KEY)
        invokeHostMethod(preference, "setTitle", CharSequence::class.java, "DSEE")
        invokeHostMethod(
            preference,
            "setSummary",
            CharSequence::class.java,
            localizedDseeText(
                context,
                chinese = "\u8fd8\u539f\u538b\u7f29\u97f3\u9891\u7684\u9ad8\u9891\u7ec6\u8282",
                english = "Restore high-frequency detail in compressed audio",
            ),
        )
        invokeHostMethod(preference, "setPersistent", Boolean::class.javaPrimitiveType!!, false)
        invokeHostMethod(preference, "setOrder", Int::class.javaPrimitiveType!!, order)
        installDseeChangeListener(preference)
        if (!addHostPreference(category, preference)) return

        callMethodOrNull(category, "setVisible", true)
        callMethodOrNull(category, "setEnabled", true)
        callMethodOrNull(category, "notifyChanged")
        lastDseePreference = WeakReference(preference)
        refreshDseePreference(preference)
        modLog("I", "injected native DSEE switch into $location")
    }

    private fun addHostPreference(category: Any, preference: Any): Boolean {
        val method = hostMethods(category.javaClass).firstOrNull { candidate ->
            // [修复·Melody 16.9.1] androidx.preference.PreferenceGroup 经 R8 后：
            //   addPreference → e(Preference)V     （旧代码只认 "addPreference"/"f" → 恒不命中）
            //   findPreference → g(CharSequence) / getPreference → h(I)
            candidate.name in setOf("addPreference", "e", "f") &&
                candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0].isAssignableFrom(preference.javaClass) &&
                candidate.returnType in setOf(Void.TYPE, Boolean::class.javaPrimitiveType)
        } ?: return false
        return runCatching {
            method.isAccessible = true
            val result = method.invoke(category, preference)
            method.returnType == Void.TYPE || result == true
        }.onFailure { modLogT("W", "add DSEE preference to ${category.javaClass.name} failed", it) }
            .getOrDefault(false)
    }

    private fun installDseeChangeListener(preference: Any) {
        val setter = hostMethods(preference.javaClass)
            .firstOrNull { it.name == "setOnPreferenceChangeListener" && it.parameterTypes.size == 1 }
            ?: return
        val listenerType = setter.parameterTypes[0]
        val listener = Proxy.newProxyInstance(
            listenerType.classLoader,
            arrayOf(listenerType),
        ) { proxy, method, args ->
            when {
                method.name == "toString" -> "MelodyPlusDseeChangeListener"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.returnType == Boolean::class.javaPrimitiveType && args?.size == 2 -> {
                    val enabled = args[1] as? Boolean
                    if (enabled != null) requestDseeChange(preference, enabled)
                    false
                }
                else -> null
            }
        }
        runCatching {
            setter.isAccessible = true
            setter.invoke(preference, listener)
        }.onFailure { modLogT("W", "install DSEE preference listener failed", it) }
    }

    private fun requestDseeChange(preference: Any, enabled: Boolean) {
        val context = contextFrom(preference) ?: currentContext() ?: return
        val address = activeModuleAddress ?: return
        val profile = DeviceProfiles.get(activeProfileId)?.takeIf { it.capabilities.supportsDsee } ?: return
        if (activeDebugPreview) {
            Toast.makeText(context, "调试预览不会发送设备命令", Toast.LENGTH_SHORT).show()
            return
        }
        pendingDseeStates[address] = enabled
        invokeHostMethod(preference, "setEnabled", Boolean::class.javaPrimitiveType!!, false)
        scope.launch {
            val result = HeadsetSessionManager.execute(
                context = context.applicationContext ?: context,
                address = address,
                profileId = profile.id,
                command = HeadsetCommand.SetDseeEnabled(enabled),
            )
            pendingDseeStates.remove(address)
            postToUi(context) {
                refreshDseePreference(preference)
                if (result !is CommandResult.Success) {
                    Toast.makeText(
                        context,
                        localizedDseeText(
                            context,
                            chinese = "DSEE \u8bbe\u7f6e\u5931\u8d25",
                            english = "Couldn't update DSEE",
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun refreshDseePreference(preference: Any? = lastDseePreference.get()) {
        if (preference == null) return
        val address = activeModuleAddress
        val enabled = address?.let(pendingDseeStates::get)
            ?: address?.let { HeadsetSessionManager.cachedState(it)?.dseeEnabled }
        if (enabled != null) {
            invokeHostMethod(preference, "setChecked", Boolean::class.javaPrimitiveType!!, enabled)
        }
        invokeHostMethod(
            preference,
            "setEnabled",
            Boolean::class.javaPrimitiveType!!,
            address != null && !pendingDseeStates.containsKey(address),
        )
        callMethodOrNull(preference, "notifyChanged")
    }

    private fun localizedDseeText(context: Context, chinese: String, english: String): String =
        if (context.resources.configuration.locales[0].language.startsWith("zh")) chinese else english

    private fun invokeHostMethod(instance: Any, name: String, parameterType: Class<*>, argument: Any?) {
        hostMethods(instance.javaClass)
            .firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.contentEquals(arrayOf(parameterType))
            }
            ?.let { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(instance, argument)
                }.onFailure { modLogT("W", "invoke ${instance.javaClass.name}#$name failed", it) }
            }
    }

    private fun hostMethods(type: Class<*>): Sequence<Method> = sequence {
        var current: Class<*>? = type
        while (current != null) {
            yieldAll(current.declaredMethods.asSequence())
            current = current.superclass
        }
    }

    private fun injectNoisePreference(preference: Any?) {
        if (preference == null) return
        setObjectField(preference, "e", true)
        setObjectField(preference, "f", true)
        setObjectField(preference, "g", true)
        setObjectField(preference, "h", true)
        submitModeItems(getObjectField(preference, "d"), contextFrom(preference))
    }

    private fun setPreferenceUnlocked(preference: Any?) {
        if (preference == null) return
        callMethodOrNull(preference, "setVisible", true)
        callMethodOrNull(preference, "setEnabled", true)
        callMethodOrNull(preference, "setSelectable", true)
        callMethodOrNull(preference, "notifyChanged")
    }

    private fun setPreferenceHidden(preference: Any?) {
        if (preference == null) return
        callMethodOrNull(preference, "setVisible", false)
        callMethodOrNull(preference, "notifyChanged")
    }

    private fun submitModeItems(widget: Any?, context: Context? = contextFrom(widget), force: Boolean = false) {
        if (widget == null) return
        // MC05：原厂降噪条整体摘除 —— 无论注入分支走哪条，提交后一律把控件 GONE。
        //   （modeOrder 已置空 → createModeItems() 返回空表 → 控件本就无内容；GONE 是
        //    防止控件自身仍占位，见 PLAN_MC05_Feature_Panel_v2 §2 块①。）
        hideModeStrip(widget)
        val selectedMode = currentAncMode(activeModuleAddress)
        if (!force && hasInjectedModeList(widget, selectedMode)) {
            forceDeviceControlWidgetEnabled(widget)
            return
        }
        submittingModeItems.set(true)
        try {
            callMethodOrNull(widget, "b", createModeItems(context))
            callMethodOrNull(widget, "setEnable", true)
            forceDeviceControlWidgetEnabled(widget)
        } finally {
            submittingModeItems.set(false)
        }
        hideModeStrip(widget)
    }

    /**
     * MC05：隐藏原厂降噪模式条。
     *
     * 依据官方 `Product$MC05`（isSupportNoiseControl=false）——MC05 无降噪能力，
     * 该条属错误注入。控 GONE 而非 removeView：宿主会在多种时机重填，removeView 会
     * 被下一轮重填打回；GONE 幂等且抗重填。父级留白由宿主 DeviceControlWidget 外层
     * `paddingBottom=10dp` + wrap_content 承担，实测无残高（若有则改 removeView）。
     */
    private fun hideModeStrip(widget: Any?) {
        val v = widget as? View ?: return
        if (v.visibility == View.GONE) return
        v.visibility = View.GONE
        modLog("I", "MODE_STRIP_REMOVED class=${v.javaClass.name}")
    }

    private fun hasInjectedModeList(widget: Any?, selectedMode: AncMode): Boolean {
        val modes = (callMethodOrNull(widget, "getModeList") as? Collection<*>
            ?: getObjectField(widget, "mModeList") as? Collection<*>
            ?: getObjectField(widget, "modeList") as? Collection<*>)
            ?.map { item ->
                DetailModeItemSnapshot(
                    tag = getObjectField(item, "a") as? String,
                    selected = getObjectField(item, "e") as? Boolean ?: false,
                    moduleMode = moduleModeForItem(item),
                )
            }
            ?: return false
        return DetailPanelPresentationPolicy.isInjectedLayout(modes, selectedMode)
    }

    private fun createModeItems(context: Context? = currentContext()): ArrayList<Any> {
        val selectedMode = currentAncMode(activeModuleAddress)
        val constructor = modeItemConstructor()
        return ArrayList(DetailPanelPresentationPolicy.modeOrder.map { mode ->
            val modeType = DetailPanelPresentationPolicy.melodyModeType(mode)
            createModeItem(context, constructor, modeType, selectedMode == mode)
        })
    }

    private fun createModeItem(context: Context?, constructor: Constructor<*>, modeType: Int, selected: Boolean): Any {
        createHostModeItem(context, modeType, selected)?.let {
            setObjectField(it, "h", true)
            DetailPanelPresentationPolicy.moduleModeForTag(modeType.toString())?.let { mode ->
                rememberModuleModeItem(it, mode)
            }
            return it
        }
        val item = constructor.newInstance()
        setObjectField(item, "a", modeType.toString())
        setObjectField(item, "b", hostDrawable(context, iconResForModeType(modeType)))
        setObjectField(item, "c", Color.rgb(0, 122, 255))
        setObjectField(item, "d", hostString(context, titleResForModeType(modeType), fallbackTitleForModeType(modeType)))
        setObjectField(item, "e", selected)
        setObjectField(item, "f", false)
        setObjectField(item, "g", true)
        setObjectField(item, "h", true)
        setObjectField(item, "i", false)
        DetailPanelPresentationPolicy.moduleModeForTag(modeType.toString())?.let { mode ->
            rememberModuleModeItem(item, mode)
        }
        return item
    }

    private fun rememberModuleModeItem(item: Any, mode: AncMode) {
        synchronized(moduleModeItems) {
            moduleModeItems.removeAll { ref -> ref.item.get() == null || ref.item.get() === item }
            moduleModeItems += ModuleModeItemRef(WeakReference(item), mode)
        }
    }

    private fun moduleModeForItem(item: Any?): AncMode? {
        if (item == null) return null
        synchronized(moduleModeItems) {
            var matchedMode: AncMode? = null
            moduleModeItems.removeAll { ref ->
                val candidate = ref.item.get()
                if (candidate === item) matchedMode = ref.mode
                candidate == null
            }
            return matchedMode
        }
    }

    private fun modeItemConstructor(): Constructor<*> =
        cachedModeItemConstructor ?: findConstructor(MODE_ITEM_CLASS).also {
            cachedModeItemConstructor = it
        }

    private fun createFunction(): Any =
        (cachedFunctionConstructor ?: findConstructor(FUNCTION_CLASS).also {
            cachedFunctionConstructor = it
        }).newInstance()

    private fun augmentFunction(function: Any?) {
        if (function == null) return
        val noiseModes = createNoiseModes()
        val profile = moduleProfileFor(function)
        function.javaClass.declaredFields.forEach { field ->
            runCatching {
                field.isAccessible = true
                when {
                    field.name == "noiseReductionMode" -> field.set(function, noiseModes)
                    field.name == "noiseReductionUIVersion" -> field.setInt(function, NOISE_UI_VERSION_BASIC)
                    field.type == Int::class.javaPrimitiveType -> field.setInt(function, functionIntValue(field.name, profile))
                    field.type == Boolean::class.javaPrimitiveType -> field.setBoolean(function, false)
                    field.type == java.lang.Integer::class.java -> field.set(function, functionIntValue(field.name, profile))
                }
            }
        }
        callMethodOrNull(function, "setNoiseReductionMode", noiseModes)
        callMethodOrNull(function, "setNoiseReductionUIVersion", NOISE_UI_VERSION_BASIC)
        callMethodOrNull(function, "setStrongNoiseReductionRealTime", functionIntValue("getStrongNoiseReductionRealTime", profile))
        callMethodOrNull(function, "setOpsReduction", functionIntValue("getOpsReduction", profile))
        callMethodOrNull(function, "setMultiDevicesConnect", functionIntValue("getMultiDevicesConnect", profile))
        callMethodOrNull(function, "setPersonalNoise", functionIntValue("getPersonalNoise", profile))
    }

    private fun createNoiseModes(): ArrayList<Any> {
        val ctor = noiseModeConstructor()
        fun mode(modeType: Int, protocolIndex: Int, children: List<Any>? = null): Any =
            ctor.newInstance(modeType, protocolIndex, children, false, 0, 0)

        return arrayListOf(
            mode(MELODY_MODE_NOISE_REDUCTION, PROTOCOL_NOISE_REDUCTION),
            mode(MELODY_MODE_OFF, PROTOCOL_OFF),
            mode(MELODY_MODE_TRANSPARENCY, PROTOCOL_TRANSPARENCY),
        )
    }

    private fun createNoiseInfo(): Any {
        val info = noiseInfoConstructor().newInstance(0, 1, 0)
        for (mode in SUPPORTED_PROTOCOL_INDICES) {
            callMethodOrNull(info, "setSupportNoiseReductionModeValue", mode, true)
        }
        return info
    }

    private fun noiseModeConstructor(): Constructor<*> =
        cachedNoiseModeConstructor ?: findConstructor(
            NOISE_MODE_CLASS,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            java.util.List::class.java,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ).also {
            cachedNoiseModeConstructor = it
        }

    private fun noiseInfoConstructor(): Constructor<*> =
        cachedNoiseInfoConstructor ?: findConstructor(
            NOISE_INFO_CLASS,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ).also {
            cachedNoiseInfoConstructor = it
        }

    private fun setStringFieldIfBlank(instance: Any, fieldName: String, value: String) {
        val current = getObjectField(instance, fieldName) as? String
        if (current.isNullOrBlank()) setObjectField(instance, fieldName, value)
    }

    private fun isOneSpaceStack(source: Any? = null): Boolean {
        val contextName = when (source) {
            is View -> source.context?.javaClass?.name
            is Context -> source.javaClass.name
            else -> null
        }
        if (contextName?.contains("onespace", ignoreCase = true) == true ||
            contextName?.contains("OneSpace", ignoreCase = true) == true
        ) {
            return true
        }
        return Thread.currentThread().stackTrace.any {
            it.className.contains("onespace", ignoreCase = true) ||
                it.className.contains("OneSpace", ignoreCase = true)
        }
    }

    private fun moduleProfileFor(source: Any?): DeviceProfile? {
        val activeAddress = activeModuleAddress
        val activeProfile = DeviceProfiles.get(activeProfileId)
        if (activeAddress != null && activeProfile != null) {
            val sourceAddress = extractDirectAddress(source)
            if (sourceAddress == null || sourceAddress == activeAddress) return activeProfile
        }
        val context = contextFrom(source) ?: currentContext() ?: return null
        val address = extractAddress(source) ?: activeModuleAddress ?: return null
        val profile = moduleProfileForAddress(context, address, source) ?: return null
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        bindActivePanelContext(normalized, profile.id)
        return profile
    }

    private fun moduleProfileForAddress(context: Context, address: String, source: Any?): DeviceProfile? {
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        val profile = profileForAddress(context, normalized, source) ?: return null
        bindActivePanelContext(normalized, profile.id)
        // 地址已就绪：触发此前因缺少 MAC 而挂起的详情页电量补采。
        flushPendingDetailRefresh(normalized)
        return profile
    }

    private fun isModuleScope(source: Any?): Boolean =
        moduleProfileFor(source) != null

    private fun contextFrom(source: Any?): Context? =
        when (source) {
            is Context -> source
            else -> (callMethodOrNull(source, "getContext") as? Context)
                ?: (callMethodOrNull(source, "getActivity") as? Context)
        }?.also { lastContext = WeakReference(it) }

    private fun currentContext(): Context? =
        lastContext.get()

    private fun extractDirectAddress(source: Any?): String? =
        runCatching {
            val raw = when (source) {
                null -> null
                is Activity -> source.intent?.let(::addressFromIntent)
                is Intent -> addressFromIntent(source)
                is Bundle -> addressFromBundle(source)
                else -> {
                    directStringField(
                        source,
                        "C",
                        "b",
                        "i",
                        "mAddress",
                        "macAddress",
                        "mMacAddress",
                        "deviceMac",
                        "deviceAddress",
                        "mac",
                        "address",
                        "device_mac_info",
                    )
                        ?: directStringMethod(
                            source,
                            "getMAddress",
                            "getAddress",
                            "getMac",
                            "getMacAddress",
                            "getDeviceMac",
                            "getDeviceAddress",
                            "getDeviceMacInfo",
                        )
                }
            } ?: return@runCatching null
            normalizeAddressOrNull(raw)
        }.getOrNull()

    private fun extractAddress(source: Any?): String? =
        extractAddress(source, depth = 0)

    private fun extractAddress(source: Any?, depth: Int): String? {
        if (source == null || depth > 4) return null
        val raw = when (source) {
            is Activity -> source.intent?.let(::addressFromIntent)
            is Intent -> addressFromIntent(source)
            is Bundle -> addressFromBundle(source)
            else -> {
                directStringField(
                    source,
                    "C",
                    "b",
                    "i",
                    "mAddress",
                    "macAddress",
                    "mMacAddress",
                    "deviceMac",
                    "deviceAddress",
                    "mac",
                    "address",
                    "device_mac_info",
                )
                    ?: directStringMethod(
                        source,
                        "getMAddress",
                        "getAddress",
                        "getMac",
                        "getMacAddress",
                        "getDeviceMac",
                        "getDeviceAddress",
                        "getDeviceMacInfo",
                    )
                    ?: extractAddress(directObjectField(source, "a"), depth + 1)
                    ?: extractAddress(directObjectField(source, "j"), depth + 1)
                    ?: extractAddress(directObjectField(source, "mNoiseReductionVO"), depth + 1)
                    ?: extractAddress(directObjectField(source, "mViewModel"), depth + 1)
            }
        } ?: return null
        return normalizeAddressOrNull(raw)
    }

    private fun profileForPanel(context: Context, address: String, intent: Intent): DeviceProfile? =
        DeviceProfiles.get(intent.getStringExtra("model_id"))
            ?: DeviceProfiles.get(intent.getStringExtra("product_id"))
            ?: DeviceProfiles.get(intent.getStringExtra("device_product_id"))
            ?: ModuleDeviceRegistry(context).profileForName(
                intent.getStringExtra("device_title") ?: intent.getStringExtra("device_name"),
            )

    private fun profileForAddress(context: Context, address: String, source: Any?): DeviceProfile? {
        // 0) MAC→型号 缓存优先：改名后仍能识别
        DeviceRegistryStore.profileIdForMac(context, address)
            ?.let { DeviceProfiles.get(it) }
            ?.let { cached ->
                // 名称仍能匹配时刷新缓存（id 不变，无害）；否则直接用缓存型号
                return cached
            }
        // 1) 活跃会话
        if (activeModuleAddress == address) {
            DeviceProfiles.get(activeProfileId)?.let { return it }
        }
        // 2) 名称识别 + 首次识别写缓存（供改名后用）
        return ModuleDeviceRegistry(context).profileForName(extractDeviceName(source))
            ?.also { profile ->
                runCatching { DeviceRegistryStore.saveMacProfile(context, address, profile.id) }
            }
    }

    private fun extractDeviceName(source: Any?): String? =
        extractDeviceName(source, depth = 0)

    private fun extractDeviceName(source: Any?, depth: Int): String? =
        if (source == null || depth > 4) {
            null
        } else when (source) {
            is Activity -> source.intent?.getStringExtra("device_title")
                ?: source.intent?.getStringExtra("device_name")
            is Intent -> source.getStringExtra("device_title")
                ?: source.getStringExtra("device_name")
            is Bundle -> nameFromBundle(source)
            else -> {
                listOf("D", "t", "c", "mProductName", "mName", "name", "deviceName", "device_title")
                    .firstNotNullOfOrNull { runCatching { getObjectField(source, it) as? String }.getOrNull() }
                    ?: directStringMethod(source, "getMProductName", "getName", "getDeviceName", "getProductName")
                    ?: extractDeviceName(directObjectField(source, "a"), depth + 1)
                    ?: extractDeviceName(directObjectField(source, "mNoiseReductionVO"), depth + 1)
                    ?: extractDeviceName(directObjectField(source, "j"), depth + 1)
            }
        }

    private fun directObjectField(source: Any?, name: String): Any? =
        runCatching { getObjectField(source, name) }.getOrNull()

    private fun directStringField(source: Any?, vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            runCatching { getObjectField(source, name) as? String }.getOrNull()
        }

    private fun directStringMethod(source: Any?, vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            runCatching { callMethodOrNull(source, name) as? String }.getOrNull()
        }

    private fun addressFromIntent(intent: Intent): String? =
        listOf(
            "device_mac_info",
            "deviceAddress",
            "device_address",
            "deviceMac",
            "device_mac",
            "mac",
            "address",
            "mAddress",
        ).firstNotNullOfOrNull { key ->
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }
        }

    private fun addressFromBundle(bundle: Bundle?): String? =
        if (bundle == null) {
            null
        } else {
            listOf(
                "device_mac_info",
                "deviceAddress",
                "device_address",
                "deviceMac",
                "device_mac",
                "launcher_address",
                "mac",
                "address",
                "mAddress",
            ).firstNotNullOfOrNull { key ->
                bundle.getString(key)?.takeIf { it.isNotBlank() }
            }
        }

    private fun nameFromIntent(intent: Intent): String? =
        intent.getStringExtra("device_name")
            ?: intent.getStringExtra("device_title")
            ?: intent.getStringExtra("name")

    private fun nameFromBundle(bundle: Bundle?): String? =
        bundle?.getString("device_name")
            ?: bundle?.getString("device_title")
            ?: bundle?.getString("name")

    private fun profileFromBundle(bundle: Bundle?): DeviceProfile? =
        DeviceProfiles.get(bundle?.getString("model_id"))
            ?: DeviceProfiles.get(bundle?.getString("product_id"))
            ?: DeviceProfiles.get(bundle?.getString("device_product_id"))

    private fun normalizeAddressOrNull(address: String?): String? {
        val normalized = address
            ?.takeIf { it.isNotBlank() }
            ?.let(DeviceRegistryStore::normalizeAddress)
            ?: return null
        return normalized.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
    }

    private fun launcherAddress(context: Context?): String? =
        runCatching {
            normalizeAddressOrNull(
                context
                    ?.getSharedPreferences("melody-model-settings", Context.MODE_PRIVATE)
                    ?.getString("launcher_address", null),
            )
        }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun addressForSupportedName(context: Context?, name: String?): String? {
        if (context == null || name.isNullOrBlank()) return null
        if (!ModuleDeviceRegistry(context).isModuleSupportedName(name)) return null
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.bondedDevices
                ?.firstOrNull { device ->
                    runCatching { ModuleDeviceRegistry(context).isModuleSupportedName(device.name) }
                        .getOrDefault(false)
                }
                ?.address
                ?.let(::normalizeAddressOrNull)
        }.onFailure {
            modLogT("W", "lookup bonded module address failed", it)
        }.getOrNull()
    }

    private fun functionIntValue(name: String, profile: DeviceProfile?): Int =
        when (name) {
            "batteryInfo",
            "getBatteryInfo"
            -> 1

            "noiseReductionUIVersion",
            "getNoiseReductionUIVersion"
            -> NOISE_UI_VERSION_BASIC

            "strongNoiseReductionRealTime",
            "opsReduction",
            "getStrongNoiseReductionRealTime",
            "getOpsReduction"
            -> if (profile?.uiFeatures?.noiseReductionStrength == true) 1 else 0

            "multiDevicesConnect",
            "getMultiDevicesConnect"
            -> if (profile?.uiFeatures?.multiDeviceConnect == true) 1 else 0

            "personalNoise",
            "getPersonalNoise"
            -> if (profile?.uiFeatures?.personalizedNoise == true) 1 else 0

            "customEqualizer",
            "customEqUiVersion",
            "customEqMax",
            "equalizer",
            "getCustomEqualizer",
            "getCustomEqUiVersion",
            "getCustomEqMax",
            "getEqualizer"
            -> if (profile?.uiFeatures?.customEq == true) 1 else 0

            else -> 0
        }

    private fun createHostModeItem(context: Context?, modeType: Int, selected: Boolean): Any? {
        val hostContext = context ?: currentContext() ?: return null
        val title = hostString(hostContext, titleResForModeType(modeType), fallbackTitleForModeType(modeType))
        val drawable = hostDrawable(hostContext, iconResForModeType(modeType))
        return runCatching {
            hostModeFactoryMethod().invoke(null, hostContext, modeType, drawable, title, selected)
        }.getOrNull()
    }

    private fun hostModeFactoryMethod(): Method =
        cachedHostModeFactoryMethod ?: findClass("com.oplus.melody.onespace.items.OneSpaceNoisePreference")
            .getDeclaredMethod(
                "i",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                Drawable::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
            )
            .apply { isAccessible = true }
            .also { cachedHostModeFactoryMethod = it }

    private fun hostDrawable(context: Context?, resId: Int): Drawable? {
        if (context == null || resId == 0) return null
        return runCatching { context.getDrawable(resId) }.getOrNull()
    }

    private fun hostString(context: Context?, resId: Int, fallback: String): String {
        if (context == null || resId == 0) return fallback
        return runCatching { context.getString(resId) }.getOrDefault(fallback)
    }

    private fun iconResForModeType(modeType: Int): Int =
        when (modeType) {
            MELODY_MODE_OFF -> 0x7f080606
            MELODY_MODE_NOISE_REDUCTION -> 0x7f080607
            MELODY_MODE_TRANSPARENCY -> 0x7f080609
            else -> 0
        }

    private fun titleResForModeType(modeType: Int): Int =
        when (modeType) {
            MELODY_MODE_OFF -> 0x7f11072e
            MELODY_MODE_NOISE_REDUCTION -> 0x7f110731
            MELODY_MODE_TRANSPARENCY -> 0x7f11072f
            else -> 0
        }

    private fun fallbackTitleForModeType(modeType: Int): String =
        when (modeType) {
            MELODY_MODE_OFF -> "关闭"
            MELODY_MODE_NOISE_REDUCTION -> "降噪"
            MELODY_MODE_TRANSPARENCY -> "通透"
            else -> ""
        }

    private fun currentAncMode(address: String? = activeModuleAddress): AncMode {
        val normalizedAddress = address?.let(DeviceRegistryStore::normalizeAddress)
        val mode = DetailPanelPresentationPolicy.resolvedMode(
            pending = normalizedAddress?.let(pendingAncModes::get),
            cached = normalizedAddress
                ?.let { runCatching { HeadsetSessionManager.cachedState(it)?.ancMode }.getOrNull() },
            fallback = activeAncMode,
        )
        activeAncMode = mode
        return mode
    }

    private fun currentBatteryState(address: String? = activeModuleAddress): BatteryState? {
        val normalized = address?.let { runCatching { DeviceRegistryStore.normalizeAddress(it) }.getOrNull() }
        // 实时 SPP 会话快照（连接/刷新路径写入）。
        val live = normalized?.let {
            runCatching { HeadsetSessionManager.cachedState(it)?.battery }.getOrNull()
        }
        // 本模块主动补采缓存（详情页在宿主无电量回调时使用）。
        val cached = normalized?.let { detailBatteryCache[it] }
        // 关键修复：**按槽位合并**，而不是「live 有任意一路就整份返回」。
        //   旧实现 `if (live.hasAnyLevel()) return live` 会在会话快照只带 left=100（单路
        //   fallback）时把整份 live 返回，detailBatteryCache 里的完整 100/100/51 被彻底丢弃
        //   → 详情页三图只剩左耳一个电量。逐槽取非空值让两源互补即可。
        val local = mergeBattery(live, cached)
        if (local?.isComplete() == true) return local
        // [SPP 单所有者] :fg 不建链，用主进程经 BatteryShare 共享的电量补齐。
        val shared = normalized?.let { n ->
            runCatching { currentContext()?.let { c -> BatteryShare.read(c, n) } }.getOrNull()
        }
        return mergeBattery(local, shared)
    }

    /** 逐槽合并两份电量：任一路取非空的优先源（primary 优先）。两源皆空返回 null。 */
    private fun mergeBattery(primary: BatteryState?, secondary: BatteryState?): BatteryState? {
        if (primary == null) return secondary
        if (secondary == null) return primary
        return BatteryState(
            single = primary.single ?: secondary.single,
            left = primary.left ?: secondary.left,
            right = primary.right ?: secondary.right,
            caseBattery = primary.caseBattery ?: secondary.caseBattery,
            leftCharging = primary.leftCharging ?: secondary.leftCharging,
            rightCharging = primary.rightCharging ?: secondary.rightCharging,
            caseCharging = primary.caseCharging ?: secondary.caseCharging,
        )
    }

    /** 三路是否齐全（左耳/右耳/仓都已有值）。缺任一路 → 视为不完整，应触发补采。 */
    private fun BatteryState.isComplete(): Boolean =
        left != null && right != null && caseBattery != null

    /** 是否含任一有效电量档位（用于判断整单是否为空）。 */
    private fun BatteryState.hasAnyLevel(): Boolean =
        left != null || right != null || caseBattery != null || single != null


    /** 取视图 resource 名（无 id 返回 null）。 */
    private fun resourceNameOf(v: View): String? = runCatching {
        if (v.id == View.NO_ID) null else v.resources.getResourceEntryName(v.id)
    }.getOrNull()


    /**
     * 宿主原生电量主动补采（详情页 / 连接发现弹窗共用）。
     *
     * 背景：宿主电量回调依赖真实耳机（伪装后常缺失），宿主原生电量控件（`renderDetailStatusInfo`
     * 承载的 b/g/u 三路）会停在 `--`。这里由模块主动建 SPP 会话补采一次真实电量，写入
     * [detailBatteryCache] 后立即刷新宿主原生电量视图（不再自绘任何容器）。
     *
     * 触发点：① 每次贴图后（即时性）；② [BATTERY_POLL_INTERVAL_MS] 周期轮询（兜底）。
     */
    private fun ensureDetailBatteryRefresh(profile: DeviceProfile) {
        val address = activeModuleAddress
        if (address.isNullOrBlank()) {
            // 地址尚未解析：挂起，等 moduleProfileForAddress 解析出 MAC 后再触发（避免重复排）。
            if (detailRefreshPosted.add(DETAIL_REFRESH_PENDING_KEY)) {
                pendingDetailRefresh.add { refreshDetailBatteryViews() }
            }
            return
        }
        scheduleDetailBatteryFetch(address, profile) { refreshDetailBatteryViews() }
    }

    private fun scheduleDetailBatteryFetch(
        address: String,
        profile: DeviceProfile,
        force: Boolean = false,
        onApplied: () -> Unit,
    ) {
        // [SPP 单所有者] :fg 不建链。
        if (isPopupProcessNow()) { onApplied(); return }
        val normalized = runCatching { DeviceRegistryStore.normalizeAddress(address) }.getOrNull() ?: address
        // 已有真实会话缓存或本模块补采缓存：直接回填，无需再建会话。
        //   关键修复：用 currentBatteryState（已按槽位合并 live+cache）判断是否**三路齐全**，
        //   而不是只看单一源的 hasAnyLevel()——否则 live 只有 left 时会跳过补采、右/仓恒空。
        //   force=true（周期轮询）：忽略「已齐全」早返回，强制建/复用会话查一次（兼作心跳）。
        val have = currentBatteryState(normalized)
        if (!force && have != null && have.isComplete()) {
            onApplied()
            return
        }
        if (!detailBatteryInFlight.add(normalized)) return
        val context = currentContext() ?: currentApplication() ?: run {
            detailBatteryInFlight.remove(normalized); return
        }
        val appContext = context.applicationContext ?: context
        scope.launch {
            runCatching { HeadsetSessionManager.refresh(appContext, normalized, profile.id)?.battery }
                .onSuccess { fetched ->
                    if (fetched != null && fetched.hasAnyLevel()) {
                        detailBatteryCache[normalized] = fetched
                        publishBatteryShare(appContext, normalized, fetched)
                        logChain(
                            "I",
                            "DETAIL_BATTERY_FETCHED addr=$normalized profile=${profile.id} " +
                                "left=${fetched.left} right=${fetched.right} box=${fetched.caseBattery} " +
                                "single=${fetched.single}",
                        )
                        postToUi(appContext) { runCatching { onApplied() } }
                    } else {
                        logChain("E", "DETAIL_BATTERY_EMPTY addr=$normalized profile=${profile.id}")
                    }
                }
                .onFailure { logChain("E", "DETAIL_BATTERY_FAILED addr=$normalized err=$it") }
            detailBatteryInFlight.remove(normalized)
        }
    }

    /** 地址解析完成后触发之前挂起的详情页补采。 */
    private fun flushPendingDetailRefresh(address: String) {
        detailRefreshPosted.remove(DETAIL_REFRESH_PENDING_KEY)
        val pending = pendingDetailRefresh.toList()
        pendingDetailRefresh.clear()
        if (pending.isEmpty()) return
        val profile = DeviceProfiles.get(activeProfileId) ?: return
        pending.forEach { onApplied ->
            runCatching { scheduleDetailBatteryFetch(address, profile, onApplied = onApplied) }
        }
    }


    /**
     * 统一解析某槽位图片（全型号通用）。
     *
     * 优先级：① 用户自定义 `<profileId>/<slot>.png`（MelodyPlus/images 目录）
     *        → ② 内置 `assets/device_images/<型号目录>/<slot>.png`
     *        → ③ 内置主图 `<型号目录>/main.png`（左/右/仓共用，旧单图型号兼容）。
     */
    private fun slotDrawable(
        context: Context,
        profile: DeviceProfile,
        slot: String,
    ): Drawable? {
        val resources = context.resources
        // ① 用户自定义（经 Provider 中转，melody 进程无存储权限）
        userDeviceImageBytes(context, profile.id, slot)?.let { bytes ->
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            if (bitmap != null) {
                return BitmapDrawable(resources, bitmap).also {
                    it.setTargetDensity(resources.displayMetrics)
                }
            }
        }
        // ② 内置「本槽位」分体资源。
        //    关键修复 1：删除 main 主图兜底（原 ③）。旧实现当 left/right/case 缺失时回退整机
        //    main.png → 三图容器里出现「整机大图」（连接发现弹窗 / 详情页「卡顿直接出现主图」）。
        //    模块只要有分体图；某槽位无图时保持空（不画），绝不把整机大图塞进单格。
        //    关键修复 2：hook 运行在宿主进程，其 ClassLoader 指向宿主 APK，`getResourceAsStream`
        //    读不到模块 assets（实测 setImages(left=,right=,case=) 全空，而 APK 内图确实存在）。
        //    改为经模块 Provider 用【模块进程】的 AssetManager 代读。
        builtinDeviceImageBytes(context, profile.id, slot)?.let { bytes ->
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            if (bitmap != null) {
                return BitmapDrawable(resources, bitmap).also {
                    it.setTargetDensity(resources.displayMetrics)
                }
            }
        }
        return null
    }

    /**
     * 经模块 Provider 取「内置」型号图片字节（assets/device_images/<dir>/<slot>.png）。
     *
     * 必须中转：hook 侧运行在宿主进程，`MelodyPanelHook::class.java.classLoader` 指向宿主
     * APK，读不到模块 assets。Provider 在模块进程内，用自身 AssetManager 读取后回传。
     */
    private fun builtinDeviceImageBytes(context: Context?, profileId: String, slot: String): ByteArray? {
        // 直接读模块自身 APK 的 assets（ZipFile 路径，绕开包可见性 / Provider / 隐藏 API）。
        // 详见 [ModuleAssets]：宿主进程无法解析模块 Provider authority（Android 11+ 包可见性，
        // 实测抛 Unknown authority），也无法用自身 ClassLoader 读模块 assets，
        // 故用 modulePath（IXposedHookZygoteInit 传来）/ dexElements 反推 + ZipFile 直读。
        val bytes = ModuleAssets.readBuiltinImage(profileId, slot)
        if (bytes != null && bytes.isNotEmpty()) {
            logChain("I", "BUILTIN_IMG profile=$profileId slot=$slot size=${bytes.size}")
        }
        return bytes
    }

    private fun replaceModuleProductImage(source: Any?): Boolean {
        if (replacingProductImage.get()) return false
        val profile = moduleProfileFor(source) ?: return false
        val sourceClassName = source?.javaClass?.name
        val imageView = when {
            sourceClassName == ONESPACE_HEADER_CLASS -> getObjectField(source, "e") as? android.widget.ImageView
            sourceClassName == ONESPACE_CONNECT_CLASS -> getObjectField(source, "f") as? android.widget.ImageView
            source is android.widget.ImageView -> source.takeIf(::isDetailNormalImage)
            source is Activity -> findDetailNormalImage(source)
            sourceClassName == DETAIL_MODEL_VIEW_CLASS ->
                findDetailNormalImage(source) ?: (getObjectField(source, "d") as? android.widget.ImageView)
            source is View -> findDetailNormalImage(source)
            else -> listOf("e", "f", "mDeviceImage", "deviceImage", "mImageView")
                .firstNotNullOfOrNull { runCatching { getObjectField(source, it) as? android.widget.ImageView }.getOrNull() }
        } ?: return false
        // 直接给宿主原生 ImageView 贴「单张主图」——不再自绘三图容器。
        //   · 图片：模块解析出的单张 main 图（用户自定义 main.png → 内置 main.png）。
        //   · 电量：完全交给宿主原生控件（模块经 renderDetailStatusInfo 注入 b/g/u 三路），
        //           本函数只负责换图，不碰电量视图。
        val drawable = productDrawable(imageView.context, profile) ?: return false
        replacingProductImage.set(true)
        try {
            // 停掉宿主 MelodyDetailModelView 的内部大图渲染，避免其 Glide 回填覆盖本模块图。
            if (sourceClassName == DETAIL_MODEL_VIEW_CLASS) {
                stopDetailModelRendering(source, imageView)
            }
            imageView.animate().cancel()
            imageView.clearAnimation()
            imageView.alpha = 1f
            imageView.scaleX = 1f
            imageView.scaleY = 1f
            imageView.visibility = View.VISIBLE
            imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            imageView.imageTintList = null
            callMethodOrNull(imageView, "setSupportImageTintList", null)
            imageView.clearColorFilter()
            val replacementDrawable = drawable.constantState?.newDrawable(imageView.resources) ?: drawable
            replacementDrawable.alpha = 255
            imageView.setImageDrawable(replacementDrawable)
            ensureImageHierarchyVisible(imageView)
        } finally {
            replacingProductImage.set(false)
        }
        imageView.invalidate()
        // 贴图后触发一次宿主原生电量补采/刷新（首次即时报数；周期轮询由 BATTERY_POLL_INTERVAL_MS 兜底）。
        runCatching { ensureDetailBatteryRefresh(profile) }
        val logKey = "${sourceClassName ?: "unknown"}#${imageView.id}"
        if (loggedProductImageTargets.add(logKey)) {
            modLog("I", "replaced normal_image product image (single-main) from $logKey")
        }
        return true
    }

    /**
     * 模块解析出的「单张主图」Drawable。
     *
     * 优先级（与 [slotDrawable] 一致）：① 用户自定义 `MelodyPlus/images/<profileId>/main.png`
     * → ② 内置 `assets/device_images/<型号目录>/main.png`；两者都无 → null。
     */
    private fun productDrawable(context: Context?, profile: DeviceProfile): Drawable? {
        val ctx = context ?: currentContext() ?: currentApplication() ?: return null
        return slotDrawable(ctx, profile, DeviceImageAssets.SLOT_MAIN)
    }

    /**
     * 把模块单张主图直接贴到任意宿主 ImageView（连接发现弹窗卡片主图 / 其它非 `normal_image` 图位）。
     *
     * 与 [replaceModuleProductImage] 的差别：不做「是否宿主详情主图」判定，调用方明确传入目标 ImageView。
     * 电量不在此处理（宿主原生承载）。
     */
    private fun applyProductImageTo(
        imageView: android.widget.ImageView,
        profile: DeviceProfile,
    ): Boolean {
        if (replacingProductImage.get()) return false
        val drawable = productDrawable(imageView.context, profile) ?: return false
        replacingProductImage.set(true)
        try {
            imageView.animate().cancel()
            imageView.clearAnimation()
            imageView.alpha = 1f
            imageView.visibility = View.VISIBLE
            imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            imageView.imageTintList = null
            callMethodOrNull(imageView, "setSupportImageTintList", null)
            imageView.clearColorFilter()
            val replacementDrawable = drawable.constantState?.newDrawable(imageView.resources) ?: drawable
            replacementDrawable.alpha = 255
            imageView.setImageDrawable(replacementDrawable)
            ensureImageHierarchyVisible(imageView)
        } finally {
            replacingProductImage.set(false)
        }
        imageView.invalidate()
        return true
    }

    private fun findDetailNormalImage(source: Any?): android.widget.ImageView? {
        val context = contextFrom(source) ?: return null
        val root = when (source) {
            is Activity -> source.window?.decorView
            is View -> source
            else -> null
        } ?: return null
        if (root is android.widget.ImageView && isDetailNormalImage(root)) return root
        val resolvedId = context.resources.getIdentifier(
            DETAIL_NORMAL_IMAGE_NAME,
            "id",
            context.packageName,
        )
        return listOf(resolvedId, RES_DETAIL_NORMAL_IMAGE)
            .filter { it != 0 }
            .distinct()
            .firstNotNullOfOrNull { imageId ->
                root.findViewById<View>(imageId) as? android.widget.ImageView
            }
    }

    private fun isDetailNormalImage(imageView: android.widget.ImageView): Boolean {
        val viewId = imageView.id
        if (viewId == View.NO_ID) return false
        if (viewId == RES_DETAIL_NORMAL_IMAGE) return true
        val resolvedId = imageView.resources.getIdentifier(
            DETAIL_NORMAL_IMAGE_NAME,
            "id",
            imageView.context.packageName,
        )
        if (resolvedId != 0 && viewId == resolvedId) return true
        return runCatching {
            imageView.resources.getResourceEntryName(viewId) == DETAIL_NORMAL_IMAGE_NAME
        }.getOrDefault(false)
    }

    private fun stopDetailModelRendering(source: Any, imageView: android.widget.ImageView) {
        callMethodOrNull(getObjectField(source, "f"), "cancel")
        setObjectField(source, "j", null)
        if (getObjectField(source, "b") != null) {
            callMethodOrNull(source, "d")
        }
        hideCompetingModelView(getObjectField(source, "c") as? View, imageView)
        hideCompetingModelView(getObjectField(source, "a") as? View, imageView)
        getObjectField(source, "e")?.let { animationView ->
            callMethodOrNull(animationView, "cancelAnimation")
            hideCompetingModelView(animationView as? View, imageView)
        }
        imageView.animate().cancel()
        imageView.clearAnimation()
    }

    private fun hideCompetingModelView(candidate: View?, imageView: android.widget.ImageView) {
        if (candidate == null) return
        candidate.animate().cancel()
        candidate.clearAnimation()
        // 【单主图改造】不再有「被三图容器接管的宿主主图」概念：候选若在宿主主图层级链上
        //   （即宿主大图本体），保持可见；否则才隐藏。电量视图不受影响（走宿主原生）。
        if (isImageHierarchyView(candidate, imageView)) {
            candidate.alpha = 1f
            candidate.visibility = View.VISIBLE
        } else {
            candidate.visibility = View.GONE
        }
    }

    private fun ensureImageHierarchyVisible(imageView: View) {
        var current: View? = imageView
        while (current != null) {
            // 【单主图改造】统一点亮主图所在层级：宿主 ImageView 与电量控件都需保持可见。
            current.alpha = 1f
            current.visibility = View.VISIBLE
            current = current.parent as? View
        }
    }

    private fun isImageHierarchyView(candidate: View, imageView: android.widget.ImageView): Boolean {
        var current: View? = imageView
        while (current != null) {
            if (current === candidate) return true
            current = current.parent as? View
        }
        return false
    }


    /**
     * 经模块 Provider（`com.melody.melodyplus.registry`，hook 侧 melody 进程无存储权限）
     * 取某型号某槽位的用户自定义图片字节；未提供返回 null。
     * @param slot [DeviceImageAssets.SLOT_MAIN] / SLOT_LEFT / SLOT_RIGHT / SLOT_CASE
     */
    private fun userDeviceImageBytes(context: Context?, profileId: String, slot: String): ByteArray? = runCatching {
        val ctx = context ?: currentContext() ?: currentApplication() ?: return null
        if (RegistryCircuit.shouldSkip()) return null
        if (!RegistryContract.isProviderAvailable(ctx)) return null
        val bundle = ctx.contentResolver.call(
            RegistryContract.URI,
            RegistryContract.METHOD_GET_DEVICE_IMAGE,
            profileId,
            Bundle().apply {
                putString(RegistryContract.EXTRA_DEBUG_PROFILE_ID, profileId)
                putString(RegistryContract.EXTRA_DEVICE_IMAGE_SLOT, slot)
            },
        ) ?: return null
        if (!bundle.getBoolean(RegistryContract.EXTRA_DEVICE_IMAGE_FOUND, false)) return null
        bundle.getByteArray(RegistryContract.EXTRA_DEVICE_IMAGE_BYTES)
    }.onFailure {
        modLog("W", "userDeviceImageBytes failed profile=$profileId slot=$slot: $it")
    }.getOrNull()

    private fun scheduleStateRefresh(context: Context, address: String, profile: DeviceProfile) {
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        val now = System.currentTimeMillis()
        val lastScheduled = lastRefreshScheduledAt[normalized] ?: 0L
        if (now - lastScheduled < STATE_REFRESH_THROTTLE_MS || !refreshInFlight.add(normalized)) {
            return
        }
        lastRefreshScheduledAt[normalized] = now
        val appContext = context.applicationContext ?: context
        Handler(Looper.getMainLooper()).postDelayed({
            scope.launch {
                runCatching {
                    HeadsetSessionManager.refresh(appContext, normalized, profile.id)
                }.onFailure {
                    modLogT("W", "refresh headset state failed", it)
                }
                refreshInFlight.remove(normalized)
                postToUi(appContext) {
                    if (activeModuleAddress == normalized) {
                        refreshConnectionInfoItem(lastConnectionItem.get())
                        refreshDetailBatteryViews()
                        refreshDseePreference()
                    }
                }
            }
        }, STATE_REFRESH_DELAY_MS)
        // 同址仅保留一条 5 分钟轮询链（兜底宿主原生电量；首次即时补采由 replaceModuleProductImage 触发）。
        scheduleBatteryPolling(appContext, normalized, profile)
    }

    private fun scheduleBatteryPolling(context: Context, address: String, profile: DeviceProfile) {
        // 同址只保留一条轮询链；后续重复排定直接忽略（避免多 Activity/多 surface 叠出 N 条）。
        if (!batteryPollingActive.add(address)) return
        val appContext = context.applicationContext ?: context
        val mainHandler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                // 模块作用域已切走 / 进程退出：停止轮询，避免无意义建会话。
                if (activeModuleAddress != address) {
                    cancelBatteryPolling(address)
                    return
                }
                // [修复] 旧实现调 scheduleDetailBatteryFetch：电量为「完整」时该方法直接 return，
                //   既不建会话也不刷新 → 5 分钟轮询实际空转。改为强制刷新：建/复用 SPP 会话
                //   主动查一次电量，并刷新详情页 + 弹窗原生槽（兼作 SPP 心跳，抑制空闲被对端断开）。
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
                mainHandler.postDelayed(this, BATTERY_POLL_INTERVAL_MS)
            }
        }
        // [修复·轮询无法主动取消] 原实现只存地址不存 Runnable：切换地址后旧 runnable 最多还会
        //   存活一个周期（5 分钟）、占用引用并可能在切换竞态触发旧设备请求。现登记 Runnable，
        //   地址切换/断连时可立即 removeCallbacks 取消，而非等下一次自检。
        batteryPollRunnables[address] = tick
        mainHandler.postDelayed(tick, BATTERY_POLL_INTERVAL_MS)
    }

    /** 立即取消某地址的 5 分钟电量轮询链（地址切换 / 断连 / 详情页销毁时调用）。 */
    private fun cancelBatteryPolling(address: String) {
        val normalized = DeviceRegistryStore.normalizeAddress(address)
        batteryPollingActive.remove(normalized)
        val runnable = batteryPollRunnables.remove(normalized) ?: return
        runCatching { Handler(Looper.getMainLooper()).removeCallbacks(runnable) }
        logChain("I", "BATTERY_POLL_CANCELLED addr=$normalized")
    }

    private fun currentProtocolIndex(address: String? = activeModuleAddress): Int =
        protocolIndexForMode(currentAncMode(address))

    private fun protocolIndexForMode(mode: AncMode): Int =
        when (mode) {
            AncMode.NOISE_CANCELLING,
            AncMode.WIND_REDUCTION
            -> PROTOCOL_NOISE_REDUCTION

            AncMode.TRANSPARENCY,
            AncMode.ADAPTIVE
            -> PROTOCOL_TRANSPARENCY

            AncMode.OFF -> PROTOCOL_OFF
        }

    private fun currentMelodyModeType(address: String? = activeModuleAddress): Int =
        melodyModeTypeForMode(currentAncMode(address))

    private fun melodyModeTypeForMode(mode: AncMode): Int =
        when (mode) {
            AncMode.NOISE_CANCELLING,
            AncMode.WIND_REDUCTION
            -> MELODY_MODE_NOISE_REDUCTION

            AncMode.TRANSPARENCY,
            AncMode.ADAPTIVE
            -> MELODY_MODE_TRANSPARENCY

            AncMode.OFF -> MELODY_MODE_OFF
        }

    private fun currentNoiseModeDto(): Any? {
        val protocolIndex = currentProtocolIndex(activeModuleAddress)
        return createNoiseModes().firstOrNull { protocolIndexFromNoiseMode(it) == protocolIndex }
    }

    private fun protocolIndexFromNoiseMode(noiseMode: Any?): Int? =
        callMethodOrNull(noiseMode, "getProtocolIndex") as? Int

    private fun applyOneSpaceNoiseVo(vo: Any?) {
        val profile = moduleProfileFor(vo) ?: return
        val address = activeModuleAddress ?: extractAddress(vo) ?: return
        callMethodOrNull(vo, "setMAddress", address)
        callMethodOrNull(vo, "setMProductId", profile.modelId)
        callMethodOrNull(vo, "setMProductName", profile.displayName)
        callMethodOrNull(vo, "setMConnectState", 2)
        callMethodOrNull(vo, "setMCurrentNoiseMode", currentMelodyModeType(address))
        callMethodOrNull(vo, "setMNoiseReductionUIVersion", NOISE_UI_VERSION_BASIC)
        callMethodOrNull(vo, "setMNoiseReductionModeList", createNoiseModes())
        callMethodOrNull(vo, "setMSupportNoiseReductionInfo", createNoiseInfo())
    }

    private fun normalizeNoiseVo(vo: Any?, mode: AncMode = currentAncMode()) {
        if (vo == null) return
        activeAncMode = mode
        val protocolIndex = protocolIndexForMode(mode)
        setObjectField(vo, "mConnectionState", 2)
        setObjectField(vo, "mHeadsetConnectionState", 2)
        setObjectField(vo, "mConnectState", 2)
        setObjectField(vo, "mCurrentNoiseReductionModeIndex", protocolIndex)
        setObjectField(vo, "mNoiseReductionModeIndex", protocolIndex)
        setObjectField(vo, "mIntelligentNoiseReductionModeIndex", protocolIndex)
        setObjectField(vo, "mNoiseReductionUIVersion", NOISE_UI_VERSION_BASIC)
        setObjectField(vo, "mCapabilityReady", true)
        setObjectField(vo, "mIsCapabilityReady", true)
        setObjectField(vo, "mSupportEarStatus", false)
        setObjectField(vo, "mSupportNoiseReductionInfo", createNoiseInfo())
        setObjectField(vo, "mNoiseReductionModeList", createNoiseModes())
    }

    private fun refreshOneSpaceNoisePreference(preference: Any?, mode: AncMode) {
        if (preference == null) return
        activeAncMode = mode
        val vo = getObjectField(preference, "j")
        val melodyMode = melodyModeTypeForMode(mode)
        if (vo != null) {
            callMethodOrNull(vo, "setMCurrentNoiseMode", melodyMode)
            callMethodOrNull(vo, "setMConnectState", 2)
            callMethodOrNull(vo, "setMNoiseReductionUIVersion", NOISE_UI_VERSION_BASIC)
            callMethodOrNull(vo, "setMNoiseReductionModeList", createNoiseModes())
            callMethodOrNull(vo, "setMSupportNoiseReductionInfo", createNoiseInfo())
            setObjectField(vo, "mCurrentNoiseMode", melodyMode)
            setObjectField(vo, "mConnectState", 2)
            setObjectField(vo, "mNoiseReductionUIVersion", NOISE_UI_VERSION_BASIC)
            setObjectField(vo, "mNoiseReductionModeList", createNoiseModes())
            setObjectField(vo, "mSupportNoiseReductionInfo", createNoiseInfo())
        }
        submitModeItems(getObjectField(preference, "d"), contextFrom(preference))
        callMethodOrNull(preference, "notifyChanged")
    }

    private fun refreshDetailNoiseItem(owner: Any?, mode: AncMode) {
        if (owner == null) return
        activeAncMode = mode
        val vo = getObjectField(owner, "mNoiseReductionVO")
        if (vo != null) normalizeNoiseVo(vo, mode)
        val widget = getObjectField(owner, "mActionView")
        if (widget != null) submitModeItems(widget, contextFrom(owner), force = true)
    }

    private fun refreshDetailBatteryViews(fragment: Any? = lastDetailFragment.get()) {
        if (fragment == null || !isModuleScope(fragment)) return
        val holder = getObjectField(fragment, "h")
        val statusInfo = getObjectField(holder, "c") ?: return
        renderDetailStatusInfo(statusInfo)
    }

    private fun refreshConnectionInfoItem(item: Any?) {
        if (item == null) return
        val vo = lastConnectionVo.get()
        if (vo != null) {
            normalizeConnectionVo(vo)
            dispatchConnectionVoChanged(item, vo)
        }
        renderConnectionInfoWidget(item)
        callMethodOrNull(item, "notifyChanged")
    }

    private fun dispatchConnectionVoChanged(item: Any?, vo: Any?) {
        if (item == null || vo == null) return
        var cls: Class<*>? = item.javaClass
        while (cls != null) {
            cls.declaredMethods
                .filter { method ->
                    method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].name == CONNECTION_INFO_VO_CLASS
                }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        method.invoke(item, vo)
                    }.onFailure { modLogT("W", "dispatch connection info ${method.name} failed", it) }
                }
            cls = cls.superclass
        }
    }

    private fun renderConnectionInfoWidget(item: Any?) {
        val widget = getObjectField(item, "mLinkActionView") as? View ?: return
        val state = connectionStateFor(extractAddress(item) ?: activeModuleAddress)
        val linkedCell = callMethodOrNull(widget, "getLinkedCell") as? View
            ?: getObjectField(widget, "c") as? View
        val linkingCell = callMethodOrNull(widget, "getLinkingCell") as? View
            ?: getObjectField(widget, "b") as? View
        if (state == 2) {
            linkedCell?.visibility = View.VISIBLE
            linkingCell?.visibility = View.GONE
        } else {
            linkedCell?.visibility = View.GONE
            linkingCell?.visibility = View.VISIBLE
            val context = contextFrom(item) ?: currentContext()
            val title = hostString(context, 0x7f1107f5, "未连接")
            val body = hostString(context, 0x7f1101b2, "")
            setTextOnNestedField(linkingCell, title, "d")
            setTextOnNestedField(linkingCell, body, "b", "c")
        }
        widget.invalidate()
    }

    private fun setTextOnNestedField(owner: Any?, text: String, vararg fieldNames: String) {
        if (owner == null || text.isBlank()) return
        fieldNames.forEach { field ->
            val view = getObjectField(owner, field) as? android.widget.TextView
            if (view != null) {
                view.text = text
                view.visibility = View.VISIBLE
                return
            }
        }
    }

    private fun normalizeConnectionVo(vo: Any?) {
        if (vo == null) return
        val address = extractAddress(vo) ?: activeModuleAddress
        val state = connectionStateFor(address)
        setConnectionStateMembers(vo, state)
        forceConnectedMembers(vo)
        setBooleanMembers(vo, true, "mIsSpp", "isSpp", "mSpp", "mConnected", "isConnected")
        callMethodOrNull(vo, "setIsSpp", true)
        callMethodOrNull(vo, "setSpp", true)
        callMethodOrNull(vo, "setConnected", true)
        if (address != null) setAddressMembers(vo, address)
    }

    private fun setConnectionStateMembers(vo: Any?, state: Int) {
        listOf(
            "mConnectionState",
            "mHeadsetConnectionState",
            "mConnectState",
            "mAclConnectionState",
            "mA2dpConnectionState",
            "mSppOverGattConnectionState",
            "connectionState",
            "headsetConnectionState",
            "connectState",
        ).forEach { field -> setObjectField(vo, field, state) }
        listOf(
            "setConnectionState",
            "setHeadsetConnectionState",
            "setConnectState",
            "setMConnectionState",
            "setMHeadsetConnectionState",
            "setMConnectState",
            "setAclConnectionState",
            "setA2dpConnectionState",
            "setSppOverGattConnectionState",
        ).forEach { method -> callMethodOrNull(vo, method, state) }
    }

    private fun setBooleanMembers(target: Any?, value: Boolean, vararg fields: String) {
        fields.forEach { field -> setObjectField(target, field, value) }
    }

    private fun setAddressMembers(target: Any?, address: String) {
        listOf(
            "mAddress",
            "mMacAddress",
            "mDeviceAddress",
            "address",
            "macAddress",
            "deviceAddress",
            "device_mac_info",
        ).forEach { field -> setObjectField(target, field, address) }
        listOf(
            "setAddress",
            "setMacAddress",
            "setDeviceAddress",
            "setMAddress",
            "setMMacAddress",
            "setMDeviceAddress",
        ).forEach { method -> callMethodOrNull(target, method, address) }
    }

    private fun connectionStateFor(address: String?): Int {
        return 2
    }

    private fun postToUi(context: Context, action: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            runCatching(action).onFailure { modLogT("W", "post UI update failed", it) }
        }
    }

    private fun hideNoiseStrengthViewIfUnsupported(view: Any?) {
        if (view !is View) return
        val profile = moduleProfileFor(view) ?: return
        if (profile.uiFeatures.noiseReductionStrength) return
        view.visibility = View.GONE
        view.isEnabled = false
    }

    private fun ancModeFromDetailModeItem(owner: Any?, item: Any?): AncMode? {
        moduleModeForItem(item)?.let { return it }
        val position = (getObjectField(item, "a") as? String)?.toIntOrNull() ?: return null
        val modes = getObjectField(owner, "mNoiseReductionModeList") as? List<*> ?: return null
        val protocolIndex = protocolIndexFromNoiseMode(modes.getOrNull(position))
            ?: protocolIndexFromNoiseMode(modes.firstOrNull { callMethodOrNull(it, "getModeType") as? Int == position })
        return ancModeFromProtocolIndex(protocolIndex)
    }

    private fun ancModeFromModeItem(item: Any?): AncMode? {
        val id = (getObjectField(item, "a") as? String)?.toIntOrNull()
        return when (id) {
            MELODY_MODE_OFF -> AncMode.OFF
            MELODY_MODE_NOISE_REDUCTION, 3, 4, 10 -> AncMode.NOISE_CANCELLING
            MELODY_MODE_TRANSPARENCY, 6 -> AncMode.TRANSPARENCY
            else -> null
        }
    }

    private fun ancModeFromProtocolIndex(index: Int?): AncMode? =
        DetailPanelPresentationPolicy.modeForProtocolIndex(index)

    private fun executeAncCommand(
        context: Context,
        address: String,
        profile: DeviceProfile,
        mode: AncMode,
    ): CompletableFuture<Any?> {
        if (activeDebugPreview) {
            activeAncMode = mode
            return CompletableFuture.completedFuture(null)
        }
        val future = CompletableFuture<Any?>()
        scope.launch {
            val result = runCatching {
                HeadsetSessionManager.execute(
                    context = context,
                    address = address,
                    profileId = profile.id,
                    command = HeadsetCommand.SetAncMode(mode),
                )
            }.getOrElse { CommandResult.Failed("exception: $it", it) }
            when (result) {
                CommandResult.Success -> {
                    activeAncMode = mode
                    future.complete(null)
                }
                // 修复：不再 completeExceptionally —— 宿主面板可能直接 future.get()，
                // 异常会在其主线程抛出导致闪退；改为失败时静默完成并回滚 UI 状态。
                is CommandResult.Unsupported, is CommandResult.Failed -> {
                    modLog("W", "anc mode change failed: $result")
                    future.complete(null)
                }
            }
        }
        return future
    }

    /**
     * 调试注入接收器：在 OPPO melody 进程内注册，监听模块专属 action。
     * 因 onHook 阶段 ActivityThread.currentApplication() 通常为 null（Application 未就绪），
     * 改为 hook Application.onCreate，在 Application 创建后（context 就绪时）再注册。
     * 模块 App（UI）发 [BluetoothPopupContract.ACTION_MODULE_AUDIO_CONNECTED] 广播后，
     * 通过这里构造虚拟连接 Intent 走完整的 [handleDiscoveryPopupIntent] 弹窗链路。
     */
    private fun registerDebugInjectReceiver() {
        safeHook(TAG, "debug inject receiver (Application attach/onCreate)") {
            findClassOrNull("android.app.Application") ?: run {
                modLog("W", "debug inject: Application class unavailable")
                return@safeHook
            }
            // attach 时机更早：此时 ActivityThread.currentApplication() 可能仍为 null
            // （mInitialApplication 在 attach 之后才赋值），必须用入参 Context。
            findMethodOrNull("android.app.Application", "attach", Context::class.java)?.let { m ->
                hookAfter(m) {
                    val ctx = (args.getOrNull(0) as? Context) ?: currentApplication()
                    if (ctx != null) ensureDebugInjectReceiver(ctx, "attach")
                }
            }
            // onCreate 兜底：Application 完全就绪（currentApplication 可用）。
            findMethodOrNull("android.app.Application", "onCreate")?.let { m ->
                hookAfter(m) { ensureDebugInjectReceiver(currentApplication(), "onCreate") }
            }
        }
    }

    @Volatile
    private var debugInjectReceiverInstalled = false

    /** 幂等注册调试注入接收器（防重复）；记录真实进程名与导出标志。 */
    private fun ensureDebugInjectReceiver(context: Context?, reason: String) {
        if (context == null || debugInjectReceiverInstalled) return
        synchronized(this) {
            if (debugInjectReceiverInstalled) return
            runCatching {
                val filter = android.content.IntentFilter().apply {
                    addAction(BluetoothPopupContract.ACTION_MODULE_AUDIO_CONNECTED)
                    addAction(BluetoothPopupContract.ACTION_POPUP_EXECUTE)
                    addAction(BluetoothPopupContract.ACTION_BATTERY_UPDATED)
                    addAction(BluetoothPopupContract.ACTION_POPUP_SHOWING)
                }
                // Android 14+ 动态 receiver 必须显式声明导出标志；跨进程广播（来自模块 App）需要 EXPORTED。
                context.registerReceiver(object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val action = intent?.action
                        logChain(
                            "I",
                            "MELODY_RECEIVER_ENTER action=$action process=${currentProcessName(context)} " +
                                "hasAddress=${intent?.hasExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS) == true}",
                        )
                        if (action == BluetoothPopupContract.ACTION_BATTERY_UPDATED) {
                            refreshPopupBatteryIfShowing()
                            return
                        }
                        if (action == BluetoothPopupContract.ACTION_POPUP_EXECUTE) {
                            // 主进程侧：执行中继来的弹窗请求
                            handleRelayedPopup(context, intent)
                            return
                        }
                        if (action == BluetoothPopupContract.ACTION_POPUP_SHOWING) {
                            // [真值矫正] :fg 侧弹窗生命周期同步来的「真实存活」真值：
                            //   仅主进程（持有 g6.j server）记录；供 i() 出口矫正使用。
                            val showing = intent.getBooleanExtra(BluetoothPopupContract.EXTRA_POPUP_SHOWING, false)
                            if (discoveryManagerServerOrNull() != null) {
                                popupRealShowing = showing
                                logChain("I", "POPUP_REAL_STATE_SET showing=$showing process=${currentProcessName(context)}")
                            }
                            return
                        }
                        // 注入一台"官方耳机"OPPO Enco X3：默认名/身份取官方 profile。
                        // 地址优先用已绑定设备真实 MAC（无绑定才退回占位）。
                        val bound = context?.let { c ->
                            runCatching { DeviceRegistryStore.read(c) }.getOrNull()
                        }
                        val name = intent?.getStringExtra(BluetoothPopupContract.EXTRA_DEVICE_NAME)
                            ?: BluetoothPopupContract.DEBUG_DEFAULT_NAME
                        val address = intent?.getStringExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS)
                            ?: bound?.address
                            ?: BluetoothPopupContract.DEBUG_DEFAULT_ADDRESS
                        logChain("I", "POPUP_INTENT_ACCEPT action=$action debugPreview=true addr=$address name=$name")
                        val debugIntent = Intent(BluetoothPopupContract.ACTION_MODULE_AUDIO_CONNECTED)
                        debugIntent.putExtra(BluetoothPopupContract.EXTRA_MODULE_TRIGGER, true)
                        debugIntent.putExtra(BluetoothPopupContract.EXTRA_DEVICE_NAME, name)
                        debugIntent.putExtra(BluetoothPopupContract.EXTRA_DEVICE_ADDRESS, address)
                        // 修复：debug 注入接收器曾被无条件标记为 debugPreview=true（→ 恒定走 X3 伪装档案），
                        //   导致真实连接链路也吃 X3 档案、渲染 X3 资源图。改为尊重广播携带的标志：
                        //   只有模块 UI 的「调试注入」按钮（显式带 EXTRA_DEBUG_PREVIEW=true）才走 X3。
                        val debugPreview = intent?.getBooleanExtra(BluetoothPopupContract.EXTRA_DEBUG_PREVIEW, false) == true
                        val resolvedProfileId = if (debugPreview) {
                            BluetoothPopupContract.DEBUG_DEFAULT_PROFILE_ID
                        } else {
                            intent?.getStringExtra(BluetoothPopupContract.EXTRA_PROFILE_ID)
                                ?: ModuleDeviceRegistry(context).profileForName(name)?.id
                                ?: BluetoothPopupContract.DEBUG_DEFAULT_PROFILE_ID
                        }
                        debugIntent.putExtra(BluetoothPopupContract.EXTRA_PROFILE_ID, resolvedProfileId)
                        debugIntent.putExtra(BluetoothPopupContract.EXTRA_DEBUG_PREVIEW, debugPreview)
                        handleDiscoveryPopupIntent(context, debugIntent)
                    }
                }, filter, android.content.Context.RECEIVER_EXPORTED)
                debugInjectReceiverInstalled = true
                logChain(
                    "I",
                    "DEBUG_RECEIVER_REGISTERED process=${currentProcessName(context)} exported=true reason=$reason",
                )
                modLog("I", "debug inject receiver registered ($reason)")
            }.onFailure {
                logChain("E", "DEBUG_RECEIVER_REGISTERED failed reason=$reason err=$it")
                modLogT("W", "debug inject receiver register failed", it)
            }
        }
    }
}

