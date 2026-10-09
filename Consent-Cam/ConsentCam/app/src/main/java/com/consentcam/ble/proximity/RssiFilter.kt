package com.consentcam.ble.proximity

import java.util.ArrayDeque

internal class RssiFilter(private val config: ProximityConfig) {
    private val samples = ArrayDeque<Int>(config.medianWindowSize)
    private var smoothed: Double? = null

    fun add(rawRssi: Int): Double? {
        if (rawRssi !in config.minimumRssi..config.maximumRssi) return null

        if (samples.size == config.medianWindowSize) samples.removeFirst()
        samples.addLast(rawRssi)
        val sorted = samples.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle].toDouble()
        }
        val previous = smoothed
        val next = if (previous == null) {
            median
        } else {
            config.smoothingAlpha * median + (1.0 - config.smoothingAlpha) * previous
        }
        smoothed = next
        return next
    }
}

