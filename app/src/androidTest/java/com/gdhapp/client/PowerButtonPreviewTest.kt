package com.gdhapp.client

import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.ui.main.*
import com.gdhapp.client.ui.theme.NyaWireTheme
import org.junit.Test
import org.junit.runner.RunWith

/** Local preview data only; never connects to a VPN or changes saved subscriptions. */
@RunWith(AndroidJUnit4::class)
class PowerButtonPreviewTest {
    @Test fun captureHomeWithFloatingButton() {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = app.settings.settings.value
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, refreshOnOpen = false, connectOnOpen = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val nodes = listOf(
                    ServerNode("one", "Нидерланды #1", "NL", "VLESS / TCP / REALITY", "{}", isSelected = true),
                    ServerNode("two", "Германия #2", "DE", "VLESS / WS / TLS", "{}")
                )
                val cases = listOf(false to VpnState.Disconnected, false to VpnState.Connecting,
                    false to VpnState.Error("Не удалось подключиться"), false to VpnState.Connected, true to VpnState.Connected)
                for ((light, vpnState) in cases) {
                    scenario.onActivity { activity -> activity.setContent {
                        val settings = ClientSettings(language = AppLanguage.RUSSIAN, theme = if (light) ThemeMode.LIGHT else ThemeMode.DARK,
                            connectionSound = false, backgroundDensity = 9, powerBorder = PowerBorder.CUSTOM,
                            powerGradientStart = 0xFF6E8BFF, powerGradientEnd = 0xFFE070D3)
                        val state = MainUiState(settings = settings, vpnState = vpnState,
                            groups = listOf(SubscriptionGroup("preview", "Список серверов", lastUpdated = "", servers = nodes)))
                        NyaWireTheme(settings) { MainScreen(state, MainActions(), remember { SnackbarHostState() }) }
                    } }
                    Thread.sleep(2000)
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    val status = when (vpnState) {
                        VpnState.Connected -> "connected"
                        VpnState.Connecting -> "connecting"
                        is VpnState.Error -> "error"
                        else -> "off"
                    }
                    app.externalCacheDir!!.resolve("header-2115-${if (light) "light" else "dark"}-$status.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                // Capture the settings overview too, to review spacing without fixed-height tiles.
                val labels = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                    if (node.isVisibleToUser && (node.text?.toString() == "Настройки" || node.contentDescription?.toString() == "Настройки")) labels.add(node)
                    repeat(node.childCount) { node.getChild(it)?.let(::visit) }
                }
                visit(instrumentation.uiAutomation.rootInActiveWindow)
                val label = labels.last()
                val bounds = android.graphics.Rect().also { label.getBoundsInScreen(it) }
                var target: android.view.accessibility.AccessibilityNodeInfo? = label
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) != true) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
                }
                Thread.sleep(2000)
                val settingsBitmap = instrumentation.uiAutomation.takeScreenshot()
                app.externalCacheDir!!.resolve("settings-compact.png").outputStream().use {
                    settingsBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                settingsBitmap.recycle()
            }
        } finally { app.settings.update { original } }
    }
}
