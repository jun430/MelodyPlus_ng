package com.melody.melodyplus.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAudioPopupGateTest {
    private var now = 1_000L
    private val gate = BluetoothAudioPopupGate(nowMillis = { now })

    @Test
    fun supportedNameConnectionTriggersPopup() {
        assertTrue(
            gate.shouldTrigger(
                address = "aa:bb:cc:dd:ee:ff",
                name = "WF-1000XM3",
                moduleSupported = true,
            ),
        )
    }

    @Test
    fun ordinaryDeviceDoesNotTriggerPopup() {
        assertFalse(
            gate.shouldTrigger(
                address = "aa:bb:cc:dd:ee:ff",
                name = "Car Audio",
                moduleSupported = false,
            ),
        )
    }

    @Test
    fun airPodsNeverTriggerEvenWhenProviderClaimsSupport() {
        assertFalse(
            gate.shouldTrigger(
                address = "aa:bb:cc:dd:ee:ff",
                name = "AirPods Pro",
                moduleSupported = true,
            ),
        )
    }

    @Test
    fun a2dpAndHfpConnectedEdgesAreDedupedByAddress() {
        assertTrue(gate.shouldTrigger("aa:bb:cc:dd:ee:ff", "WF-1000XM3", moduleSupported = true))

        now += 2_000L

        assertFalse(gate.shouldTrigger("AA:BB:CC:DD:EE:FF", "WF-1000XM3", moduleSupported = true))

        now += BluetoothAudioPopupGate.DEFAULT_DEDUPE_WINDOW_MS

        assertTrue(gate.shouldTrigger("AA:BB:CC:DD:EE:FF", "WF-1000XM3", moduleSupported = true))
    }

    @Test
    fun providerFailureFailsClosed() {
        assertFalse(gate.shouldTrigger("aa:bb:cc:dd:ee:ff", "WF-1000XM3", moduleSupported = false))
    }
}
