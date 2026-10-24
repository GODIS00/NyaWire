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
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** A real VLESS + REALITY + Vision round trip on loopback, with a panel-style Xray profile on both cores. */
@RunWith(AndroidJUnit4::class)
class RealityConnectionTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val uuid = "00000000-0000-4000-8000-000000000005"

    @Test fun xrayCarriesPanelRealityProfile() = roundTrip(CoreEngine.XRAY, "Xray")

    @Test fun singBoxCarriesPanelRealityProfile() = roundTrip(CoreEngine.SING_BOX, "sing-box")
    @Test fun singBoxCarriesNativeRealityProfile() = roundTrip(CoreEngine.SING_BOX, "sing-box", nativeProfile = true)
    @Test fun singBoxDefaultsMissingRealityFingerprint() = roundTrip(CoreEngine.SING_BOX, "sing-box", fingerprint = false)
    @Test fun singBoxRejectsWrongRealityKey() = roundTrip(CoreEngine.SING_BOX, "sing-box", wrongKey = true)

    private fun roundTrip(engine: CoreEngine, name: String, nativeProfile: Boolean = false, fingerprint: Boolean = true, wrongKey: Boolean = false) = runBlocking {
        app.coreSessions.initializeXray()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val certificate = app.cacheDir.resolve("reality-cert.pem").apply { writeBytes(assets.open("fixture-cert.pem").use { it.readBytes() }) }
        val key = app.cacheDir.resolve("reality-key.pem").apply { writeBytes(assets.open("fixture-key.pem").use { it.readBytes() }) }
        val port = ServerSocket(0).use { it.localPort }
        val tlsPort = ServerSocket(0).use { it.localPort }
        val http = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val fixtureTls = com.gdhapp.client.testing.FixtureTls(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        val https = MockWebServer().apply { useHttps(fixtureTls.context.socketFactory, false); start(InetAddress.getByName("127.0.0.1"), 0) }
        // TLS application data is large enough to exercise Vision's direct-copy transition.
        val payload = "NyaWire-REALITY-0123456789\n".repeat(20000)
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
            var profile = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":$port,
                "users":[{"id":"$uuid","encryption":"none","flow":"xtls-rprx-vision"}]}]},
                "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"localhost","fingerprint":"chrome",
                  "publicKey":"lAhm6lcHaQQZleFXYt4W8-zdDo5FYBoqIBPxtpLJTAs","shortId":"aabb","spiderX":"/"}}},
                {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
                "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"type":"field","protocol":["bittorrent"],"outboundTag":"block"}]}}"""
            if (!fingerprint) profile = profile.replace("\"fingerprint\":\"chrome\",", "")
            if (nativeProfile) profile = """{"outbounds":[{"type":"vless","tag":"proxy","server":"127.0.0.1","server_port":$port,
                "uuid":"$uuid","flow":"xtls-rprx-vision","tls":{"enabled":true,"server_name":"localhost",
                "utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,
                "public_key":"lAhm6lcHaQQZleFXYt4W8-zdDo5FYBoqIBPxtpLJTAs","short_id":"aabb"}}}]}"""
            // A valid X25519 public key (the base point), belonging to a different private key.
            if (wrongKey) profile = profile.replace("lAhm6lcHaQQZleFXYt4W8-zdDo5FYBoqIBPxtpLJTAs", "CQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            app.coreSessions.open(profile, ClientSettings(coreEngine = engine), forProbe = true).use { session ->
                assertEquals(name, session.engineName)
                val body = fetch(session.port, http.localPort)
                if (wrongKey) {
                    assertNull("Wrong REALITY key must not reach the destination", body)
                    return@use
                }
                assertEquals("$name through REALITY; core: ${session.errorDetail}", "ok", body)
                val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", session.port)))
                    .sslSocketFactory(fixtureTls.context.socketFactory, fixtureTls.trust).callTimeout(15, TimeUnit.SECONDS).build()
                try {
                    repeat(2) {
                        https.enqueue(MockResponse().setBody(payload))
                        client.newCall(Request.Builder().url(https.url("/vision-transfer").newBuilder().host("localhost").build()).build())
                            .execute().use { response -> assertEquals(200, response.code); assertEquals(payload, response.body!!.string()) }
                    }
                    assertEquals(0, https.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber)
                    assertEquals(1, https.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber)
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        } finally { runCatching { server.stopLoop() }; http.close(); https.shutdown(); certificate.delete(); key.delete() }
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
