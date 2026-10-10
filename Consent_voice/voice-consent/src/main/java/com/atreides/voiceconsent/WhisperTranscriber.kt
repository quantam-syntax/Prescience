package com.atreides.voiceconsent

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/** English-only Whisper tiny.en INT8 inference. All transcription stays on-device. */
class WhisperTranscriber(context: Context) : AutoCloseable {
    private val recognizer = OfflineRecognizer(
        context.assets,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$MODEL_DIR/tiny.en-encoder.int8.onnx",
                    decoder = "$MODEL_DIR/tiny.en-decoder.int8.onnx",
                    language = "en",
                    task = "transcribe",
                ),
                tokens = "$MODEL_DIR/tiny.en-tokens.txt",
                numThreads = 2,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    fun transcribe(pcm16kMono: FloatArray): String {
        if (pcm16kMono.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(pcm16kMono, VOICE_SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        const val MODEL_DIR = "sherpa-onnx-whisper-tiny.en"

        /** Avoid constructing Sherpa when the optional assets are not bundled. */
        fun isAvailable(context: Context): Boolean = listOf(
            "$MODEL_DIR/tiny.en-encoder.int8.onnx",
            "$MODEL_DIR/tiny.en-decoder.int8.onnx",
            "$MODEL_DIR/tiny.en-tokens.txt",
        ).all { assetPath ->
            runCatching { context.assets.open(assetPath).close() }.isSuccess
        }
    }
}
