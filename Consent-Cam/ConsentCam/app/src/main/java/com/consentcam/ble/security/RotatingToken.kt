package com.consentcam.ble.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RotatingToken {
    const val WINDOW_SECONDS: Long = 30L
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val TOKEN_BYTES = 4

    fun timeCounter(epochSeconds: Long): Long {
        require(epochSeconds >= 0) { "Epoch seconds cannot be negative" }
        return epochSeconds / WINDOW_SECONDS
    }

    fun create(
        sessionSecret: ByteArray,
        sessionId: UInt,
        flags: UByte,
        counter: Long,
    ): UInt {
        require(sessionSecret.size == 32) { "Session secret must be exactly 256 bits" }
        require(counter >= 0) { "Time counter cannot be negative" }

        val input = ByteBuffer.allocate(UInt.SIZE_BYTES + Long.SIZE_BYTES + UByte.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(sessionId.toInt())
            .putLong(counter)
            .put(flags.toByte())
            .array()
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(sessionSecret, HMAC_ALGORITHM))
        val digest = mac.doFinal(input)

        return ByteBuffer.wrap(digest, 0, TOKEN_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int
            .toUInt()
    }
}

/** Maintains replay state separately for each ephemeral session. */
class RotatingTokenVerifier {
    enum class Status {
        VALID_FRESH,
        VALID_REPEAT,
        INVALID,
    }

    data class Result(
        val status: Status,
        val matchedCounter: Long? = null,
    ) {
        val isValid: Boolean
            get() = status != Status.INVALID

        /** Only a newly observed time window may extend authenticated freshness. */
        val extendsSession: Boolean
            get() = status == Status.VALID_FRESH
    }

    private val highestAcceptedCounter = mutableMapOf<UInt, Long>()

    @Synchronized
    fun verify(
        sessionSecret: ByteArray,
        sessionId: UInt,
        flags: UByte,
        token: UInt,
        epochSeconds: Long,
    ): Result {
        if (sessionSecret.size != 32 || epochSeconds < 0) return Result(Status.INVALID)
        val currentCounter = RotatingToken.timeCounter(epochSeconds)
        val matchingCounter = sequenceOf(currentCounter, currentCounter - 1)
            .filter { it >= 0 }
            .firstOrNull { counter ->
                constantTimeEquals(
                    token,
                    RotatingToken.create(sessionSecret, sessionId, flags, counter),
                )
            }
            ?: return Result(Status.INVALID)

        val previousHighest = highestAcceptedCounter[sessionId]
        if (previousHighest == null || matchingCounter > previousHighest) {
            highestAcceptedCounter[sessionId] = matchingCounter
            return Result(Status.VALID_FRESH, matchingCounter)
        }

        return Result(Status.VALID_REPEAT, matchingCounter)
    }

    @Synchronized
    fun clearSession(sessionId: UInt) {
        highestAcceptedCounter.remove(sessionId)
    }

    @Synchronized
    fun clearAll() {
        highestAcceptedCounter.clear()
    }

    private fun constantTimeEquals(left: UInt, right: UInt): Boolean {
        var difference = 0
        for (shift in 0 until UInt.SIZE_BYTES) {
            difference = difference or
                (((left shr (shift * Byte.SIZE_BITS)) xor
                    (right shr (shift * Byte.SIZE_BITS))) and 0xffu).toInt()
        }
        return difference == 0
    }
}
