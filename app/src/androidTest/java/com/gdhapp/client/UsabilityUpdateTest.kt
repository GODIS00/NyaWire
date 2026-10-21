package com.gdhapp.client

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsabilityUpdateTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 12_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("UI condition timed out", condition())
    }
    private fun click(value: String) {
        var target: AccessibilityNodeInfo? = null
        waitFor {
            target = nodes().lastOrNull { it.text?.toString() == value || it.contentDescription?.toString() == value }
            target != null
        }
        while (target != null && !target!!.isClickable) target = target!!.parent
        assertTrue("Cannot click $value", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(300)
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()
        app.externalCacheDir!!.resolve("$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun deletionExpiresAndAppsCanBeImportedAndExported(): Unit = runBlocking {
        val settings = app.settings.settings.value
        val groups = app.subscriptions.groups.value
        val vpn = app.vpnSession.state.value
        val active = app.vpnSession.activeNode.value
        val metrics = app.vpnSession.metrics.value
        try {
            app.settings.update { it.copy(language = AppLanguage.RUSSIAN, animations = false, refreshOnOpen = false,
                connectOnOpen = false, perAppMode = PerAppMode.EXCLUDE, appPackages = emptySet(),
                theme = ThemeMode.DARK, dynamicColors = false) }
            app.subscriptions.importText("vless://00000000-0000-4000-8000-000000000098@192.0.2.1:443?security=none&type=tcp#Material%20preview%20%E2%80%94%20a%20long%20server%20name")
            val group = app.subscriptions.groups.value.single { candidate -> groups.none { it.id == candidate.id } }
            val node = group.servers.single()
            app.subscriptions.selectNode(node.id)
            app.vpnSession.setActiveNode(node)
            app.vpnSession.update(VpnState.Connected)
            app.vpnSession.updateMetrics(VpnMetrics(engine = "Xray", connectedAt = System.currentTimeMillis() - 65_000,
                uploadBytesPerSecond = 1024, downloadBytesPerSecond = 262144))
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor { nodes().any { it.contentDescription?.toString() == "Отключить VPN" } }
                screenshot("connection-material-270")
                click("Действия сервера · ${node.name}")
                click("Удалить сервер")
                waitFor { nodes().any { it.text?.toString() == "Сервер удалён" } }
                waitFor { nodes().none { it.text?.toString() == "Сервер удалён" } }
                assertTrue("Timeout must not undo deletion", app.subscriptions.groups.value.none { it.id == group.id })
                assertEquals(node, app.vpnSession.activeNode.value)
                assertTrue("An active deleted profile must still offer Stop", nodes().any { it.contentDescription?.toString() == "Отключить VPN" })
                app.vpnSession.update(VpnState.Disconnected)
                app.vpnSession.setActiveNode(null)
                click("Настройки")
                click("Маршрутизация")
                click("Выбранные приложения")
                click("Импорт")
                val chrome = app.packageManager.getApplicationInfo("com.android.chrome", 0).loadLabel(app.packageManager).toString()
                val edit = nodes().single { it.className?.toString() == "android.widget.EditText" }
                assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        "$chrome\ncom.android.settings\norg.example.future\nNo such application\ncom.gdhapp.client")
                }))
                waitFor { nodes().any { it.text?.toString() == "Распознано: 3" } }
                assertTrue(nodes().any { it.text?.toString()?.contains("No such application") == true })
                click("Импорт")
                val expected = setOf("com.android.chrome", "com.android.settings", "org.example.future")
                waitFor { app.settings.settings.value.appPackages == expected }
                assertEquals(expected, com.gdhapp.client.data.SettingsRepository(app).settings.value.appPackages)
                Thread.sleep(1000) // Icon loading is asynchronous and limited to visible rows.
                screenshot("apps-import-270")
                click("Экспорт")
                var copied: String? = null
                instrumentation.runOnMainSync { copied = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() }
                assertEquals(expected.sorted().joinToString("\n"), copied)
                click("Готово")
                screenshot("routing-270")
            }
        } finally {
            app.vpnSession.update(vpn)
            app.vpnSession.setActiveNode(active)
            app.vpnSession.updateMetrics(metrics)
            app.subscriptions.groups.value.filter { candidate -> groups.none { it.id == candidate.id } }.forEach { app.subscriptions.remove(it.id) }
            groups.flatMap { it.servers }.firstOrNull { it.isSelected }?.let { app.subscriptions.selectNode(it.id) }
            app.settings.update { settings }
        }
    }
}
