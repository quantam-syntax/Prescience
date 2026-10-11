package com.example.consent_cam.ble

import com.atreides.voiceconsent.SPEAKER_MODEL_ID
import com.example.consent_cam.recognition.EnrollmentProfileCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Session-only speaker references. The caller owns and must close each copy. */
class NearbyVoiceProfile(val sessionId: UInt, samples: List<FloatArray>) : AutoCloseable {
    private val values = samples.map(FloatArray::copyOf)
    fun copySamples(): List<FloatArray> = values.map(FloatArray::copyOf)
    override fun close() = values.forEach { it.fill(0f) }
}

class NearbyVoiceRequest(val sessionId: UInt, val samples: List<FloatArray>?) : AutoCloseable {
    override fun close() = samples?.forEach { it.fill(0f) } ?: Unit
}

/** Versioned wrapper for the existing encrypted GATT payload; legacy face payloads still decode. */
object NearbyProtectionPayload {
    private const val MAGIC = 0x5056 // "PV"
    private const val VERSION = 2
    private const val MAX_SECTION = 8_192
    private const val REQUIRED_SAMPLES = 4

    data class Decoded(val face: ByteArray?, val voice: List<FloatArray>?)

    fun encode(face: ByteArray?, voice: List<FloatArray>?): ByteArray {
        val voiceBytes = voice?.let(::encodeVoice)
        try {
            require(face != null || voiceBytes != null)
            require((face?.size ?: 0) <= MAX_SECTION && (voiceBytes?.size ?: 0) <= MAX_SECTION)
            return ByteBuffer.allocate(8 + (face?.size ?: 0) + (voiceBytes?.size ?: 0))
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(MAGIC.toShort()).put(VERSION.toByte()).put(0)
                .putShort((face?.size ?: 0).toShort()).putShort((voiceBytes?.size ?: 0).toShort())
                .also { buffer -> face?.let(buffer::put); voiceBytes?.let(buffer::put) }
                .array()
        } finally { voiceBytes?.fill(0) }
    }

    fun decode(bytes: ByteArray): Decoded? = runCatching {
        if (bytes.size < 8 || ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).short.toInt() != MAGIC) {
            val legacy = EnrollmentProfileCodec.decode(bytes) ?: return null
            legacy.close()
            return Decoded(bytes.copyOf(), null)
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.short.toInt() == MAGIC && buffer.get().toInt() == VERSION && buffer.get().toInt() == 0)
        val faceLength = buffer.short.toUShort().toInt()
        val voiceLength = buffer.short.toUShort().toInt()
        require(faceLength <= MAX_SECTION && voiceLength <= MAX_SECTION && faceLength + voiceLength == buffer.remaining())
        val face = if (faceLength > 0) ByteArray(faceLength).also(buffer::get) else null
        val voiceBytes = if (voiceLength > 0) ByteArray(voiceLength).also(buffer::get) else null
        val voice = try { voiceBytes?.let(::decodeVoice) } finally { voiceBytes?.fill(0) }
        if (face != null) {
            val decodedFace = EnrollmentProfileCodec.decode(face) ?: error("Invalid face profile")
            decodedFace.close()
        }
        require(face != null || voice != null)
        Decoded(face, voice)
    }.getOrNull()

    private fun encodeVoice(samples: List<FloatArray>): ByteArray {
        require(samples.size == REQUIRED_SAMPLES)
        val dimension = samples.first().size
        require(dimension in 1..1024 && samples.all { it.size == dimension && validEmbedding(it) })
        val model = SPEAKER_MODEL_ID.encodeToByteArray()
        require(model.size in 1..255)
        return ByteBuffer.allocate(1 + model.size + 1 + 2 + samples.size * dimension * 4)
            .order(ByteOrder.BIG_ENDIAN).put(model.size.toByte()).put(model)
            .put(REQUIRED_SAMPLES.toByte()).putShort(dimension.toShort())
            .also { buffer -> samples.forEach { values -> values.forEach(buffer::putFloat) } }.array()
    }

    private fun decodeVoice(bytes: ByteArray): List<FloatArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.remaining() >= 4)
        val modelLength = buffer.get().toUByte().toInt()
        require(modelLength in 1..255 && buffer.remaining() >= modelLength + 3)
        val model = ByteArray(modelLength).also(buffer::get).decodeToString()
        require(model == SPEAKER_MODEL_ID)
        val count = buffer.get().toUByte().toInt()
        val dimension = buffer.short.toUShort().toInt()
        require(count == REQUIRED_SAMPLES && dimension in 1..1024 && buffer.remaining() == count * dimension * 4)
        return List(count) { FloatArray(dimension) { buffer.float }.also { require(validEmbedding(it)) } }
    }

    private fun validEmbedding(values: FloatArray): Boolean {
        if (values.any { !it.isFinite() }) return false
        val norm = sqrt(values.sumOf { (it * it).toDouble() })
        return abs(norm - 1.0) < 0.02
    }
}
