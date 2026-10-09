package com.consentcam.ble.session

import com.consentcam.ble.api.ConsentAdvertisement
import com.consentcam.ble.api.ProximityBand
import com.consentcam.ble.api.ProximityState
import com.consentcam.ble.protocol.ConsentFlags
import com.consentcam.ble.proximity.ProximityTracker

/**
 * Zero-pairing BLE mode. It accepts well-formed service data without claiming cryptographic
 * identity verification. PROTECT may activate broad protection; ALLOW must never unblur alone.
 */
class DirectBleConsentRegistry(
    private var proximityTracker: ProximityTracker = ProximityTracker(),
    private val staleTimeoutMs: Long = 5_000L,
) : AutoCloseable {
    data class Session(
        val sessionId: UInt,
        val consent: ConsentStatus,
        val proximity: ProximityState,
    )

    private data class SeenSession(var flags: UByte, var lastSeenMs: Long)

    private val seenSessions = mutableMapOf<UInt, SeenSession>()

    init {
        require(staleTimeoutMs > 0)
    }

    @Synchronized
    fun observe(advertisement: ConsentAdvertisement, elapsedRealtimeMs: Long): Session? {
        val proximity = proximityTracker.recordSample(
            advertisement.sessionId,
            advertisement.rssi,
            elapsedRealtimeMs,
        ) ?: return null
        seenSessions[advertisement.sessionId] = SeenSession(
            flags = advertisement.flags,
            lastSeenMs = elapsedRealtimeMs,
        )
        return toPublic(advertisement.sessionId, advertisement.flags, proximity)
    }

    @Synchronized
    fun activeSessions(elapsedRealtimeMs: Long): List<Session> {
        val proximityById = proximityTracker.states(elapsedRealtimeMs)
            .associateBy(ProximityState::sessionId)
        return seenSessions.mapNotNull { (sessionId, seen) ->
            val proximity = proximityById[sessionId] ?: return@mapNotNull null
            if (
                elapsedRealtimeMs - seen.lastSeenMs >= staleTimeoutMs ||
                proximity.band == ProximityBand.LOST
            ) {
                null
            } else {
                toPublic(sessionId, seen.flags, proximity)
            }
        }
    }

    @Synchronized
    fun hasNearbyProtectSession(elapsedRealtimeMs: Long): Boolean =
        activeSessions(elapsedRealtimeMs).any { session ->
            session.consent == ConsentStatus.PROTECT && session.proximity.insidePrivacyZone
        }

    @Synchronized
    fun replaceProximityTracker(newTracker: ProximityTracker) {
        proximityTracker.clear()
        proximityTracker = newTracker
        seenSessions.clear()
    }

    @Synchronized
    override fun close() {
        seenSessions.clear()
        proximityTracker.clear()
    }

    private fun toPublic(
        sessionId: UInt,
        flags: UByte,
        proximity: ProximityState,
    ) = Session(
        sessionId = sessionId,
        consent = if (ConsentFlags.isAllow(flags)) ConsentStatus.ALLOW else ConsentStatus.PROTECT,
        proximity = proximity,
    )
}
