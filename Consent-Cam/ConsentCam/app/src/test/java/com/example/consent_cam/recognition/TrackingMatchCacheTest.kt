package com.example.consent_cam.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingMatchCacheTest {
    @Test
    fun poorFirstFrameRetriesAfterCooldownWithoutLeavingFrame() {
        var now = 0L
        val cache = TrackingMatchCache { now }
        cache.put(match(7).copy(state = FaceMatchState.INSUFFICIENT_QUALITY))
        assertTrue(cache.missing(setOf(7)).isEmpty())
        now = 750L
        assertEquals(setOf(7), cache.missing(setOf(7)))
    }

    @Test
    fun unknownFirstFrameRetriesAfterCooldownWithoutLeavingFrame() {
        var now = 0L
        val cache = TrackingMatchCache { now }
        cache.put(match(7))

        assertTrue(cache.missing(setOf(7)).isEmpty())
        now = 750L
        assertEquals(setOf(7), cache.missing(setOf(7)))
    }

    @Test
    fun cachedTrackingIdIsNotRequestedAgainWhileVisible() {
        val cache = TrackingMatchCache()
        cache.put(match(7))

        assertTrue(cache.missing(setOf(7)).isEmpty())
        assertEquals(setOf(8), cache.missing(setOf(7, 8)))
    }

    @Test
    fun trackingIdIsForgottenAfterItLeavesFrame() {
        val cache = TrackingMatchCache()
        cache.put(match(7))

        cache.retainVisible(emptySet())

        assertEquals(setOf(7), cache.missing(setOf(7)))
    }

    private fun match(trackingId: Int) = FaceMatch(
        trackingId = trackingId,
        sessionId = null,
        similarity = null,
        secondBestSimilarity = null,
        state = FaceMatchState.UNKNOWN,
    )
}
