package com.melody.melodyplus.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反射字段分类器契约测试（P3 拆分产物）。
 *
 * 锁定「字段名 + 类型 → 连接/电量归类」规则：这些规则决定 Hook 会强制把哪些字段置为
 * 「已连接 / 假电量」，误归类会导致宿主显示错误状态，故以测试固化既有行为。
 */
class ReflectiveFieldClassifierTest {

    private val connectStateClass = "com.oplus.mydevices.sdk.device.ConnectState"
    private val connectionClass = "com.oplus.mydevices.sdk.device.Connection"

    private fun connectedKind(
        name: String,
        typeName: String = "int",
        isInt: Boolean = true,
        isBoolean: Boolean = false,
        isMap: Boolean = false,
    ) = ReflectiveFieldClassifier.connectedKind(
        fieldName = name,
        fieldTypeName = typeName,
        isInt = isInt,
        isBoolean = isBoolean,
        isMap = isMap,
        connectStateClassName = connectStateClass,
        connectionClassName = connectionClass,
    )

    @Test
    fun intConnectionStateFieldMapsToIntState() {
        assertEquals(ConnectedFieldKind.INT_STATE, connectedKind("connectionState"))
        assertEquals(ConnectedFieldKind.INT_STATE, connectedKind("sppConnectState"))
    }

    @Test
    fun booleanConnectedFieldMapsToBooleanConnectedButDisconnectedDoesNot() {
        assertEquals(
            ConnectedFieldKind.BOOLEAN_CONNECTED,
            connectedKind("isConnected", typeName = "boolean", isInt = false, isBoolean = true),
        )
        // 关键防回归：disconnected 绝不能被归类（否则会把「未连接」强制置真）
        assertNull(connectedKind("isDisconnected", typeName = "boolean", isInt = false, isBoolean = true))
    }

    @Test
    fun connectStateEnumRequiresBothTypeAndStateName() {
        assertEquals(
            ConnectedFieldKind.CONNECT_STATE_ENUM,
            connectedKind("connectState", typeName = connectStateClass, isInt = false),
        )
        // 类型对但名字不像状态字段 → 不归类
        assertNull(connectedKind("battery", typeName = connectStateClass, isInt = false))
    }

    @Test
    fun mapStateAndConnectionObjectResolveByRuleOrder() {
        assertEquals(
            ConnectedFieldKind.CONNECTION_MAP,
            connectedKind("connectionState", typeName = "java.util.Map", isInt = false, isMap = true),
        )
        // CONNECTION_OBJECT 只看类型名，不看字段名
        assertEquals(
            ConnectedFieldKind.CONNECTION_OBJECT,
            connectedKind("anything", typeName = connectionClass, isInt = false),
        )
    }

    @Test
    fun unrelatedFieldIsNotClassified() {
        assertNull(connectedKind("batteryLevel"))
        assertNull(connectedKind("foo", typeName = "java.lang.String", isInt = false))
    }

    @Test
    fun batteryClassificationDistinguishesLevelAndCharging() {
        val level = ReflectiveFieldClassifier.batteryClassification("mLeftBatteryLevel", isInt = true, isBoolean = false)
        assertEquals("leftbatterylevel", level?.batteryKey)
        assertEquals(false, level?.charging)

        val charging = ReflectiveFieldClassifier.batteryClassification("isBoxCharging", isInt = false, isBoolean = true)
        assertNull(charging?.batteryKey)
        assertEquals(true, charging?.charging)

        // int 型 charging 名字不成立（类型不符）
        assertNull(ReflectiveFieldClassifier.batteryClassification("isBoxCharging", isInt = true, isBoolean = false))
        // 与电量/充电无关
        assertNull(ReflectiveFieldClassifier.batteryClassification("isConnected", isInt = false, isBoolean = true))
    }

    @Test
    fun batteryKeyIsNormalizedBeforeLookup() {
        val c = ReflectiveFieldClassifier.batteryClassification("getHeadsetRightBatteryLevel", isInt = true, isBoolean = false)
        assertEquals("headsetrightbatterylevel", c?.batteryKey)
        assertTrue(MelodyMemberNames.isBatteryMemberKey(c!!.batteryKey!!))
    }
}