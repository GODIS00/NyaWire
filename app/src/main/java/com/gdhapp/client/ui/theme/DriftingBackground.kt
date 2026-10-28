package com.gdhapp.client.ui.theme

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gdhapp.client.R
import kotlinx.coroutines.isActive
import kotlin.math.sin

private data class BackgroundCat(val image: Int, val x: Float, val y: Float, val size: Int, val phase: Float, val sample: Int = 2, val tilt: Float = 0f)
private val cats = listOf(
    BackgroundCat(R.drawable.background_cat_1, .02f, .01f, 130, 0f, 4, -9f),
    BackgroundCat(R.drawable.background_cat_2, .68f, .18f, 125, 1.1f, 1),
    BackgroundCat(R.drawable.background_cat_3, .06f, .35f, 120, 2.2f, tilt = 8f),
    BackgroundCat(R.drawable.background_cat_4, .73f, .49f, 145, 3.3f),
    BackgroundCat(R.drawable.background_cat_5, .01f, .72f, 150, 4.4f, tilt = -7f),
    BackgroundCat(R.drawable.background_cat_6, .66f, .85f, 135, 5.5f),
)

/** Seconds of background motion. It stands still while paused, hidden or with system animations off. */
@Composable
internal fun rememberBackgroundClock(motionEnabled: Boolean, framesPerSecond: Int = 30): FloatState {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(lifecycle, motionEnabled, framesPerSecond) {
        if (!motionEnabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!ValueAnimator.areAnimatorsEnabled()) return@repeatOnLifecycle
            var previous = 0L
            var pending = 0L
            val interval = 1_000_000_000L / framesPerSecond.coerceIn(15, 60)
            while (isActive) {
                withFrameNanos { now ->
                    if (previous != 0L) pending += (now - previous).coerceAtMost(100_000_000L)
                    previous = now
                    if (pending >= interval - 1_000_000L) {
                        time.floatValue += (pending / 1_000_000_000.0).toFloat()
                        pending = 0L
                    }
                }
            }
        }
    }
    return time
}

/** Drawing-only transforms: server lists do not recompose for each animation frame. */
@Composable
fun DriftingBackground(enabled: Boolean, motionEnabled: Boolean, dark: Boolean, modifier: Modifier = Modifier, densityCount: Int = 9, opacity: Int = 12) {
    if (!enabled) return
    val context = LocalContext.current
    val time = rememberBackgroundClock(motionEnabled)
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        // Small previews drift proportionally less, so the cats stay inside the tile.
        val drift = with(density) { minOf(24.dp, maxWidth * .07f).toPx() }
        val decorations = remember(densityCount) {
            if (densityCount <= 6) cats else cats + listOf(
                BackgroundCat(R.drawable.background_cat_2, .36f, .04f, 90, 6.6f, 2, -12f),
                BackgroundCat(R.drawable.background_cat_5, .40f, .42f, 95, 7.7f, 2, 11f),
                BackgroundCat(R.drawable.background_cat_1, .35f, .80f, 85, 8.8f, 4, -5f),
                BackgroundCat(R.drawable.background_cat_4, .03f, .55f, 80, 9.9f, 2, 9f),
                BackgroundCat(R.drawable.background_cat_3, .78f, .32f, 85, 11f, 2, -8f),
                BackgroundCat(R.drawable.background_cat_6, .40f, .63f, 75, 12.1f, 2, 5f)
            ).take(densityCount - 6)
        }
        decorations.forEach { cat ->
            val bitmap = remember(context.resources, cat.image) {
                BitmapFactory.decodeResource(context.resources, cat.image, BitmapFactory.Options().apply { inSampleSize = cat.sample }).asImageBitmap()
            }
            val size = minOf(cat.size.dp, maxWidth * .39f)
            val pixels = with(density) { size.toPx() }
            Image(bitmap, contentDescription = null, modifier = Modifier.size(size).graphicsLayer {
                // One image per layer: alpha can be applied directly, without another texture.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                // A full orbit takes about 2.5 minutes, with no jump at direction changes.
                val t = time.floatValue * .042f
                translationX = (width - pixels) * cat.x + sin(t + cat.phase) * drift
                translationY = (height - pixels) * cat.y + sin(t * .8f + cat.phase) * drift
                rotationZ = cat.tilt + sin(t * .7f + cat.phase) * 1.5f
                alpha = opacity.coerceIn(4, 24) / 100f * (if (dark) 1f else 1.15f)
            })
        }
    }
}
