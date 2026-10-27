package com.gdhapp.client.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gdhapp.client.domain.model.BackgroundScene
import com.gdhapp.client.domain.model.ClientSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The chosen app background: drifting cats or a full scene. */
@Composable
fun AppBackground(settings: ClientSettings, motionEnabled: Boolean, modifier: Modifier = Modifier) {
    if (!settings.backgroundPictures) return
    val dark = isDarkAppearance(settings)
    if (settings.backgroundScene == BackgroundScene.CATS) {
        DriftingBackground(true, motionEnabled, dark, modifier, settings.backgroundDensity, settings.backgroundOpacity)
    } else SceneBackground(settings.backgroundScene, rememberBackgroundClock(motionEnabled, if (settings.backgroundScene == BackgroundScene.RAIN) 60 else 30), dark, dark && settings.amoled, modifier)
}

/**
 * Scenes are vector drawing, sharp at any size and free of image assets. Still layers record once per size;
 * the moving layer has its own render node, so a frame redraws only lights and particles.
 */
@Composable
internal fun SceneBackground(scene: BackgroundScene, time: FloatState, dark: Boolean, amoled: Boolean, modifier: Modifier = Modifier, fadeTop: Boolean = true) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.clipToBounds()) {
        when (scene) {
            BackgroundScene.AURORA -> Aurora(time, dark, amoled)
            BackgroundScene.SUNSET -> Sunset(time, dark, amoled)
            BackgroundScene.SPACE -> Space(time, dark, amoled)
            BackgroundScene.SAKURA -> Sakura(time, dark, amoled)
            BackgroundScene.NEON -> Neon(time, dark, amoled)
            BackgroundScene.RAIN -> RainBackground(time, dark, amoled)
            BackgroundScene.OCEAN -> OceanBackground(time, dark, amoled)
            BackgroundScene.FIREFLIES -> FirefliesBackground(time, dark, amoled)
            BackgroundScene.SNOW -> SnowBackground(time, dark, amoled)
            BackgroundScene.CATS -> Unit
        }
        // The top bar keeps the plain theme color, so the scene fades in below it instead of starting at a seam.
        if (fadeTop) Still {
            val fade = minOf(56.dp.toPx(), size.height * .12f)
            val brush = Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)), endY = fade)
            onDrawBehind { drawRect(brush, size = Size(size.width, fade)) }
        }
    }
}

@Composable
internal fun BoxScope.Still(draw: CacheDrawScope.() -> DrawResult) = Spacer(Modifier.matchParentSize().drawWithCache(draw))

@Composable
internal fun BoxScope.Moving(draw: CacheDrawScope.() -> DrawResult) = Spacer(Modifier.matchParentSize().graphicsLayer().drawWithCache(draw))

/** Repeatable pseudo-random numbers in [0, 1): every launch draws the same sky. */
internal fun rand(i: Int, salt: Int): Float {
    val x = sin(i * 127.1 + salt * 311.7) * 43758.5453
    return (x - floor(x)).toFloat()
}

internal fun Float.wrap(span: Float) = (this % span + span) % span

internal const val DEGREES = .017453292f

internal class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float)

/** Stars between [top] and [bottom] (fractions of the height); the count follows the area, so tablets are not sparse. */
internal fun CacheDrawScope.starField(salt: Int, top: Float, bottom: Float, perDp: Float, max: Int): List<Star> {
    val count = (size.width * size.height / (density * density) * perDp).roundToInt().coerceIn(8, max)
    return List(count) { i ->
        Star(rand(i, salt) * size.width, (top + rand(i, salt + 1) * (bottom - top)) * size.height,
            (.45f + rand(i, salt + 2).pow(3) * 1.2f).dp.toPx(), rand(i, salt + 3) * 6.3f, .5f + rand(i, salt + 4) * 1.8f)
    }
}

/** [fall] moves bigger, nearer stars faster: pixels per second for each pixel of radius. */
internal fun DrawScope.twinkle(stars: List<Star>, color: Color, t: Float, strength: Float = 1f, fall: Float = 0f) {
    for (star in stars) {
        val y = if (fall == 0f) star.y else (star.y + t * fall * star.radius).wrap(size.height)
        drawCircle(color, star.radius, Offset(star.x, y), strength * (.35f + .65f * (.5f + .5f * sin(t * star.speed + star.phase))))
    }
}

/** Four-pointed glints from unit-sized brushes scaled into place, so frames allocate nothing. */
internal class Sparkle(color: Color) {
    private val ray = Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color, color.copy(alpha = 0f)), startX = -1f, endX = 1f)
    private val core = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = Offset.Zero, radius = 1f)

    fun draw(scope: DrawScope, x: Float, y: Float, length: Float, alpha: Float) = with(scope) {
        val thin = .7.dp.toPx()
        translate(x, y) {
            scale(length / 2f, thin, Offset.Zero) { drawRect(ray, Offset(-1f, -1f), Size(2f, 2f), alpha) }
            rotate(90f, Offset.Zero) { scale(length / 2f, thin, Offset.Zero) { drawRect(ray, Offset(-1f, -1f), Size(2f, 2f), alpha) } }
            scale(length * .16f, length * .16f, Offset.Zero) { drawCircle(core, 1f, Offset.Zero, alpha) }
        }
    }
}

