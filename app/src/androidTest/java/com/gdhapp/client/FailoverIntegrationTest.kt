package com.gdhapp.client

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gdhapp.client.data.SubscriptionParser
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.vpn.GdhappVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/** Local VLESS peers only. Does not alter the host VPN or use a real subscription. */
@RunWith(AndroidJUnit4::class)
class FailoverIntegrationTest {
    @Test fun runningConnectionSwitchesOnceAfterFailuresAndKeepsThePrimarySelected() = runBlocking {
        assumeTrue(Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val oldSettings = app.settings.settings.value
        val oldGroups = app.subscriptions.groups.value
        val uuid = "00000000-0000-4000-8000-000000000001"
        val ports = List(2) { ServerSocket(0).use { it.localPort } }
        app.coreSessions.initializeXray()
        fun peer() = CoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(code: Long, message: String?) = 0L
        })
        val peers = List(2) { peer() }
        val http = MockWebServer().apply { repeat(10) { enqueue(MockResponse().setResponseCode(204)) }; start() }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            peers.forEachIndexed { index, server -> server.startLoop("""{"log":{"loglevel":"error"},"inbounds":[{"listen":"127.0.0.1","port":${ports[index]},"protocol":"vless","settings":{"clients":[{"id":"$uuid"}],"decryption":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.1/32"]}]}}]}""", 0) }
            val group = SubscriptionParser().parse(ports.mapIndexed { i, port -> "vless://$uuid@127.0.0.1:$port?security=none&type=tcp#${if (i == 0) "Primary" else "Reserve"}" }.joinToString("\n"))
                .let { g -> g.copy(servers = g.servers.mapIndexed { i, n -> n.copy(isSelected = i == 0) }) }
            app.subscriptions.restore(listOf(group)) { app.settings.restore(oldSettings.copy(coreEngine = CoreEngine.XRAY,
                connectionMode = ConnectionMode.PROXY, routingMode = RoutingMode.GLOBAL, verifyOnConnect = true,
                probeUrl = http.url("/generate_204").toString(), probeConnectionMode = ProbeConnectionMode.DEFAULT,
                localSocksPort = 0, hotspotEnabled = false, failoverEnabled = true, backupServerId = group.servers[1].id,
                connectOnOpen = false, refreshOnOpen = false, backgroundPictures = false)) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            GdhappVpnService.start(app, group.servers[0].rawConfigJson)
            val started = withTimeout(45000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } }
            assertEquals("Primary did not connect: $started", VpnState.Connected, started)
            assertEquals(group.servers[0].id, app.vpnSession.activeNode.value?.id)
            peers[0].stopLoop()
            // One failed sample must never be enough to switch.
            delay(35000)
            assertEquals(group.servers[0].id, app.vpnSession.activeNode.value?.id)
            withTimeout(65000) { app.vpnSession.activeNode.first { it?.id == group.servers[1].id } }
            val reserve = withTimeout(30000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } }
            assertEquals("Reserve did not connect: $reserve", VpnState.Connected, reserve)
            assertTrue(app.subscriptions.groups.value.single().servers.first().isSelected)
            GdhappVpnService.stop(app)
            withTimeout(10000) { app.vpnSession.state.first { it == VpnState.Disconnected } }
            delay(1500)
            assertEquals(VpnState.Disconnected, app.vpnSession.state.value)
        } finally {
            GdhappVpnService.stop(app)
            withTimeoutOrNull(10000) { app.vpnSession.state.first { it == VpnState.Disconnected || it is VpnState.Error } }
            peers.forEach { runCatching { it.stopLoop() } }
            http.shutdown(); scenario?.close()
            app.subscriptions.restore(oldGroups) { app.settings.restore(oldSettings) }
        }
    }
}
