// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaModelProfiles.kt
// 来源: 参考模块 com.oai.huaweimelodycompat.HuaweiModelProfiles 的源码逻辑逆向复刻。
//
// 参考模块 HuaweiModelProfiles 结构（逆向，workspace 71313114）：
//   fields : BY_MAC:Map<String,Profile>, PRODUCTS:[Profile], UNKNOWN:Profile
//            CAP_ANC/CAP_ANC_EFFECT/CAP_BATTERY/CAP_CONTROL_CENTER/CAP_DUAL/CAP_EQ/
//            CAP_FIND/CAP_FIT/CAP_GAME/CAP_HIGH_QUALITY/CAP_WEAR/CAP_ZAAG_EQ
//   methods: <clinit>()（读 PRODUCTS 表）, forMac/normalize/capabilities/normalize(134 insns)
//   nested : Profile
//
// 型号数据源：XIBERIA_MODEL_TABLE.md（18 型，来自 com.cchip.desheng v1.9.26 Product 枚举真值）。

package com.melody.melodyplus.adapter.xiberia

object XiberiaModelProfiles {

    // ---------- 能力位（对应参考模块 CAP_*）----------
    const val CAP_BATTERY = 1 shl 0
    const val CAP_EQ = 1 shl 1
    const val CAP_WEAR = 1 shl 2
    const val CAP_GAME = 1 shl 3
    const val CAP_HIGH_QUALITY = 1 shl 4
    const val CAP_DUAL = 1 shl 5
    const val CAP_LEAK = 1 shl 6
    const val CAP_FIND = 1 shl 7

    /** 一个型号档案（对应参考模块 Profile）。 */
    data class Profile(
        val productId: Int,
        val broadcastCompanyId: Int,
        val model: String,
        val displayName: String,
        val capabilities: Int,
        val supported: Boolean,
    ) {
        fun has(cap: Int): Boolean = (capabilities and cap) != 0
    }

    private val UNKNOWN = Profile(
        productId = 0,
        broadcastCompanyId = 0,
        model = "UNKNOWN",
        displayName = "XIBERIA headset (unidentified)",
        capabilities = 0,
        supported = false,
    )

    /**
     * 广播 CompanyID = swap16(productId)（固件按小端序写，App 判定时 swap 回 productId）。
     * 见 XIBERIA_MODEL_TABLE.md §一 脚注。
     */
    private fun swap16(productId: Int): Int =
        ((productId and 0xFF) shl 8) or ((productId shr 8) and 0xFF)

    private fun profile(productId: Int, model: String, displayName: String, caps: Int, supported: Boolean) =
        Profile(
            productId = productId,
            broadcastCompanyId = swap16(productId),
            model = model,
            displayName = displayName,
            capabilities = caps,
            supported = supported,
        )

    private val COMMON_ON_SALE = CAP_BATTERY or CAP_EQ or CAP_WEAR or CAP_GAME or CAP_HIGH_QUALITY or CAP_LEAK

    /** 全部型号（与 Product 枚举 18 型一致）。 */
    val PRODUCTS: List<Profile> = listOf(
        profile(0x0101, "DM02BA", "DM02BA 标准版", 0, false),
        profile(0x0102, "DM02BA_TWO", "DM02BA 双金标版", 0, false),
        profile(0x0103, "DM25", "DM25", 0, false),
        profile(0x0104, "AIR_FIT", "Air Fit", 0, false),
        profile(0x0105, "DM01_TWO", "DM01双金标版", 0, false),
        profile(0x0106, "DM01_MAX", "DM01 MAX", 0, false),
        profile(0x0107, "DM02", "DM02 进阶版", 0, false),
        profile(0x0108, "AIR_CLIP", "Air Clip", 0, false),
        profile(0x0109, "DM02_BASE", "DM02 基础版", 0, false),
        profile(0x0110, "MC01", "MC01", 0, false),
        profile(0x0111, "MC02", "XIBERIA MC02", 0, false),
        profile(0x0112, "MC20", "MC20", COMMON_ON_SALE, true),
        profile(0x0113, "DM03", "DM03", COMMON_ON_SALE, true),
        profile(0x0114, "MC01_MAX", "MC01 MAX", COMMON_ON_SALE, true),
        profile(0x0115, "AS10_ANC", "AS10", COMMON_ON_SALE or CAP_FIND, true),
        profile(0x0116, "W30", "W30", COMMON_ON_SALE, true),
        profile(0x0117, "MC03", "MC03", COMMON_ON_SALE, true),
        profile(0x0118, "MC05", "MC05", COMMON_ON_SALE, true),
    )

    private val BY_MAC: MutableMap<String, Profile> = HashMap()

    /** 按 MAC 记忆已识别型号（对应参考模块 BY_MAC）。 */
    fun rememberByMac(mac: String, profile: Profile) {
        BY_MAC[mac.uppercase()] = profile
    }

    fun forMac(mac: String): Profile? = BY_MAC[mac.uppercase()]

    fun byProductId(productId: Int): Profile? =
        PRODUCTS.firstOrNull { it.productId == productId }

    /** 已知型号返回档案，未知返回 null（与 byProductId 同义，供桥接层判空用）。 */
    fun productIdOrNull(productId: Int): Profile? = byProductId(productId)

    /** productId → 6 位大写 16 进制字符串（对应 melody DTO 的 id 字段格式）。 */
    fun toHex6(productId: Int): String =
        String.format("%06X", productId and 0xFFFFFF)

    fun byModel(model: String?): Profile? {
        val m = normalize(model) ?: return null
        return PRODUCTS.firstOrNull { normalize(it.model) == m || normalize(it.displayName) == m }
    }

    fun byBroadcastCompanyId(companyId: Int): Profile? =
        PRODUCTS.firstOrNull { it.broadcastCompanyId == companyId }

    /** 只返回在售（isSupport=true）型号，用于扫描命中判定。 */
    fun supportedProductIds(): Set<Int> =
        PRODUCTS.filter { it.supported }.mapTo(linkedSetOf()) { it.productId }

    /** 对应参考模块 capabilities(...) 的判定入口：未知型号回落 UNKNOWN。 */
    fun capabilities(productId: Int): Int =
        byProductId(productId)?.capabilities ?: UNKNOWN.capabilities

    fun displayName(productId: Int): String =
        byProductId(productId)?.displayName ?: UNKNOWN.displayName

    /** 对应参考模块 normalize：去空格 + 大写。 */
    fun normalize(value: String?): String? =
        value?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
}