// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaCatalog.kt
// 来源: 参考模块 PopupModelCatalog / ControlTemplateCatalog / PopupSelection /
//       ControlTemplateSelection / NativeProfileCatalog 的源码逻辑逆向复刻。
//
// 参考模块（逆向，workspace 71313114）：
//   PopupModelCatalog   (+Product/Variant)    ← assets/melody_popup_models.tsv（261 行）
//   ControlTemplateCatalog (+Template)        ← assets/melody_control_templates.tsv（16 行）
//   PopupSelection / ControlTemplateSelection ← 用户当前选择
//   NativeProfileCatalog: load(apkPath) 读 assets/melody_template_profiles.json，
//       get(id, cl) 反射构造 com.oplus.melody.common.data.WhitelistConfigDTO
//
// 本实现复刻「读表 → 建目录 → 按 id 选条目」的数据层逻辑（不含 DTO 反射构造，
// 那部分由 hook 层 WhitelistConfig 注入负责）。

package com.melody.melodyplus.adapter.xiberia

import java.io.BufferedReader
import java.io.InputStream

object XiberiaCatalog {

    // ---------- 弹窗型号目录（melody_popup_models.tsv）----------

    data class Variant(val productId: String, val colorId: Int, val hasBootMp4: Boolean)

    data class Product(
        val productId: String,
        val modelName: String,
        val variants: List<Variant>,
    )

    /** 解析 popup_models.tsv：productId \t modelName \t colorId \t hasBootMp4 */
    fun parsePopupModels(input: InputStream): List<Product> {
        val grouped = linkedMapOf<String, MutableList<Variant>>()
        val names = linkedMapOf<String, String>()
        input.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val cols = line.split('\t')
                if (cols.size < 4) return@forEach
                val id = cols[0].trim()
                val name = cols[1].trim()
                val color = cols[2].trim().toIntOrNull() ?: return@forEach
                val boot = cols[3].trim() == "1"
                names[id] = name
                grouped.getOrPut(id) { mutableListOf() } += Variant(id, color, boot)
            }
        }
        return grouped.map { (id, variants) ->
            Product(productId = id, modelName = names[id] ?: id, variants = variants)
        }
    }

    // ---------- 控制中心模板目录（melody_control_templates.tsv）----------

    data class Template(val productId: String, val modelName: String, val verifiedResourceColor: Int)

    /** 解析 control_templates.tsv：productId \t modelName \t verifiedResourceColor */
    fun parseControlTemplates(input: InputStream): List<Template> {
        val result = mutableListOf<Template>()
        input.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val cols = line.split('\t')
                if (cols.size < 3) return@forEach
                val color = cols[2].trim().toIntOrNull() ?: return@forEach
                result += Template(cols[0].trim(), cols[1].trim(), color)
            }
        }
        return result
    }

    // ---------- 原生白名单 profile（melody_template_profiles.json）----------

    /** 一条白名单 profile 的最小投影（id/name/supportSpp/uuid/minVersion）。 */
    data class NativeProfile(
        val id: String,
        val name: String,
        val supportSpp: Boolean,
        val uuid: String?,
        val minVersion: Int,
        val raw: String,
    )

    private val ID_REGEX = Regex("[0-9A-F]{6}")

    /**
     * 解析 melody_template_profiles.json（顶层为 JSON 数组）。
     * 对应参考模块 NativeProfileCatalog.read(String)：校验 id 形如 [0-9A-F]{6}，
     * 非法条目抛错并跳过。
     */
    fun parseNativeProfiles(json: String): Map<String, NativeProfile> {
        val out = linkedMapOf<String, NativeProfile>()
        val array = org.json.JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.optString("id").uppercase()
            if (!ID_REGEX.matches(id)) {
                throw IllegalArgumentException("Invalid bundled profile id=$id")
            }
            out[id] = NativeProfile(
                id = id,
                name = obj.optString("name"),
                supportSpp = obj.optBoolean("supportSpp", false),
                uuid = obj.optString("uuid").takeIf { it.isNotBlank() },
                minVersion = obj.optInt("minVersion", 0),
                raw = obj.toString(),
            )
        }
        return out
    }
}

/** 对应参考模块 PopupSelection：写死默认 = Enco Free4 / 068C10 / color1。 */
data class PopupSelection(
    val productId: String = DEFAULT_POPUP_ID,
    val modelName: String = DEFAULT_POPUP_NAME,
    val colorId: Int = DEFAULT_POPUP_COLOR,
) {
    companion object {
        const val DEFAULT_POPUP_ID = "068C10"
        const val DEFAULT_POPUP_NAME = "OPPO Enco Free4"
        const val DEFAULT_POPUP_COLOR = 1
    }
}

/** 对应参考模块 ControlTemplateSelection：默认 = Enco X3 / 067410。 */
data class ControlTemplateSelection(
    val productId: String = DEFAULT_CONTROL_TEMPLATE_ID,
    val modelName: String = DEFAULT_CONTROL_TEMPLATE_NAME,
) {
    companion object {
        const val DEFAULT_CONTROL_TEMPLATE_ID = "067410"
        const val DEFAULT_CONTROL_TEMPLATE_NAME = "OPPO Enco X3"
        const val DEFAULT_CONTROL_TEMPLATE_PRODUCT_ID = 0x067410
    }
}