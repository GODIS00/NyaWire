package com.gdhapp.client.vpn.core

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import android.content.Context
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.CoreEngine
import go.Seq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

interface ProxySession : AutoCloseable {
    val port: Int
    val engineName: String
    val isRunning: Boolean
    val errorDetail: String
    val nativeTun: Boolean get() = false
    val bridgeCredentials: Pair<String, String>? get() = null
    fun trafficTotals(): Pair<Long, Long> = 0L to 0L
    override fun close()
}

class CoreSessionFactory(private val context: Context) {
    @Volatile private var xrayInitialized = false
    private val coreDirectory = context.filesDir.resolve("core").apply { mkdirs() }
    private val routingAssets = BundledRoutingAssets(context, coreDirectory)
    val singboxExecutable get() = File(context.applicationInfo.nativeLibraryDir, "libsingbox_exec.so")

    private val geo = GeoData(coreDirectory)
    @Volatile private var geoInstalled = false

    /** Bundled geodata, installed once per process; Xray reads the same files from this directory. */
    @Synchronized
    fun geoData(): GeoData {
        if (!geoInstalled || !routingAssets.filesPresent()) { routingAssets.install(); geoInstalled = true }
        return geo
    }

    @Synchronized
    fun initializeXray() {
        if (!xrayInitialized || !routingAssets.filesPresent()) { routingAssets.install(); geoInstalled = true }
        if (xrayInitialized) return
        Seq.setContext(context.applicationContext)
        Libv2ray.initCoreEnv(coreDirectory.absolutePath, "")
        xrayInitialized = true
    }

