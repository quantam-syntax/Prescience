package com.example.consent_cam.recognition

/** Session-only recognition decisions keyed by ML Kit's temporary tracking IDs. */
class TrackingMatchCache(private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val matches = mutableMapOf<Int, FaceMatch>()
    private val retryAfter = mutableMapOf<Int, Long>()

    @Synchronized
    fun retainVisible(visibleTrackingIds: Set<Int>) {
        matches.keys.retainAll(visibleTrackingIds)
        retryAfter.keys.retainAll(visibleTrackingIds)
    }

    @Synchronized
    fun missing(visibleTrackingIds: Set<Int>): Set<Int> {
        val now = elapsedMs()
        return visibleTrackingIds.filterTo(mutableSetOf()) { id ->
            id !in matches || retryAfter[id]?.let { now >= it } == true
        }
    }

    @Synchronized
    fun put(match: FaceMatch) {
        matches[match.trackingId] = match
        // A first clear-looking frame can still be poorly aligned or briefly
        // occluded. Keep UNKNOWN fail-closed, but retry it while the track is
        // visible so a stable later frame can resolve the same person.
        if (
            match.state == FaceMatchState.INSUFFICIENT_QUALITY ||
            match.state == FaceMatchState.AMBIGUOUS ||
            match.state == FaceMatchState.UNKNOWN
        ) {
            retryAfter[match.trackingId] = elapsedMs() + 750L
        } else {
            retryAfter.remove(match.trackingId)
        }
    }

    @Synchronized
    fun valuesFor(visibleTrackingIds: Set<Int>): List<FaceMatch> =
        visibleTrackingIds.mapNotNull(matches::get)

    @Synchronized
    fun clear() {
        matches.clear()
        retryAfter.clear()
    }
}
