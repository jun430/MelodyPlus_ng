# MelodyPlus 改造方案 —— 设备伪装对齐 + 多品牌后端路由（v1）

> 目标：① 让任意品牌第三方蓝牙耳机在 OPPO 旋律 App（`com.oplus.melody`）中**被识别为已注册官方设备**，从而走官方弹窗/管理链路；② 把跨进程后端路由从「硬编码 when 分派」升级为**可注册的多品牌路由表**，新品牌零成本接入。
> 所有 hook 点均已在**真机 16.9.1** 通过 npmcp 反编译验证签名存在（非凭空推断）。

---

## 0. 参考依据

| 来源 | 内容 | 对本方案价值 |
|---|---|---|
| `jerry-2009/MelodyLink`（文件已拉取至 `/root/MelodyLink`） | OPPO 旋律《设备伪装+会话接管》完整实现（hook `DeviceInfoManager`/`DeviceInfo`/`EarphoneDTO`/whitelist） | **核心打法参照** |
| `Mercury000/SonyPods`（拉取至 `/root/SonyPods_ref`） | 小米配索尼：型号伪装 + 系统耳机注入 + Tandem 协议 | 多品牌路由的「伪装模型」、协议引擎参照 |
| npmcp 反编译（workspace `35147598`）真机旋律 APK 16.9.1 | 逐一验证关键类/方法签名 | **落地正确性的唯一依据** |

---

## 1. 现状诊断（MelodyPlus_ng 已有 vs 缺失）

### 1.1 已实现（与 MelodyLink 等价或更全）
- ✅ whitelist DTO 伪造：`forgeWhitelistDto`（`WhitelistConfigDTO`，id=`6B450`）
- ✅ `EarphoneDTO`/`NOISE_VO`/`DeviceInfo` 等连接状态 getter 伪造 → 返回 `2`（已连接）
- ✅ 降噪模式 / 电量 / SPP / 能力位全量伪造（`hookConnectedDataClass` / `hookNoiseData` / `hookGetterResult`）
- ✅ RFCOMM 传输：`adapter/sony` + `adapter/xiberia`（A2DP 之上自建 RFCOMM 会话）
- ✅ 已逆向 `HeadsetCoreService$e → dto.getId() → parseInt(id,16) → DeviceInfoManager.a(productId, device)` 链路
- ✅ 跨进程后端：`DeviceRegistryProvider`（ContentProvider，authority `com.melody.melodyplus.registry`）

### 1.2 核心差距（「弹窗不弹」根因）
MelodyPlus 伪造的是**「已连接后各 getter 返回已连接」**，但缺少**「设备进 melody 设备注册表」**这一步：
- melody 的弹窗入口（`checkShowConnectCapsule` / discovery）**只在设备已存在于 `DeviceInfoManager` 注册表（`ConcurrentHashMap`）时触发**
- MelodyPlus **未 hook `DeviceInfoManager.d(BluetoothDevice)`**，所以目标设备根本不在注册表 → 弹窗链路不启动

MelodyLink 的关键补丁正是：**hook `DeviceInfoManager.d`（deviceRegistryGet）→ 对目标设备返回 null 时，用 `f(productId, device, addr, name)` 创建 DeviceInfo + `c(info)` 注册**，把设备**主动塞进注册表**。设备入表后 melody 才会走完整的官方识别/弹窗/卡片链路。

### 1.3 次要问题
- 伪装 productId 用 `6B450`（十进制 439376），并非官方已验证可用的合法白名单型号；改用 MelodyLink 验证过的 **`0x067410`（OPPO Enco X3，十进制 422416）**。

---

## 2. 对齐设备伪装（改造点一）

### 2.1 需新增的 hook（核心：设备注册入表）
在 `MelodyPanelHook` 新增一个 `hookDeviceRegistryInjection()`（`safeHook` 包裹），在 melody 进程 hook：

