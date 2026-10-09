package com.atreides.voiceconsent

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/** English-only, fully local transcript decoder for the demo's short speech windows. */
class MoonshineTranscriber(context: Context) : AutoCloseable {
    private val recognizer = OfflineRecognizer(
        context.assets,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = "sherpa-onnx-moonshine-tiny-en-int8/preprocess.onnx",
                    encoder = "sherpa-onnx-moonshine-tiny-en-int8/encode.int8.onnx",
                    uncachedDecoder = "sherpa-onnx-moonshine-tiny-en-int8/uncached_decode.int8.onnx",
                    cachedDecoder = "sherpa-onnx-moonshine-tiny-en-int8/cached_decode.int8.onnx",
                ),
                tokens = "sherpa-onnx-moonshine-tiny-en-int8/tokens.txt",
                numThreads = 2,
            ),
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
        /** Sherpa aborts the process on a missing model file, so check first. */
        fun isAvailable(context: Context): Boolean = runCatching {
            val files = context.assets.list("sherpa-onnx-moonshine-tiny-en-int8")?.toSet().orEmpty()
            REQUIRED_FILES.all(files::contains)
        }.getOrDefault(false)

        private val REQUIRED_FILES = setOf(
            "preprocess.onnx",
            "encode.int8.onnx",
            "uncached_decode.int8.onnx",
            "cached_decode.int8.onnx",
            "tokens.txt",
        )
    }
}
