package com.gdhapp.client

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.AppLanguage
import com.gdhapp.client.domain.model.VpnMetrics
import com.gdhapp.client.ui.main.MainViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()

    private fun node(text: String): AccessibilityNodeInfo? {
        // Compose exposes virtual children; its provider does not implement the platform text search.
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.text?.toString() == text || current.contentDescription?.toString() == text || current.hintText?.toString() == text) return current
            repeat(current.childCount) { index -> current.getChild(index)?.let { child -> find(child)?.let { return it } } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { find(it) }
    }

    private fun await(text: String): AccessibilityNodeInfo {
        val until = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < until) {
            node(text)?.let { return it }
            Thread.sleep(100)
        }
        error("Missing UI text: $text")
    }

    private fun click(text: String) {
        var target: AccessibilityNodeInfo? = await(text)
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Cannot click $text", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
    }

    @Test fun sectionsNavigateBackAndAboutIsLast() {
        val original = app.settings.settings.value
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, animations = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                click("Настройки")
                val titles = listOf("VPN", "Маршрутизация", "Оформление", "Пинг", "О приложении")
                fun assertOrder() {
                    val tops = titles.map { title -> Rect().also { await(title).getBoundsInScreen(it) }.top }
                    assertEquals(tops.sorted(), tops)
                    assertEquals(tops.size, tops.distinct().size)
                }
                assertOrder()
                click("Маршрутизация")
                await("Режим маршрутизации")
                click("Назад к настройкам")
                val untilGone = System.currentTimeMillis() + 5000
                while (node("Режим маршрутизации") != null && System.currentTimeMillis() < untilGone) Thread.sleep(100)
                assertNull(node("Режим маршрутизации"))
                click("Оформление")
                await("Язык приложения")
                // The destination survives activity recreation.
                scenario.recreate()
                await("Язык приложения")
                click("Назад к настройкам")
                assertOrder()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                app.externalCacheDir?.resolve("settings-230.png")?.outputStream()?.use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally { app.settings.update { original } }
    }

    private fun edit(text: String) {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.className?.toString() == "android.widget.EditText") return current
            repeat(current.childCount) { index -> current.getChild(index)?.let { child -> find(child)?.let { return it } } }
            return null
        }
        val target = find(instrumentation.uiAutomation.rootInActiveWindow!!) ?: error("No editable field")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        Thread.sleep(400)
    }

    @Test fun settingsSearchNavigatesToFunctionAndCustomColorPersists() {
        val original = app.settings.settings.value
        val originalVpn = app.vpnSession.state.value
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, animations = false) }
        app.vpnSession.update(com.gdhapp.client.domain.model.VpnState.Connected)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                click("Настройки")
                await("Поиск настроек")
                assertNull(node("Отключить VPN"))
                edit("свой цвет")
                click("Свой цвет")
                await("Цвет и тема")
                click("Свой цвет")
                edit("3366CC")
                click("Сохранить")
                val until = System.currentTimeMillis() + 3000
                while (app.settings.settings.value.customAccent != 0xFF3366CC && System.currentTimeMillis() < until) Thread.sleep(100)
                assertEquals(0xFF3366CC, app.settings.settings.value.customAccent)
                assertEquals(0xFF3366CC, com.gdhapp.client.data.SettingsRepository(app).settings.value.customAccent)
                click("Назад к настройкам")
                edit("DNS")
                click("Основной DNS")
                await("Сеть")
                await("Основной DNS")
                val image = instrumentation.uiAutomation.takeScreenshot()
                app.externalCacheDir!!.resolve("settings-network-230.png").outputStream().use { output -> image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                image.recycle()
            }
            app.settings.resetAppearance()
            assertNull(app.settings.settings.value.customAccent)
            assertEquals(original.localSocksEnabled, app.settings.settings.value.localSocksEnabled)
        } finally { app.vpnSession.update(originalVpn); app.settings.update { original } }
    }

    @Test fun trafficUpdatesDoNotInvalidateScreenModel() {
        val store = ViewModelStore()
        val original = app.vpnSession.metrics.value
        lateinit var model: MainViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(store, MainViewModel.Factory(app))[MainViewModel::class.java] }
        try {
            val subscriptions = model.uiState.value.groups
            repeat(20) { app.vpnSession.updateMetrics(VpnMetrics(uploadBytes = (it + 1L) * 1000)); Thread.sleep(10) }
            instrumentation.waitForIdleSync()
            assertEquals(20_000L, model.metrics.value.uploadBytes)
            assertEquals(VpnMetrics(), model.uiState.value.metrics)
            assertSame(subscriptions, model.uiState.value.groups)
        } finally {
            app.vpnSession.updateMetrics(original)
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
