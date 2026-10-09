package com.consentcam.ble.protocol

import com.consentcam.ble.api.ConsentAdvertisement

/**
 * Encodes the fixed-width service-data payload. Multi-byte fields use network byte order.
 * RSSI is scan metadata and is therefore supplied only while decoding.
 */
object ConsentAdvertisementCodec {
    const val SERVICE_UUID: String = "83c3b46e-0aef-4a4b-8b9e-44865c70a931"
    const val PROTOCOL_VERSION: UByte = 1u
    const val PAYLOAD_SIZE_BYTES: Int = 10

    sealed interface DecodeResult {
        data class Success(val advertisement: ConsentAdvertisement) : DecodeResult
        data class InvalidLength(val actualBytes: Int) : DecodeResult
        data class UnsupportedVersion(val version: UByte) : DecodeResult
        data class UnknownFlags(val flags: UByte) : DecodeResult
    }

    fun encode(
        sessionId: UInt,
        flags: UByte,
        rotatingToken: UInt,
    ): ByteArray {
        require(!ConsentFlags.hasUnknownBits(flags)) { "Unknown consent flag bits" }

        return ByteArray(PAYLOAD_SIZE_BYTES).also { output ->
            output[0] = PROTOCOL_VERSION.toByte()
            writeUInt(output, offset = 1, sessionId)
            output[5] = flags.toByte()
            writeUInt(output, offset = 6, rotatingToken)
        }
    }

    fun decode(payload: ByteArray, rssi: Int): DecodeResult {
        if (payload.size != PAYLOAD_SIZE_BYTES) {
            return DecodeResult.InvalidLength(payload.size)
        }

        val version = payload[0].toUByte()
        if (version != PROTOCOL_VERSION) {
            return DecodeResult.UnsupportedVersion(version)
        }

        val flags = payload[5].toUByte()
        if (ConsentFlags.hasUnknownBits(flags)) {
            return DecodeResult.UnknownFlags(flags)
        }

        return DecodeResult.Success(
            ConsentAdvertisement(
                sessionId = readUInt(payload, offset = 1),
                flags = flags,
                rotatingToken = readUInt(payload, offset = 6),
                rssi = rssi,
            ),
        )
    }

    private fun writeUInt(output: ByteArray, offset: Int, value: UInt) {
        for (index in 0 until UInt.SIZE_BYTES) {
            val shift = (UInt.SIZE_BYTES - index - 1) * Byte.SIZE_BITS
            output[offset + index] = (value shr shift).toByte()
        }
    }

    private fun readUInt(input: ByteArray, offset: Int): UInt {
        var result = 0u
        for (index in 0 until UInt.SIZE_BYTES) {
            result = (result shl Byte.SIZE_BITS) or input[offset + index].toUByte().toUInt()
        }
        return result
    }
}
