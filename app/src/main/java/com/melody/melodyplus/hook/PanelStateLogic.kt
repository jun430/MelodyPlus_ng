package com.melody.melodyplus.hook

import com.melody.melodyplus.adapter.xiberia.XiberiaProductCatalog

/**
 * 电量槽位（纯模型）。
 *
 * P3 结构拆分产物：从 [MelodyPanelHook] 抽出的纯逻辑，不含 Hook/反射/Android 依赖，
 * 便于单元测试与复用；[MelodyPanelHook] 侧保留同名私有薄委托，调用点零改动。
 */
enum class BatterySlot { LEFT, RIGHT, CASE, SINGLE }

/**
 * 面板绑定上下文（代际 + MAC + Profile + productId）。
 *
 * P3 拆分：从 [MelodyPanelHook] 抽出的纯模型 + 纯比较逻辑（无可变状态、无 Hook 依赖），
 * Hook 侧仍持有 `panelGeneration/activeModuleAddress/activeProfileId/activePanelProductId` 真值。
 */
data class PanelContext(
    val generation: Long,
    val mac: String,
    val profileId: String,
    val productId: Int,
) {
    /**
     * 与给定「当前面板状态」全等才算仍是当前面板；不匹配时调用方应丢弃异步结果
     * （记 `stale_result_dropped`），不得刷新 View / 污染缓存。
     */
    fun isCurrent(
        generation: Long,
        mac: String?,
        profileId: String?,
        productId: Int,
    ): Boolean =
        this.generation == generation &&
            this.mac == mac &&
            this.profileId == profileId &&
            this.productId == productId
}

/**
 * 宿主耳机 DTO 成员名解析（纯逻辑）。
 *
 * 覆盖 [MelodyPanelHook] 反射读写电量/充电字段时的成员名规范化与归类，
 * 早期以 `private fun` 硬编码在 Hook 内，测试无法触达；现抽为独立对象。
 */
object MelodyMemberNames {

    /** 连接状态字段名判定（connectionState / connectState / sppConnectionState）。 */
    fun isConnectionStateName(name: String): Boolean {
        val normalized = name.lowercase()
        return "connectionstate" in normalized ||
            "connectstate" in normalized ||
            "sppconnectionstate" in normalized
    }

    /** 连接布尔字段白名单：直接视为「已连接」语义的 getter/字段名。 */
    private val CONNECTED_BOOLEAN_NAMES = setOf(
        "getisspp",
        "issupportspp",
        "isactive",
        "isdevicebonded",
        "isinitcmdcompleted",
        "iscapabilityready",
        "isconnectedshown",
        "isconnected",
    )

    /**
     * 连接布尔字段名判定：命中白名单，或名称含 `connected` 且不含 `disconnect`。
     * （`disconnected` / `isDisconnected` 必须排除，否则会把「未连接」也当成「已连接」强制置真。）
     */
    fun isConnectedBooleanName(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized in CONNECTED_BOOLEAN_NAMES ||
            ("connected" in normalized && !normalized.contains("disconnect"))
    }

    private val CHARGING_KEYS = setOf(
        "boxcharging",
        "casecharging",
        "leftcharging",
        "rightcharging",
        "isboxcharging",
        "isleftcharging",
        "isrightcharging",
        "getisboxcharging",
        "getisleftcharging",
        "getisrightcharging",
    )

    private val BATTERY_KEYS = setOf(
        "leftbattery",
        "leftbatterylevel",
        "headsetleftbattery",
        "headsetleftbatterylevel",
        "rightbattery",
        "rightbatterylevel",
        "headsetrightbattery",
        "headsetrightbatterylevel",
        "boxbattery",
        "boxbatterylevel",
        "casebattery",
        "casebatterylevel",
        "headsetboxbattery",
        "headsetboxbatterylevel",
        "battery",
        "batterylevel",
        "singlebattery",
        "singlebatterylevel",
    )

    /**
     * 归一化成员/方法名：去 `get`/`set` 前缀、去 `m`+驼峰前缀，转小写。
     * 例：`getLeftBatteryLevel` → `leftbatterylevel`，`mBoxCharging` → `boxcharging`。
     */
    fun normalizedMemberKey(name: String): String {
        var key = name
        if (key.startsWith("get")) key = key.removePrefix("get")
        if (key.startsWith("set")) key = key.removePrefix("set")
        if (key.startsWith("m") && key.length > 1 && key[1].isUpperCase()) key = key.drop(1)
        return key.lowercase()
    }