/** Now and then a meteor crosses a different part of the sky; never in the first seconds or in a still preview. */
private fun DrawScope.meteor(t: Float, period: Float, salt: Int, color: Color) {
    val cycle = floor(t / period).toInt()
    val p = (t - cycle * period - period * .4f) / .9f
    if (p !in 0f..1.3f) return
    val angle = ((if (rand(cycle, salt) < .5f) 152f else 28f) + rand(cycle, salt + 1) * 10f - 5f) * DEGREES
    val direction = Offset(cos(angle), sin(angle))
    val start = Offset(size.width * (.15f + .7f * rand(cycle, salt + 2)), size.height * (.05f + .25f * rand(cycle, salt + 3)))
    val head = start + direction * (size.width * .45f * min(1f, p))
    val tail = head - direction * (size.width * .16f * sin(min(1f, p) * PI.toFloat()) + 3.dp.toPx())
    drawLine(Brush.linearGradient(listOf(color.copy(alpha = 0f), color), tail, head), tail, head, 1.5.dp.toPx(), StrokeCap.Round,
        alpha = if (p > 1f) 1f - (p - 1f) / .3f else 1f)
}

// ---------------------------------------------------------------- aurora

private class Curtain(val base: Float, val sway: Float, val height: Float, val speed: Float, val phase: Float, val strength: Float, val colors: List<Color>)

// Fractions of the screen, after the promo's aurora cut. Colors run from the lower edge up.
private val curtains = listOf(
    Curtain(.427f, .047f, .375f, .32f, 2.1f, .55f, listOf(Color(0xFF42DFBD), Color(0xFF53B7FF), Color(0xFF8A7BFF), Color(0xFFF194D8))),
    Curtain(.49f, .036f, .31f, .42f, 0f, .85f, listOf(Color(0xFF3FF2B2), Color(0xFF42DFBD), Color(0xFF538AFF), Color(0xFFF194D8))),
    Curtain(.563f, .026f, .21f, .55f, 4.2f, .6f, listOf(Color(0xFF66F7C4), Color(0xFF42DFBD), Color(0xFF5B8CFF), Color(0xFFC88EF0))),
)

