package com.consentcam.ble.scan

import com.consentcam.ble.protocol.ConsentAdvertisementCodec
import com.consentcam.ble.session.VerifiedSessionRegistry

/** Converts Android scan metadata into deterministic protocol/session results. */
class ConsentScanProcessor(
    private val sessionRegistry: VerifiedSessionRegistry,
) {
    sealed interface Result {
        data class MalformedAdvertisement(
            val codecResult: ConsentAdvertisementCodec.DecodeResult,
        ) : Result

        data class SessionObservation(
            val result: VerifiedSessionRegistry.ObservationResult,
        ) : Result
    }

    fun process(
        serviceData: ByteArray,
        rssi: Int,
        epochSeconds: Long,
        elapsedRealtimeMs: Long,
    ): Result = when (val decoded = ConsentAdvertisementCodec.decode(serviceData, rssi)) {
        is ConsentAdvertisementCodec.DecodeResult.Success -> Result.SessionObservation(
            sessionRegistry.observe(decoded.advertisement, epochSeconds, elapsedRealtimeMs),
        )

        else -> Result.MalformedAdvertisement(decoded)
    }
}

