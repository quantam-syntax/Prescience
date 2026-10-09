package com.atreides.consentvoice

import android.test.InstrumentationTestCase
import android.util.Log
import android.net.Uri
import com.atreides.voiceconsent.OnDeviceVoicePipeline
import com.atreides.voiceconsent.LocalSpeechDecision
import com.atreides.voiceconsent.PcmConsentRedactor
import com.atreides.voiceconsent.SpeechState
import com.atreides.voiceconsent.SPEAKER_MODEL_ID
import com.atreides.voiceconsent.cosineSimilarity
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Local supplied fixture only; never logs samples, embeddings, or transcript. */
class VoiceRegressionTest : InstrumentationTestCase() {
    fun testTest4Diagnostics() {
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "test4.mp3")
        instrumentation.context.assets.open("test4.mp3").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val pcm = ImportedAudioDecoder.decodeTo16kMono(context, Uri.fromFile(source))
        val reference = fixture("test1.wav")
        OnDeviceVoicePipeline(context).use { pipeline ->
            val profile = pipeline.enrollEmbedding(reference.copyOfRange(216000, reference.size))!!
            val decisions = pipeline.decisions(pcm, profile)
            decisions.forEach {
                Log.i("VoiceRegression", "test4 ${it.startMs}-${it.endMs} score=${it.score} state=${it.state}")
            }
            val sanitized = PcmConsentRedactor.redact(pcm, decisions)
            SanitizedWavWriter.write(File(context.getExternalFilesDir(null), "regression-test4.wav"), sanitized)
            val firstCollapsedRegion = decisions.filter { it.endMs <= 10_000 }
            assertTrue("test4 must be split into retained utterances",
                firstCollapsedRegion.count { it.state == SpeechState.UNMATCHED } >= 3)
            assertTrue("test4 must still identify protected utterances",
                firstCollapsedRegion.count { it.state == SpeechState.PROTECTED } >= 3)
        }
    }

    /** Diagnostic only: dense windows expose VAD gaps and within-recording consistency. */
    fun testLeadInDiagnostics() {
        val pcm = fixture("test3.wav")
        val reference = fixture("test1.wav")
        OnDeviceVoicePipeline(instrumentation.targetContext).use { pipeline ->
            val enrolled = pipeline.enrollEmbedding(reference.copyOfRange(216000, reference.size))!!
            val sessionReference = pipeline.enrollEmbedding(pcm.copyOfRange(192000, 248000))!!
            val extractor = SpeakerEmbeddingExtractor(instrumentation.targetContext.assets,
                SpeakerEmbeddingExtractorConfig(model = "$SPEAKER_MODEL_ID.onnx"))
            try {
                for (start in 0..(pcm.size - 24000) step 8000) {
                    val stream = extractor.createStream()
                    try {
                        stream.acceptWaveform(pcm.copyOfRange(start, start + 24000), 16000)
                        stream.inputFinished()
                        val embedding = extractor.compute(stream)
                        Log.i("VoiceRegression", "dense ${start / 16}-${(start + 24000) / 16} enrolled=${cosineSimilarity(embedding, enrolled)} session=${cosineSimilarity(embedding, sessionReference)}")
                    } finally { stream.release() }
                }
            } finally { extractor.release() }
        }
    }

    private fun fixture(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open(name).use { it.readBytes() }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.position(12)
        while (true) {
            val id = ByteArray(4).also { data.get(it) }.decodeToString()
            val size = data.int
            if (id == "data") break
            data.position(data.position() + size + (size % 2))
        }
        return FloatArray(data.remaining() / 2) { data.short / 32768f }
    }

    fun testCrossRecordingScores() {
        val reference = fixture("test1.wav")
        val pcm = fixture("test3.wav")
        OnDeviceVoicePipeline(instrumentation.targetContext).use { pipeline ->
            val profile = pipeline.enrollEmbedding(reference.copyOfRange(216000, reference.size))!!
            val decisions = pipeline.decisions(pcm, profile)
            decisions.forEach {
                Log.i("VoiceRegression", "test3 ${it.startMs}-${it.endMs} score=${it.score} state=${it.state}")
            }
            val output = PcmConsentRedactor.redact(pcm, decisions)
            val protectedSentence = decisions.filter {
                it.startMs in 10_000..16_500 && it.endMs <= 16_500
            }
            assertTrue(protectedSentence.isNotEmpty())
            assertTrue("Protected middle sentence must contain a verified match",
                protectedSentence.any { it.state == SpeechState.PROTECTED })
            val sentenceStart = protectedSentence.minOf { it.startSample }
            assertTrue("Friend opening must remain intact",
                pcm.copyOfRange(0, sentenceStart).contentEquals(output.copyOfRange(0, sentenceStart)))
            protectedSentence.filter { it.state == SpeechState.PROTECTED }.forEach { decision ->
                assertTrue("Protected middle speech must be silent",
                    output.copyOfRange(decision.startSample, decision.endSample).all { it == 0f })
            }
            val protectedRepetitions = decisions.filter {
                it.startMs >= 17_000 && it.endMs <= 19_200 && it.state == SpeechState.PROTECTED
            }
            protectedRepetitions.forEach { decision ->
                assertTrue(output.copyOfRange(decision.startSample, decision.endSample).all { it == 0f })
            }
            val friendEnding = decisions.filter {
                it.startMs >= 19_000 && it.state == SpeechState.UNMATCHED
            }.minBy { it.startSample }
            assertTrue("Rahul Rajeev's phrase must remain unchanged",
                pcm.copyOfRange(friendEnding.startSample, pcm.size)
                    .contentEquals(output.copyOfRange(friendEnding.startSample, output.size)))
            SanitizedWavWriter.write(File(instrumentation.targetContext.getExternalFilesDir(null),
                "regression-test3.wav"), output)
        }
    }

    fun testSilenceCannotEnroll() {
        OnDeviceVoicePipeline(instrumentation.targetContext).use { pipeline ->
            assertNull(pipeline.enrollEmbedding(FloatArray(16000 * 3)))
        }
    }

    fun testSpeakerScores() {
        val pcm = fixture("test1.wav")
        OnDeviceVoicePipeline(instrumentation.targetContext).use { pipeline ->
            // User labels friend 0-10/11s and protected speaker thereafter.
            // Hold out 13.5s-end for enrollment; evaluate earlier speech separately.
            val profile = pipeline.enrollEmbedding(pcm.copyOfRange(216000, pcm.size))
            assertNotNull("Protected reference must contain speech", profile)
            val decisions = pipeline.decisions(pcm, profile!!)
            decisions.forEach { Log.i("VoiceRegression", "${it.startMs}-${it.endMs} score=${it.score} state=${it.state}") }
            assertTrue(decisions.isNotEmpty())
            assertTrue("Friend windows must not match the protected speaker",
                decisions.filter { it.endMs <= 11000 }.all { it.state == SpeechState.UNMATCHED })
            assertTrue("Reference speaker must produce confirmed matches",
                decisions.count { it.startMs >= 13250 && it.state == SpeechState.PROTECTED } >= 3)
            assertTrue(decisions.all { it.endSample <= pcm.size })
            val sanitized = PcmConsentRedactor.redact(pcm, decisions)
            assertTrue("Friend's first ten seconds must be sample-identical",
                pcm.copyOfRange(0, 160000).contentEquals(sanitized.copyOfRange(0, 160000)))
            decisions.filter { it.startMs >= 13250 && it.state == SpeechState.PROTECTED }.forEach { decision ->
                assertTrue("Detected protected speech must be silent",
                    sanitized.copyOfRange(decision.startSample, decision.endSample).all { it == 0f })
            }
            SanitizedWavWriter.write(File(instrumentation.targetContext.getExternalFilesDir(null),
                "regression-cmn.wav"), sanitized)
        }
    }

    fun testOnlyConfirmedAndOverlapAreWithheldWithoutChangingOtherSamples() {
        val pcm = FloatArray(100) { 0.25f }
        val decisions = listOf(
            LocalSpeechDecision(10, 20, Float.NaN, SpeechState.UNCERTAIN),
            LocalSpeechDecision(30, 40, 0.9f, SpeechState.PROTECTED),
            LocalSpeechDecision(90, 110, 0.9f, SpeechState.OVERLAP),
        )
        val result = PcmConsentRedactor.redact(pcm, decisions)
        result.indices.forEach { i ->
            assertEquals(if (i in 30..39 || i >= 90) 0f else 0.25f, result[i])
        }
        assertTrue(pcm.all { it == 0.25f })
        assertEquals(pcm.size, result.size)
    }
}