@Composable
private fun BoxScope.Aurora(time: FloatState, dark: Boolean, amoled: Boolean) {
    Still {
        val sky = when {
            amoled -> Brush.verticalGradient(0f to Color.Black, .45f to Color(0xFF01040C), .7f to Color(0xFF041424), .8f to Color(0xFF06202E), 1f to Color(0xFF02080F))
            dark -> Brush.verticalGradient(0f to Color(0xFF060A16), .34f to Color(0xFF050D24), .62f to Color(0xFF08203D), .76f to Color(0xFF0D3348), 1f to Color(0xFF071A2C))
            else -> Brush.verticalGradient(0f to Color(0xFFF3F6FC), .4f to Color(0xFFE5EEFA), .64f to Color(0xFFD8ECF0), .78f to Color(0xFFD0EBE4), 1f to Color(0xFFE1ECF2))
        }
        // The Milky Way, and the sky glowing green under the curtains.
        val band = Brush.radialGradient(0f to Color(0x21AABEFF), .45f to Color(0x0D96AAFF), .7f to Color.Transparent, center = Offset.Zero, radius = 1f)
        val glow = Brush.radialGradient(0f to Color(0xFF42DFBD).copy(alpha = if (dark) .16f else .2f), .45f to Color(0xFF538AFF).copy(alpha = if (dark) .08f else .1f),
            .75f to Color.Transparent, center = Offset.Zero, radius = 1f)
        onDrawBehind {
            drawRect(sky)
            if (dark) withTransform({ translate(size.width * .51f, size.height * .29f); rotate(-28f, Offset.Zero); scale(size.width * .8f, size.height * .095f, Offset.Zero) }) {
                drawCircle(band, 1f, Offset.Zero)
            }
            withTransform({ translate(size.width * .5f, size.height * .55f); scale(size.width * .69f, size.height * .24f, Offset.Zero) }) { drawCircle(glow, 1f, Offset.Zero) }
        }
    }
    Moving {
        // Curtains render about 120 px across; bilinear upscaling softens their strips into rays.
        val step = (size.width / 120f).coerceAtLeast(1f)
        val low = ImageBitmap((size.width / step).roundToInt().coerceAtLeast(1), (size.height / step).roundToInt().coerceAtLeast(1))
        val lowCanvas = Canvas(low)
        val lowScope = CanvasDrawScope()
        val lowSize = Size(low.width.toFloat(), low.height.toFloat())
        val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val rays = curtains.map { c ->
            Brush.verticalGradient(0f to c.colors[3].copy(alpha = 0f), .2f to c.colors[3].copy(alpha = .3f), .48f to c.colors[2].copy(alpha = .45f),
                .75f to c.colors[1].copy(alpha = .75f), .965f to c.colors[0], 1f to c.colors[0].copy(alpha = 0f), startY = 0f, endY = 100f)
        }
        val snow = Color(0xFFF2F7FF)
        val stars = if (dark) starField(31, 0f, .8f, 1f / 1900f, 220) else emptyList()
        val sparkle = Sparkle(snow)
        val glints = if (dark) List(7) { i -> Offset(size.width * (.06f + rand(i, 41) * .88f), size.height * (.05f + rand(i, 42) * .42f)) } else emptyList()
        onDrawBehind {
            val t = time.floatValue
            twinkle(stars, snow, t)
            glints.forEachIndexed { i, at ->
                sparkle.draw(this, at.x, at.y, (14f + rand(i, 43) * 12f).dp.toPx() * (.85f + .15f * sin(t * 2.3f + i)), .55f + .45f * sin(t * 1.7f + i * 2.1f))
            }
            if (dark) meteor(t, 11f, 7, Color(0xFFDCF5FF))
            val s = t * .5f
            lowScope.draw(this, layoutDirection, lowCanvas, lowSize) {
                drawRect(Color.Black, blendMode = BlendMode.Clear)
                curtains.forEachIndexed { index, c ->
                    var x = 0f
                    while (x < lowSize.width) {
                        val xp = x / lowSize.width * 1080f
                        val base = (c.base + c.sway * (sin(xp * .0042f + s * c.speed + c.phase) + .45f * sin(xp * .011f - s * c.speed * 1.7f + c.phase * 2))) * lowSize.height
                        val height = c.height * (.45f + .55f * (.5f + .5f * sin(xp * .0065f + s * c.speed * .8f + c.phase * 3))) * lowSize.height
                        val ray = .5f + .5f * sin(xp * .09f + s * 2.4f + c.phase) * sin(xp * .031f - s * 1.3f + c.phase)
                        val breadth = .55f + .45f * sin(xp * .0024f + s * .35f + c.phase)
                        withTransform({ translate(x, base - height); scale(1f, height / 100f, Offset.Zero) }) {
                            drawRect(rays[index], size = Size(1f, 100f), alpha = c.strength * (.3f + .7f * ray) * breadth)
                        }
                        x += 1f
                    }
                }
            }
            drawImage(low, dstSize = full, alpha = if (dark) 1f else .75f)
        }
    }
    Still {
        val w = size.width
        val h = size.height
        val sx = w / 1080f
        val sy = h / 1920f
        fun ridge(base: Float, seed: Int, every: Int, tall: (Float) -> Float) = Path().apply {
            moveTo(-60f * sx, h + 2f)
            for (x in -60..1140 step every) lineTo(x * sx, (base - tall(x.toFloat()) * (.55f + .45f * rand(x, seed))) * sy)
            lineTo(1140f * sx, h + 2f)
            close()
        }
        val back = ridge(1450f, 51, 44) { x -> 150f + 110f * sin(x * .006f + 1f) + 70f * sin(x * .017f) }
        val front = ridge(1560f, 52, 70) { x -> 70f + 260f * (abs(x - 540f) / 540f).pow(1.6f) }
        // Firs along the near ridge.
        val firs = Path()
        var i = 0
        var x = -20f * sy
        while (x < w + 20f * sy) {
            val y = (1568f - (70f + 260f * (abs(x / sx - 540f) / 540f).pow(1.6f)) * .75f) * sy
            val tall = (40f + rand(i, 62) * 70f) * sy
            val half = tall * .36f
            firs.moveTo(x, y - tall)
            firs.lineTo(x + half * .5f, y - tall * .62f); firs.lineTo(x + half * .25f, y - tall * .62f)
            firs.lineTo(x + half * .8f, y - tall * .28f); firs.lineTo(x + half * .4f, y - tall * .28f)
            firs.lineTo(x + half, y); firs.lineTo(x - half, y)
            firs.lineTo(x - half * .4f, y - tall * .28f); firs.lineTo(x - half * .8f, y - tall * .28f)
            firs.lineTo(x - half * .25f, y - tall * .62f); firs.lineTo(x - half * .5f, y - tall * .62f)
            firs.close()
            i++
            x += (18f + rand(i, 61) * 14f) * sy
        }
        val far = when {
            amoled -> Brush.verticalGradient(0f to Color(0xFF07172A), .25f to Color(0xFF040B16), 1f to Color(0xFF020509), startY = h * .66f, endY = h)
            dark -> Brush.verticalGradient(0f to Color(0xFF0E2A40), .25f to Color(0xFF081626), 1f to Color(0xFF050C18), startY = h * .66f, endY = h)
            else -> Brush.verticalGradient(0f to Color(0xFFC5D7E7), .25f to Color(0xFFB5CADD), 1f to Color(0xFFA8BED3), startY = h * .66f, endY = h)
        }
        val mist = Brush.verticalGradient(listOf(Color.Transparent, if (dark) Color(0x1496E6DC) else Color(0x80FFFFFF), Color.Transparent), startY = h * .708f, endY = h * .823f)
        val near = when { amoled -> Color.Black; dark -> Color(0xFF030812); else -> Color(0xFF8FA8BF) }
        val rim = Color(0xFF6FF0C8).copy(alpha = if (dark) .22f else .55f)
        onDrawBehind {
            drawPath(back, far)
            drawPath(back, rim, style = Stroke(1.dp.toPx()))
            drawRect(mist, Offset(0f, h * .708f), Size(w, h * .115f))
            drawPath(front, near)
            drawPath(firs, near)
        }
    }
}

// ---------------------------------------------------------------- sunset

private data class SunsetColors(val top: Color, val upper: Color, val middle: Color, val low: Color, val seaTop: Color, val seaMid: Color, val seaBottom: Color,
    val glow: Color, val cloud: Color, val sun: Color, val sunEdge: Color, val shimmer: Color, val land: Color, val gull: Color)

private val goldenHour = SunsetColors(Color(0xFFE9B6CB), Color(0xFFF7C6BF), Color(0xFFFFD3A6), Color(0xFFFFB884), Color(0xFFF7AA88), Color(0xFFE2909C),
    Color(0xFFBF86AE), Color(0xE6FFE4B8), Color(0xCCFFF4EE), Color(0xFFFFF6E2), Color(0xFFFFCF96), Color(0xFFFFF4DE), Color(0x99D98BA0), Color(0x8C6E4A62))
private val dusk = SunsetColors(Color(0xFF1B1840), Color(0xFF3B2862), Color(0xFF6E3A68), Color(0xFFB9585C), Color(0xFFA8535A), Color(0xFF55315A),
    Color(0xFF1C1433), Color(0x8CFF966E), Color(0x8CFF968C), Color(0xFFFFD2A0), Color(0xFFFF874F), Color(0xFFFFC9A0), Color(0xFF2B1B3B), Color(0xBF24152E))

private class Cloud(val x: Float, val y: Float, val width: Float, val height: Float, val speed: Float, val alpha: Float)

