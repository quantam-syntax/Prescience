package com.example.consent_cam.recognition

import android.graphics.Bitmap
import com.example.consent_cam.ble.AppearanceConsent

const val FACENET_MODEL_ID = "facenet-512-v1"
const val FACENET_EMBEDDING_DIMENSIONS = 512

class EnrollmentProfile(
    val modelId: String,
    val dimensions: Int,
    embedding: FloatArray,
    val createdAtEpochMs: Long,
) : AutoCloseable {
    private val mutableEmbedding = embedding.copyOf()

    init {
        require(modelId.isNotBlank())
        require(dimensions == mutableEmbedding.size)
        require(createdAtEpochMs >= 0)
        require(EmbeddingMath.isUnitVector(mutableEmbedding))
    }

    fun embeddingCopy(): FloatArray = mutableEmbedding.copyOf()

    override fun close() {
        mutableEmbedding.fill(0f)
    }
}

class SessionProfile(
    val sessionId: UInt,
    val consent: AppearanceConsent,
    val modelId: String,
    embedding: FloatArray,
) : AutoCloseable {
    private val mutableEmbedding = embedding.copyOf()

    init {
        require(modelId.isNotBlank())
        require(mutableEmbedding.size == FACENET_EMBEDDING_DIMENSIONS)
        require(EmbeddingMath.isUnitVector(mutableEmbedding))
    }

    fun embeddingCopy(): FloatArray = mutableEmbedding.copyOf()

    fun copyForConsumer(): SessionProfile = SessionProfile(
        sessionId = sessionId,
        consent = consent,
        modelId = modelId,
        embedding = mutableEmbedding,
    )

    override fun close() {
        mutableEmbedding.fill(0f)
    }
}

interface FaceEmbedder : AutoCloseable {
    suspend fun embed(alignedFace: Bitmap): Result<FloatArray>
}

enum class FaceMatchState {
    MATCHED_ALLOW,
    MATCHED_PROTECT,
    UNKNOWN,
    AMBIGUOUS,
    INSUFFICIENT_QUALITY,
}

data class FaceMatch(
    val trackingId: Int,
    val sessionId: UInt?,
    val similarity: Float?,
    val secondBestSimilarity: Float?,
    val state: FaceMatchState,
)

interface FaceMatcher {
    suspend fun match(
        trackingId: Int,
        alignedFace: Bitmap,
        activeProfiles: List<SessionProfile>,
    ): FaceMatch
}
