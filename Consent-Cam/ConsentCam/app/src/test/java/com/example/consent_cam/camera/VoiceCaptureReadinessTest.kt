package com.example.consent_cam.camera

import com.example.consent_cam.ble.NearbyVoiceRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureReadinessTest {
    @Test
    fun missingNearbyPassportBlocksProtectedCapture() {
        val request = NearbyVoiceRequest(1u, null)
        assertTrue(
            VoiceCaptureReadiness.evaluate(true, true, listOf(request), false, true).error
                ?.contains("Voice Passport is missing") == true,
        )
    }

    @Test
    fun oneReceivedPassportAllowsProtectedCapture() {
        val request = NearbyVoiceRequest(1u, List(4) { floatArrayOf(1f) })
        try {
            val decision = VoiceCaptureReadiness.evaluate(true, true, listOf(request), false, true)
            assertNull(decision.error)
            assertTrue(decision.processVoice)
        } finally { request.close() }
    }

    @Test
    fun twoProtectedVoicesDoNotProduceSilentExport() {
        val request = NearbyVoiceRequest(1u, List(4) { floatArrayOf(1f) })
        try {
            assertTrue(VoiceCaptureReadiness.evaluate(true, true, listOf(request), true, true).error
                ?.contains("More than one") == true)
        } finally { request.close() }
    }

    @Test
    fun ordinaryCameraWithoutNearbyVoiceDoesNotRequirePassport() {
        val decision = VoiceCaptureReadiness.evaluate(false, false, null, false, false)
        assertNull(decision.error)
        assertFalse(decision.processVoice)
    }

    @Test
    fun protectedScreenWithoutAnyVoiceRequestRecordsOrdinaryAudio() {
        val decision = VoiceCaptureReadiness.evaluate(true, false, null, false, true)
        assertNull(decision.error)
        assertFalse(decision.processVoice)
    }

    @Test
    fun protectedScreenWithoutVoiceRequestCanRecordSilentVideoWithoutMicrophonePermission() {
        val decision = VoiceCaptureReadiness.evaluate(true, false, null, false, false)
        assertNull(decision.error)
        assertFalse(decision.processVoice)
    }

    @Test
    fun protectedScreenWithLocalPassportProcessesVoiceEvenWithoutNearbyRequest() {
        val decision = VoiceCaptureReadiness.evaluate(true, false, null, true, true)
        assertNull(decision.error)
        assertTrue(decision.processVoice)
    }

    @Test
    fun signaledVoiceWithoutReceivedRequestDoesNotBecomeOrdinaryVideo() {
        val decision = VoiceCaptureReadiness.evaluate(true, true, emptyList(), false, true)
        assertTrue(decision.error?.contains("Voice Passport is missing") == true)
        assertFalse(decision.processVoice)
    }
}
