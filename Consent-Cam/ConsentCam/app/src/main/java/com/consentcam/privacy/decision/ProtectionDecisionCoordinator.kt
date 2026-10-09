package com.consentcam.privacy.decision

import com.consentcam.privacy.api.FaceConsentResolution
import com.consentcam.privacy.api.FaceObservation
import com.consentcam.privacy.api.PrivacyMode
import com.consentcam.privacy.api.PreciseAssociationState
import com.consentcam.privacy.api.PreciseAssociationStatus
import com.consentcam.privacy.api.PrivacyUncertaintyEvent
import com.consentcam.privacy.api.ProtectionDecision
import com.consentcam.privacy.api.ProtectionDecisionBatch
import com.consentcam.privacy.api.ProtectionSource

/** Pure implementation of the documented privacy-decision precedence. */
class ProtectionDecisionCoordinator {
    fun decide(
        privacyEnabled: Boolean,
        mode: PrivacyMode,
        faces: List<FaceObservation>,
        faceResolutions: Map<Int, FaceConsentResolution>,
        proximityProtectionActive: Boolean,
        preciseAssociation: PreciseAssociationState = PreciseAssociationState(),
    ): ProtectionDecisionBatch {
        require(faces.map(FaceObservation::trackingId).distinct().size == faces.size) {
            "Tracking IDs must be unique within a frame"
        }

        if (!privacyEnabled) {
            return ProtectionDecisionBatch(
                decisions = faces.map { face ->
                    decision(face.trackingId, ProtectionSource.NONE, false, "Privacy disabled")
                },
                uncertaintyEvents = emptyList(),
            )
        }

        if (mode == PrivacyMode.PROXIMITY) {
            return ProtectionDecisionBatch(
                decisions = faces.map { face ->
                    if (proximityProtectionActive) {
                        decision(
                            face.trackingId,
                            ProtectionSource.PROXIMITY,
                            true,
                            if (faces.size == 1) {
                                "Nearby PROTECT session with one detected face"
                            } else {
                                "Nearby PROTECT session cannot be associated in a multi-face frame"
                            },
                        )
                    } else {
                        decision(
                            face.trackingId,
                            ProtectionSource.NONE,
                            false,
                            "No nearby verified PROTECT session",
                        )
                    }
                },
                uncertaintyEvents = emptyList(),
            )
        }

        val visibleIds = faces.mapTo(mutableSetOf(), FaceObservation::trackingId)
        val resolvedProtectedIds = preciseAssociation.protectedTrackingIds.intersect(visibleIds)
        if (
            preciseAssociation.status == PreciseAssociationStatus.RESOLVED &&
            resolvedProtectedIds == preciseAssociation.protectedTrackingIds
        ) {
            return ProtectionDecisionBatch(
                decisions = faces.map { face ->
                    if (face.trackingId in resolvedProtectedIds) {
                        decision(
                            face.trackingId,
                            ProtectionSource.PRECISE_MATCH,
                            true,
                            "Associated with the active PROTECT session",
                        )
                    } else {
                        decision(
                            face.trackingId,
                            ProtectionSource.NONE,
                            false,
                            "Not associated with the active PROTECT session",
                        )
                    }
                },
                uncertaintyEvents = emptyList(),
            )
        }

        val uncertainties = mutableListOf<PrivacyUncertaintyEvent>()
        val decisions = faces.map { face ->
            when (val resolution = faceResolutions[face.trackingId]) {
                FaceConsentResolution.MATCHED_PROTECT -> decision(
                    face.trackingId,
                    ProtectionSource.PRECISE_MATCH,
                    true,
                    "Confidently matched PROTECT session",
                )

                FaceConsentResolution.MATCHED_ALLOW -> decision(
                    face.trackingId,
                    ProtectionSource.PRECISE_MATCH,
                    false,
                    "Confidently matched ALLOW session",
                )

                else -> {
                    uncertainties += PrivacyUncertaintyEvent(face.trackingId, resolution)
                    // Enhanced mode is deliberately selective: an unknown, ambiguous or
                    // low-quality face must never be treated as a nearby PROTECT participant.
                    // The caller can choose PROXIMITY mode when broad fallback is desired.
                    decision(
                        face.trackingId,
                        ProtectionSource.NONE,
                        false,
                        "Precise match unresolved; face remains visible",
                    )
                }
            }
        }
        return ProtectionDecisionBatch(decisions, uncertainties)
    }

    private fun decision(
        trackingId: Int,
        source: ProtectionSource,
        shouldBlur: Boolean,
        reason: String,
    ) = ProtectionDecision(trackingId, source, shouldBlur, reason)
}

