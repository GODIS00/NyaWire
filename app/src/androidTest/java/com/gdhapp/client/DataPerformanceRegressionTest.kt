package com.gdhapp.client

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.background.SubscriptionUpdater
import com.gdhapp.client.data.*
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.domain.repository.LatencyTester
import com.gdhapp.client.domain.repository.SubscriptionRepository
import com.gdhapp.client.ui.main.MainViewModel
import com.gdhapp.client.vpn.VpnSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DataPerformanceRegressionTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun links(count: Int) = (1..count).joinToString("\n") {
        "vless://00000000-0000-4000-8000-000000000081@192.0.2.1:${440 + it}?security=none&type=tcp#Server$it"
    }
    private inner class Fixture : AutoCloseable {
        val name = "data-perf-${UUID.randomUUID()}"
        val directory = app.cacheDir.resolve(name).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(ignored: String, mode: Int) = app.getSharedPreferences(name, mode)
        }
        override fun close() { app.deleteSharedPreferences(name); directory.deleteRecursively() }
    }

    @Test fun loadingAndConcurrentImportKeepExistingProfiles(): Unit = runBlocking {
        Fixture().use { f ->
            val seed = LocalSubscriptionRepository(f.context)
            seed.importText(links(300))
            val selected = seed.groups.value.single().servers.single { it.isSelected }.id
            val reopened = LocalSubscriptionRepository(f.context)
            reopened.importText("vless://00000000-0000-4000-8000-000000000081@192.0.2.2:9999?security=none&type=tcp#Added")
            assertEquals(301, reopened.groups.value.sumOf { it.servers.size })
            assertEquals(selected, reopened.groups.value.single().servers.single { it.isSelected }.id)
            val again = LocalSubscriptionRepository(f.context)
            again.awaitLoaded()
            assertEquals(301, again.groups.value.single().servers.size)
        }
    }

    @Test fun batchedProbesSurviveReopenWithoutChangingSelectionOrFavorites(): Unit = runBlocking {
        Fixture().use { f ->
            val repo = LocalSubscriptionRepository(f.context)
            repo.importText(links(100))
            val nodes = repo.groups.value.single().servers
            repo.toggleFavorite(nodes[4].id)
            repo.updateProbes(nodes.associate { it.id to ProbeResult(ProbeMethod.TCP, 42) })
            val reopened = LocalSubscriptionRepository(f.context)
            reopened.awaitLoaded()
            val saved = reopened.groups.value.single().servers
            assertTrue(saved.all { it.probeResults[ProbeMethod.TCP]?.latencyMs == 42 })
            assertEquals(nodes.first().id, saved.single { it.isSelected }.id)
            assertTrue(saved[4].isFavorite)
        }
    }

    @Test fun downloadStartedBeforeRestoreCannotOverwriteRestoredProfiles(): Unit = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(links(2).toResponseBody("text/plain".toMediaType())).build()
            }.build()
            val repo = LocalSubscriptionRepository(f.context, client, sendHwid = { false })
            val original = SubscriptionParser().parse(links(1)).copy(id = "remote", sourceUrl = "https://example.invalid/sub")
            repo.restore(listOf(original)) {}
            val refreshing = launch { repo.refresh("remote") }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                repo.restore(listOf(original.copy(title = "Restored"))) {}
                release.countDown()
                refreshing.join()
                assertEquals("Restored", repo.groups.value.single().title)
                assertEquals(1, repo.groups.value.single().servers.size)
            } finally { release.countDown(); refreshing.cancelAndJoin(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun settingsFlushAndRestoreKeepNewestSnapshot(): Unit = runBlocking {
        Fixture().use { f ->
            val prefs = SettingsRepository(f.context)
            repeat(40) { i -> prefs.update { it.copy(backgroundOpacity = 4 + i % 21) } }
            val wanted = prefs.settings.value
            prefs.flushPending().join()
            assertEquals(wanted, SettingsRepository(f.context).settings.value)
            prefs.update { it.copy(textScale = 1.2f) }
            prefs.restore(wanted.copy(textScale = .9f))
            prefs.flushPending().join()
            assertEquals(.9f, SettingsRepository(f.context).settings.value.textScale)
        }
    }

    @Test fun bulkPingPersistsBatchesAndCancellationKeepsCompletedResults(): Unit = runBlocking {
        Fixture().use { f ->
            val repo = LocalSubscriptionRepository(f.context)
            repo.importText(links(80))
            val preferences = SettingsRepository(f.context)
            preferences.update { it.copy(refreshOnOpen = false, autoUpdateSubscriptions = false, probeMethod = ProbeMethod.TCP) }
            val batches = AtomicInteger()
            val saved = AtomicInteger()
            val delegate = object : SubscriptionRepository by repo {
                override suspend fun updateProbes(results: Map<String, ProbeResult>) {
                    repo.updateProbes(results); batches.incrementAndGet(); saved.addAndGet(results.size)
                }
            }
            val store = ViewModelStore()
            lateinit var model: MainViewModel
            val started = CompletableDeferred<Unit>()
            val cancelMode = AtomicBoolean(false)
            val tester = object : LatencyTester {
                override suspend fun test(node: ServerNode, method: ProbeMethod, settings: ClientSettings): ProbeResult {
                    if (!cancelMode.get()) return ProbeResult(method, 33)
                    if (node.id == repo.groups.value.single().servers.first().id) return ProbeResult(method, 44)
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(cls: Class<T>): T =
                        MainViewModel(delegate, VpnSessionStore(), tester, preferences, app.coreSessions,
                            SubscriptionUpdater(delegate, preferences, VpnSessionStore())) as T
                })[MainViewModel::class.java]
                model.testLatency()
            }
            try {
                withTimeout(5000) { model.uiState.first { !it.isTesting && it.probeCompleted == 80 } }
                assertEquals(80, saved.get())
                assertTrue("Each server was still saved separately", batches.get() < 10)
                assertTrue(repo.groups.value.single().servers.all { it.probeResults[ProbeMethod.TCP]?.latencyMs == 33 })
                saved.set(0); batches.set(0); cancelMode.set(true)
                instrumentation.runOnMainSync { model.testLatency() }
                withTimeout(5000) { started.await() }
                instrumentation.runOnMainSync { model.stopProbes() }
                withTimeout(5000) { model.uiState.first { !it.isTesting } }
                assertEquals(1, saved.get())
                assertEquals(1, batches.get())
                assertEquals(44, repo.groups.value.single().servers.first().probeResults[ProbeMethod.TCP]?.latencyMs)
            } finally {
                instrumentation.runOnMainSync { store.clear() }
                preferences.flushPending().join()
            }
        }
    }
}
