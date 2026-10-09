package com.consentcam.ble.enrollment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EnrollmentCipherTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val associatedData = EnrollmentAssociatedData.encode(7u, 9u)

    @Test
    fun encryptDecryptRoundTripUsesAuthenticatedAssociatedData() {
        var nonceByte = 0
        val cipher = EnrollmentCipher { nonce -> nonce.fill(nonceByte++.toByte()) }
        val plaintext = "session embedding bytes".encodeToByteArray()

        val first = cipher.encrypt(secret, plaintext, associatedData)
        val second = cipher.encrypt(secret, plaintext, associatedData)

        assertArrayEquals(plaintext, cipher.decrypt(secret, first, associatedData).getOrThrow())
        assertNotEquals(first.nonce.toList(), second.nonce.toList())
        first.close()
        second.close()
    }

    @Test
    fun decryptFailsClosedWhenAssociatedDataIsWrong() {
        val cipher = EnrollmentCipher { nonce -> nonce.fill(4) }
        val encrypted = cipher.encrypt(secret, byteArrayOf(1, 2, 3), associatedData)

        val result = cipher.decrypt(secret, encrypted, EnrollmentAssociatedData.encode(8u, 9u))

        assertFalse(result.isSuccess)
        encrypted.close()
    }

    @Test
    fun transportEncodingCanBeParsedAndDecrypted() {
        val cipher = EnrollmentCipher { nonce -> nonce.fill(7) }
        val encrypted = cipher.encrypt(secret, byteArrayOf(5, 6, 7), associatedData)

        val parsed = EncryptedEnrollmentPayload.fromTransportBytes(encrypted.toTransportBytes())

        assertNotNull(parsed)
        assertArrayEquals(byteArrayOf(5, 6, 7), cipher.decrypt(secret, parsed!!, associatedData).getOrThrow())
        encrypted.close()
        parsed.close()
    }
}

