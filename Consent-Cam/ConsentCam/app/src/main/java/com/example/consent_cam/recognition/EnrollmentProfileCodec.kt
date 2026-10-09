package com.example.consent_cam.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object EnrollmentProfileCodec {
    private const val SCHEMA_VERSION: Byte = 4
    private const val PRE_TRUST_SCHEMA_VERSION: Byte = 3
    private const val AVATAR_SCHEMA_VERSION: Byte = 2
    private const val LEGACY_SCHEMA_VERSION: Byte = 1
    private const val MAX_MODEL_ID_BYTES = 64
    private const val NO_PRESET = 0xFF
    private const val MAX_IDENTITY_BYTES = 96
    private const val MAX_DISPLAY_NAME_BYTES = 48
    private const val MAX_TRUSTED_CAMERAS = 16

    fun encode(profile: EnrollmentProfile): ByteArray {
        val modelBytes = profile.modelId.toByteArray(StandardCharsets.UTF_8)
        val identityBytes = profile.deviceIdentity.toByteArray(StandardCharsets.UTF_8)
        val nameBytes = profile.displayName.toByteArray(StandardCharsets.UTF_8)
        val trustedIdentityBytes = profile.trustedCameraIdentities.map { it.toByteArray(StandardCharsets.UTF_8) }
        require(modelBytes.size in 1..MAX_MODEL_ID_BYTES)
        require(identityBytes.size <= MAX_IDENTITY_BYTES && nameBytes.size <= MAX_DISPLAY_NAME_BYTES)
        require(trustedIdentityBytes.size <= MAX_TRUSTED_CAMERAS && trustedIdentityBytes.all { it.size in 1..MAX_IDENTITY_BYTES })
        val embedding = profile.embeddingCopy()
        return try {
            ByteBuffer.allocate(1 + 1 + modelBytes.size + 4 + 8 + 1 + 1 + 12 + 1 + identityBytes.size + 1 + nameBytes.size + 1 + trustedIdentityBytes.sumOf { 1 + it.size } + embedding.size * 4)
                .order(ByteOrder.BIG_ENDIAN)
                .put(SCHEMA_VERSION)
                .put(modelBytes.size.toByte())
                .put(modelBytes)
                .putInt(profile.dimensions)
                .putLong(profile.createdAtEpochMs)
                .put(profile.presentation.ordinal.toByte())
                .put((profile.avatarPreset?.ordinal ?: NO_PRESET).toByte())
                .putInt(profile.avatarStyle?.skinColor ?: 0)
                .putInt(profile.avatarStyle?.hairColor ?: 0)
                .putInt(profile.avatarStyle?.shirtColor ?: 0)
                .put(identityBytes.size.toByte()).put(identityBytes)
                .put(nameBytes.size.toByte()).put(nameBytes)
                .put(trustedIdentityBytes.size.toByte())
                .also { buffer -> trustedIdentityBytes.forEach { bytes -> buffer.put(bytes.size.toByte()).put(bytes) } }
                .also { buffer -> embedding.forEach(buffer::putFloat) }
                .array()
        } finally {
            embedding.fill(0f)
        }
    }

    fun decode(bytes: ByteArray): EnrollmentProfile? = runCatching {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() < 14) return null
        val version = buffer.get()
        if (version != SCHEMA_VERSION && version != PRE_TRUST_SCHEMA_VERSION && version != AVATAR_SCHEMA_VERSION && version != LEGACY_SCHEMA_VERSION) return null
        val modelLength = buffer.get().toUByte().toInt()
        if (modelLength !in 1..MAX_MODEL_ID_BYTES || buffer.remaining() < modelLength + 12) return null
        val modelBytes = ByteArray(modelLength)
        buffer.get(modelBytes)
        val modelId = String(modelBytes, StandardCharsets.UTF_8)
        val dimensions = buffer.int
        val createdAt = buffer.long
        if (modelId != FACENET_MODEL_ID || dimensions != FACENET_EMBEDDING_DIMENSIONS) return null
        val presentation = if (version != LEGACY_SCHEMA_VERSION) {
            if (buffer.remaining() < (if (version == PRE_TRUST_SCHEMA_VERSION || version == SCHEMA_VERSION) 14 else 13) + dimensions * 4) return null
            ProtectionPresentation.entries.getOrNull(buffer.get().toInt()) ?: return null
        } else ProtectionPresentation.BLUR
        val preset = if (version == PRE_TRUST_SCHEMA_VERSION || version == SCHEMA_VERSION) {
            val index = buffer.get().toUByte().toInt()
            if (index == NO_PRESET) null else AvatarPreset.entries.getOrNull(index) ?: return null
        } else null
        val avatarStyle = if (version != LEGACY_SCHEMA_VERSION) {
            val skin = buffer.int
            val hair = buffer.int
            val shirt = buffer.int
            if (presentation == ProtectionPresentation.AVATAR) AvatarStyle(skin, hair, shirt) else null
        } else null
        fun getBoundedString(maxBytes: Int): String? {
            if (!buffer.hasRemaining()) return null
            val size = buffer.get().toUByte().toInt()
            if (size > maxBytes || buffer.remaining() < size) return null
            val bytes = ByteArray(size)
            buffer.get(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }
        val deviceIdentity = if (version == SCHEMA_VERSION) getBoundedString(MAX_IDENTITY_BYTES) ?: return null else ""
        val displayName = if (version == SCHEMA_VERSION) getBoundedString(MAX_DISPLAY_NAME_BYTES) ?: return null else ""
        val trustedCameraIdentities = if (version == SCHEMA_VERSION) {
            if (!buffer.hasRemaining()) return null
            val count = buffer.get().toUByte().toInt()
            if (count > MAX_TRUSTED_CAMERAS) return null
            buildSet {
                repeat(count) { add(getBoundedString(MAX_IDENTITY_BYTES) ?: return null) }
            }
        } else emptySet()
        if (buffer.remaining() != dimensions * 4) return null
        val embedding = FloatArray(dimensions) { buffer.float }
        if (!EmbeddingMath.isUnitVector(embedding)) {
            embedding.fill(0f)
            return null
        }
        EnrollmentProfile(modelId, dimensions, embedding, createdAt, presentation, avatarStyle, preset, deviceIdentity, displayName, trustedCameraIdentities).also { embedding.fill(0f) }
    }.getOrNull()
}

