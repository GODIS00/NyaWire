package com.gdhapp.client.ui.main

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.ui.theme.touchFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Bounded reveal, one action on finger-up, and no action on cancellation or vertical scrolling. */
@Composable
internal fun ServerSwipeContainer(modifier: Modifier, onPing: (() -> Unit)?, onDelete: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onPing == null || onDelete == null) { Box(modifier) { content() }; return }
    val ping by rememberUpdatedState(onPing)
    val delete by rememberUpdatedState(onDelete)
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(1) }
    val offset = remember { mutableFloatStateOf(0f) }
    var returning by remember { mutableStateOf<Job?>(null) }
    val threshold = min(with(density) { 72.dp.toPx() }, width * .35f)
    val limit = min(with(density) { 112.dp.toPx() }, width * .6f)
    val right by remember { derivedStateOf { offset.floatValue >= 0f } }
    val shown by remember { derivedStateOf { abs(offset.floatValue) > .5f } }
    val motion = com.gdhapp.client.ui.theme.LocalMotionEnabled.current
    fun settle() {
        returning?.cancel()
        returning = scope.launch {
            if (motion) animate(offset.floatValue, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 650f)) { value, _ -> offset.floatValue = value }
            offset.floatValue = 0f
        }
    }
    Box(modifier.onSizeChanged { width = it.width }.clip(MaterialTheme.shapes.medium)
        .pointerInput(threshold, limit, motion) {
            var moved = 0f
            detectHorizontalDragGestures(
                onDragStart = { returning?.cancel(); moved = 0f },
                onDragCancel = { settle() },
                onDragEnd = {
                    if (abs(moved) >= threshold && abs(offset.floatValue) >= threshold) {
                        touchFeedback(view, true)
                        if (offset.floatValue > 0) ping?.invoke() else delete?.invoke()
                    }
                    settle()
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    moved += amount
                    offset.floatValue = (offset.floatValue + amount).coerceIn(-limit, limit)
                },
            )
        }) {
        if (shown) {
            val scheme = MaterialTheme.colorScheme
            Box(Modifier.matchParentSize().background(if (right) scheme.primaryContainer else scheme.errorContainer)) {
                Row(Modifier.align(if (right) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
                    .padding(horizontal = 14.dp).graphicsLayer { alpha = (abs(offset.floatValue) / threshold).coerceIn(0f, 1f) },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(if (right) Icons.Rounded.NetworkCheck else Icons.Rounded.DeleteOutline, null,
                        tint = if (right) scheme.onPrimaryContainer else scheme.onErrorContainer, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(AppText.text(if (right) R.string.checks else R.string.delete), fontSize = 11.sp,
                        color = if (right) scheme.onPrimaryContainer else scheme.onErrorContainer, maxLines = 1)
                }
            }
        }
        Box(Modifier.absoluteOffset { IntOffset(offset.floatValue.roundToInt(), 0) }) { content() }
    }
}
