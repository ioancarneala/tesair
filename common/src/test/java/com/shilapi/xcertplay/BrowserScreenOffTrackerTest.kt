package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class BrowserScreenOffTrackerTest {
    @Test fun requiresThirtyContinuousSecondsAndFreshFrames() {
        val tracker = BrowserScreenOffTracker()
        for (time in 0L until 30_000L step 2_000L) {
            assertFalse(tracker.sample(time, true, true, true, true, time))
        }
        assertEquals(2, tracker.remainingSeconds(28_000L))
        assertTrue(tracker.sample(30_000L, true, true, true, true, 30_000L))
        assertEquals(0, tracker.remainingSeconds(30_000L))
    }

    @Test fun everyRequiredRuntimeConditionResetsProgress() {
        listOf(
            booleanArrayOf(false, true, true, true),
            booleanArrayOf(true, false, true, true),
            booleanArrayOf(true, true, false, true),
            booleanArrayOf(true, true, true, false),
        ).forEach { condition ->
            val tracker = progressed()
            assertFalse(tracker.sample(22_000L, condition[0], condition[1], condition[2], condition[3], 22_000L))
            assertEquals(30, tracker.remainingSeconds(22_000L))
            assertFalse(tracker.sample(30_000L, true, true, true, true, 30_000L))
        }
    }

    @Test fun historicalMissingAndFutureAcknowledgementsCannotCompleteTheCheck() {
        listOf(-1L, 0L, 16_999L, 22_001L).forEach { timestamp ->
            val tracker = progressed()
            assertFalse(tracker.sample(22_000L, true, true, true, true, timestamp))
            assertEquals(30, tracker.remainingSeconds(22_000L))
        }
    }

    @Test fun suspendedWorkerCannotCountUnobservedTime() {
        val tracker = progressed()
        assertEquals(30, tracker.remainingSeconds(26_000L))
        assertFalse(tracker.sample(60_000L, true, true, true, true, 60_000L))
        assertEquals(30, tracker.remainingSeconds(60_000L))
        assertFalse(tracker.sample(62_000L, true, true, true, true, 62_000L))
        assertEquals(28, tracker.remainingSeconds(62_000L))
    }

    @Test fun elapsedClockGoingBackwardsRestartsObservation() {
        val tracker = progressed()
        assertFalse(tracker.sample(1_000L, true, true, true, true, 1_000L))
        assertEquals(30, tracker.remainingSeconds(1_000L))
    }

    @Test fun closingTheMonitorDiscardsIncompleteObservation() {
        val tracker = progressed()
        tracker.reset()
        assertEquals(30, tracker.remainingSeconds(22_000L))
        assertFalse(tracker.sample(30_000L, true, true, true, true, 30_000L))
    }

    private fun progressed() = BrowserScreenOffTracker().also { tracker ->
        for (time in 0L..20_000L step 2_000L) tracker.sample(time, true, true, true, true, time)
    }
}
