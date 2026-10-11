package com.atreides.consentvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atreides.voiceconsent.CoherentLeakageSuppressor
import com.atreides.voiceconsent.FixedShape16kSeparator
import com.atreides.voiceconsent.SeparatorBackend
import com.atreides.voiceconsent.SeparatorResult
import com.atreides.voiceconsent.SeparatorStatus
import com.atreides.voiceconsent.Windowed16kTwoSpeakerSeparator
import com.atreides.voiceconsent.OnDeviceVoicePipeline
import com.atreides.voiceconsent.SourceAwareRedactionProcessor
import com.atreides.voiceconsent.SourceSelectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class Qairt16kSmokeTest {
    @Test
    fun windowed16kOutputPreservesLengthAndUsesEndAlignedBlocks() {
        var calls = 0
        val separator = Windowed16kTwoSpeakerSeparator(object : FixedShape16kSeparator {
            override val status = SeparatorStatus(SeparatorBackend.QAIRT_HTP, true, "test")
            override fun separate2Seconds(pcm16k: FloatArray): SeparatorResult {
                calls++
                return SeparatorResult.Success(pcm16k.copyOf(), pcm16k.copyOf(), SeparatorBackend.QAIRT_HTP)
            }
        })
        val input = FloatArray(87_381) { index -> sin(2.0 * PI * 210 * index / 16_000).toFloat() * 0.1f }
        val result = separator.separate(input) as SeparatorResult.Success
        assertEquals(5, calls)
        assertEquals(input.size, result.sourceA16k.size)
        assertTrue(result.sourceA16k.all { it.isFinite() })
        assertEquals(input.last(), result.sourceA16k.last(), 1e-5f)
    }

    @Test
    fun gentleCleanupRetainsIndependentToneAndReducesSharedTone() {
        val time = FloatArray(16_000) { it / 16_000f }
        val wanted = FloatArray(time.size) { sin(2.0 * PI * 310 * time[it]).toFloat() }
        val leaked = FloatArray(time.size) { sin(2.0 * PI * 970 * time[it]).toFloat() }
        val retained = FloatArray(time.size) { wanted[it] + 0.25f * leaked[it] }
        val result = CoherentLeakageSuppressor.suppress(retained, leaked)
        fun projection(signal: FloatArray, reference: FloatArray): Double =
            signal.indices.sumOf { signal[it].toDouble() * reference[it] } / reference.sumOf { it.toDouble() * it }
        assertEquals(retained.size, result.size)
        assertTrue(result.all { it.isFinite() })
        assertTrue(projection(result, wanted) > 0.9)
        assertTrue(projection(result, leaked) < 0.20)
    }

    @Test
    fun cached16kModelRunsOnPhoneHtp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val block = FloatArray(32_000) { index -> sin(2.0 * PI * 220 * index / 16_000).toFloat() * 0.1f }
        QairtFixedShapeSeparator(context).use { separator ->
            assertTrue(separator.status.detail, separator.status.available)
            val result = separator.separate2Seconds(block)
            assertTrue(result.toString(), result is SeparatorResult.Success)
            result as SeparatorResult.Success
            assertEquals(32_000, result.sourceA16k.size)
            assertEquals(32_000, result.sourceB16k.size)
            assertTrue(result.sourceA16k.all { it.isFinite() })
            assertTrue(result.sourceB16k.all { it.isFinite() })
        }
    }

    @Test
    fun privateFixtureRunsThroughPassportAndExport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "private-e2e-source-16k.wav")
        org.junit.Assume.assumeTrue("Private fixture is only staged for local device QA", fixture.isFile)
        val bytes = fixture.readBytes()
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getInt(24) == 16_000 && buffer.getShort(22).toInt() == 1)
        val dataOffset = bytes.indexOfSlice("data".encodeToByteArray()) + 8
        require(dataOffset >= 8)
        val samples = FloatArray((bytes.size - dataOffset) / 2) { index -> buffer.getShort(dataOffset + index * 2) / 32768f }
        val passport = runBlocking { VoicePassportStore(context).load().getOrThrow() }
        OnDeviceVoicePipeline(context).use { pipeline ->
            Windowed16kTwoSpeakerSeparator(QairtFixedShapeSeparator(context)).use { separator ->
                val result = SourceAwareRedactionProcessor(separator, SourceSelectionPolicy.HIGHEST_PASSPORT_SCORE_FOR_DEMO)
                    .redact(samples, pipeline, passport)
                assertEquals(samples.size, result.sanitized.size)
                assertEquals(SeparatorBackend.QAIRT_HTP, result.backend)
                assertTrue("Expected an overlap replacement on the known fixture: $result", result.overlapSeparated > 0)
                val export = File(context.filesDir, "private-e2e-16k-export-REVIEW-BEFORE-SHARING.wav")
                SanitizedWavWriter.write(export, result.sanitized)
                SanitizedAudioDownloader.download(context, export)
                android.util.Log.i("Qairt16kSmokeTest", "protectedOnly=${result.protectedOnlyMuted} separated=${result.overlapSeparated} safetyMuted=${result.overlapFullyMutedForSafety} warning=${result.warning}")
            }
        }
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int =
        indices.firstOrNull { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } } ?: -1
}
