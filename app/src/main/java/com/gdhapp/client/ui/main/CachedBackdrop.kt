package com.gdhapp.client.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlin.math.roundToInt

/** A retained, panel-sized blur. Extra pixels around the crop prevent clamped seams at its edges. */
@Composable
internal fun CachedBackdrop(backdrop: GraphicsLayer, enabled: Boolean, modifier: Modifier,
    sourceOrigin: Offset, radius: Dp = 22.dp) {
    val crop = rememberGraphicsLayer()
    val density = LocalDensity.current
    val fallback = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f)
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    val blur = remember(density, radius) {
        with(density) { BlurEffect(radius.toPx(), radius.toPx(), TileMode.Clamp) }
    }
    Canvas(modifier.onGloballyPositioned { panelOrigin = it.positionInRoot() }) {
        if (!enabled || backdrop.size.width == 0 || backdrop.size.height == 0) {
            drawRect(fallback)
            return@Canvas
        }
        val padding = ceil(radius.toPx() * 2f).toInt()
        val target = IntSize(size.width.roundToInt() + padding * 2, size.height.roundToInt() + padding * 2)
        crop.clip = true
        crop.renderEffect = blur
        crop.record(target) {
            drawRect(fallback)
            translate(sourceOrigin.x - panelOrigin.x + padding, sourceOrigin.y - panelOrigin.y + padding) {
                drawLayer(backdrop)
            }
        }
        // Only the sampled background is blurred; the controls are drawn separately by the caller.
        clipRect { translate(-padding.toFloat(), -padding.toFloat()) { drawLayer(crop) } }
    }
}
