package com.melody.melodyplus.bridge

/**
 * 型号图片资源统一命名规范（全型号通用，禁止再写「某型号专用」分支）。
 *
 * 目录布局（模块内置 assets，APK 内）：
 * ```
 * assets/device_images/<imageDir>/main.png    主图（旧单图型号仅此一张）
 * assets/device_images/<imageDir>/left.png    左耳
 * assets/device_images/<imageDir>/right.png   右耳
 * assets/device_images/<imageDir>/case.png    耳机仓
 * ```
 * 用户自定义图片（模块私有目录，无需存储权限）：
 * ```
 * files/device_images/<profileId>/main.png
 * ```
 *
 * `<imageDir>` 由 [dirFor] 从 [DeviceProfile.id] 派生：把 `.` 与其余非字母数字字符
 * 统一折叠成单个 `_`（例 `xiberia.mc05` → `xiberia_mc05`）。历史目录名与该规则一致，
 * 因此无需额外映射表；如个别型号目录名不一致，再在 [dirOverride] 里补。
 */
object DeviceImageAssets {

    const val SLOT_MAIN = "main"
    const val SLOT_LEFT = "left"
    const val SLOT_RIGHT = "right"
    const val SLOT_CASE = "case"

    /** 允许槽位全集（顺序固定：主图 → 左耳 → 右耳 → 耳机仓）。 */
    val ALL_SLOTS: List<String> = listOf(SLOT_MAIN, SLOT_LEFT, SLOT_RIGHT, SLOT_CASE)

    /** 分体槽位（左/右/仓），主图不属于分体。 */
    val SPLIT_SLOTS: List<String> = listOf(SLOT_LEFT, SLOT_RIGHT, SLOT_CASE)

    /** 个别历史目录名与派生规则不一致时的显式覆盖。 */
    private val dirOverride: Map<String, String> = mapOf(
        "oppo.encox3" to "oppo_enco_x3",
        "sony.sonyult" to "sony_ult",
    )

    fun isValidSlot(slot: String?): Boolean = slot in ALL_SLOTS

    /** 型号图片目录名（唯一真源）。 */
    fun dirFor(profile: DeviceProfile): String = dirForId2(profile.id)

    /** 按 profileId 取目录名（含历史覆盖），供无 DeviceProfile 实例时使用。 */
    fun dir(profileId: String): String = dirForId2(profileId)

    private fun dirForId2(profileId: String): String =
        dirOverride[profileId] ?: dirForId(profileId)

    /** 由 profileId 派生目录名：`.`/其它分隔符 → `_`，多个连续分隔符合并。 */
    fun dirForId(profileId: String): String =
        profileId.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** 模块内置 asset 路径（ClassLoader 语义，带 `assets/` 前缀）。 */
    fun assetPath(profile: DeviceProfile, slot: String): String =
        assetPathForId(profile.id, slot)

    /**
     * 模块内置 AssetManager 相对路径（无 `assets/` 前缀）。
     *
     * 【单图模式】全型号统一只读 `main.png` 一张（三合一合成图：左耳 + 右耳 + 耳机仓）。
     * `slot` 形参保留仅为兼容历史调用签名，内部恒等于 [SLOT_MAIN]，不再按槽位分图。
     */
    fun assetManagerPath(profileId: String, slot: String): String =
        "device_images/${dir(profileId)}/$SLOT_MAIN.png"

    /** 按 profileId 取 asset 路径（供 profile 构造期使用，此时尚无 DeviceProfile 实例）。 */
    fun assetPathForId(profileId: String, slot: String): String =
        "assets/${assetManagerPath(profileId, slot)}"

    /** 旧版公共目录相对子目录（仅遗留图懒迁移兼容；新图存模块私有目录 files/device_images）。 */
    fun userRelativeDir(profileId: String): String = "MelodyPlus/images/$profileId"
}