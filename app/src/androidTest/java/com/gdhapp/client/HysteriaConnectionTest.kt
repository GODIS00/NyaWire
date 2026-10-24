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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A real Hysteria2 round trip on the device: the bundled sing-box serves Hysteria2 on loopback, and a
 * panel-style Xray profile (fingerprint, salamander, self-signed certificate) connects through it.
 */
@RunWith(AndroidJUnit4::class)
class HysteriaConnectionTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()

    @Test fun singBoxCarriesPanelHysteriaProfile() = roundTrip(CoreEngine.SING_BOX, "sing-box")
    @Test fun xrayCarriesPanelHysteriaProfile() = roundTrip(CoreEngine.XRAY, "Xray")

    private fun roundTrip(engine: CoreEngine, name: String) = runBlocking {
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
        val server = ProcessBuilder(binary, "run", "-c", serverConfig.absolutePath).redirectErrorStream(true).start()
        val serverLog = StringBuilder()
        thread(isDaemon = true) { runCatching { server.inputStream.bufferedReader().forEachLine { synchronized(serverLog) { serverLog.appendLine(it) } } } }
        val http = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!http.isClosed) runCatching { http.accept().use { client ->
                client.getInputStream().read(ByteArray(2048))
                client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
            } }
        }
        try {
            Thread.sleep(1500)
            assertTrue("Hysteria2 fixture server stopped:\n$serverLog", server.isAlive)
            // sing-box takes allowInsecure; Xray pins the certificate (its insecure mode would look the pin up without obfs).
            val der = java.util.Base64.getMimeDecoder().decode(certificate.lines().filter { !it.startsWith("-----") }.joinToString(""))
            val pin = java.security.MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
            val trust = if (engine == CoreEngine.XRAY) "\"pinnedPeerCertSha256\":\"$pin\"" else "\"allowInsecure\":true"
            val profile = """{"outbounds":[{"tag":"proxy","protocol":"hysteria","settings":{"version":2,"address":"127.0.0.1","port":$port},
                "streamSettings":{"network":"hysteria","security":"tls",
                  "tlsSettings":{"serverName":"localhost","fingerprint":"chrome","alpn":["h3"],$trust},
                  "hysteriaSettings":{"version":2,"auth":"secret"},
                  "finalmask":{"udp":[{"type":"salamander","settings":{"password":"obfs-secret"}}]}}},
                {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
                "routing":{"rules":[{"type":"field","protocol":["bittorrent"],"outboundTag":"block"}]}}"""
            app.coreSessions.open(profile, ClientSettings(coreEngine = engine), forProbe = true).use { session ->
                assertEquals(name, session.engineName)
                // The loopback HTTP server is only reachable through the Hysteria2 server's direct outbound here.
                val body = fetch(session.port, http.localPort)
                assertEquals("$name through Hysteria2\nserver log:\n$serverLog\ncore: ${session.errorDetail}", "ok", body)
            }
        } finally {
            http.close(); server.destroy(); server.waitFor(2, TimeUnit.SECONDS)
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
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
