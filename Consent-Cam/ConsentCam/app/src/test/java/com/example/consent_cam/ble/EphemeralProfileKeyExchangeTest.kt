package com.example.consent_cam.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EphemeralProfileKeyExchangeTest {
    @Test
    fun bothPhonesDeriveTheSameSessionSecret() {
        val first = EphemeralProfileKeyExchange.generate()
        val second = EphemeralProfileKeyExchange.generate()

        val firstSecret = EphemeralProfileKeyExchange.deriveSecret(
            first.private,
            EphemeralProfileKeyExchange.publicKeyBytes(second),
        )
        val secondSecret = EphemeralProfileKeyExchange.deriveSecret(
            second.private,
            EphemeralProfileKeyExchange.publicKeyBytes(first),
        )

        assertArrayEquals(firstSecret, secondSecret)
        firstSecret.fill(0)
        secondSecret.fill(0)
    }

    @Test
    fun aDifferentPeerCannotDeriveTheSameSecret() {
        val first = EphemeralProfileKeyExchange.generate()
        val second = EphemeralProfileKeyExchange.generate()
        val attacker = EphemeralProfileKeyExchange.generate()
        val expected = EphemeralProfileKeyExchange.deriveSecret(
            first.private,
            EphemeralProfileKeyExchange.publicKeyBytes(second),
        )
        val wrong = EphemeralProfileKeyExchange.deriveSecret(
            attacker.private,
            EphemeralProfileKeyExchange.publicKeyBytes(second),
        )

        assertFalse(expected.contentEquals(wrong))
        expected.fill(0)
        wrong.fill(0)
    }
}
