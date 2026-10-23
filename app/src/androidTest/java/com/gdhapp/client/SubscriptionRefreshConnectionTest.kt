package com.gdhapp.client

import android.content.ContextWrapper
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.background.SubscriptionUpdater
import com.gdhapp.client.data.LocalSubscriptionRepository
import com.gdhapp.client.data.ShareLinkParser
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.ui.main.MainViewModel
import com.gdhapp.client.vpn.GdhappVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import libv2ray.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SubscriptionRefreshConnectionTest {
    @Test fun refreshThroughViewModelReplacesRemovedServerWhileTunKeepsWorking(): Unit = runBlocking {
        assumeTrue(android.os.Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        assertNull(VpnService.prepare(app))
        val settings = app.settings.settings.value
        val store = ViewModelStore()
        val directory = app.cacheDir.resolve("active-refresh-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val tls = com.gdhapp.client.testing.FixtureTls(instrumentation.context.applicationInfo.sourceDir)
        val http = MockWebServer().apply { useHttps(tls.context.socketFactory, false); start() }
        val network = OkHttpClient.Builder().sslSocketFactory(tls.context.socketFactory, tls.trust).hostnameVerifier { host, _ -> host == "localhost" }.build()
        val repository = LocalSubscriptionRepository(context, network)
        val port = ServerSocket(0).use { it.localPort }
        val replacementPort = ServerSocket(0).use { it.localPort }
        val uuid = "00000000-0000-4000-8000-000000000066"
        app.coreSessions.initializeXray()
        val server = CoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(code: Long, message: String?) = 0L
        })
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val old = ShareLinkParser.parse("vless://$uuid@127.0.0.1:$port?security=none&type=tcp#Original").toString()
        val fresh = ShareLinkParser.parse("vless://$uuid@127.0.0.1:$replacementPort?security=none&type=tcp#Replacement").toString()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            server.startLoop("""{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"vless","settings":{"clients":[{"id":"$uuid"}],"decryption":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["10.0.2.2/32"]}]}}]}""", 0)
            app.settings.update { it.copy(coreEngine = CoreEngine.XRAY, connectionMode = ConnectionMode.VPN, xrayCompatibilityMode = false,
                bypassLan = false, verifyOnConnect = false, localSocksEnabled = false, perAppMode = PerAppMode.ALL,
                connectOnOpen = false, refreshOnOpen = false) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            http.enqueue(MockResponse().setBody(old))
            repository.importText(http.url("/subscription").toString())
            val group = repository.groups.value.single()
            lateinit var model: MainViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository, app.vpnSession, app.latencyTester,
                        app.settings, app.coreSessions, SubscriptionUpdater(repository, app.settings, app.vpnSession)) as T
                })[MainViewModel::class.java]
            }
            GdhappVpnService.start(app, old)
            assertEquals(VpnState.Connected, withTimeout(20000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } })
            delay(1500)
            assertTrue(shell("sh /data/local/tmp/nyawire-vpn-probe.sh").contains("NyaWire-TUN-OK"))
            val startedAt = app.vpnSession.metrics.value.connectedAt
            val snapshot = app.vpnSession.activeNode.value!!
            http.enqueue(MockResponse().setBody(fresh))
            instrumentation.runOnMainSync { model.refresh(group.id) }
            withTimeout(15000) { while (model.uiState.value.selectedNode?.port != replacementPort || model.uiState.value.refreshing.isNotEmpty()) delay(50) }
            assertEquals("Replacement", model.uiState.value.selectedNode!!.name)
            assertEquals(snapshot, model.uiState.value.connectionNode)
            assertEquals(port, app.vpnSession.activeNode.value!!.port)
            assertEquals(VpnState.Connected, app.vpnSession.state.value)
            assertEquals(startedAt, app.vpnSession.metrics.value.connectedAt)
            assertTrue("The original tunnel must still carry traffic after refresh", shell("sh /data/local/tmp/nyawire-vpn-probe.sh").contains("NyaWire-TUN-OK"))
            http.enqueue(MockResponse().setResponseCode(500))
            val saved = repository.groups.value
            assertTrue(runCatching { repository.refresh(group.id) }.isFailure)
            assertEquals(saved, repository.groups.value)
            assertEquals(snapshot, app.vpnSession.activeNode.value)
        } finally {
            GdhappVpnService.stop(app)
            withTimeoutOrNull(10000) { app.vpnSession.state.first { it == VpnState.Disconnected } }
            instrumentation.runOnMainSync { store.clear() }
            server.stopLoop(); http.shutdown()
            network.connectionPool.evictAll(); network.dispatcher.executorService.shutdown()
            app.settings.update { settings }
            scenario?.close()
            directory.resolve("subscriptions.json").delete(); directory.delete()
        }
    }
}
