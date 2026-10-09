package com.example.consent_cam.recognition

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class TrustedCamera(val identity: String, val displayName: String)

data class TrustedCameraState(
    val identity: String = "",
    val displayName: String = "My Prescience",
    val cameras: List<TrustedCamera> = emptyList(),
    val revision: Int = 0,
)

/**
 * Stores a local app identity and the participant's revocable trusted-camera list.
 * The list is AES-GCM encrypted under an Android Keystore key and is only sent inside the
 * existing encrypted GATT profile transfer; advertisements contain neither names nor trust data.
 */
class TrustedCameraStore(context: Context) {
    private val appContext = context.applicationContext
    private val stateFile = File(appContext.noBackupFilesDir, FILE_NAME)
    private val mutableState = MutableStateFlow(TrustedCameraState())
    val state: StateFlow<TrustedCameraState> = mutableState.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val identity = deviceIdentity()
        val loaded = runCatching { decryptState() }.getOrNull()
        mutableState.value = (loaded ?: TrustedCameraState()).copy(identity = identity)
    }

    suspend fun setDisplayName(value: String) = mutate { current ->
        current.copy(displayName = value.trim().take(MAX_NAME_CHARS).ifBlank { "My Prescience" })
    }

    suspend fun trust(identity: String, displayName: String) = mutate { current ->
        if (identity.isBlank() || identity == current.identity) current else {
            val next = current.cameras.filterNot { it.identity == identity } +
                TrustedCamera(identity.take(MAX_IDENTITY_CHARS), displayName.trim().take(MAX_NAME_CHARS).ifBlank { "Nearby Prescience" })
            current.copy(cameras = next.take(MAX_CAMERAS))
        }
    }

    suspend fun revoke(identity: String) = mutate { current ->
        current.copy(cameras = current.cameras.filterNot { it.identity == identity })
    }

    private suspend fun mutate(transform: (TrustedCameraState) -> TrustedCameraState) = withContext(Dispatchers.IO) {
        val current = mutableState.value
        val next = transform(current).copy(identity = deviceIdentity(), revision = current.revision + 1)
        encryptState(next)
        mutableState.value = next
    }

    private fun deviceIdentity(): String {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val certificate = store.getCertificate(IDENTITY_KEY_ALIAS) ?: run {
            java.security.KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(IDENTITY_KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .build(),
                )
                generateKeyPair()
            }
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.getCertificate(IDENTITY_KEY_ALIAS)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(requireNotNull(certificate).publicKey.encoded)
        return Base64.encodeToString(digest.copyOf(18), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun encryptState(state: TrustedCameraState) {
        val plaintext = encode(state)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey()) }
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(plaintext)
            stateFile.parentFile?.mkdirs()
            stateFile.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext)
        } finally { plaintext.fill(0) }
    }

    private fun decryptState(): TrustedCameraState {
        val payload = stateFile.readBytes()
        require(payload.size > 29)
        val ivLength = payload[0].toUByte().toInt()
        require(ivLength == 12 && payload.size > ivLength + 17)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, payload, 1, ivLength))
            updateAAD(AAD)
        }
        val plaintext = cipher.doFinal(payload, 1 + ivLength, payload.size - 1 - ivLength)
        return try { decode(plaintext) } finally { plaintext.fill(0); payload.fill(0) }
    }

    private fun encode(state: TrustedCameraState): ByteArray {
        val name = state.displayName.encodeToByteArray()
        val entries = state.cameras.map { it.identity.encodeToByteArray() to it.displayName.encodeToByteArray() }
        require(name.size <= MAX_NAME_CHARS && entries.size <= MAX_CAMERAS)
        val size = 1 + 1 + name.size + 1 + entries.sumOf { 1 + it.first.size + 1 + it.second.size }
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).put(1).put(name.size.toByte()).put(name).put(entries.size.toByte()).also { buffer ->
            entries.forEach { (id, label) -> buffer.put(id.size.toByte()).put(id).put(label.size.toByte()).put(label) }
        }.array()
    }

    private fun decode(bytes: ByteArray): TrustedCameraState {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.remaining() >= 3 && buffer.get().toInt() == 1)
        fun string(max: Int): String { val size = buffer.get().toUByte().toInt(); require(size <= max && buffer.remaining() >= size); return ByteArray(size).also(buffer::get).decodeToString() }
        val name = string(MAX_NAME_CHARS)
        val count = buffer.get().toUByte().toInt(); require(count <= MAX_CAMERAS)
        val cameras = List(count) { TrustedCamera(string(MAX_IDENTITY_CHARS), string(MAX_NAME_CHARS)) }
        require(!buffer.hasRemaining())
        return TrustedCameraState(displayName = name, cameras = cameras)
    }

    private fun encryptionKey(): SecretKey = (KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(ENCRYPTION_KEY_ALIAS, null) as? SecretKey)
        ?: error("Trusted-camera encryption key unavailable")
    private fun getOrCreateEncryptionKey(): SecretKey = runCatching { encryptionKey() }.getOrElse {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ENCRYPTION_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val IDENTITY_KEY_ALIAS = "prescience.trusted-camera.identity.v1"
        const val ENCRYPTION_KEY_ALIAS = "prescience.trusted-camera.records.v1"
        const val FILE_NAME = "prescience/trusted_cameras_v1.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AAD = "prescience:trusted-cameras:v1".encodeToByteArray()
        const val MAX_NAME_CHARS = 48
        const val MAX_IDENTITY_CHARS = 96
        const val MAX_CAMERAS = 16
    }
}

/** A stable, installation-specific code derived from the Keystore-backed app identity. */
object DevicePairingCode {
    fun forIdentity(identity: String): String {
        if (identity.isBlank()) return "------"
        val value = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray()), 0, 4).int.toUInt().toLong() % 1_000_000L
        return "%06d".format(java.util.Locale.US, value)
    }
}
