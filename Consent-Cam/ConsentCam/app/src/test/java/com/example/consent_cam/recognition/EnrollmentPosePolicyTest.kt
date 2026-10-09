package com.example.consent_cam.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentPosePolicyTest {
    @Test
    fun enrollmentRequiresFrontSideOppositeSideThenFront() {
        val front = evaluate(accepted = 0, yaw = 2f)
        val firstSide = evaluate(accepted = 1, yaw = 18f)
        val sameSide = evaluate(accepted = 2, yaw = 20f, firstSideSign = firstSide.firstSideSign)
        val otherSide = evaluate(accepted = 2, yaw = -18f, firstSideSign = firstSide.firstSideSign)
        val finalFront = evaluate(accepted = 3, yaw = -3f, firstSideSign = firstSide.firstSideSign)

        assertTrue(front.accepted)
        assertTrue(firstSide.accepted)
        assertFalse(sameSide.accepted)
        assertTrue(otherSide.accepted)
        assertTrue(finalFront.accepted)
    }

    @Test
    fun poorCropAndExcessiveRollAreRejected() {
        assertFalse(evaluate(accepted = 0, yaw = 0f, quality = 0.2f).accepted)
        assertFalse(evaluate(accepted = 0, yaw = 0f, roll = 30f).accepted)
    }

    private fun evaluate(
        accepted: Int,
        yaw: Float,
        roll: Float = 0f,
        quality: Float = 1f,
        firstSideSign: Float? = null,
    ) = EnrollmentPosePolicy.evaluate(accepted, quality, yaw, roll, firstSideSign)
}
