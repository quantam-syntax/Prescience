package com.atreides.faceguidance

/** Camera poses used only to guide voice enrollment; no face identity is created. */
enum class EnrollmentCameraPose(val label: String, val instruction: String) {
    CENTER("Front", "Hold the phone in front of your face"),
    LEFT("Left", "Hold the phone slightly to the left and look at it"),
    RIGHT("Right", "Hold the phone slightly to the right and look at it"),
    FARTHER("Farther", "Move the phone slightly farther away and face it"),
}

enum class GuidanceStatus { SEARCHING, ADJUSTING, HOLDING, READY, ERROR }

data class FaceGuidanceState(
    val pose: EnrollmentCameraPose,
    val status: GuidanceStatus,
    val message: String,
    val holdProgress: Float = 0f,
    val faceCount: Int = 0,
    val faceWidthRatio: Float? = null,
    val yawDegrees: Float? = null,
) {
    val canRecord: Boolean get() = status == GuidanceStatus.READY
}
