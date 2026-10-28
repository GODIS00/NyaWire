package com.gdhapp.client.ui.theme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class RainPalette(
    val sky: List<Color>, val silhouette: Color, val mist: Color, val warm: Color, val cool: Color,
    val glass: Color, val edge: Color, val highlight: Color, val rain: Color, val night: Boolean,
)

private val nightRain = RainPalette(
    listOf(Color(0xFF080E17), Color(0xFF111F2B), Color(0xFF1A303A), Color(0xFF15212C)),
    Color(0xFF0B131F), Color(0xFF243F49), Color(0xFFE6B47A), Color(0xFF92BDBF),
    Color(0xFFD1E5E7), Color(0xFF020911), Color(0xFFEEF5EF), Color(0xFFAACBD0), true,
)
private val dayRain = RainPalette(
    listOf(Color(0xFFE1E8EC), Color(0xFFCBD8DD), Color(0xFFB6C8CF), Color(0xFFACBFC6)),
    Color(0xFF8DABB7), Color(0xFFD5E1E4), Color(0xFFF3D7A7), Color(0xFFEEF4F3),
    Color(0xFFEDF6F7), Color(0xFF365864), Color.White, Color(0xFF5E7E8B), false,
)
private val amoledRain = nightRain.copy(
    sky = listOf(Color.Black, Color(0xFF02060A), Color(0xFF061015), Color(0xFF03090D)),
    silhouette = Color(0xFF010509), mist = Color(0xFF0D2028), warm = Color(0xFFBA8B56), cool = Color(0xFF6B9C9C),
)

private data class RainArtworkKey(val width: Int, val height: Int, val density: Float,
    val direction: LayoutDirection, val palette: RainPalette)
private class RainArtwork(val city: ImageBitmap, val lens: RainLens)
private val rainArtworkCache = LruCache<RainArtworkKey, RainArtwork>(3)
private val rainArtworkLock = Mutex()

