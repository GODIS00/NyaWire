package com.gdhapp.client.ui.theme

import org.junit.Assert.*
import org.junit.Test

class RainMotionTest {
    @Test fun gravityNeverMovesRunoffBackUpTheGlass() {
        for (index in 0..20) {
            val drop = RainRunoff(index, 1080f, 2400f, 3f)
            var previous = drop.distance(0f)
            repeat(7200) { frame ->
                val next = drop.distance((frame + 1) / 60f)
                assertTrue("Drop $index reversed at frame $frame", next >= previous)
                previous = next
            }
        }
    }

    @Test fun loopResetsOnlyAfterTheDropAndItsTrailLeaveTheScreen() {
        for ((width, height, density) in listOf(Triple(192f, 312f, 3f), Triple(1080f, 2400f, 3f), Triple(2560f, 1600f, 2f))) {
            val drop = RainRunoff(5, width, height, density)
            assertTrue(drop.margin > drop.trail + drop.radius * drop.stretch)
            var previous = drop.y(0f)
            repeat(36000) { frame ->
                val y = drop.y((frame + 1) / 60f)
                assertTrue(y.isFinite()); assertTrue(drop.x(y).isFinite())
                assertTrue(y >= -drop.margin && y < height + drop.margin)
                if (y < previous) {
                    assertTrue("Visible drop jumped at the bottom", previous > height + drop.trail)
                    assertTrue("Visible drop jumped at the top", y < -drop.trail)
                }
                previous = y
            }
        }
    }

    @Test fun layoutSeedsAreStableAndStayInRange() {
        val values = (0..1000).map { rainSeed(it, 123) }
        assertEquals(values, (0..1000).map { rainSeed(it, 123) })
        assertTrue(values.all { it >= 0f && it < 1f })
        assertTrue(values.distinct().size > 990)
    }
}
