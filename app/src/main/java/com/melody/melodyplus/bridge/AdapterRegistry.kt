package com.melody.melodyplus.bridge

import com.melody.melodyplus.adapter.sony.SonyHeadsetAdapter
import com.melody.melodyplus.adapter.xiberia.XiberiaHeadsetAdapter
import com.melody.melodyplus.core.HeadsetAdapter
import java.util.concurrent.ConcurrentHashMap

/**
 * 多品牌耳机适配器路由表。
 *
 * 将原 [HeadsetSessionManager.createAdapter] 的编译期 when 硬编码分派，升级为
 * 运行期可注册的路由表：新品牌只需 `AdapterRegistry.register("brand") { profile -> ... }`
 * + 一个实现 [HeadsetAdapter] 的适配器，无需改动会话管理器。
 *
 * 伪装 productId（十进制）随 adapter 绑定：统一伪造为 OPPO Enco X3（0x067410 = 422416）。
 * 单一真源在 [com.melody.melodyplus.adapter.xiberia.XiberiaSpoof]（其常量又来自
 * XiberiaCatalog.ControlTemplateSelection，对应参考模块 ControlTemplateSelection）。
 */
object AdapterRegistry {
    typealias Factory = (DeviceProfile) -> HeadsetAdapter

    /** 默认伪装型号：OPPO Enco X3，productId 十进制 422416 = 0x067410。 */
    const val DEFAULT_SPOOF_PRODUCT_ID = 0x067410
    const val DEFAULT_SPOOF_ID_HEX = "067410"
    /** 官方伪装目标机型的 profileId（调试注入默认身份）。 */
    const val OFFICIAL_PROFILE_ID = "oppo.encox3"

    private val factories = ConcurrentHashMap<String, Factory>()

    /** adapter 绑定的伪装 productId（十进制）。未显式配置时回落到默认 Enco X3。 */
    private val spoofProductIds = ConcurrentHashMap<String, Int>()

    init {
        register("sony") { SonyHeadsetAdapter(it) }
        register("xiberia") { XiberiaHeadsetAdapter(it) }
        // 统一伪装为 OPPO Enco X3（用户拍板：所有品牌一律 0x067410）
        // xiberia 语义上也可伪装 Enco X3；如需独立可在此覆盖
        spoofProductIds["sony"] = DEFAULT_SPOOF_PRODUCT_ID
        spoofProductIds["xiberia"] = DEFAULT_SPOOF_PRODUCT_ID
        // official：官方伪装目标机型（OPPO Enco X3）自身，虚拟注入走这条
        spoofProductIds["official"] = DEFAULT_SPOOF_PRODUCT_ID
    }

    fun register(adapterId: String, factory: Factory) {
        factories[adapterId] = factory
    }

    /** 可选：为某 adapter 单独指定伪装 productId（十进制）。不调用则用默认 Enco X3。 */
    fun registerSpoofProductId(adapterId: String, productId: Int) {
        spoofProductIds[adapterId] = productId
    }

    fun resolve(adapterId: String): Factory? = factories[adapterId]

    fun supportedAdapters(): Set<String> = factories.keys

    /**
     * 计算某 profile 应伪装进 melody 的 productId（十进制）：
     * 1. profile.spoofProductId 显式指定 > 2. adapter 注册值 > 3. 默认 Enco X3。
     */
    fun spoofProductIdFor(profile: DeviceProfile): Int =
        profile.spoofProductId
            ?: spoofProductIds[profile.adapter]
            ?: DEFAULT_SPOOF_PRODUCT_ID

    /** 伪装 id 的 16 进制字符串形式（供 whitelist DTO 使用）。
     *  固定 6 位大写：与 melody 原生 profile id 形如 [0-9A-F]{6} 的约定一致，
     *  避免 0x067410 → "67410"（少位）被下游 parseInt 解析成错误型号。 */
    fun spoofIdHexFor(profile: DeviceProfile): String =
        String.format("%06X", spoofProductIdFor(profile))
}