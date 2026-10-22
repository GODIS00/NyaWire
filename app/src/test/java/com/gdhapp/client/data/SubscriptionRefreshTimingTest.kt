package com.gdhapp.client.data

import com.gdhapp.client.background.SubscriptionUpdater
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.SubscriptionGroup
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRefreshTimingTest {
    private val group = SubscriptionGroup(id = "test", title = "Test", lastUpdated = "", servers = emptyList(),
        sourceUrl = "https://example.com/sub", updatedAtEpochMs = 10_000L, autoUpdateHours = 2)

    @Test fun returningBeforeTheSubscriptionIntervalDoesNotRefresh() {
        val settings = ClientSettings(subscriptionUpdateHours = 0)
        assertFalse(SubscriptionUpdater.refreshNeeded(group, settings, 10_000L + 7_199_999L))
        assertTrue(SubscriptionUpdater.refreshNeeded(group, settings, 10_000L + 7_200_000L))
    }

    @Test fun explicitIntervalOverridesSubscriptionInterval() {
        val settings = ClientSettings(subscriptionUpdateHours = 1, autoUpdateSubscriptions = false)
        assertTrue(SubscriptionUpdater.refreshNeeded(group, settings, 10_000L + 3_600_000L))
        assertFalse(SubscriptionUpdater.due(group, settings, 10_000L + 3_600_000L))
    }

    @Test fun manualServersAndFutureTimestampsAreNotRefreshed() {
        assertFalse(SubscriptionUpdater.refreshNeeded(group.copy(sourceUrl = null), ClientSettings(), Long.MAX_VALUE))
        assertFalse(SubscriptionUpdater.refreshNeeded(group, ClientSettings(), 0L))
    }
}
