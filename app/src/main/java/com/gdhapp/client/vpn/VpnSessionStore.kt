package com.gdhapp.client.vpn

import com.gdhapp.client.domain.model.VpnState
import com.gdhapp.client.domain.model.VpnMetrics
import com.gdhapp.client.domain.model.ServerNode
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Same-process service/UI state; no exported broadcasts or config Intent extras. */
class VpnSessionStore {
    private val mutableState = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state = mutableState.asStateFlow()
    private val mutableMetrics = MutableStateFlow(VpnMetrics())
    val metrics = mutableMetrics.asStateFlow()
    private val mutableActiveNode = MutableStateFlow<ServerNode?>(null)
    val activeNode = mutableActiveNode.asStateFlow()
    private val generation = AtomicLong()
    val coreMutex = Mutex()
    @Volatile var pendingConfig: String? = null
    fun update(state: VpnState) {
        mutableState.value = state
        if (state == VpnState.Disconnected || state is VpnState.Error) mutableActiveNode.value = null
    }
    fun setActiveNode(node: ServerNode?) { mutableActiveNode.value = node }
    fun updateMetrics(metrics: VpnMetrics) { mutableMetrics.value = metrics }
    fun claim(): Long = generation.incrementAndGet()
    fun isCurrent(lease: Long) = generation.get() == lease
}
