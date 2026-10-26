package com.gdhapp.client

import android.graphics.Bitmap
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.BackgroundScene
import com.gdhapp.client.domain.model.ThemeMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual shrunken app. No Compose classes are injected from the test APK. */
@RunWith(AndroidJUnit4::class)
class RainReleaseSmokeTest {
    @Test fun realHomeAnimatesAndHonorsSystemReducedMotion() {
        assumeTrue("Changes animation settings only on the test emulator", Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val original = app.settings.settings.value
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val scale = shell("settings get global animator_duration_scale").takeIf { it.matches(Regex("[0-9.]+")) } ?: "1"
        fun screenshot(name: String): Bitmap = instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            app.externalCacheDir!!.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun changed(a: Bitmap, b: Bitmap): Int {
            var pixels = 0
            // Left background strip: excludes the system clock and the moving VPN button border.
            for (y in a.height / 4 until a.height * 3 / 4 step 3) for (x in 0 until a.width / 10 step 3) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) pixels++
            }
            return pixels
        }
        try {
            shell("settings put global animator_duration_scale 1")
            app.settings.update { it.copy(backgroundScene = BackgroundScene.RAIN, backgroundPictures = true,
                theme = ThemeMode.DARK, amoled = false, refreshOnOpen = false, connectOnOpen = false) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(1000)
                val first = screenshot("rain-release-moving-a")
                Thread.sleep(600)
                val second = screenshot("rain-release-moving-b")
                try { assertTrue("Rain did not move in the real release UI", changed(first, second) > 5) }
                finally { first.recycle(); second.recycle() }
                for (look in listOf("dark", "light", "amoled")) {
                    app.settings.update { it.copy(theme = if (look == "light") ThemeMode.LIGHT else ThemeMode.DARK, amoled = look == "amoled") }
                    Thread.sleep(700)
                    screenshot("rain-release-$look").recycle()
                }
                shell("settings put global animator_duration_scale 0")
                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(300)
                scenario.moveToState(Lifecycle.State.RESUMED)
                Thread.sleep(700)
                val stillA = screenshot("rain-release-still-a")
                Thread.sleep(500)
                val stillB = screenshot("rain-release-still-b")
                try { assertEquals("Rain moved with system animations disabled", 0, changed(stillA, stillB)) }
                finally { stillA.recycle(); stillB.recycle() }
            }
        } finally {
            shell("settings put global animator_duration_scale $scale")
            app.settings.update { original }
        }
    }
}
