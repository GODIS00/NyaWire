package com.gdhapp.client

import android.net.VpnService
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.CoreEngine
import com.gdhapp.client.domain.model.PerAppMode
import com.gdhapp.client.domain.model.ProbeMethod
import com.gdhapp.client.domain.model.ProbeConnectionMode
import com.gdhapp.client.domain.model.VpnState
import com.gdhapp.client.vpn.GdhappVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/** Emulator-only test. Host fixture: tools/testing/host-http-fixture.ps1 and vpn-probe.sh. */
@RunWith(AndroidJUnit4::class)
class VpnServiceIntegrationTest {
    @Test fun xrayForwardsActualTunPacketsAndStopsCleanly() { verifyTun(CoreEngine.XRAY) }
    @Test fun singBoxForwardsActualTunPacketsAndStopsCleanly() { verifyTun(CoreEngine.SING_BOX) }

    @Test fun xrayCompatibilityBridgeForwardsWithoutPublicSocks() { verifyTun(CoreEngine.XRAY, compatibility = true) }

    private fun verifyTun(engine: CoreEngine, compatibility: Boolean = false) = runBlocking {
        assumeTrue("TUN fixture must only run on an emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        assertNull("Grant ACTIVATE_VPN to the emulator fixture before running", VpnService.prepare(app))
        val original = app.settings.settings.value
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val port = ServerSocket(0).use { it.localPort }
        val uuid = "00000000-0000-4000-8000-000000000002"
        app.coreSessions.initializeXray()
        val server = CoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(code: Long, message: String?) = 0L
        })
        val serverConfig = """{"log":{"loglevel":"warning"},"stats":{},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"vless","settings":{"clients":[{"id":"$uuid"}],"decryption":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["10.0.2.2/32"]}]}}]}"""
        val profile = com.gdhapp.client.data.ShareLinkParser.parse("vless://$uuid@127.0.0.1:$port?encryption=none&security=none&type=tcp#%F0%9F%87%B3%F0%9F%87%B1%20Netherlands%20%C2%B7%20TUN%20test")
        if (engine == CoreEngine.XRAY) {
            profile.getAsJsonArray("outbounds")[0].asJsonObject.addProperty("tag", "proxy")
            profile.add("routing", com.google.gson.JsonParser.parseString("""{"rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"proxy"},{"type":"field","domain":["geosite:google"],"outboundTag":"proxy"}]}"""))
        }
        val raw = profile.toString()
        try {
            server.startLoop(serverConfig, 0)
            app.settings.update { original.copy(coreEngine = engine, xrayCompatibilityMode = compatibility, bypassLan = false, verifyOnConnect = false,
                perAppMode = PerAppMode.ALL, appPackages = emptySet(), localSocksPort = 0, localSocksEnabled = false,
                probeMethod = ProbeMethod.HTTP_GET, probeConnectionMode = ProbeConnectionMode.KEEP_ALIVE,
                probeSamples = 2, probeUrl = "http://10.0.2.2:18080/health") }
            GdhappVpnService.start(app, raw)
            val state = withTimeout(15000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } }
            assertEquals(state.toString(), VpnState.Connected, state)
            if (engine == CoreEngine.XRAY && !compatibility) assertEquals("Native TUN must not open SOCKS", 0, app.vpnSession.metrics.value.localPort)
            else java.net.Socket("127.0.0.1", app.vpnSession.metrics.value.localPort).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertEquals(5, socket.getInputStream().read())
                assertEquals("Internal bridge must reject unauthenticated SOCKS", 255, socket.getInputStream().read())
            }
            // Switching the UI language must not restart or tear down an active VPN.
            app.settings.update { it.copy(language = com.gdhapp.client.domain.model.AppLanguage.ENGLISH) }
            delay(1000)
            assertEquals(VpnState.Connected, app.vpnSession.state.value)
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("sh /data/local/tmp/nyawire-vpn-probe.sh")
            val response = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            assertTrue("TUN HTTP did not reach host fixture: $response", response.contains("NyaWire-TUN-OK"))
            val udpDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("sh /data/local/tmp/nyawire-vpn-udp-probe.sh")
            val udpResponse = ParcelFileDescriptor.AutoCloseInputStream(udpDescriptor).bufferedReader().use { it.readText() }
            assertTrue("TUN UDP did not reach host fixture: $udpResponse", udpResponse.contains("NyaWire-UDP-OK"))
            val metrics = withTimeout(5000) { app.vpnSession.metrics.first { it.uploadBytes > 0 && it.downloadBytes > 0 } }
            assertTrue(metrics.uploadBytes > 0); assertTrue(metrics.downloadBytes > 0)
            assertEquals(engine.label, metrics.engine)
            val notifications = app.getSystemService(NotificationManager::class.java)
            val notification = notifications.activeNotifications.first { it.id == 77 }.notification
            assertEquals("🇳🇱 Netherlands · TUN test", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertNull(notification.getLargeIcon())
            assertEquals(listOf("Ping", "Stop"), notification.actions.map { it.title.toString() })
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Transferred:"))
            val connectedAt = app.vpnSession.metrics.value.connectedAt
            val socksPort = app.vpnSession.metrics.value.localPort
            notification.actions.first().actionIntent.send()
            val pingNotification = withTimeout(15000) {
                var current: Notification? = null
                while (current == null) {
                    delay(100)
                    current = notifications.activeNotifications.firstOrNull { it.id == 77 }?.notification?.takeIf {
                        it.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Ping · HTTP GET:")
                    }
                }
                current
            }
            val details = pingNotification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertFalse(details, details.contains("failed")); assertTrue(details, details.contains(" ms"))
            assertEquals(VpnState.Connected, app.vpnSession.state.value)
            assertEquals(connectedAt, app.vpnSession.metrics.value.connectedAt)
            assertEquals(socksPort, app.vpnSession.metrics.value.localPort)
            val afterPingDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("sh /data/local/tmp/nyawire-vpn-probe.sh")
            val afterPing = ParcelFileDescriptor.AutoCloseInputStream(afterPingDescriptor).bufferedReader().use { it.readText() }
            assertTrue("Active TUN must keep forwarding after the temporary ping session: $afterPing", afterPing.contains("NyaWire-TUN-OK"))
            if (engine == CoreEngine.XRAY) {
                // Optional bounded pause for manual notification screenshots on the emulator.
                InstrumentationRegistry.getArguments().getString("visualWaitSeconds")?.toLongOrNull()?.coerceIn(0, 180)?.let { seconds ->
                    val ready = app.cacheDir.resolve("notification-preview-ready").apply { writeText("ready") }
                    val done = app.cacheDir.resolve("finish-notification-preview").apply { delete() }
                    kotlinx.coroutines.withTimeoutOrNull(seconds * 1000) { while (!done.exists()) delay(200) }
                    done.delete()
                    ready.delete()
                }
            }
            // Exercise the actual notification's Stop action, then a stale Ping after stopping.
            notification.actions.last().actionIntent.send()
            withTimeout(10000) { app.vpnSession.state.first { it == VpnState.Disconnected } }
            delay(300)
            assertFalse(notifications.activeNotifications.any { it.id == 77 })
            notification.actions.first().actionIntent.send()
            delay(500)
            assertEquals(VpnState.Disconnected, app.vpnSession.state.value)
            assertFalse(notifications.activeNotifications.any { it.id == 77 })
        } finally {
            GdhappVpnService.stop(app)
            delay(500)
            app.settings.update { original }
            server.stopLoop(); scenario.close()
        }
    }
}