private const val HORIZON = .62f

@Composable
private fun BoxScope.Sunset(time: FloatState, dark: Boolean, amoled: Boolean) {
    val c = when { amoled -> dusk.copy(top = Color.Black, upper = Color(0xFF241640), seaBottom = Color.Black); dark -> dusk; else -> goldenHour }
    fun CacheDrawScope.sunRadius() = minOf(size.width * (if (dark) .15f else .13f), size.height * .08f)
    Still {
        val w = size.width
        val horizon = size.height * HORIZON
        val radius = sunRadius()
        val sun = Offset(w * .5f, if (dark) horizon + size.height * .012f else horizon - size.height * .085f)
        val sky = Brush.verticalGradient(0f to c.top, .3f to c.upper, .55f to c.middle, 1f to c.low, startY = 0f, endY = horizon)
        val halo = Brush.radialGradient(0f to c.glow, .23f to Color(0xFFFFC496).copy(alpha = if (dark) .45f else .5f), .6f to Color(0xFFFFAA82).copy(alpha = .18f),
            1f to Color.Transparent, center = sun, radius = w * .95f)
        // Soft rays: thin wedges in one path, faded by a single radial brush.
        val reach = w * 1.1f
        val rays = Path().apply {
            for (k in 0 until 30) {
                val a = k * 12f * DEGREES
                val b = a + 3.5f * DEGREES
                moveTo(sun.x, sun.y); lineTo(sun.x + cos(a) * reach, sun.y + sin(a) * reach); lineTo(sun.x + cos(b) * reach, sun.y + sin(b) * reach); close()
            }
        }
        val rayBrush = Brush.radialGradient(0f to Color(0x00FFFCF0), .15f to Color(0x3DFFFCF0), 1f to Color(0x00FFFCF0), center = sun, radius = reach)
        val disc = Brush.radialGradient(0f to c.sun, .45f to c.sun, 1f to c.sunEdge, center = sun - Offset(0f, radius * .1f), radius = radius)
        onDrawBehind {
            drawRect(sky, size = Size(w, horizon + 1f))
            drawPath(rays, rayBrush, alpha = if (dark) .3f else 1f)
            drawCircle(halo, w * .95f, sun)
            drawCircle(disc, radius, sun)
        }
    }
    Moving {
        val w = size.width
        val h = size.height
        val cloud = Brush.radialGradient(0f to c.cloud, .7f to c.cloud.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
        val clouds = List(6) { i ->
            Cloud(rand(i, 3) * 1.8f, .1f + rand(i, 4) * (if (dark) .3f else .38f), .45f + rand(i, 5) * .4f, .045f + rand(i, 6) * .03f, .006f + rand(i, 7) * .012f, .5f + rand(i, 8) * .45f)
        }
        val stars = if (dark) starField(23, 0f, .34f, 1f / 4000f, 70) else emptyList()
        val gull = Path().apply { moveTo(-1f, -.1f); cubicTo(-.7f, -.45f, -.3f, -.5f, 0f, 0f); cubicTo(.3f, -.5f, .7f, -.45f, 1f, -.1f) }
        val wing = Stroke(.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            val t = time.floatValue
            twinkle(stars, Color(0xFFFFF1E6), t, .8f)
            clouds.forEach { cl ->
                val x = (cl.x * w + t * cl.speed * w).wrap(w * 1.8f) - w * .4f
                for (k in 0..2) withTransform({
                    translate(x + cl.width * w * (.3f + .2f * k), cl.y * h + cl.height * h * .12f * k)
                    scale(cl.width * w * .36f * (1f - .22f * k), cl.height * h * .5f * (1f - .15f * k), Offset.Zero)
                }) { drawCircle(cloud, 1f, Offset.Zero, cl.alpha) }
            }
            for (i in 0 until 3) {
                val span = (6f + rand(i, 95) * 4f).dp.toPx()
                val x = (rand(i, 96) * w * 1.4f + t * (.02f + rand(i, 97) * .015f) * w).wrap(w * 1.4f) - w * .2f
                val y = h * (.3f + rand(i, 98) * .18f) + sin(t * .6f + i * 2f) * h * .008f
                val flap = .25f + .75f * (.5f + .5f * sin(t * 3.4f + i * 1.9f))
                withTransform({ translate(x, y); scale(span, span * flap, Offset.Zero) }) { drawPath(gull, c.gull, style = wing) }
            }
        }
    }
    Still {
        val w = size.width
        val h = size.height
        val horizon = h * HORIZON
        val sea = Brush.verticalGradient(0f to c.seaTop, .34f to c.seaMid, 1f to c.seaBottom, startY = horizon, endY = h)
        val path = Brush.radialGradient(0f to Color(0xFFFFEECD).copy(alpha = if (dark) .3f else .6f), .72f to Color.Transparent, center = Offset.Zero, radius = 1f)
        val haze = Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFFFF2E1).copy(alpha = if (dark) .25f else .5f), Color.Transparent),
            startY = horizon - h * .014f, endY = horizon + h * .014f)
        val islands = Path().apply {
            moveTo(w * .7f, horizon + 1f); cubicTo(w * .78f, horizon - h * .03f, w * .92f, horizon - h * .035f, w * 1.05f, horizon + 1f); close()
            moveTo(-w * .05f, horizon + 1f); cubicTo(w * .02f, horizon - h * .016f, w * .12f, horizon - h * .014f, w * .2f, horizon + 1f); close()
        }
        onDrawBehind {
            drawRect(sea, Offset(0f, horizon), Size(w, h - horizon))
            clipRect(top = horizon) {
                withTransform({ translate(w * .5f, horizon); scale(w * .38f, (h - horizon) * .6f, Offset.Zero) }) { drawCircle(path, 1f, Offset.Zero) }
            }
            drawPath(islands, c.land)
            drawRect(haze, Offset(0f, horizon - h * .014f), Size(w, h * .028f))
        }
    }
    Moving {
        val w = size.width
        val h = size.height
        val horizon = h * HORIZON
        val radius = sunRadius()
        val sparkles = List(26) { i -> Offset(rand(i, 85) * w, horizon + (.04f + .96f * rand(i, 86).pow(1.4f)) * (h - horizon)) }
        onDrawBehind {
            val t = time.floatValue
            // The path of light under the sun, widening toward the shore.
            for (i in 0 until 40) {
                val f = i / 39f
                val y = horizon + 2f + f.pow(1.5f) * (h - horizon) * .9f
                val half = radius * (.3f + 1.8f * f) * (.45f + .55f * rand(i, 81))
                val dx = sin(t * .7f + i * .9f) * radius * .25f * f
                val shimmer = .5f + .5f * sin(t * 2.2f + i * 1.7f)
                val thick = (1f + 2.2f * f).dp.toPx()
                drawRoundRect(c.shimmer, Offset(w * .5f - half + dx, y), Size(half * 2f, thick), CornerRadius(thick / 2f),
                    alpha = (1f - .6f * f) * (.25f + .6f * shimmer) * if (dark) .6f else .9f)
            }
            sparkles.forEachIndexed { i, at ->
                val glint = sin(t * (1.3f + rand(i, 87)) + i * 2.4f)
                if (glint > .3f) drawRoundRect(c.shimmer, at, Size((6f + rand(i, 88) * 10f).dp.toPx(), 1.dp.toPx()), CornerRadius(1.dp.toPx()), alpha = (glint - .3f) * .7f)
            }
        }
    }
}

