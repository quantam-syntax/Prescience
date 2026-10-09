package com.consentcam.ble.enrollment

import java.security.SecureRandom
import java.util.Base64

class QrSessionSecret private constructor(
    val sessionId: UInt,
    secret: ByteArray,
) : AutoCloseable {
    private val mutableSecret = secret.copyOf()

    fun secretCopy(): ByteArray = mutableSecret.copyOf()

    override fun close() {
        mutableSecret.fill(0)
    }

    companion object {
        internal fun create(sessionId: UInt, secret: ByteArray): QrSessionSecret =
            QrSessionSecret(sessionId, secret)
    }
}

object QrSessionSecretCodec {
    private const val PREFIX = "consentcam:v1"

    fun generate(random: SecureRandom = SecureRandom()): QrSessionSecret {
        val secret = ByteArray(32).also(random::nextBytes)
        val sessionIdBytes = ByteArray(4).also(random::nextBytes)
        val sessionId = sessionIdBytes.fold(0u) { result, byte ->
            (result shl Byte.SIZE_BITS) or byte.toUByte().toUInt()
        }
        sessionIdBytes.fill(0)
        return QrSessionSecret.create(sessionId, secret).also { secret.fill(0) }
    }

    fun encode(payload: QrSessionSecret): String {
        val secret = payload.secretCopy()
        return try {
            val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
            "$PREFIX:${payload.sessionId.toString(16).padStart(8, '0')}:$encodedSecret"
        } finally {
            secret.fill(0)
        }
    }

    fun decode(value: String): QrSessionSecret? {
        val parts = value.split(':')
        if (parts.size != 4 || "${parts[0]}:${parts[1]}" != PREFIX) return null
        if (parts[2].length != 8) return null
        if (parts[3].length != 43) return null
        val sessionId = parts[2].toUIntOrNull(radix = 16) ?: return null
        val secret = runCatching { Base64.getUrlDecoder().decode(parts[3]) }.getOrNull()
            ?: return null
        if (secret.size != 32) {
            secret.fill(0)
            return null
        }
        return QrSessionSecret.create(sessionId, secret).also { secret.fill(0) }
    }
}