| 类 | 方法签名（16.9.1 已验证） | hook 动作 |
|---|---|---|
| `com.oplus.melody.btsdk.api.manager.DeviceInfoManager` | `d(BluetoothDevice)LDeviceInfo;`（static final，deviceRegistryGet） | after：目标设备且 result==null → **注册入表**（见 2.2） |
| 同 | `f(ILBluetoothDevice;Ljava/lang/String;Ljava/lang/String;)LDeviceInfo;`（static） | 反射调用，创建 DeviceInfo（productId=`0x067410`） |
| 同 | `c(LDeviceInfo;)V`（final） | 反射调用，添加注册 |
| 同 | `h(Ljava/lang/String;)LDeviceInfo;` | （查重，可复用） |
| `com.oplus.melody.btsdk.api.data.DeviceInfo` | `setDeviceConnectState(I)V` | after：目标设备 → 置 2 |
| 同 | `setDeviceHeadsetConnectState(I)V` | after：置 2 |
| 同 | `setDeviceA2dpConnectState(I)V` | after：置 2 |
| 同 | `setDeviceLeAudioConnectState(String,I)V` 与 `(I)V` | after：置 2 |
| 同 | `setProductId(I)V` / `setA2dpActive(Z)V` | after：置 `0x067410` / true |
| 同 | `isConnected()Z` | after：目标设备 → true（现有 `hookConnectedDataClass` 已覆盖，此处为兜底） |

> `DeviceInfo` 上的 setter/getter 已由现有 `hookAllDevicesConnected`/`hookConnectedDataClass` 覆盖大部分；**增量是 `DeviceInfoManager.d/f/c` 三条注册链**。

### 2.2 注册动作实现（对齐 MelodyLink `registerTargetDevice`）
```kotlin
// 在 melody 进程，target = 用户绑定的第三方耳机 BluetoothDevice
private fun registerTargetDeviceInMelody(device: BluetoothDevice) {
    val mgr = findClass("com.oplus.melody.btsdk.api.manager.DeviceInfoManager")
    val f = mgr.getDeclaredMethod("f", Int::class.javaPrimitiveType,
        BluetoothDevice::class.java, String::class.java, String::class.java)
    f.isAccessible = true
    val info = f.invoke(null, SPOOF_PRODUCT_ID /*0x067410*/, device, device.address, device.name)
    if (info != null) {
        val c = mgr.getDeclaredMethod("c", info.javaClass)
        c.isAccessible = true
        c.invoke(mgr, info)   // 真正把设备塞进 DeviceInfoManager 的 ConcurrentHashMap
    }
}
```
- 触发时机：`DeviceInfoManager.d(device)` 返回 null（melody 查表未命中）且 `device` 属于模块作用域（`isModuleScope`/注册表标记）→ 执行注册。
- **前提判断**：仅在设备真实 A2DP 连接（`BluetoothProfile.A2DP` state==2，或 `DeviceInfo.mDeviceA2dpConnectState==2`）时注册+伪造——否则弹窗链路即便触发也无真实连接数据，适得其反（对齐 MelodyLink `isA2dpConnected` 守卫）。

### 2.3 whitelist 对齐
- 保留现有 `forgeWhitelistDto`（16.9.1 已逆向 `HeadsetCoreService$e` 链路），但：
  - 把 `setId("6B450")` → `setId("067410")`（Enco X3）
  - 把 `setType("T1")` 保留（Enco X3 类型），`setBrand` 由「目标设备品牌」提供（见 §3）
- 现有 `common.util.Y` 全方法 finder 注入（`hookWhitelistFinderInject`）保留，多品牌时按 `device.adapter/brand` 分发（见 §3.3）。

### 2.4 弹窗入口侧（可选强化）
`LA8/E` 引用了字符串 `"checkShowConnectCapsule device not in whitelist, adr = "`；若注册+白名单后仍不弹，可进一步定位 `model/scan/g` 的 `checkShowConnectCapsule` 并 hook 其返回 true（目标地址命中）。此步为增强项，注册入表是主线，先做主线。

---

## 3. 多品牌后端路由（改造点二）

### 3.1 现状
`HeadsetSessionManager.createAdapter`（第 148–151 行）：
```kotlin
when (profile.adapter) {
    "sony" -> SonyHeadsetAdapter(profile)
    "xiberia" -> XiberiaHeadsetAdapter(profile)
    else -> throw IllegalArgumentException("Unsupported adapter ${profile.adapter}")
}
```
这是**编译期硬编码分派** —— 新增品牌需改源码 + 重编译。

