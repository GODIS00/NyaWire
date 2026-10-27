package com.gdhapp.client

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.ui.main.CachedBackdrop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real GPU pixels: source alignment, live changes, softened backdrop and sharp foreground controls. */
@RunWith(AndroidJUnit4::class)
class FrostedBackdropTest {
    @Test fun cropTracksItsActualPositionAndKeepsControlsSharp() {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = app.settings.settings.value
        app.settings.update { it.copy(backgroundPictures = false, connectOnOpen = false, refreshOnOpen = false) }
        val density = app.resources.displayMetrics.density
        var topColor by mutableStateOf(Color.Red)
        fun pixel(bitmap: Bitmap, x: Float, y: Float) = bitmap.getPixel((x * density).toInt(), (y * density).toInt())
        fun capture(scenario: ActivityScenario<MainActivity>): Bitmap {
            val frame = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                activity.window.decorView.postInvalidateOnAnimation()
            }
            assertTrue("No rendered frame", frame.await(10, TimeUnit.SECONDS))
            Thread.sleep(100)
            return instrumentation.uiAutomation.takeScreenshot()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    val source = rememberGraphicsLayer()
                    var origin by remember { mutableStateOf(Offset.Zero) }
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        Canvas(Modifier.offset(20.dp, 50.dp).size(240.dp, 340.dp)
                            .onGloballyPositioned { origin = it.positionInRoot() }.drawWithContent {
                                source.record { this@drawWithContent.drawContent() }
                                drawLayer(source)
                            }) {
                            drawRect(topColor)
                            drawRect(Color.Blue, Offset(0f, size.height / 2), Size(size.width, size.height / 2))
                            for (i in 40..120) drawRect(if (i % 2 == 0) Color.White else Color.Black,
                                Offset(i * 2.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                        }
                        for (y in listOf(100, 300)) Box(Modifier.offset(40.dp, y.dp).size(160.dp, 60.dp)) {
                            CachedBackdrop(source, true, Modifier.matchParentSize(), origin, radius = 8.dp)
                            Canvas(Modifier.matchParentSize()) {
                                drawRect(Color.Green, Offset(size.width - 8.dp.toPx(), 0f), Size(8.dp.toPx(), size.height))
                            }
                        }
                    }
                } } }
                instrumentation.waitForIdleSync()
                val before = capture(scenario)
                try {
                    val top = pixel(before, 55f, 130f)
                    val bottom = pixel(before, 55f, 330f)
                    assertTrue("Top panel sampled the wrong part of the source", android.graphics.Color.red(top) > 180 && android.graphics.Color.blue(top) < 60)
                    assertTrue("Bottom panel sampled the wrong part of the source", android.graphics.Color.blue(bottom) > 180 && android.graphics.Color.red(bottom) < 60)
                    val softened = pixel(before, 160f, 130f)
                    assertTrue("Backdrop stripes were not blurred", android.graphics.Color.red(softened) in 70..185)
                    val control = pixel(before, 196f, 130f)
                    assertTrue("Foreground control was blurred", android.graphics.Color.green(control) > 240 && android.graphics.Color.red(control) < 15)
                } finally { before.recycle() }
                scenario.onActivity { topColor = Color.Yellow }
                val after = capture(scenario)
                try { assertTrue("Panel retained a stale screenshot", android.graphics.Color.green(pixel(after, 55f, 130f)) > 180) }
                finally { after.recycle() }
            }
        } finally { app.settings.update { original } }
    }
}
