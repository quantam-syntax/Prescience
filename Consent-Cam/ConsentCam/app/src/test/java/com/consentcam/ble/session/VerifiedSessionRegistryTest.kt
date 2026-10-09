package com.consentcam.ble.session

import com.consentcam.ble.api.ConsentAdvertisement
import com.consentcam.ble.protocol.ConsentFlags
import com.consentcam.ble.proximity.ProximityConfig
import com.consentcam.ble.proximity.ProximityTracker
import com.consentcam.ble.security.RotatingToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedSessionRegistryTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val proximityConfig = ProximityConfig(
        medianWindowSize = 1,
        smoothingAlpha = 1.0,
        samplesToEnter = 1,
        samplesToExit = 1,
        staleTimeoutMs = 5_000,
    )

    @Test
    fun observe_withoutSessionSecretIsUnverifiedDiscovery() {
        val registry = registry()

        val result = registry.observe(advertisement(1u, 0u, 0, -60), 0, 0)

        assertEquals(VerifiedSessionRegistry.ObservationResult.UnverifiedDiscovery, result)
    }

    @Test
    fun validProtectAdvertisementActivatesNearbyProtection() {
        val registry = registry()
        registry.registerSessionSecret(1u, secret)

        val result = registry.observe(advertisement(1u, 0u, 0, -60), 0, 100)

        assertTrue(result is VerifiedSessionRegistry.ObservationResult.Verified)
        assertTrue(registry.hasNearbyProtectSession(100))
    }

    @Test
    fun allowAdvertisementNeverActivatesProtection() {
        val registry = registry()
        registry.registerSessionSecret(1u, secret)
        val flags = ConsentFlags.ALLOW_APPEARANCE.toUByte()

        registry.observe(advertisement(1u, flags, 0, -60), 0, 100)

        assertFalse(registry.hasNearbyProtectSession(100))
        assertEquals(ConsentStatus.ALLOW, registry.activeSessions(100).single().consent)
    }

    @Test
    fun repeatedTokenCannotExtendAuthenticationTimeout() {
        val registry = registry(authenticationTimeoutMs = 1_000)
        registry.registerSessionSecret(1u, secret)
        val advertisement = advertisement(1u, 0u, 0, -60)
        registry.observe(advertisement, 0, 0)

        registry.observe(advertisement, 0, 999)

        assertTrue(registry.activeSessions(1_000).isEmpty())
    }

    @Test
    fun newlyRotatedTokenExtendsAuthenticationTimeout() {
        val registry = registry(authenticationTimeoutMs = 1_000)
        registry.registerSessionSecret(1u, secret)
        registry.observe(advertisement(1u, 0u, 0, -60), 0, 0)

        registry.observe(advertisement(1u, 0u, 1, -60), 30, 900)

        assertEquals(1, registry.activeSessions(1_500).size)
    }

    @Test
    fun flagsCannotChangeUsingNonFreshTokenWindow() {
        val registry = registry()
        registry.registerSessionSecret(1u, secret)
        registry.observe(advertisement(1u, 0u, 0, -60), 0, 0)
        val allowFlags = ConsentFlags.ALLOW_APPEARANCE.toUByte()

        val changed = registry.observe(advertisement(1u, allowFlags, 0, -60), 0, 1)

        assertEquals(
            VerifiedSessionRegistry.ObservationResult.Rejected(
                VerifiedSessionRegistry.RejectionReason.FLAGS_CHANGED_WITHOUT_FRESH_WINDOW,
            ),
            changed,
        )
    }

    private fun registry(authenticationTimeoutMs: Long = 65_000) = VerifiedSessionRegistry(
        proximityTracker = ProximityTracker(proximityConfig),
        authenticationTimeoutMs = authenticationTimeoutMs,
    )

    private fun advertisement(
        sessionId: UInt,
        flags: UByte,
        counter: Long,
        rssi: Int,
    ) = ConsentAdvertisement(
        sessionId = sessionId,
        flags = flags,
        rotatingToken = RotatingToken.create(secret, sessionId, flags, counter),
        rssi = rssi,
    )
}