### 3.2 目标：可注册路由表
新建 `headset/AdapterRegistry.kt`（或并入现有 `bridge` 包）：
```kotlin
object AdapterRegistry {
    typealias Factory = (DeviceProfile) -> HeadsetAdapter
    private val factories = ConcurrentHashMap<String, Factory>()

    fun register(adapterId: String, factory: Factory) { factories[adapterId] = factory }
    fun resolve(adapterId: String): Factory? = factories[adapterId]

    // 内置品牌自动注册
    init {
        register("sony")    { SonyHeadsetAdapter(it) }
        register("xiberia") { XiberiaHeadsetAdapter(it) }
    }
}
```
`HeadsetSessionManager.createAdapter` 改为：
```kotlin
private fun createAdapter(profile: DeviceProfile): HeadsetAdapter =
    AdapterRegistry.resolve(profile.adapter)
        ?.invoke(profile)
        ?: throw IllegalArgumentException(
            "Unsupported adapter '${profile.adapter}' (register via AdapterRegistry.register)")
```
**新品牌接入 = 一行 `register("brand"){ ... }` + 一个适配器目录实现 `HeadsetAdapter` 接口**，不必改 `HeadsetSessionManager`。

### 3.3 识别 → 适配 → 伪装 路由链（完整）
```
蓝牙发现/连接
   │  （真实 A2DP 连接成功）
   ▼
① 识别：DeviceProfiles.all.find { it.matchesForBinding(name, serviceUuids) }
   │       命中 → 得 DeviceProfile（含 adapter/brand/modelId/capabilities）
   ▼
② 适配：AdapterRegistry.resolve(profile.adapter) → HeadsetAdapter
   │       接管 RFCOMM/GATT 会话，读状态、下发命令
   ▼
③ 伪装：按 profile.adapter 选伪装 productId/型号，注册进 melody
         adapter=="sony"    → 0x067410 (Enco X3 受众)
         adapter=="xiberia" → 0x6B450 (现有 XIBERIA 伪装)
         未来品牌           → AdapterRegistry 附带的 spoofProductId 字段
   ▼
④ 呈现：melody 官方卡/弹窗显示目标设备（设备已在表 + 状态已伪造）
```

### 3.4 关键参数表（多品牌伪装映射）
| brand/adapter | 真实设备 | 伪装型号 | productId(decimal) | 伪装 id(hex string) | 会话传输 |
|---|---|---|---|---|---|
| sony | 任意 Sony TWS | OPPO Enco X3 | `422416` | `067410` | RFCOMM (A2DP 之上) |
| xiberia (cchip) | XIBERIA MC0x | （保持 XIBERIA） | `439376` | `6B450` | SPP(RFCOMM) |
| huawei(未来) | 华为 FBP 系 | 待逆向 | – | – | RFCOMM |
| samsung(未来) | Galaxy Buds 系 | 待逆向 | – | – | RFCOMM |
| xiaomi(未来) | 小米耳机 | 待逆向 | – | – | RFCOMM |
> 新品牌接入需三件套：① `HeadsetAdapter` 实现（协议）② profile（识别规则+伪装身份）③ `AdapterRegistry.register(adapterId)`（路由）。伪装 productId 选择规则：优先取**与目标设备能力最接近的官方型号**，或目标品牌自身在旋律白名单里的型号。已逆向的在 §6 注明。

---

## 4. 实施步骤（落地顺序）

1. **新建 `AdapterRegistry`**（`bridge/AdapterRegistry.kt`），迁移 `createAdapter` 的 when → registry。（§3.2，低风险，可先独立编译验证）
2. **新增 `hookDeviceRegistryInjection`**（`MelodyPanelHook.kt`）：hook `DeviceInfoManager.d/f/c` + `DeviceInfo.setDevice*ConnectState/setProductId/isConnected`，按 2.2 实现注册入表。（§2，核心）
3. **whitelist id 对齐**：`forgeWhitelistDto` 改 `067410`，brand 参数化。（§2.3）
4. **伪装参数多品牌化**：`DeviceProfile` 增加可选的 `spoofProductId` 字段（默认按 adapter 推断），`AdapterRegistry` 暴露 `spoofProductIdFor(profile)`。
5. **编译 + 打包 + 推送**（沿用既有 build 流程）。
6. **验证闭环**（见 §5）。

---

## 5. 验收标准