/** A defocused street behind real glass: the city is cached, only weather redraws. */
@Composable
internal fun BoxScope.RainBackground(time: FloatState, dark: Boolean, amoled: Boolean) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val palette = when { amoled -> amoledRain; dark -> nightRain; else -> dayRain }
        // Software drawing and defocus must not block Compose's measure pass on the UI thread.
        val key = remember(w, h, density, direction, palette) { RainArtworkKey(w.toInt(), h.toInt(), density.density, direction, palette) }
        val artworkState = remember(key) { mutableStateOf<RainArtwork?>(rainArtworkCache[key]) }
        LaunchedEffect(key) {
            if (artworkState.value != null) return@LaunchedEffect
            val ready = withContext(Dispatchers.Default) {
                rainArtworkLock.withLock {
                    rainArtworkCache[key] ?: rainCity(w, h, density, direction, palette).let { city ->
                        RainArtwork(city, RainLens(city, w, h, palette)).also { rainArtworkCache.put(key, it) }
                    }
                }
            }
            artworkState.value = ready
        }
        val artwork = artworkState.value
        if (artwork == null) {
            Still {
                val sky = Brush.verticalGradient(palette.sky)
                onDrawBehind { drawRect(sky) }
            }
            return@BoxWithConstraints
        }
        // One bounded texture is shared by the background and every refracting drop.
        val city = artwork.city
        val lens = artwork.lens
        Still {
            val full = IntSize(w.roundToInt(), h.roundToInt())
            val count = (w * h / (density.density * density.density) / 2200f).roundToInt().coerceIn(12, 190)
            val beads = List(count) { i ->
                val radius = (.65f + rainSeed(i, 102).pow(2f) * 3.4f) * density.density
                GlassBead(Offset(rainSeed(i, 100) * w, rainSeed(i, 101) * h), radius,
                    .85f + rainSeed(i, 103) * .5f, rainSeed(i, 104) * 32f - 16f)
            }
            onDrawBehind {
                drawImage(city, dstSize = full, filterQuality = FilterQuality.Low)
                for (bead in beads) lens.draw(this, bead.at.x, bead.at.y, bead.radius, bead.stretch, bead.tilt, .72f)
            }
        }
        Moving {
            val dpArea = w * h / (density.density * density.density)
            val streaks = List((dpArea / 3300f).roundToInt().coerceIn(18, 180)) { i ->
                val depth = rainSeed(i, 122)
                RainStreak(rainSeed(i, 120) * w * 1.2f - w * .1f, rainSeed(i, 121),
                    (55f + depth * 100f) * density.density,
                    (6f + depth * 22f) * density.density,
                    (.24f + depth * .38f) * density.density,
                    .025f + depth * .065f)
            }
            val runoffs = List((w / (density.density * 58f)).roundToInt().coerceIn(3, 14)) { RainRunoff(it, w, h, density.density) }
            val trailPath = Path()
            val clearPath = Path()
            val strokes = runoffs.map { Stroke(it.radius * .22f, cap = StrokeCap.Round) }
            val clear = palette.glass.copy(alpha = if (dark) .045f else .065f)
            val edge = palette.edge.copy(alpha = if (dark) .13f else .1f)
            val roadGlow = Brush.radialGradient(listOf(palette.warm.copy(alpha = if (dark) .075f else .045f), Color.Transparent), Offset.Zero, 1f)
            onDrawBehind {
                val t = time.floatValue
                val wind = .14f + .035f * sin(t * .17f)
                // Traffic stays out of focus. Its reflected glow travels with the lights.
                for (i in 0..2) {
                    val p = ((t * (.012f + i * .003f) + rainSeed(i, 130)) % 1.4f) - .2f
                    val x = p * w
                    val y = h * (.71f + i * .035f)
                    withTransform({ translate(x, y); scale(28f * density.density, 5f * density.density, Offset.Zero) }) { drawCircle(roadGlow, 1f, Offset.Zero) }
                    withTransform({ translate(x, y + h * .13f); scale(12f * density.density, h * .11f, Offset.Zero) }) { drawCircle(roadGlow, 1f, Offset.Zero, .5f) }
                }
                for (streak in streaks) {
                    val span = h + streak.length * 2f
                    val y = ((streak.phase * span + t * streak.speed) % span) - streak.length
                    val x = streak.x - (y - h * .5f) * wind
                    drawLine(palette.rain, Offset(x, y), Offset(x - wind * streak.length, y + streak.length),
                        streak.width, StrokeCap.Round, alpha = streak.alpha)
                }
                for (index in runoffs.indices) {
                    val runoff = runoffs[index]
                    val y = runoff.y(t)
                    val x = runoff.x(y)
                    // A curved, tapered wet track; no straight luminous ropes behind the drops.
                    trailPath.reset()
                    clearPath.reset()
                    val steps = 10
                    for (i in 0..steps) {
                        val progress = i.toFloat() / steps
                        val py = y - runoff.trail * (1f - progress)
                        val px = runoff.x(py)
                        if (i == 0) { trailPath.moveTo(px, py); clearPath.moveTo(px, py) }
                        else { trailPath.lineTo(px - runoff.radius * .33f * progress, py); clearPath.lineTo(px, py) }
                    }
                    for (i in steps downTo 0) {
                        val progress = i.toFloat() / steps
                        val py = y - runoff.trail * (1f - progress)
                        trailPath.lineTo(runoff.x(py) + runoff.radius * .33f * progress, py)
                    }
                    trailPath.close()
                    drawPath(trailPath, edge)
                    drawPath(clearPath, clear, style = strokes[index])
                    lens.draw(this, x, y, runoff.radius, runoff.stretch, 0f, .92f)
                }
            }
        }
    }
}

private class GlassBead(val at: Offset, val radius: Float, val stretch: Float, val tilt: Float)
private class RainStreak(val x: Float, val phase: Float, val speed: Float, val length: Float, val width: Float, val alpha: Float)

