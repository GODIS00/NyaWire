package com.gdhapp.client.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Drag reveals a bounded action. Only tapping the exposed button invokes it. */
@Composable
internal fun ServerSwipeContainer(modifier: Modifier, onPing: (() -> Unit)?, onDelete: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onPing == null || onDelete == null) { Box(modifier) { content() }; return }
    val ping by rememberUpdatedState(onPing)
    val delete by rememberUpdatedState(onDelete)
    val scope = rememberCoroutineScope()
    val offset = remember { mutableFloatStateOf(0f) }
    var settled by remember { mutableStateOf(false) }
    var animation by remember { mutableStateOf<Job?>(null) }
    val limit = with(LocalDensity.current) { 88.dp.toPx() }
    fun settle(target: Float) {
        animation?.cancel()
        settled = target != 0f
        animation = scope.launch {
            animate(offset.floatValue, target, animationSpec = spring(dampingRatio = 1f, stiffness = 650f)) { value, _ -> offset.floatValue = value }
            offset.floatValue = target
        }
    }
    BackHandler(settled) { settle(0f) }
    Box(modifier.clip(MaterialTheme.shapes.medium).pointerInput(limit) {
        detectHorizontalDragGestures(
            onDragStart = { animation?.cancel(); settled = false },
            onDragCancel = { settle(0f) },
            onDragEnd = { settle(SwipeReveal.target(offset.floatValue, limit)) },
            onHorizontalDrag = { change, amount ->
                change.consume()
                offset.floatValue = (offset.floatValue + amount).coerceIn(-limit, limit)
            },
        )
    }) {
        if (abs(offset.floatValue) > .5f) {
            val right = offset.floatValue > 0
            val scheme = MaterialTheme.colorScheme
            Box(Modifier.matchParentSize().background(if (right) scheme.primaryContainer else scheme.errorContainer)) {
                Column(Modifier.align(if (right) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
                    .width(88.dp).fillMaxHeight().clickable(enabled = settled, onClick = {
                        settle(0f)
                        if (right) ping?.invoke() else delete?.invoke()
                    }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(if (right) Icons.Rounded.NetworkCheck else Icons.Rounded.DeleteOutline,
                        AppText.text(if (right) R.string.checks else R.string.delete),
                        tint = if (right) scheme.onPrimaryContainer else scheme.onErrorContainer)
                    Text(AppText.text(if (right) R.string.checks else R.string.delete), fontSize = 10.sp,
                        color = if (right) scheme.onPrimaryContainer else scheme.onErrorContainer)
                }
            }
        }
        Box(Modifier.absoluteOffset { IntOffset(offset.floatValue.roundToInt(), 0) }) {
            content()
            if (settled) Box(Modifier.matchParentSize().clickable { settle(0f) })
        }
    }
}

internal object SwipeReveal {
    fun target(offset: Float, limit: Float): Float = when {
        offset >= limit * .5f -> limit
        offset <= -limit * .5f -> -limit
        else -> 0f
    }
}
