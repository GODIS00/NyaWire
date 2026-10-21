package com.gdhapp.client

import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.background.SubscriptionUpdater
import com.gdhapp.client.data.*
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.domain.repository.SubscriptionRepository
import com.gdhapp.client.vpn.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import libv2ray.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ClientFeaturesTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun profile(port: Int, uuid: String = "00000000-0000-4000-8000-000000000099") = ShareLinkParser.parse("vless://$uuid@127.0.0.1:$port?security=none&type=tcp").toString()
    private fun client(port: Int) = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).callTimeout(5, TimeUnit.SECONDS).build()

    @Test fun bothCoresApplyDirectAndBlockedRulesToRealRequests() = runBlocking {
        for (engine in listOf(CoreEngine.XRAY, CoreEngine.SING_BOX)) {
            val http = MockWebServer().apply { enqueue(MockResponse().setBody("direct-route-ok")); start() }
            // No server listens at this VLESS endpoint: success can only come from the direct rule.
            val unused = ServerSocket(0).use { it.localPort }
            val session = app.coreSessions.open(profile(unused), ClientSettings(coreEngine = engine, routingMode = RoutingMode.RULES,
                directIps = "127.0.0.1/32", blockedDomains = "blocked.example"))
            val proxy = client(session.port)
            try {
                proxy.newCall(Request.Builder().url(http.url("/direct")).build()).execute().use { assertEquals("direct-route-ok", it.body!!.string()) }
                assertTrue(runCatching { proxy.newCall(Request.Builder().url("http://blocked.example/").build()).execute().use { it.body!!.string() } }.isFailure)
                assertEquals(1, http.requestCount)
            } finally { session.close(); proxy.connectionPool.evictAll(); proxy.dispatcher.executorService.shutdown(); http.shutdown() }
        }
    }

    @Test fun proxyOnlyServiceWorksWithoutVpnPermissionAndCreatesNoTun(): Unit = runBlocking {
        val original = app.settings.settings.value
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val before = shell("ip -o link show")
        shell("cmd appops set com.gdhapp.client ACTIVATE_VPN deny")
        assertNotNull(VpnService.prepare(app))
        val http = MockWebServer().apply { enqueue(MockResponse().setBody("proxy-service-ok")); start() }
        try {
            app.settings.update { original.copy(connectionMode = ConnectionMode.PROXY, coreEngine = CoreEngine.XRAY, localSocksPort = 0,
                verifyOnConnect = false, routingMode = RoutingMode.RULES, directIps = "127.0.0.1/32", fragmentEnabled = false) }
            GdhappVpnService.start(app, profile(ServerSocket(0).use { it.localPort }))
            val state = withTimeout(15000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } }
            assertEquals(state.toString(), VpnState.Connected, state)
            assertEquals(10808, app.vpnSession.metrics.value.localPort); assertTrue(app.vpnSession.metrics.value.proxyOnly)
            val proxy = client(10808)
            try { proxy.newCall(Request.Builder().url(http.url("/proxy")).build()).execute().use { assertEquals("proxy-service-ok", it.body!!.string()) } }
            finally { proxy.connectionPool.evictAll(); proxy.dispatcher.executorService.shutdown() }
            assertEquals(before.lines().filter { "tun" in it }, shell("ip -o link show").lines().filter { "tun" in it })
            GdhappVpnService.stop(app)
            withTimeout(10000) { app.vpnSession.state.first { it == VpnState.Disconnected } }
        } finally {
            GdhappVpnService.stop(app); delay(500); app.settings.update { original }; http.shutdown()
            shell("cmd appops set com.gdhapp.client ACTIVATE_VPN allow"); scenario.close()
        }
    }

    @Test fun scheduledUpdatesIncludeActiveGroupsAndContinueAfterFailure() = runBlocking {
        val name = "update-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) { override fun getSharedPreferences(n: String, mode: Int) = super.getSharedPreferences(name, mode) }
        val prefs = SettingsRepository(context)
        val node = SubscriptionParser().parse(profile(443)).servers.single().copy(isSelected = true)
        fun group(id: String, age: Long, remote: Boolean = true, selected: Boolean = false) = SubscriptionGroup(id, id, lastUpdated = "fixture", servers = listOf(node.copy(isSelected = selected)),
            sourceUrl = if (remote) "https://example.invalid/$id" else null, updatedAtEpochMs = System.currentTimeMillis() - age)
        val groups = MutableStateFlow(listOf(group("active", 7_200_000, selected = true), group("local", 7_200_000, false), group("recent", 1000), group("broken", 7_200_000), group("due", 7_200_000)))
        val calls = mutableListOf<String>()
        val delegate = app.subscriptions
        val repository = object : SubscriptionRepository by delegate {
            override val groups = groups.asStateFlow()
            override suspend fun refresh(id: String) { calls += id; if (id == "broken") error("fixture failure") }
        }
        val session = VpnSessionStore().apply { update(VpnState.Connected) }
        try {
            SubscriptionUpdater(repository, prefs, session).refreshDue()
            assertEquals(listOf("active", "broken", "due"), calls)
            calls.clear(); prefs.update { it.copy(autoUpdateSubscriptions = false) }
            SubscriptionUpdater(repository, prefs, session).refreshDue(); assertTrue(calls.isEmpty())
            assertNull(app.getSystemService(JobScheduler::class.java).getPendingJob(SubscriptionUpdater.JOB_ID))
            prefs.update { it.copy(autoUpdateSubscriptions = true) }
            val job = app.getSystemService(JobScheduler::class.java).getPendingJob(SubscriptionUpdater.JOB_ID)
            assertNotNull(job); assertTrue(job!!.isPersisted); assertEquals(900_000L, job.intervalMillis)
        } finally { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit(); SubscriptionUpdater.schedule(app, app.settings.settings.value.autoUpdateSubscriptions) }
    }

    @Test fun startupConnectsSelectedProxyOnceAndBootReceiverHonorsItsSwitch(): Unit = runBlocking {
        val original = app.settings.settings.value
        val selected = app.subscriptions.groups.value.flatMap { it.servers }.firstOrNull { it.isSelected }?.id
        val oldIds = app.subscriptions.groups.value.map { it.id }.toSet()
        var scenario: ActivityScenario<MainActivity>? = null
        var fixtureId: String? = null
        try {
            app.subscriptions.importText(profile(ServerSocket(0).use { it.localPort }))
            val fixture = app.subscriptions.groups.value.first { it.id !in oldIds }
            fixtureId = fixture.id; app.subscriptions.selectNode(fixture.servers.first().id)
            app.settings.update { original.copy(connectionMode = ConnectionMode.PROXY, coreEngine = CoreEngine.XRAY, verifyOnConnect = false,
                fragmentEnabled = false, connectOnOpen = true, connectOnBoot = false, localSocksPort = 0) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            assertEquals(VpnState.Connected, withTimeout(15000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } })
            GdhappVpnService.stop(app); withTimeout(10000) { app.vpnSession.state.first { it == VpnState.Disconnected } }
            delay(800); assertEquals(VpnState.Disconnected, app.vpnSession.state.value)
            val boot = android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED)
            com.gdhapp.client.background.StartupReceiver().onReceive(app, boot)
            delay(300); assertEquals(VpnState.Disconnected, app.vpnSession.state.value)
            app.settings.update { it.copy(connectOnBoot = true) }
            com.gdhapp.client.background.StartupReceiver().onReceive(app, boot)
            assertEquals(VpnState.Connected, withTimeout(15000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } })
        } finally {
            GdhappVpnService.stop(app); delay(500); app.settings.update { original }
            fixtureId?.let { app.subscriptions.remove(it) }; selected?.let { app.subscriptions.selectNode(it) }; scenario?.close()
        }
    }

    @Test fun httpsRefreshUpdatesSavedProfileWithoutChangingConnectionSnapshot(): Unit = runBlocking {
        val directory = app.cacheDir.resolve("refresh-race-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val tls = com.gdhapp.client.testing.FixtureTls(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        val http = MockWebServer().apply { useHttps(tls.context.socketFactory, false); start() }
        val network = OkHttpClient.Builder().sslSocketFactory(tls.context.socketFactory, tls.trust).hostnameVerifier { host, _ -> host == "localhost" }.build()
        val session = VpnSessionStore()
        val repository = LocalSubscriptionRepository(context, network)
        val old = profile(443); val fresh = profile(8443)
        try {
            http.enqueue(MockResponse().setBody(old))
            repository.importText(http.url("/subscription").toString())
            val group = repository.groups.value.single()
            http.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    session.setActiveNode(group.servers.single()); session.update(VpnState.Connected)
                    return MockResponse().setBody(fresh)
                }
            }
            repository.refresh(group.id)
            assertEquals(8443, repository.groups.value.single().servers.single().port)
            assertEquals(group.servers.single().rawConfigJson, session.activeNode.value!!.rawConfigJson)
            http.dispatcher = object : okhttp3.mockwebserver.Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody(fresh) }
            repository.refresh(group.id)
            assertEquals(8443, repository.groups.value.single().servers.single().port)
            assertTrue(repository.groups.value.single().servers.single().isSelected)
        } finally { http.shutdown(); network.connectionPool.evictAll(); network.dispatcher.executorService.shutdown(); directory.deleteRecursively() }
    }
}