/** Unit-sized optical details are reused for tiny beads and larger moving drops. */
private class RainLens(private val city: ImageBitmap, width: Float, height: Float, private val palette: RainPalette) {
    private val sx = city.width / width
    private val sy = city.height / height
    private val body = Path().apply {
        moveTo(.05f, -1.03f); cubicTo(.57f, -.95f, .92f, -.42f, .87f, .25f)
        cubicTo(.88f, .86f, .4f, 1.02f, -.15f, .93f); cubicTo(-.86f, .92f, -1f, .5f, -.88f, -.05f)
        cubicTo(-.79f, -.59f, -.4f, -1.05f, .05f, -1.03f); close()
    }
    private val rim = Brush.radialGradient(0f to Color.Transparent, .65f to Color.Transparent,
        .86f to palette.edge.copy(alpha = .08f), 1f to palette.edge.copy(alpha = .46f), center = Offset(-.1f, -.17f), radius = 1.2f)
    private val light = Brush.radialGradient(0f to palette.highlight.copy(alpha = .56f), .22f to palette.highlight.copy(alpha = .13f),
        1f to Color.Transparent, center = Offset(-.38f, -.53f), radius = .54f)
    private val lip = Path().apply { moveTo(-.65f, .57f); cubicTo(-.3f, .92f, .29f, .99f, .62f, .64f) }
    private val crest = Path().apply { moveTo(-.58f, -.37f); cubicTo(-.5f, -.66f, -.32f, -.79f, -.06f, -.8f) }
    private val rimStroke = Stroke(.065f, cap = StrokeCap.Round)
    private val crestStroke = Stroke(.09f, cap = StrokeCap.Round)
    private val crop = IntSize(min(24, city.width), min(32, city.height))
    private val lensSize = IntSize(128, 128)
    private val lensOffset = IntOffset(-64, -64)

    private val columns = 6
    private val rows = 4
    private val tile = IntSize(64, 80)
    private val atlas = ImageBitmap(tile.width * columns, tile.height * rows).also { bitmap ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            for (row in 0 until rows) for (column in 0 until columns) {
                withTransform({ translate((column + .5f) * tile.width, (row + .5f) * tile.height); scale(25f, 32f, Offset.Zero) }) {
                    val sampleX = (column + .5f) / columns * width
                    val sampleY = (row + .5f) / rows * height
                    drawOptics(this, sampleX, sampleY)
                }
            }
        }
    }

    /** Pre-rendered lenses keep curved clipping and refraction off the GPU animation path. */
    fun draw(scope: DrawScope, x: Float, y: Float, radius: Float, stretch: Float, tilt: Float, alpha: Float) = with(scope) {
        val column = (x * sx / city.width * columns).toInt().coerceIn(0, columns - 1)
        val row = (y * sy / city.height * rows).toInt().coerceIn(0, rows - 1)
        val width = (radius * tile.width / 25f).roundToInt().coerceAtLeast(2)
        val height = (radius * stretch * tile.height / 32f).roundToInt().coerceAtLeast(2)
        withTransform({ translate(x, y); rotate(tilt, Offset.Zero) }) {
            drawImage(atlas, srcOffset = IntOffset(column * tile.width, row * tile.height), srcSize = tile,
                dstOffset = IntOffset(-width / 2, -height / 2), dstSize = IntSize(width, height), alpha = alpha, filterQuality = FilterQuality.Low)
        }
    }
    private fun drawOptics(scope: DrawScope, x: Float, y: Float) = with(scope) {
        val sample = IntOffset((x * sx - crop.width / 2f).roundToInt().coerceIn(0, city.width - crop.width),
            (y * sy - crop.height / 2f).roundToInt().coerceIn(0, city.height - crop.height))
        drawPath(body, palette.edge, alpha = .1f)
        clipPath(body) {
            // Inverted city samples provide the refracted colour beneath the curved water surface.
            withTransform({ scale(-1f / 64f, -1f / 64f, Offset.Zero) }) {
                drawImage(city, srcOffset = sample, srcSize = crop, dstOffset = lensOffset, dstSize = lensSize,
                    alpha = if (palette.night) .67f else .55f, filterQuality = FilterQuality.Low)
            }
            drawPath(body, rim)
            drawPath(body, light)
        }
        drawPath(lip, palette.glass, alpha = .28f, style = rimStroke)
        drawPath(crest, palette.highlight, alpha = .5f, style = crestStroke)
    }
}

