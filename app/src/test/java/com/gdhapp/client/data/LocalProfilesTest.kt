package com.gdhapp.client.data

import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.domain.model.SubscriptionGroup
import org.junit.Assert.*
import org.junit.Test

class LocalProfilesTest {
    private fun group(id: String, url: String? = null, selected: Boolean = false) = SubscriptionGroup(
        id = id, title = id, lastUpdated = id, sourceUrl = url,
        servers = listOf(ServerNode(id = "$id:node", name = id, protocolSummary = "VLESS", rawConfigJson = "config:$id", isSelected = selected)),
    )

    @Test fun `existing local lists merge without altering remote subscriptions or node IDs`() {
        val first = group("one")
        val remote = group("remote", "https://example.com/sub")
        val second = group("two", selected = true)
        val result = LocalProfiles.combine(listOf(first, remote, second))
        assertEquals(listOf("one", "remote"), result.map { it.id })
        assertEquals(listOf("one:node", "two:node"), result.first().servers.map { it.id })
        assertTrue(result.first().servers.last().isSelected)
        assertSame(remote, result.last())
        assertEquals(result, LocalProfiles.combine(result))
    }

    @Test fun `new manual keys append to the local list and keep selection`() {
        val first = group("one", selected = true).copy(isExpanded = false)
        val result = LocalProfiles.append(listOf(first), group("two"))
        assertEquals(1, result.size)
        assertEquals(2, result.single().servers.size)
        assertTrue(result.single().isExpanded)
        assertEquals("one:node", result.single().servers.single { it.isSelected }.id)
    }

    @Test fun `repeated import does not duplicate a local server`() {
        val first = group("one", selected = true)
        val repeat = group("repeat").copy(servers = first.servers.map { it.copy(id = "repeat:node") })
        assertEquals(listOf(first), LocalProfiles.append(listOf(first), repeat))
    }

    @Test fun `a new local list does not steal selection from a subscription`() {
        val remote = group("remote", "https://example.com/sub", true)
        val result = LocalProfiles.append(listOf(remote), group("one"))
        assertEquals(2, result.size)
        assertFalse(result.last().servers.single().isSelected)
        assertTrue(result.first().servers.single().isSelected)
    }

    @Test fun `first import selects only the first unique server`() {
        val first = group("one")
        val second = group("two")
        val incoming = first.copy(servers = first.servers + first.servers + second.servers)
        val result = LocalProfiles.append(emptyList(), incoming).single()
        assertEquals(2, result.servers.size)
        assertEquals(listOf(true, false), result.servers.map { it.isSelected })
    }
}
