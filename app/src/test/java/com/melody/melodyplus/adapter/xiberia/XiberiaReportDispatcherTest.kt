// 文件: app/src/test/java/com/melody/melodyplus/adapter/xiberia/XiberiaReportDispatcherTest.kt
// 说明: 设备主动上报帧分发的断言（对齐官方 *REPORT 命令码族）。
//
// ⚠️ 本测试不依赖 android framework：XiberiaReportDispatcher.dispatch 内部调用 modLog，
//    单测环境（JVM，无 android.util.Log）会抛异常 → 由 runCatching 兜住，不影响断言。
package com.melody.melodyplus.adapter.xiberia

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiberiaReportDispatcherTest {

    @After
    fun tearDown() {
        XiberiaReportDispatcher.clear()
    }

    @Test
    fun reportKindMapping_matchesOfficialCodes() {
        assertEquals(XiberiaReportDispatcher.ReportKind.BATTERY, XiberiaReportDispatcher.ReportKind.of(0x0A11))
        assertEquals(XiberiaReportDispatcher.ReportKind.BATTERY, XiberiaReportDispatcher.ReportKind.of(0x0A12))
        assertEquals(XiberiaReportDispatcher.ReportKind.GAME_MODE, XiberiaReportDispatcher.ReportKind.of(0x0C03))
        assertEquals(XiberiaReportDispatcher.ReportKind.NOISE, XiberiaReportDispatcher.ReportKind.of(0x0B03))
        assertEquals(XiberiaReportDispatcher.ReportKind.DONGLE, XiberiaReportDispatcher.ReportKind.of(0x0E25))
    }

    @Test
    fun nonReportCode_returnsNull() {
        assertNull(XiberiaReportDispatcher.ReportKind.of(0x0E05)) // LDAC_GET 是查询应答，非上报
        assertNull(XiberiaReportDispatcher.ReportKind.of(0x0807))
    }

    @Test
    fun dispatch_deliversToRegisteredListener() {
        val got = mutableListOf<XiberiaReportDispatcher.Report>()
        val handle = XiberiaReportDispatcher.register { got += it }
        val handled = XiberiaReportDispatcher.dispatch("AA:BB:CC:DD:EE:FF", 0x0C03, byteArrayOf(0x01))
        assertTrue("0x0C03 应被识别为上报", handled)
        assertEquals(1, got.size)
        assertEquals(XiberiaReportDispatcher.ReportKind.GAME_MODE, got[0].kind)
        assertEquals("AA:BB:CC:DD:EE:FF", got[0].mac)
        assertTrue(got[0].asBool)
        handle.close()
    }

    @Test
    fun dispatch_nonReportCode_returnsFalse_andDoesNotDeliver() {
        var called = false
        XiberiaReportDispatcher.register { called = true }
        val handled = XiberiaReportDispatcher.dispatch("AA:BB:CC:DD:EE:FF", 0x0E05, byteArrayOf(0x01))
        assertFalse(handled)
        assertFalse(called)
    }

    @Test
    fun unregister_stopsDelivery() {
        var count = 0
        val listener = XiberiaReportDispatcher.Listener { count++ }
        XiberiaReportDispatcher.register(listener)
        XiberiaReportDispatcher.dispatch("AA:BB:CC:DD:EE:FF", 0x0B03, byteArrayOf(0x02))
        XiberiaReportDispatcher.unregister(listener)
        XiberiaReportDispatcher.dispatch("AA:BB:CC:DD:EE:FF", 0x0B03, byteArrayOf(0x02))
        assertEquals(1, count)
    }

    @Test
    fun listenerThrowing_doesNotBreakOthers() {
        val ok = mutableListOf<Int>()
        XiberiaReportDispatcher.register { throw RuntimeException("boom") }
        XiberiaReportDispatcher.register { ok += it.command }
        XiberiaReportDispatcher.dispatch("AA:BB:CC:DD:EE:FF", 0x0E25, byteArrayOf(0x00))
        assertEquals(listOf(0x0E25), ok)
    }

    @Test
    fun reportPayloadSemantics() {
        val r = XiberiaReportDispatcher.Report(
            mac = "M",
            kind = XiberiaReportDispatcher.ReportKind.NOISE,
            command = 0x0B03,
            payload = byteArrayOf(0x00, 0x03),
            receivedAtMillis = 0L,
        )
        assertEquals(0x03, r.tailByte)
        assertEquals(3, r.asLevel)
        assertFalse(r.asBool)
        assertEquals(2, r.payload.size)
    }

    @Test
    fun reportEquality_usesContentNotReference() {
        val a = XiberiaReportDispatcher.Report("M", XiberiaReportDispatcher.ReportKind.BATTERY, 0x0A11, byteArrayOf(1, 2), 0L)
        val b = XiberiaReportDispatcher.Report("M", XiberiaReportDispatcher.ReportKind.BATTERY, 0x0A11, byteArrayOf(1, 2), 999L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}