package com.gdhapp.client

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PowerButtonInteractionTest {
    @Test fun cachedButtonHandlesHoldQuickTapAndScrollCancellation() {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = app.settings.settings.value
        app.settings.update { it.copy(refreshOnOpen = false, connectOnOpen = false, language = AppLanguage.RUSSIAN) }
        val node = ServerNode("button", "Нидерланды", "NL", "VLESS / TCP", "{}", isSelected = true)
        val state = mutableStateOf(MainUiState(settings = ClientSettings(language = AppLanguage.RUSSIAN,
            backgroundPictures = false, connectionSound = false), groups = listOf(SubscriptionGroup("button", "Серверы", lastUpdated = "", servers = listOf(node)))))
        var taps = 0
        fun findButton(description: String): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.contentDescription?.toString() == description) return node
                repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child)?.let { return it } } }
                return null
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        }
        fun button(description: String): Rect {
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline) {
                findButton(description)?.let { return Rect().also(it::getBoundsInScreen) }
                Thread.sleep(50)
            }
            error("Missing button: $description")
        }
        fun touch(action: Int, bounds: Rect, down: Long, y: Float = bounds.exactCenterY()) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        fun capture(name: String) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            app.externalCacheDir!!.resolve("power-cache-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                NyaWireTheme(state.value.settings) { MainScreen(state.value, MainActions(connect = {
                    taps++
                    state.value = state.value.copy(vpnState = if (state.value.connectionBusy) VpnState.Disconnected else VpnState.Connecting)
                }), remember { SnackbarHostState() }) }
            } }
            val initial = button("Подключить VPN")
            val held = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, initial, held)
            Thread.sleep(180)
            capture("held")
            assertEquals("A hold must not connect before release", 0, taps)
            touch(MotionEvent.ACTION_UP, initial, held)
            Thread.sleep(300)
            assertEquals(1, taps)
            capture("connecting")
            scenario.onActivity { state.value = state.value.copy(vpnState = VpnState.Connected) }
            Thread.sleep(1500)
            capture("connected")
            val connected = button("Отключить VPN")
            val quick = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, connected, quick)
            touch(MotionEvent.ACTION_UP, connected, quick)
            Thread.sleep(600)
            assertEquals(2, taps)
            val released = button("Подключить VPN")
            val drag = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, released, drag)
            repeat(12) { step -> touch(MotionEvent.ACTION_MOVE, released, drag, released.exactCenterY() + 20f * (step + 1)); Thread.sleep(20) }
            touch(MotionEvent.ACTION_UP, released, drag, released.exactCenterY() + 240f)
            Thread.sleep(500)
            assertEquals("Scrolling across the button must not toggle VPN", 2, taps)
            capture("released")
        } } finally { app.settings.update { original } }
    }
}
