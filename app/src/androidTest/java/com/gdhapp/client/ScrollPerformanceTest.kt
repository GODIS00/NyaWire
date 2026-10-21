package com.gdhapp.client

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.data.ShareLinkParser
import com.gdhapp.client.domain.model.*
import com.google.gson.JsonArray
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in paired UI workload. Uses synthetic connection state; no subscription or remote endpoint. */
@RunWith(AndroidJUnit4::class)
class ScrollPerformanceTest {
    @Test fun scrollHundredServersWithConnectionPanel() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("scrollPreview") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        // Recover only this fixture after an interrupted instrumentation run on the test emulator.
        app.subscriptions.groups.value.filter { group -> group.sourceUrl == null && group.servers.size == 100 &&
            group.servers.all { it.host == "192.0.2.1" && it.rawConfigJson.contains("00000000-0000-4000-8000-000000000099") }
        }.forEach { app.subscriptions.remove(it.id) }
        val settings = app.settings.settings.value
        val groups = app.subscriptions.groups.value
        val metrics = app.vpnSession.metrics.value
        val vpn = app.vpnSession.state.value
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        var ticker: Job? = null
        try {
            val profiles = JsonArray()
            repeat(100) { index ->
                profiles.add(ShareLinkParser.parse("vless://00000000-0000-4000-8000-000000000099@192.0.2.1:443?security=none&type=tcp").apply {
                    addProperty("remarks", listOf("🇳🇱 Нидерланды", "🇫🇮 Финляндия", "🇺🇸 США", "🇩🇪 Германия", "🇪🇪 Эстония", "🇷🇺 Россия")[index % 6] + " #${index + 1}")
                })
            }
            app.subscriptions.importText(profiles.toString())
            app.settings.update { it.copy(language = AppLanguage.RUSSIAN, theme = ThemeMode.DARK, animations = false, softBlur = true,
                backgroundPictures = true, connectOnOpen = false, refreshOnOpen = false, layout = ServerLayout.COMFORTABLE) }
            app.vpnSession.update(VpnState.Connected)
            ticker = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    app.vpnSession.updateMetrics(VpnMetrics(connectedAt = System.currentTimeMillis() - 60_000, engine = "Xray", localPort = 10808, proxyOnly = true))
                    delay(1000)
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                // ActivityScenario waits for idleness while launching; enable the drawing clock afterwards.
                app.settings.update { it.copy(animations = true) }
                delay(2000)
                shell("input swipe 540 1900 540 650 400")
                delay(1000)
                shell("dumpsys gfxinfo com.gdhapp.client reset")
                repeat(8) { shell("input swipe 540 1850 540 600 400") }
                repeat(6) { shell("input swipe 540 600 540 1850 400") }
                org.junit.Assert.assertEquals(100, app.subscriptions.groups.value.filter { group -> groups.none { it.id == group.id } }.sumOf { it.servers.size })
                delay(500)
                val variant = args.getString("variant", "ui").replace(Regex("[^A-Za-z0-9_-]"), "")
                app.externalCacheDir!!.resolve("scroll-$variant.txt").writeText(shell("dumpsys gfxinfo com.gdhapp.client framestats"))
                delay(2500) // Capture the idle frosted panels after the final fling has settled.
                val image = instrumentation.uiAutomation.takeScreenshot()
                app.externalCacheDir!!.resolve("scroll-$variant.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                if (args.getString("motionPreview") == "true") {
                    delay(6000)
                    val moved = instrumentation.uiAutomation.takeScreenshot()
                    app.externalCacheDir!!.resolve("scroll-$variant-motion.png").outputStream().use { moved.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    moved.recycle()
                }
                app.settings.update { it.copy(animations = false) }
            }
        } finally {
            ticker?.cancelAndJoin()
            app.vpnSession.update(vpn)
            app.vpnSession.updateMetrics(metrics)
            app.subscriptions.groups.value.filter { group -> groups.none { it.id == group.id } }.forEach { app.subscriptions.remove(it.id) }
            groups.flatMap { it.servers }.firstOrNull { it.isSelected }?.let { app.subscriptions.selectNode(it.id) }
            app.settings.update { settings }
        }
    }
}
