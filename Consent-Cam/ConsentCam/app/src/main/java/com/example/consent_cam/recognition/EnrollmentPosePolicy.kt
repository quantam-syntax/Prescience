package com.example.consent_cam.recognition

import kotlin.math.abs
import kotlin.math.sign

data class EnrollmentPoseDecision(
    val accepted: Boolean,
    val status: String,
    val firstSideSign: Float? = null,
)

/** Pure pose checks for a repeatable five-sample enrollment sequence. */
object EnrollmentPosePolicy {
    fun evaluate(
        acceptedSamples: Int,
        quality: Float,
        yawDegrees: Float,
        rollDegrees: Float,
        firstSideSign: Float?,
    ): EnrollmentPoseDecision {
        if (quality < MINIMUM_CROP_QUALITY || abs(rollDegrees) > MAX_ROLL_DEGREES) {
            return EnrollmentPoseDecision(false, "Hold steady with your face closer to the camera", firstSideSign)
        }
        return when (acceptedSamples) {
            0, 3, 4 -> if (abs(yawDegrees) <= MAX_FRONTAL_YAW_DEGREES) {
                EnrollmentPoseDecision(true, "Pose accepted", firstSideSign)
            } else {
                EnrollmentPoseDecision(false, "Look straight at the camera", firstSideSign)
            }
            1 -> if (abs(yawDegrees) in MIN_SIDE_YAW_DEGREES..MAX_SIDE_YAW_DEGREES) {
                EnrollmentPoseDecision(true, "Pose accepted", yawDegrees.sign)
            } else {
                EnrollmentPoseDecision(false, "Turn your face slightly to one side", firstSideSign)
            }
            2 -> if (
                firstSideSign != null &&
                abs(yawDegrees) in MIN_SIDE_YAW_DEGREES..MAX_SIDE_YAW_DEGREES &&
                yawDegrees.sign != firstSideSign
            ) {
                EnrollmentPoseDecision(true, "Pose accepted", firstSideSign)
            } else {
                EnrollmentPoseDecision(false, "Turn your face slightly to the other side", firstSideSign)
            }
            else -> EnrollmentPoseDecision(false, "Enrollment already has enough samples", firstSideSign)
        }
    }

    private const val MINIMUM_CROP_QUALITY = 0.45f
    private const val MAX_ROLL_DEGREES = 22f
    private const val MAX_FRONTAL_YAW_DEGREES = 14f
    private const val MIN_SIDE_YAW_DEGREES = 10f
    private const val MAX_SIDE_YAW_DEGREES = 35f
}
