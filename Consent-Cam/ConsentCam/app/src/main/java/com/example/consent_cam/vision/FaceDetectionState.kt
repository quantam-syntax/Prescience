package com.example.consent_cam.vision

import android.graphics.Matrix
import com.consentcam.privacy.api.FaceObservation

/** Latest detector result in the upright, display-oriented analysis coordinate space. */
data class FaceDetectionState(
    val faces: List<FaceObservation> = emptyList(),
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val timestampNs: Long = 0,
    /** CameraX transform from sensor active-array coordinates to this analysis buffer. */
    val sensorToBufferTransform: Matrix? = null,
    /** Full analysis buffer dimensions before CameraX's shared viewport crop. */
    val bufferWidth: Int = 0,
    val bufferHeight: Int = 0,
    /** The analysis crop expressed in the upright ML Kit coordinate space. */
    val orientedCrop: FaceCoordinateMapper.IntRect? = null,
    val rotationDegrees: Int = 0,
    val mirrorHorizontally: Boolean = false,
) {
    val hasFrameGeometry: Boolean
        get() = sourceWidth > 0 && sourceHeight > 0

    val hasOutputTransform: Boolean
        get() = sensorToBufferTransform != null && bufferWidth > 0 && bufferHeight > 0 && orientedCrop != null
}
