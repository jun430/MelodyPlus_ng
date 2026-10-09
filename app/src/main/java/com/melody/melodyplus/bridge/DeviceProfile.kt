package com.melody.melodyplus.bridge

import com.melody.melodyplus.core.AncMode
import com.melody.melodyplus.core.HeadsetCapabilities
import java.util.Locale
import java.util.UUID

data class DeviceProfile(
    val id: String,
    val modelId: String,
    val displayName: String,
    val adapter: String,
    val transportType: String,
    val protocolVersion: String,
    val serviceUuid: UUID,
    val nameRegex: Regex,
    val manualBindRequired: Boolean,
    val capabilities: HeadsetCapabilities,
    val uiFeatures: DeviceUiFeatures = DeviceUiFeatures(),
    /** 伪装进 melody 注册表的官方型号 productId（十进制）。null 时按 adapter 走 AdapterRegistry 默认。 */
    val spoofProductId: Int? = null,
) {
    fun matchesForBinding(name: String?, serviceUuids: Set<UUID>): Boolean {
        if (!nameRegex.matches(name.orEmpty())) return false
        return serviceUuid in serviceUuids
    }

    fun matchesName(name: String?): Boolean =
        nameRegex.matches(name.orEmpty())
}

data class DeviceUiFeatures(
    val noiseReductionStrength: Boolean = false,
    val multiDeviceConnect: Boolean = false,
    val personalizedNoise: Boolean = false,
    val customEq: Boolean = false,
    val smartBluetooth: Boolean = false,
)

object DeviceProfiles {
    const val SONY_WF1000XM3 = "sony.wf1000xm3"

    private const val SONY_V1_UUID = "96cc203e-5068-46ad-b32d-e316f5e069ba"
    private const val SONY_V2_UUID = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"

