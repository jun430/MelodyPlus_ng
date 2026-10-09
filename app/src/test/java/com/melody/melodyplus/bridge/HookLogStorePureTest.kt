package com.melody.melodyplus.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 结构拆分配套契约测试：锁定 Provider `append_log` 入口的纯输入归一化与 [HookLogStore.sanitize]。
 * 这些逻辑此前内嵌在 [DeviceRegistryProvider.call] 的 Binder 分支里，无法脱离 Android 单测。
 */
class HookLogStorePureTest {

    @Test
    fun appendInputDefaultsLevelAndSource() {
        val entry = HookLogStore.normalizeAppendInput(
            level = null,
            source = null,
            message = "hello",
            defaultSource = "com.melody.melodyplus",
        )
        assertEquals("I", entry?.level)
        assertEquals("com.melody.melodyplus", entry?.source)
        assertEquals("hello", entry?.message)
    }

    @Test
    fun appendInputBlanksFallBack() {
        val entry = HookLogStore.normalizeAppendInput(
            level = "   ",
            source = "",
            message = "x",
            defaultSource = "unknown",
        )
        assertEquals("I", entry?.level)
        assertEquals("unknown", entry?.source)
    }

    @Test
    fun appendInputRejectsBlankMessage() {
        assertNull(HookLogStore.normalizeAppendInput("I", "s", null, "d"))
        assertNull(HookLogStore.normalizeAppendInput("I", "s", "", "d"))
        assertNull(HookLogStore.normalizeAppendInput("I", "s", "   ", "d"))
    }

    @Test
    fun appendInputClampsMessageLength() {
        val long = "a".repeat(HookLogStore.MAX_MESSAGE_CHARS + 500)
        val entry = HookLogStore.normalizeAppendInput("I", "s", long, "d")
        assertEquals(HookLogStore.MAX_MESSAGE_CHARS, entry?.message?.length)
    }

    // ── RegistryCallerPolicy ───────────────────────────────────────────

    @Test
    fun specialUidMapsRootShellAndSelf() {
        assertEquals(RegistryCallerPolicy.ROOT, RegistryCallerPolicy.specialUidLabel(0, 10123))
        assertEquals(RegistryCallerPolicy.SHELL, RegistryCallerPolicy.specialUidLabel(2000, 10123))
        assertEquals(RegistryCallerPolicy.SELF, RegistryCallerPolicy.specialUidLabel(10123, 10123))
        assertNull(RegistryCallerPolicy.specialUidLabel(10456, 10123))
    }

    @Test
    fun looseAllowsAnyWhitelistedPackage() {
        // 混合 UID（白名单 + 非白名单）→ 宽松放行仍取首个命中白名单者
        assertEquals(
            "com.oplus.melody",
            RegistryCallerPolicy.resolveLoose(listOf("com.evil.app", "com.oplus.melody")),
        )
        assertNull(RegistryCallerPolicy.resolveLoose(listOf("com.evil.app")))
        assertNull(RegistryCallerPolicy.resolveLoose(emptyList()))
    }

    @Test
    fun strictRequiresAllPackagesWhitelisted() {
        // 全部受控 → 放行，返回首个
        assertEquals(
            "com.oplus.melody",
            RegistryCallerPolicy.resolveStrict(listOf("com.oplus.melody", "com.android.bluetooth")),
        )
        // shared UID 混入非白名单包 → 拒绝（不能任取其一）
        assertNull(RegistryCallerPolicy.resolveStrict(listOf("com.oplus.melody", "com.evil.app")))
        assertNull(RegistryCallerPolicy.resolveStrict(emptyList()))
    }

    @Test
    fun sanitizeWhitelistsLevelAndTruncatesWithMarker() {
        val ok = HookLogStore.sanitize(HookLogEntry(level = "w", source = "  src  ", message = "  msg  "))
        assertEquals("W", ok?.level)
        assertEquals("src", ok?.source)
        assertEquals("msg", ok?.message)

        // 非法 level → I
        assertEquals("I", HookLogStore.sanitize(HookLogEntry("X", "s", "m"))?.level)

        // 超长消息 → 截断并带标记
        val long = "b".repeat(HookLogStore.MAX_MESSAGE_CHARS + 10)
        val truncated = HookLogStore.sanitize(HookLogEntry("I", "s", long))
        assertTrue(truncated!!.message.endsWith("…(truncated)"))
        assertTrue(truncated.message.length <= HookLogStore.MAX_MESSAGE_CHARS + "…(truncated)".length)

        // 空消息 → 丢弃
        assertNull(HookLogStore.sanitize(HookLogEntry("I", "s", "   ")))
    }
}