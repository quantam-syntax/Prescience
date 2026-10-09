package com.consentcam.privacy.region

import com.consentcam.privacy.api.FaceObservation
import com.consentcam.privacy.api.NormalizedRect
import com.consentcam.privacy.api.ProtectionDecision
import com.consentcam.privacy.api.ProtectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurRegionGeneratorTest {
    @Test
    fun generatePadsClampsAndPreservesTimestamp() {
        val face = FaceObservation(7, NormalizedRect(0f, 0.1f, 0.2f, 0.3f), 99, 1f)
        val decision = ProtectionDecision(7, ProtectionSource.PROXIMITY, true, "test")

        val region = BlurRegionGenerator().generate(listOf(face), listOf(decision)).single()

        assertEquals(0f, region.normalizedRect.left, 0f)
        assertTrue(region.normalizedRect.right > face.normalizedRect.right)
        assertEquals(99L, region.timestampNs)
        assertTrue(region.enabled)
    }

    @Test
    fun generateIncludesDisabledRegionForVisibleFace() {
        val face = FaceObservation(7, NormalizedRect(0.2f, 0.2f, 0.4f, 0.4f), 99, 1f)
        val decision = ProtectionDecision(7, ProtectionSource.NONE, false, "test")

        val region = BlurRegionGenerator().generate(listOf(face), listOf(decision)).single()

        assertFalse(region.enabled)
    }
}

