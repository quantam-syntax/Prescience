package com.consentcam.ble.session

import com.consentcam.ble.api.ConsentAdvertisement
import com.consentcam.ble.protocol.ConsentFlags
import com.consentcam.ble.proximity.ProximityConfig
import com.consentcam.ble.proximity.ProximityTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBleConsentRegistryTest {
    @Test
    fun nearbyProtectActivatesButAllowDoesNot() {
        val protectRegistry = registry()
        protectRegistry.observe(advertisement(1u, 0u), 0)
        val allowRegistry = registry()
        allowRegistry.observe(
            advertisement(2u, ConsentFlags.ALLOW_APPEARANCE.toUByte()),
            0,
        )

        assertTrue(protectRegistry.hasNearbyProtectSession(0))
        assertFalse(allowRegistry.hasNearbyProtectSession(0))
    }

    @Test
    fun staleDirectSessionCannotKeepProtectionActive() {
        val registry = registry()
        registry.observe(advertisement(1u, 0u), 0)

        assertFalse(registry.hasNearbyProtectSession(5_000))
    }

    private fun registry() = DirectBleConsentRegistry(
        proximityTracker = ProximityTracker(
            ProximityConfig(
                medianWindowSize = 1,
                smoothingAlpha = 1.0,
                samplesToEnter = 1,
                samplesToExit = 1,
                staleTimeoutMs = 5_000,
            ),
        ),
    )

    private fun advertisement(sessionId: UInt, flags: UByte) = ConsentAdvertisement(
        sessionId = sessionId,
        flags = flags,
        rotatingToken = 1u,
        rssi = -55,
    )
}
