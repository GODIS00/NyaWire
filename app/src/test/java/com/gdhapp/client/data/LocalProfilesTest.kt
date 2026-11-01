package com.gdhapp.client.data

import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.domain.model.SubscriptionGroup
import org.junit.Assert.*
import org.junit.Test

class LocalProfilesTest {
    @Test fun `refresh keeps metadata selection and first duplicate favorite match`() {
        val one = ServerNode("saved:a", "same", protocolSummary = "VLESS", rawConfigJson = "old", isFavorite = true)
        val old = SubscriptionGroup("saved", "my title", lastUpdated = "old", sourceUrl = "https://example.com/sub",
            isPinned = true, isExpanded = false, servers = listOf(one, one.copy(id = "saved:b", isFavorite = false, isSelected = true)))
        val fresh = old.copy(id = "download", title = "provider title", servers = listOf(
            one.copy(id = "a", rawConfigJson = "new"), one.copy(id = "b", rawConfigJson = "new")))
        val result = LocalProfiles.mergeRemote(old, fresh)
        assertEquals("saved", result.id)
        assertEquals("my title", result.title)
        assertTrue(result.isPinned)
        assertFalse(result.isExpanded)
        assertEquals("saved:b", result.servers.single { it.isSelected }.id)
        assertTrue(result.servers.all { it.isFavorite })
        assertTrue(result.servers.all { it.rawConfigJson == "new" })
    }

    @Test fun `refresh does not select a server when another group owns selection`() {
        val old = group("saved", "https://example.com/sub")
        val result = LocalProfiles.mergeRemote(old, group("fresh"))
        assertTrue(result.servers.none { it.isSelected })
    }

    @Test fun `removed selected server falls back to first entry`() {
        val old = group("saved", "https://example.com/sub", true)
        val fresh = group("new")
        assertTrue(LocalProfiles.mergeRemote(old, fresh).servers.single().isSelected)
    }

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
