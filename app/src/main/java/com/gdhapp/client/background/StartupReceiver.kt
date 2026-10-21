package com.gdhapp.client.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.gdhapp.client.GdhappApplication
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.vpn.GdhappVpnService

class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as GdhappApplication
        val settings = app.settings.settings.value
        SubscriptionUpdater.schedule(app, settings.autoUpdateSubscriptions)
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !settings.connectOnBoot) return
        if (settings.connectionMode == ConnectionMode.VPN && VpnService.prepare(app) != null) return
        app.subscriptions.groups.value.flatMap { it.servers }.firstOrNull { it.isSelected }?.let { GdhappVpnService.start(app, it.rawConfigJson, waitForNetwork = true) }
    }
}