    private fun sonyProfile(
        name: String,
        protocolVersion: String = "v2",
        nameRegex: Regex = Regex(".*${Regex.escape(name)}.*", RegexOption.IGNORE_CASE),
        singleBattery: Boolean = false,
        dualBattery: Boolean = false,
        caseBattery: Boolean = false,
        ambientSound: Boolean = false,
        noiseCancelling: Boolean = ambientSound,
        windReduction: Boolean = false,
        dsee: Boolean = false,
    ): DeviceProfile {
        val ancModes = buildSet {
            if (ambientSound) {
                add(AncMode.OFF)
                if (noiseCancelling) add(AncMode.NOISE_CANCELLING)
                add(AncMode.TRANSPARENCY)
                if (windReduction) add(AncMode.WIND_REDUCTION)
            }
        }
        return DeviceProfile(
            id = "sony.${name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")}",
            modelId = name.replace(Regex("[^A-Za-z0-9]+"), ""),
            displayName = if (name.startsWith("Sony ", ignoreCase = true)) name else "Sony $name",
            adapter = "sony",
            transportType = "rfcomm",
            protocolVersion = protocolVersion,
            serviceUuid = UUID.fromString(if (protocolVersion == "v1") SONY_V1_UUID else SONY_V2_UUID),
            nameRegex = nameRegex,
            manualBindRequired = false,
            capabilities = HeadsetCapabilities(
                ancModes = ancModes,
                supportsAmbientLevel = ambientSound,
                supportsSingleBattery = singleBattery,
                supportsLeftRightBattery = dualBattery,
                supportsCaseBattery = caseBattery,
                supportsDsee = dsee,
            ),
        )
    }

    val sonyLinkBuds = sonyProfile(
        name = "LinkBuds",
        nameRegex = Regex("^LinkBuds$", RegexOption.IGNORE_CASE),
        dualBattery = true,
        caseBattery = true,
        dsee = true,
    )
    val sonyLinkBudsS = sonyProfile(
        name = "LinkBuds S",
        dualBattery = true,
        caseBattery = true,
        ambientSound = true,
        dsee = true,
    )
    val sonyUlt = sonyProfile(
        name = "Sony ULT",
        nameRegex = Regex("^(Sony ULT|ULT WEAR)$", RegexOption.IGNORE_CASE),
        singleBattery = true,
        ambientSound = true,
    )
    val sonyWf1000Xm3 = sonyProfile(
        name = "WF-1000XM3",
        dualBattery = true,
        caseBattery = true,
        ambientSound = true,
        windReduction = true,
        dsee = true,
    )
    val sonyWf1000Xm4 = sonyProfile(
        name = "WF-1000XM4",
        dualBattery = true,
        caseBattery = true,
        ambientSound = true,
        windReduction = true,
        dsee = true,
    )
    val sonyWf1000Xm5 = sonyProfile(
        name = "WF-1000XM5",
        dualBattery = true,
        caseBattery = true,
        ambientSound = true,
        dsee = true,
    )
    val sonyWf1000Xm6 = sonyProfile(
        name = "WF-1000XM6",
        dualBattery = true,
        caseBattery = true,
        ambientSound = true,
        dsee = true,
    )
    val sonyWfC500 = sonyProfile(name = "WF-C500", dualBattery = true, dsee = true)
    val sonyWfC510 = sonyProfile(
        name = "WF-C510",
        dualBattery = true,
        caseBattery = true,
        ambientSound = true,
        noiseCancelling = false,
        dsee = true,
    )
    val sonyWfC700N = sonyProfile(
        name = "WF-C700N",
        dualBattery = true,
        caseBattery = true,
        ambientSound = true,
        dsee = true,
    )
    val sonyWfC710N = sonyProfile(
        name = "WF-C710N",
        dualBattery = true,
        caseBattery = true,
        ambientSound = true,
        dsee = true,
    )
    val sonyWfSp800N = sonyProfile(
        name = "WF-SP800N",
        dualBattery = true,
        caseBattery = true,
        ambientSound = true,
    )
    val sonyWh1000Xm2 = sonyProfile(
        name = "WH-1000XM2",
        protocolVersion = "v1",
        singleBattery = true,
        ambientSound = true,
        windReduction = true,
        dsee = true,
    )
    val sonyWh1000Xm3 = sonyProfile(
        name = "WH-1000XM3",
        protocolVersion = "v1",
        singleBattery = true,
        ambientSound = true,
        windReduction = true,
        dsee = true,
    )
    val sonyWh1000Xm4 = sonyProfile(
        name = "WH-1000XM4",
        protocolVersion = "v1",
        singleBattery = true,
        ambientSound = true,
        windReduction = true,
        dsee = true,
    )
    val sonyWh1000Xm5 = sonyProfile(
        name = "WH-1000XM5",
        singleBattery = true,
        ambientSound = true,
        dsee = true,
    )
    val sonyWh1000Xm6 = sonyProfile(
        name = "WH-1000XM6",
        singleBattery = true,
        ambientSound = true,
        dsee = true,
    )
    val sonyWhCh720N = sonyProfile(
        name = "WH-CH720N",
        singleBattery = true,
        ambientSound = true,
        dsee = true,
    )
    val sonyWhXb900N = sonyProfile(
        name = "WH-XB900N",
        protocolVersion = "v1",
        singleBattery = true,
        ambientSound = true,
        dsee = true,
    )
    val sonyWhXb910N = sonyProfile(
        name = "WH-XB910N",
        singleBattery = true,
        ambientSound = true,
    )
    val sonyWiC100 = sonyProfile(name = "WI-C100", singleBattery = true, dsee = true)
    val sonyWiSp600N = sonyProfile(
        name = "WI-SP600N",
        protocolVersion = "v1",
        singleBattery = true,
        ambientSound = true,
    )

    // ==================== 官方耳机（伪装身份 / 调试注入，单一真源） ====================
    // OPPO Enco X3 = 模块统一伪装身份（productId 0x067410）。
    // 唯一实例见下方 officialEncoX3：不进 all / 白名单，仅用于调试注入与 get("oppo.encox3") 解析。
    // （历史重复的 oppoEncoX3 已删除：其 id 同为 "oppo.encox3"，导致 findByName 与 get 返回不同对象。）
    // ==================== 官方耳机（伪装目标机型 / 调试注入） ====================
    /**
     * 官方伪装目标机型：OPPO Enco X3（productId 0x067410）。
     *
     * 仅用于「调试注入官方耳机弹窗」与作为统一伪装身份展示；
     * **不放进 [all]**，因此不参与真机白名单名称匹配（避免把真·官方设备误判为模块设备）。
     */
    val officialEncoX3 = DeviceProfile(
        id = "oppo.encox3",
        modelId = "EncoX3",
        displayName = "OPPO Enco X3",
        adapter = "official",
        transportType = "rfcomm",
        protocolVersion = "official",
        serviceUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
        nameRegex = Regex("^OPPO Enco X3$", RegexOption.IGNORE_CASE),
        manualBindRequired = false,
        capabilities = HeadsetCapabilities(
            ancModes = setOf(AncMode.OFF, AncMode.NOISE_CANCELLING, AncMode.TRANSPARENCY),
            supportsAmbientLevel = true,
            supportsSingleBattery = false,
            supportsLeftRightBattery = true,
            supportsCaseBattery = true,
            supportsDsee = true,
        ),
        // 官方伪装目标产品号（十进制）= OPPO Enco X3 0x067410
        spoofProductId = 0x067410,
    )

    private fun xiberiaProfile(
        name: String,
        nameRegex: Regex = Regex(".*${Regex.escape(name)}.*", RegexOption.IGNORE_CASE),
        leakSuppress: Boolean = true,
        gameMode: Boolean = true,
        ldac: Boolean = true,
    ): DeviceProfile {
        // MC05 无 ANC，ancModes 用枚举承载「模式」语义：
        //   OFF=普通 / NOISE_CANCELLING=漏音抑制 / ADAPTIVE=游戏低延迟
        val ancModes = buildSet {
            add(AncMode.OFF)
            if (leakSuppress) add(AncMode.NOISE_CANCELLING)
            if (gameMode) add(AncMode.ADAPTIVE)
        }
        return DeviceProfile(
            id = "xiberia.${name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")}",
            modelId = name.replace(Regex("[^A-Za-z0-9]+"), ""),
            displayName = if (name.startsWith("XIBERIA", ignoreCase = true)) name else "XIBERIA $name",
            adapter = "xiberia",
            transportType = "rfcomm",
            protocolVersion = "cchip-v1",
            serviceUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
            nameRegex = nameRegex,
            manualBindRequired = false,
            capabilities = HeadsetCapabilities(
                ancModes = ancModes,
                supportsAmbientLevel = true, // 语义复用为提示音档位
                // 官方 parseBattery 返回左/右/仓三态 → 打开分体电量与仓电量位
                supportsSingleBattery = false,
                supportsLeftRightBattery = true,
                supportsCaseBattery = true,
                supportsDsee = ldac,          // 语义复用为 LDAC
            ),
        )
    }

    val xiberiaMc05 = xiberiaProfile(
        name = "MC05",
        nameRegex = Regex("^XIBERIA.*MC05.*$|^MC05.*$", RegexOption.IGNORE_CASE),
    )
    // 全系共用 cchip 协议。官方 18 型中 isSupport=true 的 7 型全部建档（判定见 XiberiaProductCatalog）；
    // 其余 11 型在官方 App 内无能力覆写或整体不开放，这里也建档案以便「设备名命中即正常接管 UI」。
    val xiberiaMc01 = xiberiaProfile(name = "MC01")
    val xiberiaMc02 = xiberiaProfile(name = "MC02")
    val xiberiaMc20 = xiberiaProfile(name = "MC20")
    val xiberiaMc03 = xiberiaProfile(name = "MC03")
    val xiberiaMc01Max = xiberiaProfile(name = "MC01 MAX")
    val xiberiaAs10 = xiberiaProfile(name = "AS10")
    val xiberiaW30 = xiberiaProfile(name = "W30")
    val xiberiaDm03 = xiberiaProfile(name = "DM03")
    // 官方无能力覆写 / 整体不开放的型号（仅用于名称识别，面板项由 catalog 判空 → 不注入）
    val xiberiaDm02ba = xiberiaProfile(name = "DM02BA")
    val xiberiaDm02baTwo = xiberiaProfile(
        name = "DM02BA_TWO",
        nameRegex = Regex("^XIBERIA.*(DM02BA[_ ]?TWO|DM02BA双金标).*$", RegexOption.IGNORE_CASE),
    )
    val xiberiaDm25 = xiberiaProfile(name = "DM25")
    val xiberiaAirFit = xiberiaProfile(
        name = "AIR FIT",
        nameRegex = Regex("^XIBERIA.*AIR[_ ]?FIT.*$|^Air Fit.*$", RegexOption.IGNORE_CASE),
    )
    val xiberiaDm01Two = xiberiaProfile(
        name = "DM01_TWO",
        nameRegex = Regex("^XIBERIA.*(DM01[_ ]?TWO|DM01双金标).*$", RegexOption.IGNORE_CASE),
    )
    val xiberiaDm01Max = xiberiaProfile(name = "DM01 MAX")
    val xiberiaDm02 = xiberiaProfile(name = "DM02")
    val xiberiaAirClip = xiberiaProfile(
        name = "AIR CLIP",
        nameRegex = Regex("^XIBERIA.*AIR[_ ]?CLIP.*$|^Air Clip.*$", RegexOption.IGNORE_CASE),
    )
    val xiberiaDm02Base = xiberiaProfile(
        name = "DM02_BASE",
        nameRegex = Regex("^XIBERIA.*DM02[_ ]?BASE.*$", RegexOption.IGNORE_CASE),
    )

    val all: List<DeviceProfile> = listOf(
        // XIBERIA：**长名优先**（`MC01 MAX` / `DM01 MAX` 必须先于 `MC01` / `DM01` 命中，
        //   否则 findByName 会把 "XIBERIA MC01 MAX" 误判成 MC01）。
        xiberiaMc05,
        xiberiaMc01Max,
        xiberiaMc01,
        xiberiaMc02,
        xiberiaMc20,
        xiberiaMc03,
        xiberiaAs10,
        xiberiaW30,
        xiberiaDm03,
        xiberiaDm02baTwo,
        xiberiaDm02ba,
        xiberiaDm25,
        xiberiaAirFit,
        xiberiaDm01Two,
        xiberiaDm01Max,
        xiberiaDm02Base,
        xiberiaDm02,
        xiberiaAirClip,
        sonyLinkBuds,
        sonyLinkBudsS,
        sonyUlt,
        sonyWf1000Xm3,
        sonyWf1000Xm4,
        sonyWf1000Xm5,
        sonyWf1000Xm6,
        sonyWfC500,
        sonyWfC510,
        sonyWfC700N,
        sonyWfC710N,
        sonyWfSp800N,
        sonyWh1000Xm2,
        sonyWh1000Xm3,
        sonyWh1000Xm4,
        sonyWh1000Xm5,
        sonyWh1000Xm6,
        sonyWhCh720N,
        sonyWhXb900N,
        sonyWhXb910N,
        sonyWiC100,
        sonyWiSp600N,
    )

    val enabledProfiles: Map<String, DeviceProfile> = all.associateBy { it.id }
    val defaultNames: Set<String> = all.flatMapTo(linkedSetOf()) { profile ->
        if (profile === sonyUlt) listOf("Sony ULT", "ULT WEAR") else listOf(profile.displayName.removePrefix("Sony "))
    }
    private val aliases: Map<String, DeviceProfile> = all.flatMap { profile ->
        listOf(profile.id, profile.modelId, profile.displayName, profile.displayName.removePrefix("Sony "))
            .map { it.lowercase(Locale.US) to profile }
    }.toMap() + mapOf(
        "ult wear" to sonyUlt,
        // 官方伪装目标机型（调试注入用），不进 all/白名单，但可通过 get() 解析
        AdapterRegistry.OFFICIAL_PROFILE_ID to officialEncoX3,
    )

    fun get(profileId: String?): DeviceProfile? =
        aliases[profileId?.lowercase(Locale.US)]

    fun findByName(name: String?): DeviceProfile? =
        name?.let { candidate -> all.firstOrNull { it.matchesName(candidate) } }
}
