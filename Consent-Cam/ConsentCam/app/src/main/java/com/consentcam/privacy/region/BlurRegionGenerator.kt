package com.consentcam.privacy.region

import com.consentcam.privacy.api.BlurRegion
import com.consentcam.privacy.api.FaceObservation
import com.consentcam.privacy.api.NormalizedRect
import com.consentcam.privacy.api.ProtectionDecision

class BlurRegionGenerator(
    private val horizontalPaddingFraction: Float = 0.20f,
    private val verticalPaddingFraction: Float = 0.25f,
) {
    init {
        require(horizontalPaddingFraction >= 0f && horizontalPaddingFraction.isFinite())
        require(verticalPaddingFraction >= 0f && verticalPaddingFraction.isFinite())
    }

    fun generate(
        faces: List<FaceObservation>,
        decisions: List<ProtectionDecision>,
    ): List<BlurRegion> {
        val decisionsById = decisions.associateBy(ProtectionDecision::trackingId)
        return faces.map { face ->
            val decision = requireNotNull(decisionsById[face.trackingId]) {
                "Missing protection decision for tracking ID ${face.trackingId}"
            }
            BlurRegion(
                trackingId = face.trackingId,
                normalizedRect = padAndClamp(face.normalizedRect),
                timestampNs = face.timestampNs,
                enabled = decision.shouldBlur,
                rotationDegrees = face.rotationDegrees,
            )
        }
    }

    private fun padAndClamp(rect: NormalizedRect): NormalizedRect {
        val horizontalPadding = rect.width * horizontalPaddingFraction
        val verticalPadding = rect.height * verticalPaddingFraction
        return NormalizedRect(
            left = (rect.left - horizontalPadding).coerceIn(0f, 1f),
            top = (rect.top - verticalPadding).coerceIn(0f, 1f),
            right = (rect.right + horizontalPadding).coerceIn(0f, 1f),
            bottom = (rect.bottom + verticalPadding).coerceIn(0f, 1f),
        )
    }
}

