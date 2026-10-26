package com.gdhapp.client.vpn.core

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.CoreEngine
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class PreparedCoreConfig(val engine: CoreEngine, val json: String)

object CoreConfigBuilder {
    /**
     * `forProbe` builds a latency check: every request goes through the tested server. With the profile's or the
     * client's rules a test URL that matches a direct rule (or an imported profile's direct default) would leave
     * without the server, and every profile would answer, working or not.
     */
    fun build(raw: String, settings: ClientSettings, port: Int, nativeTun: Boolean = false, includeSocks: Boolean = true, socksCredentials: Pair<String, String>? = null, shareProxy: Boolean = false, geo: GeoData? = null, forProbe: Boolean = false): PreparedCoreConfig {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject.deepCopy() }
            .getOrElse { throw IllegalArgumentException(AppText.text(R.string.the_configuration_is_not_a_json_object)) }
        require(root.get("demo")?.asBoolean != true) { AppText.text(R.string.demo_configurations_were_removed_add_your_own) }
        root.remove("demo"); root.remove("remarks"); root.remove("name"); root.remove("countryCode")
        val outbounds = root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalArgumentException(AppText.text(R.string.the_configuration_has_no_outbounds))
        val leaf = outbounds.firstOrNull { it.isJsonObject &&
            (it.asJsonObject.text("protocol") ?: it.asJsonObject.text("type")) !in setOf("selector", "urltest", "direct", "freedom", "block", "blackhole", "dns")
        }?.asJsonObject ?: throw IllegalArgumentException(AppText.text(R.string.the_configuration_has_no_proxy_outbound))
        val xrayFormat = leaf.has("protocol")
        val quic = (leaf.text("protocol") ?: leaf.text("type")) in setOf("hysteria", "hysteria2", "hy2", "tuic", "anytls")
        val nativeXrayHy2 = leaf.text("protocol") == "hysteria" &&
            (leaf.obj("settings")?.get("version")?.asInt == 2 || leaf.obj("streamSettings")?.obj("hysteriaSettings")?.get("version")?.asInt == 2)
        val autoEngine = if (xrayFormat && (!quic || nativeXrayHy2)) CoreEngine.XRAY else CoreEngine.SING_BOX
        val engine = if (settings.coreEngine == CoreEngine.AUTO) autoEngine else settings.coreEngine
        if (engine == CoreEngine.SING_BOX && xrayFormat) {
            // Panel profiles carry blackhole/dns helpers; in sing-box these are rule actions, not outbounds.
            val actions = mutableMapOf<String, String>()
            root.add("outbounds", JsonArray().apply { outbounds.forEach { item ->
                val outbound = item.asJsonObject
                val protocol = outbound.text("protocol")
                if (protocol == "blackhole" || protocol == "dns") outbound.text("tag")?.let { actions[it] = protocol }
                else add(xrayToSingBox(outbound))
            } })
            val tags = root.getAsJsonArray("outbounds").mapNotNull { it.asJsonObject.text("tag") }.toSet()
            val rules = xrayRulesToSingBox(root.obj("routing"), actions, tags, geo)
            root.remove("routing"); root.remove("dns"); root.remove("policy"); root.remove("stats"); root.remove("api")
            if (rules.size() > 0) root.add("route", JsonObject().apply { add("rules", rules) })
        } else if (engine == CoreEngine.XRAY && !xrayFormat) {
            throw IllegalArgumentException(AppText.text(R.string.this_is_a_sing_box_profile_select))
        }
        if (engine == CoreEngine.XRAY) {
            val oldTags = root.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
                .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.text("tag") }.toSet()
            root.add("inbounds", JsonArray().apply { add(JsonObject().apply {
                addProperty("tag", "nyawire-in"); addProperty("listen", "127.0.0.1"); addProperty("port", port)
                addProperty("protocol", "socks")
                add("settings", JsonObject().apply { addProperty("auth", "noauth"); addProperty("udp", true); addProperty("ip", "127.0.0.1") })
                add("sniffing", JsonObject().apply { addProperty("enabled", true); add("destOverride", arrayOfStrings("http", "tls", "quic")); addProperty("routeOnly", true) })
            }) })
            socksCredentials?.let { (user, password) ->
                root.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("settings").apply {
                    addProperty("auth", "password")
                    add("accounts", JsonArray().apply { add(JsonObject().apply { addProperty("user", user); addProperty("pass", password) }) })
                }
            }
            if (nativeTun) root.getAsJsonArray("inbounds").add(JsonObject().apply {
                addProperty("tag", "nyawire-tun"); addProperty("protocol", "tun")
                add("settings", JsonObject().apply { addProperty("name", "nyawire"); addProperty("mtu", settings.mtu) })
                add("sniffing", root.getAsJsonArray("inbounds")[0].asJsonObject.get("sniffing").deepCopy())
            })
            root.obj("routing")?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { rule ->
                rule.takeIf { it.isJsonObject }?.asJsonObject?.get("inboundTag")?.takeIf { it.isJsonArray }?.asJsonArray?.let { tags ->
                    if (tags.any { it.isJsonPrimitive && it.asString in oldTags }) rule.asJsonObject.add("inboundTag", arrayOfStrings("nyawire-in"))
                    val mapped = rule.asJsonObject.getAsJsonArray("inboundTag")
                    if (nativeTun && mapped.any { it.asString == "nyawire-in" } && mapped.none { it.asString == "nyawire-tun" }) mapped.add("nyawire-tun")
                }
            }
            if (nativeTun && !includeSocks) root.getAsJsonArray("inbounds").remove(0)
            root.add("log", JsonObject().apply { addProperty("loglevel", settings.logLevel) })
            root.add("stats", JsonObject())
            root.remove("api")
            val policy = root.obj("policy") ?: JsonObject().also { root.add("policy", it) }
            val system = policy.obj("system") ?: JsonObject().also { policy.add("system", it) }
            system.addProperty("statsOutboundUplink", true); system.addProperty("statsOutboundDownlink", true)
            // The binding exposes outbound counters. Untagged outbounds otherwise have no counters.
            if (nativeTun || settings.connectionMode == com.gdhapp.client.domain.model.ConnectionMode.PROXY) {
                val used = outbounds.mapNotNull { it.asJsonObject.text("tag") }.toMutableSet()
                outbounds.forEachIndexed { index, outbound ->
                    if (outbound.asJsonObject.text("tag").isNullOrBlank()) {
                        var tag = "nyawire-out-$index"
                        while (tag in used) tag += "_"
                        used.add(tag); outbound.asJsonObject.addProperty("tag", tag)
                    }
                }
            }
        } else {
            root.add("inbounds", JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "mixed"); addProperty("tag", "nyawire-in")
                addProperty("listen", "127.0.0.1"); addProperty("listen_port", port)
                socksCredentials?.let { (user, password) -> add("users", JsonArray().apply {
                    add(JsonObject().apply { addProperty("username", user); addProperty("password", password) })
                }) }
            }) })
            root.add("log", JsonObject().apply {
                addProperty("level", if (settings.logLevel == "warning") "warn" else settings.logLevel)
                addProperty("timestamp", false)
            })
            root.obj("experimental")?.let { it.remove("clash_api"); it.remove("v2ray_api") }
            ensureSingBoxDns(root, settings)
        }
        ClientCoreOptions.apply(root, engine, if (forProbe) settings.copy(routingMode = com.gdhapp.client.domain.model.RoutingMode.GLOBAL) else settings, geo)
        if (shareProxy && settings.hotspotEnabled) {
            require(settings.hotspotUser.isNotBlank() && settings.hotspotPassword.length >= 8 && settings.hotspotPort in 1024..65535 && settings.hotspotPort != port) { AppText.text(R.string.hotspot_credentials_error) }
            root.getAsJsonArray("inbounds").add(JsonObject().apply {
                addProperty("tag", "nyawire-share")
                addProperty("listen", "0.0.0.0")
                if (engine == CoreEngine.XRAY) {
                    addProperty("port", settings.hotspotPort); addProperty("protocol", "socks")
                    add("settings", JsonObject().apply {
                        addProperty("auth", "password"); addProperty("udp", false)
                        add("accounts", JsonArray().apply { add(JsonObject().apply { addProperty("user", settings.hotspotUser); addProperty("pass", settings.hotspotPassword) }) })
                    })
                } else {
                    addProperty("listen_port", settings.hotspotPort); addProperty("type", "mixed")
                    add("users", JsonArray().apply { add(JsonObject().apply { addProperty("username", settings.hotspotUser); addProperty("password", settings.hotspotPassword) }) })
                }
            })
        }
        return PreparedCoreConfig(engine, root.toString())
    }

    /** The standalone Android core cannot use Go's /etc/resolv.conf fallback (localhost:53). */
    private fun ensureSingBoxDns(root: JsonObject, settings: ClientSettings) {
        val dns = root.obj("dns") ?: JsonObject().also { root.add("dns", it) }
        val servers = dns.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray
        if (servers != null && servers.size() > 0) return // Keep the imported profile's DNS policy.
        require(com.gdhapp.client.vpn.NetworkRoutes.isLiteralAddress(settings.dnsPrimary)) { AppText.text(R.string.primary_dns_must_be_an_ip_address) }
        val tag = "nyawire-bootstrap-dns"
        dns.add("servers", JsonArray().apply { add(JsonObject().apply {
            addProperty("type", "udp"); addProperty("tag", tag); addProperty("server", settings.dnsPrimary)
        }) })
        if (!dns.has("final")) dns.addProperty("final", tag)
        val route = root.obj("route") ?: JsonObject().also { root.add("route", it) }
        if (!route.has("default_domain_resolver")) route.addProperty("default_domain_resolver", tag)
    }

    private val convertibleRuleKeys = setOf("type", "outboundTag", "ruleTag", "inboundTag", "domain", "ip", "port", "network", "protocol")

    /**
     * Xray routing rules as sing-box rules. Rules on things NyaWire's single inbound cannot tell apart
     * (users, sources, balancers) are left out rather than guessed; inboundTag is ignored for the same reason.
     */
    private fun xrayRulesToSingBox(routing: JsonObject?, actions: Map<String, String>, outbounds: Set<String>, geo: GeoData?): JsonArray {
        val result = JsonArray()
        val rules = routing?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: return result
        var sniff = false
        for (element in rules) {
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (rule.keySet().any { it !in convertibleRuleKeys }) continue
            val tag = rule.text("outboundTag") ?: continue
            if (actions[tag] == null && tag !in outbounds) continue
            val conditions = JsonObject()
            rule.get("network")?.takeIf { it.isJsonPrimitive }?.asString?.split(',')?.map { it.trim() }?.filter { it in setOf("tcp", "udp") }
                ?.takeIf { it.isNotEmpty() }?.let { conditions.add("network", arrayOfStrings(*it.toTypedArray())) }
            rule.get("protocol")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }?.takeIf { it.isNotEmpty() }
                ?.let { conditions.add("protocol", arrayOfStrings(*it.toTypedArray())); sniff = true }
            rule.get("port")?.let { value ->
                val parts = (if (value.isJsonPrimitive) value.asString else "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val single = parts.mapNotNull { it.toIntOrNull() }
                val ranges = parts.filter { '-' in it }.map { it.replace('-', ':') }
                if (single.isNotEmpty()) conditions.add("port", JsonArray().apply { single.forEach(::add) })
                if (ranges.isNotEmpty()) conditions.add("port_range", arrayOfStrings(*ranges.toTypedArray()))
            }
            fun list(key: String) = rule.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asString }.orEmpty()
            // Categories missing from the bundled geodata cannot match anything; drop just those entries.
            val domains = list("domain").filter { !it.startsWith("geosite:") || ClientCoreOptions.geoData(geo).hasSite(it.removePrefix("geosite:")) }
            val ips = list("ip").filter { !it.startsWith("geoip:") || ClientCoreOptions.geoData(geo).hasIp(it.removePrefix("geoip:")) }
            if ((rule.has("domain") && domains.isEmpty()) || (rule.has("ip") && ips.isEmpty())) continue
            val matchers = listOfNotNull(ClientCoreOptions.singBoxDomains(domains, geo)?.also { sniff = true }) + ClientCoreOptions.singBoxIps(ips, geo)
            for (matcher in matchers.ifEmpty { listOf(JsonObject()) }) {
                conditions.entrySet().forEach { (key, value) -> matcher.add(key, value.deepCopy()) }
                when (actions[tag]) {
                    "blackhole" -> matcher.addProperty("action", "reject")
                    "dns" -> matcher.addProperty("action", "hijack-dns")
                    else -> { matcher.addProperty("action", "route"); matcher.addProperty("outbound", tag) }
                }
                result.add(matcher)
            }
        }
        // Domain and protocol conditions need the sniffed name or protocol first.
        if (!sniff) return result
        return JsonArray().apply { add(JsonObject().apply { addProperty("action", "sniff") }); result.forEach(::add) }
    }

    private fun xrayToSingBox(source: JsonObject): JsonObject {
        val protocol = source.text("protocol") ?: throw IllegalArgumentException(AppText.text(R.string.invalid_outbound))
        if (protocol == "freedom") return JsonObject().apply { addProperty("type", "direct"); addProperty("tag", source.text("tag") ?: "direct") }
        require(protocol !in setOf("blackhole", "dns", "loopback")) { AppText.text(R.string.this_profile_requires_xray) }
        val settings = source.obj("settings") ?: JsonObject()
        val server = sequenceOf("servers", "vnext").mapNotNull { settings.get(it)?.takeIf { v -> v.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf { v -> v.isJsonObject }?.asJsonObject }.firstOrNull() ?: settings
        val user = server.get("users")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: server
        val stream = source.obj("streamSettings") ?: JsonObject()
        val network = stream.text("network") ?: "tcp"
        val hysteria2 = protocol in setOf("hysteria2", "hy2") || (protocol == "hysteria" &&
            (settings.get("version")?.asInt == 2 || stream.obj("hysteriaSettings")?.get("version")?.asInt == 2))
        require(network !in setOf("xhttp", "splithttp")) { AppText.text(R.string.xhttp_requires_xray_select_auto_or_xray) }
        return JsonObject().apply {
            addProperty("type", if (hysteria2) "hysteria2" else protocol)
            addProperty("tag", source.text("tag") ?: "proxy")
            addProperty("server", server.text("address") ?: server.text("server") ?: server.text("host"))
            addProperty("server_port", server.get("port")?.asInt ?: server.get("server_port")?.asInt ?: 443)
            when (protocol) {
                "vless", "vmess" -> {
                    addProperty("uuid", user.text("id")); user.text("flow")?.let { addProperty("flow", it) }
                    if (protocol == "vmess") { addProperty("security", user.text("security") ?: "auto"); addProperty("alter_id", user.get("alterId")?.asInt ?: 0) }
                }
                "trojan", "shadowsocks", "hysteria2", "hy2", "tuic" -> {
                    addProperty("password", user.text("password") ?: server.text("password") ?: settings.text("password") ?: settings.text("auth"))
                    if (protocol == "shadowsocks") addProperty("method", server.text("method"))
                    if (protocol == "tuic") addProperty("uuid", user.text("id") ?: settings.text("uuid"))
                }
                "hysteria" -> {
                    val auth = stream.obj("hysteriaSettings")?.text("auth") ?: server.text("auth") ?: server.text("auth_str") ?: server.text("password") ?: settings.text("auth")
                    if (hysteria2) addProperty("password", auth) else {
                        addProperty("auth_str", auth)
                        addProperty("up_mbps", settings.get("up_mbps")?.asInt ?: settings.get("upMbps")?.asInt ?: 10)
                        addProperty("down_mbps", settings.get("down_mbps")?.asInt ?: settings.get("downMbps")?.asInt ?: 50)
                    }
                }
            }
            val security = stream.text("security")
            if (security in setOf("tls", "reality") || protocol in setOf("hysteria", "hysteria2", "hy2", "tuic")) {
                val tls = stream.obj(if (security == "reality") "realitySettings" else "tlsSettings") ?: source.obj("tls") ?: JsonObject()
                require(!tls.has("pinnedPeerCertSha256")) { AppText.text(R.string.this_profile_uses_certificate_pinning_and_requires) }
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    (tls.text("serverName") ?: tls.text("server_name"))?.let { addProperty("server_name", it) }
                    addProperty("insecure", tls.get("allowInsecure")?.asBoolean ?: tls.get("insecure")?.asBoolean ?: false)
                    tls.get("alpn")?.let { add("alpn", it.deepCopy()) }
                    // uTLS only shapes TCP handshakes; sing-box's QUIC clients reject a uTLS config outright.
                    if (protocol !in setOf("hysteria", "hysteria2", "hy2", "tuic"))
                        (tls.text("fingerprint")?.takeIf { it.isNotBlank() } ?: if (security == "reality") "chrome" else null)
                            ?.let { add("utls", JsonObject().apply { addProperty("enabled", true); addProperty("fingerprint", it) }) }
                    if (security == "reality") add("reality", JsonObject().apply {
                        addProperty("enabled", true); addProperty("public_key", tls.text("publicKey") ?: tls.text("password"))
                        addProperty("short_id", tls.text("shortId").orEmpty())
                    })
                })
            }
            if (hysteria2) stream.obj("finalmask")?.get("udp")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it.text("type") == "salamander" }?.let { mask ->
                    add("obfs", JsonObject().apply { addProperty("type", "salamander"); addProperty("password", mask.obj("settings")?.text("password")) })
                }
            if (network == "ws") add("transport", JsonObject().apply {
                addProperty("type", "ws"); val ws = stream.obj("wsSettings")
                addProperty("path", ws?.text("path") ?: "/"); ws?.get("headers")?.let { add("headers", it.deepCopy()) }
            })
            if (network == "grpc") add("transport", JsonObject().apply {
                addProperty("type", "grpc"); addProperty("service_name", stream.obj("grpcSettings")?.text("serviceName").orEmpty())
            })
        }
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
internal fun arrayOfStrings(vararg values: String) = JsonArray().apply { values.forEach(::add) }
