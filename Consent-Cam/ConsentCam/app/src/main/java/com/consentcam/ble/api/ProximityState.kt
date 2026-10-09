package com.consentcam.ble.api

enum class ProximityBand {
    NEAR,
    MID,
    FAR,
    LOST,
}

data class ProximityState(
    val sessionId: UInt,
    val smoothedRssi: Double,
    val band: ProximityBand,
    val insidePrivacyZone: Boolean,
    val lastSeenElapsedRealtimeMs: Long,
)

