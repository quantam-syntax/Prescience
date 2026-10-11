package com.atreides.consentvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atreides.voiceconsent.SeparatedVoiceClarifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeparatedVoiceClarifierTest {
    @Test
    fun trimsMudAndGentlyLiftsConsonantBand() {
        fun tone(hz: Int) = FloatArray(32_000) { index ->
            (0.1 * kotlin.math.sin(2.0 * Math.PI * hz * index / 16_000)).toFloat()
        }
        fun rms(samples: FloatArray): Double = kotlin.math.sqrt(
            samples.drop(1_600).sumOf { (it * it).toDouble() } / (samples.size - 1_600),
        )
        val bass = tone(200)
        val speech = tone(2_000)
        val bassRatio = rms(SeparatedVoiceClarifier.clarify(bass)) / rms(bass)
        val speechRatio = rms(SeparatedVoiceClarifier.clarify(speech)) / rms(speech)
        assertTrue("Low speech muddiness should be reduced", bassRatio in 0.7..0.95)
        assertTrue("Speech detail boost should remain gentle", speechRatio in 1.05..1.3)
        assertTrue(SeparatedVoiceClarifier.clarify(FloatArray(1_000)).all { it == 0f })
        assertEquals(speech.size, SeparatedVoiceClarifier.clarify(speech).size)
    }
}