// ---------------------------------------------------------------- space

private class Nebula(val x: Float, val y: Float, val rx: Float, val ry: Float, val angle: Float, val color: Color, val darkAlpha: Float, val lightAlpha: Float)

private val nebulae = listOf(
    Nebula(.25f, .3f, .75f, .24f, -20f, Color(0xFF7B5CFF), .22f, .3f),
    Nebula(.78f, .48f, .65f, .2f, 15f, Color(0xFFF06FB5), .15f, .26f),
    Nebula(.38f, .63f, .55f, .15f, -8f, Color(0xFF3FC9D9), .12f, .22f),
    Nebula(.86f, .16f, .42f, .14f, 25f, Color(0xFF4D7CFF), .15f, .22f),
)

@Composable
private fun BoxScope.Space(time: FloatState, dark: Boolean, amoled: Boolean) {
    Still {
        val base = when {
            amoled -> Brush.verticalGradient(listOf(Color.Black, Color(0xFF04030C)))
            dark -> Brush.verticalGradient(listOf(Color(0xFF070816), Color(0xFF0A0B1F), Color(0xFF0E0B25)))
            else -> Brush.verticalGradient(listOf(Color(0xFFF5F3FF), Color(0xFFEDE9FF), Color(0xFFF8EEF8)))
        }
        val clouds = nebulae.map { n ->
            val alpha = if (dark) n.darkAlpha else n.lightAlpha
            Brush.radialGradient(0f to n.color.copy(alpha = alpha), .5f to n.color.copy(alpha = alpha * .35f), 1f to Color.Transparent, center = Offset.Zero, radius = 1f)
        }
        onDrawBehind {
            drawRect(base)
            nebulae.forEachIndexed { i, n ->
                withTransform({ translate(size.width * n.x, size.height * n.y); rotate(n.angle, Offset.Zero); scale(size.width * n.rx, size.height * n.ry, Offset.Zero) }) {
                    drawCircle(clouds[i], 1f, Offset.Zero)
                }
            }
        }
    }
    Moving {
        val star = if (dark) Color(0xFFF2F4FF) else Color(0xFF7469CF)
        val stars = starField(57, 0f, 1f, 1f / 1500f, 260)
        val sparkle = Sparkle(star)
        val glints = List(5) { i -> Offset(size.width * (.08f + rand(i, 47) * .84f), size.height * (.06f + rand(i, 48) * .6f)) }
        onDrawBehind {
            val t = time.floatValue
            twinkle(stars, star, t, if (dark) 1f else .6f, fall = 2.5f)
            glints.forEachIndexed { i, at -> sparkle.draw(this, at.x, at.y, (12f + rand(i, 49) * 10f).dp.toPx(), (.5f + .5f * sin(t * 1.4f + i * 2.3f)) * if (dark) 1f else .7f) }
            if (dark) meteor(t, 9f, 13, Color(0xFFE6E9FF))
        }
    }
    Still {
        val w = size.width
        val h = size.height
        val r = minOf(w * .3f, h * .17f)
        val c = Offset(w * .84f, h * .83f)
        val light = if (dark) Color(0xFFA192FF) else Color(0xFFFFE3EF)
        val mid = if (dark) Color(0xFF4B3BA8) else Color(0xFFD5B9F2)
        val deep = if (dark) Color(0xFF140E38) else Color(0xFF9A82D6)
        val body = Brush.radialGradient(0f to light, .55f to mid, 1f to deep, center = c + Offset(-r * .45f, -r * .5f), radius = r * 1.75f)
        val air = Brush.radialGradient(0f to mid.copy(alpha = .45f), .8f to mid.copy(alpha = .45f), 1f to Color.Transparent, center = c, radius = r * 1.25f)
        val shade = Brush.radialGradient(.55f to Color.Transparent, 1f to (if (dark) Color.Black else deep).copy(alpha = if (dark) .55f else .35f),
            center = c + Offset(-r * .35f, -r * .4f), radius = r * 1.6f)
        val ring = Brush.horizontalGradient(listOf(light.copy(alpha = .15f), light.copy(alpha = .8f), light.copy(alpha = .3f)), startX = c.x - r * 1.8f, endX = c.x + r * 1.8f)
        val band = (if (dark) Color.White else deep).copy(alpha = .08f)
        val disc = Path().apply { addOval(Rect(c, r)) }
        val moon = Offset(w * .16f, h * .79f)
        val moonRadius = minOf(w * .035f, h * .02f)
        val moonBrush = Brush.radialGradient(listOf(if (dark) Color(0xFFDCD8EE) else Color.White, if (dark) Color(0xFF5E5A78) else Color(0xFFC9BDE8)),
            center = moon + Offset(-moonRadius * .4f, -moonRadius * .4f), radius = moonRadius * 1.6f)
        // The far half of the ring passes behind the planet, the near half in front.
        fun DrawScope.ringHalf(near: Boolean) = rotate(-18f, c) {
            clipRect(c.x - r * 2f, if (near) c.y else c.y - r, c.x + r * 2f, if (near) c.y + r else c.y) {
                drawOval(ring, Offset(c.x - r * 1.8f, c.y - r * .4f), Size(r * 3.6f, r * .8f), style = Stroke(r * .09f))
                drawOval(ring, Offset(c.x - r * 1.5f, c.y - r * .31f), Size(r * 3f, r * .62f), alpha = .6f, style = Stroke(r * .035f))
            }
        }
        onDrawBehind {
            drawCircle(air, r * 1.25f, c)
            ringHalf(near = false)
            drawCircle(body, r, c)
            clipPath(disc) { rotate(-18f, c) { for (k in 0 until 4) drawRect(band, Offset(c.x - r * 1.2f, c.y - r * .6f + k * r * .38f), Size(r * 2.4f, r * (.08f + .05f * k))) } }
            drawCircle(shade, r, c)
            ringHalf(near = true)
            drawCircle(moonBrush, moonRadius, moon)
        }
    }
}

