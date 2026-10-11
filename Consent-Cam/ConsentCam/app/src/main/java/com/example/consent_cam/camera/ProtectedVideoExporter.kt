package com.example.consent_cam.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.atreides.consentvoice.ImportedAudioDecoder
import com.atreides.consentvoice.QairtFixedShapeSeparator
import com.atreides.consentvoice.VoicePassportStore
import com.atreides.voiceconsent.OnDeviceVoicePipeline
import com.atreides.voiceconsent.SeparatorBackend
import com.atreides.voiceconsent.SourceAwareRedactionProcessor
import com.atreides.voiceconsent.SourceSelectionPolicy
import com.atreides.voiceconsent.Windowed16kTwoSpeakerSeparator
import com.example.consent_cam.ble.NearbyVoiceRequest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProtectedVideoResult(
    val uri: Uri,
    val protectedOnlyMuted: Int,
    val overlapSeparated: Int,
    val overlapFullyMuted: Int,
    val warning: String?,
)

/** Publishes a face-protected capture only after its voice track is replaced. */
object ProtectedVideoExporter {
    private const val sampleRate = 16_000
    private const val protectedVideoLeakageSuppressionStrength = 0.85
    private const val protectedVideoSpectralDominanceWeight = 0.75

    suspend fun export(
        context: Context,
        stagedUri: Uri,
        remoteVoiceRequests: List<NearbyVoiceRequest>? = null,
    ): ProtectedVideoResult = withContext(Dispatchers.IO) {
        require(stagedUri.scheme == "file") { "Expected a private staged video" }
        val source = File(requireNotNull(stagedUri.path)).canonicalFile
        require(source.parentFile == context.cacheDir.canonicalFile && source.name.startsWith("protected-video-")) {
            "Refusing to process a video outside private capture storage"
        }
        val remuxed = File.createTempFile("protected-export-", ".mp4", context.cacheDir)
        try {
            val passport = if (remoteVoiceRequests != null) remoteVoiceRequests.singleOrNull()?.samples
                else VoicePassportStore(context).load().getOrElse {
                    error("Enroll a Voice Passport in Consent Audio before using Protected video")
                }
            val processed = runCatching {
                requireNotNull(passport) { "One usable nearby voice profile is required" }
                val originalPcm = ImportedAudioDecoder.decodeTo16kMono(context, stagedUri)
                try {
                    val redacted = OnDeviceVoicePipeline(context).use { pipeline ->
                        Windowed16kTwoSpeakerSeparator(QairtFixedShapeSeparator(context)).use { separator ->
                            SourceAwareRedactionProcessor(
                                separator,
                                SourceSelectionPolicy.HIGHEST_PASSPORT_SCORE_FOR_DEMO,
                                leakageSuppressionStrength = protectedVideoLeakageSuppressionStrength,
                                spectralDominanceWeight = protectedVideoSpectralDominanceWeight,
                                adaptiveLeakageLevelMatch = true,
                                useRetainedSourceAsBase = true,
                            )
                                .redact(originalPcm, pipeline, passport)
                        }
                    }
                    require(redacted.backend == SeparatorBackend.QAIRT_HTP) { "NPU voice separation unavailable" }
                    require(redacted.identityMatched) { "Protected voice could not be identified" }
                    require(redacted.sanitized.size == originalPcm.size && redacted.sanitized.all { it.isFinite() }) {
                        "Voice output was invalid"
                    }
                    redacted
                } finally { originalPcm.fill(0f) }
            }
            // Missing profiles or failed separation cannot safely keep the
            // original microphone track. Do not publish a misleading silent
            // "Protected" video either; surface the failure to the camera.
            val redacted = processed.getOrThrow()
            val audio = redacted.sanitized
            val encodedAudio = try { encodeAac(audio) } finally { audio.fill(0f) }
            muxProtectedVideo(source, remuxed, encodedAudio)
            val uri = publish(context, remuxed)
            ProtectedVideoResult(
                uri,
                redacted.protectedOnlyMuted,
                redacted.overlapSeparated,
                redacted.overlapFullyMutedForSafety,
                redacted.warning,
            )
        } finally {
            // These exact files are app-owned temporary captures, never Gallery items.
            source.delete()
            remuxed.delete()
        }
    }

    private data class EncodedSample(val bytes: ByteArray, val timeUs: Long, val flags: Int)
    private data class EncodedAudio(val format: MediaFormat, val samples: List<EncodedSample>)

    private fun encodeAac(samples: FloatArray): EncodedAudio {
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        val gain = if (peak > 0.95f) 0.95f / peak else 1f
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val packets = ArrayList<EncodedSample>()
        var outputFormat: MediaFormat? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var position = 0
            var inputEnded = false
            var outputEnded = false
            while (!outputEnded) {
                if (!inputEnded) {
                    val slot = encoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val buffer = requireNotNull(encoder.getInputBuffer(slot)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        val count = minOf(1024, samples.size - position, buffer.capacity() / 2)
                        if (count > 0) {
                            for (index in 0 until count) {
                                buffer.putShort((samples[position + index] * gain * Short.MAX_VALUE).roundToInt().toShort())
                            }
                            encoder.queueInputBuffer(slot, 0, count * 2, position.toLong() * 1_000_000 / sampleRate, 0)
                            position += count
                        } else {
                            encoder.queueInputBuffer(slot, 0, 0, position.toLong() * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        }
                    }
                }
                when (val slot = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = encoder.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (slot >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = requireNotNull(encoder.getOutputBuffer(slot)).duplicate()
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            packets += EncodedSample(bytes, info.presentationTimeUs, info.flags)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(slot, false)
                    }
                }
            }
            return EncodedAudio(requireNotNull(outputFormat) { "AAC encoder did not provide an output format" }, packets)
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    private fun muxProtectedVideo(source: File, destination: File, audio: EncodedAudio) {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val videoTrack = (0 until extractor.trackCount).firstOrNull { track ->
            extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("Private capture has no video track")
        val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var videoSamples = 0
        try {
            val videoFormat = extractor.getTrackFormat(videoTrack)
            val outputVideo = muxer.addTrack(videoFormat)
            val outputAudio = muxer.addTrack(audio.format)
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(source.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()?.let(muxer::setOrientationHint)
            } finally { metadata.release() }
            muxer.start()
            started = true
            extractor.selectTrack(videoTrack)
            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) extractor.sampleSize else buffer.capacity().toLong()
                if (required < 0) break
                if (required > buffer.capacity()) buffer = ByteBuffer.allocateDirect(required.toInt())
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(size <= buffer.capacity()) { "Video sample exceeded mux buffer" }
                val keyFrameFlag = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                info.set(0, size, extractor.sampleTime, keyFrameFlag)
                muxer.writeSampleData(outputVideo, buffer, info)
                videoSamples++
                extractor.advance()
            }
            for (packet in audio.samples) {
                info.set(0, packet.bytes.size, packet.timeUs, packet.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                muxer.writeSampleData(outputAudio, ByteBuffer.wrap(packet.bytes), info)
            }
        } finally {
            extractor.release()
            try { if (started) muxer.stop() } finally { muxer.release() }
        }
        require(videoSamples > 0 && destination.length() > 0) { "Protected video remux produced no video" }
    }

    private fun publish(context: Context, file: File): Uri {
        val name = "PROTECTED_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ConsentCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not reserve protected video output")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Could not write protected video")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(context.contentResolver.update(uri, values, null, null) > 0) { "Could not finalize protected video" }
            return uri
        } catch (failure: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw failure
        }
    }
}
