package com.gdhapp.client.domain.repository

import com.gdhapp.client.domain.model.SubscriptionGroup
import kotlinx.coroutines.flow.StateFlow
import com.gdhapp.client.domain.model.ProbeResult
import com.gdhapp.client.domain.model.RemovedServer

interface SubscriptionRepository {
    val groups: StateFlow<List<SubscriptionGroup>>
    suspend fun importText(text: String)
    suspend fun refresh(id: String)
    suspend fun selectNode(id: String)
    suspend fun toggleExpanded(id: String)
    suspend fun collapseAll()
    suspend fun togglePinned(id: String)
    suspend fun remove(id: String)
    suspend fun removeNode(id: String): RemovedServer?
    suspend fun restoreNode(removed: RemovedServer)
    suspend fun updatePings(id: String, pings: Map<String, Int>)
    suspend fun updateProbe(nodeId: String, result: ProbeResult)
    suspend fun toggleFavorite(nodeId: String)
    suspend fun updateNode(nodeId: String, name: String, config: String)
    suspend fun renameGroup(id: String, title: String)
}
