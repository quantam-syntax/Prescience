package com.consentcam.ble.proximity

data class ProximityConfig(
    val minimumRssi: Int = -127,
    val maximumRssi: Int = 20,
    val medianWindowSize: Int = 5,
    val smoothingAlpha: Double = 0.35,
    val nearThresholdRssi: Double = -60.0,
    val midThresholdRssi: Double = -75.0,
    val enterPrivacyZoneRssi: Double = -65.0,
    val exitPrivacyZoneRssi: Double = -72.0,
    val samplesToEnter: Int = 3,
    val samplesToExit: Int = 3,
    val staleTimeoutMs: Long = 5_000L,
) {
    init {
        require(minimumRssi < maximumRssi)
        require(medianWindowSize > 0)
        require(smoothingAlpha in 0.0..1.0 && smoothingAlpha > 0.0)
        require(nearThresholdRssi > midThresholdRssi)
        require(enterPrivacyZoneRssi > exitPrivacyZoneRssi)
        require(samplesToEnter > 0)
        require(samplesToExit > 0)
        require(staleTimeoutMs > 0)
    }
}

