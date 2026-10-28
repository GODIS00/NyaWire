package com.gdhapp.client.ui.main

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.ProbeResult
import com.gdhapp.client.domain.model.ServerLayout
import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.ui.theme.LocalMotionEnabled
import com.gdhapp.client.ui.assets.CountryFlags
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerCard(node: ServerNode, settings: ClientSettings, testing: Boolean, onSelect: () -> Unit,
    onInfo: () -> Unit, onFavorite: () -> Unit, modifier: Modifier = Modifier, grid: Boolean = false,
    onCopy: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onPing: (() -> Unit)? = null) {
    ServerSwipeContainer(modifier, onPing, onDelete) {
        ServerCardContent(node, settings, testing, onSelect, onInfo, onFavorite, grid, onCopy, onDelete, onPing)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCardContent(node: ServerNode, settings: ClientSettings, testing: Boolean, onSelect: () -> Unit,
    onInfo: () -> Unit, onFavorite: () -> Unit, grid: Boolean, onCopy: (() -> Unit)?, onDelete: (() -> Unit)?, onPing: (() -> Unit)?) = com.gdhapp.client.ui.theme.BlockColors {
    val scheme = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val background by animateColorAsState(if (node.isSelected) androidx.compose.ui.graphics.lerp(scheme.surfaceContainerLow, scheme.primary, .08f) else scheme.surfaceContainerLow,
        animationSpec = if (motion) tween(180) else snap(), label = "nodeColor")
    val borderColor by animateColorAsState(if (node.isSelected) scheme.primary.copy(alpha = .5f) else scheme.outlineVariant.copy(alpha = .35f),
        animationSpec = if (motion) tween(180) else snap(), label = "nodeBorder")
    val marker by animateFloatAsState(if (node.isSelected) 1f else 0f, if (motion) tween(220) else snap(), label = "nodeMarker")
    val compact = settings.layout == ServerLayout.COMPACT
    val displayName = remember(node.name) { cleanNodeName(node.name) }
    val protocol = remember(node.protocolSummary) { node.protocolSummary.substringBefore('|').trim().replace(" / ", " · ") }
    Surface(color = background, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().semantics { selected = node.isSelected }.combinedClickable(role = Role.RadioButton, onClick = onSelect, onLongClick = onInfo)) {
        Box {
            if (marker > 0f) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(22.dp)
                .graphicsLayer { alpha = marker; scaleY = marker }.background(scheme.primary, CircleShape))
            if (grid) Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (settings.showFlags) CountryBadge(node.flagResOrCode, Modifier.size(28.dp), small = true)
                    if (node.isFavorite) Icon(Icons.Rounded.Star, AppText.text(R.string.favorites), tint = scheme.primary, modifier = Modifier.padding(start = 6.dp).size(12.dp))
                    Spacer(Modifier.weight(1f))
                    ServerActionsMenu(node, onInfo, onFavorite, onCopy, onDelete, onPing)
                }
                Text(displayName, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (settings.showProtocol) node.protocolSummary.substringBefore(" /") else settings.probeMethod.label,
                        fontSize = 11.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LatencyChip(node.probeResults[settings.probeMethod], testing, compact = true, flat = true, modifier = Modifier.widthIn(max = 84.dp))
                }
            } else Row(Modifier.heightIn(min = if (compact) 56.dp else 64.dp).padding(start = 12.dp, end = 4.dp,
                top = if (compact) 4.dp else 6.dp, bottom = if (compact) 4.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (settings.showFlags) { CountryBadge(node.flagResOrCode, Modifier.size(if (compact) 28.dp else 32.dp), small = true); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f).padding(end = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(displayName, fontSize = if (compact) 13.sp else 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (node.isFavorite) Icon(Icons.Rounded.Star, AppText.text(R.string.favorites), tint = scheme.primary, modifier = Modifier.size(11.dp))
                    }
                    if (settings.showProtocol) Text(protocol,
                        fontSize = 11.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    if (settings.showAddress) Text("${node.host ?: AppText.text(R.string.address_not_found)}:${node.port ?: "?"}", fontSize = 10.sp, lineHeight = 13.sp,
                        color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(72.dp)) {
                    LatencyChip(node.probeResults[settings.probeMethod], testing, compact = true, flat = true)
                    Text(settings.probeMethod.label, fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
                }
                ServerActionsMenu(node, onInfo, onFavorite, onCopy, onDelete, onPing)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerActionsMenu(node: ServerNode, onInfo: () -> Unit, onFavorite: () -> Unit, onCopy: (() -> Unit)?, onDelete: (() -> Unit)?, onPing: (() -> Unit)?) {
    var expanded by remember(node.id) { mutableStateOf(false) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) { Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.MoreVert, "${AppText.text(R.string.server_actions)} · ${cleanNodeName(node.name)}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            if (onPing != null) DropdownMenuItem(text = { Text(AppText.text(R.string.ping_server)) }, leadingIcon = { Icon(Icons.Rounded.NetworkCheck, null) },
                onClick = { expanded = false; onPing() })
            DropdownMenuItem(text = { Text(AppText.text(R.string.details_and_checks)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { expanded = false; onInfo() })
            DropdownMenuItem(text = { Text(if (node.isFavorite) AppText.text(R.string.remove_from_favorites) else AppText.text(R.string.add_to_favorites)) },
                leadingIcon = { Icon(if (node.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, null) }, onClick = { expanded = false; onFavorite() })
            if (onCopy != null) DropdownMenuItem(text = { Text(AppText.text(R.string.copy_configuration)) },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { expanded = false; onCopy() })
            if (onDelete != null) DropdownMenuItem(text = { Text(AppText.text(R.string.delete_server), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { expanded = false; onDelete() })
        }
    }
} }

@Composable
fun LatencyChip(result: ProbeResult?, testing: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, flat: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val color by animateColorAsState(when {
        testing || result == null -> scheme.onSurfaceVariant
        !result.isSuccess -> scheme.error
        result.latencyMs!! < 250 -> scheme.primary
        result.latencyMs < 800 -> scheme.primary
        else -> scheme.tertiary
    }, animationSpec = if (motion) tween(220) else snap(), label = "latencyColor")
    Surface(color = if (flat) Color.Transparent else color.copy(alpha = .1f), shape = CircleShape, modifier = modifier) {
        Row(Modifier.padding(horizontal = if (flat) 0.dp else if (compact) 8.dp else 10.dp, vertical = if (flat) 0.dp else 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (testing) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = color)
            else if (!compact) Icon(when { result == null -> Icons.Rounded.Schedule; result.isSuccess -> Icons.Rounded.NetworkCheck; else -> Icons.Rounded.ErrorOutline },
                null, tint = color, modifier = Modifier.size(12.dp))
            val label = when { testing -> if (compact) "…" else AppText.text(R.string.checking); result == null -> if (compact) "—" else AppText.text(R.string.not_checked); result.isSuccess -> AppText.text(R.string.ms, result.latencyMs);
                compact -> AppText.text(R.string.ping_compact_error); else -> probeFailureTitle(result.error) }
            Text(label, color = color, fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = if (compact) 14.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun probeFailureTitle(error: String?): String = when {
    error == null -> AppText.text(R.string.error)
    error.contains("geoip.dat", true) || error.contains("geosite.dat", true) -> AppText.text(R.string.core_data_error)
    error.contains("config error", true) || error.contains(AppText.text(R.string.config), true) || error.contains("failed to build", true) -> AppText.text(R.string.config_error)
    error.startsWith("HTTP ") -> error.substringBefore('.')
    error.startsWith("DNS") -> AppText.text(R.string.dns_error)
    error.startsWith("TLS") -> AppText.text(R.string.tls_error)
    error.contains(AppText.text(R.string.no_response_within), true) -> AppText.text(R.string.timed_out)
    else -> AppText.text(R.string.check_failed)
}

@Composable
fun CountryBadge(code: String, modifier: Modifier = Modifier, small: Boolean = false) {
    val resource = CountryFlags.drawable(code)
    val shape = RoundedCornerShape(if (small) 6.dp else 9.dp)
    val frame = modifier.size(if (small) 32.dp else 42.dp).clip(shape)
    if (resource != null) Image(painterResource(resource), null, modifier = frame, contentScale = ContentScale.FillBounds)
    else Box(frame.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (small) 18.dp else 22.dp))
    }
}

fun cleanNodeName(name: String): String {
    val text = name.trim()
    val points = text.codePoints().toArray()
    return if (points.size >= 2 && points[0] in 0x1F1E6..0x1F1FF && points[1] in 0x1F1E6..0x1F1FF) text.drop(4).trim() else text
}

fun formatBytes(value: Long, perSecond: Boolean = false): String {
    val suffix = if (perSecond) AppText.text(R.string.s) else ""
    val unit: String
    val divisor: Double
    when {
        value < 1024 -> { unit = AppText.text(R.string.b); divisor = 1.0 }
        value < 1024 * 1024 -> { unit = AppText.text(R.string.kb); divisor = 1024.0 }
        value < 1024L * 1024 * 1024 -> { unit = AppText.text(R.string.mb); divisor = 1048576.0 }
        else -> { unit = AppText.text(R.string.gb); divisor = 1073741824.0 }
    }
    val amount = String.format(com.gdhapp.client.i18n.AppText.locale, if (divisor < 1048576) "%.0f" else "%.1f", value / divisor)
    return "${amount} ${unit}${suffix}"
}
