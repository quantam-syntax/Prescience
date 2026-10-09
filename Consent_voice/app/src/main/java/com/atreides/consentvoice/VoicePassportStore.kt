package com.atreides.consentvoice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keystore-encrypted, device-local Voice Passport samples. No audio is stored. */
class VoicePassportStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "consent_voice/passport_v1.bin")

    suspend fun load(): Result<List<FloatArray>> = withContext(Dispatchers.IO) { runCatching {
        val payload = file.readBytes(); require(payload.size > 29)
        val ivSize = payload[0].toUByte().toInt(); require(ivSize == 12 && payload.size > 1 + ivSize + 16)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload, 1, ivSize)); updateAAD(AAD)
        }
        val plain = cipher.doFinal(payload, 1 + ivSize, payload.size - 1 - ivSize)
        try { decode(plain) } finally { plain.fill(0); payload.fill(0) }
    } }

    suspend fun save(samples: List<FloatArray>) = withContext(Dispatchers.IO) { runCatching {
        require(samples.size == REQUIRED_SAMPLES && samples.map { it.size }.distinct().size == 1)
        val plain = encode(samples)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(create = true)); updateAAD(AAD) }
            val encrypted = cipher.doFinal(plain)
            file.parentFile?.mkdirs(); file.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        } finally { plain.fill(0) }
    } }

    private fun encode(samples: List<FloatArray>): ByteArray = ByteBuffer.allocate(1 + 1 + 2 + samples.sumOf { it.size * 4 }).order(ByteOrder.BIG_ENDIAN)
        .put(1).put(samples.size.toByte()).putShort(samples.first().size.toShort()).also { buffer -> samples.forEach { values -> values.forEach(buffer::putFloat) } }.array()

    private fun decode(bytes: ByteArray): List<FloatArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.remaining() >= 4 && buffer.get().toInt() == 1)
        val count = buffer.get().toUByte().toInt(); val dimension = buffer.short.toInt()
        require(count == REQUIRED_SAMPLES && dimension in 1..1024 && buffer.remaining() == count * dimension * 4)
        return List(count) { FloatArray(dimension) { buffer.float } }
    }

    private fun key(create: Boolean = false): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        require(create) { "Voice Passport key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        /** Hackathon close-range profile: front, right, left, and arm's length. */
        const val REQUIRED_SAMPLES = 4
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "consentvoice.passport.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "consentvoice:passport:v1".encodeToByteArray()
    }
}
