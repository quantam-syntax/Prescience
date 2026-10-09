package com.example.consent_cam.recognition

/** Selects distinct faces for every active PROTECT profile; uncertainty keeps broad blur. */
class ProtectedFaceSelector(
    private val faceAmbiguityMargin: Float = ExactFaceMatcher.DEFAULT_AMBIGUITY_MARGIN,
) {
    sealed interface Result {
        data object Resolving : Result
        data class Resolved(val trackingId: Int, val sessionId: UInt) : Result
    }

    init {
        require(faceAmbiguityMargin in 0f..2f)
    }

    fun select(matches: Collection<FaceMatch>, expectedSessionId: UInt): Result {
        val candidates = matches
            .asSequence()
            .filter { it.sessionId == expectedSessionId && it.similarity != null }
            .sortedByDescending { it.similarity }
            .toList()
        val best = candidates.firstOrNull() ?: return Result.Resolving
        if (best.state != FaceMatchState.MATCHED_PROTECT) return Result.Resolving
        val runnerUp = candidates.getOrNull(1)?.similarity
        if (runnerUp != null && requireNotNull(best.similarity) - runnerUp < faceAmbiguityMargin) {
            return Result.Resolving
        }
        return Result.Resolved(best.trackingId, expectedSessionId)
    }

    fun selectAll(matches: Collection<FaceMatch>, expectedSessionIds: Set<UInt>): Set<Int>? {
        if (expectedSessionIds.isEmpty()) return emptySet()
        val selected = expectedSessionIds.map { sessionId ->
            select(matches, sessionId) as? Result.Resolved ?: return null
        }
        val trackingIds = selected.mapTo(mutableSetOf(), Result.Resolved::trackingId)
        return trackingIds.takeIf { it.size == selected.size }
    }
}

