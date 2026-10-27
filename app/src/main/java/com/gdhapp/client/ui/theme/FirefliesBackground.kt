package com.gdhapp.client.ui.theme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private data class MeadowPalette(
    val sky: List<Color>, val night: Boolean, val moon: Color, val glow: Color, val far: Color, val near: Color, val mist: Color, val ground: Color,
    /** Base and tip of the back, middle and front rows of grass. */
    val grass: List<Color>, val stem: Color, val umbel: Color, val fly: Color, val core: Color, val flyAlpha: Float,
)

private val summerNight = MeadowPalette(
    listOf(Color(0xFF060A1F), Color(0xFF0B1434), Color(0xFF13234A), Color(0xFF1B3A4E), Color(0xFF23463F)), true,
    Color(0xFFF6F8E8), Color(0xFFB9CBFF), Color(0xFF142A3A), Color(0xFF0A171D), Color(0xFF9FC4C0), Color(0xFF07120F),
    listOf(Color(0xFF0E2622), Color(0xFF2B5A4C), Color(0xFF091A16), Color(0xFF21473B), Color(0xFF040C0A), Color(0xFF132E26)),
    Color(0xFF0A1A15), Color(0xFF1B3A31), Color(0xFFD8FF6A), Color(0xFFFBFFD2), 1f,
)
private val summerEvening = MeadowPalette(
    listOf(Color(0xFFF6F2E9), Color(0xFFFBECD8), Color(0xFFF9DEC6), Color(0xFFF2D0B9), Color(0xFFE6CBB3)), false,
    Color(0xFFFFF4DC), Color(0xFFFFD49A), Color(0xFFC3CCB6), Color(0xFF97AE95), Color.White, Color(0xFFA3BF90),
    listOf(Color(0xFFADC99D), Color(0xFFDDEDC6), Color(0xFF90B486), Color(0xFFC9E2B1), Color(0xFF6F9868), Color(0xFFA7CD90)),
    Color(0xFF809E78), Color(0xFFFAF6EC), Color(0xFFFFC45C), Color(0xFFFFF6DC), .8f,
)
private val summerAmoled = summerNight.copy(
    sky = listOf(Color.Black, Color(0xFF01030A), Color(0xFF040A18), Color(0xFF07131C), Color(0xFF0A1612)),
    far = Color(0xFF07111A), near = Color(0xFF020609), mist = Color(0xFF4F6E6A), ground = Color(0xFF010302),
    grass = listOf(Color(0xFF041210), Color(0xFF15332A), Color(0xFF030B09), Color(0xFF0F271F), Color(0xFF010403), Color(0xFF09170F)),
    stem = Color(0xFF040C09), umbel = Color(0xFF0E2219),
)

/** Half the span of a pine's tier [k] of [tiers], counted from the top. */
internal fun pineHalf(k: Int, tiers: Int, width: Float, salt: Int) = width / 2f * (.14f + .86f * k / tiers) * (.85f + .3f * rand(k, salt))

/** A pine of drooping tiers, added to a shared silhouette so a whole forest fills in one draw. */
internal fun Path.addPine(x: Float, tip: Float, base: Float, width: Float, salt: Int, tiers: Int = 8) {
    val h = base - tip
    val step = h * .94f / tiers
    fun half(k: Int) = pineHalf(k, tiers, width, salt)
    moveTo(x - width * .08f, base + h * .12f)
    for (k in tiers downTo 1) {
        val y = tip + step * k
        val r = half(k)
        lineTo(x - r, y); lineTo(x - r * .42f, y - step * .62f)
    }
    lineTo(x, tip)
    for (k in 1..tiers) {
        val y = tip + step * k
        val r = half(k)
        lineTo(x + r * .42f, y - step * .62f); lineTo(x + r, y)
    }
    lineTo(x + width * .08f, base + h * .12f)
    close()
}

/** Pines along a line: [spacing] and [tall] are pixels; trees vary in height and width. */
internal fun forestLine(width: Float, base: Float, spacing: Float, tall: Float, salt: Int, tiers: Int = 8, each: (x: Float, tip: Float, base: Float, width: Float, salt: Int) -> Unit) {
    var i = 0
    var x = -spacing
    while (x < width + spacing) {
        val height = tall * (.55f + rand(i, salt + 1) * .6f)
        each(x + (rand(i, salt) - .5f) * spacing * .7f, base - height, base, height * (.34f + rand(i, salt + 2) * .12f), salt + 10 + i)
        x += spacing
        i++
    }
}

