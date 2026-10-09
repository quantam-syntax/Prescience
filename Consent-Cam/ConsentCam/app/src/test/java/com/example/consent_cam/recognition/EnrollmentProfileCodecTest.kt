package com.example.consent_cam.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentProfileCodecTest {
    @Test
    fun profileRoundTripsWithoutChangingEmbedding() {
        val vector = FloatArray(FACENET_EMBEDDING_DIMENSIONS).also { it[0] = 1f }
        val profile = EnrollmentProfile(FACENET_MODEL_ID, vector.size, vector, 1234)

        val decoded = requireNotNull(EnrollmentProfileCodec.decode(EnrollmentProfileCodec.encode(profile)))
        val decodedVector = decoded.embeddingCopy()

        assertEquals(FACENET_MODEL_ID, decoded.modelId)
        assertEquals(1234, decoded.createdAtEpochMs)
        assertEquals(1f, decodedVector[0], 0f)
        assertTrue(EmbeddingMath.isUnitVector(decodedVector))
        decodedVector.fill(0f)
        decoded.close()
        profile.close()
    }

    @Test
    fun malformedProfileIsRejected() {
        assertNull(EnrollmentProfileCodec.decode(byteArrayOf(1, 2, 3)))
    }
}
