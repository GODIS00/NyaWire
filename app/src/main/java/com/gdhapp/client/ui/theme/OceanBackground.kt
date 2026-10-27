package com.gdhapp.client.ui.theme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private data class OceanPalette(
    val water: List<Color>, val light: Color, val ray: Color, val rayAlpha: Float, val mote: Color, val plankton: Color,
    val jellies: List<Color>, val jellyAlpha: Float, val bubble: Color, val kelp: Color, val leaf: Color, val floor: List<Color>,
    val fish: Color, val fishAlpha: Float, val night: Boolean, val rock: Color,
)

private val deepSea = OceanPalette(
    listOf(Color(0xFF0C4A6E), Color(0xFF072F4D), Color(0xFF041A31), Color(0xFF020B18)), Color(0xFF3FA6C8), Color(0xFFA8ECFF), .15f,
    Color(0xFFCFEFFF), Color(0xFF4FE8C4), listOf(Color(0xFFF49AD8), Color(0xFFB9A0FF), Color(0xFF74E9FF)), 1f,
    Color(0xFFD8F6FF), Color(0xFF03241D), Color(0xFF05301F), listOf(Color(0xFF031019), Color(0xFF01070D)), Color(0xFF0A3550), .55f, true, Color(0xFF020B12),
)
private val lagoon = OceanPalette(
    listOf(Color(0xFFE9F8FC), Color(0xFFC8EEF7), Color(0xFFA3DDEC), Color(0xFF84CDE1)), Color.White, Color.White, .5f,
    Color.White, Color.White, listOf(Color(0xFFF08CC4), Color(0xFFA98EEB), Color(0xFF4FBCE0)), .8f,
    Color.White, Color(0xFF4E9F8E), Color(0xFF61B39F), listOf(Color(0xFFDDF0E7), Color(0xFFC6E3D8)), Color(0xFF3D86A8), .28f, false, Color(0xFF6FA9A6),
)
private val abyss = deepSea.copy(
    water = listOf(Color.Black, Color(0xFF01060C), Color(0xFF020C17), Color(0xFF000307)), light = Color(0xFF0E3A52), rayAlpha = .05f,
    kelp = Color(0xFF010F0C), leaf = Color(0xFF021610), floor = listOf(Color(0xFF01060A), Color.Black), fish = Color(0xFF05182A), fishAlpha = .6f, rock = Color(0xFF010508),
)

private class LightRay(val x: Float, val width: Float, val angle: Float, val phase: Float, val strength: Float)
private class Jelly(val x: Float, val y: Float, val size: Float, val color: Int, val phase: Float)
private class Mote(val x: Float, val y: Float, val depth: Float, val phase: Float, val glows: Boolean)
private class Bubble(val x: Float, val start: Float, val radius: Float, val speed: Float, val wobble: Float)
private class Fish(val dx: Float, val dy: Float, val size: Float, val phase: Float)
private class School(val y: Float, val speed: Float, val scale: Float, val alpha: Float, val offset: Float)
private class Kelp(val x: Float, val height: Float, val phase: Float, val width: Float)

private fun easeOutCubic(x: Float) = 1f - (1f - x).pow(3)
private fun easeInOutSine(x: Float) = (1f - cos(PI.toFloat() * x)) / 2f

private fun DrawScope.bubble(x: Float, y: Float, r: Float, fill: Brush, rim: Color, stroke: Stroke) {
    withTransform({ translate(x, y); scale(r, r, Offset.Zero) }) { drawCircle(fill, 1f, Offset.Zero) }
    drawCircle(rim, r, Offset(x, y), .45f, style = stroke)
    drawCircle(Color.White, r * .26f, Offset(x - r * .36f, y - r * .38f), .75f)
}

/** One stroke a pulse: a quick squeeze that lifts the jellyfish, then a slow relax. */
private const val JELLY_PULSE = 3.4f

