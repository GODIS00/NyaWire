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
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler

/**
 * A real Hysteria2 round trip on the device: the bundled sing-box serves Hysteria2 on loopback, and a
 * panel-style Xray profile (fingerprint, salamander, self-signed certificate) connects through it.
 */
@RunWith(AndroidJUnit4::class)
class HysteriaConnectionTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()

    @Test fun singBoxCarriesPanelHysteriaProfile() = roundTrip(CoreEngine.SING_BOX, "sing-box")
    @Test fun xrayCarriesPanelHysteriaProfile() = roundTrip(CoreEngine.XRAY, "Xray")
    @Test fun singBoxCarriesHysteriaOnXrayServer() = roundTrip(CoreEngine.SING_BOX, "sing-box", xrayServer = true)
    @Test fun xrayCarriesHysteriaOnXrayServer() = roundTrip(CoreEngine.XRAY, "Xray", xrayServer = true)
    @Test fun singBoxResolvesPanelServerHostname() {
        DnsFixture().use { roundTrip(CoreEngine.SING_BOX, "sing-box", domain = true, dnsPort = it.port) }
    }
    @Test fun singBoxResolvesShareLinkServerHostname() {
        DnsFixture().use { roundTrip(CoreEngine.SING_BOX, "sing-box", domain = true, nativeLink = true, dnsPort = it.port) }
    }

    private fun roundTrip(engine: CoreEngine, name: String, domain: Boolean = false, nativeLink: Boolean = false, xrayServer: Boolean = false, dnsPort: Int = 53) = runBlocking {
        val directory = app.cacheDir.resolve("hy2-fixture-${System.nanoTime()}").apply { mkdirs() }
        val binary = app.coreSessions.singboxExecutable.absolutePath
        // A throwaway self-signed pair made by sing-box itself.
        val pair = ProcessBuilder(binary, "generate", "tls-keypair", "localhost").redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()
        val key = Regex("-----BEGIN PRIVATE KEY-----[\\s\\S]+?-----END PRIVATE KEY-----").find(pair)?.value ?: error(pair)
        val certificate = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").find(pair)?.value ?: error(pair)
        val keyFile = directory.resolve("key.pem").apply { writeText(key) }
        val certificateFile = directory.resolve("cert.pem").apply { writeText(certificate) }
        val port = java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val serverConfig = directory.resolve("server.json").apply { writeText("""{"log":{"level":"warn"},
            "inbounds":[{"type":"hysteria2","listen":"127.0.0.1","listen_port":$port,"users":[{"password":"secret"}],
              "obfs":{"type":"salamander","password":"obfs-secret"},
              "tls":{"enabled":true,"alpn":["h3"],"certificate_path":"${certificateFile.absolutePath}","key_path":"${keyFile.absolutePath}"}}],
            "outbounds":[{"type":"direct"}]}""") }
        val server = if (!xrayServer) ProcessBuilder(binary, "run", "-c", serverConfig.absolutePath).redirectErrorStream(true).start() else null
        val xray = if (xrayServer) CoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(code: Long, message: String?) = 0L
        }).also {
            app.coreSessions.initializeXray()
            it.startLoop("""{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"hysteria",
              "settings":{"version":2,"clients":[{"auth":"secret"}]},"streamSettings":{"network":"hysteria","security":"tls",
              "hysteriaSettings":{"version":2},"tlsSettings":{"alpn":["h3"],"certificates":[{"certificateFile":"${certificateFile.absolutePath}","keyFile":"${keyFile.absolutePath}"}]},
              "finalmask":{"udp":[{"type":"salamander","settings":{"password":"obfs-secret"}}]}}}],
              "outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.0/8"]}]}}]}""", 0)
        } else null
        val serverLog = StringBuilder()
        if (server != null) thread(isDaemon = true) { runCatching { server.inputStream.bufferedReader().forEachLine { synchronized(serverLog) { serverLog.appendLine(it) } } } }
        val http = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!http.isClosed) runCatching { http.accept().use { client ->
                client.getInputStream().read(ByteArray(2048))
                client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
            } }
        }
        try {
            Thread.sleep(1500)
            assertTrue("Hysteria2 fixture server stopped:\n$serverLog", server?.isAlive ?: (xray?.isRunning == true))
            // sing-box takes allowInsecure; Xray pins the certificate (its insecure mode would look the pin up without obfs).
            val der = java.util.Base64.getMimeDecoder().decode(certificate.lines().filter { !it.startsWith("-----") }.joinToString(""))
            val pin = java.security.MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
            val trust = if (engine == CoreEngine.XRAY) "\"pinnedPeerCertSha256\":\"$pin\"" else "\"allowInsecure\":true"
            val host = if (domain) "hy2.fixture.test" else "127.0.0.1"
            var profile = """{"outbounds":[{"tag":"proxy","protocol":"hysteria","settings":{"version":2,"address":"$host","port":$port},
                "streamSettings":{"network":"hysteria","security":"tls",
                  "tlsSettings":{"serverName":"localhost","fingerprint":"chrome","alpn":["h3"],$trust},
                  "hysteriaSettings":{"version":2,"auth":"secret"},
                  "finalmask":{"udp":[{"type":"salamander","settings":{"password":"obfs-secret"}}]}}},
                {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
                "routing":{"rules":[{"type":"field","protocol":["bittorrent"],"outboundTag":"block"}]}}"""
            if (nativeLink) profile = com.gdhapp.client.data.ShareLinkParser.parse("hysteria2://secret@$host:$port?alpn=h3&fp=firefox&obfs=salamander&obfs-password=obfs-secret&sni=localhost&insecure=1").toString()
            val settings = ClientSettings(coreEngine = engine, dnsPrimary = if (domain) "127.0.0.1" else "1.1.1.1", logLevel = "debug")
            if (domain) {
                // Generate production DNS first; change only the fixture's port because apps cannot bind port 53.
                val prepared = com.google.gson.JsonParser.parseString(com.gdhapp.client.vpn.core.CoreConfigBuilder.build(profile, settings, 10808).json).asJsonObject
                val dns = prepared.getAsJsonObject("dns").getAsJsonArray("servers").single().asJsonObject
                assertEquals("127.0.0.1", dns.get("server").asString)
                assertEquals("udp", dns.get("type").asString)
                dns.addProperty("server_port", dnsPort)
                profile = prepared.toString()
            }
            app.coreSessions.open(profile, settings, forProbe = true).use { session ->
                assertEquals(name, session.engineName)
                // The loopback HTTP server is only reachable through the Hysteria2 server's direct outbound here.
                val body = fetch(session.port, http.localPort)
                assertEquals("$name through Hysteria2\nserver log:\n$serverLog\ncore: ${session.errorDetail}", "ok", body)
            }
        } finally {
            http.close(); server?.destroy(); server?.waitFor(2, TimeUnit.SECONDS); xray?.stopLoop()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }

    /** A real DNS endpoint, so tests exercise endpoint resolution rather than only literal IPs. */
    private class DnsFixture : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        init { thread(isDaemon = true) {
            while (!socket.isClosed) runCatching {
                val request = DatagramPacket(ByteArray(2048), 2048); socket.receive(request)
                val bytes = request.data
                var end = 12
                val labels = mutableListOf<String>()
                while (bytes[end].toInt() != 0) {
                    val length = bytes[end].toInt() and 255
                    require(length in 1..63 && end + length + 5 < request.length)
                    labels.add(String(bytes, end + 1, length, Charsets.US_ASCII)); end += length + 1
                }
                val kind = ((bytes[end + 1].toInt() and 255) shl 8) or (bytes[end + 2].toInt() and 255)
                require(labels.joinToString(".").lowercase() == "hy2.fixture.test")
                val answer = if (kind == 1) byteArrayOf(0xc0.toByte(),12,0,1,0,1,0,0,0,30,0,4,127,0,0,1) else byteArrayOf()
                val header = byteArrayOf(bytes[0],bytes[1],0x81.toByte(),0x80.toByte(),0,1,0,if (answer.isEmpty()) 0 else 1,0,0,0,0)
                val response = header + bytes.copyOfRange(12, end + 5) + answer
                socket.send(DatagramPacket(response, response.size, request.address, request.port))
            }
        } }
        override fun close() { socket.close() }
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
