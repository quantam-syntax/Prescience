package com.example.consent_cam.recognition

import com.example.consent_cam.vision.FaceCrop
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EnrollmentUiState(
    val hasProfile: Boolean = false,
    val acceptedSamples: Int = 0,
    val requiredSamples: Int = FaceEnrollmentController.REQUIRED_SAMPLES,
    val processing: Boolean = false,
    val status: String = "No protected-face profile",
    val profileRevision: Long = 0,
    val avatarEnabled: Boolean = false,
    val avatarPreset: AvatarPreset? = null,
)

class FaceEnrollmentController(
    private val embedder: FaceEmbedder,
    private val store: OwnerProfileStore,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val samples = mutableListOf<FloatArray>()
    private val generation = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(EnrollmentUiState())
    val state: StateFlow<EnrollmentUiState> = mutableState.asStateFlow()
    @Volatile private var firstSideSign: Float? = null

    init {
        scope.launch {
            val available = store.hasProfile()
            if (!closed.get()) {
                val current = mutableState.value
                val profile = if (available) store.load().getOrNull() else null
                mutableState.value = current.copy(
                    hasProfile = available,
                    avatarEnabled = profile?.presentation == ProtectionPresentation.AVATAR,
                    avatarPreset = profile?.avatarPreset,
                    profileRevision = 1,
                    status = if (current.status == "No protected-face profile") {
                        if (available) "Protected-face profile ready" else current.status
                    } else {
                        current.status
                    },
                )
                profile?.close()
            }
        }
    }

    fun submit(crops: List<FaceCrop>) {
        if (closed.get() || mutableState.value.processing) {
            crops.forEach(FaceCrop::close)
            return
        }
        if (crops.size != 1) {
            crops.forEach(FaceCrop::close)
            mutableState.value = mutableState.value.copy(
                status = if (crops.isEmpty()) "No face found - move into the guide" else "Only one face can be enrolled",
            )
            return
        }

        val crop = crops.single()
        val acceptedCount = synchronized(samples) { samples.size }
        val pose = EnrollmentPosePolicy.evaluate(
            acceptedSamples = acceptedCount,
            quality = crop.quality,
            yawDegrees = crop.yawDegrees,
            rollDegrees = crop.rollDegrees,
            firstSideSign = firstSideSign,
        )
        if (!pose.accepted) {
            crop.close()
            mutableState.value = mutableState.value.copy(status = pose.status)
            return
        }

        val requestGeneration = generation.get()
        mutableState.value = mutableState.value.copy(processing = true, status = "Processing sample...")
        scope.launch {
            val result = try {
                embedder.embed(crop.bitmap)
            } finally {
                crop.close()
            }
            val embedding = result.getOrNull()
            if (!isCurrent(requestGeneration)) {
                embedding?.fill(0f)
                return@launch
            }
            if (embedding == null || embedding.size != FACENET_EMBEDDING_DIMENSIONS) {
                embedding?.fill(0f)
                mutableState.value = mutableState.value.copy(
                    processing = false,
                    status = result.exceptionOrNull()?.let {
                        "Face model failed: ${it.message ?: it.javaClass.simpleName}"
                    } ?: "Sample quality was not sufficient",
                )
                return@launch
            }

            firstSideSign = pose.firstSideSign
            val sampleCount = synchronized(samples) {
                samples += embedding
                samples.size
            }
            if (sampleCount < REQUIRED_SAMPLES) {
                mutableState.value = mutableState.value.copy(
                    acceptedSamples = sampleCount,
                    processing = false,
                    status = instructionFor(sampleCount),
                )
            } else {
                finishEnrollment(requestGeneration)
            }
        }
    }

    fun reset() {
        generation.incrementAndGet()
        clearSamples()
        firstSideSign = null
        mutableState.value = mutableState.value.copy(
            acceptedSamples = 0,
            processing = false,
            status = "Look straight at the camera",
        )
    }

    fun deleteProfile() {
        reset()
        val deleteGeneration = generation.get()
        scope.launch {
            store.delete()
            if (isCurrent(deleteGeneration)) {
                mutableState.value = EnrollmentUiState(
                    status = "Protected-face profile deleted",
                    profileRevision = mutableState.value.profileRevision + 1,
                )
            }
        }
    }

    fun setAvatarEnabled(enabled: Boolean) {
        if (!mutableState.value.hasProfile) return
        val requestGeneration = generation.get()
        scope.launch {
            val profile = store.load().getOrNull() ?: return@launch
            val updated = try {
                if (enabled) profile.withAvatarPreset(profile.avatarPreset ?: AvatarPreset.IRON_MAN)
                else profile.withPresentation(ProtectionPresentation.BLUR)
            } finally { profile.close() }
            val saved = try { store.save(updated).isSuccess } finally { updated.close() }
            if (saved && isCurrent(requestGeneration)) {
                mutableState.value = mutableState.value.copy(
                    avatarEnabled = enabled,
                    avatarPreset = if (enabled) updated.avatarPreset else null,
                    profileRevision = mutableState.value.profileRevision + 1,
                    status = if (enabled) "Protected-face profile ready with avatar" else "Protected-face profile ready with blur",
                )
            }
        }
    }

    fun selectAvatarPreset(preset: AvatarPreset) {
        if (!mutableState.value.hasProfile) return
        val requestGeneration = generation.get()
        scope.launch {
            val profile = store.load().getOrNull() ?: return@launch
            val updated = try { profile.withAvatarPreset(preset) } finally { profile.close() }
            val saved = try { store.save(updated).isSuccess } finally { updated.close() }
            if (saved && isCurrent(requestGeneration)) {
                mutableState.value = mutableState.value.copy(
                    avatarEnabled = true,
                    avatarPreset = preset,
                    profileRevision = mutableState.value.profileRevision + 1,
                    status = "Protected-face profile ready with ${preset.label}",
                )
            }
        }
    }

    suspend fun loadProfile(): EnrollmentProfile? = store.load().getOrNull()

    override fun close() {
        closed.set(true)
        generation.incrementAndGet()
        clearSamples()
        firstSideSign = null
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun finishEnrollment(requestGeneration: Long) {
        val enrollmentSamples = synchronized(samples) { samples.map(FloatArray::copyOf) }
        val aggregate = EmbeddingMath.aggregate(enrollmentSamples)
        val consistent = aggregate != null && enrollmentSamples.all { sample ->
            (EmbeddingMath.cosine(sample, aggregate) ?: -1f) >= MINIMUM_SAMPLE_SIMILARITY
        }
        enrollmentSamples.forEach { it.fill(0f) }
        if (!isCurrent(requestGeneration)) {
            aggregate?.fill(0f)
            return
        }
        if (!consistent) {
            aggregate?.fill(0f)
            clearSamples()
            firstSideSign = null
            mutableState.value = mutableState.value.copy(
                acceptedSamples = 0,
                processing = false,
                status = "Samples differed too much - please enrol again",
            )
            return
        }

        val profile = EnrollmentProfile(
            modelId = FACENET_MODEL_ID,
            dimensions = FACENET_EMBEDDING_DIMENSIONS,
            embedding = requireNotNull(aggregate),
            createdAtEpochMs = System.currentTimeMillis(),
            presentation = ProtectionPresentation.BLUR,
        )
        aggregate.fill(0f)
        val saved = try {
            store.save(profile)
        } finally {
            profile.close()
        }
        if (!isCurrent(requestGeneration)) return
        clearSamples()
        firstSideSign = null
        mutableState.value = EnrollmentUiState(
            hasProfile = saved.isSuccess,
            acceptedSamples = if (saved.isSuccess) REQUIRED_SAMPLES else 0,
            status = if (saved.isSuccess) "Protected-face profile ready" else "Could not securely save the profile",
            profileRevision = mutableState.value.profileRevision + 1,
        )
    }

    private fun clearSamples() = synchronized(samples) {
        samples.forEach { it.fill(0f) }
        samples.clear()
    }

    private fun isCurrent(expectedGeneration: Long): Boolean =
        !closed.get() && generation.get() == expectedGeneration

    private fun instructionFor(accepted: Int): String = when (accepted) {
        1 -> "Turn your face slightly to one side"
        2 -> "Turn your face slightly to the other side"
        3 -> "Look straight again"
        4 -> "One final straight pose"
        else -> "Look straight at the camera"
    }

    companion object {
        const val REQUIRED_SAMPLES = 5
        private const val MINIMUM_SAMPLE_SIMILARITY = 0.62f
    }
}
