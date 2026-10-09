package com.atreides.voiceconsent

/** Replaces protected speech with silence in a 16 kHz mono PCM buffer. */
object PcmConsentRedactor {
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
}
