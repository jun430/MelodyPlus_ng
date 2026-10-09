package com.melody.melodyplus.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleDeviceRegistryTest {
    private val registry = ModuleDeviceRegistry(context = null)

    @Test
    fun builtInSonyNameWorksWithoutProvider() {
        assertTrue(registry.isModuleSupportedName("WF-1000XM3"))
        assertTrue(registry.isModuleSupportedName("WH-1000XM6"))
        assertTrue(registry.isModuleSupportedName("ULT WEAR"))
    }

    @Test
    fun fallbackDoesNotMatchOtherDevices() {
        assertFalse(registry.isModuleSupportedName("AirPods Pro"))
        assertFalse(registry.isModuleSupportedName("Unknown headset"))
    }
}
