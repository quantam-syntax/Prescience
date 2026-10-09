package com.consentcam.ble.session

import com.consentcam.ble.api.ConsentAdvertisement
import com.consentcam.ble.api.ProximityBand
import com.consentcam.ble.api.ProximityState
import com.consentcam.ble.protocol.ConsentFlags
import com.consentcam.ble.proximity.ProximityTracker
import com.consentcam.ble.security.RotatingToken
import com.consentcam.ble.security.RotatingTokenVerifier

enum class ConsentStatus {
    ALLOW,
    PROTECT,
}

data class VerifiedConsentSession(
    val sessionId: UInt,
    val consent: ConsentStatus,
    val preciseMatchAvailable: Boolean,
    val proximity: ProximityState,
    val authenticatedAtElapsedRealtimeMs: Long,
)

/**
 * Owns recorder-side session secrets only in memory and combines authentication with proximity.
 * Repeated tokens may update physical proximity, but only a new token window refreshes authentication.
 */
class VerifiedSessionRegistry(
    private val proximityTracker: ProximityTracker = ProximityTracker(),
    private val authenticationTimeoutMs: Long = RotatingToken.WINDOW_SECONDS * 2_000L + 5_000L,
    private val tokenVerifier: RotatingTokenVerifier = RotatingTokenVerifier(),
) : AutoCloseable {
    sealed interface ObservationResult {
        data class Verified(val session: VerifiedConsentSession) : ObservationResult
        data object UnverifiedDiscovery : ObservationResult
        data class Rejected(val reason: RejectionReason) : ObservationResult
    }

    enum class RejectionReason {
        INVALID_TOKEN,
        INVALID_RSSI,
        FLAGS_CHANGED_WITHOUT_FRESH_WINDOW,
    }

    private data class AuthenticatedState(
        var flags: UByte,
        var authenticatedAtMs: Long,
    )

    private val secrets = mutableMapOf<UInt, ByteArray>()
    private val authenticated = mutableMapOf<UInt, AuthenticatedState>()

    init {
        require(authenticationTimeoutMs > 0)
    }

    @Synchronized
    fun registerSessionSecret(sessionId: UInt, sessionSecret: ByteArray) {
        require(sessionSecret.size == 32) { "Session secret must be exactly 256 bits" }
        removeSession(sessionId)
        secrets[sessionId] = sessionSecret.copyOf()
    }

    @Synchronized
    fun observe(
        advertisement: ConsentAdvertisement,
        epochSeconds: Long,
        elapsedRealtimeMs: Long,
    ): ObservationResult {
        val secret = secrets[advertisement.sessionId]
            ?: return ObservationResult.UnverifiedDiscovery
        val verification = tokenVerifier.verify(
            sessionSecret = secret,
            sessionId = advertisement.sessionId,
            flags = advertisement.flags,
            token = advertisement.rotatingToken,
            epochSeconds = epochSeconds,
        )
        if (!verification.isValid) {
            return ObservationResult.Rejected(RejectionReason.INVALID_TOKEN)
        }

        val prior = authenticated[advertisement.sessionId]
        if (
            prior != null &&
            prior.flags != advertisement.flags &&
            !verification.extendsSession
        ) {
            return ObservationResult.Rejected(RejectionReason.FLAGS_CHANGED_WITHOUT_FRESH_WINDOW)
        }
        val proximity = proximityTracker.recordSample(
            advertisement.sessionId,
            advertisement.rssi,
            elapsedRealtimeMs,
        ) ?: return ObservationResult.Rejected(RejectionReason.INVALID_RSSI)

        val state = if (prior == null) {
            AuthenticatedState(advertisement.flags, elapsedRealtimeMs)
                .also { authenticated[advertisement.sessionId] = it }
        } else {
            if (verification.extendsSession) {
                prior.flags = advertisement.flags
                prior.authenticatedAtMs = elapsedRealtimeMs
            }
            prior
        }
        return ObservationResult.Verified(state.toPublic(advertisement.sessionId, proximity))
    }

    @Synchronized
    fun activeSessions(elapsedRealtimeMs: Long): List<VerifiedConsentSession> {
        val proximityBySession = proximityTracker.states(elapsedRealtimeMs)
            .associateBy(ProximityState::sessionId)
        return authenticated.mapNotNull { (sessionId, state) ->
            val proximity = proximityBySession[sessionId] ?: return@mapNotNull null
            val authenticationExpired =
                elapsedRealtimeMs - state.authenticatedAtMs >= authenticationTimeoutMs
            if (authenticationExpired || proximity.band == ProximityBand.LOST) {
                null
            } else {
                state.toPublic(sessionId, proximity)
            }
        }
    }

    @Synchronized
    fun hasNearbyProtectSession(elapsedRealtimeMs: Long): Boolean =
        activeSessions(elapsedRealtimeMs).any { session ->
            session.consent == ConsentStatus.PROTECT && session.proximity.insidePrivacyZone
        }

    @Synchronized
    fun removeSession(sessionId: UInt) {
        secrets.remove(sessionId)?.fill(0)
        authenticated.remove(sessionId)
        proximityTracker.removeSession(sessionId)
        tokenVerifier.clearSession(sessionId)
    }

    @Synchronized
    override fun close() {
        secrets.values.forEach { it.fill(0) }
        secrets.clear()
        authenticated.clear()
        proximityTracker.clear()
        tokenVerifier.clearAll()
    }

    private fun AuthenticatedState.toPublic(
        sessionId: UInt,
        proximity: ProximityState,
    ) = VerifiedConsentSession(
        sessionId = sessionId,
        consent = if (ConsentFlags.isAllow(flags)) ConsentStatus.ALLOW else ConsentStatus.PROTECT,
        preciseMatchAvailable = ConsentFlags.hasPreciseMatch(flags),
        proximity = proximity,
        authenticatedAtElapsedRealtimeMs = authenticatedAtMs,
    )
}

