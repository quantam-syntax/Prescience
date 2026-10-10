package com.atreides.consentvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atreides.voiceconsent.FixedShape8kSeparator
import com.atreides.voiceconsent.SeparatorBackend
import com.atreides.voiceconsent.SeparatorResult
import com.atreides.voiceconsent.SeparatorStatus
import com.atreides.voiceconsent.WindowedTwoSpeakerSeparator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the second and later fixed-shape block offsets for recordings over four seconds. */
@RunWith(AndroidJUnit4::class)
class WindowedSeparatorTest {
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
