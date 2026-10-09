package com.consentcam.ble.api

/** A decoded Consent-Cam service-data advertisement and its scan RSSI. */
data class ConsentAdvertisement(
    val sessionId: UInt,
    val flags: UByte,
    val rotatingToken: UInt,
    val rssi: Int,
)

