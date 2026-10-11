package com.atreides.voiceconsent

import kotlin.math.PI
import kotlin.math.exp

/** Gentle speech EQ for the retained 8 kHz separator source, after identity matching. */
object SeparatedVoiceClarifier {
    fun clarify(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val highPassPole = pole(80.0)
        val mudPole = pole(320.0)
        val presencePole = pole(1_200.0)
        var previousInput = 0f
        var highPassState = 0f
        var mudState = 0f
        var presenceState = 0f
        return FloatArray(samples.size) { index ->
            val input = samples[index]
            highPassState = highPassPole * (highPassState + input - previousInput)
            previousInput = input
            mudState = mudPole * mudState + (1f - mudPole) * highPassState
            val lessMud = highPassState - 0.18f * mudState
            presenceState = presencePole * presenceState + (1f - presencePole) * lessMud
            lessMud + 0.25f * (lessMud - presenceState)
        }
    }

    private fun pole(cutoffHz: Double): Float =
        exp(-2.0 * PI * cutoffHz / VOICE_SAMPLE_RATE_HZ).toFloat()
}