private class Blade(val x: Float, val root: Float, val tall: Float, val lean: Float, val width: Float)
private class GrassRow(val blades: List<Blade>, val brush: Brush, val top: Float)
private class Umbel(val x: Float, val root: Float, val height: Float, val size: Float)
private class Firefly(val x: Float, val y: Float, val depth: Float, val a: Float, val b: Float, val phase: Float, val period: Float)

/** A firefly glows up for a moment every couple of seconds. */
private fun flash(t: Float, period: Float, phase: Float): Float {
    val s = max(0f, sin(2f * PI.toFloat() * t / period + phase))
    return .12f + .88f * s * s * s
}

/**
 * A meadow at the edge of a pine wood. At night fireflies blink over the grass under the moon; the light theme
 * is a warm summer evening where golden pollen drifts in the low sun.
 */
@Composable
internal fun BoxScope.FirefliesBackground(time: FloatState, dark: Boolean, amoled: Boolean) {
    val c = when { amoled -> summerAmoled; dark -> summerNight; else -> summerEvening }
    Still {
        val w = size.width
        val h = size.height
        val unit = min(w, h * .5625f)
        val k = unit / 1080f
        val sky = Brush.verticalGradient(0f to c.sky[0], .28f to c.sky[1], .52f to c.sky[2], .68f to c.sky[3], .8f to c.sky[4])
        val stars = if (c.night) starField(311, 0f, .62f, 1f / 2600f, 170) else emptyList()
        val moon = Offset(w * .82f, h * .2f)
        val moonRadius = unit * .05f
        val moonHalo = Brush.radialGradient(0f to c.glow.copy(alpha = .3f), .3f to c.glow.copy(alpha = .09f), 1f to Color.Transparent, center = moon, radius = unit * .55f)
        // Low enough that the pines cut into it.
        val sun = Offset(w * .27f, h * .655f)
        val sunGlow = Brush.radialGradient(0f to c.glow.copy(alpha = .85f), .35f to c.glow.copy(alpha = .35f), 1f to Color.Transparent, center = sun, radius = max(w, h * .5f) * .75f)
        val farBase = h * .725f
        val nearBase = h * .785f
        val far = Path()
        val near = Path()
        forestLine(w, farBase, 37f * k, 200f * k, 311) { x, tip, base, width, salt -> far.addPine(x, tip, base, width, salt) }
        forestLine(w, nearBase, 72f * k, 330f * k, 321) { x, tip, base, width, salt -> near.addPine(x, tip, base, width, salt) }
        val mist = Brush.verticalGradient(listOf(Color.Transparent, c.mist.copy(alpha = if (c.night) .2f else .55f), Color.Transparent), startY = h * .64f, endY = h * .78f)
        onDrawBehind {
            drawRect(sky)
            stars.forEach { drawCircle(Color(0xFFEEF2FF), it.radius, Offset(it.x, it.y), .25f + .5f * it.phase / 6.3f) }
            if (c.night) {
                drawCircle(moonHalo, unit * .55f, moon)
                drawCircle(c.moon, moonRadius, moon)
                drawCircle(Color(0xFFDFE4D0), moonRadius * .2f, moon + Offset(-moonRadius * .3f, -moonRadius * .28f), .6f)
                drawCircle(Color(0xFFDFE4D0), moonRadius * .13f, moon + Offset(moonRadius * .32f, moonRadius * .3f), .5f)
            } else {
                drawRect(sunGlow)
                drawCircle(c.moon, unit * .06f, sun, .9f)
            }
            drawPath(far, c.far)
            drawRect(c.far, Offset(0f, farBase), Size(w, h - farBase))
            drawRect(mist, Offset(0f, h * .64f), Size(w, h * .14f))
            drawPath(near, c.near)
            drawRect(c.ground, Offset(0f, nearBase - 2f * k), Size(w, h - nearBase + 2f * k))
        }
    }
    Moving {
        val w = size.width
        val h = size.height
        val dp = density
        val unit = min(w, h * .5625f)
        val k = unit / 1080f
        fun row(salt: Int, spacing: Float, from: Float, to: Float, tall: Float, base: Color, tip: Color): GrassRow {
            val count = (w / (spacing * k)).roundToInt().coerceIn(12, 420)
            val blades = List(count) { i ->
                Blade(rand(i, salt) * w * 1.06f - w * .03f, h * (from + rand(i, salt + 1) * (to - from)), tall * k * (.45f + rand(i, salt + 2) * .75f),
                    (rand(i, salt + 3) - .5f) * .5f, (4f + rand(i, salt + 4) * 6f) * k)
            }
            val top = h * from - tall * k * 1.2f
            return GrassRow(blades, Brush.verticalGradient(0f to tip, .55f to base, 1f to base, startY = top, endY = h * to), top)
        }
        val rows = listOf(
            row(401, 9.5f, .76f, .82f, 150f, c.grass[0], c.grass[1]),
            row(411, 10.4f, .83f, .92f, 240f, c.grass[2], c.grass[3]),
            row(421, 16f, .95f, 1.03f, 390f, c.grass[4], c.grass[5]),
        )
        val grass = Path()
        val umbels = listOf(Umbel(.1f, .9f, 420f, 46f), Umbel(.19f, .93f, 300f, 34f), Umbel(.82f, .91f, 460f, 52f), Umbel(.92f, .95f, 330f, 38f), Umbel(.44f, .85f, 180f, 24f))
        val stemStroke = Stroke(2.5f * k, cap = StrokeCap.Round)
        val count = (w * h / (dp * dp) / 5200f).roundToInt().coerceIn(14, 90)
        val flies = List(count) { i ->
            Firefly(rand(i, 501), .5f + rand(i, 502).pow(.8f) * .52f, .3f + rand(i, 503) * .7f, .2f + rand(i, 504) * .4f, .3f + rand(i, 505) * .5f,
                rand(i, 506) * 6.3f, 1.6f + rand(i, 507) * 2.4f)
        }
        val halo = Brush.radialGradient(0f to c.fly.copy(alpha = .85f), .3f to c.fly.copy(alpha = .3f), 1f to c.fly.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
        fun DrawScope.blades(r: GrassRow, t: Float) {
            grass.rewind()
            for (b in r.blades) {
                val bend = (b.lean + sin(t * .8f + b.x / (160f * k)) * .16f) * b.tall
                grass.moveTo(b.x - b.width / 2f, b.root)
                grass.quadraticTo(b.x + bend * .3f, b.root - b.tall * .6f, b.x + bend, b.root - b.tall)
                grass.quadraticTo(b.x + bend * .3f + b.width * .2f, b.root - b.tall * .55f, b.x + b.width / 2f, b.root)
                grass.close()
            }
            drawPath(grass, r.brush)
        }
        fun DrawScope.fireflies(t: Float, near: Boolean) {
            for (f in flies) {
                if ((f.depth >= .55f) != near) continue
                var x = f.x * w + sin(t * f.a + f.phase) * 70f * k + sin(t * f.b * 1.7f + f.phase * 2f) * 26f * k
                var y = f.y * h + sin(t * f.b + f.phase * 1.3f) * 46f * k
                val light = if (c.night) flash(t, f.period, f.phase) else .55f + .45f * sin(t * .9f + f.phase)
                // Pollen rises slowly through the evening light.
                if (!c.night) { y = (y - t * 9f * k * f.depth - h * .3f).wrap(h * .75f) + h * .3f; x += sin(t * .3f + f.phase) * 20f * k }
                val size = if (near) f.depth * 1.5f else f.depth
                withTransform({ translate(x, y); scale((10f + 26f * size) * k, (10f + 26f * size) * k, Offset.Zero) }) { drawCircle(halo, 1f, Offset.Zero, light * c.flyAlpha) }
                drawCircle(c.core, (1.6f + 2.6f * size) * k, Offset(x, y), light)
            }
        }
        onDrawBehind {
            val t = time.floatValue
            blades(rows[0], t)
            fireflies(t, near = false)
            blades(rows[1], t)
            umbels.forEachIndexed { i, u ->
                val x = u.x * w
                val root = u.root * h
                val height = u.height * k
                val bend = sin(t * .7f + i * 1.7f) * .06f * height
                val head = Offset(x + bend, root - height)
                grass.rewind()
                grass.moveTo(x, root)
                grass.quadraticTo(x + bend * .2f, root - height * .5f, head.x, head.y)
                drawPath(grass, c.stem, style = stemStroke)
                for (n in 0..8) {
                    val a = PI.toFloat() * (1.12f + .76f * n / 8f)
                    val tip = head + Offset(cos(a) * u.size * k, sin(a) * u.size * k * .55f)
                    drawLine(c.stem, head, tip, 1.5f * k, StrokeCap.Round)
                    drawCircle(c.umbel, u.size * k * .13f, tip)
                }
            }
            fireflies(t, near = true)
            blades(rows[2], t)
        }
    }
}
