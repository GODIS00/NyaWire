package com.gdhapp.client.vpn

import com.gdhapp.client.i18n.AppText

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gdhapp.client.GdhappApplication
import com.gdhapp.client.MainActivity
import com.gdhapp.client.R
import com.gdhapp.client.data.NetworkLatencyTester
import com.gdhapp.client.data.SubscriptionParser
import com.gdhapp.client.domain.model.ProbeResult
import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.domain.model.PerAppMode
import com.gdhapp.client.domain.model.ProbeMethod
import com.gdhapp.client.domain.model.VpnMetrics
import com.gdhapp.client.domain.model.VpnState
import com.gdhapp.client.domain.vpn.VpnCoreManager
import com.gdhapp.client.vpn.core.CoreConfigBuilder
import com.gdhapp.client.vpn.core.safeCoreError
import com.gdhapp.client.ui.notifications.VpnNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class GdhappVpnService : VpnService() {
    private val app get() = application as GdhappApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var monitor: Job? = null
    private var tunnel: ParcelFileDescriptor? = null
    private lateinit var core: VpnCoreManager
    private var lease = 0L
    private var activeNode: ServerNode? = null
    private var notificationProbe: ProbeResult? = null
    private var probeJob: Job? = null
    private var stopping = false
    private var proxyOnly = false
    // The routing the running core was started with; settings may change while it runs.
    private var liveRouting = com.gdhapp.client.domain.model.RoutingMode.PROFILE
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        core = app.vpnCoreFactory()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, AppText.text(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW),
        )
        var notificationLanguage = app.settings.settings.value.language
        scope.launch {
            app.settings.settings.collect { settings ->
                if (app.vpnSession.state.value == VpnState.Connected && !stopping) updateWakeLock(settings.keepAwake)
                if (!settings.killSwitch && app.vpnSession.state.value is VpnState.Error && tunnel != null && !stopping) stopVpn(preserveError = true)
                if (notificationLanguage != settings.language) {
                    notificationLanguage = settings.language
                    getSystemService(NotificationManager::class.java).createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, AppText.text(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW))
                    if (app.vpnSession.state.value == VpnState.Connected) refreshNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        if (intent?.action == ACTION_PING) {
            if (app.vpnSession.state.value == VpnState.Connected && !stopping) probeActiveConnection()
            else if (app.vpnSession.state.value != VpnState.Connecting) stopSelf()
            return START_NOT_STICKY
        }
        val config = app.vpnSession.pendingConfig
        app.vpnSession.pendingConfig = null
        try {
            proxyOnly = app.settings.settings.value.connectionMode == com.gdhapp.client.domain.model.ConnectionMode.PROXY
            activeNode = config?.let { raw ->
                val nodes = app.subscriptions.groups.value.flatMap { it.servers }
                app.vpnSession.activeNode.value?.takeIf { it.rawConfigJson == raw }
                    ?: nodes.firstOrNull { it.rawConfigJson == raw && it.isSelected }
                    ?: nodes.firstOrNull { it.rawConfigJson == raw }
                    ?: runCatching { SubscriptionParser().parse(raw).servers.single() }.getOrNull()
            }
            app.vpnSession.setActiveNode(activeNode)
            promoteToForeground(AppText.text(R.string.connecting))
            if (config == null) { app.vpnSession.update(VpnState.Error(AppText.text(R.string.select_a_server_in_the_app))); finishService() }
            else startVpn(config, intent?.getBooleanExtra("wait_for_network", false) == true)
        } catch (error: Exception) { app.vpnSession.update(VpnState.Error(safeCoreError(error))); finishService() }
        return START_NOT_STICKY
    }

    fun startVpn(config: String, waitForNetwork: Boolean = false) {
        stopping = true
        operation?.cancel()
        val attempt = app.vpnSession.claim().also { lease = it }
        app.vpnSession.update(VpnState.Connecting)
        val settings = app.settings.settings.value
        operation = scope.launch {
            app.vpnSession.coreMutex.withLock {
                releaseResources(keepTunnel = settings.killSwitch && !proxyOnly)
                try {
                    if (waitForNetwork) {
                        val network = getSystemService(android.net.ConnectivityManager::class.java)
                        val ready = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                            while (network.allNetworks.none { item -> network.getNetworkCapabilities(item)?.let {
                                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                            } == true }) delay(500)
                            true
                        } == true
                        require(ready) { AppText.text(R.string.startup_network_unavailable) }
                    }
                    if (!proxyOnly) {
                    require(prepare(this@GdhappVpnService) == null) { AppText.text(R.string.allow_the_vpn_in_the_android_permission) }
                    require(NetworkRoutes.isLiteralAddress(settings.dnsPrimary)) { AppText.text(R.string.primary_dns_must_be_an_ip_address) }
                    require(settings.dnsSecondary.isBlank() || NetworkRoutes.isLiteralAddress(settings.dnsSecondary)) { AppText.text(R.string.secondary_dns_must_be_an_ip_address) }
                    require(settings.ipv6 || ':' !in (settings.dnsPrimary + settings.dnsSecondary)) { AppText.text(R.string.enable_ipv6_in_vpn_settings_to_use) }
                    CoreConfigBuilder.build(config, settings, 10808, geo = app.coreSessions.geoData())
                    val builder = Builder().setSession("NyaWire").setMtu(settings.mtu)
                        .addAddress("10.77.0.2", 30).addDnsServer(settings.dnsPrimary).setBlocking(false)
                    settings.dnsSecondary.takeIf { it.isNotBlank() }?.let(builder::addDnsServer)
                    if (settings.bypassLan) NetworkRoutes.publicIpv4Routes().forEach { builder.addRoute(it.first, it.second) }
                    else builder.addRoute("0.0.0.0", 0)
                    if (settings.ipv6) builder.addAddress("fd77:77::2", 126).addRoute(if (settings.bypassLan) "2000::" else "::", if (settings.bypassLan) 3 else 0)
                    builder.setMetered(false)

                    // Native core sockets inherit this UID. Exclude it to prevent tunnel loops.
                    // User apps still enter TUN; HTTP probes explicitly use the local SOCKS proxy.
                    if (settings.perAppMode == PerAppMode.INCLUDE) {
                        val included = settings.appPackages.filter { it != packageName }
                        require(included.isNotEmpty()) { AppText.text(R.string.select_apps_for_selected_only_mode) }
                        val installed = included.count { name ->
                            try { builder.addAllowedApplication(name); true }
                            catch (_: PackageManager.NameNotFoundException) { false }
                        }
                        require(installed > 0) { AppText.text(R.string.the_selected_apps_are_not_installed_update) }
                    } else {
                        builder.addDisallowedApplication(packageName)
                        if (settings.perAppMode == PerAppMode.EXCLUDE) settings.appPackages.filter { it != packageName }.forEach {
                            try { builder.addDisallowedApplication(it) } catch (_: PackageManager.NameNotFoundException) { }
                        }
                    }
                    val previousTunnel = tunnel
                    tunnel = builder.establish() ?: error(AppText.text(R.string.android_could_not_create_the_tun_interface))
                    runCatching { previousTunnel?.close() }
                    }
                    withTimeout(25_000) { core.start(config, tunnel?.fd ?: 0, settings) }
                    liveRouting = settings.routingMode
                    if (settings.verifyOnConnect) withContext(Dispatchers.IO) {
                        if (liveCoreReachesServer()) NetworkLatencyTester.httpViaProxy(core.localPort, ProbeMethod.HTTP_GET, settings)
                        else app.coreSessions.open(config, settings, forProbe = true).use { probe ->
                            NetworkLatencyTester.httpViaProxy(probe.port, ProbeMethod.HTTP_GET, settings)
                        }
                    }
                    if (!app.vpnSession.isCurrent(attempt)) throw CancellationException()
                    val connectedAt = System.currentTimeMillis()
                    stopping = false
                    updateWakeLock(app.settings.settings.value.keepAwake)
                    app.vpnSession.updateMetrics(VpnMetrics(connectedAt = connectedAt, engine = core.name, localPort = core.localPort,
                        proxyOnly = proxyOnly, trafficAvailable = !proxyOnly || core.name == "Xray"))
                    app.vpnSession.update(VpnState.Connected)
                    promoteToForeground(AppText.text(R.string.vpn_connected_097, core.name))
                    startMonitoring(attempt, connectedAt)
                } catch (_: TimeoutCancellationException) {
                    failAfterCleanup(attempt, AppText.text(R.string.the_core_did_not_start_within_25))
                } catch (cancelled: CancellationException) {
                    releaseResources(); throw cancelled
                } catch (error: Throwable) {
                    failAfterCleanup(attempt, safeCoreError(error))
                }
            }
        }
    }

    private fun startMonitoring(attempt: Long, connectedAt: Long) {
        monitor?.cancel()
        monitor = scope.launch {
            var previous = 0L to 0L
            var previousAt = SystemClock.elapsedRealtime()
            while (app.vpnSession.isCurrent(attempt)) {
                delay(1000)
                if (!core.isRunning) {
                    app.vpnSession.update(VpnState.Error(AppText.text(R.string.the_core_process_or_tun_bridge_stopped, core.name)))
                    val reason = AppText.text(R.string.the_core_process_or_tun_bridge_stopped, core.name)
                    scope.launch { app.vpnSession.coreMutex.withLock { failAfterCleanup(attempt, reason) } }
                    return@launch
                }
                val totals = withContext(Dispatchers.IO) { core.trafficTotals() }
                val now = SystemClock.elapsedRealtime()
                if (wakeLock != null && now / 300_000 != previousAt / 300_000) wakeLock?.acquire(10 * 60 * 1000L)
                val elapsed = (now - previousAt).coerceAtLeast(1).toDouble() / 1000
                app.vpnSession.updateMetrics(VpnMetrics(connectedAt, totals.first, totals.second,
                    ((totals.first - previous.first).coerceAtLeast(0) / elapsed).toLong(),
                    ((totals.second - previous.second).coerceAtLeast(0) / elapsed).toLong(), core.name, core.localPort,
                    proxyOnly = proxyOnly, trafficAvailable = !proxyOnly || core.name == "Xray"))
                previous = totals
                previousAt = now
                refreshNotification()
            }
        }
    }

    fun stopVpn(preserveError: Boolean = false) {
        stopping = true
        monitor?.cancel(); operation?.cancel()
        operation = scope.launch {
            app.vpnSession.coreMutex.withLock {
                releaseResources()
                if (app.vpnSession.isCurrent(lease)) {
                    if (!preserveError) app.vpnSession.update(VpnState.Disconnected)
                    app.vpnSession.updateMetrics(VpnMetrics())
                }
                finishService()
            }
        }
    }

    private suspend fun failAfterCleanup(attempt: Long, message: String) {
        val hold = app.settings.settings.value.killSwitch && !proxyOnly && tunnel != null
        releaseResources(keepTunnel = hold)
        stopping = !hold
        if (app.vpnSession.isCurrent(attempt)) { app.vpnSession.update(VpnState.Error(message)); app.vpnSession.updateMetrics(VpnMetrics()) }
        if (hold) promoteToForeground(AppText.text(R.string.kill_switch_blocked)) else finishService()
    }

    private suspend fun releaseResources(keepTunnel: Boolean = false) = withContext(NonCancellable + Dispatchers.IO) {
        monitor?.cancel(); monitor = null
        probeJob?.cancel(); probeJob = null; notificationProbe = null
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }; wakeLock = null
        try { core.stop() } finally { if (!keepTunnel) { runCatching { tunnel?.close() }; tunnel = null } }
    }
    private fun updateWakeLock(enabled: Boolean) {
        if (!enabled) { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null; return }
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "NyaWire:Vpn").apply {
            setReferenceCounted(false); acquire(10 * 60 * 1000L)
        }
    }
    private fun finishService() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    private fun promoteToForeground(status: String) = ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(status),
        if (Build.VERSION.SDK_INT >= 34) { if (proxyOnly) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED } else 0)

    override fun onRevoke() { scope.launch { stopVpn() } }
    override fun onDestroy() {
        stopping = true
        operation?.cancel(); monitor?.cancel()
        scope.launch {
            app.vpnSession.coreMutex.withLock {
                releaseResources()
                if (app.vpnSession.isCurrent(lease) && app.vpnSession.state.value !is VpnState.Error) {
                    app.vpnSession.update(VpnState.Disconnected); app.vpnSession.updateMetrics(VpnMetrics())
                }
            }
            scope.cancel()
        }
        super.onDestroy()
    }

    private fun refreshNotification() {
        if (!stopping && app.vpnSession.state.value == VpnState.Connected)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(AppText.text(R.string.vpn_connected)))
    }

    /**
     * The running core follows the routing rules, so a test URL matching a direct rule would skip the server.
     * Only global routing sends its SOCKS requests through the server; otherwise a separate probe core checks it.
     */
    private fun liveCoreReachesServer() = core.publicSocksAvailable && liveRouting == com.gdhapp.client.domain.model.RoutingMode.GLOBAL

    private fun probeActiveConnection() {
        if (probeJob?.isActive == true) return
        val node = activeNode ?: return
        val attempt = lease
        val port = core.localPort
        val settings = app.settings.settings.value
        val method = settings.probeMethod
        probeJob = scope.launch {
            // Yield once so the assigned Job is visible to the notification's checking state.
            kotlinx.coroutines.yield()
            refreshNotification()
            try {
                val result = if (method.usesProxy && liveCoreReachesServer()) app.latencyTester.testViaProxy(port, method, settings)
                    else app.latencyTester.test(node, method, settings)
                if (app.vpnSession.isCurrent(attempt) && !stopping && app.vpnSession.state.value == VpnState.Connected) {
                    notificationProbe = result
                    app.subscriptions.updateProbe(node.id, result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (app.vpnSession.isCurrent(attempt) && !stopping)
                    notificationProbe = ProbeResult(method, error = safeCoreError(error))
            } finally {
                if (app.vpnSession.isCurrent(attempt) && !stopping) {
                    probeJob = null
                    refreshNotification()
                }
            }
        }
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, GdhappVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ping = PendingIntent.getService(this, 2, Intent(this, GdhappVpnService::class.java).setAction(ACTION_PING), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return VpnNotification.build(this, CHANNEL_ID, activeNode, status, app.vpnSession.metrics.value,
            app.vpnSession.state.value == VpnState.Connected && !stopping, probeJob?.isActive == true, notificationProbe, open, ping, stop, app.settings.settings.value.notificationSpeed)
    }

    companion object {
        private const val ACTION_STOP = "com.gdhapp.client.STOP"
        private const val ACTION_PING = "com.gdhapp.client.PING"
        private const val CHANNEL_ID = "gdhapp_vpn"
        private const val NOTIFICATION_ID = 77
        fun start(context: Context, config: String, waitForNetwork: Boolean = false) {
            val app = context.applicationContext as GdhappApplication
            if (app.vpnSession.state.value == VpnState.Connected || app.vpnSession.state.value == VpnState.Connecting) return
            app.vpnSession.pendingConfig = config
            app.vpnSession.setActiveNode(app.subscriptions.groups.value.flatMap { it.servers }.firstOrNull { it.rawConfigJson == config && it.isSelected })
            app.vpnSession.update(VpnState.Connecting)
            try { ContextCompat.startForegroundService(context, Intent(context, GdhappVpnService::class.java).putExtra("wait_for_network", waitForNetwork)) }
            catch (error: Exception) { app.vpnSession.pendingConfig = null; app.vpnSession.update(VpnState.Error(safeCoreError(error))) }
        }
        fun stop(context: Context) { context.startService(Intent(context, GdhappVpnService::class.java).setAction(ACTION_STOP)) }
    }
}
