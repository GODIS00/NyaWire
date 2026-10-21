package com.gdhapp.client.data

import com.gdhapp.client.domain.model.SubscriptionGroup

/** Local keys share one list; remote subscriptions keep their own metadata and refresh URL. */
internal object LocalProfiles {
    fun combine(groups: List<SubscriptionGroup>): List<SubscriptionGroup> {
        val local = groups.filter { it.sourceUrl == null }
        if (local.size < 2) return groups
        val first = local.first()
        val merged = first.copy(
            servers = local.flatMap { it.servers },
            isPinned = local.any { it.isPinned },
            isExpanded = local.any { it.isExpanded },
            lastUpdated = local.maxBy { it.updatedAtEpochMs }.lastUpdated,
            updatedAtEpochMs = local.maxOf { it.updatedAtEpochMs },
        )
        return groups.mapNotNull { if (it.sourceUrl != null) it else if (it.id == first.id) merged else null }
    }

    fun append(current: List<SubscriptionGroup>, incoming: SubscriptionGroup): List<SubscriptionGroup> {
        val groups = combine(current)
        val target = groups.firstOrNull { it.sourceUrl == null }
        val existing = target?.servers.orEmpty().map { it.rawConfigJson }.toSet()
        val additions = incoming.servers.distinctBy { it.rawConfigJson }.filterNot { it.rawConfigJson in existing }
        if (additions.isEmpty()) return groups
        val hasSelection = groups.any { group -> group.servers.any { it.isSelected } }
        val nodes = additions.mapIndexed { index, node -> node.copy(isSelected = !hasSelection && index == 0) }
        if (target == null) return groups + incoming.copy(servers = nodes)
        return groups.map { if (it.id != target.id) it else it.copy(
            servers = it.servers + nodes, isExpanded = true,
            lastUpdated = incoming.lastUpdated, updatedAtEpochMs = incoming.updatedAtEpochMs,
        ) }
    }
}
