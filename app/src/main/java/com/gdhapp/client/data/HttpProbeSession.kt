package com.gdhapp.client.data

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.ProbeConnectionMode
import com.gdhapp.client.domain.model.ProbeMethod
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource

/** A pool belongs to one tested profile. Never share sockets across proxy sessions. */
internal class HttpProbeSession(port: Int, private val settings: ClientSettings) : AutoCloseable {
    private var prepared = false
    private val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        .connectTimeout(settings.probeTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(settings.probeTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .callTimeout(settings.probeTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .apply {
            when (settings.probeConnectionMode) {
                ProbeConnectionMode.DEFAULT -> Unit // OkHttp's standard HTTP negotiation and pooling.
                ProbeConnectionMode.DISABLE -> {
                    protocols(listOf(Protocol.HTTP_1_1))
                    connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                }
                ProbeConnectionMode.KEEP_ALIVE -> connectionPool(ConnectionPool(2, 2, TimeUnit.MINUTES))
            }
        }.build()

    fun execute(method: ProbeMethod): Pair<Int, Int> {
        // KeepAlive measures a request on an established connection, even for one sample.
        // The warm-up still goes through the same profile and URL and must succeed.
        if (!prepared && settings.probeConnectionMode == ProbeConnectionMode.KEEP_ALIVE) {
            exchange(method)
            prepared = true
        }
        return exchange(method)
    }

    private fun exchange(method: ProbeMethod): Pair<Int, Int> {
        require(method.usesProxy)
        val request = Request.Builder().url(settings.probeUrl)
            .method(if (method == ProbeMethod.HTTP_HEAD) "HEAD" else "GET", null)
            .apply { when (settings.probeConnectionMode) {
                ProbeConnectionMode.DEFAULT -> Unit
                ProbeConnectionMode.DISABLE -> header("Connection", "close")
                ProbeConnectionMode.KEEP_ALIVE -> header("Connection", "keep-alive")
            } }.build()
        val started = TimeSource.Monotonic.markNow()
        return client.newCall(request).execute().use { response ->
            require(response.code in 200..399) { "HTTP ${response.code}. ${if (response.code == 405 && method == ProbeMethod.HTTP_HEAD) AppText.text(R.string.this_site_does_not_support_head_try) else AppText.text(R.string.try_a_different_test_url)}" }
            val elapsed = started.elapsedNow().inWholeMilliseconds.toInt().coerceAtLeast(1)
            // Drain small bodies so the next sample can reuse HTTP/1.1. Never download large pages.
            response.body?.takeIf { settings.probeConnectionMode != ProbeConnectionMode.DISABLE && it.contentLength() in 0..65_536 }?.let { body ->
                runCatching {
                    body.source().timeout().deadline(200, TimeUnit.MILLISECONDS)
                    try { body.source().skip(body.contentLength()) } finally { body.source().timeout().clearDeadline() }
                }
            }
            elapsed to response.code
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
