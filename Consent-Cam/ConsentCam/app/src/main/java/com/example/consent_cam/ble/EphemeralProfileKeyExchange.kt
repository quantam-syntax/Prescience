package com.example.consent_cam.ble

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/** Unpaired, per-session ECDH encryption for automatic nearby profile exchange. */
object EphemeralProfileKeyExchange {
    private val domain = "ConsentCam automatic profile v1".toByteArray(StandardCharsets.UTF_8)

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    fun publicKeyBytes(keyPair: KeyPair): ByteArray = keyPair.public.encoded.copyOf()

    fun deriveSecret(privateKey: PrivateKey, peerPublicKeyBytes: ByteArray): ByteArray {
        require(peerPublicKeyBytes.size in 64..160) { "Invalid ephemeral public key" }
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peer, true)
            generateSecret()
        }
        return try {
            MessageDigest.getInstance("SHA-256").run {
                update(domain)
                digest(shared)
            }
        } finally {
            shared.fill(0)
        }
    }
}
