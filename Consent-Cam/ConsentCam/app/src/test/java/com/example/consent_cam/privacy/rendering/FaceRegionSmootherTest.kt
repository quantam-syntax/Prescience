package com.example.consent_cam.privacy.rendering

import com.consentcam.privacy.api.BlurRegion
import com.consentcam.privacy.api.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceRegionSmootherTest {
    @Test
    fun updateInterpolatesMovement() {
        val smoother = FaceRegionSmoother(smoothingAlpha = 0.5f)
        smoother.update(listOf(region(0.1f, 100)), 100, true)

        val result = smoother.update(listOf(region(0.3f, 200)), 200, true).single()

        assertEquals(0.2f, result.normalizedRect.left, 0.0001f)
    }

    @Test
    fun missingDetectionIsHeldBrieflyThenRemoved() {
        val smoother = FaceRegionSmoother(missedDetectionHoldNs = 300)
        smoother.update(listOf(region(0.1f, 100)), 100, true)

        assertEquals(1, smoother.update(emptyList(), 400, true).size)
        assertTrue(smoother.update(emptyList(), 401, true).isEmpty())
    }

    @Test
    fun disablingProtectionClearsHeldRegionsImmediately() {
        val smoother = FaceRegionSmoother()
        smoother.update(listOf(region(0.1f, 100)), 100, true)

        assertTrue(smoother.update(emptyList(), 101, false).isEmpty())
    }

    @Test
    fun changedTrackingIdIsReassociatedByOverlap() {
        val smoother = FaceRegionSmoother(smoothingAlpha = 0.5f)
        smoother.update(listOf(region(0.1f, 100, trackingId = 7)), 100, true)

        val result = smoother.update(
            listOf(region(0.12f, 200, trackingId = 19)),
            200,
            true,
        ).single()

        assertEquals(19, result.trackingId)
        assertEquals(0.11f, result.normalizedRect.left, 0.0001f)
    }

    private fun region(left: Float, timestampNs: Long, trackingId: Int = 7) = BlurRegion(
        trackingId = trackingId,
        normalizedRect = NormalizedRect(left, 0.1f, left + 0.2f, 0.4f),
        timestampNs = timestampNs,
        enabled = true,
    )
}
