package com.gdhapp.client.ui.main

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gdhapp.client.GdhappApplication
import com.gdhapp.client.data.SettingsRepository
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.ProbeMethod
import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.domain.model.ServerSort
import com.gdhapp.client.domain.model.SubscriptionGroup
import com.gdhapp.client.domain.model.VpnMetrics
import com.gdhapp.client.domain.model.VpnState
import com.gdhapp.client.domain.repository.LatencyTester
import com.gdhapp.client.domain.repository.SubscriptionRepository
import com.gdhapp.client.vpn.VpnSessionStore
import com.gdhapp.client.vpn.core.CoreSessionFactory
import com.gdhapp.client.vpn.core.safeCoreError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AppEntry(val packageName: String, val label: String)

data class MainUiState(
    val groups: List<SubscriptionGroup> = emptyList(),
    val settings: ClientSettings = ClientSettings(),
    val vpnState: VpnState = VpnState.Disconnected,
    val metrics: VpnMetrics = VpnMetrics(),
    val importing: Boolean = false,
    val refreshing: Set<String> = emptySet(),
    val startupRefreshing: Boolean = false,
    val refreshTotal: Int = 0,
    val refreshCompleted: Int = 0,
    val activeNode: ServerNode? = null,
    val testingNodes: Set<String> = emptySet(),
    val isTesting: Boolean = false,
    val probeTotal: Int = 0,
    val probeCompleted: Int = 0,
    val favoritesOnly: Boolean = false,
    val protocolFilter: String? = null,
    val coreVersions: Map<String, String> = emptyMap(),
    val apps: List<AppEntry> = emptyList(),
    val loadingApps: Boolean = false,
) {
    val allNodes get() = groups.flatMap { it.servers }
    val selectedNode get() = groups.firstNotNullOfOrNull { group -> group.servers.firstOrNull { it.isSelected } }
    val connectionBusy get() = vpnState == VpnState.Connected || vpnState == VpnState.Connecting
    val connectionNode get() = if (connectionBusy) activeNode ?: selectedNode else selectedNode
    fun visibleNodes(group: SubscriptionGroup): List<ServerNode> {
        val nodes = group.servers.filter {
            (!favoritesOnly || it.isFavorite) &&
                (protocolFilter == null || it.protocolSummary.substringBefore(" /").equals(protocolFilter, true))
        }
        return when (settings.sort) {
            ServerSort.ORIGINAL -> nodes
            ServerSort.NAME -> nodes.sortedBy { it.name.lowercase() }
            ServerSort.LATENCY -> nodes.sortedBy { it.probeResults[settings.probeMethod]?.latencyMs ?: Int.MAX_VALUE }
            ServerSort.FAVORITES -> nodes.sortedByDescending { it.isFavorite }
        }
    }
}

sealed interface MainEvent {
    data class Message(val text: String) : MainEvent
    data class ServerRemoved(val removed: com.gdhapp.client.domain.model.RemovedServer) : MainEvent
    data class RequestVpnPermission(val config: String) : MainEvent
    data object StopVpn : MainEvent
}

