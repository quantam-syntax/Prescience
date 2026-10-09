package com.consentcam.ble.proximity

import com.consentcam.ble.api.ProximityBand
import com.consentcam.ble.api.ProximityState

/** Deterministic, per-session RSSI filtering and privacy-zone hysteresis. */
class ProximityTracker(private val config: ProximityConfig = ProximityConfig()) {
    private data class SessionState(
        val filter: RssiFilter,
        var smoothedRssi: Double = Double.NaN,
        var insidePrivacyZone: Boolean = false,
        var enterSamples: Int = 0,
        var exitSamples: Int = 0,
        var lastSeenMs: Long = 0,
    )

    private val sessions = mutableMapOf<UInt, SessionState>()

    @Synchronized
    fun recordSample(sessionId: UInt, rawRssi: Int, elapsedRealtimeMs: Long): ProximityState? {
        require(elapsedRealtimeMs >= 0) { "Elapsed realtime cannot be negative" }
        if (rawRssi !in config.minimumRssi..config.maximumRssi) return null
        val existingState = sessions[sessionId]
        if (elapsedRealtimeMs < (existingState?.lastSeenMs ?: 0L)) return null
        if (
            existingState != null &&
            elapsedRealtimeMs - existingState.lastSeenMs >= config.staleTimeoutMs
        ) {
            existingState.insidePrivacyZone = false
            existingState.enterSamples = 0
            existingState.exitSamples = 0
        }
        val state = sessions.getOrPut(sessionId) { SessionState(RssiFilter(config)) }
        val smoothed = state.filter.add(rawRssi) ?: return null
        state.smoothedRssi = smoothed
        state.lastSeenMs = elapsedRealtimeMs
        updatePrivacyZone(state, smoothed)
        return state.toPublic(sessionId, bandFor(smoothed))
    }

    @Synchronized
    fun states(elapsedRealtimeMs: Long): List<ProximityState> {
        require(elapsedRealtimeMs >= 0) { "Elapsed realtime cannot be negative" }
        return sessions.map { (sessionId, state) ->
            val isStale = elapsedRealtimeMs - state.lastSeenMs >= config.staleTimeoutMs
            if (isStale) {
                state.insidePrivacyZone = false
                state.enterSamples = 0
                state.exitSamples = 0
            }
            state.toPublic(
                sessionId = sessionId,
                band = if (isStale) ProximityBand.LOST else bandFor(state.smoothedRssi),
            )
        }
    }

    @Synchronized
    fun removeSession(sessionId: UInt) {
        sessions.remove(sessionId)
    }

    @Synchronized
    fun clear() {
        sessions.clear()
    }

    private fun updatePrivacyZone(state: SessionState, smoothedRssi: Double) {
        if (state.insidePrivacyZone) {
            state.enterSamples = 0
            state.exitSamples = if (smoothedRssi <= config.exitPrivacyZoneRssi) {
                state.exitSamples + 1
            } else {
                0
            }
            if (state.exitSamples >= config.samplesToExit) {
                state.insidePrivacyZone = false
                state.exitSamples = 0
            }
        } else {
            state.exitSamples = 0
            state.enterSamples = if (smoothedRssi >= config.enterPrivacyZoneRssi) {
                state.enterSamples + 1
            } else {
                0
            }
            if (state.enterSamples >= config.samplesToEnter) {
                state.insidePrivacyZone = true
                state.enterSamples = 0
            }
        }
    }

    private fun bandFor(smoothedRssi: Double): ProximityBand = when {
        smoothedRssi >= config.nearThresholdRssi -> ProximityBand.NEAR
        smoothedRssi >= config.midThresholdRssi -> ProximityBand.MID
        else -> ProximityBand.FAR
    }

    private fun SessionState.toPublic(
        sessionId: UInt,
        band: ProximityBand,
    ) = ProximityState(
        sessionId = sessionId,
        smoothedRssi = smoothedRssi,
        band = band,
        insidePrivacyZone = insidePrivacyZone,
        lastSeenElapsedRealtimeMs = lastSeenMs,
    )
}
