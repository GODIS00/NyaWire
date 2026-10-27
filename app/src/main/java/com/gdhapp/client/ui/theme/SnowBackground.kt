package com.gdhapp.client.ui.theme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private data class WinterPalette(
    val sky: List<Color>, val night: Boolean, val moon: Color, val lit: List<Color>, val shade: List<Color>, val haze: Color,
    val farPine: Color, val farSnow: Color, val nearPine: Color, val nearSnow: Color, val ground: List<Color>, val drift: Color,
    val wall: Color, val roof: Color, val window: Color, val smoke: Color, val flake: Color, val flakeShadow: Color,
)

private val winterNight = WinterPalette(
    listOf(Color(0xFF0B1430), Color(0xFF16244B), Color(0xFF26386A), Color(0xFF4A5A8E)), true, Color(0xFFFFF4E0),
    listOf(Color(0xFFC9D5EF), Color(0xFFA9B9DD)), listOf(Color(0xFF6A7BAD), Color(0xFF56689A)), Color(0xFF8FA3D0),
    Color(0xFF22335A), Color(0xFFC9D6F2), Color(0xFF111B35), Color(0xFFE6EDFF),
    listOf(Color(0xFFAFC0E2), Color(0xFFCDD8F0), Color(0xFFE4EAF7)), Color(0xFF7F93C4),
    Color(0xFF2B2028), Color(0xFF251B22), Color(0xFFFFCF7A), Color(0xFFDDE4F5), Color.White, Color.Transparent,
)
private val winterDay = WinterPalette(
    listOf(Color(0xFFC2D2EF), Color(0xFFD9E2F5), Color(0xFFECE7F4), Color(0xFFF8E2E9)), false, Color.White,
    listOf(Color.White, Color(0xFFF5E8F1)), listOf(Color(0xFFB4C3E4), Color(0xFFA5B5DA)), Color.White,
    Color(0xFF6E86B4), Color.White, Color(0xFF4A6394), Color.White,
    listOf(Color(0xFFE2E9F7), Color(0xFFEFF3FB), Color(0xFFFAFCFF)), Color(0xFFC0CDE8),
    Color(0xFF6D4C47), Color(0xFF573E3A), Color(0xFFFFC66E), Color.White, Color.White, Color(0xFF8EA3CF),
)
private val winterAmoled = winterNight.copy(
    sky = listOf(Color.Black, Color(0xFF02040C), Color(0xFF060B1C), Color(0xFF0D1430)),
    lit = listOf(Color(0xFF46557C), Color(0xFF3A4870)), shade = listOf(Color(0xFF1C2644), Color(0xFF161F38)), haze = Color(0xFF2A3A66),
    farPine = Color(0xFF0A1022), farSnow = Color(0xFF56658F), nearPine = Color(0xFF03060E), nearSnow = Color(0xFF7C8AB3),
    ground = listOf(Color(0xFF222C47), Color(0xFF2C3753), Color(0xFF333F5E)), drift = Color(0xFF141B33),
    wall = Color(0xFF120C10), roof = Color(0xFF0D090C), window = Color(0xFFE6A85A), smoke = Color(0xFF6C7795),
)

// Peaks as (x, top, half width), in fractions of the screen; the mountains stand on MOUNTAIN_BASE.
private val peaks = floatArrayOf(.11f, .52f, .31f, .4f, .47f, .28f, .65f, .54f, .26f, .89f, .49f, .3f, .28f, .575f, .24f, .76f, .585f, .22f)
private const val MOUNTAIN_BASE = .705f

/** Snow lying on the tiers of a pine drawn by [addPine] with the same arguments. */
private fun Path.addPineSnow(x: Float, tip: Float, base: Float, width: Float, salt: Int, tiers: Int) {
    val step = (base - tip) * .94f / tiers
    for (k in 1..tiers) {
        val y = tip + step * k
        val r = pineHalf(k, tiers, width, salt)
        moveTo(x - r * .96f, y - step * .04f)
        quadraticTo(x - r * .6f, y - step * .5f, x - r * .3f, y - step * .7f)
        lineTo(x + r * .25f, y - step * .68f)
        quadraticTo(x + r * .55f, y - step * .45f, x + r * .8f, y - step * .08f)
    }
}

private class Flake(val x: Float, val y: Float, val radius: Float, val speed: Float, val sway: Float)
private class Crystal(val x: Float, val y: Float, val size: Float, val speed: Float, val spin: Float, val sway: Float)
private class Glint(val x: Float, val y: Float, val period: Float, val phase: Float)

