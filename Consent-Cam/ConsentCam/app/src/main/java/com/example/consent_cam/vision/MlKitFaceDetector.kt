package com.example.consent_cam.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.consentcam.privacy.api.FaceObservation
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Offline ML Kit adapter. Recognition and privacy decisions deliberately live elsewhere. */
class MlKitFaceDetector : AutoCloseable {
    private val processing = AtomicBoolean(false)
    private val fallbackTrackingId = AtomicInteger(-1)
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(MINIMUM_FACE_SIZE)
            .enableTracking()
            .build(),
    )

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun analyze(
        imageProxy: ImageProxy,
        mirrorHorizontally: Boolean,
        onResult: (FaceDetectionState) -> Unit,
        captureFaceCrops: Boolean = false,
        onFaceCrops: (List<FaceCrop>) -> Unit = {},
        onError: (String) -> Unit,
    ) {
        if (!processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            processing.set(false)
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val orientedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageProxy.height
        } else {
            imageProxy.width
        }
        val orientedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageProxy.width
        } else {
            imageProxy.height
        }
        val rawCrop = imageProxy.cropRect
        val orientedCrop = FaceCoordinateMapper.rotateCropRect(
            cropRect = FaceCoordinateMapper.IntRect(rawCrop.left, rawCrop.top, rawCrop.right, rawCrop.bottom),
            bufferWidth = imageProxy.width,
            bufferHeight = imageProxy.height,
            rotationDegrees = rotationDegrees,
        )
        val timestampNs = imageProxy.imageInfo.timestamp
        val input = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        detector.process(input)
            .addOnSuccessListener { detectedFaces ->
                val observations = detectedFaces.map { face ->
                    val box = face.boundingBox
                    FaceObservation(
                        // A missing ML Kit ID must never be reused for a different face. A unique
                        // negative ID deliberately causes recognition on the next analyzed frame.
                        trackingId = face.trackingId ?: fallbackTrackingId.getAndDecrement(),
                        normalizedRect = FaceCoordinateMapper.normalize(
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                            sourceWidth = orientedWidth,
                            sourceHeight = orientedHeight,
                            mirrorHorizontally = mirrorHorizontally,
                            cropRect = orientedCrop,
                        ),
                        timestampNs = timestampNs,
                        // ML Kit does not expose a face-detection confidence value.
                        quality = 1f,
                        rotationDegrees = if (mirrorHorizontally) {
                            -face.headEulerAngleZ
                        } else {
                            face.headEulerAngleZ
                        },
                    )
                }
                onResult(
                    FaceDetectionState(
                        faces = observations,
                        sourceWidth = orientedCrop.width,
                        sourceHeight = orientedCrop.height,
                        timestampNs = timestampNs,
                        sensorToBufferTransform = Matrix(imageProxy.imageInfo.sensorToBufferTransformMatrix),
                        bufferWidth = imageProxy.width,
                        bufferHeight = imageProxy.height,
                        orientedCrop = orientedCrop,
                        rotationDegrees = rotationDegrees,
                        mirrorHorizontally = mirrorHorizontally,
                    ),
                )
                if (captureFaceCrops) {
                    onFaceCrops(
                        createFaceCrops(
                            imageProxy = imageProxy,
                            detectedFaces = detectedFaces,
                            observations = observations,
                            rotationDegrees = rotationDegrees,
                            orientedCrop = orientedCrop,
                            mirrorHorizontally = mirrorHorizontally,
                            timestampNs = timestampNs,
                        ),
                    )
                }
            }
            .addOnFailureListener { error ->
                onError("Face detection failed: ${error.message ?: "unknown error"}")
            }
            .addOnCompleteListener {
                processing.set(false)
                imageProxy.close()
            }
    }

    override fun close() {
        detector.close()
    }

    private fun createFaceCrops(
        imageProxy: ImageProxy,
        detectedFaces: List<com.google.mlkit.vision.face.Face>,
        observations: List<FaceObservation>,
        rotationDegrees: Int,
        orientedCrop: FaceCoordinateMapper.IntRect,
        mirrorHorizontally: Boolean,
        timestampNs: Long,
    ): List<FaceCrop> {
        val raw = runCatching { imageProxy.toBitmap() }.getOrNull() ?: return emptyList()
        val oriented = transformBitmap(raw, rotationDegrees.toFloat(), false)
        if (oriented !== raw && !raw.isRecycled) raw.recycle()
        val viewport = Bitmap.createBitmap(
            oriented,
            orientedCrop.left,
            orientedCrop.top,
            orientedCrop.width,
            orientedCrop.height,
        )
        if (viewport !== oriented && !oriented.isRecycled) oriented.recycle()
        val upright = transformBitmap(viewport, 0f, mirrorHorizontally)
        if (upright !== viewport && !viewport.isRecycled) viewport.recycle()
        return try {
            detectedFaces.zip(observations).mapNotNull { (face, observation) ->
                val rect = observation.normalizedRect
                val horizontalPadding = rect.width * 0.18f
                val verticalPadding = rect.height * 0.18f
                val left = ((rect.left - horizontalPadding) * upright.width).toInt()
                    .coerceIn(0, upright.width - 1)
                val top = ((rect.top - verticalPadding) * upright.height).toInt()
                    .coerceIn(0, upright.height - 1)
                val right = ((rect.right + horizontalPadding) * upright.width).toInt()
                    .coerceIn(left + 1, upright.width)
                val bottom = ((rect.bottom + verticalPadding) * upright.height).toInt()
                    .coerceIn(top + 1, upright.height)
                if (right - left < MIN_CROP_PIXELS || bottom - top < MIN_CROP_PIXELS) {
                    return@mapNotNull null
                }
                val crop = Bitmap.createBitmap(upright, left, top, right - left, bottom - top)
                val firstEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                val secondEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                val displayedRoll = if (firstEye != null && secondEye != null) {
                    FaceAlignment.eyeLineRollDegrees(
                        firstX = firstEye.x,
                        firstY = firstEye.y,
                        secondX = secondEye.x,
                        secondY = secondEye.y,
                        mirrorHorizontally = mirrorHorizontally,
                    )
                } else {
                    null
                } ?: if (mirrorHorizontally) -face.headEulerAngleZ else face.headEulerAngleZ
                val displayAligned = transformBitmap(crop, -displayedRoll, false)
                if (displayAligned !== crop && !crop.isRecycled) crop.recycle()
                // Preview coordinates are mirrored for a selfie, but FaceNet must
                // receive the same physical orientation on front and rear cameras.
                val aligned = transformBitmap(displayAligned, 0f, mirrorHorizontally)
                if (aligned !== displayAligned && !displayAligned.isRecycled) displayAligned.recycle()
                FaceCrop(
                    trackingId = observation.trackingId,
                    bitmap = aligned,
                    timestampNs = timestampNs,
                    yawDegrees = if (mirrorHorizontally) -face.headEulerAngleY else face.headEulerAngleY,
                    rollDegrees = displayedRoll,
                    quality = (minOf(right - left, bottom - top) / 240f).coerceIn(0f, 1f),
                )
            }
        } finally {
            if (!upright.isRecycled) upright.recycle()
        }
    }

    private fun transformBitmap(source: Bitmap, rotation: Float, mirror: Boolean): Bitmap {
        if (rotation == 0f && !mirror) return source
        val matrix = Matrix().apply {
            if (rotation != 0f) postRotate(rotation)
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private companion object {
        // Detect faces down to roughly 6% of the frame width while retaining multi-face tracking.
        const val MINIMUM_FACE_SIZE = 0.06f
        const val MIN_CROP_PIXELS = 96
    }
}
