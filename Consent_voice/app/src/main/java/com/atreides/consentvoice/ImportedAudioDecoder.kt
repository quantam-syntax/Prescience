package com.atreides.consentvoice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.atreides.voiceconsent.VOICE_SAMPLE_RATE_HZ
import java.nio.ByteOrder

/** Decodes a user-selected local audio file to 16 kHz mono PCM in RAM. */
object ImportedAudioDecoder {
    fun decodeTo16kMono(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("The selected file does not contain an audio track")
        val inputFormat = extractor.getTrackFormat(track)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Missing audio format")
        val sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        extractor.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(mime)
        val pcm = ArrayList<Float>()
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: error("No decoder input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val output = codec.getOutputBuffer(outputIndex)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
                            if (output != null) {
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                while (output.remaining() >= channels * 2) {
                                    var mixed = 0f
                                    repeat(channels) { mixed += output.short / 32768f }
                                    pcm += mixed / channels
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        check(pcm.isNotEmpty()) { "No audible PCM was decoded from this file" }
        return resample(pcm.toFloatArray(), sourceRate)
    }

    private fun resample(input: FloatArray, sourceRate: Int): FloatArray {
        if (sourceRate == VOICE_SAMPLE_RATE_HZ) return input
        val outputSize = (input.size.toLong() * VOICE_SAMPLE_RATE_HZ / sourceRate).toInt()
        return FloatArray(outputSize) { index ->
            val position = index.toDouble() * sourceRate / VOICE_SAMPLE_RATE_HZ
            val left = position.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (position - left).toFloat()
            input[left] * (1f - fraction) + input[right] * fraction
        }
    }
}
