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

/** Verifies the actual frame clock: scrolling/visibility pauses do not restart or fast-forward the rain. */
@RunWith(AndroidJUnit4::class)
class BackgroundAnimationTest {
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
                    Box(Modifier.fillMaxSize()) { SceneBackground(BackgroundScene.RAIN, clock, true, false, Modifier.fillMaxSize()) }
                }
            } }
            instrumentation.waitForIdleSync()
            awaitNextFrame(0f, "Initial visible scene")
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
