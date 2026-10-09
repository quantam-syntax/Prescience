package com.atreides.voiceconsent

import java.time.Instant
import java.util.UUID

const val VOICE_CONSENT_CONTRACT_VERSION = 1

data class VoiceProfileDescriptor(
    val profileId: UUID,
    val modelId: String,
    val preprocessingVersion: String,
    val embeddingDimension: Int,
    val privateReference: String,
)

data class ConsentSession(
    val sessionId: UUID,
    val profileId: UUID,
    val expiresAt: Instant,
    val protocolVersion: Int = VOICE_CONSENT_CONTRACT_VERSION,
)

enum class SpeechState { PROTECTED, UNCERTAIN, OVERLAP, UNMATCHED }

data class SpeechDecision(
    val startMs: Long,
    val endMs: Long,
    val state: SpeechState,
    val score: Float,
    val profileId: UUID,
)

data class AudioAsset(
    val privateReference: String,
    val durationMs: Long,
    val sampleRateHz: Int,
    val channels: Int,
)

data class RedactionRequest(
    val contractVersion: Int = VOICE_CONSENT_CONTRACT_VERSION,
    val audio: AudioAsset,
    val decisions: List<SpeechDecision>,
)

data class RedactionResult(
    val sanitizedReference: String,
    val appliedRanges: List<SpeechDecision>,
    val warnings: List<String>,
    val engineVersion: String,
)

interface VoiceEnrollmentService {
    suspend fun enroll(profileLabel: String): VoiceProfileDescriptor
    suspend fun delete(profileId: UUID)
}

interface ConsentSessionService {
    suspend fun start(profile: VoiceProfileDescriptor): ConsentSession
    suspend fun approveProfileTransfer(sessionId: UUID): VoiceProfileDescriptor
    suspend fun end(sessionId: UUID)
}

interface SpeakerMatchingService {
    suspend fun decide(audio: AudioAsset, profile: VoiceProfileDescriptor): List<SpeechDecision>
}

interface RedactionEngine {
    suspend fun redact(request: RedactionRequest): RedactionResult
}

sealed class SessionState(val label: String) {
    data object Idle : SessionState("Idle")
    data object EnrollmentRequired : SessionState("Enrollment required")
    data object AwaitingPeerApproval : SessionState("Awaiting nearby peer approval")
    data object Active : SessionState("Consent session active")
}
