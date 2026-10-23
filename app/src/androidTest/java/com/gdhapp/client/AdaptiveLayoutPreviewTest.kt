package com.gdhapp.client

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.key
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

/** Preview fixtures only; no network requests or saved server changes. Window size is set by the test runner. */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val prefix = InstrumentationRegistry.getArguments().getString("previewName") ?: "adaptive"
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun find(value: String): AccessibilityNodeInfo {
        val end = android.os.SystemClock.uptimeMillis() + 8000
        do {
            nodes().lastOrNull { it.text?.toString() == value || it.contentDescription?.toString() == value }?.let { return it }
            Thread.sleep(100)
        } while (android.os.SystemClock.uptimeMillis() < end)
        capture("missing-control")
        error("Missing visible control: $value; visible: ${nodes().mapNotNull { it.text?.toString() }}")
    }
    private fun click(value: String) {
        val label = find(value)
        val bounds = Rect().also(label::getBoundsInScreen)
        var clickable: AccessibilityNodeInfo? = label
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
        }
        Thread.sleep(1100)
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(1000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        app.externalCacheDir!!.resolve("$prefix-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun emptyAndPopulatedScreensRemainUsable() {
        val original = app.settings.settings.value
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, refreshOnOpen = false, connectOnOpen = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val settings = ClientSettings(language = AppLanguage.RUSSIAN, connectionSound = false, animations = false, theme = ThemeMode.DARK)
                var serial = 0
                fun render(value: MainUiState) {
                    val id = serial++
                    val current = mutableStateOf(value)
                    scenario.onActivity { activity -> activity.setContent { key(id) {
                        val state = current.value
                        NyaWireTheme(state.settings) { MainScreen(state, MainActions(
                            updateSettings = { current.value = current.value.copy(settings = it) },
                            selectNode = { selected -> current.value = current.value.copy(groups = current.value.groups.map { group -> group.copy(servers = group.servers.map { it.copy(isSelected = it.id == selected) }) }) }
                        ), remember { SnackbarHostState() }) }
                    } } }
                    Thread.sleep(1800)
                }
                render(MainUiState(settings = settings))
                capture("empty-servers")
                val add = find("Добавить подписку")
                val addBounds = Rect().also(add::getBoundsInScreen)
                val screen = instrumentation.uiAutomation.takeScreenshot().also { assertFalse("Display is unavailable", it.width == 0) }
                assertTrue("Add action must fit vertically", addBounds.bottom < screen.height)
                screen.recycle()
                capture("empty-servers")
                click("Добавить подписку")
                find("Добавить серверы")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                Thread.sleep(700)
                click("Пинг")
                capture("empty-ping")
                val servers = (1..12).map { index ->
                    ServerNode("preview-$index", if (index % 2 == 1) "Нидерланды #$index" else "Германия #$index",
                        if (index % 2 == 1) "NL" else "DE", "VLESS / TCP / REALITY", "{}", isSelected = index == 1,
                        host = "example.invalid", port = 443,
                        probeResults = mapOf(ProbeMethod.HTTP_GET to ProbeResult(ProbeMethod.HTTP_GET, latencyMs = 70 + index * 6)))
                }
                val groups = servers.chunked(6).mapIndexed { index, entries ->
                    SubscriptionGroup("preview-$index", if (index == 0) "Основные серверы" else "Резервные серверы", lastUpdated = "23.10.2026", servers = entries)
                }
                render(MainUiState(settings = settings, groups = groups, vpnState = VpnState.Connected,
                    activeNode = servers.first(), metrics = VpnMetrics(engine = "Xray", connectedAt = System.currentTimeMillis() - 60_000)))
                find("Основные серверы")
                capture("servers")
                click("Пинг")
                find("Выбрать самый быстрый")
                capture("ping")
                click("Настройки")
                capture("settings")
                render(MainUiState(settings = settings.copy(theme = ThemeMode.LIGHT), groups = groups))
                capture("light")
            }
        } finally { app.settings.update { original } }
    }
}
