package com.example.consent_cam.camera

import com.atreides.voiceconsent.CoherentLeakageSuppressor
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedVoiceLeakageCleanupTest {
    @Test
    fun strongerCleanupReducesSharedVoiceWithoutRemovingIndependentVoice() {
        val length = 16_000
        val retainedSpeaker = FloatArray(length) { index ->
            sin(2.0 * PI * 310 * index / 16_000).toFloat()
        }
        val protectedSpeaker = FloatArray(length) { index ->
            sin(2.0 * PI * 970 * index / 16_000).toFloat()
        }
        val separatedRetained = FloatArray(length) { index ->
            retainedSpeaker[index] + protectedSpeaker[index] * 0.25f
        }
        val gentle = CoherentLeakageSuppressor.suppress(separatedRetained, protectedSpeaker)
        val stronger = CoherentLeakageSuppressor.suppress(separatedRetained, protectedSpeaker, strength = 0.85)

        assertEquals(length, stronger.size)
        assertTrue(stronger.all(Float::isFinite))
        assertTrue(projection(stronger, protectedSpeaker) < projection(gentle, protectedSpeaker) * 0.7)
        assertTrue(projection(stronger, retainedSpeaker) > 0.9)
    }

    @Test
    fun spectralDominanceReducesPhaseDistortedLeakageAndKeepsIndependentVoice() {
        val length = 32_000
        val retainedSpeaker = FloatArray(length) { index ->
            sin(2.0 * PI * 310 * index / 16_000).toFloat()
        }
        val protectedSpeaker = FloatArray(length) { index ->
            sin(2.0 * PI * 970 * index / 16_000).toFloat()
        }
        val separatedRetained = FloatArray(length) { index ->
            val phaseFlip = if (index / 320 % 2 == 0) 1f else -1f
            retainedSpeaker[index] + 0.25f * phaseFlip * protectedSpeaker[index]
        }
        val coherentOnly = CoherentLeakageSuppressor.suppress(
            separatedRetained, protectedSpeaker, strength = 0.85,
        )
        val protectedVideo = CoherentLeakageSuppressor.suppress(
            separatedRetained,
            protectedSpeaker,
            strength = 0.85,
            spectralDominanceWeight = 0.75,
        )

        assertTrue(protectedVideo.all(Float::isFinite))
        assertTrue(errorRms(protectedVideo, retainedSpeaker) < errorRms(coherentOnly, retainedSpeaker))
        assertTrue(projection(protectedVideo, retainedSpeaker) > 0.85)
    }

    @Test
    fun adaptiveLevelMatchReducesQuietProtectedLeakage() {
        val length = 32_000
        val retainedSpeaker = FloatArray(length) { index ->
            sin(2.0 * PI * 310 * index / 16_000).toFloat()
        }
        val protectedSpeaker = FloatArray(length) { index ->
            val level = if (index < length / 2) 1f else 0.22f
            level * sin(2.0 * PI * 970 * index / 16_000).toFloat()
        }
        val separatedRetained = FloatArray(length) { index ->
            retainedSpeaker[index] + 0.25f * protectedSpeaker[index]
        }
        val fixed = CoherentLeakageSuppressor.suppress(
            separatedRetained, protectedSpeaker,
            strength = 0.85, spectralDominanceWeight = 0.75,
        )
        val adaptive = CoherentLeakageSuppressor.suppress(
            separatedRetained, protectedSpeaker,
            strength = 0.85, spectralDominanceWeight = 0.75,
            adaptiveLevelMatch = true,
        )
        val quietRange = length / 2 until length
        val quietProtected = protectedSpeaker.copyOfRange(quietRange.first, quietRange.last + 1)
        val fixedQuiet = fixed.copyOfRange(quietRange.first, quietRange.last + 1)
        val adaptiveQuiet = adaptive.copyOfRange(quietRange.first, quietRange.last + 1)
        val protectedLevelFixed = projection(fixedQuiet, quietProtected)
        val protectedLevelAdaptive = projection(adaptiveQuiet, quietProtected)
        assertTrue(
            "adaptive=$protectedLevelAdaptive fixed=$protectedLevelFixed",
            protectedLevelAdaptive < protectedLevelFixed,
        )
        assertTrue(projection(adaptive, retainedSpeaker) > 0.85)
        assertTrue(errorRms(adaptive, retainedSpeaker, quietRange) < errorRms(fixed, retainedSpeaker, quietRange) * 1.1)
    }

    private fun projection(signal: FloatArray, reference: FloatArray): Double =
        signal.indices.sumOf { signal[it].toDouble() * reference[it] } /
            reference.sumOf { it.toDouble() * it }

    private fun errorRms(signal: FloatArray, reference: FloatArray): Double =
        errorRms(signal, reference, signal.indices)

    private fun errorRms(signal: FloatArray, reference: FloatArray, range: IntRange): Double =
        sqrt(range.sumOf {
            val error = signal[it].toDouble() - reference[it]
            error * error
        } / range.count())
}