/** Defocus is baked once at a bounded resolution; there is no full-screen blur on a frame. */
private fun rainCity(width: Float, height: Float, density: Density, direction: LayoutDirection, palette: RainPalette): ImageBitmap {
    val ratio = min(1f, min(360f / width, 900f / height))
    val w = (width * ratio).roundToInt().coerceAtLeast(1)
    val h = (height * ratio).roundToInt().coerceAtLeast(1)
    val image = ImageBitmap(w, h)
    CanvasDrawScope().draw(density, direction, Canvas(image), Size(w.toFloat(), h.toFloat())) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        drawRect(Brush.verticalGradient(0f to palette.sky[0], .32f to palette.sky[1], .69f to palette.sky[2], 1f to palette.sky[3]))
        val mist = Brush.radialGradient(listOf(palette.mist.copy(alpha = .45f), Color.Transparent), Offset.Zero, 1f)
        val warm = Brush.radialGradient(0f to palette.warm.copy(alpha = .62f), .18f to palette.warm.copy(alpha = .3f),
            .6f to palette.warm.copy(alpha = .04f), 1f to Color.Transparent, center = Offset.Zero, radius = 1f)
        val cool = Brush.radialGradient(0f to palette.cool.copy(alpha = .5f), .25f to palette.cool.copy(alpha = .18f),
            1f to Color.Transparent, center = Offset.Zero, radius = 1f)
        val bokeh = listOf(palette.warm, palette.cool).map { color -> Brush.radialGradient(
            0f to color.copy(alpha = .04f), .55f to color.copy(alpha = .12f), .8f to color.copy(alpha = .18f),
            .94f to color.copy(alpha = .045f), 1f to Color.Transparent, center = Offset.Zero, radius = 1f) }
        // Distant skyline, partly swallowed by the fog. Each building has its own window spacing.
        for (i in 0..13) {
            val x = (i / 13f - .07f) * wf
            val bw = (.065f + rainSeed(i, 150) * .075f) * wf
            val top = (.39f + rainSeed(i, 151) * .25f) * hf
            drawRect(palette.silhouette, Offset(x, top), Size(bw, hf - top), alpha = .28f + rainSeed(i, 152) * .2f)
            val columns = 3 + (rainSeed(i, 153) * 4).toInt()
            val rowHeight = hf * (.014f + rainSeed(i, 154) * .008f)
            var y = top + rowHeight
            var row = 0
            while (y < hf * .77f) {
                for (col in 0 until columns) {
                    val seed = i * 7919 + row * 37 + col
                    if (rainSeed(seed, 155) > .65f) {
                        val px = x + (col + .5f) * bw / columns
                        val brush = if (rainSeed(seed, 156) < .73f) warm else cool
                        val r = wf * (.007f + rainSeed(seed, 157) * .008f)
                        withTransform({ translate(px, y); scale(r, r * .8f, Offset.Zero) }) { drawCircle(brush, 1f, Offset.Zero, if (palette.night) .52f else .23f) }
                    }
                }
                y += rowHeight; row++
            }
        }
        val street = Path().apply {
            moveTo(wf * .5f, hf * .64f)
            cubicTo(wf * .44f, hf * .75f, wf * .24f, hf * .9f, -wf * .14f, hf)
            lineTo(wf * 1.14f, hf)
            cubicTo(wf * .77f, hf * .9f, wf * .58f, hf * .75f, wf * .5f, hf * .64f)
            close()
        }
        drawPath(street, Brush.verticalGradient(listOf(palette.sky[2].copy(alpha = .15f), palette.sky[3]), startY = hf * .64f, endY = hf))
        withTransform({ translate(wf * .52f, hf * .63f); scale(wf * .8f, hf * .34f, Offset.Zero) }) { drawCircle(mist, 1f, Offset.Zero) }
        // Street lamps and small, irregular reflections on the wet road.
        for (i in 0..16) {
            val x = rainSeed(i, 160) * wf
            val y = hf * (.58f + rainSeed(i, 161) * .22f)
            val r = wf * (.015f + rainSeed(i, 162).pow(2f) * .062f)
            val brush = if (i % 4 == 0) cool else warm
            withTransform({ translate(x, y); scale(r, r * .88f, Offset.Zero) }) { drawCircle(brush, 1f, Offset.Zero, .7f) }
            for (j in 0..7) {
                val ry = y + hf * (.025f + j * .018f)
                val rx = x + (rainSeed(i * 31 + j, 163) - .5f) * r
                withTransform({ translate(rx, ry); scale(r * (.6f + j * .08f), hf * .006f, Offset.Zero) }) {
                    drawCircle(brush, 1f, Offset.Zero, .14f * (1f - j / 9f))
                }
            }
        }
        for (i in 0..35) {
            val depth = rainSeed(i, 180).pow(.7f)
            val y = hf * (.67f + depth * .34f)
            val x = wf * (.5f + (rainSeed(i, 181) - .5f) * depth * .9f)
            val brush = if (i % 3 == 0) cool else warm
            withTransform({ translate(x, y); scale(wf * (.006f + depth * .018f), hf * .002f, Offset.Zero) }) {
                drawCircle(brush, 1f, Offset.Zero, .12f + depth * .08f)
            }
        }
        for (i in 0..11) {
            val x = rainSeed(i, 170) * wf
            val y = hf * (.28f + rainSeed(i, 171) * .68f)
            val r = wf * (.025f + rainSeed(i, 172) * .065f)
            withTransform({ translate(x, y); scale(r, r, Offset.Zero) }) { drawCircle(bokeh[i % 2], 1f, Offset.Zero, if (palette.night) .5f else .3f) }
        }
        // Condensation and a subtle vignette keep the weather behind readable UI.
        drawRect(Brush.verticalGradient(listOf(palette.sky[0].copy(alpha = .08f), Color.Transparent, palette.sky[3].copy(alpha = .25f))))
        val vignette = Brush.radialGradient(0f to Color.Transparent, .55f to Color.Transparent,
            1f to palette.sky[0].copy(alpha = .65f), center = Offset(wf * .5f, hf * .48f), radius = hf * .7f)
        drawRect(vignette)
    }
    return softenRainCity(image)
}