| 序号 | 验收项 | 通过判据 |
|---|---|---|
| 1 | 设备注册入表 | 设备真实 A2DP 连接后，melody `DeviceInfoManager` 注册表出现目标设备（可查 logcat `DeviceInfoManager createDeviceInfo SUCCESS/FAILURE`，或 `pm dump` / 模块日志） |
| 2 | 官方弹窗 | 连接第三方耳机 → melody 自动弹官方连接弹窗（capsule），不再需要自造 route |
| 3 | 官方卡片 | melody 蓝牙设备列表/详情出现目标设备，显示已连接 + 电量/降噪 |
| 4 | 状态伪造闭环 | `EarphoneDTO.getConnectionState` == 2，电量真实，降噪切换生效（RFCOMM 下发给真实耳机） |
| 5 | 多品牌路由 | 新增一个假品牌 adapter 注册到 `AdapterRegistry` 后，`createAdapter` 无 `Unsupported adapter` 异常（单测/日志验证） |
| 6 | 无回归 | 现有 sony / xiberia 连接、四 Tab UI、Hook 日志均正常 |
| 7 | 进程/作用域 | 模块对 `com.oplus.melody` 及 `com.android.bluetooth` 作用域均注入成功（HookEntry 日志无 `handleLoadPackage` 缺失） |

---

## 6. 已逆向关键凭证（安卓 16.9.1，npmcp workspace 35147598）

- `DeviceInfoManager`（`com.oplus.melody.btsdk.api.manager`）：field `a:ConcurrentHashMap`
  - `f(ILBluetoothDevice;Ljava/lang/String;Ljava/lang/String;)LDeviceInfo;` static — 创建（日志 `createDeviceInfo SUCCESS/FAILURE by`）
  - `c(LDeviceInfo;)V` — 添加注册（日志 `addDeviceInfo IGNORE`）
  - `d(BluetoothDevice;)LDeviceInfo;` static final — 查表 get（`checkGetDeviceInfo device is null!`）
  - `h(Ljava/lang/String;)LDeviceInfo;` — 按地址查
  - `i(BluetoothDevice;)V` — getOrGenerate
  - `e(LDeviceInfo;)V` static — checkUpdateConnectionState
  - `a(ILBluetoothDevice;)V` — addBluetoothDeviceWithProductId
  - `b(ILjava/lang/String;)V` — addDeviceAddressWithProductId
  - `j(ILjava/lang/String;)V` — 设 productId
- `DeviceInfo`（`com.oplus.melody.btsdk.api.data`）：
  - `setDeviceConnectState(I)V` / `setDeviceHeadsetConnectState(I)V` / `setDeviceA2dpConnectState(I)V` / `setDeviceLeAudioConnectState(I)V` / `setDeviceLeAudioConnectState(Ljava/lang/String;I)V`
  - `setProductId(I)V` / `getProductId()I` / `isConnected()Z` / `setA2dpActive(Z)V`
- whitelist 链路：`HeadsetCoreService$e` → `dto.getId()` → `Integer.parseInt(id,16)` → productId → `DeviceInfoManager.a(productId, device)`；`common.util.Y` 全 finder 方法可 hook（已实现）
- MelodyLink 常量：`WF_1000XM3_PRODUCT_ID = 0x067410`；`SONY_TEST_PROFILE_ID="067410"`，`SONY_TEST_PROFILE_NAME="OPPO Enco X3"`
- 弹窗入口线索：`A8/E.get()` 内含 `"checkShowConnectCapsule device not in whitelist, adr = "`（增强项，§2.4）

---

## 7. 风险与回退

| 风险 | 影响 | 缓解 |
|---|---|---|
| 混淆类名跨版本漂移 | `DeviceInfoManager`/`DeviceInfo`/`common.util.*` 方法可能变 | 用 `findClassOrNull`+按签名/字段回溯，一次定位多套候选；hook 全部 `runCatching` 失败级联降级（现有 safeHook 已保证） |
| 设备未真实连接就伪装 | 弹窗无数据/卡死 | 注册前校验真实 A2DP state==2（对齐 MelodyLink `isA2dpConnected`） |
| 多品牌 productId 未知 | 伪装不弹 | 先只灰度已验证的 sony/xiberia；新品牌先逆向确认官方型号 productId |
| 弹窗入口第二道白名单 | 注册+白名单后仍不弹 | 走 §2.4 增强项 hook `checkShowConnectCapsule` |
| 进程/作用域注入失败 | 全链路失效 | 检查 LSPosed 作用域含 `com.oplus.melody`；软重启重载；用 HookEntry/模块日志确认 |

---

## 8. 待确认事项（动手前拍板）
1. **优先级**：先做「设备注册入表」（弹窗根因），后做多品牌路由表？还是两者同批落地？
2. **伪装型号**：统一伪装为 OPPO Enco X3（0x067410）？还是按品牌各配各的（sony→Enco X3，xiberia→现有 6B450，其余待逆向）？
3. **是否包含 §2.4 弹窗入口 hook** 作为本轮范围？还是注册入表验证不成再补？