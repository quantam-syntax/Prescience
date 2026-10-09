package com.example.consent_cam.ble

import com.consentcam.ble.proximity.ProximityConfig

enum class PrivacyZonePreset(val label: String) {
    NEAR("Close"),
    BALANCED("Nearby"),
    FAR("Room");

    fun config(): ProximityConfig = when (this) {
        NEAR -> ProximityConfig(
            medianWindowSize = 3,
            smoothingAlpha = 0.50,
            enterPrivacyZoneRssi = -60.0,
            exitPrivacyZoneRssi = -68.0,
            samplesToEnter = 2,
        )
        BALANCED -> ProximityConfig(
            medianWindowSize = 3,
            smoothingAlpha = 0.50,
            enterPrivacyZoneRssi = -70.0,
            exitPrivacyZoneRssi = -78.0,
            samplesToEnter = 2,
        )
        FAR -> ProximityConfig(
            medianWindowSize = 3,
            smoothingAlpha = 0.55,
            enterPrivacyZoneRssi = -82.0,
            exitPrivacyZoneRssi = -90.0,
            samplesToEnter = 2,
            samplesToExit = 4,
            staleTimeoutMs = 8_000L,
        )
    }
}
