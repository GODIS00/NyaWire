package com.gdhapp.client

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.data.LocalSubscriptionRepository
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.vpn.VpnSessionStore
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ServerActionsTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun links(count: Int) = (1..count).joinToString("\n") {
        "vless://00000000-0000-4000-8000-000000000099@192.0.2.1:${440 + it}?security=none&type=tcp#Gesture$it"
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun awaitCondition(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 8000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue("Condition did not become true", condition())
    }
    private fun text(value: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        awaitCondition { found = nodes().firstOrNull { it.text?.toString() == value || it.contentDescription?.toString() == value }; found != null }
        return found!!
    }
    private fun click(value: String) {
        var node: AccessibilityNodeInfo? = text(value)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(250)
    }
    private fun pingButtonTop(): Int {
        // Accessibility bounds can lag behind Compose's inset transform. Compare actual
        // rendered pixels of the wide blue button instead of virtual text/glyph bounds.
        val image = instrumentation.uiAutomation.takeScreenshot()
        try {
            val color = AccentStyle.BLUE.argb.toInt()
            for (y in 200 until image.height - 450) {
                val line = (image.width / 5 until image.width * 4 / 5 step 6)
                if (line.all { x -> (image.getPixel(x, y) and 0xFFFFFF) == (color and 0xFFFFFF) }) return y
            }
            error("Wide ping button not found in rendered image")
        } finally { image.recycle() }
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()
        app.externalCacheDir!!.resolve("$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun swipe(name: String, right: Boolean) {
        text(name)
        val y = bounds(nodes().last { it.text?.toString() == name }).centerY()
        val width = app.resources.displayMetrics.widthPixels
        val left = (width * .16f).toInt()
        val edge = (width * .87f).toInt()
        shell("input swipe ${if (right) left else edge} $y ${if (right) edge else left} $y 300")
        Thread.sleep(600)
    }
    private fun menu(name: String) {
        click("Действия сервера · $name")
    }

    @Test fun swipesMenusUndoAndPingLayout(): Unit = runBlocking {
        val originalSettings = app.settings.settings.value
        val originalGroups = app.subscriptions.groups.value
        val originalState = app.vpnSession.state.value
        val originalActive = app.vpnSession.activeNode.value
        val originalMetrics = app.vpnSession.metrics.value
        try {
            app.settings.update { it.copy(language = AppLanguage.RUSSIAN, animations = false, refreshOnOpen = false, connectOnOpen = false,
                theme = ThemeMode.DARK, accent = AccentStyle.BLUE, customAccent = null, dynamicColors = false,
                sort = ServerSort.ORIGINAL, layout = ServerLayout.COMFORTABLE, probeMethod = ProbeMethod.TCP, probeTimeoutSeconds = 2, probeSamples = 1) }
            app.subscriptions.importText(links(3))
            val group = app.subscriptions.groups.value.single { candidate -> originalGroups.none { it.id == candidate.id } }
            val first = group.servers[0]
            app.subscriptions.selectNode(first.id)
            ActivityScenario.launch(MainActivity::class.java).use {
                text("Gesture1")
                assertFalse(nodes().any { it.className?.toString() == "android.widget.EditText" })
                swipe("Gesture1", true)
                awaitCondition { app.subscriptions.groups.value.single { it.id == group.id }.servers.first().probeResults[ProbeMethod.TCP] != null }
                menu("Gesture1")
                click("Копировать конфигурацию")
                var copied: String? = null
                instrumentation.runOnMainSync { copied = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() }
                assertEquals(first.rawConfigJson, copied)
                assertEquals(3, app.subscriptions.groups.value.single { it.id == group.id }.servers.size)
                swipe("Gesture2", false)
                awaitCondition { app.subscriptions.groups.value.single { it.id == group.id }.servers.size == 2 }
                click("Отменить")
                awaitCondition { app.subscriptions.groups.value.single { it.id == group.id }.servers.size == 3 }
                menu("Gesture3")
                click("Удалить сервер")
                awaitCondition { app.subscriptions.groups.value.single { it.id == group.id }.servers.none { it.name == "Gesture3" } }
                click("Отменить")
                awaitCondition { app.subscriptions.groups.value.single { it.id == group.id }.servers.size == 3 }

                app.vpnSession.setActiveNode(first)
                app.vpnSession.update(VpnState.Connected)
                app.vpnSession.updateMetrics(VpnMetrics(engine = "Xray", connectedAt = System.currentTimeMillis()))
                menu("Gesture1")
                click("Удалить сервер")
                awaitCondition { app.subscriptions.groups.value.single { it.id == group.id }.servers.none { it.id == first.id } }
                assertEquals(VpnState.Connected, app.vpnSession.state.value)
                assertEquals(first, app.vpnSession.activeNode.value)
                click("Отменить")
                awaitCondition { app.subscriptions.groups.value.single { it.id == group.id }.servers.size == 3 }

                app.settings.update { it.copy(probeMethod = ProbeMethod.HTTP_GET) }
                click("Пинг")
                instrumentation.waitForIdleSync()
                Thread.sleep(800) // Allow accessibility bounds to catch up with the new destination.
                screenshot("ping-before-260")
                val buttonTop = pingButtonTop()
                listOf("TCP", "ICMP", "TLS", "HTTP HEAD", "DNS", "HTTP GET").forEach { method ->
                    click(method)
                    instrumentation.waitForIdleSync()
                    Thread.sleep(800)
                    if (method == "TCP") screenshot("ping-tcp-260")
                    assertEquals("Ping card shifted for $method", buttonTop, pingButtonTop())
                }
                assertFalse(nodes().any { it.text?.toString() == "Проверяет интернет через выбранный сервер." })
            }
        } finally {
            app.vpnSession.update(originalState)
            app.vpnSession.setActiveNode(originalActive)
            app.vpnSession.updateMetrics(originalMetrics)
            app.subscriptions.groups.value.filter { candidate -> originalGroups.none { it.id == candidate.id } }.forEach { app.subscriptions.remove(it.id) }
            originalGroups.flatMap { it.servers }.firstOrNull { it.isSelected }?.let { app.subscriptions.selectNode(it.id) }
            app.settings.update { originalSettings }
        }
    }

    @Test fun deleteAndUndoPersistWithoutLosingSelectionOrSubscription(): Unit = runBlocking {
        val folder = app.cacheDir.resolve("server-actions-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = folder }
        try {
            val repository = LocalSubscriptionRepository(context)
            repository.importText(links(2))
            val group = repository.groups.value.single()
            val original = group.servers.first()
            repository.toggleFavorite(original.id)
            val removed = repository.removeNode(original.id)!!
            assertEquals(group.servers[1].id, repository.groups.value.single().servers.single { it.isSelected }.id)
            repository.restoreNode(removed)
            repository.restoreNode(removed) // Undo is idempotent, including after a refresh restores the same ID.
            val saved = LocalSubscriptionRepository(context).groups.value.single().servers
            assertEquals(group.servers.map { it.id }, saved.map { it.id })
            assertEquals(1, saved.count { it.isSelected })
            assertTrue(saved.first().isFavorite)
            repository.removeNode(original.id)
            val last = repository.removeNode(group.servers[1].id)!!
            assertTrue(repository.groups.value.isEmpty())
            repository.restoreNode(last)
            assertEquals(group.id, repository.groups.value.single().id)
            assertTrue(repository.groups.value.single().servers.single().isSelected)

            val remote = group.copy(sourceUrl = "https://example.invalid/sub", servers = listOf(original))
            folder.resolve("subscriptions.json").writeText(Gson().toJson(listOf(remote)))
            val remoteRepository = LocalSubscriptionRepository(context)
            val session = VpnSessionStore().apply { setActiveNode(original); update(VpnState.Connected) }
            val removedRemote = remoteRepository.removeNode(original.id)!!
            assertEquals(remote.sourceUrl, remoteRepository.groups.value.single().sourceUrl)
            assertTrue(remoteRepository.groups.value.single().servers.isEmpty())
            assertEquals(original, session.activeNode.value)
            remoteRepository.restoreNode(removedRemote)
            assertEquals(original.id, LocalSubscriptionRepository(context).groups.value.single().servers.single().id)
        } finally { folder.deleteRecursively() }
    }
}
