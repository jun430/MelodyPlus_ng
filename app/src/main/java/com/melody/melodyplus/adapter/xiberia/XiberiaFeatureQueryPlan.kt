// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaFeatureQueryPlan.kt
// 说明: 「按型号能力位分发查询」—— 对齐官方 DeviceActivity.readState() 的能力位过滤语义。
//
// 官方真源（npmcp workspace 36144653 / 55490541，com.cchip.desheng v1.9.26）：
//   1) 取型号 : Product.Companion.findProductById(deviceEntity.modelInt)
//   2) 过滤   : 只有 isSupportXxx()==true 的项才发对应 cmdGet（未支持项**根本不查**）
//   3) 逐项   : SET/GET/REPORT 三元组，读回值决定 UI 初态
//
// 本模块把「型号能力位 → 查询项」收敛为一份 plan：查询项直接由
// XiberiaProductCatalog.panelItems(productId) 派生（面板项已按能力位生成，天然与 UI 一致），
// 再按 payload 语义分类（BOOL / LEVEL / RAW），供 XiberiaFeatureBackend 逐项回读。
package com.melody.melodyplus.adapter.xiberia

import com.melody.melodyplus.adapter.xiberia.XiberiaProductCatalog.Kind

object XiberiaFeatureQueryPlan {

    /** 应答 payload 的语义（决定如何解析，而不是如何发送）。 */
    enum class PayloadKind {
        /** 尾字节 == 0x01 为开（官方 SET/GET 对称单字节）。 */
        BOOL,

        /** 尾字节为档位值（如音量档位 0..3 / 降噪档位 1..5）。 */
        LEVEL,

        /** 原样透传（EQ 全量 0x0807 等无单值语义，仅 dump 供上层解析）。 */
        RAW,
    }

    /**
     * 一条查询项。
     *
     * @param key     面板项 key（与 [XiberiaProductCatalog.PanelItem.key] 一致，便于回填 UI）。
     * @param title   面板项标题（日志 / 调试用）。
     * @param cmdGet  官方 GET 命令码（来自 panelItem.cmdGet）。
     * @param cmdSet  官方 SET 命令码（回写时用；null = 该项只读/无独立 SET）。
     * @param kind    应答解析语义。
     */
    data class Query(
        val key: String,
        val title: String,
        val cmdGet: Int,
        val cmdSet: Int?,
        val kind: PayloadKind,
    )

    /**
     * payload 语义覆盖表。
     *
     * 默认规则：`Kind.SWITCH → BOOL` / `Kind.CHOICE → LEVEL` / `Kind.PAGE → RAW`。
     * 例外在此显式声明（这些是 PAGE 形态但底层实为布尔开关的项）。
     */
    private val KIND_OVERRIDE: Map<String, PayloadKind> = mapOf(
        // LDAC 官方是「跳转子页」形态，但底层 0x0E05 GET 回 Bool（开/关）。
        "melodyplus_xi_ldac" to PayloadKind.BOOL,
        // 均衡器子页 cmdGet = 0x0807（USER_ALL_EQ_GET），回 EQ 数据块，非单值。
        "melodyplus_xi_eq" to PayloadKind.RAW,
        // 提示音选项 / 音量档位：PAGE 形态但 cmdGet = 0x0E27 回档位值 0..3 → LEVEL。
        "melodyplus_xi_prompt_tone" to PayloadKind.LEVEL,
        // 按键功能：PAGE 形态，cmdGet = 0x0314 ALL_KEY_GET 回键位表，非单值 → RAW。
        "melodyplus_xi_key_function" to PayloadKind.RAW,
    )

    private fun kindOf(item: XiberiaProductCatalog.PanelItem): PayloadKind =
        KIND_OVERRIDE[item.key] ?: when (item.kind) {
            Kind.SWITCH -> PayloadKind.BOOL
            Kind.CHOICE -> PayloadKind.LEVEL
            Kind.PAGE -> PayloadKind.RAW
        }

    /**
     * 按型号生成查询计划 —— **这就是「按型号能力位分发查询」的判定入口**。
     *
     * 与官方 `readState()` 一致：
     * - 未识别型号 / 官方未开放型号 → 空计划（官方 `Product==null` 直接 return）。
     * - 只包含该型号能力位为 true 且带 `cmdGet` 的项。
     *
     * ⚠️ 官方在 `PAGE` 类项（如按键功能）多数只发查询不等结果；本模块仅纳入带 `cmdGet` 的项。
     */
    fun planFor(productId: Int): List<Query> =
        XiberiaProductCatalog.panelItems(productId).mapNotNull { item ->
            val cmdGet = item.cmdGet ?: return@mapNotNull null
            Query(
                key = item.key,
                title = item.title,
                cmdGet = cmdGet,
                cmdSet = item.cmdSet,
                kind = kindOf(item),
            )
        }

    /** 便捷：所有可查询命令码（含型号无关的通用项，用于调试 dump）。 */
    fun commandCodesFor(productId: Int): IntArray = planFor(productId).map { it.cmdGet }.distinct().toIntArray()
}