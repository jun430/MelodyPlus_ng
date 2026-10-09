package com.melody.melodyplus.hook

import com.melody.melodyplus.adapter.xiberia.XiberiaProductCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 结构拆分配套契约测试：锁定从 [MelodyPanelHook] 抽出的纯逻辑行为，
 * 使后续继续迁移反射/View 注入时有回归保护（此前这些函数是 Hook 内 private，无法触达）。
 */
class PanelStateLogicTest {

    // ── MelodyMemberNames ──────────────────────────────────────────────

    @Test
    fun normalizedKeyStripsGetSetAndFieldPrefixes() {
        assertEquals("leftbatterylevel", MelodyMemberNames.normalizedMemberKey("getLeftBatteryLevel"))
        assertEquals("leftbatterylevel", MelodyMemberNames.normalizedMemberKey("setLeftBatteryLevel"))
        assertEquals("boxcharging", MelodyMemberNames.normalizedMemberKey("mBoxCharging"))
        assertEquals("leftbattery", MelodyMemberNames.normalizedMemberKey("LeftBattery"))
        // `m` 后非大写不裁剪（避免误伤 model 之类字段）。
        assertEquals("model", MelodyMemberNames.normalizedMemberKey("model"))
    }

    @Test
    fun chargingKeysCoverAllVendorVariants() {
        assertTrue(MelodyMemberNames.isChargingName("getLeftCharging"))
        assertTrue(MelodyMemberNames.isChargingName("mBoxCharging"))
        assertTrue(MelodyMemberNames.isChargingName("isRightCharging"))
        assertTrue(MelodyMemberNames.isChargingKey("casecharging"))
        assertFalse(MelodyMemberNames.isChargingName("leftBattery"))
    }

    @Test
    fun batteryMemberKeysCoverAllVendorVariants() {
        assertTrue(MelodyMemberNames.isBatteryMemberKey("leftbatterylevel"))
        assertTrue(MelodyMemberNames.isBatteryMemberKey("headsetboxbattery"))
        assertTrue(MelodyMemberNames.isBatteryMemberKey("singlebattery"))
        assertFalse(MelodyMemberNames.isBatteryMemberKey("noisecancelling"))
    }

    @Test
    fun batterySlotsClassifyLeftRightCaseSingle() {
        assertEquals(BatterySlot.LEFT, MelodyMemberNames.batterySlotForMemberKey("headsetleftbatterylevel"))
        assertEquals(BatterySlot.RIGHT, MelodyMemberNames.batterySlotForMemberKey("rightbattery"))
        assertEquals(BatterySlot.CASE, MelodyMemberNames.batterySlotForMemberKey("casebatterylevel"))
        assertEquals(BatterySlot.SINGLE, MelodyMemberNames.batterySlotForMemberKey("batterylevel"))
        assertNull(MelodyMemberNames.batterySlotForMemberKey("unknownfield"))
    }

    // ── PanelContext ───────────────────────────────────────────────────

    @Test
    fun panelContextCurrentOnlyWhenAllFourFieldsMatch() {
        val ctx = PanelContext(generation = 7, mac = "AA:BB", profileId = "xiberia.mc05", productId = 100)
        // 全等 → 当前
        assertTrue(ctx.isCurrent(generation = 7, mac = "AA:BB", profileId = "xiberia.mc05", productId = 100))
        // 代际变化（切设备）→ 过期
        assertFalse(ctx.isCurrent(generation = 8, mac = "AA:BB", profileId = "xiberia.mc05", productId = 100))
        // 同型号换 MAC（代际不变）→ 过期
        assertFalse(ctx.isCurrent(generation = 7, mac = "CC:DD", profileId = "xiberia.mc05", productId = 100))
        // MAC 未解析（null）→ 过期
        assertFalse(ctx.isCurrent(generation = 7, mac = null, profileId = null, productId = 0))
        // 型号变化 → 过期
        assertFalse(ctx.isCurrent(generation = 7, mac = "AA:BB", profileId = "xiberia.mc05", productId = 200))
    }

    // ── 连接状态谓词 ───────────────────────────────────────────────────

    @Test
    fun connectionStateNameMatchesVendorVariants() {
        assertTrue(MelodyMemberNames.isConnectionStateName("getConnectionState"))
        assertTrue(MelodyMemberNames.isConnectionStateName("mConnectState"))
        assertTrue(MelodyMemberNames.isConnectionStateName("sppConnectionState"))
        assertFalse(MelodyMemberNames.isConnectionStateName("getConnected"))
        assertFalse(MelodyMemberNames.isConnectionStateName("batteryLevel"))
    }

