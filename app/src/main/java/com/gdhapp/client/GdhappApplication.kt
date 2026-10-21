package com.gdhapp.client

import android.app.Application
import com.gdhapp.client.data.LocalSubscriptionRepository
import com.gdhapp.client.data.NetworkLatencyTester
import com.gdhapp.client.data.SettingsRepository
import com.gdhapp.client.domain.vpn.VpnCoreManager
import com.gdhapp.client.vpn.VpnSessionStore
import com.gdhapp.client.vpn.core.CoreSessionFactory
import com.gdhapp.client.vpn.core.BundledVpnCoreManager

class GdhappApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.gdhapp.client.i18n.AppText.initialize(this, settings.settings.value.language)
        com.gdhapp.client.background.SubscriptionUpdater.schedule(this, settings.settings.value.autoUpdateSubscriptions)
    }
    val subscriptions by lazy { LocalSubscriptionRepository(this, sendHwid = { settings.settings.value.sendHwid }) }
    val settings by lazy { SettingsRepository(this) }
    val coreSessions by lazy { CoreSessionFactory(this) }
    val latencyTester by lazy { NetworkLatencyTester(coreSessions) }
    val vpnSession = VpnSessionStore()
    val subscriptionUpdater by lazy { com.gdhapp.client.background.SubscriptionUpdater(subscriptions, settings, vpnSession,
        networkAvailable = { getSystemService(android.net.ConnectivityManager::class.java).activeNetwork != null }) }

    val vpnCoreFactory: () -> VpnCoreManager = { BundledVpnCoreManager(this, coreSessions) }
}
