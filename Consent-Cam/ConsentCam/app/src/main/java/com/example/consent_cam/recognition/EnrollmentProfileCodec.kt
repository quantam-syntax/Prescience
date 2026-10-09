package com.example.consent_cam.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object EnrollmentProfileCodec {
    private const val SCHEMA_VERSION: Byte = 1
    private const val MAX_MODEL_ID_BYTES = 64

    fun encode(profile: EnrollmentProfile): ByteArray {
        val modelBytes = profile.modelId.toByteArray(StandardCharsets.UTF_8)
        require(modelBytes.size in 1..MAX_MODEL_ID_BYTES)
        val embedding = profile.embeddingCopy()
        return try {
            ByteBuffer.allocate(1 + 1 + modelBytes.size + 4 + 8 + embedding.size * 4)
                .order(ByteOrder.BIG_ENDIAN)
                .put(SCHEMA_VERSION)
                .put(modelBytes.size.toByte())
                .put(modelBytes)
                .putInt(profile.dimensions)
                .putLong(profile.createdAtEpochMs)
                .also { buffer -> embedding.forEach(buffer::putFloat) }
                .array()
        } finally {
            embedding.fill(0f)
        }
    }

    fun decode(bytes: ByteArray): EnrollmentProfile? = runCatching {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() < 14 || buffer.get() != SCHEMA_VERSION) return null
        val modelLength = buffer.get().toUByte().toInt()
        if (modelLength !in 1..MAX_MODEL_ID_BYTES || buffer.remaining() < modelLength + 12) return null
        val modelBytes = ByteArray(modelLength)
        buffer.get(modelBytes)
        val modelId = String(modelBytes, StandardCharsets.UTF_8)
        val dimensions = buffer.int
        val createdAt = buffer.long
        if (modelId != FACENET_MODEL_ID || dimensions != FACENET_EMBEDDING_DIMENSIONS) return null
        if (buffer.remaining() != dimensions * 4) return null
        val embedding = FloatArray(dimensions) { buffer.float }
        if (!EmbeddingMath.isUnitVector(embedding)) {
            embedding.fill(0f)
            return null
        }
        EnrollmentProfile(modelId, dimensions, embedding, createdAt).also { embedding.fill(0f) }
    }.getOrNull()
}