/** Small separable blur, once on the CPU. Foreground lenses stay sharp; the far city stays out of focus. */
private fun softenRainCity(image: ImageBitmap): ImageBitmap {
    val w = image.width
    val h = image.height
    val pixels = IntArray(w * h)
    val horizontal = IntArray(pixels.size)
    val blurred = IntArray(pixels.size)
    image.asAndroidBitmap().getPixels(pixels, 0, w, 0, 0, w, h)
    val weights = intArrayOf(1, 4, 6, 4, 1)
    fun filter(source: IntArray, destination: IntArray, vertical: Boolean) {
        for (y in 0 until h) for (x in 0 until w) {
            var red = 0; var green = 0; var blue = 0
            for (tap in -2..2) {
                val px = if (vertical) x else (x + tap).coerceIn(0, w - 1)
                val py = if (vertical) (y + tap).coerceIn(0, h - 1) else y
                val color = source[py * w + px]
                val weight = weights[tap + 2]
                red += ((color ushr 16) and 255) * weight
                green += ((color ushr 8) and 255) * weight
                blue += (color and 255) * weight
            }
            destination[y * w + x] = (255 shl 24) or ((red / 16) shl 16) or ((green / 16) shl 8) or (blue / 16)
        }
    }
    filter(pixels, horizontal, false)
    filter(horizontal, blurred, true)
    return android.graphics.Bitmap.createBitmap(blurred, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
