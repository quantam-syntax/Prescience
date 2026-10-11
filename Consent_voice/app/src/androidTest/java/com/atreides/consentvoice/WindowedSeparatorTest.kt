package com.atreides.consentvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atreides.voiceconsent.FixedShape8kSeparator
import com.atreides.voiceconsent.SeparatorBackend
import com.atreides.voiceconsent.SeparatorResult
import com.atreides.voiceconsent.SeparatorStatus
import com.atreides.voiceconsent.WindowedTwoSpeakerSeparator
import com.atreides.voiceconsent.resamplePcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the second and later fixed-shape block offsets for recordings over four seconds. */
@RunWith(AndroidJUnit4::class)
class WindowedSeparatorTest {
    @Test
    fun shortTailDoesNotRunAnExtraMostlyPaddedBlock() {
        var calls = 0
        var finalInputSample = 0f
        val separator = WindowedTwoSpeakerSeparator(object : FixedShape8kSeparator {
            override val status = SeparatorStatus(SeparatorBackend.QAIRT_HTP, true, "test")
            override fun separate4Seconds(pcm8k: FloatArray): SeparatorResult {
                calls++
                finalInputSample = pcm8k.last()
                return SeparatorResult.Success(pcm8k.copyOf(), pcm8k.copyOf(), SeparatorBackend.QAIRT_HTP)
            }
        })
        val input = FloatArray(16_000 * 5 + 16_000 / 2) { 0.1f }
        val result = separator.separate(input) as SeparatorResult.Success
        assertEquals(2, calls)
        assertEquals("Final model block should end with real audio", 0.1f, finalInputSample, 0.01f)
        assertEquals(input.size, result.sourceA16k.size)
        assertTrue(result.sourceA16k.last().isFinite())
    }

    @Test
    fun sourceOrderRemainsStableWhenModelSwapsOutputsBetweenBlocks() {
        var calls = 0
        val separator = WindowedTwoSpeakerSeparator(object : FixedShape8kSeparator {
            override val status = SeparatorStatus(SeparatorBackend.QAIRT_HTP, true, "test")
            override fun separate4Seconds(pcm8k: FloatArray): SeparatorResult {
                val start = calls++ * 16_000
                val first = FloatArray(32_000) { index ->
                    kotlin.math.sin(2.0 * Math.PI * 200 * (start + index) / 8_000).toFloat()
                }
                val second = FloatArray(32_000) { index ->
                    kotlin.math.sin(2.0 * Math.PI * 500 * (start + index) / 8_000).toFloat()
                }
                return if (calls % 2 == 0) {
                    SeparatorResult.Success(second, first, SeparatorBackend.QAIRT_HTP)
                } else {
                    SeparatorResult.Success(first, second, SeparatorBackend.QAIRT_HTP)
                }
            }
        })
        val result = separator.separate(FloatArray(16_000 * 8)) as SeparatorResult.Success
        fun toneCorrelation(samples: FloatArray, hz: Int): Double {
            var dot = 0.0
            var norm = 0.0
            for (index in samples.indices step 8) {
                val tone = kotlin.math.sin(2.0 * Math.PI * hz * index / 16_000)
                dot += samples[index] * tone
                norm += samples[index] * samples[index]
            }
            return dot / kotlin.math.sqrt(norm * (samples.size / 8.0) / 2.0)
        }
        assertEquals(3, calls)
        assertTrue(toneCorrelation(result.sourceA16k, 200) > 0.95)
        assertTrue(toneCorrelation(result.sourceB16k, 500) > 0.95)
    }

    @Test
    fun downsamplingRemovesFrequenciesAboveEightKilohertzNyquist() {
        fun tone(hz: Int) = FloatArray(16_000) { index ->
            kotlin.math.sin(2.0 * Math.PI * hz * index / 16_000).toFloat()
        }
        fun rms(samples: FloatArray): Double = kotlin.math.sqrt(
            samples.drop(100).dropLast(100).sumOf { (it * it).toDouble() } / (samples.size - 200),
        )
        val speechBand = resamplePcm(tone(1_000), 16_000, 8_000)
        val aliasedBand = resamplePcm(tone(6_000), 16_000, 8_000)
        assertTrue("Speech band should be retained", rms(speechBand) > 0.6)
        assertTrue("Above-Nyquist energy should not fold into speech", rms(aliasedBand) < 0.08)
    }

    @Test
    fun separatesRecordingLongerThanOneBlock() {
        val separator = WindowedTwoSpeakerSeparator(object : FixedShape8kSeparator {
            override val status = SeparatorStatus(SeparatorBackend.QAIRT_HTP, true, "test")
            override fun separate4Seconds(pcm8k: FloatArray) = SeparatorResult.Success(
                sourceA16k = pcm8k.copyOf(),
                sourceB16k = pcm8k.copyOf(),
                backend = SeparatorBackend.QAIRT_HTP,
            )
        })
        val input = FloatArray(16_000 * 12) { it / 1_000_000f }
        val result = separator.separate(input) as SeparatorResult.Success
        assertEquals(input.size, result.sourceA16k.size)
        assertEquals(input.size, result.sourceB16k.size)
    }
}
