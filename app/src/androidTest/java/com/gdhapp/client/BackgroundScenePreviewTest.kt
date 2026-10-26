package com.gdhapp.client

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.ui.main.*
import com.gdhapp.client.ui.theme.NyaWireTheme
import org.junit.Test
import org.junit.runner.RunWith

/** Screenshots of every background scene on the real home screen with local preview data. Never starts a VPN. */
@RunWith(AndroidJUnit4::class)
class BackgroundScenePreviewTest {
    @Test fun captureScenes() {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = app.settings.settings.value
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, refreshOnOpen = false, connectOnOpen = false, backgroundPictures = false) }
        fun capture(name: String) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            app.externalCacheDir!!.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun find(text: String): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun click(text: String) {
            var target: AccessibilityNodeInfo? = null
            for (attempt in 0 until 10) {
                runCatching { instrumentation.uiAutomation.waitForIdle(300, 3000) }
                target = find(text)
                if (target != null) break
            }
            while (target != null && !target.isClickable) target = target.parent
            checkNotNull(target) { "No clickable $text" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(1500)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val nodes = listOf(
                    ServerNode("scene-nl", "Нидерланды #1", "NL", "VLESS / TCP / REALITY", "{}", isSelected = true, host = "example.invalid", port = 443,
                        probeResults = mapOf(ProbeMethod.HTTP_GET to ProbeResult(ProbeMethod.HTTP_GET, latencyMs = 78))),
                    ServerNode("scene-de", "Германия #2", "DE", "VLESS / WS / TLS", "{}", host = "example.invalid", port = 443,
                        probeResults = mapOf(ProbeMethod.HTTP_GET to ProbeResult(ProbeMethod.HTTP_GET, latencyMs = 92)))
                )
                val base = ClientSettings(language = AppLanguage.RUSSIAN, connectionSound = false, refreshOnOpen = false, connectOnOpen = false)
                val requested = InstrumentationRegistry.getArguments().getString("scene")
                for (scene in BackgroundScene.entries.filter { requested == null || it.name == requested }) for (look in (InstrumentationRegistry.getArguments().getString("looks") ?: "dark,light,amoled").split(',')) {
                    val settings = base.copy(backgroundScene = scene, theme = if (look == "light") ThemeMode.LIGHT else ThemeMode.DARK, amoled = look == "amoled")
                    val state = MainUiState(settings = settings, vpnState = VpnState.Disconnected,
                        groups = listOf(SubscriptionGroup("scene", "Мои серверы", lastUpdated = "", servers = nodes)))
                    scenario.onActivity { activity -> activity.setContent { key(scene, look) {
                        NyaWireTheme(settings) {
                            val view = LocalView.current
                            SideEffect { WindowCompat.getInsetsController(activity.window, view).apply {
                                isAppearanceLightStatusBars = look == "light"
                                isAppearanceLightNavigationBars = look == "light"
                            } }
                            MainScreen(state, MainActions(), remember { SnackbarHostState() })
                        }
                    } } }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(3500)
                    capture("scene-${scene.name.lowercase()}-$look")
                    if (look != "amoled" && scene in setOf(BackgroundScene.AURORA, BackgroundScene.SAKURA)) runCatching {
                        click("Настройки")
                        click("Оформление")
                        // Scroll until the scene tiles sit in the middle of the screen.
                        repeat(4) {
                            if (find("Котики") == null) android.os.ParcelFileDescriptor.AutoCloseInputStream(
                                instrumentation.uiAutomation.executeShellCommand("input swipe 540 1700 540 1100 400")).use { it.readBytes() }
                            Thread.sleep(1200)
                        }
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            instrumentation.uiAutomation.executeShellCommand("input swipe 540 1600 540 1100 400")).use { it.readBytes() }
                        Thread.sleep(1200)
                        capture("scene-settings-${scene.name.lowercase()}-$look")
                    }
                }
            }
        } finally { app.settings.update { original } }
    }
}
