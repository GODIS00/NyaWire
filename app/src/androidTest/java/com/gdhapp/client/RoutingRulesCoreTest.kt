package com.gdhapp.client

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gdhapp.client.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Both bundled cores must load the preset categories and route by them; traffic stays on the device. */
@RunWith(AndroidJUnit4::class)
class RoutingRulesCoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    // The proxy outbounds point at TEST-NET-1 and are never reached by the routed requests.
    private val xray = """{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]}}]}"""
    private val singBox = """{"outbounds":[{"type":"tuic","tag":"proxy","server":"192.0.2.1","server_port":443,"uuid":"11111111-1111-4111-8111-111111111111","password":"secret","tls":{"enabled":true,"server_name":"example.com"}}]}"""

    @Test fun xrayRoutesByBundledCategories() = verify(xray, "Xray")
    @Test fun singBoxRoutesByExpandedCategories() = verify(singBox, "sing-box")

    /** A panel-style Xray Hysteria2 profile with its own routing, run on sing-box. */
    @Test fun singBoxRunsXrayPanelProfileWithItsRouting() = runBlocking {
        fun panel(privateTarget: String) = """{"outbounds":[{"tag":"proxy","protocol":"hysteria","settings":{"version":2,"address":"192.0.2.1","port":443},
            "streamSettings":{"network":"hysteria","security":"tls","tlsSettings":{"serverName":"example.com","alpn":["h3"]},"hysteriaSettings":{"version":2,"auth":"secret"}}},
            {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
            "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"type":"field","protocol":["bittorrent"],"outboundTag":"block"},
            {"type":"field","ip":["geoip:private"],"outboundTag":"$privateTarget"}]}}"""
        val settings = ClientSettings(coreEngine = CoreEngine.SING_BOX)
        withLocalServer { port ->
            app.coreSessions.open(panel("direct"), settings).use { session ->
                assertEquals("sing-box", session.engineName)
                assertEquals("profile rule geoip:private → direct", "ok", fetch(session.port, port))
            }
            app.coreSessions.open(panel("block"), settings).use { session ->
                assertNotEquals("profile rule geoip:private → block", "ok", fetch(session.port, port))
            }
        }
    }

    private fun verify(raw: String, engine: String) = runBlocking {
        withLocalServer { port ->
            val presets = ClientSettings(routingMode = RoutingMode.RULES, routeRussiaDirect = true, blockAds = true)
            val direct = presets.copy(importedRouting = ImportedRouting(name = "test", directIps = listOf("geoip:private"), proxySites = listOf("geosite:youtube")))
            app.coreSessions.open(raw, direct).use { session ->
                assertEquals(engine, session.engineName)
                assertEquals("$engine: geoip:private should go direct", "ok", fetch(session.port, port))
            }
            val blocked = presets.copy(importedRouting = ImportedRouting(name = "test", blockIps = listOf("geoip:private")))
            app.coreSessions.open(raw, blocked).use { session ->
                assertNotEquals("$engine: geoip:private should be blocked", "ok", fetch(session.port, port))
            }
        }
    }

    /** Runs [block] with a local HTTP server on 127.0.0.1 that answers "ok". */
    private suspend fun withLocalServer(block: suspend (Int) -> Unit) {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().use { client ->
                client.getInputStream().read(ByteArray(2048))
                client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
            } }
        }
        try { block(server.localPort) } finally { server.close() }
    }

    /** HTTP body fetched from 127.0.0.1:[target] through the core's SOCKS5 listener, or null. */
    private fun fetch(socks: Int, target: Int): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socks), 2000); socket.soTimeout = 5000
            val output = socket.getOutputStream(); val input = DataInputStream(socket.getInputStream())
            output.write(byteArrayOf(5, 1, 0)); check(input.read() == 5 && input.read() == 0)
            output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (target shr 8).toByte(), target.toByte()))
            val reply = ByteArray(10); input.readFully(reply); check(reply[1].toInt() == 0)
            output.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            input.readBytes().decodeToString().substringAfter("\r\n\r\n", "").takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
}