// ---------------------------------------------------------------- sakura

private data class SakuraColors(val sky: List<Color>, val glow: Color, val mountain: Color, val mountainLow: Color, val snow: Color, val hillBack: Color,
    val hillFront: Color, val mist: Color, val branch: Color, val petal: Color, val heart: Color, val bud: Color, val fallingTop: Color, val fallingBottom: Color)

private val sakuraDay = SakuraColors(listOf(Color(0xFFFFF6F9), Color(0xFFFCE7EF), Color(0xFFF6D8E6)), Color(0xB3FFFFFF), Color(0xFFE6CFE0), Color(0xFFF0DEEA),
    Color(0xF2FFFFFF), Color(0xFFEDD2E0), Color(0xFFE4C0D3), Color(0x73FFFFFF), Color(0xFF6E4757), Color(0xFFF9C3D9), Color(0xFFE0789F), Color(0xFFF09ABD),
    Color(0xFFFFE6F0), Color(0xFFF4A6C6))
private val sakuraNight = SakuraColors(listOf(Color(0xFF0B0D22), Color(0xFF191536), Color(0xFF281C40)), Color(0x38FFF1E6), Color(0xFF252046), Color(0xFF1C1838),
    Color(0xD9CFCBEA), Color(0xFF1D1835), Color(0xFF151129), Color(0x1F8A7FC0), Color(0xFF3A2536), Color(0xFFEBA2C6), Color(0xFFB94E82), Color(0xFFD47AA6),
    Color(0xFFFFD3E6), Color(0xFFE58AB6))

// Branch polylines as (x, y, width) triples, in a square anchored at the top right corner.
private val twigs = listOf(
    floatArrayOf(1.06f, .03f, .032f, .9f, .09f, .026f, .78f, .13f, .021f, .64f, .15f, .016f, .52f, .21f, .012f, .4f, .24f, .009f, .31f, .29f, .006f),
    floatArrayOf(.78f, .13f, .012f, .73f, .06f, .008f, .69f, .02f, .005f),
    floatArrayOf(.64f, .15f, .01f, .6f, .24f, .007f, .57f, .31f, .005f),
    floatArrayOf(.52f, .21f, .008f, .46f, .15f, .006f, .43f, .11f, .004f),
    floatArrayOf(.9f, .09f, .012f, .88f, .19f, .008f, .84f, .25f, .005f),
)
// Flowers as (x, y, size), buds as (x, y).
private val blossoms = floatArrayOf(.31f, .29f, 1f, .36f, .255f, .8f, .43f, .11f, .9f, .47f, .17f, .75f, .57f, .31f, 1f, .61f, .22f, .8f, .69f, .02f, .9f,
    .72f, .08f, .7f, .84f, .25f, 1f, .87f, .17f, .8f, .55f, .19f, .7f, .67f, .14f, .85f, .8f, .15f, .7f, .95f, .07f, .8f, .4f, .22f, .65f)
private val buds = floatArrayOf(.33f, .31f, .45f, .13f, .59f, .33f, .71f, .01f, .86f, .27f)

private class FallingPetal(val radius: Float, val x: Float, val y: Float, val fall: Float, val sway: Float, val gust: Float, val spin: Float, val angle: Float)

