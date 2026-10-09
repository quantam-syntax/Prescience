package com.consentcam.ble.protocol

import com.consentcam.ble.api.ConsentAdvertisement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ConsentAdvertisementCodecTest {
    @Test
    fun serviceUuid_isAValidPrivate128BitUuid() {
        assertEquals(
            ConsentAdvertisementCodec.SERVICE_UUID,
            UUID.fromString(ConsentAdvertisementCodec.SERVICE_UUID).toString(),
        )
    }

    @Test
    fun encode_usesDocumentedBigEndianWireFormat() {
        val encoded = ConsentAdvertisementCodec.encode(
            sessionId = 0x01020304u,
            flags = ConsentFlags.PRECISE_MATCH_AVAILABLE.toUByte(),
            rotatingToken = 0xa1b2c3d4u,
        )

        assertArrayEquals(
            byteArrayOf(1, 1, 2, 3, 4, 2, 0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte()),
            encoded,
        )
    }

    @Test
    fun decode_roundTripsEveryUnsignedField() {
        val expected = ConsentAdvertisement(
            sessionId = UInt.MAX_VALUE,
            flags = ConsentFlags.ALLOW_APPEARANCE.toUByte(),
            rotatingToken = 0x80000000u,
            rssi = -67,
        )
        val payload = ConsentAdvertisementCodec.encode(
            expected.sessionId,
            expected.flags,
            expected.rotatingToken,
        )

        val result = ConsentAdvertisementCodec.decode(payload, expected.rssi)

        assertEquals(ConsentAdvertisementCodec.DecodeResult.Success(expected), result)
    }

    @Test
    fun decode_rejectsMalformedLengthVersionAndFlags() {
        assertEquals(
            ConsentAdvertisementCodec.DecodeResult.InvalidLength(1),
            ConsentAdvertisementCodec.decode(byteArrayOf(1), rssi = -50),
        )
        val unsupportedVersion = ByteArray(ConsentAdvertisementCodec.PAYLOAD_SIZE_BYTES)
        unsupportedVersion[0] = 2
        assertEquals(
            ConsentAdvertisementCodec.DecodeResult.UnsupportedVersion(2u),
            ConsentAdvertisementCodec.decode(unsupportedVersion, rssi = -50),
        )
        val unknownFlags = ByteArray(ConsentAdvertisementCodec.PAYLOAD_SIZE_BYTES)
        unknownFlags[0] = 1
        unknownFlags[5] = 0x40
        assertEquals(
            ConsentAdvertisementCodec.DecodeResult.UnknownFlags(0x40u),
            ConsentAdvertisementCodec.decode(unknownFlags, rssi = -50),
        )
    }

    @Test
    fun encode_rejectsUnknownFlagBits() {
        val exception = runCatching {
            ConsentAdvertisementCodec.encode(1u, 0x80u, 2u)
        }.exceptionOrNull()

        assertTrue(exception is IllegalArgumentException)
    }
}
