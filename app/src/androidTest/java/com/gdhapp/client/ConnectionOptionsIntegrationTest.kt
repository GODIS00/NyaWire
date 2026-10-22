package com.gdhapp.client

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gdhapp.client.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.vpn.GdhappVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

@RunWith(AndroidJUnit4::class)
class ConnectionOptionsIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val raw = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}]}"""

    @Test fun xraySharedListenerRejectsAnonymousAndAcceptsPassword(): Unit = runBlocking { shared(CoreEngine.XRAY) }
    @Test fun singBoxSharedListenerRejectsAnonymousAndAcceptsPassword(): Unit = runBlocking { shared(CoreEngine.SING_BOX) }
    private suspend fun shared(engine: CoreEngine) {
        val port = ServerSocket(0).use { it.localPort }
        val settings = ClientSettings(coreEngine = engine, hotspotEnabled = true, hotspotUser = "fixture", hotspotPassword = "fixture-password", hotspotPort = port)
        app.coreSessions.open(raw, settings).use {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertEquals(5, socket.getInputStream().read())
                assertEquals(255, socket.getInputStream().read())
            }
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                val output = socket.getOutputStream(); val input = socket.getInputStream()
                output.write(byteArrayOf(5, 1, 2)); assertEquals(5, input.read()); assertEquals(2, input.read())
                val user = settings.hotspotUser.toByteArray(); val password = settings.hotspotPassword.toByteArray()
                output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
                assertEquals(1, input.read()); assertEquals(0, input.read())
            }
        }
    }
    @Test fun nativeCoresAcceptIpPreferenceAndMuxConfig(): Unit = runBlocking {
        for (engine in listOf(CoreEngine.XRAY, CoreEngine.SING_BOX)) {
            app.coreSessions.open(raw, ClientSettings(coreEngine = engine, ipMode = IpMode.IPV4, muxEnabled = true), forProbe = true).use { assertTrue(it.isRunning) }
        }
    }

    @Test fun killSwitchKeepsTheTunnelAfterCoreFailureAndReleasesWhenDisabled(): Unit = runBlocking {
        val original = app.settings.settings.value
        fun tunnelExists(): Boolean {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("ip addr show")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().contains("10.77.0.2") }
        }
        app.settings.update { original.copy(killSwitch = true, coreEngine = CoreEngine.XRAY, verifyOnConnect = false,
            connectionMode = ConnectionMode.VPN, connectOnOpen = false, refreshOnOpen = false, perAppMode = PerAppMode.ALL,
            hotspotEnabled = false, localSocksEnabled = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                // Native Xray rejects this missing encryption field after Android establishes TUN.
                val broken = raw.replace(",\"encryption\":\"none\"", "")
                GdhappVpnService.start(app, broken)
                withTimeout(15_000) { app.vpnSession.state.first { it is VpnState.Error } }
                delay(500)
                assertTrue("Core failure must retain the VPN interface", tunnelExists())
                app.settings.update { it.copy(killSwitch = false) }
                withTimeout(5000) { while (tunnelExists()) delay(100) }
                assertFalse(tunnelExists())
            }
        } finally {
            GdhappVpnService.stop(app)
            delay(300)
            app.settings.update { original }
        }
    }
}
