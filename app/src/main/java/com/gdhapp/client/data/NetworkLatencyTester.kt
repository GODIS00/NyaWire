package com.gdhapp.client.data

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.ProbeMethod
import com.gdhapp.client.domain.model.ProbeResult
import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.domain.repository.LatencyTester
import com.gdhapp.client.vpn.core.CoreSessionFactory
import com.gdhapp.client.vpn.core.safeCoreError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.roundToInt
import kotlin.time.TimeSource

class NetworkLatencyTester(private val cores: CoreSessionFactory) : LatencyTester {
    override suspend fun test(node: ServerNode, method: ProbeMethod, settings: ClientSettings): ProbeResult = withContext(Dispatchers.IO) {
        var session: com.gdhapp.client.vpn.core.ProxySession? = null
        try {
            if (method.usesProxy) {
                session = cores.open(node.rawConfigJson, settings, forProbe = true)
                return@withContext testViaProxy(session.port, method, settings)
            }
            val values = mutableListOf<Int>()
            var lastError: Throwable? = null
            var httpStatus: Int? = null
            repeat(settings.probeSamples) {
                currentCoroutineContext().ensureActive()
                try {
                    val latency = when (method) {
                        ProbeMethod.TCP -> tcp(node, settings)
                        ProbeMethod.ICMP -> icmp(node, settings)
                        ProbeMethod.DNS -> dns(node)
                        ProbeMethod.TLS -> tls(node, settings)
                        ProbeMethod.HTTP_GET, ProbeMethod.HTTP_HEAD -> error("HTTP probe requires a proxy session")
                    }
                    values.add(latency)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { lastError = error }
            }
            if (values.isEmpty()) ProbeResult(method, error = explain(lastError ?: IllegalStateException(AppText.text(R.string.no_response)), settings))
            else ProbeResult(method, latencyMs = values.sorted()[values.size / 2], httpStatus = httpStatus, samplesMs = values)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { ProbeResult(method, error = explain(error, settings)) }
        finally { withContext(NonCancellable + Dispatchers.IO) { session?.close() } }
    }

    /** Check the already connected core without opening another profile or restarting the VPN. */
    suspend fun testViaProxy(port: Int, method: ProbeMethod, settings: ClientSettings): ProbeResult = withContext(Dispatchers.IO) {
        require(method.usesProxy)
        try {
            HttpProbeSession(port, settings).use { http ->
                val values = mutableListOf<Int>()
                var lastError: Exception? = null
                var status: Int? = null
                repeat(settings.probeSamples) {
                    currentCoroutineContext().ensureActive()
                    try { val sample = http.execute(method); values.add(sample.first); status = sample.second }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { lastError = error }
                }
                if (values.isEmpty()) ProbeResult(method, error = explain(lastError ?: IllegalStateException(AppText.text(R.string.no_response)), settings))
                else ProbeResult(method, latencyMs = values.sorted()[values.size / 2], httpStatus = status, samplesMs = values)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ProbeResult(method, error = explain(error, settings)) }
    }

    private fun endpoint(node: ServerNode): InetSocketAddress {
        val host = node.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException(AppText.text(R.string.server_address_is_missing_open_the_details))
        val port = node.port ?: throw IllegalArgumentException(AppText.text(R.string.server_port_is_missing_from_the_configuration))
        return InetSocketAddress(host, port)
    }

    private fun tcp(node: ServerNode, settings: ClientSettings): Int {
        val address = endpoint(node)
        val start = TimeSource.Monotonic.markNow()
        Socket().use { it.connect(address, settings.probeTimeoutSeconds * 1000) }
        return start.elapsedNow().inWholeMilliseconds.toInt().coerceAtLeast(1)
    }

    private fun dns(node: ServerNode): Int {
        val host = node.host ?: throw IllegalArgumentException(AppText.text(R.string.server_address_not_found))
        val start = TimeSource.Monotonic.markNow()
        require(InetAddress.getAllByName(host).isNotEmpty()) { AppText.text(R.string.dns_returned_no_addresses) }
        return start.elapsedNow().inWholeMilliseconds.toInt().coerceAtLeast(1)
    }

    private fun tls(node: ServerNode, settings: ClientSettings): Int {
        val address = endpoint(node)
        Socket().use { tcp ->
            tcp.connect(address, settings.probeTimeoutSeconds * 1000)
            val serverName = node.serverName?.takeIf { it.isNotBlank() } ?: node.host!!
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(tcp, serverName, address.port, true).use { raw ->
                val socket = raw as SSLSocket
                socket.soTimeout = settings.probeTimeoutSeconds * 1000
                socket.sslParameters = socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    runCatching { serverNames = listOf(SNIHostName(serverName)) }
                }
                val start = TimeSource.Monotonic.markNow()
                socket.startHandshake()
                return start.elapsedNow().inWholeMilliseconds.toInt().coerceAtLeast(1)
            }
        }
    }

    private fun icmp(node: ServerNode, settings: ClientSettings): Int {
        val address = InetAddress.getByName(node.host ?: throw IllegalArgumentException(AppText.text(R.string.server_address_not_found)))
        val args = mutableListOf("/system/bin/ping")
        if (address.address.size == 16) args.add("-6")
        args.addAll(listOf("-n", "-c", "1", "-W", settings.probeTimeoutSeconds.toString(), address.hostAddress!!))
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(settings.probeTimeoutSeconds + 2L, TimeUnit.SECONDS)) throw SocketTimeoutException()
            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() != 0) {
                if (output.contains("permitted", true) || output.contains("denied", true)) throw IllegalArgumentException(AppText.text(R.string.android_does_not_allow_icmp_for_this))
                throw IllegalArgumentException(AppText.text(R.string.no_icmp_reply_the_server_may_block))
            }
            return Regex("time[=<]\\s*([0-9.]+)").find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt()?.coerceAtLeast(1)
                ?: throw IllegalArgumentException(AppText.text(R.string.could_not_read_the_icmp_response))
        } finally { process.destroy() }
    }

    companion object {
        /** End-to-end HTTP request through the selected profile's real local SOCKS listener. */
        fun httpViaProxy(port: Int, method: ProbeMethod, settings: ClientSettings): Pair<Int, Int> {
            return HttpProbeSession(port, settings).use { it.execute(method) }
        }

        fun explain(error: Throwable, settings: ClientSettings): String = when (error) {
            is java.io.InterruptedIOException -> AppText.text(R.string.no_response_within_s, settings.probeTimeoutSeconds)
            is UnknownHostException -> AppText.text(R.string.dns_server_address_not_found)
            is SSLHandshakeException -> "TLS: ${safeCoreError(error)}"
            is ConnectException -> AppText.text(R.string.connection_refused_or_port_unavailable)
            else -> safeCoreError(error)
        }
    }
}