    suspend fun versions(): Map<String, String> = withContext(Dispatchers.IO) {
        val xray = runCatching { initializeXray(); Libv2ray.checkVersionX() }.getOrElse { AppText.text(R.string.xray_error, safeCoreError(it)) }
        val box = runCatching {
            val process = ProcessBuilder(singboxExecutable.absolutePath, "version").redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); error(AppText.text(R.string.timed_out_334)) }
            process.inputStream.bufferedReader().readText().lineSequence().firstOrNull().orEmpty()
        }.getOrElse { AppText.text(R.string.sing_box_error, safeCoreError(it)) }
        mapOf("Xray" to xray, "sing-box" to box)
    }

    suspend fun open(raw: String, settings: ClientSettings, forProbe: Boolean = false, tunFd: Int = 0, forVpn: Boolean = false): ProxySession = withContext(Dispatchers.IO) {
        val publicProxy = settings.localSocksEnabled || settings.connectionMode == com.gdhapp.client.domain.model.ConnectionMode.PROXY
        val internalOnly = forVpn && !forProbe && !publicProxy
        val credentials = if (internalOnly) UUID.randomUUID().toString().replace("-", "") to UUID.randomUUID().toString().replace("-", "") else null
        val candidatePort = if (!forProbe && !internalOnly && settings.localSocksPort != 0) settings.localSocksPort
            else if (!forProbe && publicProxy) 10808 else ServerSocket(0).use { it.localPort }
        val config = CoreConfigBuilder.build(raw, settings, candidatePort, nativeTun = tunFd > 0, includeSocks = !internalOnly, socksCredentials = credentials, shareProxy = !forProbe, geo = geoData(), forProbe = forProbe)
        val noListener = internalOnly && config.engine == CoreEngine.XRAY && tunFd > 0
        val port = if (noListener) 0 else candidatePort
        var opened: ProxySession? = null
        try {
            val session = when (config.engine) {
                CoreEngine.XRAY -> {
                    initializeXray()
                    val compatible = LegacyTlsCompatibility.normalize(config.json) { address, serverPort, serverName, quic ->
                        val request = JsonObject().apply { addProperty("address", address); addProperty("port", serverPort)
                            addProperty("serverName", serverName); addProperty("timeoutMs", settings.probeTimeoutSeconds * 1000) }.toString()
                        val result = JsonParser.parseString(if (quic) Libv2ray.fetchQuicCertSha256(request) else Libv2ray.fetchTlsCertSha256(request)).asJsonObject
                        result.text("sha256")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("TLS: ${result.text("error") ?: AppText.text(R.string.certificate_unavailable)}")
                    }
                    openXray(compatible, port, tunFd)
                }
                CoreEngine.SING_BOX -> openSingBox(config.json, port)
                CoreEngine.AUTO -> error(AppText.text(R.string.no_core_selected))
            }
            opened = session
            var ready = false
            for (attempt in 0 until 120) {
                if (!session.isRunning) throw IllegalArgumentException(session.errorDetail.ifBlank { AppText.text(R.string.the_core_stopped, session.engineName) })
                if (noListener || socksReady(port, credentials)) { ready = true; break }
                delay(50)
            }
            require(ready) { session.errorDetail.ifBlank { AppText.text(R.string.the_socks_listener_did_not_start, session.engineName) } }
            object : ProxySession by session { override val bridgeCredentials = if (noListener) null else credentials }
        } catch (error: Throwable) {
            opened?.close()
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw IllegalArgumentException(safeCoreError(error), error)
        }
    }

    private fun openXray(config: String, port: Int, tunFd: Int): ProxySession {
        initializeXray()
        var detail = ""
        val controller = CoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, status: String?): Long { if (code < 0) detail = status.orEmpty(); return 0 }
        })
        // libv2ray hands the TUN descriptor over in a process-wide variable (xray.tun.fd) that the TUN inbound
        // reads while starting. A probe starting meanwhile would overwrite it with 0, so starts take turns.
        try { synchronized(xrayStart) { controller.startLoop(config, tunFd) } } catch (error: Throwable) { runCatching { controller.stopLoop() }; throw error }
        return object : ProxySession {
            private var upload = 0L
            private var download = 0L
            override val port = port
            override val engineName = "Xray"
            override val isRunning get() = controller.isRunning
            override val errorDetail get() = detail
            override val nativeTun = tunFd > 0
            @Synchronized override fun trafficTotals(): Pair<Long, Long> {
                controller.queryAllOutboundTrafficStats().orEmpty().split(';').forEach { item ->
                    val direction = item.substringBeforeLast(',').substringAfterLast(',')
                    item.substringAfterLast(',').toLongOrNull()?.coerceAtLeast(0)?.let { bytes ->
                        when (direction) { "uplink" -> upload += bytes; "downlink" -> download += bytes }
                    }
                }
                return upload to download
            }
            @Synchronized override fun close() { runCatching { controller.stopLoop() } }
        }
    }

    private fun openSingBox(config: String, port: Int): ProxySession {
        require(singboxExecutable.isFile) { AppText.text(R.string.sing_box_no_binary_found_for_this) }
        val directory = coreDirectory.resolve(UUID.randomUUID().toString()).apply { mkdirs() }
        val file = directory.resolve("config.json").apply { writeText(config); setReadable(false, false); setReadable(true, true) }
        val lines = ArrayDeque<String>()
        val process = try { ProcessBuilder(singboxExecutable.absolutePath, "run", "-c", file.absolutePath)
            .directory(coreDirectory).redirectErrorStream(true).start() }
        catch (error: Throwable) { file.delete(); directory.delete(); throw error }
        val output = Thread({ runCatching { process.inputStream.bufferedReader().useLines { stream -> stream.forEach { line ->
            synchronized(lines) { if (lines.size >= 20) lines.removeFirst(); lines.addLast(line.take(1000)) }
        } } } }, "sing-box-output").apply { isDaemon = true; start() }
        return object : ProxySession {
            override val port = port
            override val engineName = "sing-box"
            override val isRunning get() = process.isAlive
            override val errorDetail get() = synchronized(lines) { lines.takeLast(3).joinToString("\n") }.let { safeCoreError(IllegalStateException(it)) }
            override fun close() {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
                runCatching { output.join(150) }; file.delete(); directory.delete()
            }
        }
    }

    private companion object {
        /** Guards libv2ray's process-wide TUN descriptor variable; see openXray. */
        val xrayStart = Any()
    }

    private fun socksReady(port: Int, credentials: Pair<String, String>? = null): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 150); socket.soTimeout = 150
            val output = socket.getOutputStream(); val input = socket.getInputStream()
            output.write(byteArrayOf(5, 1, if (credentials == null) 0 else 2))
            if (input.read() != 5 || input.read() != if (credentials == null) 0 else 2) return@use false
            if (credentials == null) true else {
                val user = credentials.first.toByteArray(); val password = credentials.second.toByteArray()
                output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
                input.read() == 1 && input.read() == 0
            }
        }
    }.getOrDefault(false)
}

fun safeCoreError(error: Throwable): String = error.message.orEmpty()
    .replace(Regex("\\u001B\\[[;\\d]*m"), "")
    .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "[UUID]")
    .ifBlank { error.javaClass.simpleName }.take(600)
