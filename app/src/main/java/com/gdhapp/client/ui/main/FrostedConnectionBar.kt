package com.gdhapp.client.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.domain.model.VpnState

/** A fixed overlay: showing the panel never resizes or reanchors the server list. */
@Composable
fun FrostedConnectionBar(state: MainUiState, onStop: () -> Unit, backdrop: GraphicsLayer, blur: Boolean, sourceOrigin: Offset = Offset.Zero) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(76.dp).clip(MaterialTheme.shapes.large)) {
        CachedBackdrop(backdrop, blur, Modifier.matchParentSize(), sourceOrigin, radius = 22.dp)
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(
            scheme.surfaceContainerHigh.copy(alpha = if (blur) .62f else .98f),
            scheme.surfaceContainer.copy(alpha = if (blur) .82f else 1f),
        ))))
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(scheme.onSurface.copy(alpha = .09f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(state.settings.cornerRadius.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(state.connectionNode?.let { cleanNodeName(it.name) }.orEmpty(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    state.vpnState == VpnState.Connecting -> AppText.text(R.string.connecting)
                    state.metrics.proxyOnly -> "${state.metrics.engine} · SOCKS5 · 127.0.0.1:${state.metrics.localPort}"
                    else -> AppText.text(R.string.vpn_connected_097, state.metrics.engine)
                }, fontSize = 10.sp, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalIconButton(onClick = onStop) { Icon(Icons.Rounded.PowerSettingsNew, AppText.text(R.string.disconnect_vpn)) }
        }
    }
}
