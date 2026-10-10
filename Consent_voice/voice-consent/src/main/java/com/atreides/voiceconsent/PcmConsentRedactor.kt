package com.atreides.voiceconsent

/** Replaces protected speech with silence in a 16 kHz mono PCM buffer. */
object PcmConsentRedactor {
    private const val FADE_SAMPLES = VOICE_SAMPLE_RATE_HZ / 100 // 10 ms
    fun redact(
        pcm16kMono: FloatArray,
        decisions: List<LocalSpeechDecision>,
        shouldWithhold: (SpeechState) -> Boolean = {
            it == SpeechState.PROTECTED || it == SpeechState.OVERLAP
        },
    ): FloatArray {
        val output = pcm16kMono.copyOf()
        decisions.filter { shouldWithhold(it.state) }.forEach { decision ->
            val start = decision.startSample.coerceIn(0, output.size)
            val end = decision.endSample.coerceIn(start, output.size)
            output.fill(0f, start, end)
        }
        return output
    }

    /**
     * Applies source-aware replacements. Every replacement overwrites its full
     * interval, so samples from the original mixed recording cannot leak into
     * an overlap. Invalid or overlapping replacements fail closed by muting the
     * affected interval.
     */
    fun redactWithReplacements(
        pcm16kMono: FloatArray,
        decisions: List<LocalSpeechDecision>,
        replacements: List<LocalReplacementAudio>,
    ): FloatArray {
        val output = redact(pcm16kMono, decisions)
        replacements.sortedBy { it.startSample }.forEach { replacement ->
            val start = replacement.startSample.coerceIn(0, output.size)
            val end = replacement.endSample.coerceIn(start, output.size)
            output.fill(0f, start, end)
            val expected = end - start
            if (expected == 0 || replacement.samples.size != expected || replacement.samples.any { !it.isFinite() }) return@forEach
            replacement.samples.copyInto(output, destinationOffset = start)
            applyEdgeFades(output, start, end)
        }
        return output
    }

    private fun applyEdgeFades(output: FloatArray, start: Int, end: Int) {
        val fade = minOf(FADE_SAMPLES, (end - start) / 2)
        for (index in 0 until fade) {
            val amount = (index + 1).toFloat() / (fade + 1)
            output[start + index] *= amount
            output[end - 1 - index] *= amount
        }
    }
}

data class LocalReplacementAudio(
    val startSample: Int,
    val endSample: Int,
    val samples: FloatArray,
)
