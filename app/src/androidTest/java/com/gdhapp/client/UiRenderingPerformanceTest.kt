package com.gdhapp.client

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.ui.main.*
import com.gdhapp.client.ui.theme.NyaWireTheme
import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** Opt-in paired workload: the same app, list, display and gestures for before/after profiling. */
@RunWith(AndroidJUnit4::class)
class UiRenderingPerformanceTest {
    @Test fun compareBackgroundAndBlurFrameCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("profileUi") == "true")
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val original = app.settings.settings.value
        app.settings.update { it.copy(refreshOnOpen = false, connectOnOpen = false, backgroundPictures = false) }
        val scene = BackgroundScene.valueOf(args.getString("scene", "CATS"))
        val blur = args.getString("blur", "true") == "true"
        val motion = args.getString("motion", "true") == "true"
        val background = args.getString("background", "true") == "true"
        val variant = args.getString("variant", "baseline").replace(Regex("[^a-zA-Z0-9_-]"), "")
        val servers = List(100) { i -> ServerNode("profile-$i", "Нидерланды #${i + 1}", "NL", "VLESS / TCP / REALITY", "{}",
            isSelected = i == 0, probeResults = mapOf(ProbeMethod.HTTP_GET to ProbeResult(ProbeMethod.HTTP_GET, 70 + i))) }
        val settings = mutableStateOf(ClientSettings(language = AppLanguage.RUSSIAN, backgroundScene = scene, animations = false,
            softBlur = blur, backgroundPictures = background, connectionSound = false))
        val frames = Collections.synchronizedList(mutableListOf<LongArray>())
        val handlerThread = HandlerThread("ui-frame-profile").apply { start() }
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    NyaWireTheme(settings.value) { MainScreen(MainUiState(settings = settings.value,
                        groups = listOf(SubscriptionGroup("profile", "Серверы", lastUpdated = "", servers = servers)),
                        vpnState = VpnState.Connected, activeNode = servers.first(),
                        metrics = VpnMetrics(engine = "Xray", connectedAt = 60000L)), MainActions(), remember { SnackbarHostState() }) }
                }
            }
            Thread.sleep(1400)
            scenario.onActivity { settings.value = settings.value.copy(animations = motion) }
            Thread.sleep(1600)
            val width = app.resources.displayMetrics.widthPixels.toFloat()
            val height = app.resources.displayMetrics.heightPixels.toFloat()
            fun drag(up: Boolean) {
                val down = SystemClock.uptimeMillis()
                val start = if (up) height * .76f else height * .3f
                val end = if (up) height * .3f else height * .76f
                for (step in 0..20) {
                    val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, width * .5f, start + (end - start) * step / 20f, 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, false)) } finally { event.recycle() }
                    Thread.sleep(16)
                }
            }
            drag(true)
            Thread.sleep(1200)
            val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) frames += longArrayOf(
                    metrics.getMetric(FrameMetrics.TOTAL_DURATION), metrics.getMetric(FrameMetrics.DRAW_DURATION),
                    metrics.getMetric(FrameMetrics.GPU_DURATION), dropped.toLong())
            }
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper)) }
            repeat(6) { drag(true) }
            repeat(6) { drag(false) }
            Thread.sleep(800)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            val samples = synchronized(frames) { frames.toList() }
            assertTrue("No frame metrics were delivered", samples.size > 20)
            fun percentile(column: Int, percent: Float): Double {
                val times = samples.map { it[column] }.sorted()
                return times[((times.size - 1) * percent).toInt()] / 1_000_000.0
            }
            val report = mapOf("variant" to variant, "scene" to scene.name, "blur" to blur, "motion" to motion, "background" to background, "frames" to samples.size,
                "over16msPercent" to 100.0 * samples.count { it[0] > 16_666_667 } / samples.size,
                "medianMs" to percentile(0, .5f), "p90Ms" to percentile(0, .9f), "p95Ms" to percentile(0, .95f),
                "drawP90Ms" to percentile(1, .9f), "gpuP90Ms" to percentile(2, .9f))
            val json = Gson().toJson(report)
            app.externalCacheDir!!.resolve("ui-profile-$variant-${scene.name}-$blur.json").writeText(json)
            println("UI_PROFILE $json")
            scenario.onActivity { settings.value = settings.value.copy(animations = false) }
        } } finally { handlerThread.quitSafely(); app.settings.update { original } }
    }
}
