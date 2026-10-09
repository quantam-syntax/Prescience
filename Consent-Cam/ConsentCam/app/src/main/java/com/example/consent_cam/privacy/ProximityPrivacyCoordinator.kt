package com.example.consent_cam.privacy

import com.consentcam.privacy.api.BlurRegion
import com.consentcam.privacy.api.FaceObservation
import com.consentcam.privacy.api.FaceConsentResolution
import com.consentcam.privacy.api.PrivacyMode
import com.consentcam.privacy.api.PreciseAssociationState
import com.consentcam.privacy.decision.ProtectionDecisionCoordinator
import com.consentcam.privacy.region.BlurRegionGenerator

/** Android integration boundary from direct BLE proximity to dependency-neutral privacy logic. */
class ProximityPrivacyCoordinator(
    private val decisionCoordinator: ProtectionDecisionCoordinator = ProtectionDecisionCoordinator(),
    private val regionGenerator: BlurRegionGenerator = BlurRegionGenerator(),
) {
    fun regions(
        faces: List<FaceObservation>,
        proximityProtectionActive: Boolean,
        mode: PrivacyMode = PrivacyMode.PROXIMITY,
        preciseAssociation: PreciseAssociationState = PreciseAssociationState(),
        faceResolutions: Map<Int, FaceConsentResolution> = emptyMap(),
        privacyEnabled: Boolean = true,
    ): List<BlurRegion> {
        val decisions = decisionCoordinator.decide(
            privacyEnabled = privacyEnabled,
            mode = mode,
            faces = faces,
            faceResolutions = faceResolutions,
            proximityProtectionActive = proximityProtectionActive,
            preciseAssociation = preciseAssociation,
        )
        return regionGenerator.generate(faces, decisions.decisions)
    }
}