    /** 是否为充电状态字段（含 `m`/`is`/`get` 变体）。 */
    fun isChargingKey(key: String): Boolean = key in CHARGING_KEYS

    /** 是否为充电状态字段（入参为原始成员名，内部先归一化）。 */
    fun isChargingName(name: String): Boolean = isChargingKey(normalizedMemberKey(name))

    /** 是否为电量字段（入参应为归一化 key）。 */
    fun isBatteryMemberKey(key: String): Boolean = key in BATTERY_KEYS

    /**
     * 归一化 key → 电量槽位。全不匹配返回 null（视为非电量字段）。
     *
     * 注意：本函数只做**归类**，实际取值的单/双耳回退（如 `left ?: single`）留在 Hook 侧，
     * 因为它依赖运行时的 [com.melody.melodyplus.core.BatteryState]。
     */
    fun batterySlotForMemberKey(key: String): BatterySlot? = when (key) {
        "leftbattery",
        "leftbatterylevel",
        "headsetleftbattery",
        "headsetleftbatterylevel",
        -> BatterySlot.LEFT

        "rightbattery",
        "rightbatterylevel",
        "headsetrightbattery",
        "headsetrightbatterylevel",
        -> BatterySlot.RIGHT

        "boxbattery",
        "boxbatterylevel",
        "casebattery",
        "casebatterylevel",
        "headsetboxbattery",
        "headsetboxbatterylevel",
        -> BatterySlot.CASE

        "battery",
        "batterylevel",
        "singlebattery",
        "singlebatterylevel",
        -> BatterySlot.SINGLE

        else -> null
    }
}

/** 连接字段分类（纯逻辑，不含反射句柄）。 */
enum class ConnectedFieldKind {
    INT_STATE,
    BOOLEAN_CONNECTED,
    CONNECT_STATE_ENUM,
    CONNECTION_MAP,
    CONNECTION_OBJECT,
}

/** 电量/充电字段分类结果：batteryKey 为归一化电量键（非电量字段为 null）。 */
data class BatteryFieldClassification(
    val batteryKey: String?,
    val charging: Boolean,
)

/**
 * 反射字段分类器（纯逻辑）。
 *
 * 从 [MelodyPanelHook] 的 `connectedFieldPlansFor` / `batteryFieldPlansFor` 抽出「字段名 + 类型 →
 * 归类」判定，Hook 侧只保留 `Field` 句柄装配与 `isAccessible`。这样字段识别规则（哪些名字算
 * 连接态、哪些算电量/充电）可脱离反射单测，避免误加名字导致强制置真/置电量。
 */
object ReflectiveFieldClassifier {

    /**
     * 连接字段归类；不匹配任何规则返回 null。
     *
     * 判定顺序与 Hook 原实现一致：INT_STATE → BOOLEAN_CONNECTED → CONNECT_STATE_ENUM →
     * CONNECTION_MAP → CONNECTION_OBJECT。最后一项只按类型名匹配，不看字段名。
     */
    fun connectedKind(
        fieldName: String,
        fieldTypeName: String,
        isInt: Boolean,
        isBoolean: Boolean,
        isMap: Boolean,
        connectStateClassName: String,
        connectionClassName: String,
    ): ConnectedFieldKind? = when {
        isInt && MelodyMemberNames.isConnectionStateName(fieldName) ->
            ConnectedFieldKind.INT_STATE
        isBoolean && MelodyMemberNames.isConnectedBooleanName(fieldName) ->
            ConnectedFieldKind.BOOLEAN_CONNECTED
        fieldTypeName == connectStateClassName && MelodyMemberNames.isConnectionStateName(fieldName) ->
            ConnectedFieldKind.CONNECT_STATE_ENUM
        isMap && MelodyMemberNames.isConnectionStateName(fieldName) ->
            ConnectedFieldKind.CONNECTION_MAP
        fieldTypeName == connectionClassName ->
            ConnectedFieldKind.CONNECTION_OBJECT
        else -> null
    }

