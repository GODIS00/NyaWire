package com.gdhapp.client

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.background.SubscriptionUpdater
import com.gdhapp.client.data.LocalSubscriptionRepository
import com.gdhapp.client.data.SettingsRepository
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.domain.repository.LatencyTester
import com.gdhapp.client.ui.main.*
import com.gdhapp.client.vpn.VpnSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RefreshAndGestureTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun links(count: Int) = (1..count).joinToString("\n") {
        "vless://00000000-0000-4000-8000-000000000081@192.0.2.1:${440 + it}?security=none&type=tcp#Gesture$it"
    }
    private inner class Fixture : AutoCloseable {
        val original = app.settings.settings.value
        val name = "refresh-gesture-${UUID.randomUUID()}"
        val directory = app.cacheDir.resolve(name).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(ignored: String, mode: Int) = app.getSharedPreferences(name, mode)
        }
        val preferences = SettingsRepository(context).apply { update { it.copy(language = AppLanguage.RUSSIAN,
            refreshOnOpen = false, connectOnOpen = false, autoUpdateSubscriptions = false, probeMethod = ProbeMethod.TCP,
            probeSamples = 1, probeConcurrency = 2, sort = ServerSort.ORIGINAL, dynamicColors = false, theme = ThemeMode.DARK) } }
        val store = ViewModelStore()
        val session = VpnSessionStore()
        fun model(repository: LocalSubscriptionRepository, tester: LatencyTester = CountingTester(), network: () -> Boolean = { true }): MainViewModel {
            lateinit var result: MainViewModel
            instrumentation.runOnMainSync {
                result = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository,
                        session, tester, preferences, app.coreSessions, SubscriptionUpdater(repository, preferences, session, network)) as T
                })[MainViewModel::class.java]
            }
            return result
        }
        @android.annotation.SuppressLint("RestrictedApi")
        fun show(model: MainViewModel): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            // Reuse the actual release composition and its actions. Test-only Compose lambdas
            // reference APIs which R8 legitimately removes from the shipping APK.
            scenario.onActivity { activity ->
                activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${MainViewModel::class.java.canonicalName}", model)
            }
            scenario.recreate()
        }
        override fun close() {
            instrumentation.runOnMainSync { store.clear() }
            app.settings.update { original }
            SubscriptionUpdater.schedule(app, original.autoUpdateSubscriptions)
            app.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
    private class CountingTester : LatencyTester {
        val calls = CopyOnWriteArrayList<String>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        override suspend fun test(node: ServerNode, method: ProbeMethod, settings: ClientSettings): ProbeResult {
            calls += node.id
            val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
            try { delay(180); return ProbeResult(method, latencyMs = 80, samplesMs = listOf(80)) }
            finally { active.decrementAndGet() }
        }
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
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(50)
        assertTrue("Condition did not complete", condition())
    }
    private fun text(value: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await { found = nodes().firstOrNull { it.text?.toString() == value || it.contentDescription?.toString() == value }; found != null }
        return found!!
    }
    private fun click(value: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
        val target = text(value)
        var clickable: AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
            val bounds = Rect().also { target.getBoundsInScreen(it) }
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        Thread.sleep(250)
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        app.externalCacheDir!!.resolve(name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun drag(name: String, fraction: Float, cancel: Boolean = false, beforeUp: () -> Unit = {}) {
        text(name)
        // The selected server name is also shown in the connection card above the list.
        val bounds = Rect().also { nodes().last { node -> node.text?.toString() == name }.getBoundsInScreen(it) }
        val width = app.resources.displayMetrics.widthPixels.toFloat()
        val from = if (fraction >= 0) width * .28f else width * .76f
        val y = bounds.centerY().toFloat()
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, from)
        repeat(10) { step -> Thread.sleep(15); inject(MotionEvent.ACTION_MOVE, from + width * fraction * (step + 1) / 10f) }
        beforeUp()
        inject(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, from + width * fraction)
        Thread.sleep(400)
    }

    @Test fun backCancelsStalledRefreshAndKeepsTheAppUsable(): Unit = runBlocking {
        Fixture().use { fixture ->
            val tls = com.gdhapp.client.testing.FixtureTls(instrumentation.context.applicationInfo.sourceDir)
            val server = MockWebServer().apply { useHttps(tls.context.socketFactory, false); start() }
            val client = OkHttpClient.Builder().sslSocketFactory(tls.context.socketFactory, tls.trust).hostnameVerifier { host, _ -> host == "localhost" }.build()
            try {
                val repository = LocalSubscriptionRepository(fixture.context, client)
                server.enqueue(MockResponse().setBody(links(2)))
                repository.importText(server.url("/subscription").toString()); server.takeRequest(5, TimeUnit.SECONDS)
                val saved = repository.groups.value
                fixture.preferences.update { it.copy(refreshOnOpen = true) }
                val model = fixture.model(repository)
                fixture.show(model).use {
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    instrumentation.runOnMainSync { model.foreground(true) }
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    await { model.uiState.value.startupRefreshing }
                    text("Отмена")
                    screenshot("refresh-280.png")
                    val start = SystemClock.uptimeMillis()
                    shell("input keyevent 4")
                    await { !model.uiState.value.startupRefreshing && model.uiState.value.refreshing.isEmpty() }
                    withTimeout(1500) { model.awaitStartupRefresh() }
                    assertTrue("Back must not wait for a socket timeout", SystemClock.uptimeMillis() - start < 2000)
                    assertEquals(saved, repository.groups.value)
                    await { client.dispatcher.runningCallsCount() == 0 }
                    click("Пинг всех серверов")
                    await { repository.groups.value.flatMap { group -> group.servers }.all { node -> ProbeMethod.TCP in node.probeResults } }
                    assertEquals(2, model.uiState.value.probeTotal)
                }
            } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun cancellationDuringResponseBodyClosesTheCallAndReleasesTheLock(): Unit = runBlocking {
        Fixture().use { fixture ->
            val tls = com.gdhapp.client.testing.FixtureTls(instrumentation.context.applicationInfo.sourceDir)
            val server = MockWebServer().apply { useHttps(tls.context.socketFactory, false); start() }
            val headers = AtomicReference<CountDownLatch?>()
            val client = OkHttpClient.Builder().sslSocketFactory(tls.context.socketFactory, tls.trust).hostnameVerifier { host, _ -> host == "localhost" }
                .addNetworkInterceptor { chain -> chain.proceed(chain.request()).also { headers.get()?.countDown() } }.build()
            try {
                val repository = LocalSubscriptionRepository(fixture.context, client)
                server.enqueue(MockResponse().setBody(links(2)))
                repository.importText(server.url("/subscription").toString()); server.takeRequest(5, TimeUnit.SECONDS)
                val saved = repository.groups.value
                val latch = CountDownLatch(1); headers.set(latch)
                server.enqueue(MockResponse().setBody(links(1)).setBodyDelay(4, TimeUnit.SECONDS))
                val refresh = launch { repository.refresh(saved.single().id) }
                assertTrue(withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) })
                delay(80)
                val start = SystemClock.uptimeMillis()
                refresh.cancel()
                withTimeout(1500) { refresh.join() }
                await { client.dispatcher.runningCallsCount() == 0 }
                assertTrue(SystemClock.uptimeMillis() - start < 2000)
                assertEquals(saved, repository.groups.value)
                server.enqueue(MockResponse().setBody(links(3)))
                withTimeout(2500) { repository.refresh(saved.single().id) }
                assertEquals(3, repository.groups.value.single().servers.size)
            } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun offlineStartupSkipsRequestsAndAllPingIncludesFilteredAndCollapsedServers(): Unit = runBlocking {
        Fixture().use { fixture ->
            val repository = LocalSubscriptionRepository(fixture.context)
            repository.importText(links(3))
            repository.importText("trojan://fixture@192.0.2.2:443?security=tls&type=tcp#Other")
            val ids = repository.groups.value.flatMap { it.servers }.map { it.id }.toSet()
            repository.toggleFavorite(ids.first())
            repository.collapseAll()
            fixture.preferences.update { it.copy(refreshOnOpen = true) }
            var requests = 0
            val remoteRepository = object : com.gdhapp.client.domain.repository.SubscriptionRepository by repository {
                override val groups = kotlinx.coroutines.flow.MutableStateFlow(repository.groups.value.map { it.copy(sourceUrl = "https://example.invalid/offline") })
                override suspend fun refresh(id: String) { requests++; error("Offline startup must not call the repository") }
            }
            assertEquals(0, SubscriptionUpdater(remoteRepository, fixture.preferences, fixture.session) { false }.refreshOnOpen { error("Offline must not show a blocking dialog") })
            assertEquals(0, requests)
            val tester = CountingTester()
            val model = fixture.model(repository, tester)
            instrumentation.runOnMainSync { model.setFavoritesOnly(true); model.setProtocol("VLESS"); model.testLatency() }
            withTimeout(8000) { model.uiState.first { !it.isTesting && it.probeCompleted == 4 } }
            assertEquals(ids, tester.calls.toSet())
            assertEquals(4, tester.calls.size)
            assertTrue(tester.peak.get() <= 2)
            assertTrue(repository.groups.value.flatMap { it.servers }.all { ProbeMethod.TCP in it.probeResults })
        }
    }

    @Test fun swipesRevealButtonsAndNeverActOnRelease(): Unit = runBlocking {
        Fixture().use { fixture ->
            val repository = LocalSubscriptionRepository(fixture.context)
            repository.importText(links(3))
            val tester = CountingTester()
            val model = fixture.model(repository, tester)
            fixture.show(model).use {
                text("Gesture1")
                screenshot("ping-all-280.png")
                drag("Gesture1", .06f)
                drag("Gesture1", -.06f)
                drag("Gesture1", .46f, cancel = true)
                drag("Gesture1", -.46f, cancel = true)
                assertTrue(tester.calls.isEmpty())
                assertEquals(3, repository.groups.value.single().servers.size)
                drag("Gesture1", .46f, beforeUp = {
                    assertTrue("Crossing a threshold must not fire before release", tester.calls.isEmpty())
                    screenshot("swipe-280-held.png")
                })
                Thread.sleep(350)
                assertTrue("Releasing a swipe must only reveal the button", tester.calls.isEmpty())
                val pingBounds = Rect().also { nodes().last { node -> node.text?.toString() == "Gesture1" }.getBoundsInScreen(it) }
                shell("input tap ${(app.resources.displayMetrics.widthPixels * .12f).toInt()} ${pingBounds.centerY()}")
                await { tester.calls.size == 1 && !model.uiState.value.isTesting }
                assertEquals(repository.groups.value.single().servers.first().id, tester.calls.single())
                screenshot("swipe-280-settled.png")
                assertEquals(3, repository.groups.value.single().servers.size)
                drag("Gesture2", -.46f, beforeUp = { assertEquals(3, repository.groups.value.single().servers.size) })
                Thread.sleep(350)
                assertEquals("Releasing a swipe must not delete", 3, repository.groups.value.single().servers.size)
                val deleteBounds = Rect().also { nodes().last { node -> node.text?.toString() == "Gesture2" }.getBoundsInScreen(it) }
                shell("input tap ${(app.resources.displayMetrics.widthPixels * .88f).toInt()} ${deleteBounds.centerY()}")
                await { repository.groups.value.single().servers.size == 2 }
                assertEquals(1, tester.calls.size)
                // Normal taps still select after cancelled gestures and spring return.
                click("Gesture3")
                await { repository.groups.value.single().servers.single { it.isSelected }.name == "Gesture3" }
                val bounds = Rect().also { nodes().last { node -> node.text?.toString() == "Gesture3" }.getBoundsInScreen(it) }
                val x = app.resources.displayMetrics.widthPixels / 2
                shell("input swipe $x ${bounds.centerY()} ${x + 15} ${bounds.centerY() - 180} 400")
                assertEquals("Vertical scrolling must not ping or delete", 1, tester.calls.size)
                assertEquals(2, repository.groups.value.single().servers.size)
            }
        }
    }

    @Test fun connectionAndTunnelSettingsHaveSeparateDestinations(): Unit = runBlocking {
        Fixture().use { fixture ->
            val repository = LocalSubscriptionRepository(fixture.context)
            repository.importText(links(1))
            fixture.show(fixture.model(repository)).use {
                click("Настройки")
                text("Соединение"); text("Туннель"); text("Подписки")
                click("Туннель")
                text("Встроенный TUN Xray")
                screenshot("tunnel-2100.png")
                shell("input swipe 500 1700 500 650 400")
                text("Тип IP")
                shell("input keyevent 4")
                click("Соединение")
                text("Режим подключения")
                screenshot("connection-2100.png")
            }
        }
    }
}
