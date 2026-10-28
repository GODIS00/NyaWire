package com.gdhapp.client

import android.graphics.Bitmap
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

/** Long names, a failed ping and provider metadata on phone/tablet and in both themes. */
@RunWith(AndroidJUnit4::class)
class ServerListPresentationTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    @Test fun listControlsStaySeparateAtLargeTextAndMenusStillOpen() {
        val original = app.settings.settings.value
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, refreshOnOpen = false, connectOnOpen = false) }
        val nodes = (1..8).map { index -> ServerNode("visual-$index", if (index == 2) "Нидерланды · Быстрый сервер для видео #2" else "Нидерланды #$index",
            "NL", "VLESS / TCP / REALITY", "{}", isSelected = index == 1,
            host = "example.invalid", port = 443,
            probeResults = mapOf(ProbeMethod.HTTP_GET to if (index == 3) ProbeResult(ProbeMethod.HTTP_GET, error = "Connection closed") else ProbeResult(ProbeMethod.HTTP_GET, latencyMs = 70 + index))) }
        val group = SubscriptionGroup("visual", "Европа · Основная подписка", servers = nodes, lastUpdated = "28.10.2026 16:00",
            sourceUrl = "https://example.invalid/sub", usedTrafficText = "12 ГБ", totalTrafficText = "100 ГБ", expiryDateText = "01.12.2026",
            trafficFraction = .12f, announcementText = "Быстрые серверы для повседневных задач.")
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            listOf("dark" to ClientSettings(), "light" to ClientSettings(theme = ThemeMode.LIGHT),
                "large" to ClientSettings(textScale = 1.2f), "grid" to ClientSettings(layout = ServerLayout.GRID)).forEachIndexed { index, (label, style) ->
                val state = mutableStateOf(MainUiState(settings = style.copy(language = AppLanguage.RUSSIAN, backgroundPictures = false), groups = listOf(group)))
                scenario.onActivity { activity -> activity.setContent { key(index) {
                    NyaWireTheme(state.value.settings) { MainScreen(state.value, MainActions(
                        selectNode = { id -> state.value = state.value.copy(groups = state.value.groups.map { it.copy(servers = it.servers.map { node -> node.copy(isSelected = node.id == id) }) }) }
                    ), remember { SnackbarHostState() }) }
                } } }
                Thread.sleep(1700)
                val size = app.resources.displayMetrics
                if (size.widthPixels < size.heightPixels) shell("input swipe ${size.widthPixels / 2} ${size.heightPixels * 3 / 4} ${size.widthPixels / 2} ${size.heightPixels / 4} 450")
                Thread.sleep(700)
                val image = instrumentation.uiAutomation.takeScreenshot()
                app.externalCacheDir!!.resolve("server-list-$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                val all = accessibilityNodes()
                val menu = all.firstOrNull { it.contentDescription?.toString() == "Действия сервера · Нидерланды #1" }
                    ?: error("First server action is not reachable")
                val title = all.first { it.text?.toString() == "Нидерланды #1" }
                val titleRect = Rect().also(title::getBoundsInScreen)
                val menuRect = Rect().also(menu::getBoundsInScreen)
                assertFalse("Server title overlaps its action button", Rect.intersects(titleRect, menuRect))
                var target: AccessibilityNodeInfo? = menu
                while (target != null && !target.isClickable) target = target.parent
                assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                Thread.sleep(400)
                assertTrue(accessibilityNodes().any { it.text?.toString() == "Удалить сервер" })
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
        } } finally { app.settings.update { original } }
    }

    private fun accessibilityNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { if (node.isVisibleToUser) result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
}