    /** 电量/充电字段归类；非电量且非充电返回 null。 */
    fun batteryClassification(
        fieldName: String,
        isInt: Boolean,
        isBoolean: Boolean,
    ): BatteryFieldClassification? {
        val key = MelodyMemberNames.normalizedMemberKey(fieldName)
        val batteryKey = if (isInt && MelodyMemberNames.isBatteryMemberKey(key)) key else null
        val charging = isBoolean && MelodyMemberNames.isChargingKey(key)
        if (batteryKey == null && !charging) return null
        return BatteryFieldClassification(batteryKey, charging)
    }
}

/**
 * Xiberia 面板 GET 命令选择与 CHOICE 应答解析（纯逻辑）。
 *
 * 从 [MelodyPanelHook] 抽出：这两处是「面板主动查询」的正确性核心，且完全无 Hook 依赖。
 */
object XiberiaPanelQuery {

    /**
     * 该面板项对应的 GET 命令码；非 SWITCH/CHOICE 型（如 PAGE）返回 null。
     *
     * 单一真源：直接取自 catalog 的 [XiberiaProductCatalog.PanelItem.cmdGet]，不再 `when(item.key)`
     * 硬编码（旧实现与 catalog 双真源漂移，导致 noise/lhdc 等项永不发查询、面板停在假态）。
     * 只对 SWITCH / CHOICE 探测：PAGE 型无布尔/档位态，应答尾字节会误判污染缓存。
     */
    /** LDAC 子页 key：PAGE 型但具备 00/01 布尔语义，须参与回读（唯一例外）。 */
    private const val LDAC_PAGE_KEY: String = "melodyplus_xi_ldac"
    fun queryCommandFor(item: XiberiaProductCatalog.PanelItem): Int? =
        when (item.kind) {
            XiberiaProductCatalog.Kind.SWITCH,
            XiberiaProductCatalog.Kind.CHOICE,
            -> item.cmdGet
            // [修复·LDAC 假态] PAGE 型默认无布尔/档位态，应答尾字节会误判污染缓存；
            //   但 LDAC 是「开关型子页」（0x0E04 SET / 0x0E05 GET，应答单字节 00/01）。
            //   仅对 LDAC 放行回读，其余 PAGE 维持 null（不污染缓存）。
            XiberiaProductCatalog.Kind.PAGE ->
                item.cmdGet.takeIf { it != 0 && item.key == LDAC_PAGE_KEY }
            else -> null
        }

    /**
     * 从 CHOICE 型应答里解析档位值，无法判定返回 null（视为"没问到"，不污染缓存）。
     *
     * **SOUND_EFFECT（`0x0E0E`）专用规则**（真机实测应答 = 单字节 `01`）：
     *  官方 `SoundEffectMode` 声明序为 `KJ(0) / LY(1) / FOOT(2)`，MC05 的
     *  `getSoundEffectItems() = [KJ, LY]`，故单字节按 enum ordinal → choices 下标 解释：
     *  - `0x00` → `choices[0]`；`0x01` → `choices[1]`。
     *  兼容两字节帧（取尾字节同义）与"应答直接回 modeValue"的固件变体。
     *
     * 通用 CHOICE 规则（其余项）：在 payload 首/次/尾字节里找已知值域成员。
     */
    fun resolveChoiceValue(
        item: XiberiaProductCatalog.PanelItem,
        payload: ByteArray,
    ): Int? {
        if (item.choices.isEmpty()) return null
        if (payload.isEmpty()) return null
        if (item.key == "melodyplus_xi_sound_effect") {
            val byte = payload.last().toInt() and 0xFF
            // ① ordinal 解释（0/1/2 → 选项下标）
            item.choices.getOrNull(byte)?.let { return it.value }
            // ② 值解释（应答直接回 modeValue）
            item.choices.firstOrNull { it.value == byte }?.let { return it.value }
            return null
        }
        val known = item.choices.map { it.value }.toSet()
        val candidates = buildList {
            add(payload[0].toInt() and 0xFF)
            if (payload.size >= 2) add(payload[1].toInt() and 0xFF)
            add(payload[payload.size - 1].toInt() and 0xFF)
        }
        return candidates.firstOrNull { it in known }
    }
}
