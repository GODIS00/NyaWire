package com.gdhapp.client.data

import com.gdhapp.client.domain.model.SubscriptionGroup

/** Local keys share one list; remote subscriptions keep their own metadata and refresh URL. */
internal object LocalProfiles {
    fun mergeRemote(old: SubscriptionGroup, fresh: SubscriptionGroup): SubscriptionGroup {
        val selected = old.servers.firstOrNull { it.isSelected }
        val matching = fresh.servers.indexOfFirst { "${old.id}:${it.id}" == selected?.id }
            .takeIf { it >= 0 } ?: fresh.servers.indexOfFirst { it.name == selected?.name }
        // Preserve the first name match, as before, without scanning the old list per server.
        val byName = old.servers.asReversed().associateBy { it.name }
        return fresh.copy(id = old.id, title = old.title, isPinned = old.isPinned, isExpanded = old.isExpanded,
            servers = fresh.servers.mapIndexed { index, node -> node.copy(
                id = "${old.id}:${node.id}",
                isSelected = selected != null && index == if (matching >= 0) matching else 0,
                isFavorite = byName[node.name]?.isFavorite == true,
            ) })
    }

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