/**
 * Under water: light falls from the surface, jellyfish pulse upward, plankton glows, fish pass by and kelp sways
 * at the sides. The dark theme gets the night sea, AMOLED a black abyss, and the light theme a sunlit lagoon.
 */
@Composable
internal fun BoxScope.OceanBackground(time: FloatState, dark: Boolean, amoled: Boolean) {
    val c = when { amoled -> abyss; dark -> deepSea; else -> lagoon }
    Still {
        val w = size.width
        val h = size.height
        val water = Brush.verticalGradient(0f to c.water[0], .38f to c.water[1], .72f to c.water[2], 1f to c.water[3])
        val glow = Brush.radialGradient(listOf(c.light.copy(alpha = if (c.night) .32f else .75f), c.light.copy(alpha = 0f)),
            center = Offset(w * .5f, -h * .06f), radius = max(w * .9f, h * .5f))
        onDrawBehind {
            drawRect(water)
            drawRect(glow)
        }
    }
    Moving {
        val w = size.width
        val h = size.height
        val area = w * h / (density * density)
        val unit = min(w, h * .5625f)
        // Sizes are in dp on a phone; the small previews in the scene picker shrink them with the screen.
        val dp = density * min(1f, unit / (360f * density))
        val rays = List(7) { i -> LightRay(.04f + i * .155f + rand(i, 201) * .06f, .1f + rand(i, 202) * .14f, -14f + i * 4.5f + rand(i, 203) * 4f,
            rand(i, 204) * 6.3f, .45f + rand(i, 205) * .55f) }
        val rayShape = Path().apply { moveTo(-.16f, 0f); lineTo(.16f, 0f); lineTo(.5f, 1f); lineTo(-.5f, 1f); close() }
        val rayBrush = Brush.verticalGradient(0f to c.ray, .45f to c.ray.copy(alpha = .4f), 1f to c.ray.copy(alpha = 0f), startY = 0f, endY = 1f)

        // Fish: nose at +1, tail at -1.25.
        val fishShape = Path().apply {
            moveTo(1f, 0f); cubicTo(.55f, -.45f, -.45f, -.42f, -.72f, 0f); cubicTo(-.45f, .42f, .55f, .45f, 1f, 0f); close()
            moveTo(-.64f, 0f); lineTo(-1.25f, -.45f); lineTo(-1.08f, 0f); lineTo(-1.25f, .45f); close()
        }
        val school = List(26) { i -> Fish(rand(i, 211) - .5f, (rand(i, 212) - .5f) * .35f, .7f + rand(i, 213) * .6f, rand(i, 214) * 6.3f) }
        val schools = listOf(School(.45f, .016f, .65f, .55f, 0f), School(.69f, .027f, 1f, 1f, .9f))

        val jellies = listOf(Jelly(.13f, .66f, 24f, 0, 0f), Jelly(.88f, .2f, 17f, 1, 1.7f), Jelly(.85f, .86f, 30f, 2, 3.1f),
            Jelly(.1f, .3f, 13f, 2, 4.4f), Jelly(.56f, 1.04f, 16f, 0, 5.2f))
        // A unit bell with a scalloped rim, opening downward.
        val bell = Path().apply {
            moveTo(-1f, 0f); cubicTo(-1f, -1.3f, 1f, -1.3f, 1f, 0f)
            quadraticTo(.75f, .2f, .5f, .06f); quadraticTo(.25f, .22f, 0f, .06f)
            quadraticTo(-.25f, .22f, -.5f, .06f); quadraticTo(-.75f, .2f, -1f, 0f); close()
        }
        val bellFills = c.jellies.map { color ->
            Brush.radialGradient(0f to color.copy(alpha = .8f * c.jellyAlpha), .55f to color.copy(alpha = .32f * c.jellyAlpha),
                1f to color.copy(alpha = .1f * c.jellyAlpha), center = Offset(0f, -.4f), radius = 1.15f)
        }
        val halos = c.jellies.map { color -> Brush.radialGradient(listOf(color.copy(alpha = if (c.night) .3f else .16f), color.copy(alpha = 0f)), Offset.Zero, 1f) }
        val bellRims = jellies.map { Stroke(1.2f * dp / (it.size * dp)) }
        val arms = jellies.map { Stroke(it.size * dp * .14f, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        val tentacleStroke = Stroke(.9f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tentacles = Path()
        val armPath = Path()

        val motes = List((area / 2400f).roundToInt().coerceIn(24, 200)) { i -> Mote(rand(i, 221), rand(i, 222), .25f + rand(i, 223) * .75f, rand(i, 224) * 6.3f, i % 3 == 0) }
        val glow = Brush.radialGradient(listOf(c.plankton.copy(alpha = .75f), c.plankton.copy(alpha = .22f), c.plankton.copy(alpha = 0f)), Offset.Zero, 1f)

        val bubbles = List((area / 8000f).roundToInt().coerceIn(8, 44)) { i ->
            Bubble(rand(i, 231), rand(i, 232), (1.4f + rand(i, 233).pow(2) * 5f) * dp, (16f + rand(i, 234) * 26f) * dp, rand(i, 235) * 6.3f)
        }
        // Two thin streams rise from the kelp beds.
        val streams = floatArrayOf(.07f, .92f)
        val bubbleFill = Brush.radialGradient(0f to c.bubble.copy(alpha = .04f), .72f to c.bubble.copy(alpha = .1f), 1f to c.bubble.copy(alpha = .42f),
            center = Offset(-.15f, -.2f), radius = 1.15f)
        val bubbleRim = Stroke(.8f * dp)

        val kelps = listOf(Kelp(.02f, .42f, 0f, 5f), Kelp(.085f, .3f, 1.3f, 4f), Kelp(.155f, .19f, 2.2f, 3.2f),
            Kelp(.9f, .33f, .7f, 4.4f), Kelp(.975f, .46f, 2.9f, 5f))
        val kelpStrokes = kelps.map { Stroke(it.width * dp, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        val stalk = Path()

        onDrawBehind {
            val t = time.floatValue

            for (ray in rays) {
                val sway = ray.angle + sin(t * .25f + ray.phase) * 3f
                val pulse = ray.strength * (.6f + .4f * sin(t * .5f + ray.phase))
                withTransform({ translate(ray.x * w, -h * .04f); rotate(sway, Offset.Zero); scale(ray.width * w, h * .8f, Offset.Zero) }) {
                    // Three nested shapes soften the edges of each beam.
                    for (k in 0..2) withTransform({ scale(1f - k * .3f, 1f, Offset.Zero) }) { drawPath(rayShape, rayBrush, alpha = c.rayAlpha * pulse * .5f) }
                }
            }

            for (s in schools) {
                val span = unit * .55f * s.scale
                val cx = (t * s.speed * w + s.offset * w).wrap(w * 2.4f) - w * .7f
                if (cx < -span || cx > w + span) continue
                for (f in school) {
                    val x = cx + f.dx * span + sin(t * 1.1f + f.phase) * 4f * dp
                    val y = h * s.y + f.dy * span + sin(t * .6f + f.dx * 3f) * h * .012f + cos(t * 2f + f.phase) * 1.5f * dp
                    val length = f.size * 6.5f * dp * s.scale
                    withTransform({ translate(x, y); rotate(sin(t * 3f + f.phase) * 5f, Offset.Zero); scale(length, length, Offset.Zero) }) {
                        drawPath(fishShape, c.fish, alpha = c.fishAlpha * s.alpha)
                    }
                }
            }

            jellies.forEachIndexed { index, j ->
                val size = j.size * dp
                val u = t / JELLY_PULSE + j.phase / 6.3f
                val cycle = u - floor(u)
                val squeeze = if (cycle < .2f) easeOutCubic(cycle / .2f) else 1f - easeInOutSine((cycle - .2f) / .8f)
                val rise = (floor(u) + easeOutCubic(min(1f, cycle / .5f))) * size * .5f
                val x = j.x * w + sin(t * .3f + j.phase) * 8f * dp
                val y = (j.y * h - rise + h * .2f).wrap(h * 1.45f) - h * .2f
                val glowing = if (c.night) .75f + .25f * sin(t * .9f + j.phase) else 1f
                val sx = 1f - .15f * squeeze
                val sy = 1f + .1f * squeeze
                val color = c.jellies[j.color]
                withTransform({ translate(x, y); rotate(sin(t * .55f + j.phase) * 7f, Offset.Zero) }) {
                    withTransform({ scale(size * 2.6f, size * 2.6f, Offset.Zero) }) { drawCircle(halos[j.color], 1f, Offset.Zero, glowing) }
                    // Frilled arms in the middle, fine tentacles from the rim; a wave runs down both.
                    armPath.rewind()
                    for (a in 0..2) for (k in 0..10) {
                        val f = k / 10f
                        val px = (a - 1) * size * .16f + sin(k * .8f - t * 2.4f + a * 2f) * (1f + k * .5f) * size * .05f
                        val py = size * .05f + f * size * 1.7f
                        if (k == 0) armPath.moveTo(px, py) else armPath.lineTo(px, py)
                    }
                    drawPath(armPath, color, alpha = (.16f + .18f * glowing) * c.jellyAlpha, style = arms[index])
                    tentacles.rewind()
                    for (n in 0..7) {
                        val rim = (-.8f + 1.6f * n / 7f) * size * sx
                        val length = size * (2.4f + rand(n, index + 240) * 1.2f) * (1f - .12f * squeeze)
                        for (k in 0..12) {
                            val f = k / 12f
                            val px = rim * (1f - .35f * f) + sin(k * .5f - t * 3.3f + n + j.phase) * (.5f + k * .25f) * size * .06f
                            val py = size * .06f + f * length
                            if (k == 0) tentacles.moveTo(px, py) else tentacles.lineTo(px, py)
                        }
                    }
                    drawPath(tentacles, color, alpha = (.25f + .3f * glowing) * c.jellyAlpha, style = tentacleStroke)
                    withTransform({ scale(size * sx, size * sy, Offset.Zero) }) {
                        drawPath(bell, bellFills[j.color], alpha = .7f + .3f * glowing)
                        drawPath(bell, color, alpha = (.45f + .4f * glowing) * c.jellyAlpha, style = bellRims[index])
                        drawOval(Color.White, Offset(-.32f, -.66f), Size(.64f, .3f), alpha = .12f + .2f * glowing)
                    }
                }
            }

            for (m in motes) {
                val y = (m.y * h - t * (4f + 10f * m.depth) * dp).wrap(h * 1.04f) - h * .02f
                val x = m.x * w + sin(t * .4f + m.phase) * 6f * dp * m.depth
                drawCircle(c.mote, (.6f + 1.5f * m.depth) * dp, Offset(x, y), (.12f + .34f * m.depth) * if (c.night) 1f else .8f)
                if (!m.glows) continue
                val light = sin(t * (if (c.night) 1.1f else 1.8f) + m.phase * 3f)
                if (c.night) {
                    val shine = .35f + .65f * light * light
                    withTransform({ translate(x, y); scale((4.5f + 6f * m.depth) * dp, (4.5f + 6f * m.depth) * dp, Offset.Zero) }) { drawCircle(glow, 1f, Offset.Zero, shine) }
                    drawCircle(Color.White, (.7f + m.depth) * dp, Offset(x, y), shine * .9f)
                } else if (light > .6f) {
                    // In the lagoon these are flecks of sunlight instead.
                    withTransform({ translate(x, y); scale(5f * dp * m.depth, 5f * dp * m.depth, Offset.Zero) }) { drawCircle(glow, 1f, Offset.Zero, (light - .6f) * 2.2f) }
                }
            }

            for (b in bubbles) {
                val y = h * 1.03f - (b.start * h * 1.1f + t * b.speed).wrap(h * 1.1f)
                bubble(b.x * w + sin(t * 2.2f + b.wobble) * (1.5f * dp + b.radius * .4f), y, b.radius, bubbleFill, c.bubble, bubbleRim)
            }
            for (s in streams.indices) for (k in 0 until 7) {
                val rise = (t * 38f * dp + k * h * .06f + s * h * .4f).wrap(h * .42f)
                val r = (1.4f + rand(k, 250 + s) * 2.4f) * dp
                bubble(streams[s] * w + sin(t * 3f + k) * 2f * dp + rise * .02f, h * .95f - rise, r, bubbleFill, c.bubble, bubbleRim)
            }

            kelps.forEachIndexed { index, k ->
                val height = k.height * h
                stalk.rewind()
                for (p in 0..12) {
                    val f = p / 12f
                    val x = k.x * w + sin(t * .55f + k.phase + f * 3f) * 14f * dp * f.pow(1.3f)
                    val y = h + 4f * dp - f * height
                    if (p == 0) stalk.moveTo(x, y) else stalk.lineTo(x, y)
                }
                drawPath(stalk, c.kelp, style = kelpStrokes[index])
                for (p in 2..12) {
                    val f = p / 12f
                    val x = k.x * w + sin(t * .55f + k.phase + f * 3f) * 14f * dp * f.pow(1.3f)
                    val y = h + 4f * dp - f * height
                    val length = (17f - 7f * f) * dp
                    val right = p % 2 == 0
                    withTransform({ translate(x, y); rotate((if (right) -28f else 28f) + sin(t * .9f + p + k.phase) * 8f, Offset.Zero) }) {
                        drawOval(c.leaf, Offset(if (right) 0f else -length, -length * .16f), Size(length, length * .32f))
                    }
                }
            }
        }
    }
    Still {
        val w = size.width
        val h = size.height
        val floor = Path().apply {
            moveTo(-w * .05f, h); lineTo(-w * .05f, h * .955f)
            cubicTo(w * .08f, h * .93f, w * .2f, h * .965f, w * .32f, h * .958f)
            cubicTo(w * .46f, h * .95f, w * .56f, h * .975f, w * .7f, h * .965f)
            cubicTo(w * .82f, h * .955f, w * .92f, h * .925f, w * 1.05f, h * .94f)
            lineTo(w * 1.05f, h); close()
        }
        // A boulder in each corner, lit from above.
        val boulders = Path().apply {
            addOval(Rect(-w * .08f, h * .905f, w * .2f, h * 1.04f))
            addOval(Rect(w * .8f, h * .89f, w * 1.1f, h * 1.05f))
        }
        val rockBrush = Brush.verticalGradient(listOf(lerp(c.rock, c.water[1], if (c.night) .25f else .35f), c.rock), startY = h * .89f, endY = h * .97f)
        val brush = Brush.verticalGradient(listOf(c.floor[0], c.floor[1]), startY = h * .89f, endY = h)
        val ripples = if (c.night) null else Path().apply {
            for (k in 0 until 5) {
                val y = h * (.972f + k * .006f)
                moveTo(w * (.24f + rand(k, 260) * .1f), y)
                cubicTo(w * .36f, y - h * .004f, w * .5f, y + h * .004f, w * (.62f + rand(k, 261) * .12f), y)
            }
        }
        onDrawBehind {
            drawPath(floor, brush)
            drawPath(boulders, rockBrush)
            ripples?.let { drawPath(it, Color.White, alpha = .55f, style = Stroke(1.2f * density, cap = StrokeCap.Round)) }
        }
    }
}
