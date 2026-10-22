package com.gdhapp.client.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeRevealTest {
    @Test fun shortDragsCloseAndLongDragsOnlyRevealABoundedButton() {
        assertEquals(0f, SwipeReveal.target(20f, 88f), 0f)
        assertEquals(0f, SwipeReveal.target(-20f, 88f), 0f)
        assertEquals(88f, SwipeReveal.target(300f, 88f), 0f)
        assertEquals(-88f, SwipeReveal.target(-300f, 88f), 0f)
    }
}
