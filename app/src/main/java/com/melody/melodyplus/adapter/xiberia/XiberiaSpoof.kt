// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaSpoof.kt
// 作用: hook 层 / bridge 层与 XIBERIA 数据目录之间的唯一桥接点。
//       把原先散落在 hook 与 AdapterRegistry 里的「硬编码型号常量」收敛到这里，
//       对应参考模块 XiberiaModelProfiles + ControlTemplateSelection 的用法。
//
// 参考模块对应关系：
//   defaultModelName()      ← ControlTemplateSelection.DEFAULT_CONTROL_TEMPLATE_NAME
//   spoofProductIdHex()     ← ControlTemplateSelection.DEFAULT_CONTROL_TEMPLATE_ID
//   isSupported(productId)  ← HuaweiModelProfiles.capabilities()/PRODUCTS(isSupport)
//   capabilitiesOf(id)      ← HuaweiModelProfiles.capabilities(int)

package com.melody.melodyplus.adapter.xiberia

import com.melody.melodyplus.bridge.AdapterRegistry

object XiberiaSpoof {

    /** 默认伪装型号名（对应 ControlTemplateSelection 的 Enco X3）。 */
    fun defaultModelName(): String = ControlTemplateSelection.DEFAULT_CONTROL_TEMPLATE_NAME

    /** 默认伪装 productId 的 16 进制字符串（对应 ControlTemplateSelection.DEFAULT_CONTROL_TEMPLATE_ID）。 */
    fun spoofProductIdHex(): String = ControlTemplateSelection.DEFAULT_CONTROL_TEMPLATE_ID

    /** 默认伪装 productId（十进制）。 */
    fun spoofProductId(): Int = ControlTemplateSelection.DEFAULT_CONTROL_TEMPLATE_PRODUCT_ID

    /** 该伪装 productId 是否为模块原生目录中的在售型号（对应 capabilities 判定）。 */
    fun isSupported(productId: Int = spoofProductId()): Boolean =
        XiberiaModelProfiles.productIdOrNull(productId)?.supported ?: false

    /** 取伪装型号的能力位（对应 HuaweiModelProfiles.capabilities）。 */
    fun capabilitiesOf(productId: Int = spoofProductId()): Int =
        XiberiaModelProfiles.capabilities(productId)

    /** 取伪装型号档案。 */
    fun profileOf(productId: Int = spoofProductId()): XiberiaModelProfiles.Profile? =
        XiberiaModelProfiles.byProductId(productId)

    /** 设备名 → 应伪装的成对（hex, name）。未知回落 Enco X3（Enco X3 与 AdapterRegistry 一致）。 */
    fun resolveSpoof(deviceName: String?): Pair<String, String> {
        val model = XiberiaModelProfiles.byModel(deviceName)
        if (model != null) {
            return XiberiaModelProfiles.toHex6(model.productId) to model.displayName
        }
        return spoofProductIdHex() to defaultModelName()
    }

    /** 兼容入口：与 AdapterRegistry 保持同源（避免两处常量漂移）。 */
    fun registryDefaultHex(): String = AdapterRegistry.DEFAULT_SPOOF_ID_HEX
}
