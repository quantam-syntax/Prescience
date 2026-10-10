package com.atreides.faceguidance

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Front-camera enrollment guide. It emits geometry and pose state only; it never stores a frame,
 * recognizes a person, opens the microphone, or evaluates audio quality.
 */
class FaceGuidanceAnalyzer(
    initialPose: EnrollmentCameraPose = EnrollmentCameraPose.CENTER,
    private val onState: (FaceGuidanceState) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    @Volatile private var targetPose = initialPose
    private val processing = AtomicBoolean(false)
    private var readySinceMs: Long? = null
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .build(),
    )

    fun setTargetPose(pose: EnrollmentCameraPose) {
        targetPose = pose
        readySinceMs = null
        onState(FaceGuidanceState(pose, GuidanceStatus.SEARCHING, pose.instruction))
    }

    override fun analyze(image: ImageProxy) {
        if (!processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        val mediaImage = image.image
        if (mediaImage == null) {
            processing.set(false)
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val width = if (rotation == 90 || rotation == 270) image.height else image.width
        val height = if (rotation == 90 || rotation == 270) image.width else image.height
        detector.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener { faces -> evaluate(faces, width, height) }
            .addOnFailureListener { error ->
                readySinceMs = null
                onState(
                    FaceGuidanceState(
                        targetPose,
                        GuidanceStatus.ERROR,
                        "Face guidance unavailable: ${error.message ?: "detector error"}",
                    ),
                )
            }
            .addOnCompleteListener {
                processing.set(false)
                image.close()
            }
    }

    private fun evaluate(faces: List<Face>, width: Int, height: Int) {
        val pose = targetPose
        if (faces.size != 1) {
            readySinceMs = null
            val message = if (faces.isEmpty()) "Move your face into the guide" else "Only one person should be visible"
            onState(FaceGuidanceState(pose, GuidanceStatus.SEARCHING, message, faceCount = faces.size))
            return
        }
        val face = faces.single()
        val box = face.boundingBox
        val widthRatio = box.width().toFloat() / width.coerceAtLeast(1)
        val centerX = box.centerX().toFloat() / width.coerceAtLeast(1)
        val centerY = box.centerY().toFloat() / height.coerceAtLeast(1)
        val yaw = face.headEulerAngleY
        val roll = face.headEulerAngleZ

        val adjustment = when {
            centerX !in 0.18f..0.82f || centerY !in 0.16f..0.82f -> "Center your face in the guide"
            abs(roll) > 18f -> "Keep the phone level"
            pose != EnrollmentCameraPose.FARTHER && widthRatio < 0.20f -> "Move the phone a little closer"
            pose != EnrollmentCameraPose.FARTHER && widthRatio > 0.65f -> "Move the phone a little farther away"
            pose == EnrollmentCameraPose.CENTER && abs(yaw) > 15f -> "Face the phone directly"
            pose == EnrollmentCameraPose.LEFT && yaw !in 8f..42f -> "Move to the left position and look at the phone"
            pose == EnrollmentCameraPose.RIGHT && yaw !in -42f..-8f -> "Move to the right position and look at the phone"
            pose == EnrollmentCameraPose.FARTHER && widthRatio !in 0.12f..0.36f ->
                if (widthRatio > 0.36f) "Move the phone farther away" else "Move the phone slightly closer"
            pose == EnrollmentCameraPose.FARTHER && abs(yaw) > 18f -> "Face the phone directly"
            else -> null
        }
        if (adjustment != null) {
            readySinceMs = null
            onState(
                FaceGuidanceState(
                    pose,
                    GuidanceStatus.ADJUSTING,
                    adjustment,
                    faceCount = 1,
                    faceWidthRatio = widthRatio,
                    yawDegrees = yaw,
                ),
            )
            return
        }

        val now = SystemClock.elapsedRealtime()
        val since = readySinceMs ?: now.also { readySinceMs = it }
        val progress = ((now - since).toFloat() / HOLD_DURATION_MS).coerceIn(0f, 1f)
        val ready = progress >= 1f
        onState(
            FaceGuidanceState(
                pose,
                if (ready) GuidanceStatus.READY else GuidanceStatus.HOLDING,
                if (ready) "Position ready" else "Hold steady",
                holdProgress = progress,
                faceCount = 1,
                faceWidthRatio = widthRatio,
                yawDegrees = yaw,
            ),
        )
    }

    override fun close() = detector.close()

    private companion object {
        const val HOLD_DURATION_MS = 650L
    }
}
