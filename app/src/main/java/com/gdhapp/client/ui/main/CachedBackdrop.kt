package com.gdhapp.client.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Live panel blur at quarter resolution. Text and controls never pass through this layer. */
@Composable
internal fun CachedBackdrop(backdrop: GraphicsLayer, enabled: Boolean, modifier: Modifier,
    sourceOrigin: Offset, radius: Dp = 22.dp) {
    val crop = rememberGraphicsLayer()
    val density = LocalDensity.current
    val sampleScale = .25f
    val fallback = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f)
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    val blur = remember(density, radius) {
        with(density) { BlurEffect(radius.toPx() * sampleScale, radius.toPx() * sampleScale, TileMode.Clamp) }
    }
    Canvas(modifier.onGloballyPositioned { panelOrigin = it.positionInRoot() }) {
        if (!enabled || backdrop.size.width == 0 || backdrop.size.height == 0) {
            drawRect(fallback)
            return@Canvas
        }
        // Blur is a low-frequency image. Sampling fewer pixels preserves its optical radius
        // while avoiding two full-resolution GPU filters during a connected list scroll.
        val padding = ceil(radius.toPx() * sampleScale * 2f).toInt() + 2
        val target = IntSize(ceil(size.width * sampleScale).toInt() + padding * 2,
            ceil(size.height * sampleScale).toInt() + padding * 2)
        crop.clip = true
        crop.renderEffect = blur
        crop.record(target) {
            drawRect(fallback)
            translate(padding.toFloat(), padding.toFloat()) {
                scale(sampleScale, sampleScale, Offset.Zero) {
                    translate(sourceOrigin.x - panelOrigin.x, sourceOrigin.y - panelOrigin.y) { drawLayer(backdrop) }
                }
            }
        }
        // Only the sampled background is blurred; the controls are drawn separately by the caller.
        clipRect {
            scale(1f / sampleScale, 1f / sampleScale, Offset.Zero) {
                translate(-padding.toFloat(), -padding.toFloat()) { drawLayer(crop) }
            }
        }
    }
}
