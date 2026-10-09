package com.consentcam.privacy.decision

import com.consentcam.privacy.api.FaceConsentResolution
import com.consentcam.privacy.api.FaceObservation
import com.consentcam.privacy.api.NormalizedRect
import com.consentcam.privacy.api.PrivacyMode
import com.consentcam.privacy.api.PreciseAssociationState
import com.consentcam.privacy.api.ProtectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionDecisionCoordinatorTest {
    private val coordinator = ProtectionDecisionCoordinator()
    private val faces = listOf(face(1), face(2))

    @Test
    fun privacyDisabledEmitsNoAutomaticBlur() {
        val result = coordinator.decide(false, PrivacyMode.PROXIMITY, faces, emptyMap(), true)

        assertTrue(result.decisions.none { it.shouldBlur })
    }

    @Test
    fun proximityModeBlursEveryDetectedFaceWhenProtectIsNearby() {
        val result = coordinator.decide(true, PrivacyMode.PROXIMITY, faces, emptyMap(), true)

        assertTrue(result.decisions.all { it.shouldBlur })
        assertTrue(result.decisions.all { it.source == ProtectionSource.PROXIMITY })
    }

    @Test
    fun enhancedModeLeavesUnknownFaceVisibleBesideMatchedAllow() {
        val result = coordinator.decide(
            true,
            PrivacyMode.PRECISE,
            faces,
            mapOf(1 to FaceConsentResolution.MATCHED_ALLOW),
            true,
        )

        assertFalse(result.decisions.single { it.trackingId == 1 }.shouldBlur)
        assertFalse(result.decisions.single { it.trackingId == 2 }.shouldBlur)
    }

    @Test
    fun matchedProtectBlursWithoutProximityFallback() {
        val result = coordinator.decide(
            true,
            PrivacyMode.PRECISE,
            listOf(face(1)),
            mapOf(1 to FaceConsentResolution.MATCHED_PROTECT),
            false,
        )

        assertTrue(result.decisions.single().shouldBlur)
        assertEquals(ProtectionSource.PRECISE_MATCH, result.decisions.single().source)
    }

    @Test
    fun resolvedProtectAssociationBlursOnlyThatFace() {
        val result = coordinator.decide(
            privacyEnabled = true,
            mode = PrivacyMode.PRECISE,
            faces = faces,
            faceResolutions = emptyMap(),
            proximityProtectionActive = true,
            preciseAssociation = PreciseAssociationState.resolved(2),
        )

        assertFalse(result.decisions.single { it.trackingId == 1 }.shouldBlur)
        assertTrue(result.decisions.single { it.trackingId == 2 }.shouldBlur)
        assertEquals(
            ProtectionSource.PRECISE_MATCH,
            result.decisions.single { it.trackingId == 2 }.source,
        )
    }

    @Test
    fun resolvedMultipleProtectAssociationsBlurEveryMatchedFace() {
        val result = coordinator.decide(
            privacyEnabled = true,
            mode = PrivacyMode.PRECISE,
            faces = listOf(face(1), face(2), face(3)),
            faceResolutions = emptyMap(),
            proximityProtectionActive = true,
            preciseAssociation = PreciseAssociationState.resolved(setOf(1, 3)),
        )

        assertTrue(result.decisions.single { it.trackingId == 1 }.shouldBlur)
        assertFalse(result.decisions.single { it.trackingId == 2 }.shouldBlur)
        assertTrue(result.decisions.single { it.trackingId == 3 }.shouldBlur)
    }

    @Test
    fun missingResolvedProtectTrackLeavesVisibleFacesUnblurred() {
        val result = coordinator.decide(
            privacyEnabled = true,
            mode = PrivacyMode.PRECISE,
            faces = faces,
            faceResolutions = emptyMap(),
            proximityProtectionActive = true,
            preciseAssociation = PreciseAssociationState.resolved(99),
        )

        assertTrue(result.decisions.none { it.shouldBlur })
        assertTrue(result.decisions.all { it.source == ProtectionSource.NONE })
    }

    @Test
    fun unresolvedMatchWithoutProximityRemainsVisibleAndEmitsUncertainty() {
        val result = coordinator.decide(
            true,
            PrivacyMode.PRECISE,
            listOf(face(1)),
            mapOf(1 to FaceConsentResolution.AMBIGUOUS),
            false,
        )

        assertFalse(result.decisions.single().shouldBlur)
        assertEquals(FaceConsentResolution.AMBIGUOUS, result.uncertaintyEvents.single().resolution)
    }

    @Test
    fun enhancedModeBlursOnlyConfirmedProtectFaceBesideUncertainFace() {
        val result = coordinator.decide(
            true,
            PrivacyMode.PRECISE,
            faces,
            mapOf(
                1 to FaceConsentResolution.MATCHED_PROTECT,
                2 to FaceConsentResolution.UNKNOWN,
            ),
            true,
        )

        assertTrue(result.decisions.single { it.trackingId == 1 }.shouldBlur)
        assertFalse(result.decisions.single { it.trackingId == 2 }.shouldBlur)
        assertEquals(FaceConsentResolution.UNKNOWN, result.uncertaintyEvents.single().resolution)
    }

    private fun face(id: Int) = FaceObservation(
        trackingId = id,
        normalizedRect = NormalizedRect(0.2f, 0.2f, 0.4f, 0.5f),
        timestampNs = 10,
        quality = 1f,
    )
}

