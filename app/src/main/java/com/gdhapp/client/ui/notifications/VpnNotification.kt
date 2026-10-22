package com.gdhapp.client.ui.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.gdhapp.client.R
import com.gdhapp.client.domain.model.ProbeResult
import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.domain.model.VpnMetrics
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.ui.main.formatBytes

/** Standard Android notification template: readable on different OEMs and font scales. */
object VpnNotification {
    fun build(context: Context, channel: String, node: ServerNode?, status: String,
        metrics: VpnMetrics, connected: Boolean, checking: Boolean, probe: ProbeResult?,
        open: PendingIntent, ping: PendingIntent, stop: PendingIntent, showSpeed: Boolean = true): Notification {
        val speeds = if (metrics.trafficAvailable) "↑ ${formatBytes(metrics.uploadBytesPerSecond, true)}   ↓ ${formatBytes(metrics.downloadBytesPerSecond, true)}" else AppText.text(R.string.proxy_no_counters)
        val pingText = when {
            checking -> AppText.text(R.string.notification_ping_checking)
            probe == null -> AppText.text(R.string.notification_ping_idle)
            probe.isSuccess -> AppText.text(R.string.notification_ping_result, probe.method.label, AppText.text(R.string.ms, probe.latencyMs))
            else -> AppText.text(R.string.notification_ping_failed, probe.method.label)
        }
        val seconds = ((System.currentTimeMillis() - metrics.connectedAt) / 1000).coerceAtLeast(0)
        val duration = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        val details = if (connected) listOfNotNull(if (metrics.proxyOnly) "SOCKS5 · 127.0.0.1:${metrics.localPort}" else null, speeds.takeIf { showSpeed },
            if (metrics.trafficAvailable) AppText.text(R.string.notification_transferred, formatBytes(metrics.uploadBytes), formatBytes(metrics.downloadBytes)) else null,
            AppText.text(R.string.notification_connected_for, duration, metrics.engine), pingText).joinToString("\n") else status
        val title = node?.name?.takeIf { it.isNotBlank() } ?: "NyaWire"
        return NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(title).setContentText(if (connected && showSpeed) speeds else status)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(details))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShowWhen(false).setUsesChronometer(false)
            .apply { if (connected) addAction(0, AppText.text(if (checking) R.string.checking else R.string.notification_ping_action), ping) }
            .addAction(0, AppText.text(R.string.notification_stop_action), stop).build()
    }
}
