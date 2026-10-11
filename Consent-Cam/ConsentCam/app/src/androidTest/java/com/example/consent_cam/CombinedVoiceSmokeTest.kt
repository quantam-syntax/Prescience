package com.example.consent_cam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atreides.consentvoice.QairtFixedShapeSeparator
import com.atreides.voiceconsent.SeparatorResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Safe targeted check: no enrollment, camera profile, or media is changed. */
@RunWith(AndroidJUnit4::class)
class CombinedVoiceSmokeTest {
    @Test fun combinedApkRunsTwoSecondVoiceModelOnHtp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = FloatArray(32_000) { index ->
            (kotlin.math.sin(2.0 * Math.PI * 220 * index / 16_000) * 0.1).toFloat()
        }
        QairtFixedShapeSeparator(context).use { separator ->
            assertTrue(separator.status.detail, separator.status.available)
            val result = separator.separate2Seconds(input)
            assertTrue(result.toString(), result is SeparatorResult.Success)
            result as SeparatorResult.Success
            assertEquals(input.size, result.sourceA16k.size)
            assertEquals(input.size, result.sourceB16k.size)
            assertTrue(result.sourceA16k.all { it.isFinite() })
            assertTrue(result.sourceB16k.all { it.isFinite() })
        }
    }
}
