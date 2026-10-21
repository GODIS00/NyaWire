package com.gdhapp.client.ui.settings

import com.gdhapp.client.R
import com.gdhapp.client.BuildConfig
import com.gdhapp.client.i18n.AppText

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.ui.main.AppEntry
import com.gdhapp.client.ui.main.MainActions
import com.gdhapp.client.ui.main.MainUiState
import com.gdhapp.client.vpn.NetworkRoutes
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

private data class TextEditor(val title: String, val value: String, val hint: String,
    val validate: (String) -> String?, val save: (String) -> Unit, val multiline: Boolean = false)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier, onPreviewSound: () -> Unit) {
    val settings = state.settings
    var category by rememberSaveable { mutableIntStateOf(-1) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showColorPicker by rememberSaveable { mutableStateOf(false) }
    var showBlockColorPicker by rememberSaveable { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun openCategory(value: Int, target: String? = null) { category = value; query = ""; pendingKey = target; focus.clearFocus() }
    androidx.activity.compose.BackHandler(category >= 0 || query.isNotBlank()) {
        if (query.isNotBlank()) query = "" else category = -1
    }
    LaunchedEffect(category, query) {
        if (query.isBlank() && category >= 0) {
            val index = settingsContentKeys[category]?.indexOf(pendingKey)?.coerceAtLeast(0) ?: 0
            listState.scrollToItem(index)
            pendingKey = null
        } else if (query.isNotBlank()) listState.scrollToItem(0)
    }
    var editor by remember { mutableStateOf<TextEditor?>(null) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val categories = listOf("VPN", AppText.text(R.string.appearance), AppText.text(R.string.checks), AppText.text(R.string.about), AppText.text(R.string.routing_title))
    val icons = listOf(Icons.Rounded.Shield, Icons.Rounded.Palette, Icons.Rounded.NetworkCheck, Icons.Rounded.Info, Icons.AutoMirrored.Rounded.AltRoute)
    Column(modifier) {
        if (category >= 0) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (query.isNotBlank()) query = "" else category = -1 }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, AppText.text(R.string.back_to_settings))
            }
            Text(categories[category], fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(AppText.text(R.string.search_settings), fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(20.dp)) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, AppText.text(R.string.clear_search)) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium)
        if (category < 0 && query.isBlank()) SettingsOverview(categories,
            listOf("${settings.connectionMode.label} · ${settings.coreEngine.label}", "${settings.theme.label} · ${if (settings.dynamicColors) AppText.text(R.string.android_wallpaper_colors) else if (settings.customAccent != null) AppText.text(R.string.custom_color) else settings.accent.label}",
                settings.probeMethod.label, "NyaWire ${BuildConfig.VERSION_NAME}", settings.routingMode.label), icons, Modifier.weight(1f).fillMaxWidth()) { openCategory(it) }
        else LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isNotBlank()) {
                val results = settingsSearchTargets.filter { it.matches(query) }
                if (results.isEmpty()) item { SettingsHint(AppText.text(R.string.no_results)) }
                items(results, key = { "search:${it.category}:${it.title}" }, contentType = { "settings-result" }) { result ->
                    SettingsDestination(result.label, categories[result.category], icons[result.category]) { openCategory(result.category, result.key) }
                }
            } else when (category) {
                0 -> {
                    item(key = "settings-content:1", contentType = "settings-content") { SettingsIntro(AppText.text(R.string.vpn_configuration), AppText.text(R.string.vpn_settings_take_effect_when_you_reconnect)) }
                    item(key = "settings-content:2", contentType = "settings-content") { SettingsSection(AppText.text(R.string.connection_mode), Icons.Rounded.SwapHoriz) {
                        ChoiceChips(ConnectionMode.entries, settings.connectionMode, { it.label }) { actions.updateSettings(settings.copy(connectionMode = it)) }
                        SettingsHint(AppText.text(R.string.proxy_mode_hint))
                    } }
                    item(key = "settings-content:24", contentType = "settings-content") { SettingsSection(AppText.text(R.string.local_socks_title), Icons.Rounded.Lan) {
                        val proxyOnly = settings.connectionMode == ConnectionMode.PROXY
                        ToggleSetting(AppText.text(R.string.local_socks_title), AppText.text(R.string.local_socks_hint), settings.localSocksEnabled || proxyOnly, enabled = !proxyOnly) {
                            actions.updateSettings(settings.copy(localSocksEnabled = it))
                        }
                        if (proxyOnly) SettingsHint(AppText.text(R.string.socks_required_proxy))
                        if (settings.localSocksEnabled || proxyOnly) {
                        EditSetting(AppText.text(R.string.local_socks_port), AppText.text(R.string.value_0_selects_an_available_port_automatically), if (settings.localSocksPort == 0) "10808" else settings.localSocksPort.toString()) {
                            editor = TextEditor(AppText.text(R.string.local_socks_port), settings.localSocksPort.toString(), AppText.text(R.string.value_0_or_1024_65535), { value -> if (value.toIntOrNull() == 0 || value.toIntOrNull() in 1024..65535) null else AppText.text(R.string.enter_0_or_a_port_between_1024) }, { actions.updateSettings(settings.copy(localSocksPort = it.toInt())) })
                        }
                        }
                        SettingsHint(AppText.text(R.string.vpn_settings_take_effect_when_you_reconnect))
                    } }
                    item(key = "settings-content:3", contentType = "settings-content") { SettingsSection(AppText.text(R.string.connection_core), Icons.Rounded.Memory) {
                        ChoiceChips(CoreEngine.entries, settings.coreEngine, { it.label }) { actions.updateSettings(settings.copy(coreEngine = it)) }
                        SettingsHint(AppText.text(R.string.auto_keeps_the_profile_s_format_xray))
                        ToggleSetting(AppText.text(R.string.xray_compatibility_mode), AppText.text(R.string.xray_compatibility_hint), settings.xrayCompatibilityMode) {
                            actions.updateSettings(settings.copy(xrayCompatibilityMode = it))
                        }
                    } }
                    item(key = "settings-content:4", contentType = "settings-content") { SettingsSection(AppText.text(R.string.network), Icons.Rounded.Wifi) {
                        ToggleSetting("IPv6", AppText.text(R.string.route_ipv6_through_the_tunnel_ipv6_is), settings.ipv6) { actions.updateSettings(settings.copy(ipv6 = it)) }
                        EditSetting("MTU", AppText.text(R.string.tunnel_packet_size), "${settings.mtu}") {
                            editor = TextEditor("MTU", settings.mtu.toString(), AppText.text(R.string.value_1280_to_9000), { value -> if (value.toIntOrNull() in 1280..9000) null else AppText.text(R.string.enter_a_number_between_1280_and_9000) }, { actions.updateSettings(settings.copy(mtu = it.toInt())) })
                        }
                        EditSetting(AppText.text(R.string.primary_dns), AppText.text(R.string.for_apps_routed_through_the_vpn), settings.dnsPrimary) {
                            editor = TextEditor(AppText.text(R.string.primary_dns), settings.dnsPrimary, AppText.text(R.string.ipv4_or_ipv6_address), { if (NetworkRoutes.isLiteralAddress(it)) null else AppText.text(R.string.enter_a_dns_server_ip_address) }, { actions.updateSettings(settings.copy(dnsPrimary = it)) })
                        }
                        EditSetting(AppText.text(R.string.secondary_dns), AppText.text(R.string.optional), settings.dnsSecondary.ifBlank { AppText.text(R.string.not_set) }) {
                            editor = TextEditor(AppText.text(R.string.secondary_dns), settings.dnsSecondary, AppText.text(R.string.ip_address_or_leave_blank), { if (it.isBlank() || NetworkRoutes.isLiteralAddress(it)) null else AppText.text(R.string.enter_a_dns_server_ip_address) }, { actions.updateSettings(settings.copy(dnsSecondary = it)) })
                        }
                        ToggleSetting(AppText.text(R.string.bypass_local_network), AppText.text(R.string.access_home_devices_and_local_addresses_directly), settings.bypassLan) { actions.updateSettings(settings.copy(bypassLan = it)) }
                    } }
                    item(key = "settings-content:6", contentType = "settings-content") { SettingsSection(AppText.text(R.string.behavior), Icons.Rounded.Tune) {
                        ToggleSetting(AppText.text(R.string.connect_on_open), AppText.text(R.string.connect_on_open_hint), settings.connectOnOpen) { actions.updateSettings(settings.copy(connectOnOpen = it)) }
                        ToggleSetting(AppText.text(R.string.connect_on_boot), AppText.text(R.string.connect_on_boot_hint), settings.connectOnBoot) { actions.updateSettings(settings.copy(connectOnBoot = it)) }
                        ToggleSetting(AppText.text(R.string.verify_connection), AppText.text(R.string.run_an_http_get_through_the_profile), settings.verifyOnConnect) { actions.updateSettings(settings.copy(verifyOnConnect = it)) }
                        ToggleSetting(AppText.text(R.string.reconnect_on_selection), AppText.text(R.string.switch_the_active_server_automatically), settings.reconnectOnSelect) { actions.updateSettings(settings.copy(reconnectOnSelect = it)) }
                        Text(AppText.text(R.string.log_level), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        ChoiceChips(listOf("error", "warning", "info", "debug"), settings.logLevel, { when (it) { "error" -> AppText.text(R.string.errors); "warning" -> AppText.text(R.string.normal); "info" -> AppText.text(R.string.info); else -> AppText.text(R.string.debug) } }) { actions.updateSettings(settings.copy(logLevel = it)) }
                        OutlinedButton(onClick = actions.openSystemVpnSettings, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(AppText.text(R.string.android_vpn_settings), fontSize = 12.sp)
                        }
                    } }
                    item(key = "settings-content:7", contentType = "settings-content") { SettingsSection(AppText.text(R.string.auto_update_subscriptions), Icons.Rounded.Update) {
                        ToggleSetting(AppText.text(R.string.update_when_opened), AppText.text(R.string.uses_the_interval_provided_by_the_subscription), settings.refreshOnOpen) { actions.updateSettings(settings.copy(refreshOnOpen = it)) }
                        SettingsHint(AppText.text(R.string.import_xray_and_sing_box_json_base64))
                        ToggleSetting(AppText.text(R.string.subscription_hwid), AppText.text(R.string.subscription_hwid_hint), settings.sendHwid) { actions.updateSettings(settings.copy(sendHwid = it)) }
                        ToggleSetting(AppText.text(R.string.auto_update_subscriptions), AppText.text(R.string.auto_update_hint), settings.autoUpdateSubscriptions) { actions.updateSettings(settings.copy(autoUpdateSubscriptions = it)) }
                        ChoiceChips(listOf(0, 1, 3, 6, 12, 24), settings.subscriptionUpdateHours, { if (it == 0) AppText.text(R.string.from_subscription) else AppText.text(R.string.hours_interval, it) }) { actions.updateSettings(settings.copy(subscriptionUpdateHours = it)) }
                    } }
                }
                4 -> {
                    item(key = "settings-content:9", contentType = "settings-content") { SettingsSection(AppText.text(R.string.routing_mode), Icons.AutoMirrored.Rounded.AltRoute) {
                        ChoiceChips(RoutingMode.entries, settings.routingMode, { it.label }) { actions.updateSettings(settings.copy(routingMode = it)) }
                        SettingsHint(AppText.text(R.string.routing_rules_hint))
                        if (settings.routingMode == RoutingMode.RULES) {
                            fun editRules(title: String, value: String, ip: Boolean, save: (String) -> Unit) {
                                editor = TextEditor(title, value, if (ip) "192.0.2.0/24\n2001:db8::/32" else "example.com\n*.example.org", { text ->
                                    runCatching { if (ip) com.gdhapp.client.vpn.core.ClientCoreOptions.ips(text) else com.gdhapp.client.vpn.core.ClientCoreOptions.domains(text) }.exceptionOrNull()?.message
                                }, save, multiline = true)
                            }
                            EditSetting(AppText.text(R.string.direct_domains), AppText.text(R.string.one_rule_per_line), "${com.gdhapp.client.vpn.core.ClientCoreOptions.lines(settings.directDomains).size}") {
                                editRules(AppText.text(R.string.direct_domains), settings.directDomains, false) { actions.updateSettings(settings.copy(directDomains = it)) }
                            }
                            EditSetting(AppText.text(R.string.direct_ips), AppText.text(R.string.one_rule_per_line), "${com.gdhapp.client.vpn.core.ClientCoreOptions.lines(settings.directIps).size}") {
                                editRules(AppText.text(R.string.direct_ips), settings.directIps, true) { actions.updateSettings(settings.copy(directIps = it)) }
                            }
                            EditSetting(AppText.text(R.string.blocked_domains), AppText.text(R.string.one_rule_per_line), "${com.gdhapp.client.vpn.core.ClientCoreOptions.lines(settings.blockedDomains).size}") {
                                editRules(AppText.text(R.string.blocked_domains), settings.blockedDomains, false) { actions.updateSettings(settings.copy(blockedDomains = it)) }
                            }
                            EditSetting(AppText.text(R.string.blocked_ips), AppText.text(R.string.one_rule_per_line), "${com.gdhapp.client.vpn.core.ClientCoreOptions.lines(settings.blockedIps).size}") {
                                editRules(AppText.text(R.string.blocked_ips), settings.blockedIps, true) { actions.updateSettings(settings.copy(blockedIps = it)) }
                            }
                        }
                    } }
                    item(key = "settings-content:5", contentType = "settings-content") { SettingsSection(AppText.text(R.string.per_app_vpn), Icons.Rounded.Apps) {
                        ChoiceChips(PerAppMode.entries, settings.perAppMode, { it.label }) { actions.updateSettings(settings.copy(perAppMode = it)) }
                        if (settings.perAppMode != PerAppMode.ALL) EditSetting(AppText.text(R.string.selected_apps), if (settings.perAppMode == PerAppMode.INCLUDE) AppText.text(R.string.use_the_vpn_for_these_apps) else AppText.text(R.string.these_apps_bypass_the_vpn), "${settings.appPackages.size}") {
                            actions.loadApps(); showApps = true
                        }
                    } }
                    item(key = "settings-content:10", contentType = "settings-content") { SettingsSection(AppText.text(R.string.fragmentation), Icons.AutoMirrored.Rounded.CallSplit) {
                        ToggleSetting(AppText.text(R.string.fragmentation), AppText.text(R.string.fragment_hint), settings.fragmentEnabled) { actions.updateSettings(settings.copy(fragmentEnabled = it)) }
                        if (settings.fragmentEnabled) {
                            EditSetting(AppText.text(R.string.fragment_length), AppText.text(R.string.fragment_range_hint), settings.fragmentLength) {
                                editor = TextEditor(AppText.text(R.string.fragment_length), settings.fragmentLength, "100-200", { if (com.gdhapp.client.vpn.core.ClientCoreOptions.validRange(it, 1, 4096)) null else AppText.text(R.string.invalid_fragment_range) }, { actions.updateSettings(settings.copy(fragmentLength = it)) })
                            }
                            EditSetting(AppText.text(R.string.fragment_interval), AppText.text(R.string.fragment_range_hint), settings.fragmentInterval) {
                                editor = TextEditor(AppText.text(R.string.fragment_interval), settings.fragmentInterval, "10-20", { if (com.gdhapp.client.vpn.core.ClientCoreOptions.validRange(it, 0, 1000)) null else AppText.text(R.string.invalid_fragment_range) }, { actions.updateSettings(settings.copy(fragmentInterval = it)) })
                            }
                        }
                    } }
                }
                1 -> {
                    item(key = "settings-content:12", contentType = "settings-content") { SettingsSection(AppText.text(R.string.app_language), Icons.Rounded.Language) {
                        ChoiceChips(AppLanguage.entries, settings.language, { it.label }) { actions.updateSettings(settings.copy(language = it)) }
                    } }
                    item(key = "settings-content:14", contentType = "settings-content") { SettingsSection(AppText.text(R.string.color_and_theme), Icons.Rounded.Palette) {
                        ChoiceChips(ThemeMode.entries, settings.theme, { it.label }) { actions.updateSettings(settings.copy(theme = it)) }
                        Text(AppText.text(R.string.accent), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AccentStyle.entries.forEach { accent ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(onClick = { actions.updateSettings(settings.copy(accent = accent, customAccent = null, dynamicColors = false)) },
                                        shape = CircleShape, color = Color(accent.argb), border = if (settings.customAccent == null && settings.accent == accent && !settings.dynamicColors) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
                                        modifier = Modifier.size(39.dp)) {
                                        if (settings.customAccent == null && settings.accent == accent && !settings.dynamicColors) Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, accent.label, tint = Color(0xFF17352E), modifier = Modifier.size(18.dp)) }
                                    }
                                    Text(accent.label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                                }
                            }
                        }
                        EditSetting(AppText.text(R.string.custom_color), AppText.text(R.string.custom_color_hint),
                            settings.customAccent?.let { "#%06X".format(it and 0xFFFFFF) } ?: AppText.text(R.string.choose_color)) { showColorPicker = true }
                        ToggleSetting(AppText.text(R.string.android_wallpaper_colors), AppText.text(R.string.dynamic_colors_on_android_12_and_later), settings.dynamicColors,
                            enabled = Build.VERSION.SDK_INT >= 31) { actions.updateSettings(settings.copy(dynamicColors = it, customAccent = if (it) null else settings.customAccent)) }
                        ToggleSetting(AppText.text(R.string.true_black), AppText.text(R.string.amoled_background_in_dark_mode), settings.amoled) { actions.updateSettings(settings.copy(amoled = it)) }
                    } }
                    item(key = "settings-content:26", contentType = "settings-content") { SettingsSection(AppText.text(R.string.block_colors), Icons.Rounded.FormatColorFill) {
                        val dark = com.gdhapp.client.ui.theme.isDarkAppearance(settings)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BlockStyle.entries.forEach { style ->
                                val selected = settings.customBlockColor == null && settings.blockStyle == style
                                val color = if (style == BlockStyle.AUTO) MaterialTheme.colorScheme.secondaryContainer else Color(if (dark) style.darkArgb else style.lightArgb)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(onClick = { actions.updateSettings(settings.copy(blockStyle = style, customBlockColor = null)) },
                                        shape = CircleShape, color = color,
                                        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                                        modifier = Modifier.size(39.dp)) {
                                        if (selected) Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, style.label,
                                            tint = if (color.luminance() > .179f) Color.Black else Color.White, modifier = Modifier.size(18.dp)) }
                                    }
                                    Text(style.label, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
                                }
                            }
                        }
                        EditSetting(AppText.text(R.string.custom_color), AppText.text(R.string.block_colors_hint),
                            settings.customBlockColor?.let { "#%06X".format(it and 0xFFFFFF) } ?: AppText.text(R.string.choose_color)) { showBlockColorPicker = true }
                    } }
                    item(key = "settings-content:15", contentType = "settings-content") { SettingsSection(AppText.text(R.string.server_list), Icons.Rounded.ViewAgenda) {
                        ChoiceChips(ServerLayout.entries, settings.layout, { it.label }) { actions.updateSettings(settings.copy(layout = it)) }
                        Text(AppText.text(R.string.sort_order), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        ChoiceChips(ServerSort.entries, settings.sort, { it.label }) { actions.updateSettings(settings.copy(sort = it)) }
                        ToggleSetting(AppText.text(R.string.country_flags), AppText.text(R.string.detected_from_the_profile_name), settings.showFlags) { actions.updateSettings(settings.copy(showFlags = it)) }
                        ToggleSetting(AppText.text(R.string.protocol_and_transport), AppText.text(R.string.for_example_vless_xhttp_reality), settings.showProtocol) { actions.updateSettings(settings.copy(showProtocol = it)) }
                        ToggleSetting(AppText.text(R.string.server_addresses), AppText.text(R.string.show_host_and_port_on_server_cards), settings.showAddress) { actions.updateSettings(settings.copy(showAddress = it)) }
                    } }
                    item(key = "settings-content:16", contentType = "settings-content") { SettingsSection(AppText.text(R.string.shape_and_motion), Icons.Rounded.AutoAwesome) {
                        Text(AppText.text(R.string.corner_radius_dp, settings.cornerRadius), fontSize = 13.sp)
                        Slider(settings.cornerRadius.toFloat(), { actions.updateSettings(settings.copy(cornerRadius = it.roundToInt())) }, valueRange = 12f..36f, steps = 7)
                        Text(AppText.text(R.string.text_size, (settings.textScale * 100).roundToInt()), fontSize = 13.sp)
                        Slider(settings.textScale, { actions.updateSettings(settings.copy(textScale = it)) }, valueRange = .85f..1.2f, steps = 6)
                        ToggleSetting(AppText.text(R.string.background_pictures), AppText.text(R.string.background_pictures_hint), settings.backgroundPictures) { actions.updateSettings(settings.copy(backgroundPictures = it)) }
                        ToggleSetting(AppText.text(R.string.connection_sound), AppText.text(R.string.connection_sound_hint), settings.connectionSound) { actions.updateSettings(settings.copy(connectionSound = it)) }
                        TextButton(onClick = onPreviewSound, enabled = settings.connectionSound) {
                            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(AppText.text(R.string.preview_connection_sound), fontSize = 12.sp)
                        }
                        TextButton(onClick = actions.resetAppearance) { Text(AppText.text(R.string.reset_appearance), fontSize = 12.sp) }
                    } }
                }
                2 -> {
                    item(key = "settings-content:17", contentType = "settings-content") { SettingsIntro(AppText.text(R.string.latency_checks), AppText.text(R.string.http_get_and_head_use_the_vpn)) }
                    item(key = "settings-content:18", contentType = "settings-content") { SettingsSection(AppText.text(R.string.check_method), Icons.Rounded.NetworkCheck) {
                        ChoiceChips(ProbeMethod.entries, settings.probeMethod, { it.label }) { actions.updateSettings(settings.copy(probeMethod = it)) }
                    } }
                    item(key = "settings-content:19", contentType = "settings-content") { SettingsSection(AppText.text(R.string.check_options), Icons.Rounded.Timer) {
                        Text(AppText.text(R.string.http_connections), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        ChoiceChips(ProbeConnectionMode.entries, settings.probeConnectionMode, { it.label }) { actions.updateSettings(settings.copy(probeConnectionMode = it)) }
                        SettingsHint(AppText.text(R.string.default_uses_standard_http_behavior_disable_opens))
                        EditSetting(AppText.text(R.string.http_test_url), AppText.text(R.string.also_used_to_verify_a_new_vpn), settings.probeUrl) {
                            editor = TextEditor(AppText.text(R.string.test_url), settings.probeUrl, "https://www.gstatic.com/generate_204", { if (it.toHttpUrlOrNull() != null) null else AppText.text(R.string.enter_an_http_or_https_url) }, { actions.updateSettings(settings.copy(probeUrl = it)) })
                        }
                        Text(AppText.text(R.string.timeout), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        ChoiceChips(listOf(3, 5, 8, 12, 20, 30), settings.probeTimeoutSeconds, { AppText.text(R.string.s_290, it) }) { actions.updateSettings(settings.copy(probeTimeoutSeconds = it)) }
                        Text(AppText.text(R.string.samples_median_result), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        ChoiceChips(listOf(1, 2, 3, 5), settings.probeSamples, { it.toString() }) { actions.updateSettings(settings.copy(probeSamples = it)) }
                        Text(AppText.text(R.string.concurrent_checks), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        ChoiceChips(listOf(1, 2, 3, 4), settings.probeConcurrency, { it.toString() }) { actions.updateSettings(settings.copy(probeConcurrency = it)) }
                        SettingsHint(AppText.text(R.string.each_http_check_uses_a_separate_core))
                    } }
                }
                else -> {
                    item(key = "settings-content:20", contentType = "settings-content") { SettingsIntro("NyaWire ${BuildConfig.VERSION_NAME}", AppText.text(R.string.subscriptions_and_settings_stay_on_your_device), showTitle = true) }
                    item(key = "settings-content:25", contentType = "settings-content") { SettingsSection(AppText.text(R.string.app_update_title), Icons.Rounded.SystemUpdate) {
                        SettingsHint(AppText.text(R.string.app_update_hint))
                        OutlinedButton(onClick = actions.checkAppUpdate, modifier = Modifier.fillMaxWidth()) { Text(AppText.text(R.string.app_update_check)) }
                    } }
                    item(key = "settings-content:21", contentType = "settings-content") { SettingsSection(AppText.text(R.string.bundled_cores), Icons.Rounded.Memory) {
                        listOf("Xray", "sing-box").forEach { engine ->
                            Text(engine, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            SelectionContainer { Text(state.coreVersions[engine] ?: AppText.text(R.string.checking_library), fontSize = 11.sp,
                                color = if (state.coreVersions[engine]?.let { it.contains("error", true) || it.contains("Ошибка", true) } == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                            Spacer(Modifier.height(8.dp))
                        }
                        SettingsHint(AppText.text(R.string.tun_bridge_hev_2_19_1_cores))
                        OutlinedButton(onClick = actions.checkCoreVersions, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(AppText.text(R.string.check_libraries), fontSize = 12.sp)
                        }
                        TextButton(onClick = { showLicenses = true }) { Text(AppText.text(R.string.component_licenses), fontSize = 12.sp) }
                    } }
                    item(key = "settings-content:23", contentType = "settings-content") { SettingsSection(AppText.text(R.string.diagnostics), Icons.Rounded.BugReport) {
                        Text(if (state.vpnState is VpnState.Error) state.vpnState.message else AppText.text(R.string.no_critical_connection_errors), fontSize = 12.sp,
                            color = if (state.vpnState is VpnState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { showDiagnostics = true }, modifier = Modifier.fillMaxWidth()) { Text(AppText.text(R.string.diagnostic_information), fontSize = 12.sp) }
                    } }
                }
        }
    }
    }
    if (showColorPicker) CustomColorDialog(settings.customAccent ?: settings.accent.argb,
        onDismiss = { showColorPicker = false }, onSave = { color ->
            actions.updateSettings(settings.copy(customAccent = color, dynamicColors = false)); showColorPicker = false
        })
    if (showBlockColorPicker) CustomColorDialog(settings.customBlockColor ?: (if (com.gdhapp.client.ui.theme.isDarkAppearance(settings)) settings.blockStyle.darkArgb else settings.blockStyle.lightArgb),
        onDismiss = { showBlockColorPicker = false }, onSave = { color ->
            actions.updateSettings(settings.copy(customBlockColor = color)); showBlockColorPicker = false
        })
    editor?.let { config ->
        var text by remember(config) { mutableStateOf(config.value) }
        var error by remember(config) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editor = null }, title = { Text(config.title) },
            text = { Column { OutlinedTextField(text, { text = it; error = null }, placeholder = { Text(config.hint, fontSize = 11.sp) }, singleLine = !config.multiline, minLines = if (config.multiline) 4 else 1, maxLines = if (config.multiline) 8 else 1, isError = error != null)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) } } },
            confirmButton = { TextButton(onClick = { val value = text.trim(); error = config.validate(value); if (error == null) { config.save(value); editor = null } }) { Text(AppText.text(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editor = null }) { Text(AppText.text(R.string.cancel)) } })
    }
    if (showApps) AppsPicker(state.apps, settings.appPackages, state.loadingApps, onDismiss = { showApps = false }, onChange = { actions.updateSettings(settings.copy(appPackages = it)) }, onExport = actions.copyText)
    if (showLicenses) {
        val entries = listOf("NyaWire · GPL-3.0" to "GPL-3.0.txt", "AndroidLibXrayLite" to "AndroidLibXrayLite-LICENSE",
            "Xray-core" to "Xray-core-LICENSE", "sing-box" to "sing-box-LICENSE", "HEV" to "hev-LICENSE",
            "GeoIP" to "geoip-LICENSE", "Geosite" to "geosite-LICENSE", "flag-icons · MIT" to "flag-icons-LICENSE")
        var selectedLicense by remember { mutableIntStateOf(0) }
        val license = remember(selectedLicense) { context.assets.open("licenses/${entries[selectedLicense].second}").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text(AppText.text(R.string.licenses)) },
            text = { Column(Modifier.heightIn(max = 480.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    entries.forEachIndexed { index, item -> FilterChip(selectedLicense == index, { selectedLicense = index }, label = { Text(item.first, fontSize = 10.sp) }) }
                }
                key(selectedLicense) { SelectionContainer { Text(license, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.verticalScroll(rememberScrollState())) } }
            } }, confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(AppText.text(R.string.close)) } })
    }
    if (showDiagnostics) {
        val report = buildString {
            appendLine("NyaWire ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(AppText.text(R.string.architectures, Build.SUPPORTED_ABIS.joinToString()))
            state.coreVersions.forEach { (engine, version) -> appendLine("${engine}: ${version}") }
            appendLine(AppText.text(R.string.profiles_method, state.allNodes.size, settings.probeMethod.label))
            appendLine("MTU ${settings.mtu}; IPv6 ${settings.ipv6}; DNS ${settings.dnsPrimary}")
            appendLine("Xray TUN: ${if (settings.xrayCompatibilityMode) "HEV" else "native"}")
            (state.vpnState as? VpnState.Error)?.let { appendLine(AppText.text(R.string.error_309, it.message)) }
        }
        AlertDialog(onDismissRequest = { showDiagnostics = false }, title = { Text(AppText.text(R.string.diagnostics)) }, text = { SelectionContainer { Text(report, fontSize = 12.sp) } },
            confirmButton = { TextButton(onClick = { actions.copyText(report) }) { Text(AppText.text(R.string.copy)) } },
            dismissButton = { TextButton(onClick = { showDiagnostics = false }) { Text(AppText.text(R.string.close)) } })
    }
}

@Composable
private fun SettingsIntro(title: String, subtitle: String, showTitle: Boolean = false) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (showTitle) Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
} }

@Composable
private fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) = com.gdhapp.client.ui.theme.BlockColors {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(values: List<T>, selected: T, label: (T) -> String, onClick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { values.forEach { value ->
        FilterChip(selected = selected == value, onClick = { onClick(value) }, label = { Text(label(value), fontSize = 11.sp) })
    } }
}

@Composable
private fun ToggleSetting(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.heightIn(min = 48.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun EditSetting(title: String, subtitle: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(start = 4.dp))
    }
}

@Composable
private fun SettingsHint(text: String) { Text(text, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }

