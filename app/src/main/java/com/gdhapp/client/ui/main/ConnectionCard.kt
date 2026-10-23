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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.launch
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
    Column(Modifier.fillMaxWidth().then(if (motion) Modifier.animateContentSize() else Modifier).padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    val glow by animateFloatAsState(if (connected) .3f else if (connecting) .2f else .12f,
        animationSpec = if (motion) tween(400) else snap(), label = "powerGlow")
    // Everything below is read only inside drawBehind/graphicsLayer, so the per-frame loop redraws without recomposing.
    val lit = animateFloatAsState(if (connected) 1f else 0f,
        if (motion) tween(700, easing = FastOutSlowInEasing) else snap(), label = "powerLit")
    val comet = animateFloatAsState(if (connecting) 1f else 0f,
        if (motion) tween(450, easing = FastOutSlowInEasing) else snap(), label = "powerComet")
    // Degrees per second; integrated frame by frame so speed changes ease in instead of jumping the angle.
    val speed = animateFloatAsState(if (connecting) 330f else if (connected) 40f else 18f,
        if (motion) tween(800, easing = FastOutSlowInEasing) else snap(), label = "powerSpeed")
    val angle = remember { mutableFloatStateOf(0f) }
    if (motion) LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            angle.floatValue = (angle.floatValue + speed.value * (now - last) / 1_000_000_000f) % 360f
            last = now
        }
    }
    val ambient = rememberInfiniteTransition(label = "powerAmbient")
    val breath = if (motion) ambient.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "powerBreath") else remember { mutableFloatStateOf(0f) }
    val ripple = if (motion) ambient.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "powerRipple") else remember { mutableFloatStateOf(0f) }
    val burst = remember { Animatable(1f) }
    val pop = remember { Animatable(1f) }
    val wasConnected = remember { mutableStateOf(connected) }
    LaunchedEffect(connected) {
        val fresh = connected && !wasConnected.value
        wasConnected.value = connected
        if (fresh && motion) {
            launch { burst.snapTo(0f); burst.animateTo(1f, tween(950, easing = LinearOutSlowInEasing)) }
            pop.snapTo(.78f)
            pop.animateTo(1f, spring(dampingRatio = .36f, stiffness = 240f))
        }
    }
    Box(Modifier.size(216.dp).graphicsLayer { scaleX = scale; scaleY = scale }.drawBehind {
        val c = center
        val ring = 92.dp.toPx()
        val fill = 84.dp.toPx()
        val l = lit.value
        val k = comet.value
        val b = breath.value
        val brush = Brush.sweepGradient(colors + colors.first(), center = c)
        rotate(angle.floatValue) {
            // Ripples radiating out of the ring while connected.
            if (l > 0f) for (i in 0 until 2) {
                val p = (ripple.value + i / 2f) % 1f
                drawCircle(brush, radius = ring + p * 15.dp.toPx(), style = Stroke((1f + (1f - p) * 3f).dp.toPx()),
                    alpha = (1f - p) * (1f - p) * .55f * l)
            }
            drawCircle(brush, radius = ring, style = Stroke(24.dp.toPx()), alpha = glow * (.35f + .35f * l * b))
        }
        drawCircle(Brush.linearGradient(listOf(base, end)), radius = fill)
        // Soft specular highlight, breathing slightly while connected.
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .12f + .06f * l * b), Color.Transparent),
            center = Offset(c.x - fill * .35f, c.y - fill * .45f), radius = fill * 1.1f), radius = fill)
        drawCircle(Color.White.copy(alpha = .07f), radius = fill - 3.dp.toPx(), style = Stroke(1.dp.toPx()))
        rotate(angle.floatValue) {
            val dim = 1f - k * .7f
            drawCircle(brush, radius = ring, style = Stroke(8.dp.toPx()), alpha = glow * dim)
            drawCircle(brush, radius = ring, style = Stroke(3.5.dp.toPx()), alpha = dim)
            // Comet chasing around the ring while connecting.
            if (k > 0f) {
                val head = androidx.compose.ui.graphics.lerp(colors.last(), Color.White, .4f)
                val tail = Brush.sweepGradient(0f to Color.Transparent, .5f to colors.first(), .72f to head, center = c)
                drawArc(tail, 0f, 260f, false, topLeft = Offset(c.x - ring, c.y - ring), size = Size(ring * 2, ring * 2),
                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round), alpha = k)
                val a = Math.toRadians(260.0)
                val tip = Offset(c.x + ring * kotlin.math.cos(a).toFloat(), c.y + ring * kotlin.math.sin(a).toFloat())
                drawCircle(head, radius = 12.dp.toPx(), center = tip, alpha = .28f * k)
                drawCircle(Color.White, radius = 3.5.dp.toPx(), center = tip, alpha = k)
            }
        }
        // One-shot shockwave and flash the moment the tunnel comes up.
        val q = burst.value
        if (q < 1f) {
            drawCircle(Color.White, radius = fill, alpha = (1f - q) * .2f)
            drawCircle(brush, radius = fill + q * 23.dp.toPx(), style = Stroke((1f - q) * 10.dp.toPx() + 1f), alpha = (1f - q) * .9f)
        }
    }.padding(24.dp), contentAlignment = Alignment.Center) {
        FilledIconButton(onClick, modifier = Modifier.fillMaxSize(), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent, contentColor = Color.White),
            interactionSource = interaction) {
            Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) { content() }
        }
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
