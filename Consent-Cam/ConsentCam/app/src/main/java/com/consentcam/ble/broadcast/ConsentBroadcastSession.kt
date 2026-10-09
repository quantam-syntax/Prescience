package com.consentcam.ble.broadcast

import com.consentcam.ble.enrollment.QrSessionSecret
import com.consentcam.ble.enrollment.QrSessionSecretCodec
import com.consentcam.ble.protocol.ConsentAdvertisementCodec
import com.consentcam.ble.protocol.ConsentFlags
import com.consentcam.ble.security.RotatingToken
import java.security.SecureRandom

/** In-memory broadcaster state; starting a new consent choice must create a new session. */
class ConsentBroadcastSession private constructor(
    val sessionId: UInt,
    val flags: UByte,
    secret: ByteArray,
) : AutoCloseable {
    private val mutableSecret = secret.copyOf()
    private var closed = false

    init {
        require(!ConsentFlags.hasUnknownBits(flags))
        require(secret.size == 32)
    }

    @Synchronized
    fun serviceData(epochSeconds: Long): ByteArray {
        check(!closed) { "Broadcast session is closed" }
        val token = RotatingToken.create(
            sessionSecret = mutableSecret,
            sessionId = sessionId,
            flags = flags,
            counter = RotatingToken.timeCounter(epochSeconds),
        )
        return ConsentAdvertisementCodec.encode(sessionId, flags, token)
    }

    @Synchronized
    fun qrPayload(): String {
        check(!closed) { "Broadcast session is closed" }
        val qrSecret = QrSessionSecret.create(sessionId, mutableSecret)
        return try {
            QrSessionSecretCodec.encode(qrSecret)
        } finally {
            qrSecret.close()
        }
    }

    @Synchronized
    override fun close() {
        mutableSecret.fill(0)
        closed = true
    }

    companion object {
        fun create(flags: UByte, random: SecureRandom = SecureRandom()): ConsentBroadcastSession {
            val qrSecret = QrSessionSecretCodec.generate(random)
            val secret = qrSecret.secretCopy()
            return try {
                ConsentBroadcastSession(qrSecret.sessionId, flags, secret)
            } finally {
                secret.fill(0)
                qrSecret.close()
            }
        }

        fun restore(sessionId: UInt, flags: UByte, secret: ByteArray): ConsentBroadcastSession =
            ConsentBroadcastSession(sessionId, flags, secret)
    }
}

