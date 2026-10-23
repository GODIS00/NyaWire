package com.gdhapp.client.domain.model

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

enum class ThemeMode { SYSTEM, DARK, LIGHT;
    val label get() = when (this) { SYSTEM -> AppText.text(R.string.system); DARK -> AppText.text(R.string.dark); LIGHT -> AppText.text(R.string.light) }
}
enum class AccentStyle(val argb: Long) {
    MINT(0xFF73E0C1), BLUE(0xFF8BBCFF), LAVENDER(0xFFC0AEFF), PEACH(0xFFFFB596), PINK(0xFFF4A7D1), LIME(0xFFC8DF80);
    val label get() = when (this) { MINT -> AppText.text(R.string.mint); BLUE -> AppText.text(R.string.ocean); LAVENDER -> AppText.text(R.string.lavender); PEACH -> AppText.text(R.string.peach); PINK -> AppText.text(R.string.rose); LIME -> AppText.text(R.string.lime) }
}
enum class ServerLayout { COMFORTABLE, COMPACT, GRID;
    val label get() = when (this) { COMFORTABLE -> AppText.text(R.string.cards); COMPACT -> AppText.text(R.string.compact); GRID -> AppText.text(R.string.grid) }
}

enum class BlockStyle(val darkArgb: Long, val lightArgb: Long, val labelRes: Int) {
    AUTO(0xFF171D29, 0xFFF0F3F9, R.string.system),
    GRAPHITE(0xFF202124, 0xFFF0F0F2, R.string.graphite),
    BLUE(0xFF17283D, 0xFFE4EFFC, R.string.ocean),
    PURPLE(0xFF2B223D, 0xFFEFE6FB, R.string.lavender),
    ROSE(0xFF38212E, 0xFFFBE5EF, R.string.rose),
    MINT(0xFF17312B, 0xFFE0F3EB, R.string.mint);
    val label get() = AppText.text(labelRes)
}
enum class ServerSort { ORIGINAL, NAME, LATENCY, FAVORITES;
    val label get() = when (this) { ORIGINAL -> AppText.text(R.string.subscription_order); NAME -> AppText.text(R.string.name); LATENCY -> AppText.text(R.string.latency); FAVORITES -> AppText.text(R.string.favorites_first) }
}
enum class PerAppMode { ALL, EXCLUDE, INCLUDE;
    val label get() = when (this) { ALL -> AppText.text(R.string.all_apps); EXCLUDE -> AppText.text(R.string.exclude_selected); INCLUDE -> AppText.text(R.string.selected_only) }
}
enum class AppLanguage(val tag: String) { SYSTEM(""), RUSSIAN("ru"), ENGLISH("en");
    val label get() = when (this) { SYSTEM -> AppText.text(R.string.follow_system); RUSSIAN -> AppText.text(R.string.language_russian); ENGLISH -> "English" }
}
enum class ProbeConnectionMode(val label: String) { DEFAULT("Default"), DISABLE("Disable"), KEEP_ALIVE("KeepAlive") }
enum class PowerBorder(val labelRes: Int) { NEON(R.string.border_neon), AURORA(R.string.border_aurora), ACCENT(R.string.border_accent), SUNSET(R.string.border_sunset), CUSTOM(R.string.custom_color);
    val label get() = AppText.text(labelRes)
}
enum class IpMode(val label: String) { AUTO("Auto"), IPV4("IPv4"), IPV6("IPv6") }
enum class TunnelMode(val labelRes: Int) { VPN(R.string.mode_vpn), VPN_PROXY(R.string.mode_vpn_proxy), PROXY(R.string.mode_proxy);
    val label get() = AppText.text(labelRes)
}
enum class ConnectionMode { VPN, PROXY;
    val label get() = AppText.text(if (this == VPN) R.string.mode_vpn else R.string.mode_proxy)
}
enum class RoutingMode { PROFILE, GLOBAL, RULES;
    val label get() = AppText.text(when (this) { PROFILE -> R.string.routing_profile; GLOBAL -> R.string.routing_global; RULES -> R.string.routing_rules })
}

@androidx.compose.runtime.Immutable
data class ClientSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: AccentStyle = AccentStyle.MINT,
    val customAccent: Long? = null,
    val dynamicColors: Boolean = false,
    val amoled: Boolean = false,
    val blockStyle: BlockStyle = BlockStyle.AUTO,
    val customBlockColor: Long? = null,
    val animations: Boolean = true,
    val haptics: Boolean = true,
    val softBlur: Boolean = true,
    val connectionSound: Boolean = true,
    val backgroundPictures: Boolean = true,
    val backgroundDensity: Int = 9,
    val backgroundOpacity: Int = 12,
    val powerBorder: PowerBorder = PowerBorder.NEON,
    val powerGradientStart: Long = 0xFF538AFF,
    val powerGradientEnd: Long = 0xFFBB69FF,
    val layout: ServerLayout = ServerLayout.COMFORTABLE,
    val sort: ServerSort = ServerSort.ORIGINAL,
    val showFlags: Boolean = true,
    val showProtocol: Boolean = true,
    val showAddress: Boolean = false,
    val cornerRadius: Int = 24,
    val textScale: Float = 1f,
    val coreEngine: CoreEngine = CoreEngine.AUTO,
    val connectionMode: ConnectionMode = ConnectionMode.VPN,
    val routingMode: RoutingMode = RoutingMode.PROFILE,
    val directDomains: String = "",
    val directIps: String = "",
    val blockedDomains: String = "",
    val blockedIps: String = "",
    val fragmentEnabled: Boolean = false,
    val fragmentLength: String = "100-200",
    val fragmentInterval: String = "10-20",
    val sendHwid: Boolean = true,
    val autoUpdateSubscriptions: Boolean = true,
    val subscriptionUpdateHours: Int = 0,
    val connectOnOpen: Boolean = false,
    val connectOnBoot: Boolean = false,
    val xrayCompatibilityMode: Boolean = false,
    val ipv6: Boolean = false,
    val ipMode: IpMode = IpMode.AUTO,
    val muxEnabled: Boolean = false,
    val notificationSpeed: Boolean = true,
    val keepAwake: Boolean = false,
    val killSwitch: Boolean = false,
    val hotspotEnabled: Boolean = false,
    val hotspotPort: Int = 10809,
    val hotspotUser: String = "nyawire",
    val hotspotPassword: String = "",
    val mtu: Int = 1500,
    val dnsPrimary: String = "1.1.1.1",
    val dnsSecondary: String = "8.8.8.8",
    val bypassLan: Boolean = true,
    val perAppMode: PerAppMode = PerAppMode.ALL,
    val appPackages: Set<String> = emptySet(),
    val localSocksEnabled: Boolean = false,
    val localSocksPort: Int = 0,
    val verifyOnConnect: Boolean = true,
    val reconnectOnSelect: Boolean = true,
    val logLevel: String = "warning",
    val probeMethod: ProbeMethod = ProbeMethod.HTTP_GET,
    val probeUrl: String = "https://www.gstatic.com/generate_204",
    val probeTimeoutSeconds: Int = 8,
    val probeSamples: Int = 1,
    val probeConcurrency: Int = 2,
    val probeConnectionMode: ProbeConnectionMode = ProbeConnectionMode.DEFAULT,
    val refreshOnOpen: Boolean = true,
)
