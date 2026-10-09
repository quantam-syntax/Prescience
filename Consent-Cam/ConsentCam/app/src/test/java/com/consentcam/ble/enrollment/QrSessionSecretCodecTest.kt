package com.consentcam.ble.enrollment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrSessionSecretCodecTest {
    @Test
    fun encodeDecodeRoundTripContainsOnlySessionIdAndSecret() {
        val original = QrSessionSecret.create(0x10203040u, ByteArray(32) { it.toByte() })

        val encoded = QrSessionSecretCodec.encode(original)
        val decoded = QrSessionSecretCodec.decode(encoded)!!

        assertEquals(original.sessionId, decoded.sessionId)
        assertArrayEquals(original.secretCopy(), decoded.secretCopy())
        original.close()
        decoded.close()
    }

    @Test
    fun decodeRejectsWrongVersionAndMalformedSecret() {
        assertNull(QrSessionSecretCodec.decode("consentcam:v2:00000001:bad"))
        assertNull(QrSessionSecretCodec.decode("consentcam:v1:00000001:bad"))
    }
}

