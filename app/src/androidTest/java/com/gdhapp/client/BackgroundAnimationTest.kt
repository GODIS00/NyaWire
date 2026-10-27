package com.gdhapp.client

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.BackgroundScene
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.ThemeMode
import com.gdhapp.client.ui.theme.NyaWireTheme
import com.gdhapp.client.ui.theme.SceneBackground
import com.gdhapp.client.ui.theme.rememberBackgroundClock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies background motion during scrolling and clock continuity across visibility pauses. */
@RunWith(AndroidJUnit4::class)
class BackgroundAnimationTest {
    @Test fun rainKeepsMovingDuringAnActualServerListDrag() {
        assumeTrue("Uses only the dedicated test emulator", Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<GdhappApplication>()
        val original = app.settings.settings.value
        app.settings.update { it.copy(backgroundPictures = false, refreshOnOpen = false, connectOnOpen = false) }
        val settings = ClientSettings(theme = ThemeMode.DARK, backgroundScene = BackgroundScene.RAIN)
        val nodes = List(100) { index -> com.gdhapp.client.domain.model.ServerNode("scroll-$index", "Server $index",
            protocolSummary = "VLESS · TCP", rawConfigJson = "{}", isSelected = index == 0) }
        val group = com.gdhapp.client.domain.model.SubscriptionGroup("scroll-fixture", "Servers", lastUpdated = "", servers = nodes)
        var before: android.graphics.Bitmap? = null
        var after: android.graphics.Bitmap? = null
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                NyaWireTheme(settings) { com.gdhapp.client.ui.main.MainScreen(
                    com.gdhapp.client.ui.main.MainUiState(groups = listOf(group), settings = settings),
                    com.gdhapp.client.ui.main.MainActions(), androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }) }
            } }
            Thread.sleep(800)
            val width = app.resources.displayMetrics.widthPixels.toFloat()
            val height = app.resources.displayMetrics.heightPixels.toFloat()
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, y: Float) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, width * .55f, y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            touch(android.view.MotionEvent.ACTION_DOWN, height * .75f)
            try {
                for (step in 1..40) {
                    touch(android.view.MotionEvent.ACTION_MOVE, height * .75f - step * 7f)
                    Thread.sleep(40)
                    if (step == 20) before = instrumentation.uiAutomation.takeScreenshot()
                    if (step == 35) after = instrumentation.uiAutomation.takeScreenshot()
                }
            } finally { touch(android.view.MotionEvent.ACTION_UP, height * .75f - 280f) }
            val a = checkNotNull(before); val b = checkNotNull(after)
            val density = app.resources.displayMetrics.density
            var changed = 0
            // The outer list gutter contains only the fixed background, never moving server cards.
            for (edge in listOf(0, 1)) for (offset in (2 * density).toInt()..(9 * density).toInt()) {
                val x = if (edge == 0) offset else a.width - 1 - offset
                for (y in a.height / 3 until a.height * 4 / 5) if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
            }
            assertTrue("The background froze while the finger was dragging the list: $changed changed pixels", changed > 20)
        } } finally { before?.recycle(); after?.recycle(); app.settings.update { original } }
    }

    @Test fun captureRainDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Opt-in visual review", InstrumentationRegistry.getArguments().getString("captureRain") == "true")
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<GdhappApplication>()
        val original = app.settings.settings.value
        app.settings.update { it.copy(backgroundPictures = false, refreshOnOpen = false, connectOnOpen = false) }
        val time = mutableFloatStateOf(14f)
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (look in listOf("dark", "light", "amoled")) {
                val settings = ClientSettings(theme = if (look == "light") ThemeMode.LIGHT else ThemeMode.DARK, amoled = look == "amoled")
                scenario.onActivity { activity -> activity.setContent {
                    NyaWireTheme(settings) { Box(Modifier.fillMaxSize()) {
                        SceneBackground(BackgroundScene.RAIN, time, look != "light", look == "amoled", Modifier.fillMaxSize())
                    } }
                } }
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                for (frame in 0..2) {
                    scenario.onActivity { time.floatValue = 14f + frame * .2f }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(100)
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    app.externalCacheDir!!.resolve("rain-detail-$look-$frame.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        } } finally { app.settings.update { original } }
    }

    @Test fun rainPausesAndResumesWithoutJumping() {
        assumeTrue("Uses only the dedicated test emulator", Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<GdhappApplication>()
        val original = app.settings.settings.value
        app.settings.update { it.copy(backgroundPictures = false, refreshOnOpen = false, connectOnOpen = false) }
        var running by mutableStateOf(true)
        lateinit var clock: FloatState
        val clockReady = java.util.concurrent.CountDownLatch(1)
        fun awaitNextFrame(previous: Float, label: String) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000L
            while (clock.floatValue <= previous && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(16)
            assertTrue("$label: clock stayed at ${clock.floatValue}; was $previous; animators=${android.animation.ValueAnimator.areAnimatorsEnabled()}", clock.floatValue > previous)
            assertTrue("$label: paused time was replayed", clock.floatValue - previous < .2f)
        }
        val settings = ClientSettings(theme = ThemeMode.DARK, backgroundScene = BackgroundScene.RAIN)
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                NyaWireTheme(settings) {
                    clock = rememberBackgroundClock(running, 60)
                    androidx.compose.runtime.SideEffect { clockReady.countDown() }
                    Box(Modifier.fillMaxSize()) { SceneBackground(BackgroundScene.RAIN, clock, true, false, Modifier.fillMaxSize()) }
                }
            } }
            instrumentation.waitForIdleSync()
            assertTrue("The scene did not compose", clockReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
            awaitNextFrame(clock.floatValue, "Initial visible scene")
            scenario.onActivity { running = false }
            instrumentation.waitForIdleSync()
            Thread.sleep(150)
            val paused = clock.floatValue
            Thread.sleep(350)
            assertEquals("Motion continued during a scroll pause", paused, clock.floatValue, 0f)
            scenario.onActivity { running = true }
            instrumentation.waitForIdleSync()
            awaitNextFrame(paused, "Scroll resume")
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(150)
            val hidden = clock.floatValue
            Thread.sleep(400)
            assertEquals("Motion continued in the background", hidden, clock.floatValue, 0f)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitNextFrame(hidden, "Activity resume")
        } } finally { app.settings.update { original } }
    }
}
