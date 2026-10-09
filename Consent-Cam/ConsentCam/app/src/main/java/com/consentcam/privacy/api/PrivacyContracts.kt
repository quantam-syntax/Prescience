package com.consentcam.privacy.api

enum class PrivacyMode {
    PROXIMITY,
    PRECISE,
}

enum class PreciseAssociationStatus {
    UNAVAILABLE,
    RESOLVING,
    RESOLVED,
}

data class PreciseAssociationState(
    val status: PreciseAssociationStatus = PreciseAssociationStatus.UNAVAILABLE,
    val protectedTrackingIds: Set<Int> = emptySet(),
) {
    init {
        require((status == PreciseAssociationStatus.RESOLVED) == protectedTrackingIds.isNotEmpty()) {
            "Only a resolved association may carry protected tracking IDs"
        }
    }

    val protectedTrackingId: Int?
        get() = protectedTrackingIds.singleOrNull()

    companion object {
        fun resolving() = PreciseAssociationState(PreciseAssociationStatus.RESOLVING)
        fun resolved(trackingId: Int) = PreciseAssociationState(
            PreciseAssociationStatus.RESOLVED,
            setOf(trackingId),
        )

        fun resolved(trackingIds: Set<Int>) = PreciseAssociationState(
            PreciseAssociationStatus.RESOLVED,
            trackingIds.toSet(),
        )
    }
}

/** A camera-transformed face rectangle expressed entirely in the normalized [0, 1] space. */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(listOf(left, top, right, bottom).all { it in 0f..1f })
        require(left <= right && top <= bottom)
    }

    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

data class FaceObservation(
    val trackingId: Int,
    val normalizedRect: NormalizedRect,
    val timestampNs: Long,
    val quality: Float,
    val rotationDegrees: Float = 0f,
) {
    init {
        require(timestampNs >= 0)
        require(quality.isFinite() && quality in 0f..1f)
        require(rotationDegrees.isFinite())
    }
}

/** Dependency-neutral input to be mapped from Yazeen's FaceMatchState at integration. */
enum class FaceConsentResolution {
    MATCHED_ALLOW,
    MATCHED_PROTECT,
    UNKNOWN,
    AMBIGUOUS,
    INSUFFICIENT_QUALITY,
}

enum class ProtectionSource {
    PRECISE_MATCH,
    PROXIMITY,
    NONE,
}

data class ProtectionDecision(
    val trackingId: Int,
    val source: ProtectionSource,
    val shouldBlur: Boolean,
    val reason: String,
)

data class PrivacyUncertaintyEvent(
    val trackingId: Int,
    val resolution: FaceConsentResolution?,
)

data class ProtectionDecisionBatch(
    val decisions: List<ProtectionDecision>,
    val uncertaintyEvents: List<PrivacyUncertaintyEvent>,
)

data class BlurRegion(
    val trackingId: Int,
    val normalizedRect: NormalizedRect,
    val timestampNs: Long,
    val enabled: Boolean,
    val rotationDegrees: Float = 0f,
)
