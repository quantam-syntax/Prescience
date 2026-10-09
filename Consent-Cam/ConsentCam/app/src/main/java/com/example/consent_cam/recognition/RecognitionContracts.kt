package com.example.consent_cam.recognition

import android.graphics.Bitmap
import com.example.consent_cam.ble.AppearanceConsent

const val FACENET_MODEL_ID = "facenet-512-v1"
const val FACENET_EMBEDDING_DIMENSIONS = 512

/** The protected participant selects this; a recorder never upgrades blur to an avatar. */
enum class ProtectionPresentation { BLUR, AVATAR }

/** Small non-biometric drawing recipe for the local illustrated avatar. */
data class AvatarStyle(
    val skinColor: Int,
    val hairColor: Int,
    val shirtColor: Int,
)

/** Original, offline character archetypes. These are not licensed character likenesses. */
enum class AvatarPreset(val label: String, val assetFile: String, val style: AvatarStyle) {
    IRON_MAN("Iron Man", "iron_man.png", AvatarStyle(0xFFF2BF9B.toInt(), 0xFF2E313D.toInt(), 0xFFC5282F.toInt())),
    BATMAN("Batman", "batman.png", AvatarStyle(0xFFF1C6A4.toInt(), 0xFF17171C.toInt(), 0xFF23262C.toInt())),
    THOR("Thor", "thor.png", AvatarStyle(0xFFD89568.toInt(), 0xFFD8B26A.toInt(), 0xFF3A3B45.toInt())),
    CAPTAIN_AMERICA("Captain America", "captain_america.png", AvatarStyle(0xFFF0C9A7.toInt(), 0xFF6A472F.toInt(), 0xFF2455B7.toInt())),
    WONDER_WOMAN("Wonder Woman", "wonder_woman.png", AvatarStyle(0xFFE6B28F.toInt(), 0xFF2A1A1C.toInt(), 0xFFB88A27.toInt())),
    FLASH("Flash", "flash.png", AvatarStyle(0xFFB96F4C.toInt(), 0xFF7F1D1D.toInt(), 0xFFC5282F.toInt())),
    SCARLET_WITCH("Scarlet Witch", "scarlet_witch.png", AvatarStyle(0xFFE2AD95.toInt(), 0xFF6F2632.toInt(), 0xFF8D1635.toInt())),
    SPIDER_MAN("Spider-Man", "spider_man.png", AvatarStyle(0xFFB96F4C.toInt(), 0xFF102345.toInt(), 0xFFB71C31.toInt())),
    DOCTOR_DOOM("Doctor Doom", "doctor_doom.png", AvatarStyle(0xFFCF8C63.toInt(), 0xFF5F673A.toInt(), 0xFF365C3A.toInt())),
}

object AvatarStyleGenerator {
    fun fromEmbedding(embedding: FloatArray): AvatarStyle {
        var hash = 0x6D2B79F5.toInt()
        embedding.take(32).forEach { value ->
            hash = (hash xor value.toBits()) * 0x45D9F3B
        }
        fun choose(colors: IntArray, shift: Int): Int = colors[((hash ushr shift) and 0x7fffffff) % colors.size]
        return AvatarStyle(
            skinColor = choose(intArrayOf(0xFFF4C7A1.toInt(), 0xFFE0A078.toInt(), 0xFFB96F4C.toInt(), 0xFF80452F.toInt()), 0),
            hairColor = choose(intArrayOf(0xFF1E1714.toInt(), 0xFF4A2C20.toInt(), 0xFF704C31.toInt(), 0xFF25283B.toInt()), 7),
            shirtColor = choose(intArrayOf(0xFF355CDE.toInt(), 0xFF8D48C7.toInt(), 0xFF168F80.toInt(), 0xFFE18A29.toInt()), 14),
        )
    }
}

class EnrollmentProfile(
    val modelId: String,
    val dimensions: Int,
    embedding: FloatArray,
    val createdAtEpochMs: Long,
    val presentation: ProtectionPresentation = ProtectionPresentation.BLUR,
    val avatarStyle: AvatarStyle? = null,
    val avatarPreset: AvatarPreset? = null,
    /** Encrypted transport metadata. It is never included in BLE advertisements. */
    val deviceIdentity: String = "",
    val displayName: String = "",
    val trustedCameraIdentities: Set<String> = emptySet(),
) : AutoCloseable {
    private val mutableEmbedding = embedding.copyOf()

    init {
        require(modelId.isNotBlank())
        require(dimensions == mutableEmbedding.size)
        require(createdAtEpochMs >= 0)
        require(EmbeddingMath.isUnitVector(mutableEmbedding))
    }

    fun embeddingCopy(): FloatArray = mutableEmbedding.copyOf()

    fun withPresentation(presentation: ProtectionPresentation): EnrollmentProfile = EnrollmentProfile(
        modelId = modelId,
        dimensions = dimensions,
        embedding = mutableEmbedding,
        createdAtEpochMs = createdAtEpochMs,
        presentation = presentation,
        avatarStyle = if (presentation == ProtectionPresentation.AVATAR) {
            avatarStyle ?: AvatarStyleGenerator.fromEmbedding(mutableEmbedding)
        } else {
            null
        },
        avatarPreset = if (presentation == ProtectionPresentation.AVATAR) avatarPreset else null,
        deviceIdentity = deviceIdentity,
        displayName = displayName,
        trustedCameraIdentities = trustedCameraIdentities,
    )

    fun withAvatarPreset(preset: AvatarPreset): EnrollmentProfile = EnrollmentProfile(
        modelId = modelId,
        dimensions = dimensions,
        embedding = mutableEmbedding,
        createdAtEpochMs = createdAtEpochMs,
        presentation = ProtectionPresentation.AVATAR,
        avatarStyle = preset.style,
        avatarPreset = preset,
        deviceIdentity = deviceIdentity,
        displayName = displayName,
        trustedCameraIdentities = trustedCameraIdentities,
    )

    fun withTrustMetadata(
        identity: String,
        name: String,
        trustedCameras: Set<String>,
    ): EnrollmentProfile = EnrollmentProfile(
        modelId = modelId,
        dimensions = dimensions,
        embedding = mutableEmbedding,
        createdAtEpochMs = createdAtEpochMs,
        presentation = presentation,
        avatarStyle = avatarStyle,
        avatarPreset = avatarPreset,
        deviceIdentity = identity,
        displayName = name,
        trustedCameraIdentities = trustedCameras,
    )

    override fun close() {
        mutableEmbedding.fill(0f)
    }
}

class SessionProfile(
    val sessionId: UInt,
    val consent: AppearanceConsent,
    val modelId: String,
    embedding: FloatArray,
    val presentation: ProtectionPresentation = ProtectionPresentation.BLUR,
    val avatarStyle: AvatarStyle? = null,
    val avatarPreset: AvatarPreset? = null,
    val deviceIdentity: String = "",
    val displayName: String = "",
    val trustedCameraIdentities: Set<String> = emptySet(),
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
        presentation = presentation,
        avatarStyle = avatarStyle,
        avatarPreset = avatarPreset,
        deviceIdentity = deviceIdentity,
        displayName = displayName,
        trustedCameraIdentities = trustedCameraIdentities,
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
