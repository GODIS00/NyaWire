package com.gdhapp.client

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.*
import kotlinx.coroutines.runBlocking
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** A real VLESS + REALITY + Vision round trip on loopback, with a panel-style Xray profile on both cores. */
@RunWith(AndroidJUnit4::class)
class RealityConnectionTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val uuid = "00000000-0000-4000-8000-000000000005"

    @Test fun xrayCarriesPanelRealityProfile() = roundTrip(CoreEngine.XRAY, "Xray")

    /** sing-box 1.14 strips the X25519MLKEM768 share that current REALITY servers require; say so instead of failing later. */
    @Test fun singBoxRefusesRealityWithAClearMessage() {
        val error = runCatching { roundTrip(CoreEngine.SING_BOX, "sing-box") }.exceptionOrNull()
        assertTrue(error.toString(), error is IllegalArgumentException && error.message.orEmpty().contains("REALITY"))
    }

    private fun roundTrip(engine: CoreEngine, name: String) = runBlocking {
        app.coreSessions.initializeXray()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val certificate = app.cacheDir.resolve("reality-cert.pem").apply { writeBytes(assets.open("fixture-cert.pem").use { it.readBytes() }) }
        val key = app.cacheDir.resolve("reality-key.pem").apply { writeBytes(assets.open("fixture-key.pem").use { it.readBytes() }) }
        val port = ServerSocket(0).use { it.localPort }
        val tlsPort = ServerSocket(0).use { it.localPort }
        val http = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!http.isClosed) runCatching { http.accept().use { client ->
                client.getInputStream().read(ByteArray(2048))
                client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
            } }
        }
        // Synthetic fixture key pair; REALITY's TLS target also stays on loopback.
        val serverConfig = """{"log":{"loglevel":"warning"},"inbounds":[
            {"listen":"127.0.0.1","port":$port,"protocol":"vless","settings":{"clients":[{"id":"$uuid","flow":"xtls-rprx-vision"}],"decryption":"none"},
             "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"target":"127.0.0.1:$tlsPort","serverNames":["localhost"],
               "privateKey":"2OUz9iwAh4Kcr-CgWRH4NpUfWmiAAgS9mrA-msSCm3Y","shortIds":["aabb"]}}},
            {"listen":"127.0.0.1","port":$tlsPort,"protocol":"trojan","settings":{"clients":[{"password":"fixture-only"}]},
             "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"alpn":["h2","http/1.1"],
               "certificates":[{"certificateFile":"${certificate.absolutePath}","keyFile":"${key.absolutePath}"}]}}}],
            "outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.0/8"]}]}}]}"""
        val server = CoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(code: Long, message: String?) = 0L
        })
        try {
            server.startLoop(serverConfig, 0)
            val profile = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":$port,
                "users":[{"id":"$uuid","encryption":"none","flow":"xtls-rprx-vision"}]}]},
                "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"localhost","fingerprint":"chrome",
                  "publicKey":"lAhm6lcHaQQZleFXYt4W8-zdDo5FYBoqIBPxtpLJTAs","shortId":"aabb","spiderX":"/"}}},
                {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
                "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"type":"field","protocol":["bittorrent"],"outboundTag":"block"}]}}"""
            app.coreSessions.open(profile, ClientSettings(coreEngine = engine), forProbe = true).use { session ->
                assertEquals(name, session.engineName)
                val body = fetch(session.port, http.localPort)
                assertEquals("$name through REALITY; core: ${session.errorDetail}", "ok", body)
            }
        } finally { runCatching { server.stopLoop() }; http.close(); certificate.delete(); key.delete() }
    }

    private fun fetch(socks: Int, target: Int): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socks), 2000); socket.soTimeout = 10000
            val output = socket.getOutputStream(); val input = DataInputStream(socket.getInputStream())
            output.write(byteArrayOf(5, 1, 0)); check(input.read() == 5 && input.read() == 0)
            output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (target shr 8).toByte(), target.toByte()))
            val reply = ByteArray(10); input.readFully(reply); check(reply[1].toInt() == 0)
            output.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            input.readBytes().decodeToString().substringAfter("\r\n\r\n", "").takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
}