    @Test
    fun connectedBooleanNameExcludesDisconnected() {
        assertTrue(MelodyMemberNames.isConnectedBooleanName("isConnected"))
        assertTrue(MelodyMemberNames.isConnectedBooleanName("getIsSpp"))
        assertTrue(MelodyMemberNames.isConnectedBooleanName("isDeviceBonded"))
        // 含 connected 但带 disconnect → 排除（不能把「未连接」当已连接）
        assertFalse(MelodyMemberNames.isConnectedBooleanName("isDisconnected"))
        assertFalse(MelodyMemberNames.isConnectedBooleanName("disconnected"))
        assertFalse(MelodyMemberNames.isConnectedBooleanName("getBattery"))
    }

    // ── XiberiaPanelQuery ──────────────────────────────────────────────

    private fun item(
        key: String,
        kind: XiberiaProductCatalog.Kind,
        cmdGet: Int? = null,
        choices: List<XiberiaProductCatalog.PanelItem.Choice> = emptyList(),
    ) = XiberiaProductCatalog.PanelItem(
        key = key,
        title = key,
        summary = "",
        kind = kind,
        cmdGet = cmdGet,
        choices = choices,
    )

    @Test
    fun queryCommandOnlyForSwitchAndChoice() {
        assertEquals(
            0x0B02,
            XiberiaPanelQuery.queryCommandFor(
                item("k", XiberiaProductCatalog.Kind.SWITCH, cmdGet = 0x0B02),
            ),
        )
        assertEquals(
            0x0C02,
            XiberiaPanelQuery.queryCommandFor(
                item("k", XiberiaProductCatalog.Kind.CHOICE, cmdGet = 0x0C02),
            ),
        )
        // PAGE 型不探测（无档位态，应答尾字节会误判污染缓存）。
        assertNull(
            XiberiaPanelQuery.queryCommandFor(
                item("k", XiberiaProductCatalog.Kind.PAGE, cmdGet = 0x1234),
            ),
        )
        // 未登记 GET 码的 SWITCH 也返回 null。
        assertNull(
            XiberiaPanelQuery.queryCommandFor(item("k", XiberiaProductCatalog.Kind.SWITCH)),
        )
    }

    @Test
    fun choiceParsingRejectsEmptyChoicesAndEmptyPayload() {
        assertNull(XiberiaPanelQuery.resolveChoiceValue(item("k", XiberiaProductCatalog.Kind.CHOICE), byteArrayOf(1)))
        assertNull(
            XiberiaPanelQuery.resolveChoiceValue(
                item(
                    "k",
                    XiberiaProductCatalog.Kind.CHOICE,
                    choices = listOf(XiberiaProductCatalog.PanelItem.Choice("A", 1)),
                ),
                ByteArray(0),
            ),
        )
    }

    @Test
    fun soundEffectUsesOrdinalIndexMapping() {
        val soundEffect = item(
            "melodyplus_xi_sound_effect",
            XiberiaProductCatalog.Kind.CHOICE,
            choices = listOf(
                XiberiaProductCatalog.PanelItem.Choice("KJ", 0x0D),
                XiberiaProductCatalog.PanelItem.Choice("LY", 0x0E),
            ),
        )
        // ordinal 解释：0x00 → choices[0]，0x01 → choices[1]
        assertEquals(0x0D, XiberiaPanelQuery.resolveChoiceValue(soundEffect, byteArrayOf(0x00)))
        assertEquals(0x0E, XiberiaPanelQuery.resolveChoiceValue(soundEffect, byteArrayOf(0x01)))
        // 值解释回退：应答直接回 modeValue 时也能解出。
        assertEquals(0x0E, XiberiaPanelQuery.resolveChoiceValue(soundEffect, byteArrayOf(0x0E)))
        // 越界且非已知值 → null（不污染缓存）。
        assertNull(XiberiaPanelQuery.resolveChoiceValue(soundEffect, byteArrayOf(0x7F)))
    }

    @Test
    fun genericChoiceScansFirstSecondAndLastBytes() {
        val item = item(
            "k",
            XiberiaProductCatalog.Kind.CHOICE,
            choices = listOf(
                XiberiaProductCatalog.PanelItem.Choice("A", 0x10),
                XiberiaProductCatalog.PanelItem.Choice("B", 0x20),
            ),
        )
        assertEquals(0x10, XiberiaPanelQuery.resolveChoiceValue(item, byteArrayOf(0x10, 0x00)))
        assertEquals(0x20, XiberiaPanelQuery.resolveChoiceValue(item, byteArrayOf(0x00, 0x20)))
        assertEquals(0x10, XiberiaPanelQuery.resolveChoiceValue(item, byteArrayOf(0x00, 0x00, 0x10)))
        // 全部不匹配 → null。
        assertNull(XiberiaPanelQuery.resolveChoiceValue(item, byteArrayOf(0x01, 0x02)))
    }
}