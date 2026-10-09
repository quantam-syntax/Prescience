package com.example.consent_cam.privacy.rendering

import com.consentcam.privacy.api.BlurRegion
import com.example.consent_cam.vision.FaceDetectionState

/** Regions to conceal on a preview frame. Saved-output protection is a separate effect. */
data class ProtectedFrameState(
    val regions: List<BlurRegion> = emptyList(),
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** Geometry from the same analyzed frame as [regions], used for output-specific mapping. */
    val detectionState: FaceDetectionState? = null,
) {
    val hasFrameGeometry: Boolean
        get() = sourceWidth > 0 && sourceHeight > 0
}
