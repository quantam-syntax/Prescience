package com.example.consent_cam.privacy.rendering

import com.consentcam.privacy.api.BlurRegion
import com.consentcam.privacy.api.NormalizedRect

/** Smooths detector jitter and retains masks briefly across isolated missed detections. */
class FaceRegionSmoother(
    private val smoothingAlpha: Float = 0.78f,
    private val missedDetectionHoldNs: Long = 450_000_000L,
    private val reassociationIouThreshold: Float = 0.30f,
) {
    private data class TrackedRegion(
        var rect: NormalizedRect,
        var rotationDegrees: Float,
        var lastSeenNs: Long,
    )

    private val tracked = mutableMapOf<Int, TrackedRegion>()
    private var lastFrameTimestampNs = 0L

    init {
        require(smoothingAlpha in 0f..1f && smoothingAlpha > 0f)
        require(missedDetectionHoldNs >= 0)
        require(reassociationIouThreshold in 0f..1f)
    }

    @Synchronized
    fun update(
        regions: List<BlurRegion>,
        frameTimestampNs: Long,
        protectionActive: Boolean,
    ): List<BlurRegion> {
        require(frameTimestampNs >= 0)
        if (!protectionActive) {
            clear()
            return emptyList()
        }
        if (frameTimestampNs < lastFrameTimestampNs) tracked.clear()
        lastFrameTimestampNs = frameTimestampNs

        val enabledRegions = regions.filter(BlurRegion::enabled)
        val incomingIds = enabledRegions.mapTo(mutableSetOf(), BlurRegion::trackingId)
        val reassociatedIds = mutableSetOf<Int>()
        enabledRegions.forEach { region ->
            var existing = tracked[region.trackingId]
            if (existing == null) {
                val spatialMatch = tracked.entries
                    .asSequence()
                    .filter { (trackingId) -> trackingId !in incomingIds && trackingId !in reassociatedIds }
                    .map { entry -> entry to intersectionOverUnion(entry.value.rect, region.normalizedRect) }
                    .filter { (_, overlap) -> overlap >= reassociationIouThreshold }
                    .maxByOrNull { (_, overlap) -> overlap }
                if (spatialMatch != null) {
                    val oldId = spatialMatch.first.key
                    existing = tracked.remove(oldId)
                    reassociatedIds += oldId
                    if (existing != null) tracked[region.trackingId] = existing
                }
            }
            if (existing == null) {
                tracked[region.trackingId] = TrackedRegion(
                    region.normalizedRect,
                    region.rotationDegrees,
                    frameTimestampNs,
                )
            } else {
                existing.rect = interpolate(existing.rect, region.normalizedRect)
                existing.rotationDegrees = interpolateAngle(
                    existing.rotationDegrees,
                    region.rotationDegrees,
                )
                existing.lastSeenNs = frameTimestampNs
            }
        }
        tracked.entries.removeAll { (_, region) ->
            frameTimestampNs - region.lastSeenNs > missedDetectionHoldNs
        }
        return tracked.map { (trackingId, region) ->
            BlurRegion(
                trackingId = trackingId,
                normalizedRect = region.rect,
                timestampNs = frameTimestampNs,
                enabled = true,
                rotationDegrees = region.rotationDegrees,
            )
        }
    }

    @Synchronized
    fun clear() {
        tracked.clear()
        lastFrameTimestampNs = 0L
    }

    private fun interpolate(previous: NormalizedRect, current: NormalizedRect) = NormalizedRect(
        left = lerp(previous.left, current.left),
        top = lerp(previous.top, current.top),
        right = lerp(previous.right, current.right),
        bottom = lerp(previous.bottom, current.bottom),
    )

    private fun lerp(previous: Float, current: Float): Float =
        previous + smoothingAlpha * (current - previous)

    private fun interpolateAngle(previous: Float, current: Float): Float {
        val shortestDelta = ((current - previous + 540f) % 360f) - 180f
        return previous + smoothingAlpha * shortestDelta
    }

    private fun intersectionOverUnion(first: NormalizedRect, second: NormalizedRect): Float {
        val intersectionWidth = (minOf(first.right, second.right) - maxOf(first.left, second.left))
            .coerceAtLeast(0f)
        val intersectionHeight = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
            .coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val union = first.width * first.height + second.width * second.height - intersection
        return if (union > 0f) intersection / union else 0f
    }
}
