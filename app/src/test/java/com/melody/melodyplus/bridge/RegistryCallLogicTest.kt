package com.melody.melodyplus.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 侧 P3 拆分纯逻辑契约测试。
 *
 * 解析核心（`resolveFrom`）不依赖 Android Bundle，可在纯 JVM 单测中验证；
 * Bundle 适配入口（`resolve`）仅做参数取值，逻辑已在核心中覆盖。
 */
class RegistryCallLogicTest {

    // ── RegistryMethodPolicy ──────────────────────────────────────────

    @Test
    fun highPrivilegeMethodsRequireStrictCaller() {
        assertTrue(RegistryMethodPolicy.requiresStrictCaller(RegistryContract.METHOD_EXPORT_LOGS))
        assertTrue(RegistryMethodPolicy.requiresStrictCaller(RegistryContract.METHOD_CLEAR_LOGS))
        assertTrue(RegistryMethodPolicy.requiresStrictCaller(RegistryContract.METHOD_INJECT_DEBUG_POPUP))
    }

    @Test
    fun readOnlyMethodsDoNotRequireStrictCaller() {
        assertFalse(RegistryMethodPolicy.requiresStrictCaller(RegistryContract.METHOD_GET_BINDING))
        assertFalse(RegistryMethodPolicy.requiresStrictCaller(RegistryContract.METHOD_GET_LOGS))
        assertFalse(RegistryMethodPolicy.requiresStrictCaller(RegistryContract.METHOD_APPEND_LOG))
        assertFalse(RegistryMethodPolicy.requiresStrictCaller(RegistryContract.METHOD_GET_DEVICE_IMAGE))
    }

    // ── DeviceImageRequest ────────────────────────────────────────────

    @Test
    fun imageRequestPrefersExtrasProfileIdOverArg() {
        val req = DeviceImageRequest.resolveFrom(
            profileIdFromExtras = "xiberia.mc05",
            profileIdFromArg = "sony.wf1000xm5",
            slotFromExtras = null,
        )
        assertEquals("xiberia.mc05", req?.profileId)
        assertEquals(DeviceImageAssets.SLOT_MAIN, req?.slot)
    }

    @Test
    fun imageRequestFallsBackToArgThenNullAndDefaultSlot() {
        val fromArg = DeviceImageRequest.resolveFrom(null, "sony.wf1000xm5", null)
        assertEquals("sony.wf1000xm5", fromArg?.profileId)
        assertEquals(DeviceImageAssets.SLOT_MAIN, fromArg?.slot)

        // 非法 slot 回落 main
        assertEquals(
            DeviceImageAssets.SLOT_MAIN,
            DeviceImageRequest.resolveFrom("p", null, "nope")?.slot,
        )
        // 合法 slot 保留
        assertEquals(
            DeviceImageAssets.SLOT_LEFT,
            DeviceImageRequest.resolveFrom("p", null, DeviceImageAssets.SLOT_LEFT)?.slot,
        )

        // profileId 来源皆空白 → null
        assertNull(DeviceImageRequest.resolveFrom(null, null, null))
        assertNull(DeviceImageRequest.resolveFrom("   ", "  ", null))
        // 空 extras 但 arg 有效 → 用 arg
        assertEquals("p", DeviceImageRequest.resolveFrom(null, "p", null)?.profileId)
    }

    // ── DebugInjectRequest ────────────────────────────────────────────

    @Test
    fun debugInjectPrefersExplicitExtras() {
        val req = DebugInjectRequest.resolveFrom(
            nameFromExtras = "My Buds",
            addressFromExtras = "AA:BB:CC:DD:EE:FF",
            profileIdFromExtras = "xiberia.mc05",
            boundAddress = "11:22:33:44:55:66",
        )
        assertEquals("My Buds", req.name)
        assertEquals("AA:BB:CC:DD:EE:FF", req.address)
        assertEquals("xiberia.mc05", req.profileId)
    }

