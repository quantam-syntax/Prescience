package com.example.consent_cam.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedFaceSelectorTest {
    private val selector = ProtectedFaceSelector(faceAmbiguityMargin = 0.08f)

    @Test
    fun selectsOnlyStrongSeparatedProtectFace() {
        val result = selector.select(
            matches = listOf(match(7, 0.91f), match(9, 0.72f, FaceMatchState.UNKNOWN)),
            expectedSessionId = 42u,
        )

        assertEquals(7, (result as ProtectedFaceSelector.Result.Resolved).trackingId)
    }

    @Test
    fun closeScoresRemainResolving() {
        val result = selector.select(
            matches = listOf(match(7, 0.91f), match(9, 0.86f)),
            expectedSessionId = 42u,
        )

        assertTrue(result is ProtectedFaceSelector.Result.Resolving)
    }

    @Test
    fun anotherSessionCannotResolveThisProfile() {
        val result = selector.select(listOf(match(7, 0.95f, sessionId = 99u)), 42u)

        assertTrue(result is ProtectedFaceSelector.Result.Resolving)
    }

    @Test
    fun selectsDistinctFacesForEveryProtectSession() {
        val result = selector.selectAll(
            matches = listOf(
                match(7, 0.94f, sessionId = 42u),
                match(9, 0.92f, sessionId = 99u),
            ),
            expectedSessionIds = setOf(42u, 99u),
        )

        assertEquals(setOf(7, 9), result)
    }

    @Test
    fun unresolvedProtectSessionKeepsAllAssociationsResolving() {
        val result = selector.selectAll(
            matches = listOf(match(7, 0.94f, sessionId = 42u)),
            expectedSessionIds = setOf(42u, 99u),
        )

        assertEquals(null, result)
    }

    private fun match(
        trackingId: Int,
        similarity: Float,
        state: FaceMatchState = FaceMatchState.MATCHED_PROTECT,
        sessionId: UInt = 42u,
    ) = FaceMatch(trackingId, sessionId, similarity, null, state)
}

