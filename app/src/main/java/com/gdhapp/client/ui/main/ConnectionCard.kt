package com.gdhapp.client.ui.main

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gdhapp.client.domain.model.VpnState
import com.gdhapp.client.ui.theme.LocalMotionEnabled

@Composable
fun ConnectionCard(state: MainUiState, onConnect: () -> Unit, onAdd: () -> Unit, onErrorInfo: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val connected = state.vpnState == VpnState.Connected
    val connecting = state.vpnState == VpnState.Connecting
    val error = state.vpnState as? VpnState.Error
    val add = state.selectedNode == null && !state.connectionBusy
    val motion = LocalMotionEnabled.current
    val statusColor by animateColorAsState(if (error != null) scheme.errorContainer else if (state.connectionBusy) scheme.primaryContainer else scheme.surfaceContainerHigh,
        animationSpec = if (motion) tween(250) else snap(), label = "statusColor")
    val statusTint by animateColorAsState(if (error != null) scheme.onErrorContainer else if (state.connectionBusy) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        animationSpec = if (motion) tween(250) else snap(), label = "statusTint")
    val statusText = when {
        connected -> AppText.text(if (state.metrics.proxyOnly) R.string.proxy_started else R.string.vpn_connected)
        connecting -> AppText.text(R.string.connecting)
        error != null -> AppText.text(R.string.connection_failed)
        add -> AppText.text(R.string.add_a_subscription)
        else -> AppText.text(R.string.vpn_is_off)
    }
        Column(Modifier.fillMaxWidth().then(if (motion) Modifier.animateContentSize() else Modifier).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = CircleShape, color = statusColor) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (connecting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = statusTint)
                        else Icon(if (connected) Icons.Rounded.VerifiedUser else Icons.Rounded.Shield, null, tint = statusTint, modifier = Modifier.size(16.dp))
                        Crossfade(statusText, animationSpec = if (motion) tween(180) else snap(), label = "statusText") { text ->
                            Text(text, fontSize = 12.sp, color = statusTint, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                GradientPowerButton(connecting, connected, state.settings, onClick = { if (add) onAdd() else onConnect() }) {
                    Crossfade(add, animationSpec = if (motion) tween(180) else snap(), label = "powerIcon") { adding ->
                        Icon(if (adding) Icons.Rounded.Add else Icons.Rounded.PowerSettingsNew,
                            AppText.text(if (adding) R.string.add_subscription else if (state.connectionBusy) R.string.disconnect_vpn else R.string.connect_vpn),
                            modifier = Modifier.size(62.dp))
                    }
                }
                Text(state.connectionNode?.let { cleanNodeName(it.name) } ?: AppText.text(R.string.your_servers_will_appear_here),
                    fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally)) {
                Surface(shape = RoundedCornerShape(7.dp), color = scheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Memory, null, modifier = Modifier.size(12.dp), tint = scheme.onSecondaryContainer)
                        Text(if (connected) state.metrics.engine else state.settings.coreEngine.label, fontSize = 9.sp, color = scheme.onSecondaryContainer)
                    }
                }
                if (connected) {
                    Text(elapsed(state.metrics.connectedAt), fontSize = 10.sp, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (state.metrics.proxyOnly) Text("127.0.0.1:${state.metrics.localPort}", fontSize = 10.sp, color = scheme.onSurfaceVariant)
                    else Text("↑ ${formatBytes(state.metrics.uploadBytesPerSecond, true)}   ↓ ${formatBytes(state.metrics.downloadBytesPerSecond, true)}",
                        fontSize = 10.sp, maxLines = 1, color = scheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis)
                } else Text(AppText.text(R.string.server_count_short, state.allNodes.size), fontSize = 10.sp, color = scheme.onSurfaceVariant)
            }
            if (error != null) Surface(onClick = onErrorInfo, shape = MaterialTheme.shapes.small, color = scheme.errorContainer) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Info, null, tint = scheme.onErrorContainer, modifier = Modifier.size(17.dp))
                    Text(connectionErrorSummary(error.message), fontSize = 11.sp, lineHeight = 15.sp, color = scheme.onErrorContainer,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, AppText.text(R.string.view_error_details), tint = scheme.onErrorContainer, modifier = Modifier.size(18.dp))
                }
            }
        }
}

@Composable
private fun GradientPowerButton(connecting: Boolean, connected: Boolean, settings: com.gdhapp.client.domain.model.ClientSettings, onClick: () -> Unit, content: @Composable () -> Unit) {
    val motion = LocalMotionEnabled.current
    val phase = if (motion) rememberInfiniteTransition(label = "powerGradient").animateFloat(0f, 6.283185f,
        infiniteRepeatable(tween(if (connecting) 2400 else 10000, easing = LinearEasing)), label = "gradientAngle") else androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && motion) .92f else 1f,
        if (motion) com.gdhapp.client.ui.theme.IosMotion.value() else snap(), label = "powerPress")
    val colors = com.gdhapp.client.ui.theme.powerGradientColors(settings)
    val base by animateColorAsState(
        androidx.compose.ui.graphics.lerp(Color(0xFF111522), colors.first(), if (connected) .36f else .15f),
        animationSpec = if (motion) tween(250) else snap(), label = "powerFill")
    val end by animateColorAsState(
        androidx.compose.ui.graphics.lerp(Color(0xFF111522), colors.last(), if (connected) .52f else .32f),
        animationSpec = if (motion) tween(250) else snap(), label = "powerFillEnd")
    val glow by animateFloatAsState(if (connected) .24f else .12f,
        animationSpec = if (motion) tween(250) else snap(), label = "powerGlow")
    Box(Modifier.size(200.dp).graphicsLayer { scaleX = scale; scaleY = scale }.drawBehind {
        val brush = Brush.sweepGradient(colors + colors.first())
        drawCircle(Brush.linearGradient(listOf(base, end)), radius = size.minDimension / 2 - 16.dp.toPx())
        drawCircle(Color.White.copy(alpha = .07f), radius = size.minDimension / 2 - 19.dp.toPx(), style = Stroke(1.dp.toPx()))
        rotate(phase.value * 180f / kotlin.math.PI.toFloat()) {
            drawCircle(brush, radius = size.minDimension / 2 - 8.dp.toPx(), style = Stroke(18.dp.toPx()), alpha = glow * .5f)
            drawCircle(brush, radius = size.minDimension / 2 - 8.dp.toPx(), style = Stroke(8.dp.toPx()), alpha = glow)
            drawCircle(brush, radius = size.minDimension / 2 - 8.dp.toPx(), style = Stroke(3.5.dp.toPx()))
        }
    }.padding(16.dp), contentAlignment = Alignment.Center) {
        FilledIconButton(onClick, modifier = Modifier.fillMaxSize(), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent, contentColor = Color.White),
            interactionSource = interaction, content = content)
    }
}

private fun connectionErrorSummary(message: String): String = when {
    message.contains("geoip.dat", true) || message.contains("geosite.dat", true) -> AppText.text(R.string.could_not_load_routing_data_view_details)
    message.contains("config error", true) || message.contains("failed to build", true) -> AppText.text(R.string.the_core_rejected_this_configuration_view_details)
    else -> message
}

private fun elapsed(start: Long): String {
    val seconds = if (start > 0) ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0) else 0
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
