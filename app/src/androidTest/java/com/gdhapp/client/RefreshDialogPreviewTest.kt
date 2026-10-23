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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in screenshot fixture for visual review, without a network request or saved subscription. */
@RunWith(AndroidJUnit4::class)
class RefreshDialogPreviewTest {
    @Test fun captureRefreshDialog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("copyPreview") == "true")
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val original = app.settings.settings.value
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, connectOnOpen = false, refreshOnOpen = false, animations = false) }
        val previewSettings = app.settings.settings.value
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var previewActivity: MainActivity? = null
                scenario.onActivity { activity -> previewActivity = activity; activity.setContent {
                    val state = MainUiState(settings = previewSettings,
                        groups = listOf(SubscriptionGroup("preview", "Основная подписка", lastUpdated = "", servers = emptyList())),
                        startupRefreshing = true, refreshing = setOf("preview"), refreshTotal = 3, refreshCompleted = 1)
                    NyaWireTheme(state.settings) { MainScreen(state, MainActions(), remember { SnackbarHostState() }) }
                } }
                Thread.sleep(3000)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                app.externalCacheDir!!.resolve("refresh-dialog-250.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                instrumentation.runOnMainSync { previewActivity?.setContent {} }
            }
        } finally { app.settings.update { original } }
    }
}
