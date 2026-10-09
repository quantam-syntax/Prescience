package com.example.consent_cam.vision

import kotlin.math.atan2

object FaceAlignment {
    /** Returns the displayed eye-line roll in degrees, independent of landmark ordering. */
    fun eyeLineRollDegrees(
        firstX: Float,
        firstY: Float,
        secondX: Float,
        secondY: Float,
        mirrorHorizontally: Boolean,
    ): Float? {
        if (listOf(firstX, firstY, secondX, secondY).any { !it.isFinite() }) return null
        val (leftX, leftY, rightX, rightY) = if (firstX <= secondX) {
            listOf(firstX, firstY, secondX, secondY)
        } else {
            listOf(secondX, secondY, firstX, firstY)
        }
        val horizontalDistance = rightX - leftX
        if (horizontalDistance < MINIMUM_EYE_SEPARATION_PIXELS) return null
        val raw = Math.toDegrees(atan2((rightY - leftY).toDouble(), horizontalDistance.toDouble())).toFloat()
        return if (mirrorHorizontally) -raw else raw
    }

    private const val MINIMUM_EYE_SEPARATION_PIXELS = 4f
}
