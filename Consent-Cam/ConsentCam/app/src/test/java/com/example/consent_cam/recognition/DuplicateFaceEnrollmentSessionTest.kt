package com.example.consent_cam.recognition

import com.example.consent_cam.ble.AppearanceConsent
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateFaceEnrollmentSessionTest {
    @Test
    fun restartedSessionForSameEnrollmentReplacesOnlyOldSession() {
        val old = profile(1u, 1f, 0f)
        val new = profile(2u, 1f, 0f)
        val otherPerson = profile(3u, 0f, 1f)
        try {
            assertEquals(listOf(1u), duplicateFaceEnrollmentSessionIds(listOf(old, otherPerson), new))
        } finally {
            old.close()
            new.close()
            otherPerson.close()
        }
    }

    @Test
    fun closeFaceEmbeddingIsNotTreatedAsSameEnrollment() {
        val old = profile(1u, 1f, 0f)
        val similarPerson = profile(2u, 0.999f, 0.04471018f)
        try {
            assertEquals(emptyList<UInt>(), duplicateFaceEnrollmentSessionIds(listOf(old), similarPerson))
        } finally {
            old.close()
            similarPerson.close()
        }
    }

    private fun profile(sessionId: UInt, first: Float, second: Float): SessionProfile = SessionProfile(
        sessionId = sessionId,
        consent = AppearanceConsent.PROTECT,
        modelId = FACENET_MODEL_ID,
        embedding = FloatArray(FACENET_EMBEDDING_DIMENSIONS).also {
            it[0] = first
            it[1] = second
        },
    )
}
