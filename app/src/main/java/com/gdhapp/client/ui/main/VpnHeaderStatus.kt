package com.gdhapp.client.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gdhapp.client.R
import com.gdhapp.client.domain.model.VpnMetrics
import com.gdhapp.client.domain.model.VpnState
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.ui.theme.LocalMotionEnabled
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun VpnHeaderStatus(state: MainUiState, metrics: StateFlow<VpnMetrics>?, modifier: Modifier = Modifier) {
    // Observe traffic here, so a metrics update never recomposes the server list.
    val currentMetrics = metrics?.collectAsStateWithLifecycle()?.value ?: state.metrics
    val scheme = MaterialTheme.colorScheme
    val tint by animateColorAsState(when (state.vpnState) {
        VpnState.Connected, VpnState.Connecting -> scheme.primary
        is VpnState.Error -> scheme.error
        else -> scheme.onSurfaceVariant
    }, animationSpec = if (LocalMotionEnabled.current) tween(250) else snap(), label = "headerStatusColor")
    val label = AppText.text(when (state.vpnState) {
        VpnState.Connected -> if (currentMetrics.proxyOnly) R.string.proxy_started else R.string.vpn_connected
        VpnState.Connecting -> R.string.connecting
        is VpnState.Error -> R.string.connection_failed
        else -> R.string.vpn_is_off
    })
    Row(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(tint, CircleShape))
        Text(label, color = tint, fontSize = 11.sp, lineHeight = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
