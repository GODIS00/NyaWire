package com.gdhapp.client

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.data.SubscriptionParser
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.CoreEngine
import com.gdhapp.client.domain.model.ProbeMethod
import com.gdhapp.client.domain.model.ProbeConnectionMode
import kotlinx.coroutines.runBlocking
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest

/** Real native-core tests with a local VLESS server; never creates a production subscription. */
@RunWith(AndroidJUnit4::class)
class NativeCoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val uuid = "00000000-0000-4000-8000-000000000001"

    @Test fun bothBundledCoresLoadAndReportVersions() = runBlocking {
        val versions = app.coreSessions.versions()
        assertFalse(versions.toString(), versions.values.any { it.contains("Ошибка", true) || it.contains("error", true) })
        assertTrue(versions["Xray"].orEmpty().contains("Xray"))
        assertTrue(versions["sing-box"].orEmpty().contains("1.14.3"))
    }

    @Test fun xrayCanAuthenticateAndForwardHttpGetAndHead() = verifyProxy(CoreEngine.XRAY)
    @Test fun singBoxCanAuthenticateAndForwardHttpGetAndHead() = verifyProxy(CoreEngine.SING_BOX)
    @Test fun xrayCanForwardHysteria2Quic() = verifyHysteria(CoreEngine.XRAY)
    @Test fun singBoxCanForwardHysteria2Quic() = verifyHysteria(CoreEngine.SING_BOX)
    @Test fun xrayCanForwardWithGeoipAndGeositeRouting() = verifyProxy(CoreEngine.XRAY, withRouting = true)

    @Test fun httpProbeModesControlActualConnectionReuse() = runBlocking {
        app.coreSessions.initializeXray()
        val port = ServerSocket(0).use { it.localPort }
        val server = CoreController(callback())
        val config = """{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"vless","settings":{"clients":[{"id":"$uuid"}],"decryption":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.0/8"]}]}}]}"""
        try {
            server.startLoop(config, 0)
            val node = SubscriptionParser().parse(com.gdhapp.client.data.ShareLinkParser.parse("vless://$uuid@127.0.0.1:$port?encryption=none&security=none&type=tcp#Pool-test").toString()).servers.single()
            for (mode in ProbeConnectionMode.entries) for (method in listOf(ProbeMethod.HTTP_GET, ProbeMethod.HTTP_HEAD)) {
                val http = MockWebServer()
                try {
                    val requestsCount = if (mode == ProbeConnectionMode.KEEP_ALIVE) 4 else 3
                    repeat(requestsCount) { http.enqueue(MockResponse().setResponseCode(200).apply { if (method == ProbeMethod.HTTP_GET) setBody("ok") }) }
                    http.start()
                    val settings = ClientSettings(coreEngine = CoreEngine.XRAY, probeUrl = http.url("/health").toString(), probeSamples = 3, probeConnectionMode = mode)
                    val result = app.latencyTester.test(node, method, settings)
                    assertTrue("$mode $method: ${result.error}", result.isSuccess)
                    assertEquals(3, result.samplesMs.size)
                    val requests = (1..requestsCount).map { checkNotNull(http.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
                    assertEquals("$mode $method connections", if (mode == ProbeConnectionMode.DISABLE) List(requestsCount) { 0 } else (0 until requestsCount).toList(), requests.map { it.sequenceNumber })
                    assertTrue(requests.all { it.method == if (method == ProbeMethod.HTTP_HEAD) "HEAD" else "GET" })
                    if (mode == ProbeConnectionMode.DISABLE) assertTrue(requests.all { it.getHeader("Connection").equals("close", true) })
                    if (mode == ProbeConnectionMode.KEEP_ALIVE) assertTrue(requests.all { it.getHeader("Connection").equals("keep-alive", true) })
                } finally { http.shutdown() }
            }
        } finally { server.stopLoop() }
    }

    private fun verifyHysteria(engine: CoreEngine) = runBlocking {
        app.coreSessions.initializeXray()
        val http = HttpFixture()
        val port = java.net.DatagramSocket(0).use { it.localPort }
        val server = CoreController(callback())
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val certificate = app.cacheDir.resolve("fixture-cert.pem").apply { writeBytes(assets.open("fixture-cert.pem").use { it.readBytes() }) }
        val key = app.cacheDir.resolve("fixture-key.pem").apply { writeBytes(assets.open("fixture-key.pem").use { it.readBytes() }) }
        val config = """{"log":{"loglevel":"warning"},"stats":{},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"hysteria","settings":{"version":2,"clients":[{"auth":"fixture-password"}]},"streamSettings":{"network":"hysteria","security":"tls","hysteriaSettings":{"version":2},"tlsSettings":{"alpn":["h3"],"certificates":[{"certificateFile":"${certificate.absolutePath}","keyFile":"${key.absolutePath}"}]}}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.0/8"]}]}}]}"""
        try {
            server.startLoop(config, 0)
            val raw = """{"outbounds":[{"protocol":"hysteria","settings":{"version":2,"address":"127.0.0.1","port":$port},"streamSettings":{"network":"hysteria","security":"tls","tlsSettings":{"serverName":"localhost","allowInsecure":true,"alpn":["h3"]},"hysteriaSettings":{"version":2,"auth":"fixture-password"}}}]}"""
            val node = SubscriptionParser().parse(raw).servers.single()
            val settings = ClientSettings(coreEngine = engine, probeUrl = "http://127.0.0.1:${http.port}/health", probeTimeoutSeconds = 8)
            val result = app.latencyTester.test(node, ProbeMethod.HTTP_GET, settings)
            assertTrue("$engine Hysteria2: ${result.error}", result.isSuccess)
            assertEquals(204, result.httpStatus)
        } finally { server.stopLoop(); http.close(); certificate.delete(); key.delete() }
    }

    @Test fun keepAliveSingleSampleExcludesColdRequestButRetainsResponseDelay() = runBlocking {
        app.coreSessions.initializeXray()
        val port = ServerSocket(0).use { it.localPort }
        val server = CoreController(callback())
        val config = """{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"vless","settings":{"clients":[{"id":"$uuid"}],"decryption":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.0/8"]}]}}]}"""
        try {
            server.startLoop(config, 0)
            val node = SubscriptionParser().parse(com.gdhapp.client.data.ShareLinkParser.parse("vless://$uuid@127.0.0.1:$port?security=none&type=tcp#Warm-latency").toString()).servers.single()
            for (mode in listOf(ProbeConnectionMode.KEEP_ALIVE, ProbeConnectionMode.DISABLE)) {
                val http = MockWebServer()
                try {
                    http.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(800, java.util.concurrent.TimeUnit.MILLISECONDS))
                    if (mode == ProbeConnectionMode.KEEP_ALIVE) http.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(90, java.util.concurrent.TimeUnit.MILLISECONDS))
                    http.start()
                    val result = app.latencyTester.test(node, ProbeMethod.HTTP_GET, ClientSettings(coreEngine = CoreEngine.XRAY,
                        probeUrl = http.url("/generate_204").toString(), probeSamples = 1, probeConnectionMode = mode))
                    assertTrue(result.toString(), result.isSuccess)
                    assertEquals(listOf(result.latencyMs), result.samplesMs)
                    if (mode == ProbeConnectionMode.KEEP_ALIVE) {
                        assertTrue("Real response delay must remain: $result", result.latencyMs!! >= 80)
                        assertTrue("Cold request leaked into KeepAlive: $result", result.latencyMs!! < 650)
                        assertEquals(0, http.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)!!.sequenceNumber)
                        assertEquals(1, http.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)!!.sequenceNumber)
                    } else {
                        assertTrue("Disable must include its fresh request: $result", result.latencyMs!! >= 780)
                        assertEquals(1, http.requestCount)
                    }
                    android.util.Log.i("ProbeTimingTest", "$mode single sample: ${result.latencyMs} ms")
                } finally { http.shutdown() }
            }
        } finally { server.stopLoop() }
    }

    private fun verifyProxy(engine: CoreEngine, withRouting: Boolean = false) = runBlocking {
        app.coreSessions.initializeXray()
        val http = HttpFixture()
        val port = ServerSocket(0).use { it.localPort }
        val server = CoreController(callback())
        val config = """{"log":{"loglevel":"debug"},"stats":{},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"vless","settings":{"clients":[{"id":"$uuid"}],"decryption":"none"},"streamSettings":{"network":"tcp","security":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.0/8"]}]}}]}"""
        try {
            server.startLoop(config, 0)
            val raw = com.google.gson.JsonParser.parseString(com.gdhapp.client.data.ShareLinkParser.parse("vless://$uuid@127.0.0.1:$port?encryption=none&security=none&type=tcp#Native-test").toString()).asJsonObject
            if (withRouting) {
                raw.getAsJsonArray("outbounds")[0].asJsonObject.addProperty("tag", "proxy")
                raw.add("routing", com.google.gson.JsonParser.parseString("""{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"proxy"},{"type":"field","domain":["geosite:google"],"outboundTag":"proxy"}]}"""))
            }
            val node = SubscriptionParser().parse(raw.toString()).servers.single()
            val settings = ClientSettings(coreEngine = engine, probeUrl = "http://127.0.0.1:${http.port}/health", probeTimeoutSeconds = 5, logLevel = "debug")
            val direct = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
            direct.newCall(okhttp3.Request.Builder().url(settings.probeUrl).build()).execute().use { assertEquals("HTTP fixture baseline", 204, it.code) }
            android.util.Log.i("CoreDiagnostic", "Direct HTTP fixture works; engine=$engine")
            if (engine == CoreEngine.XRAY) {
                val proxy = app.coreSessions.open(node.rawConfigJson, settings)
                try {
                    java.net.Socket(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", proxy.port))).use { socket ->
                        socket.soTimeout = 5000
                        socket.connect(java.net.InetSocketAddress.createUnresolved("127.0.0.1", http.port), 5000)
                        android.util.Log.i("CoreDiagnostic", "Raw SOCKS connected")
                        socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().flush()
                        android.util.Log.i("CoreDiagnostic", "Raw SOCKS payload sent")
                        val firstLine = socket.getInputStream().bufferedReader().readLine()
                        android.util.Log.i("CoreDiagnostic", "Raw SOCKS response: $firstLine")
                        assertTrue(firstLine.orEmpty().contains("204"))
                    }
                } finally { proxy.close() }
            }
            for (method in listOf(ProbeMethod.HTTP_GET, ProbeMethod.HTTP_HEAD)) {
                val result = app.latencyTester.test(node, method, settings)
                assertTrue("$engine $method: ${result.error}", result.isSuccess)
                assertEquals(204, result.httpStatus)
            }
            for (method in listOf(ProbeMethod.TCP, ProbeMethod.DNS, ProbeMethod.ICMP)) {
                val result = app.latencyTester.test(node, method, settings)
                assertTrue("$method: ${result.error}", result.isSuccess)
            }
        } finally { server.stopLoop(); http.close() }
    }

    private fun callback() = object : CoreCallbackHandler {
        override fun startup() = 0L
        override fun shutdown() = 0L
        override fun onEmitStatus(code: Long, message: String?) = 0L
    }

    private class HttpFixture : AutoCloseable {
        private val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(204) }
            start()
        }
        val port get() = server.port
        override fun close() { server.shutdown() }
    }
}