/**
 * A snowy valley: mountains, pines under snow and a cabin with warm windows, while snow falls in three depths.
 * The dark theme is a moonlit night, AMOLED a black one, and the light theme pastel winter dusk.
 */
@Composable
internal fun BoxScope.SnowBackground(time: FloatState, dark: Boolean, amoled: Boolean) {
    val c = when { amoled -> winterAmoled; dark -> winterNight; else -> winterDay }
    // The cabin is drawn in its own units: 1080 of them across a portrait screen.
    fun cabinScale(w: Float, h: Float) = min(w, h * .5625f) / 1080f * 1.15f
    fun cabinAt(w: Float, h: Float) = Offset(w * .15f, h * .838f)
    Still {
        val w = size.width
        val h = size.height
        val unit = min(w, h * .5625f)
        val k = unit / 1080f
        val sky = Brush.verticalGradient(0f to c.sky[0], .3f to c.sky[1], .55f to c.sky[2], .72f to c.sky[3])
        val stars = if (c.night) starField(331, 0f, .5f, 1f / 2800f, 140) else emptyList()
        val moon = Offset(w * .16f, h * .19f)
        val moonRadius = unit * .046f
        val moonHalo = Brush.radialGradient(0f to c.moon.copy(alpha = if (c.night) .3f else .5f), 1f to Color.Transparent, center = moon, radius = unit * .32f)
        val bite = Path().apply { addOval(Rect(moon + Offset(moonRadius * .45f, -moonRadius * .3f), moonRadius * .86f)) }
        val base = h * MOUNTAIN_BASE
        val mountains = (0 until peaks.size / 3).map { i ->
            val x = peaks[i * 3] * w
            val top = peaks[i * 3 + 1] * h
            val half = peaks[i * 3 + 2] * w
            val split = x + half * .18f
            fun jitter(n: Int, seed: Int) = (rand(n, seed) - .5f) * unit * .03f
            val face = Path().apply {
                moveTo(x - half, base)
                for (n in 1 until 6) lineTo(x - half + half * n / 6f + jitter(n, 900 + i), base + (top - base) * n / 6f)
                lineTo(x, top)
                for (n in 1 until 7) lineTo(x + (split - x) * n / 7f + jitter(n, 950 + i), top + (base - top) * n / 7f)
                lineTo(split, base)
                close()
            }
            val back = Path().apply {
                moveTo(x, top)
                for (n in 1 until 7) lineTo(x + (split - x) * n / 7f + jitter(n, 950 + i), top + (base - top) * n / 7f)
                lineTo(split, base); lineTo(x + half, base)
                for (n in 5 downTo 1) lineTo(x + half * n / 6f + jitter(n, 970 + i), base + (top - base) * (6 - n) / 6f)
                close()
            }
            Triple(face, back, i / 5f)
        }
        val haze = Brush.verticalGradient(listOf(Color.Transparent, c.haze.copy(alpha = if (c.night) .35f else .75f)), startY = h * .6f, endY = h * .715f)
        val farBase = h * .735f
        val nearBase = h * .8f
        val farTrees = Path()
        val farSnow = Path()
        forestLine(w, farBase, 39f * k, 210f * k, 331, 7) { x, tip, b, width, salt -> farTrees.addPine(x, tip, b, width, salt, 7); farSnow.addPineSnow(x, tip, b, width, salt, 7) }
        val nearTrees = Path()
        val nearSnow = Path()
        forestLine(w, nearBase, 84f * k, 360f * k, 361, 7) { x, tip, b, width, salt -> nearTrees.addPine(x, tip, b, width, salt, 7); nearSnow.addPineSnow(x, tip, b, width, salt, 7) }
        val ground = Path().apply {
            moveTo(-w * .02f, h * .805f)
            cubicTo(w * .17f, h * .78f, w * .33f, h * .82f, w * .52f, h * .8f)
            cubicTo(w * .7f, h * .78f, w * .83f, h * .815f, w * 1.02f, h * .795f)
            lineTo(w * 1.02f, h); lineTo(-w * .02f, h); close()
        }
        val groundBrush = Brush.verticalGradient(0f to c.ground[0], .35f to c.ground[1], 1f to c.ground[2], startY = h * .79f, endY = h)
        val drift = Path().apply { moveTo(-w * .02f, h * .89f); cubicTo(w * .22f, h * .865f, w * .4f, h * .905f, w * .65f, h * .885f); cubicTo(w * .8f, h * .873f, w * .9f, h * .89f, w * 1.02f, h * .88f) }
        val ck = cabinScale(w, h)
        val cabin = cabinAt(w, h)
        val roof = Path().apply { moveTo(-110f, -66f); lineTo(-15f, -128f); lineTo(80f, -66f); close() }
        val roofSnow = Path().apply { moveTo(-116f, -62f); lineTo(-15f, -130f); lineTo(86f, -62f) }
        val frames = Path().apply { moveTo(-70f, -33f); lineTo(-40f, -33f); moveTo(-55f, -48f); lineTo(-55f, -18f); moveTo(0f, -33f); lineTo(30f, -33f); moveTo(15f, -48f); lineTo(15f, -18f) }
        onDrawBehind {
            drawRect(sky)
            stars.forEach { drawCircle(Color(0xFFEEF2FF), it.radius, Offset(it.x, it.y), .25f + .5f * it.phase / 6.3f) }
            drawCircle(moonHalo, unit * .32f, moon)
            clipPath(bite, ClipOp.Difference) { drawCircle(c.moon, moonRadius, moon, if (c.night) 1f else .85f) }
            mountains.forEach { (face, back, f) ->
                drawPath(face, lerp(c.lit[0], c.lit[1], f))
                drawPath(back, lerp(c.shade[0], c.shade[1], f))
            }
            drawRect(haze, Offset(0f, h * .6f), Size(w, h * .115f))
            drawPath(farTrees, c.farPine)
            drawPath(farSnow, c.farSnow, alpha = .6f, style = Stroke(3f * k, cap = StrokeCap.Round))
            drawRect(c.farPine, Offset(0f, farBase), Size(w, nearBase - farBase))
            drawPath(nearTrees, c.nearPine)
            drawPath(nearSnow, c.nearSnow, alpha = .75f, style = Stroke(5f * k, cap = StrokeCap.Round))
            drawPath(ground, groundBrush)
            // A soft shadow where the snow dips; wide faint strokes stand in for a blur.
            for (n in 0..3) drawPath(drift, c.drift, alpha = .05f + .025f * n, style = Stroke((90f - n * 22f) * k, cap = StrokeCap.Round))
            withTransform({ translate(cabin.x, cabin.y); scale(ck, ck, Offset.Zero) }) {
                drawRect(c.wall, Offset(-90f, -70f), Size(150f, 78f))
                drawPath(roof, c.roof)
                drawPath(roofSnow, c.nearSnow, style = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawRect(c.roof, Offset(34f, -132f), Size(18f, 40f))
                drawRoundRect(c.nearSnow, Offset(31f, -138f), Size(24f, 9f), CornerRadius(4f))
                drawRoundRect(c.window, Offset(-70f, -48f), Size(30f, 30f), CornerRadius(3f))
                drawRoundRect(c.window, Offset(0f, -48f), Size(30f, 30f), CornerRadius(3f))
                drawPath(frames, c.wall, style = Stroke(3f))
                // Snow banked against the walls.
                drawOval(c.ground[1], Offset(-128f, -4f), Size(220f, 22f))
            }
        }
    }
    Moving {
        val w = size.width
        val h = size.height
        val area = w * h / (density * density)
        // Sizes are in dp on a phone; the small previews in the scene picker shrink them with the screen.
        val dp = density * min(1f, min(w, h * .5625f) / (360f * density))
        val ck = cabinScale(w, h)
        val cabin = cabinAt(w, h)
        fun flakes(salt: Int, perDp: Float, min: Int, max: Int, small: Float, big: Float, speed: Float) =
            List((area / perDp).roundToInt().coerceIn(min, max)) { i ->
                Flake(rand(i, salt), rand(i, salt + 1), (small + rand(i, salt + 2) * (big - small)) * dp, speed * (.75f + rand(i, salt + 3) * .5f) * dp, rand(i, salt + 4) * 6.3f)
            }
        val far = flakes(1001, 1700f, 40, 260, .45f, .9f, 11f)
        val mid = flakes(1011, 4200f, 24, 110, 1f, 1.7f, 21f)
        val near = flakes(1021, 16000f, 6, 30, 3f, 6f, 36f)
        val crystals = List((area / 40000f).roundToInt().coerceIn(4, 10)) { i ->
            Crystal(rand(i, 1101), rand(i, 1102), (6f + rand(i, 1103) * 5f) * dp, (14f + rand(i, 1104) * 6f) * dp, (rand(i, 1105) - .5f) * 90f, rand(i, 1106) * 6.3f)
        }
        // Six arms with two pairs of branches, one unit across.
        val crystal = Path().apply {
            for (arm in 0 until 6) {
                val a = arm * PI.toFloat() / 3f
                moveTo(0f, 0f); lineTo(cos(a), sin(a))
                for (r in floatArrayOf(.45f, .7f)) for (side in intArrayOf(-1, 1)) {
                    val b = a + side * .6f
                    moveTo(cos(a) * r, sin(a) * r); lineTo(cos(a) * r + cos(b) * .22f, sin(a) * r + sin(b) * .22f)
                }
            }
        }
        val crystalLines = crystals.map { Stroke(1.2f * dp / it.size, cap = StrokeCap.Round) }
        val crystalShadows = crystals.map { Stroke(2.8f * dp / it.size, cap = StrokeCap.Round) }
        val soft = Brush.radialGradient(0f to c.flake.copy(alpha = .9f), .45f to c.flake.copy(alpha = .45f), 1f to c.flake.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
        val softShadow = Brush.radialGradient(0f to c.flakeShadow.copy(alpha = .3f), 1f to c.flakeShadow.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
        val smoke = Brush.radialGradient(listOf(c.smoke, c.smoke.copy(alpha = 0f)), Offset.Zero, 1f)
        val windowGlow = Brush.radialGradient(0f to c.window.copy(alpha = .75f), 1f to c.window.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
        val sparkle = Sparkle(Color.White)
        val glints = List(28) { i -> Glint(rand(i, 1201) * w, h * (.83f + rand(i, 1202) * .16f), 1.4f + rand(i, 1203) * 2f, rand(i, 1204) * 4f) }
        val wind = 6f * dp
        val windows = floatArrayOf(-55f, 15f)
        onDrawBehind {
            val t = time.floatValue
            withTransform({ translate(cabin.x, cabin.y); scale(ck, ck, Offset.Zero) }) {
                val flicker = .85f + .1f * sin(t * 7.3f) + .05f * sin(t * 13.1f)
                for (x in windows) withTransform({ translate(x, -33f); scale(80f, 80f, Offset.Zero) }) { drawCircle(windowGlow, 1f, Offset.Zero, flicker * if (c.night) .7f else .45f) }
                for (i in 0 until 9) {
                    val age = (t / 7f + i / 9f).wrap(1f)
                    val r = (20f + rand(i, 1301) * 16f) * (.5f + age)
                    withTransform({ translate(43f + age * 70f + sin(age * 6f + i) * 10f, -140f - age * 260f); scale(r, r, Offset.Zero) }) {
                        drawCircle(smoke, 1f, Offset.Zero, .38f * (1f - age))
                    }
                }
            }
            for (g in glints) {
                val s = sin((t + g.phase) / g.period * 2f * PI.toFloat())
                if (s < .7f) continue
                val twinkle = ((s - .7f) / .3f).pow(2)
                sparkle.draw(this, g.x, g.y, (5f + 11f * twinkle) * dp, twinkle * if (c.night) .9f else 1f)
            }
            fun fall(f: Flake): Offset = Offset((f.x * w * 1.1f + t * wind + sin(t * .5f + f.sway) * 8f * dp).wrap(w * 1.1f) - w * .05f,
                (f.y * h * 1.06f + t * f.speed).wrap(h * 1.06f) - h * .03f)
            for (f in far) {
                val at = fall(f)
                if (!c.night) drawCircle(c.flakeShadow, f.radius * 1.4f, at, .3f)
                drawCircle(c.flake, f.radius, at, .75f)
            }
            for (f in mid) {
                val at = fall(f)
                if (!c.night) drawCircle(c.flakeShadow, f.radius * 1.35f, at + Offset(0f, .4f * dp), .3f)
                drawCircle(c.flake, f.radius, at, .9f)
            }
            crystals.forEachIndexed { i, cr ->
                val x = (cr.x * w * 1.1f + t * wind + sin(t * .4f + cr.sway) * 12f * dp).wrap(w * 1.1f) - w * .05f
                val y = (cr.y * h * 1.06f + t * cr.speed).wrap(h * 1.06f) - h * .03f
                withTransform({ translate(x, y); rotate(t * cr.spin + cr.sway * 30f, Offset.Zero); scale(cr.size, cr.size, Offset.Zero) }) {
                    if (!c.night) drawPath(crystal, c.flakeShadow, alpha = .45f, style = crystalShadows[i])
                    drawPath(crystal, c.flake, alpha = .9f, style = crystalLines[i])
                }
            }
            for (f in near) {
                val at = fall(f)
                val r = f.radius * 1.6f
                withTransform({ translate(at.x, at.y); scale(r, r, Offset.Zero) }) {
                    if (!c.night) drawCircle(softShadow, 1.3f, Offset.Zero)
                    drawCircle(soft, 1f, Offset.Zero, .8f)
                }
            }
        }
    }
}
