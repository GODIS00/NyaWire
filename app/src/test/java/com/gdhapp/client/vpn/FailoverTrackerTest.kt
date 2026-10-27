package com.gdhapp.client.vpn

import org.junit.Assert.*
import org.junit.Test

class FailoverTrackerTest {
    @Test fun needsThreeConsecutiveFailures() {
        val tracker = FailoverTracker()
        assertFalse(tracker.ready("wifi", 0))
        assertTrue(tracker.ready("wifi", 15000))
        assertFalse(tracker.record(false)); assertFalse(tracker.record(false)); assertTrue(tracker.record(false))
    }
    @Test fun aSuccessfulCheckClearsPreviousFailures() {
        val tracker = FailoverTracker()
        tracker.record(false); tracker.record(false); assertFalse(tracker.record(true))
        assertFalse(tracker.record(false)); assertFalse(tracker.record(false)); assertTrue(tracker.record(false))
    }
    @Test fun offlineAndNetworkChangesClearFailuresAndWaitForGracePeriod() {
        val tracker = FailoverTracker()
        tracker.ready("wifi", 0); tracker.record(false); tracker.record(false)
        assertFalse(tracker.ready(null, 20000))
        assertFalse(tracker.ready("mobile", 21000))
        assertFalse(tracker.ready("mobile", 35000))
        assertTrue(tracker.ready("mobile", 36000))
        assertFalse(tracker.record(false)); assertFalse(tracker.record(false))
        assertFalse(tracker.ready("wifi", 40000)); assertFalse(tracker.record(false))
    }
}
