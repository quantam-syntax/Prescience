package com.example.consent_cam.camera

import com.example.consent_cam.ble.NearbyVoiceRequest

data class VoiceCaptureDecision(val processVoice: Boolean, val error: String? = null)

/** A Protected screen without an active voice request may record ordinary audio. */
object VoiceCaptureReadiness {
    fun evaluate(
        protectedMode: Boolean,
        nearbyVoiceSignaled: Boolean,
        remoteRequests: List<NearbyVoiceRequest>?,
        localPassportAvailable: Boolean,
        microphoneAllowed: Boolean,
    ): VoiceCaptureDecision {
        if (!protectedMode && !nearbyVoiceSignaled) return VoiceCaptureDecision(false)
        val requests = remoteRequests.orEmpty()
        val processVoice = requests.isNotEmpty() || (protectedMode && localPassportAvailable)
        if (requests.size > 1 || (localPassportAvailable && requests.isNotEmpty())) {
            return VoiceCaptureDecision(false, "More than one protected voice is active. Record one protected person at a time.")
        }
        if (nearbyVoiceSignaled && (requests.size != 1 || requests.single().samples?.size != 4)) {
            return VoiceCaptureDecision(false, "Nearby voice signal received, but its Voice Passport is missing. Check enrollment on the sending phone, retry BLE if needed, then wait for received profiles: 1.")
        }
        if (processVoice && !microphoneAllowed) {
            return VoiceCaptureDecision(false, "Allow microphone access before recording protected audio.")
        }
        return VoiceCaptureDecision(processVoice)
    }
}
