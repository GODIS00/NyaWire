package com.gdhapp.client.ui.theme

import kotlin.math.cos
import kotlin.math.sin

/** Seeded once per viewport. No random work or object creation on an animation frame. */
internal fun rainSeed(index: Int, salt: Int): Float {
    var bits = index * 0x45d9f3b + salt * 0x119de1f3
    bits = (bits xor (bits ushr 16)) * 0x45d9f3b
    bits = (bits xor (bits ushr 16)) * 0x45d9f3b
    return ((bits xor (bits ushr 16)) ushr 8) / 16777216f
}

/** A drop accelerates and nearly sticks, but gravity never reverses its travel. */
internal class RainRunoff(index: Int, width: Float, height: Float, density: Float) {
    val radius = (2.3f + rainSeed(index, 1) * 2.6f) * density
    val stretch = 1.15f + rainSeed(index, 2) * .45f
    val trail = (22f + rainSeed(index, 3) * 46f) * density
    val margin = 100f * density
    val span = height + margin * 2f
    private val speed = (15f + rainSeed(index, 4) * 28f) * density
    private val frequency = .55f + rainSeed(index, 5) * .7f
    private val phase = rainSeed(index, 6) * 6.283185f
    private val start = rainSeed(index, 7) * span
    private val baseX = (.035f + rainSeed(index, 8) * .93f) * width
    private val sway = (1f + rainSeed(index, 9) * 2f) * density
    private val bend = (38f + rainSeed(index, 10) * 40f) * density

    fun distance(seconds: Float): Float = start + speed * seconds +
        .88f * speed / frequency * (cos(phase) - cos(seconds * frequency + phase))

    fun y(seconds: Float): Float = ((distance(seconds) % span + span) % span) - margin
    fun x(y: Float): Float = baseX + sway * sin(y / bend + phase) + sway * .3f * sin(y / (bend * .37f) + phase)
}
