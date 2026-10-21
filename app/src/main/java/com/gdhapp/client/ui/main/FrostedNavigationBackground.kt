package com.gdhapp.client.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.dp

/** Keep the last blurred frame during flings; do not rerender the whole list for a glass effect. */
@Composable
fun FrostedNavigationBackground(backdrop: GraphicsLayer, enabled: Boolean, modifier: Modifier = Modifier, refresh: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))) {
        CachedBackdrop(backdrop, enabled, refresh, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            scheme.surfaceContainer.copy(alpha = if (enabled) .62f else .98f),
            scheme.surfaceContainerLow.copy(alpha = if (enabled) .88f else 1f),
        ))))
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent,
                scheme.onSurface.copy(alpha = .12f), androidx.compose.ui.graphics.Color.Transparent)),
                size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
        }
    }
}
