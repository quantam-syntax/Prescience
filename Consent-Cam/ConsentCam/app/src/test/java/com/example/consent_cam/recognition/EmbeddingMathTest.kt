package com.example.consent_cam.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingMathTest {
    @Test
    fun normalizeCreatesUnitVector() {
        val result = requireNotNull(EmbeddingMath.normalize(floatArrayOf(3f, 4f)))

        assertEquals(0.6f, result[0], 0.0001f)
        assertEquals(0.8f, result[1], 0.0001f)
        assertTrue(EmbeddingMath.isUnitVector(result))
    }

    @Test
    fun normalizeRejectsZeroAndNonFiniteVectors() {
        assertNull(EmbeddingMath.normalize(floatArrayOf(0f, 0f)))
        assertNull(EmbeddingMath.normalize(floatArrayOf(Float.NaN, 1f)))
    }

    @Test
    fun cosineHandlesIdenticalAndOrthogonalVectors() {
        assertEquals(1f, EmbeddingMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))!!, 0.0001f)
        assertEquals(0f, EmbeddingMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))!!, 0.0001f)
    }

    @Test
    fun aggregateRejectsDifferentDimensions() {
        assertNull(EmbeddingMath.aggregate(listOf(floatArrayOf(1f), floatArrayOf(1f, 0f))))
        assertNotNull(EmbeddingMath.aggregate(listOf(floatArrayOf(1f, 0f), floatArrayOf(0.9f, 0.1f))))
    }
}