@Composable
private fun BoxScope.Sakura(time: FloatState, dark: Boolean, amoled: Boolean) {
    val c = when {
        amoled -> sakuraNight.copy(sky = listOf(Color.Black, Color(0xFF0D0A1C), Color(0xFF170F28)), hillFront = Color(0xFF0B0816))
        dark -> sakuraNight
        else -> sakuraDay
    }
    Still {
        val w = size.width
        val h = size.height
        val sky = Brush.verticalGradient(c.sky)
        val light = Offset(w * .2f, h * .15f)
        val glow = Brush.radialGradient(listOf(c.glow, c.glow.copy(alpha = 0f)), center = light, radius = w * .32f)
        val stars = if (dark) starField(67, 0f, .5f, 1f / 5000f, 60) else emptyList()
        val mountain = Path().apply {
            moveTo(-w * .1f, h * .88f); cubicTo(w * .12f, h * .84f, w * .27f, h * .72f, w * .35f, h * .64f)
            lineTo(w * .41f, h * .64f); cubicTo(w * .49f, h * .72f, w * .64f, h * .84f, w * 1.1f, h * .89f)
            lineTo(w * 1.1f, h); lineTo(-w * .1f, h); close()
        }
        val snow = Path().apply {
            val points = floatArrayOf(.35f, .64f, .41f, .64f, .448f, .678f, .43f, .672f, .418f, .692f, .398f, .674f, .38f, .695f, .362f, .673f, .342f, .688f, .322f, .668f)
            moveTo(w * points[0], h * points[1])
            for (k in 2 until points.size step 2) lineTo(w * points[k], h * points[k + 1])
            close()
        }
        val rock = Brush.verticalGradient(listOf(c.mountain, c.mountainLow), startY = h * .64f, endY = h * .9f)
        val hillBack = Path().apply {
            moveTo(-w * .05f, h * .86f); cubicTo(w * .2f, h * .82f, w * .45f, h * .88f, w * .7f, h * .85f)
            cubicTo(w * .85f, h * .83f, w, h * .84f, w * 1.05f, h * .85f); lineTo(w * 1.05f, h); lineTo(-w * .05f, h); close()
        }
        val hillFront = Path().apply {
            moveTo(-w * .05f, h * .93f); cubicTo(w * .25f, h * .88f, w * .55f, h * .95f, w * 1.05f, h * .9f); lineTo(w * 1.05f, h); lineTo(-w * .05f, h); close()
        }
        val mist = Brush.verticalGradient(listOf(Color.Transparent, c.mist, Color.Transparent), startY = h * .8f, endY = h * .9f)
        val u = minOf(w, h * .62f)
        fun at(x: Float, y: Float) = Offset(w - u + x * u, y * u)
        onDrawBehind {
            drawRect(sky)
            stars.forEach { drawCircle(Color.White, it.radius, Offset(it.x, it.y), .2f + .45f * it.phase / 6.3f) }
            drawCircle(glow, w * .32f, light)
            if (dark) drawCircle(Color(0xFFFFF1E6), w * .055f, light)
            drawPath(mountain, rock)
            drawPath(snow, c.snow)
            drawPath(hillBack, c.hillBack)
            drawRect(mist, Offset(0f, h * .8f), Size(w, h * .1f))
            drawPath(hillFront, c.hillFront)
            twigs.forEach { twig ->
                for (k in 0 until twig.size / 3 - 1) {
                    drawLine(c.branch, at(twig[k * 3], twig[k * 3 + 1]), at(twig[k * 3 + 3], twig[k * 3 + 4]), (twig[k * 3 + 2] + twig[k * 3 + 5]) / 2f * u, StrokeCap.Round)
                }
            }
            for (k in 0 until blossoms.size / 3) {
                val center = at(blossoms[k * 3], blossoms[k * 3 + 1])
                val radius = blossoms[k * 3 + 2] * u * .026f
                val turn = rand(k, 71) * 72f
                for (p in 0 until 5) {
                    val a = (turn + p * 72f) * DEGREES
                    drawCircle(c.petal, radius * .5f, center + Offset(cos(a), sin(a)) * (radius * .5f))
                }
                drawCircle(c.heart, radius * .24f, center)
            }
            for (k in 0 until buds.size / 2) drawCircle(c.bud, u * .009f, at(buds[k * 2], buds[k * 2 + 1]))
        }
    }
    Moving {
        val w = size.width
        val h = size.height
        val petal = Path().apply { moveTo(0f, 1f); cubicTo(-.75f, .55f, -.6f, -.75f, -.12f, -.9f); lineTo(0f, -.72f); lineTo(.12f, -.9f); cubicTo(.6f, -.75f, .75f, .55f, 0f, 1f); close() }
        val tint = Brush.verticalGradient(listOf(c.fallingTop, c.fallingBottom), startY = -1f, endY = 1f)
        val count = (w * h / (density * density) / 9000f).roundToInt().coerceIn(12, 40)
        val petals = List(count) { i -> FallingPetal((4f + rand(i, 94) * 3.5f).dp.toPx(), rand(i, 91), rand(i, 92),
            .025f + rand(i, 93) * .025f, .025f + rand(i, 95) * .03f, .5f + rand(i, 96) * .6f,
            1f + rand(i, 97) * 1.5f, rand(i, 98) * 360f) }
        onDrawBehind {
            val t = time.floatValue
            for (i in petals.indices) {
                val p = petals[i]
                val y = ((p.y + t * p.fall).wrap(1.1f) - .05f) * h
                val x = ((p.x + t * .01f + p.sway * sin(t * p.gust + i)).wrap(1.1f) - .05f) * w
                val turn = p.angle + t * p.spin * 35f
                val flip = abs(cos(t * p.spin * .8f + i)).coerceAtLeast(.2f)
                withTransform({ translate(x, y); rotate(turn, Offset.Zero); scale(p.radius * flip, p.radius, Offset.Zero) }) { drawPath(petal, tint, alpha = if (dark) .85f else .9f) }
            }
        }
    }
}

// ---------------------------------------------------------------- neon

