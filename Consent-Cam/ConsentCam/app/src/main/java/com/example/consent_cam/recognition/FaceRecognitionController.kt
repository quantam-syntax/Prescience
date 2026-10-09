package com.example.consent_cam.recognition

import com.consentcam.privacy.api.FaceObservation
import com.consentcam.privacy.api.FaceConsentResolution
import com.consentcam.privacy.api.PreciseAssociationState
import com.consentcam.privacy.api.PreciseAssociationStatus
import com.example.consent_cam.vision.FaceCrop
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecognitionUiState(
    val association: PreciseAssociationState = PreciseAssociationState(),
    val faceResolutions: Map<Int, FaceConsentResolution> = emptyMap(),
    /** Session ownership is exposed only for rendering the already-confirmed protection choice. */
    val protectedSessionByTrackingId: Map<Int, UInt> = emptyMap(),
    val status: String = "Precise matching unavailable",
)

/** Runs FaceNet once per ML Kit tracking ID and keeps uncertainty fail-closed. */
class FaceRecognitionController(
    private val matcher: FaceMatcher,
    private val selector: ProtectedFaceSelector = ProtectedFaceSelector(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cropRequestLock = Any()
    private val matchCache = TrackingMatchCache()
    private val mutableState = MutableStateFlow(RecognitionUiState())
    val state: StateFlow<RecognitionUiState> = mutableState.asStateFlow()

    @Volatile private var profiles: List<SessionProfile> = emptyList()
    @Volatile private var generation = 0L
    @Volatile private var visibleTrackingIds: Set<Int> = emptySet()
    private var nextCropRequestToken = 0L
    private var activeCropRequest: CropRequest? = null

    fun setProfiles(newProfiles: List<SessionProfile>) {
        val old = profiles
        profiles = newProfiles
        generation += 1
        visibleTrackingIds = emptySet()
        matchCache.clear()
        old.forEach(SessionProfile::close)
        mutableState.value = if (newProfiles.isEmpty()) {
            RecognitionUiState()
        } else {
            resolving("Matching - temporary broad protection")
        }
    }

    fun updateFaces(
        faces: List<FaceObservation>,
        protectionActive: Boolean,
        requestCrops: ((List<FaceCrop>) -> Unit) -> Boolean,
    ) {
        val activeProfiles = profiles
        if (!protectionActive || activeProfiles.isEmpty()) {
            visibleTrackingIds = emptySet()
            matchCache.clear()
            if (mutableState.value.association.status != PreciseAssociationStatus.UNAVAILABLE) {
                mutableState.value = RecognitionUiState()
            }
            return
        }

        val visible = faces.mapTo(mutableSetOf()) { it.trackingId }
        visibleTrackingIds = visible
        matchCache.retainVisible(visible)

        val resolvedIds = mutableState.value.association.protectedTrackingIds
        if (resolvedIds.any { it !in visible }) {
            mutableState.value = resolving("Match lost - reacquiring with broad protection")
        } else if (mutableState.value.association.status == PreciseAssociationStatus.UNAVAILABLE) {
            mutableState.value = resolving("Matching - temporary broad protection")
        }

        val missingIds = matchCache.missing(visible)
        if (missingIds.isEmpty()) {
            selectFromCache(activeProfiles, visible)
            return
        }
        val requestToken = acquireCropRequest() ?: return

        val requestGeneration = generation
        val accepted = requestCrops { crops ->
            consumeCrops(crops, requestGeneration, missingIds, requestToken)
        }
        if (!accepted) finishCropRequest(requestToken)
    }

    private fun consumeCrops(
        crops: List<FaceCrop>,
        requestGeneration: Long,
        requestedIds: Set<Int>,
        requestToken: Long,
    ) {
        if (!isActiveCropRequest(requestToken) || requestGeneration != generation) {
            crops.forEach(FaceCrop::close)
            finishCropRequest(requestToken)
            return
        }
        val requestProfiles = profiles.map(SessionProfile::copyForConsumer)
        if (requestProfiles.isEmpty()) {
            crops.forEach(FaceCrop::close)
            finishCropRequest(requestToken)
            return
        }

        scope.launch {
            try {
                val returnedIds = mutableSetOf<Int>()
                crops.forEach { crop ->
                    try {
                        if (crop.trackingId !in requestedIds) return@forEach
                        returnedIds += crop.trackingId
                        val match = if (!isUsable(crop)) {
                            unresolved(crop.trackingId, FaceMatchState.INSUFFICIENT_QUALITY)
                        } else {
                            matcher.match(crop.trackingId, crop.bitmap, requestProfiles)
                        }
                        if (isActiveCropRequest(requestToken) && requestGeneration == generation) {
                            matchCache.put(match)
                        }
                    } finally {
                        crop.close()
                    }
                }
                if (!isActiveCropRequest(requestToken) || requestGeneration != generation) return@launch
                (requestedIds - returnedIds).forEach { trackingId ->
                    matchCache.put(unresolved(trackingId, FaceMatchState.INSUFFICIENT_QUALITY))
                }
                selectFromCache(requestProfiles, visibleTrackingIds)
            } finally {
                requestProfiles.forEach(SessionProfile::close)
                finishCropRequest(requestToken)
            }
        }
    }

    /** Replaces a crop request that never called back and ignores any late result from it. */
    private fun acquireCropRequest(): Long? = synchronized(cropRequestLock) {
        val now = System.nanoTime() / 1_000_000L
        val active = activeCropRequest
        if (active != null && now - active.startedAtMs < CROP_REQUEST_TIMEOUT_MS) return null
        val token = ++nextCropRequestToken
        activeCropRequest = CropRequest(token, now)
        token
    }

    private fun isActiveCropRequest(token: Long): Boolean = synchronized(cropRequestLock) {
        activeCropRequest?.token == token
    }

    private fun finishCropRequest(token: Long) {
        synchronized(cropRequestLock) {
            if (activeCropRequest?.token == token) activeCropRequest = null
        }
    }

    private fun selectFromCache(activeProfiles: List<SessionProfile>, visibleIds: Set<Int>) {
        val visibleMatches = matchCache.valuesFor(visibleIds)
        val resolutions = visibleMatches.associate { match ->
            match.trackingId to match.state.toResolution()
        }
        val bestScore = visibleMatches.mapNotNull(FaceMatch::similarity).maxOrNull()
        val scoreSuffix = bestScore?.let { " - score ${String.format(Locale.US, "%.3f", it)}" }.orEmpty()
        val protectSessionIds = activeProfiles
            .filter { it.consent == com.example.consent_cam.ble.AppearanceConsent.PROTECT }
            .mapTo(mutableSetOf(), SessionProfile::sessionId)
        val protectedIds = selector.selectAll(visibleMatches, protectSessionIds)
        mutableState.value = if (protectedIds != null && protectedIds.isNotEmpty()) {
            val selected = protectedIds.toSet()
            val protectedSessions = visibleMatches.mapNotNull { match ->
                match.sessionId?.takeIf { match.trackingId in selected }?.let { match.trackingId to it }
            }.toMap()
            RecognitionUiState(
                association = PreciseAssociationState.resolved(protectedIds),
                faceResolutions = resolutions,
                protectedSessionByTrackingId = protectedSessions,
                status = "${protectedIds.size} protected ${if (protectedIds.size == 1) "person" else "people"} matched$scoreSuffix",
            )
        } else {
            resolving(
                status = if (visibleIds.isEmpty()) "Waiting for a face - no face is blurred"
                else "Match uncertain$scoreSuffix - no face is blurred",
                resolutions = resolutions,
            )
        }
    }

    override fun close() {
        generation += 1
        visibleTrackingIds = emptySet()
        matchCache.clear()
        profiles.forEach(SessionProfile::close)
        profiles = emptyList()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun isUsable(crop: FaceCrop): Boolean =
        crop.quality >= MINIMUM_CROP_QUALITY &&
            kotlin.math.abs(crop.yawDegrees) <= MAX_YAW_DEGREES &&
            kotlin.math.abs(crop.rollDegrees) <= MAX_ROLL_DEGREES

    private fun unresolved(trackingId: Int, state: FaceMatchState) = FaceMatch(
        trackingId = trackingId,
        sessionId = null,
        similarity = null,
        secondBestSimilarity = null,
        state = state,
    )

    private fun resolving(
        status: String,
        resolutions: Map<Int, FaceConsentResolution> = emptyMap(),
    ) = RecognitionUiState(
        association = PreciseAssociationState.resolving(),
        faceResolutions = resolutions,
        status = status,
    )

    private fun FaceMatchState.toResolution(): FaceConsentResolution = when (this) {
        FaceMatchState.MATCHED_ALLOW -> FaceConsentResolution.MATCHED_ALLOW
        FaceMatchState.MATCHED_PROTECT -> FaceConsentResolution.MATCHED_PROTECT
        FaceMatchState.UNKNOWN -> FaceConsentResolution.UNKNOWN
        FaceMatchState.AMBIGUOUS -> FaceConsentResolution.AMBIGUOUS
        FaceMatchState.INSUFFICIENT_QUALITY -> FaceConsentResolution.INSUFFICIENT_QUALITY
    }

    private companion object {
        data class CropRequest(val token: Long, val startedAtMs: Long)
        const val CROP_REQUEST_TIMEOUT_MS = 1_500L
        const val MINIMUM_CROP_QUALITY = 0.45f
        const val MAX_YAW_DEGREES = 45f
        const val MAX_ROLL_DEGREES = 25f
    }
}
