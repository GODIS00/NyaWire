package com.gdhapp.client

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.data.ShareLinkParser
import com.gdhapp.client.domain.model.CoreEngine
import com.gdhapp.client.domain.model.PerAppMode
import com.gdhapp.client.domain.model.VpnState
import com.gdhapp.client.vpn.GdhappVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Opt-in emulator benchmark; compares the same VLESS core with and without Android's TUN path. */
@RunWith(AndroidJUnit4::class)
class ThroughputBenchmarkTest {
    @Test fun compareSocksAndTunBulkTransfers() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use -e throughput true with the local host fixture", args.getString("throughput") == "true")
        assumeTrue(Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        assertNull(VpnService.prepare(app))
        val original = app.settings.settings.value
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.moveToState(Lifecycle.State.CREATED)
        app.coreSessions.initializeXray()
        val port = ServerSocket(0).use { it.localPort }
        val uuid = "00000000-0000-4000-8000-000000000004"
        val server = CoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(code: Long, message: String?) = 0L
        })
        val reality = args.getString("reality") == "true"
        val security = if (reality) "security=reality&sni=localhost&fp=chrome&pbk=lAhm6lcHaQQZleFXYt4W8-zdDo5FYBoqIBPxtpLJTAs&sid=aabb&flow=xtls-rprx-vision" else "security=none"
        val raw = ShareLinkParser.parse("vless://$uuid@127.0.0.1:$port?$security&encryption=none&type=tcp#Throughput-fixture").toString()
        val testApk = InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir
        val tls = if (args.getString("https") == "true") com.gdhapp.client.testing.FixtureTls(testApk) else null
        val scheme = if (tls == null) "http" else "https"
        val http = BulkHttpFixture(tls)
        val config = com.google.gson.JsonParser.parseString("""{"log":{"loglevel":"warning"},"stats":{},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"vless","settings":{"clients":[{"id":"$uuid"}],"decryption":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"redirect":"127.0.0.1:${http.port}","finalRules":[{"action":"allow","ip":["127.0.0.0/8"]}]}}]}""").asJsonObject
        val certificate = app.cacheDir.resolve("throughput-cert.pem")
        val key = app.cacheDir.resolve("throughput-key.pem")
        if (reality) {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            certificate.writeBytes(assets.open("fixture-cert.pem").use { it.readBytes() })
            key.writeBytes(assets.open("fixture-key.pem").use { it.readBytes() })
            val tlsPort = ServerSocket(0).use { it.localPort }
            val inbound = config.getAsJsonArray("inbounds")[0].asJsonObject
            inbound.getAsJsonObject("settings").getAsJsonArray("clients")[0].asJsonObject.addProperty("flow", "xtls-rprx-vision")
            // Synthetic key pair is fixture-only. REALITY's TLS target also stays on loopback.
            inbound.add("streamSettings", com.google.gson.JsonParser.parseString("""{"network":"tcp","security":"reality","realitySettings":{"target":"127.0.0.1:$tlsPort","serverNames":["localhost"],"privateKey":"2OUz9iwAh4Kcr-CgWRH4NpUfWmiAAgS9mrA-msSCm3Y","shortIds":["aabb"]}}"""))
            config.getAsJsonArray("inbounds").add(com.google.gson.JsonParser.parseString("""{"listen":"127.0.0.1","port":$tlsPort,"protocol":"trojan","settings":{"clients":[{"password":"fixture-only"}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"alpn":["h2","http/1.1"],"certificates":[{"certificateFile":"${certificate.absolutePath}","keyFile":"${key.absolutePath}"}]}}}"""))
        }
        val size = args.getString("sizeMib", "8").toInt().coerceIn(1, 64) * 1024 * 1024
        val expectedCrc = java.util.zip.CRC32().apply {
            val block = ByteArray(65536) { (it % 256).toByte() }
            repeat(size / block.size) { update(block) }
        }.value
        val report = StringBuilder("variant,mtu,path,direction,round,bytes,milliseconds,mbps\n")
        try {
            server.startLoop(config.toString(), 0)
            val mtus = args.getString("mtus", "1500,8500").split(',').map { it.toInt() }
            for (stack in args.getString("stacks", "native").split(',')) for (mtu in mtus) {
                val variant = args.getString("variant", "baseline") + "-" + stack
                app.settings.update { original.copy(coreEngine = CoreEngine.XRAY, mtu = mtu, bypassLan = false,
                    xrayCompatibilityMode = stack == "hev",
                    fragmentEnabled = args.getString("fragment") == "true",
                    verifyOnConnect = false, perAppMode = PerAppMode.ALL, appPackages = emptySet(), localSocksPort = 0, localSocksEnabled = true, logLevel = "warning") }
                GdhappVpnService.start(app, raw)
                val state = withTimeout(15000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } }
                assertEquals(state.toString(), VpnState.Connected, state)
                val client = OkHttpClient.Builder().apply {
                    if (tls != null) sslSocketFactory(tls.context.socketFactory, tls.trust).hostnameVerifier { host, _ -> host == "10.0.2.2" }
                }.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", app.vpnSession.metrics.value.localPort)))
                    .callTimeout(45, TimeUnit.SECONDS).build()
                try {
                    // Warm up the core before measuring. No external speed-test service is contacted.
                    client.newCall(Request.Builder().url("$scheme://10.0.2.2:18082/262144").build()).execute().use { it.body!!.bytes() }
                    repeat(args.getString("rounds", "3").toInt().coerceIn(1, 10)) { round ->
                        var started = SystemClock.elapsedRealtime()
                        var count = 0L
                        val crc = java.util.zip.CRC32()
                        client.newCall(Request.Builder().url("$scheme://10.0.2.2:18082/$size").build()).execute().use { response ->
                            assertEquals(200, response.code)
                            response.body!!.byteStream().use { input ->
                                val buffer = ByteArray(65536)
                                while (count < size) { val n = input.read(buffer); if (n < 0) break; count += n; crc.update(buffer, 0, n) }
                            }
                        }
                        assertEquals(size.toLong(), count)
                        assertEquals(expectedCrc, crc.value)
                        append(report, variant, mtu, "socks", round, count, SystemClock.elapsedRealtime() - started)
                        started = SystemClock.elapsedRealtime()
                        val command = "sh /data/local/tmp/nyawire-vpn-bulk-probe.sh $testApk $size $scheme"
                        val result = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
                        val fields = Regex("BYTES=(\\d+) MS=(\\d+) CRC=(\\d+)").find(result)?.groupValues ?: error("Invalid transfer result: $result")
                        val transferred = fields[1].toLong()
                        val elapsed = fields[2].toLong()
                        assertEquals(size.toLong(), transferred)
                        assertEquals(expectedCrc, fields[3].toLong())
                        append(report, variant, mtu, "tun", round, size.toLong(), elapsed)
                    }
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                GdhappVpnService.stop(app)
                withTimeout(10000) { app.vpnSession.state.first { it == VpnState.Disconnected } }
                delay(500)
            }
        } finally {
            app.getExternalFilesDir(null)!!.resolve("throughput-${args.getString("variant", "baseline")}.csv").writeText(report.toString())
            android.util.Log.i("ThroughputBenchmark", report.toString())
            GdhappVpnService.stop(app); delay(500)
            app.settings.update { original }; server.stopLoop(); http.close(); certificate.delete(); key.delete(); scenario.close()
        }
    }

    /** All bulk bytes stay on the emulator; host NAT cannot become the bottleneck. */
    private class BulkHttpFixture(tls: com.gdhapp.client.testing.FixtureTls?) : AutoCloseable {
        private val socket = if (tls == null) ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1")) else
            tls.context.serverSocketFactory.createServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        private val workers = java.util.concurrent.Executors.newCachedThreadPool()
        val port get() = socket.localPort
        init { workers.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { runCatching { client.use {
                    it.tcpNoDelay = true
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return@use
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) line = reader.readLine()
                    var remaining = request.substringAfter("GET /").substringBefore(' ').toInt()
                    val output = it.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: $remaining\r\nConnection: close\r\n\r\n".toByteArray())
                    val block = ByteArray(65536) { index -> (index % 256).toByte() }
                    while (remaining > 0) { val n = minOf(remaining, block.size); output.write(block, 0, n); remaining -= n }
                } } }
            }
        } }
        override fun close() { socket.close(); workers.shutdownNow() }
    }
    private fun append(report: StringBuilder, variant: String, mtu: Int, path: String, round: Int, bytes: Long, elapsed: Long) {
        val mbps = bytes * 8.0 / elapsed.coerceAtLeast(1) / 1000
        report.appendLine("$variant,$mtu,$path,download,$round,$bytes,$elapsed,${String.format(java.util.Locale.ROOT, "%.2f", mbps)}")
        android.util.Log.i("ThroughputBenchmark", report.lineSequence().last { it.isNotBlank() })
    }
}
