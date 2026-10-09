package com.example.consent_cam.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceAlignmentTest {
    @Test
    fun eyeRollDoesNotDependOnLandmarkOrdering() {
        val forward = FaceAlignment.eyeLineRollDegrees(10f, 10f, 30f, 20f, false)
        val reverse = FaceAlignment.eyeLineRollDegrees(30f, 20f, 10f, 10f, false)

        assertEquals(requireNotNull(forward), requireNotNull(reverse), 0.0001f)
    }

    @Test
    fun frontCameraMirrorReversesDisplayedRoll() {
        val back = requireNotNull(FaceAlignment.eyeLineRollDegrees(10f, 10f, 30f, 20f, false))
        val front = requireNotNull(FaceAlignment.eyeLineRollDegrees(10f, 10f, 30f, 20f, true))

        assertEquals(-back, front, 0.0001f)
    }

    @Test
    fun unusableEyeGeometryFallsBackToDetectorPose() {
        assertNull(FaceAlignment.eyeLineRollDegrees(10f, 10f, 12f, 11f, false))
    }
}
