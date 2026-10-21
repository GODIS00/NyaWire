package com.gdhapp.client.vpn.core

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import android.content.Context
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.vpn.VpnCoreManager
import hev.htproxy.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class BundledVpnCoreManager(private val context: Context, private val factory: CoreSessionFactory) : VpnCoreManager {
    private var session: ProxySession? = null
    private var tunnelConfig: File? = null
    private var bridgeStarted = false
    private var proxyOnly = false
    override val name get() = session?.engineName.orEmpty()
    override val publicSocksAvailable get() = session?.let { it.port > 0 && it.bridgeCredentials == null } == true
    override val localPort get() = session?.port ?: 0
    override val isRunning get() = session?.isRunning == true && (proxyOnly || session?.nativeTun == true || TProxyService.TProxyIsRunning())

    override suspend fun start(config: String, tunFd: Int, settings: ClientSettings) = withContext(Dispatchers.IO) {
        check(session == null) { AppText.text(R.string.vpn_is_already_running) }
        try {
            val proxy = factory.open(config, settings, tunFd = if (settings.xrayCompatibilityMode) 0 else tunFd, forVpn = true)
            session = proxy
            proxyOnly = tunFd == 0
            // Xray owns a duplicate of Android's descriptor and dispatches TUN packets directly.
            // Optional public SOCKS is separate from the authenticated internal HEV transport.
            if (proxy.nativeTun || proxyOnly) return@withContext
            val file = context.cacheDir.resolve("gdhapp-tunnel.yml")
            tunnelConfig = file
            file.writeText("""
                tunnel:
                  name: gdhapp
                  mtu: ${settings.mtu}
                  ipv4: 10.77.0.2
                  ${if (settings.ipv6) "ipv6: 'fd77:77::2'" else ""}
                  icmp: 'off'
                socks5:
                  address: 127.0.0.1
                  port: ${proxy.port}
                  udp: 'udp'
                  ${proxy.bridgeCredentials?.let { "username: '${it.first}'\n                  password: '${it.second}'" }.orEmpty()}
                misc:
                  task-stack-size: 86016
                  connect-timeout: ${settings.probeTimeoutSeconds * 1000}
                  log-level: error
                  log-file: stderr
            """.trimIndent())
            require(TProxyService.TProxyStartService(file.absolutePath, tunFd)) { AppText.text(R.string.could_not_start_the_hev_tun_bridge) }
            bridgeStarted = true
            delay(120)
            require(TProxyService.TProxyIsRunning()) { AppText.text(R.string.the_tun_bridge_stopped_during_startup) }
        } catch (error: Throwable) { stop(); throw error }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (bridgeStarted) runCatching { TProxyService.TProxyStopService() }
        bridgeStarted = false
        proxyOnly = false
        session?.close(); session = null
        tunnelConfig?.delete(); tunnelConfig = null
    }

    override fun trafficTotals(): Pair<Long, Long> = runCatching {
        session?.takeIf { it.nativeTun || proxyOnly }?.let { return@runCatching it.trafficTotals() }
        val counters = TProxyService.TProxyGetStats()
        counters.getOrElse(1) { 0L } to counters.getOrElse(3) { 0L }
    }.getOrDefault(0L to 0L)
}
