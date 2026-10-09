package com.consentcam.ble.enrollment

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedEnrollmentPayload private constructor(
    nonce: ByteArray,
    ciphertextAndTag: ByteArray,
) : AutoCloseable {
    private val mutableNonce = nonce.copyOf()
    private val mutableCiphertextAndTag = ciphertextAndTag.copyOf()

    val nonce: ByteArray
        get() = mutableNonce.copyOf()

    val ciphertextAndTag: ByteArray
        get() = mutableCiphertextAndTag.copyOf()

    fun toTransportBytes(): ByteArray = mutableNonce + mutableCiphertextAndTag

    override fun close() {
        mutableNonce.fill(0)
        mutableCiphertextAndTag.fill(0)
    }

    companion object {
        const val NONCE_SIZE_BYTES = 12
        const val TAG_SIZE_BYTES = 16

        fun fromTransportBytes(bytes: ByteArray): EncryptedEnrollmentPayload? {
            if (bytes.size < NONCE_SIZE_BYTES + TAG_SIZE_BYTES) return null
            return EncryptedEnrollmentPayload(
                nonce = bytes.copyOfRange(0, NONCE_SIZE_BYTES),
                ciphertextAndTag = bytes.copyOfRange(NONCE_SIZE_BYTES, bytes.size),
            )
        }

        internal fun create(
            nonce: ByteArray,
            ciphertextAndTag: ByteArray,
        ) = EncryptedEnrollmentPayload(nonce, ciphertextAndTag)
    }
}

class EnrollmentCipher(
    private val nonceGenerator: (ByteArray) -> Unit = SecureRandom()::nextBytes,
) {
    fun encrypt(
        sessionSecret: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): EncryptedEnrollmentPayload {
        require(sessionSecret.size == 32) { "Session secret must be exactly 256 bits" }
        val nonce = ByteArray(EncryptedEnrollmentPayload.NONCE_SIZE_BYTES)
        nonceGenerator(nonce)
        val cipher = newCipher(Cipher.ENCRYPT_MODE, sessionSecret, nonce, associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedEnrollmentPayload.create(nonce, ciphertext).also { nonce.fill(0) }
    }

    fun decrypt(
        sessionSecret: ByteArray,
        payload: EncryptedEnrollmentPayload,
        associatedData: ByteArray,
    ): Result<ByteArray> = runCatching {
        require(sessionSecret.size == 32) { "Session secret must be exactly 256 bits" }
        val nonce = payload.nonce
        val ciphertext = payload.ciphertextAndTag
        try {
            newCipher(Cipher.DECRYPT_MODE, sessionSecret, nonce, associatedData)
                .doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            throw SecurityException("Enrollment payload authentication failed", error)
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun newCipher(
        mode: Int,
        sessionSecret: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
    ): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(
            mode,
            SecretKeySpec(sessionSecret, "AES"),
            GCMParameterSpec(EncryptedEnrollmentPayload.TAG_SIZE_BYTES * Byte.SIZE_BITS, nonce),
        )
        updateAAD(associatedData)
    }
}

object EnrollmentAssociatedData {
    const val SCHEMA_VERSION: UByte = 1u

    fun encode(sessionId: UInt, transferId: UInt): ByteArray =
        ByteBuffer.allocate(UByte.SIZE_BYTES + UInt.SIZE_BYTES * 2)
            .order(ByteOrder.BIG_ENDIAN)
            .put(SCHEMA_VERSION.toByte())
            .putInt(sessionId.toInt())
            .putInt(transferId.toInt())
            .array()
}

