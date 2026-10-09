package com.example.consent_cam.recognition

class ExactFaceMatcher(
    private val embedder: FaceEmbedder,
    private val acceptanceThreshold: Float = DEFAULT_ACCEPTANCE_THRESHOLD,
    private val profileAmbiguityMargin: Float = DEFAULT_AMBIGUITY_MARGIN,
) : FaceMatcher {
    init {
        require(acceptanceThreshold in -1f..1f)
        require(profileAmbiguityMargin in 0f..2f)
    }

    override suspend fun match(
        trackingId: Int,
        alignedFace: android.graphics.Bitmap,
        activeProfiles: List<SessionProfile>,
    ): FaceMatch {
        if (activeProfiles.isEmpty()) return unresolved(trackingId, FaceMatchState.UNKNOWN)
        val embedding = embedder.embed(alignedFace).getOrNull()
            ?: return unresolved(trackingId, FaceMatchState.INSUFFICIENT_QUALITY)
        val normalized = EmbeddingMath.normalize(embedding)
        embedding.fill(0f)
        if (normalized == null || normalized.size != FACENET_EMBEDDING_DIMENSIONS) {
            return unresolved(trackingId, FaceMatchState.INSUFFICIENT_QUALITY)
        }

        return try {
            val scored = activeProfiles.mapNotNull { profile ->
                if (profile.modelId != FACENET_MODEL_ID) return@mapNotNull null
                val profileEmbedding = profile.embeddingCopy()
                try {
                    EmbeddingMath.cosine(normalized, profileEmbedding)?.let { profile to it }
                } finally {
                    profileEmbedding.fill(0f)
                }
            }
            decide(trackingId, scored, acceptanceThreshold, profileAmbiguityMargin)
        } finally {
            normalized.fill(0f)
        }
    }

    private fun unresolved(trackingId: Int, state: FaceMatchState) = FaceMatch(
        trackingId = trackingId,
        sessionId = null,
        similarity = null,
        secondBestSimilarity = null,
        state = state,
    )

    companion object {
        // Demo policy: require a strong cosine match before selective masking.
        // Scores below 0.64 remain uncertain and, in Enhanced mode, stay visible.
        // Recalibrate with representative participant samples before production use.
        const val DEFAULT_ACCEPTANCE_THRESHOLD = 0.64f
        const val DEFAULT_AMBIGUITY_MARGIN = 0.08f

        internal fun decide(
            trackingId: Int,
            scores: List<Pair<SessionProfile, Float>>,
            acceptanceThreshold: Float = DEFAULT_ACCEPTANCE_THRESHOLD,
            ambiguityMargin: Float = DEFAULT_AMBIGUITY_MARGIN,
        ): FaceMatch {
            val sorted = scores.sortedByDescending { it.second }
            val best = sorted.firstOrNull()
                ?: return FaceMatch(trackingId, null, null, null, FaceMatchState.UNKNOWN)
            val second = sorted.getOrNull(1)?.second
            val state = when {
                best.second < acceptanceThreshold -> FaceMatchState.UNKNOWN
                // If competing profiles have the same action, the consent outcome is
                // unambiguous: protect wins for multiple protected phones, while
                // multiple ALLOW profiles remain visible. Only a close PROTECT vs
                // ALLOW competition is unsafe to resolve automatically.
                second != null &&
                    best.second - second < ambiguityMargin &&
                    best.first.consent != sorted[1].first.consent -> FaceMatchState.AMBIGUOUS
                best.first.consent == com.example.consent_cam.ble.AppearanceConsent.PROTECT -> {
                    FaceMatchState.MATCHED_PROTECT
                }
                else -> FaceMatchState.MATCHED_ALLOW
            }
            return FaceMatch(trackingId, best.first.sessionId, best.second, second, state)
        }
    }
}