    @Test
    fun debugInjectFallsBackToBoundThenDefaultConstants() {
        // 有绑定 MAC → 用绑定 MAC，名称/型号回落常量
        val withBound = DebugInjectRequest.resolveFrom(null, null, null, "11:22:33:44:55:66")
        assertEquals(BluetoothPopupContract.DEBUG_DEFAULT_NAME, withBound.name)
        assertEquals("11:22:33:44:55:66", withBound.address)
        assertEquals(BluetoothPopupContract.DEBUG_DEFAULT_PROFILE_ID, withBound.profileId)

        // 无绑定 MAC → 占位地址常量（不能是空串）
        val noBound = DebugInjectRequest.resolveFrom(null, null, null, null)
        assertEquals(BluetoothPopupContract.DEBUG_DEFAULT_ADDRESS, noBound.address)
        // 空白 extras 值应被忽略并回落
        val blankReq = DebugInjectRequest.resolveFrom("  ", "", null, "11:22:33:44:55:66")
        assertEquals(BluetoothPopupContract.DEBUG_DEFAULT_NAME, blankReq.name)
        assertEquals("11:22:33:44:55:66", blankReq.address)
    }

    // ── RegistryMethodPolicy.authorize（signature 权限 + 白名单双闸）──
    @Test
    fun authorizeAllowsNonStrictMethodsRegardlessOfPermission() {
        // 非高权限方法：不受 signature 权限影响，恒放行（授权交给外层宽松判定）。
        assertTrue(RegistryMethodPolicy.authorize(RegistryContract.METHOD_APPEND_LOG, false, null))
        assertTrue(RegistryMethodPolicy.authorize(RegistryContract.METHOD_GET_LOGS, false, null))
    }

    @Test
    fun authorizeDeniesStrictMethodsWithoutSignaturePermission() {
        // 白名单命中但无 signature 权限 → 拒。
        assertFalse(
            RegistryMethodPolicy.authorize(
                RegistryContract.METHOD_EXPORT_LOGS, hasSignaturePermission = false, strictPackage = "com.melody.melodyplus",
            ),
        )
    }

    @Test
    fun authorizeDeniesStrictMethodsWithoutStrictPackage() {
        // 有 signature 权限但 UID 包集合未全部受控 → 拒。
        assertFalse(
            RegistryMethodPolicy.authorize(
                RegistryContract.METHOD_CLEAR_LOGS, hasSignaturePermission = true, strictPackage = null,
            ),
        )
    }

    @Test
    fun authorizeAllowsStrictMethodsWhenBothConditionsHold() {
        assertTrue(
            RegistryMethodPolicy.authorize(
                RegistryContract.METHOD_INJECT_DEBUG_POPUP, hasSignaturePermission = true, strictPackage = "com.melody.melodyplus",
            ),
        )
    }

    // ── ProviderStats（自观测计数）──
    @Test
    fun providerStatsCountsRejectionsAndDrops() {
        val before = ProviderStats.snapshot()
        ProviderStats.record("append_log", elapsedMs = 1, rejectedFlag = false, reason = null)
        ProviderStats.record("clear_logs", elapsedMs = 1, rejectedFlag = true, reason = "high-priv-denied")
        ProviderStats.recordLogDropped()
        val after = ProviderStats.snapshot()
        assertEquals(before.total + 2, after.total)
        assertEquals(before.rejected + 1, after.rejected)
        assertEquals(before.droppedLogs + 1, after.droppedLogs)
        assertTrue(after.lastReason.contains("clear_logs"))
    }

    @Test
    fun providerStatsTracksSlowCallAndMaxElapsed() {
        val before = ProviderStats.snapshot()
        ProviderStats.record("get_logs", elapsedMs = ProviderStats.SLOW_MS + 10, rejectedFlag = false, reason = null)
        val after = ProviderStats.snapshot()
        assertEquals(before.slow + 1, after.slow)
        assertTrue(after.maxElapsedMs >= ProviderStats.SLOW_MS + 10)
    }
}