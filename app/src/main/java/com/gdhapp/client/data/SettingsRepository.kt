package com.gdhapp.client.data

import android.content.Context
import com.gdhapp.client.domain.model.ClientSettings
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("client_settings", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mutableSettings = MutableStateFlow(load())
    val settings = mutableSettings.asStateFlow()

    @Synchronized
    fun update(transform: (ClientSettings) -> ClientSettings) {
        val previousAutoUpdate = mutableSettings.value.autoUpdateSubscriptions
        val next = normalize(transform(mutableSettings.value))
        com.gdhapp.client.i18n.AppText.setLanguage(next.language)
        mutableSettings.value = next
        preferences.edit().putString("settings", gson.toJson(next)).apply()
        if (previousAutoUpdate != next.autoUpdateSubscriptions) com.gdhapp.client.background.SubscriptionUpdater.schedule(context, next.autoUpdateSubscriptions)
    }

    fun resetAppearance() = update { old -> ClientSettings().let { defaults -> old.copy(
        theme = defaults.theme, accent = defaults.accent, customAccent = null, dynamicColors = false, amoled = false,
        blockStyle = defaults.blockStyle, customBlockColor = null,
        animations = true, haptics = true, softBlur = true, connectionSound = defaults.connectionSound,
        backgroundPictures = true, layout = defaults.layout, sort = defaults.sort,
        backgroundDensity = defaults.backgroundDensity, backgroundOpacity = defaults.backgroundOpacity, powerBorder = defaults.powerBorder,
        powerGradientStart = defaults.powerGradientStart, powerGradientEnd = defaults.powerGradientEnd,
        showFlags = true, showProtocol = true, showAddress = false, cornerRadius = 24, textScale = 1f,
    ) } }

    private fun load(): ClientSettings = runCatching {
        preferences.getString("settings", null)?.let {
            normalize(gson.fromJson(it, ClientSettings::class.java))
        } ?: ClientSettings()
    }.getOrElse { ClientSettings() }

    private fun normalize(value: ClientSettings): ClientSettings {
        // Also tolerate missing fields from an older preference schema.
        val defaults = ClientSettings()
        return value.copy(
            // These effects are now built in. Repair previous disabled preferences on load/update.
            animations = true, softBlur = true, haptics = true,
            backgroundDensity = value.backgroundDensity.takeIf { it in setOf(6, 9, 12) } ?: defaults.backgroundDensity,
            backgroundOpacity = value.backgroundOpacity.takeIf { it in 4..24 } ?: defaults.backgroundOpacity,
            powerBorder = (value.powerBorder as? com.gdhapp.client.domain.model.PowerBorder) ?: defaults.powerBorder,
            powerGradientStart = value.powerGradientStart.takeIf { it in 0xFF000000L..0xFFFFFFFFL } ?: defaults.powerGradientStart,
            powerGradientEnd = value.powerGradientEnd.takeIf { it in 0xFF000000L..0xFFFFFFFFL } ?: defaults.powerGradientEnd,
            connectionMode = (value.connectionMode as? com.gdhapp.client.domain.model.ConnectionMode) ?: defaults.connectionMode,
            routingMode = (value.routingMode as? com.gdhapp.client.domain.model.RoutingMode) ?: defaults.routingMode,
            directDomains = value.directDomains.orEmpty().take(16000), directIps = value.directIps.orEmpty().take(16000),
            blockedDomains = value.blockedDomains.orEmpty().take(16000), blockedIps = value.blockedIps.orEmpty().take(16000),
            fragmentLength = value.fragmentLength?.takeIf { com.gdhapp.client.vpn.core.ClientCoreOptions.validRange(it, 1, 4096) } ?: defaults.fragmentLength,
            fragmentInterval = value.fragmentInterval?.takeIf { com.gdhapp.client.vpn.core.ClientCoreOptions.validRange(it, 0, 1000) } ?: defaults.fragmentInterval,
            subscriptionUpdateHours = value.subscriptionUpdateHours.takeIf { it in setOf(0, 1, 3, 6, 12, 24) } ?: 0,
            language = (value.language as? com.gdhapp.client.domain.model.AppLanguage) ?: defaults.language,
            probeConnectionMode = (value.probeConnectionMode as? com.gdhapp.client.domain.model.ProbeConnectionMode) ?: defaults.probeConnectionMode,
            theme = (value.theme as? com.gdhapp.client.domain.model.ThemeMode) ?: defaults.theme,
            accent = (value.accent as? com.gdhapp.client.domain.model.AccentStyle) ?: defaults.accent,
            blockStyle = (value.blockStyle as? com.gdhapp.client.domain.model.BlockStyle) ?: defaults.blockStyle,
            customBlockColor = value.customBlockColor?.takeIf { it in 0xFF000000L..0xFFFFFFFFL },
            layout = (value.layout as? com.gdhapp.client.domain.model.ServerLayout) ?: defaults.layout,
            sort = (value.sort as? com.gdhapp.client.domain.model.ServerSort) ?: defaults.sort,
            coreEngine = (value.coreEngine as? com.gdhapp.client.domain.model.CoreEngine) ?: defaults.coreEngine,
            perAppMode = (value.perAppMode as? com.gdhapp.client.domain.model.PerAppMode) ?: defaults.perAppMode,
            probeMethod = (value.probeMethod as? com.gdhapp.client.domain.model.ProbeMethod) ?: defaults.probeMethod,
            ipMode = (value.ipMode as? com.gdhapp.client.domain.model.IpMode) ?: defaults.ipMode,
            notificationSpeed = value.notificationSpeed,
            hotspotUser = value.hotspotUser.orEmpty().ifBlank { "nyawire" }.take(64),
            hotspotPassword = value.hotspotPassword.orEmpty().take(128),
            hotspotPort = value.hotspotPort.takeIf { it in 1024..65535 } ?: 10809,
            appPackages = value.appPackages.orEmpty(),
            mtu = value.mtu.coerceIn(1280, 9000),
            customAccent = value.customAccent?.takeIf { it in 0xFF000000L..0xFFFFFFFFL },
            localSocksPort = value.localSocksPort.coerceIn(0, 65535),
            cornerRadius = value.cornerRadius.coerceIn(12, 36),
            textScale = value.textScale.takeIf { it.isFinite() }?.coerceIn(.85f, 1.2f) ?: 1f,
            probeTimeoutSeconds = value.probeTimeoutSeconds.coerceIn(2, 30),
            probeSamples = value.probeSamples.coerceIn(1, 5),
            probeConcurrency = value.probeConcurrency.coerceIn(1, 4),
            dnsPrimary = (value.dnsPrimary as String?).orEmpty().ifBlank { defaults.dnsPrimary },
            dnsSecondary = (value.dnsSecondary as String?).orEmpty(),
            probeUrl = (value.probeUrl as String?).orEmpty().ifBlank { defaults.probeUrl },
            logLevel = value.logLevel.takeIf { it in setOf("error", "warning", "info", "debug") } ?: "warning",
        )
    }
}
