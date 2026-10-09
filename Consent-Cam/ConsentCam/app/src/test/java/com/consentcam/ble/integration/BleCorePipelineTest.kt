package com.consentcam.ble.integration

import com.consentcam.ble.broadcast.ConsentBroadcastSession
import com.consentcam.ble.enrollment.QrSessionSecretCodec
import com.consentcam.ble.proximity.ProximityConfig
import com.consentcam.ble.proximity.ProximityTracker
import com.consentcam.ble.scan.ConsentScanProcessor
import com.consentcam.ble.session.VerifiedSessionRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class BleCorePipelineTest {
    @Test
    fun broadcastQrAndScanPipelineActivatesVerifiedProtection() {
        val secret = ByteArray(32) { it.toByte() }
        val broadcaster = ConsentBroadcastSession.restore(7u, 0u, secret)
        val qrSecret = QrSessionSecretCodec.decode(broadcaster.qrPayload())!!
        val registry = VerifiedSessionRegistry(
            proximityTracker = ProximityTracker(
                ProximityConfig(
                    medianWindowSize = 1,
                    smoothingAlpha = 1.0,
                    samplesToEnter = 1,
                    samplesToExit = 1,
                ),
            ),
        )
        val registeredSecret = qrSecret.secretCopy()
        registry.registerSessionSecret(qrSecret.sessionId, registeredSecret)
        registeredSecret.fill(0)
        val processor = ConsentScanProcessor(registry)

        val result = processor.process(
            serviceData = broadcaster.serviceData(epochSeconds = 60),
            rssi = -55,
            epochSeconds = 60,
            elapsedRealtimeMs = 100,
        )

        assertTrue(result is ConsentScanProcessor.Result.SessionObservation)
        assertTrue(registry.hasNearbyProtectSession(100))
        qrSecret.close()
        broadcaster.close()
        registry.close()
        secret.fill(0)
    }

    @Test
    fun malformedServiceDataIsRejectedBeforeSessionProcessing() {
        val processor = ConsentScanProcessor(VerifiedSessionRegistry())

        val result = processor.process(byteArrayOf(1), -50, 0, 0)

        assertTrue(result is ConsentScanProcessor.Result.MalformedAdvertisement)
    }
}

