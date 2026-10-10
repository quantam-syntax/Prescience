package com.atreides.voiceconsent

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.sqrt

const val VOICE_SAMPLE_RATE_HZ = 16_000
const val SPEAKER_MODEL_ID = "3dspeaker_eres2net_en_voxceleb_16k"
@Deprecated("Use SPEAKER_MODEL_ID")
const val WESPEAKER_MODEL_ID = SPEAKER_MODEL_ID

/** A locally produced speech window and its relationship to the approved voice profile. */
data class LocalSpeechDecision(
    val startSample: Int,
    val endSample: Int,
    val score: Float,
    val state: SpeechState,
) {
    val startMs: Long get() = startSample * 1_000L / VOICE_SAMPLE_RATE_HZ
    val endMs: Long get() = endSample * 1_000L / VOICE_SAMPLE_RATE_HZ
}

/** VAD utterance segmentation + ERes2Net enrolled-speaker verification. Audio stays on-device. */
class OnDeviceVoicePipeline(context: Context) : AutoCloseable {
    private val extractor = SpeakerEmbeddingExtractor(
        context.assets,
        SpeakerEmbeddingExtractorConfig(model = "$SPEAKER_MODEL_ID.onnx"),
    )
    private val vad = Vad(
        context.assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.35f,
                maxSpeechDuration = 8f,
            ),
        ),
    )
    val embeddingDimension: Int get() = extractor.dim()

    /** Pool speech from the enrollment, rather than relying on a single short phrase. */
    fun enrollEmbedding(pcm16kMono: FloatArray): FloatArray? {
        val segments = speechSegments(pcm16kMono)
        val speechSize = segments.sumOf { it.samples.size }
        if (speechSize < VOICE_SAMPLE_RATE_HZ * 2) return null
        val speech = FloatArray(minOf(speechSize, VOICE_SAMPLE_RATE_HZ * 12))
        var offset = 0
        for (segment in segments) {
            val count = minOf(segment.samples.size, speech.size - offset)
            segment.samples.copyInto(speech, offset, 0, count)
            offset += count
            if (offset == speech.size) break
        }
        return embeddingFor(SpeechSegment(0, speech))
    }

    /** Keeps one normalized reference per position so averaging cannot erase useful voice detail. */
    fun enrollEmbedding(guidedTakes: List<FloatArray>): List<FloatArray>? {
        val embeddings = guidedTakes.mapNotNull(::enrollEmbedding)
        if (embeddings.size != guidedTakes.size || embeddings.isEmpty()) return null
        return embeddings
    }

    fun decisions(
        pcm16kMono: FloatArray,
        protectedEmbedding: FloatArray,
        protectedThreshold: Float = 0.35f,
        uncertainThreshold: Float = 0.28f,
    ): List<LocalSpeechDecision> = decisions(
        pcm16kMono = pcm16kMono,
        protectedEmbeddings = listOf(protectedEmbedding),
        protectedThreshold = protectedThreshold,
        uncertainThreshold = uncertainThreshold,
    )

    /** Scores every utterance against all accepted Voice Passport samples. */
    fun decisions(
        pcm16kMono: FloatArray,
        protectedEmbeddings: List<FloatArray>,
        protectedThreshold: Float = 0.35f,
        uncertainThreshold: Float = 0.28f,
    ): List<LocalSpeechDecision> {
        require(protectedEmbeddings.isNotEmpty()) { "Enroll a voice first" }
        require(protectedEmbeddings.all { it.size == embeddingDimension }) { "Profile uses a different speaker model" }
        require(uncertainThreshold < protectedThreshold) { "Uncertain threshold must be lower than protected threshold" }
        require(protectedEmbeddings.all { embedding -> embedding.all { it.isFinite() } && embedding.any { it != 0f } }) {
            "Invalid voice profile; please enroll again"
        }
        if (pcm16kMono.isEmpty()) return emptyList()
        val bursts = speechSegments(pcm16kMono).flatMap { segment ->
            val end = (segment.start + segment.samples.size).coerceAtMost(pcm16kMono.size)
            energyBursts(pcm16kMono, segment.start, end)
        }
        if (bursts.isEmpty()) return emptyList()
        return bursts.map { burst ->
            val score = embeddingFor(burst)?.let { candidate ->
                protectedEmbeddings.maxOf { enrolled -> cosineSimilarity(candidate, enrolled) }
            } ?: Float.NaN
            LocalSpeechDecision(
                burst.start,
                burst.start + burst.samples.size,
                score,
                when {
                    !score.isFinite() -> SpeechState.UNCERTAIN
                    score >= protectedThreshold -> SpeechState.PROTECTED
                    score >= uncertainThreshold -> SpeechState.UNCERTAIN
                    else -> SpeechState.UNMATCHED
                },
            )
        }
    }

    private fun energyBursts(pcm: FloatArray, start: Int, end: Int): List<SpeechSegment> {
        val frameSize = VOICE_SAMPLE_RATE_HZ / 50 // 20 ms
        val frames = buildList {
            var offset = start
            while (offset < end) {
                val frameEnd = minOf(offset + frameSize, end)
                var energy = 0.0
                for (i in offset until frameEnd) energy += pcm[i] * pcm[i]
                add(Triple(offset, frameEnd, kotlin.math.sqrt(energy / (frameEnd - offset)).toFloat()))
                offset = frameEnd
            }
        }
        if (frames.isEmpty()) return emptyList()
        val sortedEnergy = frames.map { it.third }.sorted()
        val noiseFloor = sortedEnergy[sortedEnergy.size / 4]
        val threshold = maxOf(0.045f, noiseFloor * 1.8f)
        val active = frames.map { it.third >= threshold }.toMutableList()
        // A speaker embedding needs a phrase, not a syllable. Keep ordinary pauses
        // inside the same turn so the verifier has enough of the speaker's voice.
        // A gap longer than 500 ms is still treated as a new turn.
        var i = 0
        while (i < active.size) {
            if (active[i]) { i++; continue }
            val gapStart = i
            while (i < active.size && !active[i]) i++
            if (gapStart > 0 && i < active.size && i - gapStart <= 25) {
                for (j in gapStart until i) active[j] = true
            }
        }
        return buildList {
            i = 0
            while (i < active.size) {
                while (i < active.size && !active[i]) i++
                if (i == active.size) break
                val first = i
                while (i < active.size && active[i]) i++
                val last = i - 1
                val burstStart = (frames[first].first - frameSize * 2).coerceAtLeast(start)
                val burstEnd = (frames[last].second + frameSize * 2).coerceAtMost(end)
                // ERes2Net verification is unreliable for word-sized snippets.
                // Ignore anything under 0.8 s rather than label it as a different
                // person and leave a random part of a protected utterance audible.
                if (burstEnd - burstStart >= VOICE_SAMPLE_RATE_HZ * 8 / 10) {
                    add(SpeechSegment(burstStart, pcm.copyOfRange(burstStart, burstEnd)))
                }
            }
        }
    }

    private fun speechSegments(pcm: FloatArray): List<SpeechSegment> {
        vad.reset()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + 512, pcm.size)
            val chunk = pcm.copyOfRange(offset, end)
            // Silero expects 512 samples. Zero-pad only the final partial frame.
            vad.acceptWaveform(if (chunk.size == 512) chunk else chunk.copyOf(512))
            offset = end
        }
        vad.flush()
        return buildList {
            while (!vad.empty()) {
                val segment = vad.front()
                val size = minOf(segment.samples.size, pcm.size - segment.start)
                if (size > 0) add(SpeechSegment(segment.start, segment.samples.copyOf(size)))
                vad.pop()
            }
        }
    }

    private fun embeddingFor(segment: SpeechSegment): FloatArray? {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(segment.samples, VOICE_SAMPLE_RATE_HZ)
            stream.inputFinished()
            if (extractor.isReady(stream)) normalize(extractor.compute(stream)) else null
        } finally {
            stream.release()
        }
    }

    override fun close() {
        vad.release()
        extractor.release()
    }
}

fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
    require(left.size == right.size) { "Embedding dimensions differ" }
    var dot = 0f
    var leftNorm = 0f
    var rightNorm = 0f
    left.indices.forEach { index ->
        dot += left[index] * right[index]
        leftNorm += left[index] * left[index]
        rightNorm += right[index] * right[index]
    }
    return if (leftNorm == 0f || rightNorm == 0f) 0f else dot / sqrt(leftNorm * rightNorm)
}

fun normalize(values: FloatArray): FloatArray {
    val norm = sqrt(values.sumOf { (it * it).toDouble() }.toFloat())
    return if (norm == 0f) values else FloatArray(values.size) { values[it] / norm }
}
