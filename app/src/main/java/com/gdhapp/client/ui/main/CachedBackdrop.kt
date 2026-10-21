package com.gdhapp.client.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Rasterize just a small panel crop while idle. Scrolling draws immutable pixels without a blur pass. */
@Composable
internal fun CachedBackdrop(backdrop: GraphicsLayer, enabled: Boolean, refresh: Boolean, modifier: Modifier,
    leftOffset: Dp = 0.dp, bottomOffset: Dp = 0.dp, radius: Dp = 28.dp) {
    val crop = rememberGraphicsLayer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val fallback = MaterialTheme.colorScheme.surfaceContainer
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val pixels = remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(backdrop, enabled, refresh, bounds, density, direction, lifecycle, leftOffset, bottomOffset, radius) {
        if (!enabled) { pixels.value = null; return@LaunchedEffect }
        if (!refresh || bounds.width == 0 || bounds.height == 0) return@LaunchedEffect
        // Quarter resolution is ample for a soft blur and keeps GPU readback small.
        val target = IntSize((bounds.width / 4).coerceAtLeast(1), (bounds.height / 4).coerceAtLeast(1))
        val sx = target.width.toFloat() / bounds.width
        val sy = target.height.toFloat() / bounds.height
        crop.clip = true
        crop.renderEffect = with(density) { BlurEffect(radius.toPx() * sx, radius.toPx() * sy, TileMode.Clamp) }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (isActive) {
            withFrameNanos { }
            if (backdrop.size.width > 0 && backdrop.size.height > 0) {
                crop.record(density, direction, target) {
                    scale(sx, sy, Offset.Zero) {
                        translate(left = leftOffset.toPx(), top = bounds.height - backdrop.size.height + bottomOffset.toPx()) { drawLayer(backdrop) }
                    }
                }
                try { pixels.value = crop.toImageBitmap() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the last frame; the panel tint remains readable. */ }
            }
            delay(1000)
        } }
    }
    Canvas(modifier.onSizeChanged { bounds = it }) {
        if (pixels.value == null) drawRect(fallback)
        pixels.value?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
    }
}
