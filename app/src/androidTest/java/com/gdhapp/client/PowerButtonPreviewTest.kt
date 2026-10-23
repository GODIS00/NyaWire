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
                for (light in listOf(false, true)) {
                    scenario.onActivity { activity -> activity.setContent {
                        val settings = ClientSettings(language = AppLanguage.RUSSIAN, theme = if (light) ThemeMode.LIGHT else ThemeMode.DARK,
                            connectionSound = false, backgroundDensity = 9, powerBorder = PowerBorder.CUSTOM,
                            powerGradientStart = 0xFF6E8BFF, powerGradientEnd = 0xFFE070D3)
                        val state = MainUiState(settings = settings,
                            groups = listOf(SubscriptionGroup("preview", "Список серверов", lastUpdated = "", servers = nodes)))
                        NyaWireTheme(settings) { MainScreen(state, MainActions(), remember { SnackbarHostState() }) }
                    } }
                    Thread.sleep(2000)
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    app.externalCacheDir!!.resolve("power-button-2111-${if (light) "light" else "dark"}.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        } finally { app.settings.update { original } }
    }
}
