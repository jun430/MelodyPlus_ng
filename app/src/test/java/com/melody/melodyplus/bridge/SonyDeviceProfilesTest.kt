package com.melody.melodyplus.bridge

import com.melody.melodyplus.core.AncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyDeviceProfilesTest {
    private val sourceDeviceNames = listOf(
        "LinkBuds",
        "LinkBuds S",
        "Sony ULT",
        "WF-1000XM3",
        "WF-1000XM4",
        "WF-1000XM5",
        "WF-1000XM6",
        "WF-C500",
        "WF-C510",
        "WF-C700N",
        "WF-C710N",
        "WF-SP800N",
        "WH-1000XM2",
        "WH-1000XM3",
        "WH-1000XM4",
        "WH-1000XM5",
        "WH-1000XM6",
        "WH-CH720N",
        "WH-XB900N",
        "WH-XB910N",
        "WI-C100",
        "WI-SP600N",
    )

    @Test
    fun registersEveryBudsLinkSonyDevice() {
        // 只断言「清单里每个 Sony 型号都已注册」——不再断言 all.size == 清单长度：
        //   DeviceProfiles.all 已同时承载 XIBERIA 等其它品牌型号，总数必然大于 Sony 清单。
        sourceDeviceNames.forEach { name ->
            val profile = DeviceProfiles.findByName(name)
            assertNotNull("Missing profile for $name", profile)
            val expectedDisplayName = if (name.startsWith("Sony ")) name else "Sony $name"
            assertEquals("Wrong profile for $name", expectedDisplayName, profile?.displayName)
        }
    }

    @Test
    fun mapsProtocolGenerationsFromBudsLink() {
        assertEquals("v2", DeviceProfiles.findByName("WF-1000XM3")?.protocolVersion)
        assertEquals("v2", DeviceProfiles.findByName("WH-1000XM6")?.protocolVersion)
        assertEquals("v1", DeviceProfiles.findByName("WH-1000XM3")?.protocolVersion)
        assertEquals("v1", DeviceProfiles.findByName("WH-1000XM4")?.protocolVersion)
    }

    @Test
    fun preservesPerModelAncAndBatteryCapabilities() {
        val ambientOnly = DeviceProfiles.findByName("WF-C510")!!.capabilities
        assertTrue(AncMode.TRANSPARENCY in ambientOnly.ancModes)
        assertFalse(AncMode.NOISE_CANCELLING in ambientOnly.ancModes)

        val linkBuds = DeviceProfiles.findByName("LinkBuds")!!.capabilities
        assertTrue(linkBuds.ancModes.isEmpty())
        assertTrue(linkBuds.supportsLeftRightBattery)
        assertTrue(linkBuds.supportsCaseBattery)

        val headphones = DeviceProfiles.findByName("WH-1000XM5")!!.capabilities
        assertTrue(headphones.supportsSingleBattery)
        assertFalse(headphones.supportsLeftRightBattery)
    }

    @Test
    fun equalizerIsNotEnabledForAnyProfile() {
        assertTrue(DeviceProfiles.all.all { !it.uiFeatures.customEq })
    }

    @Test
    fun assignsUniqueProductImageToEveryProfile() {
        // [测试修复] 旧的 `profile.productImageAsset` 字段已随「单图模式 + DeviceImageAssets 真源」重构移除，
        //   本用例原来引用不存在的 API 导致整个测试模块无法编译。现改为校验当前真源：
        //   每个 profile 映射到唯一图片目录，且内置 asset 路径固定为 `<目录>/main.png`。
        val dirs = DeviceProfiles.all.map { DeviceImageAssets.dir(it.id) }

        assertEquals(DeviceProfiles.all.size, dirs.toSet().size)
        assertTrue(dirs.all { it.isNotBlank() })
        DeviceProfiles.all.forEach { profile ->
            assertEquals(
                "assets/device_images/${DeviceImageAssets.dir(profile.id)}/main.png",
                DeviceImageAssets.assetPath(profile, DeviceImageAssets.SLOT_MAIN),
            )
        }
    }
}