class MainViewModel(
    private val repository: SubscriptionRepository,
    private val session: VpnSessionStore,
    private val latencyTester: LatencyTester,
    private val preferences: SettingsRepository,
    private val cores: CoreSessionFactory,
    private val subscriptionUpdater: com.gdhapp.client.background.SubscriptionUpdater,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState(groups = repository.groups.value, settings = preferences.settings.value))
    val uiState = mutableState.asStateFlow()
    // Traffic is consumed only by connection indicators, never by the list or settings.
    val metrics = session.metrics
    private val mutableEvents = MutableSharedFlow<MainEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events = mutableEvents.asSharedFlow()
    private var probeJob: Job? = null
    private var refreshTimer: Job? = null
    private var startupRefresh: Job? = null
    private var startupRefreshIds = emptySet<String>()
    private var startupRefreshed = false
    private var switchJob: Job? = null

    init {
        viewModelScope.launch { repository.groups.collect { groups -> mutableState.update { it.copy(groups = groups) } } }
        viewModelScope.launch { preferences.settings.collect { settings -> mutableState.update { it.copy(settings = settings) } } }
        viewModelScope.launch { session.activeNode.collect { node -> mutableState.update { it.copy(activeNode = node) } } }
        viewModelScope.launch { session.state.collect { vpn ->
            mutableState.update { it.copy(vpnState = vpn) }
            if (vpn is VpnState.Error) message(vpn.message)
        } }
        checkCoreVersions()
    }

    fun foreground(visible: Boolean) {
        refreshTimer?.cancel(); refreshTimer = null
        if (visible && !startupRefreshed) {
            startupRefreshed = true
            startupRefresh = viewModelScope.launch {
                var total = 0
                try {
                    val failed = subscriptionUpdater.refreshOnOpen { ids ->
                        if (total == 0) total = ids.size
                        val previous = startupRefreshIds
                        startupRefreshIds = ids
                        mutableState.update { it.copy(startupRefreshing = ids.isNotEmpty(), refreshing = it.refreshing - previous + ids, refreshTotal = total, refreshCompleted = total - ids.size) }
                    }
                    if (failed > 0) message(AppText.text(R.string.subscription_refresh_failed, failed))
                } finally {
                    val remaining = startupRefreshIds
                    startupRefreshIds = emptySet()
                    mutableState.update { it.copy(startupRefreshing = false, refreshing = it.refreshing - remaining) }
                }
            }
        }
        if (visible) refreshTimer = viewModelScope.launch {
            while (true) {
                delay(60_000)
                subscriptionUpdater.refreshDue()
            }
        }
    }
    suspend fun awaitStartupRefresh() { startupRefresh?.join() }
    fun cancelStartupRefresh() {
        startupRefresh?.cancel()
        val pending = startupRefreshIds
        startupRefreshIds = emptySet()
        mutableState.update { it.copy(startupRefreshing = false, refreshing = it.refreshing - pending) }
    }

    fun connect() {
        if (uiState.value.connectionBusy) mutableEvents.tryEmit(MainEvent.StopVpn)
        else repository.groups.value.firstNotNullOfOrNull { it.servers.firstOrNull { server -> server.isSelected } }?.let { node ->
            if (uiState.value.groups.any { it.id in uiState.value.refreshing && it.servers.any { server -> server.id == node.id } }) message(AppText.text(R.string.wait_for_the_subscription_update_to_finish))
            else mutableEvents.tryEmit(MainEvent.RequestVpnPermission(node.rawConfigJson))
        } ?: message(AppText.text(R.string.add_a_subscription_and_select_a_server))
    }

    fun selectNode(id: String) {
        if (uiState.value.selectedNode?.id == id && (!uiState.value.connectionBusy || uiState.value.activeNode?.id == id)) return
        switchJob?.cancel()
        switchJob = viewModelScope.launch {
            try {
                val reconnect = uiState.value.connectionBusy
                if (reconnect && !uiState.value.settings.reconnectOnSelect) { message(AppText.text(R.string.disconnect_the_vpn_before_switching_servers)); return@launch }
                if (reconnect) {
                    mutableEvents.emit(MainEvent.StopVpn)
                    withTimeout(8000) { session.state.first { it == VpnState.Disconnected || it is VpnState.Error } }
                }
                repository.selectNode(id)
                if (reconnect) repository.groups.value.flatMap { it.servers }.firstOrNull { it.id == id }?.let {
                    mutableEvents.emit(MainEvent.RequestVpnPermission(it.rawConfigJson))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(safeCoreError(error)) }
        }
    }

    fun setFavoritesOnly(value: Boolean) { mutableState.update { it.copy(favoritesOnly = value) } }
    fun setProtocol(value: String?) { mutableState.update { it.copy(protocolFilter = value) } }
    fun updateSettings(settings: ClientSettings) = preferences.update { settings }
    fun resetAppearance() = preferences.resetAppearance()
    fun checkCoreVersions() { viewModelScope.launch { val versions = cores.versions(); mutableState.update { it.copy(coreVersions = versions) } } }
    fun toggleExpanded(id: String) = runAction { repository.toggleExpanded(id) }
    fun collapseAll() = runAction { repository.collapseAll() }
    fun togglePinned(id: String) = runAction { repository.togglePinned(id) }
    fun toggleFavorite(id: String) = runAction { repository.toggleFavorite(id) }
    fun renameGroup(id: String, title: String) = runAction { repository.renameGroup(id, title) }
    fun editNode(id: String, name: String, config: String) {
        if (uiState.value.connectionBusy && uiState.value.selectedNode?.id == id) { message(AppText.text(R.string.disconnect_the_vpn_before_editing_the_active)); return }
        runAction { repository.updateNode(id, name, config); message(AppText.text(R.string.configuration_saved)) }
    }
    fun remove(id: String) {
        if (uiState.value.connectionBusy && repository.groups.value.firstOrNull { it.id == id }?.servers?.any { it.isSelected } == true) {
            message(AppText.text(R.string.disconnect_the_vpn_before_deleting_the_active)); return
        }
        runAction { repository.remove(id) }
    }
    fun importText(text: String) {
        if (uiState.value.importing) return
        mutableState.update { it.copy(importing = true) }
        runAction(onFinally = { mutableState.update { it.copy(importing = false) } }) { repository.importText(text); message(AppText.text(R.string.subscription_added)) }
    }
    fun removeNode(id: String) = runAction {
        repository.removeNode(id)?.let { mutableEvents.emit(MainEvent.ServerRemoved(it)) }
    }
    fun restoreNode(removed: com.gdhapp.client.domain.model.RemovedServer) = runAction { repository.restoreNode(removed) }
    fun refresh(id: String, automatic: Boolean = false) {
        if (id in uiState.value.refreshing) return
        mutableState.update { it.copy(refreshing = it.refreshing + id) }
        runAction(onFinally = { mutableState.update { it.copy(refreshing = it.refreshing - id) } }) {
            repository.refresh(id); if (!automatic) message(AppText.text(R.string.subscription_updated))
        }
    }

    fun testLatency(groupId: String? = null, nodeId: String? = null) {
        if (probeJob?.isCompleted == false) {
            // A swipe during a batch must not queue a second measurement of the same server.
            if (nodeId == null) message(AppText.text(R.string.a_check_is_already_running_you_can))
            return
        }
        val current = uiState.value
        val nodes = current.groups.filter { groupId == null || it.id == groupId }.flatMap { group ->
            if (nodeId == null) group.servers else group.servers.filter { it.id == nodeId }
        }
        if (nodes.isEmpty()) { message(AppText.text(R.string.no_servers_to_check)); return }
        mutableState.update { it.copy(isTesting = true, probeTotal = nodes.size, probeCompleted = 0) }
        probeJob = viewModelScope.launch {
            try {
                val semaphore = Semaphore(current.settings.probeConcurrency)
                nodes.map { node -> async { semaphore.withPermit {
                    mutableState.update { it.copy(testingNodes = it.testingNodes + node.id) }
                    try { repository.updateProbe(node.id, latencyTester.test(node, current.settings.probeMethod, current.settings)) }
                    finally { mutableState.update { it.copy(testingNodes = it.testingNodes - node.id, probeCompleted = it.probeCompleted + 1) } }
                } } }.awaitAll()
                val successful = uiState.value.allNodes.filter { node -> nodes.any { it.id == node.id } }
                    .count { it.probeResults[current.settings.probeMethod]?.isSuccess == true }
                message(AppText.text(R.string.of_successful, current.settings.probeMethod.label, successful, nodes.size))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(safeCoreError(error)) }
            finally { mutableState.update { it.copy(isTesting = false, testingNodes = emptySet()) } }
        }
    }
    fun stopProbes() { probeJob?.cancel() }
    fun chooseFastest() {
        val fastest = uiState.value.allNodes.filter { it.probeResults[uiState.value.settings.probeMethod]?.isSuccess == true }
            .minByOrNull { it.probeResults.getValue(uiState.value.settings.probeMethod).latencyMs!! }
        if (fastest == null) message(AppText.text(R.string.run_a_check_with_the_selected_method))
        else { selectNode(fastest.id); message(AppText.text(R.string.selected_182, fastest.name)) }
    }

    fun loadApps(packageManager: PackageManager) {
        if (uiState.value.loadingApps) return
        mutableState.update { it.copy(loadingApps = true) }
        runAction(onFinally = { mutableState.update { it.copy(loadingApps = false) } }) {
            val apps = withContext(Dispatchers.IO) {
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != "com.gdhapp.client" }
                    .map { AppEntry(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                    .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            }
            mutableState.update { it.copy(apps = apps) }
        }
    }
    fun message(text: String) { mutableEvents.tryEmit(MainEvent.Message(text)) }
    private fun runAction(onFinally: () -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(if (error is IllegalArgumentException) safeCoreError(error) else AppText.text(R.string.could_not_complete_this_action_check_the)) }
            finally { onFinally() }
        }
    }
    class Factory(private val app: GdhappApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(app.subscriptions, app.vpnSession, app.latencyTester, app.settings, app.coreSessions, app.subscriptionUpdater) as T
    }
}
