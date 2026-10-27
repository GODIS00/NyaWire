package com.gdhapp.client

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.data.SubscriptionParser
import com.gdhapp.client.domain.model.AppLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupSettingsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result.add(node); repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val until = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < until) {
            nodes().firstOrNull { !it.isEditable && (it.text?.toString() == text || it.contentDescription?.toString() == text) }?.let { return it }
            Thread.sleep(100)
        }
        save("backup-ui-failure")
        error("Missing: $text; visible: ${nodes().mapNotNull { it.text?.toString() }}")
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = await(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(300)
    }
    private fun edit(node: AccessibilityNodeInfo, text: String) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        Thread.sleep(300)
    }
    private fun search(text: String) = edit(nodes().first { it.className?.toString() == "android.widget.EditText" }, text)
    @Test fun chooseReserveAndOpenPasswordDialogWithoutAddingMainScreenControls() {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val original = app.settings.settings.value
        val oldGroups = app.subscriptions.groups.value
        val fixture = SubscriptionParser().parse("vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=none&type=tcp#Primary\nvless://00000000-0000-4000-8000-000000000002@192.0.2.2:443?security=none&type=tcp#Reserve")
        kotlinx.coroutines.runBlocking { app.subscriptions.restore(listOf(fixture)) { app.settings.restore(original.copy(language = AppLanguage.RUSSIAN,
            backgroundPictures = false, connectOnOpen = false, refreshOnOpen = false, backupServerId = null, failoverEnabled = false)) } }
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click("Настройки"); search("Резервный сервер"); click("Резервный сервер")
            click("Выбрать сервер"); click("Reserve"); click("Переключаться при сбое")
            assertEquals(fixture.servers[1].id, app.settings.settings.value.backupServerId)
            assertTrue(app.settings.settings.value.failoverEnabled)
            save("reserve-settings")
            click("Назад к настройкам"); search("Резервная копия"); click("Резервная копия")
            click("Создать копию"); await("Повторите пароль")
            click("Без пароля")
            assertEquals(0, nodes().count { it.className?.toString() == "android.widget.EditText" })
            await("Ключи серверов и ссылки подписок будут доступны для чтения в файле.")
            save("backup-without-password")
            click("С паролем"); await("Повторите пароль")
            val inputs = nodes().filter { it.className?.toString() == "android.widget.EditText" }
            assertEquals(2, inputs.size)
            inputs.forEach { edit(it, "test-password") }
            save("backup-password")
            scenario.recreate()
            await("Повторите пароль")
            // Passwords deliberately do not survive recreation or enter SavedState.
            val button = await("Сохранить файл")
            var clickable: AccessibilityNodeInfo? = button
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertFalse(clickable?.isEnabled == true)
            click("Отмена")
            save("backup-settings")
        } } finally { kotlinx.coroutines.runBlocking { app.subscriptions.restore(oldGroups) { app.settings.restore(original) } } }
    }
    private fun save(name: String) {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { app.externalCacheDir!!.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
