package com.consentcam.ble.proximity

import com.consentcam.ble.api.ProximityBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTrackerTest {
    private val deterministicConfig = ProximityConfig(
        medianWindowSize = 1,
        smoothingAlpha = 1.0,
        enterPrivacyZoneRssi = -65.0,
        exitPrivacyZoneRssi = -72.0,
        samplesToEnter = 3,
        samplesToExit = 3,
        staleTimeoutMs = 5_000,
    )

    @Test
    fun privacyZone_requiresConsecutiveSamplesAndUsesExitHysteresis() {
        val tracker = ProximityTracker(deterministicConfig)

        assertFalse(tracker.recordSample(1u, -64, 0)!!.insidePrivacyZone)
        assertFalse(tracker.recordSample(1u, -64, 100)!!.insidePrivacyZone)
        assertTrue(tracker.recordSample(1u, -64, 200)!!.insidePrivacyZone)
        assertTrue(tracker.recordSample(1u, -70, 300)!!.insidePrivacyZone)
        assertTrue(tracker.recordSample(1u, -73, 400)!!.insidePrivacyZone)
        assertTrue(tracker.recordSample(1u, -73, 500)!!.insidePrivacyZone)
        assertFalse(tracker.recordSample(1u, -73, 600)!!.insidePrivacyZone)
    }

    @Test
    fun recordSample_filtersOutliersWithoutChangingLastSeen() {
        val tracker = ProximityTracker(deterministicConfig)
        tracker.recordSample(1u, -60, 100)

        assertNull(tracker.recordSample(1u, -128, 4_000))
        val state = tracker.states(4_999).single()

        assertEquals(100L, state.lastSeenElapsedRealtimeMs)
    }

    @Test
    fun states_marksSessionLostAtStaleTimeoutAndClearsPrivacyZone() {
        val tracker = ProximityTracker(deterministicConfig)
        tracker.recordSample(1u, -60, 100)
        tracker.recordSample(1u, -60, 200)
        tracker.recordSample(1u, -60, 300)

        val state = tracker.states(5_300).single()

        assertEquals(ProximityBand.LOST, state.band)
        assertFalse(state.insidePrivacyZone)
    }

    @Test
    fun firstSampleAfterStaleGap_mustReconfirmPrivacyZoneEntry() {
        val tracker = ProximityTracker(deterministicConfig)
        tracker.recordSample(1u, -60, 100)
        tracker.recordSample(1u, -60, 200)
        tracker.recordSample(1u, -60, 300)

        val returned = tracker.recordSample(1u, -60, 5_300)

        assertFalse(returned!!.insidePrivacyZone)
    }

    @Test
    fun samplesAreMaintainedIndependentlyPerSession() {
        val tracker = ProximityTracker(deterministicConfig)
        repeat(3) { index -> tracker.recordSample(1u, -60, index.toLong()) }
        repeat(3) { index -> tracker.recordSample(2u, -90, index.toLong()) }

        val states = tracker.states(10).associateBy { it.sessionId }

        assertTrue(states.getValue(1u).insidePrivacyZone)
        assertFalse(states.getValue(2u).insidePrivacyZone)
        assertEquals(ProximityBand.NEAR, states.getValue(1u).band)
        assertEquals(ProximityBand.FAR, states.getValue(2u).band)
    }

    @Test
    fun rollingMedianRejectsSingleRssiSpike() {
        val tracker = ProximityTracker(
            deterministicConfig.copy(medianWindowSize = 3),
        )
        tracker.recordSample(1u, -60, 0)
        tracker.recordSample(1u, -100, 1)

        val state = tracker.recordSample(1u, -61, 2)!!

        assertEquals(-61.0, state.smoothedRssi, 0.0)
    }
}
