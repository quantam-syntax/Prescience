package com.example.consent_cam.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyProtectionPayloadTest {
    @Test
    fun voiceOnlyRoundTripAndCorruptionRejection() {
        val samples = List(4) { index -> FloatArray(192).also { it[index] = 1f } }
        val encoded = NearbyProtectionPayload.encode(null, samples)
        val decoded = requireNotNull(NearbyProtectionPayload.decode(encoded))
        assertNull(decoded.face)
        assertEquals(4, decoded.voice?.size)
        assertTrue(decoded.voice!!.zip(samples).all { (actual, expected) -> actual.contentEquals(expected) })
        decoded.voice.forEach { it.fill(0f) }
        encoded[7] = 0
        assertNull(NearbyProtectionPayload.decode(encoded))
    }
}
