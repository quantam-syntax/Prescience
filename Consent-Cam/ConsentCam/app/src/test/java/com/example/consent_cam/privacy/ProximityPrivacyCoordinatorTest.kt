package com.example.consent_cam.privacy

import com.consentcam.privacy.api.FaceObservation
import com.consentcam.privacy.api.NormalizedRect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityPrivacyCoordinatorTest {
    private val face = FaceObservation(1, NormalizedRect(0.2f, 0.2f, 0.4f, 0.5f), 10, 1f)

    @Test
    fun protectEnablesPaddedFaceRegion() {
        val region = ProximityPrivacyCoordinator().regions(listOf(face), true).single()

        assertTrue(region.enabled)
        assertTrue(region.normalizedRect.left < face.normalizedRect.left)
        assertTrue(region.normalizedRect.bottom > face.normalizedRect.bottom)
    }

    @Test
    fun inactiveProximityLeavesFaceRegionDisabled() {
        val region = ProximityPrivacyCoordinator().regions(listOf(face), false).single()

        assertFalse(region.enabled)
    }
}
