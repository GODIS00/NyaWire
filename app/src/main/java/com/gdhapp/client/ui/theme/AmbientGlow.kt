package com.gdhapp.client.ui.theme

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Decorative layers only; text and scrolling rows stay sharp. */
@Composable
fun AmbientGlow(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val radius = size.width * .38f
        val center = Offset(size.width * .9f, size.height * .1f)
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = .14f), Color.Transparent), center, radius), radius, center)
        val other = Offset(size.width * .1f, size.height)
        drawCircle(Brush.radialGradient(listOf(secondary.copy(alpha = .07f), Color.Transparent), other, radius), radius, other)
    }
}
