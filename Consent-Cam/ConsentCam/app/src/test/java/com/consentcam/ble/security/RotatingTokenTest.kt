package com.consentcam.ble.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatingTokenTest {
    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun create_matchesKnownHmacSha256Vector() {
        val token = RotatingToken.create(
            sessionSecret = secret,
            sessionId = 0x01020304u,
            flags = 3u,
            counter = 123L,
        )

        assertEquals(0x91db67f2u, token)
    }

    @Test
    fun verify_acceptsCurrentAndImmediatelyPreviousWindows() {
        val currentCounter = 50L
        val now = currentCounter * RotatingToken.WINDOW_SECONDS
        val currentToken = RotatingToken.create(secret, 7u, 0u, currentCounter)
        val previousToken = RotatingToken.create(secret, 8u, 0u, currentCounter - 1)
        val verifier = RotatingTokenVerifier()

        assertTrue(verifier.verify(secret, 7u, 0u, currentToken, now).isValid)
        assertTrue(verifier.verify(secret, 8u, 0u, previousToken, now).isValid)
    }

    @Test
    fun verify_rejectsOlderWrongSessionWrongFlagsAndWrongSecret() {
        val counter = 50L
        val now = counter * RotatingToken.WINDOW_SECONDS
        val oldToken = RotatingToken.create(secret, 7u, 0u, counter - 2)
        val currentToken = RotatingToken.create(secret, 7u, 0u, counter)
        val otherSecret = ByteArray(32) { (it + 1).toByte() }
        val verifier = RotatingTokenVerifier()

        assertFalse(verifier.verify(secret, 7u, 0u, oldToken, now).isValid)
        assertFalse(verifier.verify(secret, 8u, 0u, currentToken, now).isValid)
        assertFalse(verifier.verify(secret, 7u, 1u, currentToken, now).isValid)
        assertFalse(verifier.verify(otherSecret, 7u, 0u, currentToken, now).isValid)
    }

    @Test
    fun verify_repeatCannotExtendSessionFreshness() {
        val counter = 50L
        val now = counter * RotatingToken.WINDOW_SECONDS
        val token = RotatingToken.create(secret, 7u, 0u, counter)
        val verifier = RotatingTokenVerifier()

        val first = verifier.verify(secret, 7u, 0u, token, now)
        val replay = verifier.verify(secret, 7u, 0u, token, now)

        assertEquals(RotatingTokenVerifier.Status.VALID_FRESH, first.status)
        assertTrue(first.extendsSession)
        assertEquals(RotatingTokenVerifier.Status.VALID_REPEAT, replay.status)
        assertFalse(replay.extendsSession)
    }

    @Test
    fun verify_previousWindowCannotBecomeFreshAfterCurrentWindowWasSeen() {
        val counter = 50L
        val now = counter * RotatingToken.WINDOW_SECONDS
        val currentToken = RotatingToken.create(secret, 7u, 0u, counter)
        val previousToken = RotatingToken.create(secret, 7u, 0u, counter - 1)
        val verifier = RotatingTokenVerifier()

        verifier.verify(secret, 7u, 0u, currentToken, now)
        val delayedPrevious = verifier.verify(secret, 7u, 0u, previousToken, now)

        assertEquals(RotatingTokenVerifier.Status.VALID_REPEAT, delayedPrevious.status)
        assertFalse(delayedPrevious.extendsSession)
    }

    @Test
    fun verify_malformedSecretFailsClosed() {
        val result = RotatingTokenVerifier().verify(
            sessionSecret = ByteArray(4),
            sessionId = 7u,
            flags = 0u,
            token = 0u,
            epochSeconds = 1_000,
        )

        assertEquals(RotatingTokenVerifier.Status.INVALID, result.status)
    }
}