@Composable
private fun BoxScope.Neon(time: FloatState, dark: Boolean, amoled: Boolean) {
    val line = if (dark) Color(0xFFFF4FD8) else Color(0xFFB27CE6)
    Still {
        val w = size.width
        val h = size.height
        val horizon = h * .6f
        val r = minOf(w * .27f, h * .16f)
        val sun = Offset(w * .5f, horizon - r * .6f)
        val sky = when {
            amoled -> Brush.verticalGradient(0f to Color.Black, .5f to Color(0xFF0E0422), .85f to Color(0xFF3A0E52), 1f to Color(0xFF63185F), endY = horizon)
            dark -> Brush.verticalGradient(0f to Color(0xFF0B0619), .45f to Color(0xFF1E0B3F), .8f to Color(0xFF4A1260), 1f to Color(0xFF7A1D6E), endY = horizon)
            else -> Brush.verticalGradient(0f to Color(0xFFFBF4FF), .6f to Color(0xFFF6DDF3), 1f to Color(0xFFFFCFE1), endY = horizon)
        }
        val floor = when {
            amoled -> Brush.verticalGradient(listOf(Color(0xFF14052B), Color.Black), startY = horizon, endY = h)
            dark -> Brush.verticalGradient(listOf(Color(0xFF1D0A3B), Color(0xFF0B0418)), startY = horizon, endY = h)
            else -> Brush.verticalGradient(listOf(Color(0xFFF3E1FA), Color(0xFFEBD3F6)), startY = horizon, endY = h)
        }
        val glow = Brush.radialGradient(listOf(Color(0xFFFF4FA3).copy(alpha = if (dark) .35f else .3f), Color.Transparent), center = sun, radius = r * 2.3f)
        val disc = if (dark) Brush.verticalGradient(0f to Color(0xFFFFE66D), .5f to Color(0xFFFF8E5E), 1f to Color(0xFFFF2F92), startY = sun.y - r, endY = sun.y + r)
            else Brush.verticalGradient(0f to Color(0xFFFFE08A), .5f to Color(0xFFFFA07F), 1f to Color(0xFFFF72AE), startY = sun.y - r, endY = sun.y + r)
        // The lower half of the sun is cut by ever wider gaps.
        val bands = Path().apply {
            var top = sun.y - r
            for (k in 0 until 7) {
                val gap = sun.y - r * .2f + k * r * .15f
                addRect(Rect(sun.x - r, top, sun.x + r, gap))
                top = gap + r * (.02f + k * .016f)
            }
            if (top < sun.y + r) addRect(Rect(sun.x - r, top, sun.x + r, sun.y + r))
        }
        fun range(vararg p: Float) = Path().apply {
            moveTo(p[0] * w, horizon + 1f)
            for (k in p.indices step 2) lineTo(p[k] * w, horizon - p[k + 1] * h)
            lineTo(p[p.size - 2] * w, horizon + 1f)
            close()
        }
        val mountains = Path().apply {
            addPath(range(-.02f, 0f, .04f, .05f, .09f, .028f, .15f, .075f, .22f, .035f, .27f, .055f, .33f, .012f, .37f, 0f))
            addPath(range(.63f, 0f, .68f, .03f, .74f, .06f, .79f, .025f, .86f, .085f, .92f, .04f, .97f, .06f, 1.03f, 0f))
        }
        val rock = if (dark) Color(0xFF1A0B33) else Color(0xFFEAD2F4)
        val edge = if (dark) Color(0xFF39E6FF) else Color(0xFFC490E6)
        val haze = Brush.verticalGradient(listOf(Color.Transparent, line.copy(alpha = .55f), Color.Transparent), startY = horizon - 6.dp.toPx(), endY = horizon + 6.dp.toPx())
        val stars = if (dark) starField(77, 0f, .45f, 1f / 3500f, 80) else emptyList()
        onDrawBehind {
            drawRect(sky, size = Size(w, horizon))
            stars.forEach { drawCircle(Color.White, it.radius, Offset(it.x, it.y), .2f + .5f * it.phase / 6.3f) }
            drawCircle(glow, r * 2.3f, sun)
            clipPath(bands) { drawCircle(disc, r, sun) }
            drawPath(mountains, rock)
            drawPath(mountains, edge, alpha = .7f, style = Stroke(1.dp.toPx()))
            drawRect(floor, Offset(0f, horizon), Size(w, h - horizon))
            drawRect(haze, Offset(0f, horizon - 6.dp.toPx()), Size(w, 12.dp.toPx()))
            drawLine(line, Offset(0f, horizon), Offset(w, horizon), 1.dp.toPx())
        }
    }
    Moving {
        val w = size.width
        val h = size.height
        val horizon = h * .6f
        val depth = h - horizon
        onDrawBehind {
            val t = time.floatValue
            val thin = 1.dp.toPx()
            val wide = 3.5.dp.toPx()
            for (k in -14..14) {
                val top = Offset(w * .5f + k * w * .012f, horizon)
                val bottom = Offset(w * .5f + k * w * .16f, h)
                drawLine(line, top, bottom, wide, alpha = .12f)
                drawLine(line, top, bottom, thin, alpha = .6f)
            }
            // Cross lines in perspective, rolling toward the viewer.
            val shift = (t * .45f) % 1f
            for (i in 0 until 26) {
                val y = horizon + depth / (i + 1f - shift)
                if (y > h) continue
                val fade = ((y - horizon) / depth).pow(.6f)
                drawLine(line, Offset(0f, y), Offset(w, y), wide, alpha = .12f * fade)
                drawLine(line, Offset(0f, y), Offset(w, y), thin, alpha = .75f * fade)
            }
        }
    }
}
