package com.example.consent_cam.recognition

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OwnerProfileStore(context: Context) {
    private val profileFile = File(context.noBackupFilesDir, PROFILE_FILE_NAME)

    suspend fun hasProfile(): Boolean = withContext(Dispatchers.IO) { profileFile.isFile }

    suspend fun save(profile: EnrollmentProfile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val plaintext = EnrollmentProfileCodec.encode(profile)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                cipher.updateAAD(ASSOCIATED_DATA)
                val encrypted = cipher.doFinal(plaintext)
                val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
                val temporary = File(profileFile.parentFile, "${profileFile.name}.tmp")
                profileFile.parentFile?.mkdirs()
                temporary.outputStream().use { it.write(payload) }
                runCatching {
                    Files.move(
                        temporary.toPath(),
                        profileFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.getOrElse {
                    Files.move(
                        temporary.toPath(),
                        profileFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                Unit
            } finally {
                plaintext.fill(0)
            }
        }
    }

    suspend fun load(): Result<EnrollmentProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = profileFile.readBytes()
            require(payload.size > 1 + 12 + 16) { "Encrypted profile is truncated" }
            val nonceLength = payload[0].toUByte().toInt()
            require(nonceLength == 12 && payload.size > 1 + nonceLength + 16) {
                "Encrypted profile nonce is invalid"
            }
            val nonce = payload.copyOfRange(1, 1 + nonceLength)
            val ciphertext = payload.copyOfRange(1 + nonceLength, payload.size)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, nonce))
                cipher.updateAAD(ASSOCIATED_DATA)
                val plaintext = cipher.doFinal(ciphertext)
                try {
                    EnrollmentProfileCodec.decode(plaintext)
                        ?: error("Stored face profile is incompatible")
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                nonce.fill(0)
                ciphertext.fill(0)
                payload.fill(0)
            }
        }.onFailure { deleteInternal() }
    }

    suspend fun delete() = withContext(Dispatchers.IO) { deleteInternal() }

    private fun deleteInternal() {
        if (profileFile.exists()) profileFile.delete()
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun existingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Face profile key is unavailable")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "consentcam.owner.face-profile.v1"
        const val PROFILE_FILE_NAME = "consentcam/face_profile_v1.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val ASSOCIATED_DATA = "consentcam:owner-profile:$FACENET_MODEL_ID".encodeToByteArray()
    }
}
