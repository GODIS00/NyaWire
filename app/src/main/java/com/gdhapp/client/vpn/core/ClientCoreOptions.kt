package com.gdhapp.client.vpn.core

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.domain.model.*
import com.google.gson.*
import java.net.IDN

/** Applies explicit client overrides to a private copy, preserving the imported profile. */
object ClientCoreOptions {
    fun validRange(value: String, min: Int, max: Int): Boolean {
        val pieces = value.split('-')
        if (pieces.size !in 1..2) return false
        val a = pieces[0].toIntOrNull() ?: return false
        val b = pieces.last().toIntOrNull() ?: return false
        return a in min..max && b in a..max
    }
    fun lines(value: String) = value.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.distinct()
    fun domains(value: String): List<String> = lines(value).map { raw ->
        val name = IDN.toASCII(raw.removePrefix("*.").removePrefix(".")).lowercase()
        require(name.length <= 253 && name.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) { AppText.text(R.string.invalid_route_domain) }
        name
    }
    fun ips(value: String): List<String> = lines(value).onEach { raw ->
        val parts = raw.split('/')
        val host = parts[0]
        val ipv6 = ':' in host && host.matches(Regex("[0-9a-fA-F:]+")) && runCatching { java.net.InetAddress.getByName(host) is java.net.Inet6Address }.getOrDefault(false)
        val ipv4 = host.split('.').let { it.size == 4 && it.all { p -> p.toIntOrNull() in 0..255 && p.all(Char::isDigit) } }
        require((ipv4 || ipv6) && parts.size in 1..2 && (parts.size == 1 || parts[1].toIntOrNull() in 0..if (ipv6) 128 else 32)) { AppText.text(R.string.invalid_route_ip) }
    }
    fun apply(root: JsonObject, engine: CoreEngine, settings: ClientSettings) {
        val outbounds = root.getAsJsonArray("outbounds")
        val xray = engine == CoreEngine.XRAY
        val proxy = outbounds.first { (it.asJsonObject.text(if (xray) "protocol" else "type")) !in setOf("freedom", "direct", "blackhole", "block", "dns", "selector", "urltest") }.asJsonObject
        if (settings.ipMode != IpMode.AUTO) {
            if (xray) {
                val stream = proxy.obj("streamSettings") ?: JsonObject().also { proxy.add("streamSettings", it) }
                val socket = stream.obj("sockopt") ?: JsonObject().also { stream.add("sockopt", it) }
                socket.addProperty("domainStrategy", if (settings.ipMode == IpMode.IPV4) "ForceIPv4" else "ForceIPv6")
            } else {
                val route = root.obj("route") ?: JsonObject().also { root.add("route", it) }
                val resolver = route.text("default_domain_resolver") ?: "nyawire-ip-dns"
                if (resolver == "nyawire-ip-dns") {
                    val dns = root.obj("dns") ?: JsonObject().also { root.add("dns", it) }
                    val servers = dns.get("servers")?.asJsonArray ?: JsonArray().also { dns.add("servers", it) }
                    if (servers.none { it.asJsonObject.text("tag") == resolver }) servers.add(JsonObject().apply { addProperty("tag", resolver); addProperty("type", "local") })
                }
                proxy.add("domain_resolver", JsonObject().apply { addProperty("server", resolver); addProperty("strategy", if (settings.ipMode == IpMode.IPV4) "ipv4_only" else "ipv6_only") })
            }
        }
        if (settings.muxEnabled && xray) {
            val protocol = proxy.text("protocol")
            val vision = proxy.toString().contains("xtls-rprx-vision")
            val network = proxy.obj("streamSettings")?.text("network") ?: "tcp"
            // QUIC, Vision, gRPC and XHTTP already manage their streams differently.
            if (protocol in setOf("vless", "vmess", "trojan", "shadowsocks") && !vision && network in setOf("tcp", "raw", "ws", "httpupgrade")) {
                proxy.add("mux", JsonObject().apply { addProperty("enabled", true); addProperty("concurrency", 8); addProperty("xudpConcurrency", 16) })
            }
        }
        fun tag(out: JsonObject): String {
            out.text("tag")?.takeIf { it.isNotBlank() }?.let { return it }
            var next = "gdhapp-proxy"
            while (outbounds.any { it.asJsonObject.text("tag") == next }) next += "_"
            out.addProperty("tag", next); return next
        }
        fun addedTag(base: String): String { var value = base; while (outbounds.any { it.asJsonObject.text("tag") == value }) value += "_"; return value }
        if (settings.routingMode != RoutingMode.PROFILE) {
            val proxyTag = tag(proxy)
            if (xray) {
                val routing = (root.obj("routing") ?: JsonObject()).also { root.add("routing", it) }
                if (settings.routingMode == RoutingMode.GLOBAL) routing.add("rules", JsonArray().apply { add(JsonObject().apply {
                    addProperty("type", "field"); addProperty("network", "tcp,udp"); addProperty("outboundTag", proxyTag)
                }) }) else {
                    if (settings.directIps.isNotBlank() || settings.blockedIps.isNotBlank()) routing.addProperty("domainStrategy", "IPOnDemand")
                    val directTag = addedTag("gdhapp-direct"); val blockTag = addedTag("gdhapp-block")
                    outbounds.add(JsonObject().apply {
                        addProperty("tag", directTag); addProperty("protocol", "freedom")
                        // Only explicit direct rules reach this outbound, including local hostnames.
                        val allowed = listOf("0.0.0.0/0", "::/0")
                        if (allowed.isNotEmpty()) add("settings", JsonObject().apply { add("finalRules", JsonArray().apply { add(JsonObject().apply {
                            addProperty("action", "allow"); add("ip", JsonArray().apply { allowed.forEach(::add) })
                        }) }) })
                    })
                    outbounds.add(JsonObject().apply { addProperty("tag", blockTag); addProperty("protocol", "blackhole") })
                    val rules = JsonArray()
                    fun addRule(key: String, values: List<String>, target: String) { if (values.isNotEmpty()) rules.add(JsonObject().apply {
                        addProperty("type", "field"); add(key, JsonArray().apply { values.forEach(::add) }); addProperty("outboundTag", target)
                    }) }
                    addRule("domain", domains(settings.blockedDomains).map { "domain:$it" }, blockTag)
                    addRule("ip", ips(settings.blockedIps), blockTag)
                    addRule("domain", domains(settings.directDomains).map { "domain:$it" }, directTag)
                    addRule("ip", ips(settings.directIps), directTag)
                    routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { rules.add(it.deepCopy()) }
                    routing.add("rules", rules)
                }
            } else {
                val route = (root.obj("route") ?: JsonObject()).also { root.add("route", it) }
                if (settings.routingMode == RoutingMode.GLOBAL) { route.add("rules", JsonArray()); route.addProperty("final", proxyTag) }
                else {
                    val directTag = addedTag("gdhapp-direct")
                    outbounds.add(JsonObject().apply { addProperty("tag", directTag); addProperty("type", "direct") })
                    val rules = JsonArray()
                    if (settings.directDomains.isNotBlank() || settings.blockedDomains.isNotBlank()) rules.add(JsonObject().apply { addProperty("action", "sniff"); addProperty("timeout", "300ms") })
                    fun addRule(key: String, values: List<String>, blocked: Boolean) { if (values.isNotEmpty()) rules.add(JsonObject().apply {
                        add(key, JsonArray().apply { values.forEach(::add) }); addProperty("action", if (blocked) "reject" else "route")
                        if (!blocked) addProperty("outbound", directTag)
                    }) }
                    addRule("domain_suffix", domains(settings.blockedDomains), true)
                    if (settings.directIps.isNotBlank() || settings.blockedIps.isNotBlank()) {
                        val dns = root.obj("dns") ?: JsonObject().also { root.add("dns", it) }
                        val servers = dns.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { dns.add("servers", it) }
                        val existing = route.get("default_domain_resolver")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: dns.text("final") ?: servers.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.text("tag")
                        val resolver = existing ?: run {
                            var name = "gdhapp-rule-dns"
                            while (servers.any { it.isJsonObject && it.asJsonObject.text("tag") == name }) name += "_"
                            servers.add(JsonObject().apply { addProperty("type", "local"); addProperty("tag", name) }); name
                        }
                        rules.add(JsonObject().apply { addProperty("action", "resolve"); addProperty("server", resolver) })
                    }
                    addRule("ip_cidr", ips(settings.blockedIps).map { if ('/' in it) it else "$it/${if (':' in it) 128 else 32}" }, true)
                    addRule("domain_suffix", domains(settings.directDomains), false); addRule("ip_cidr", ips(settings.directIps).map { if ('/' in it) it else "$it/${if (':' in it) 128 else 32}" }, false)
                    route.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { rules.add(it.deepCopy()) }; route.add("rules", rules)
                }
            }
        }
        if (settings.fragmentEnabled) {
            require(xray) { AppText.text(R.string.fragment_xray_only) }
            val stream = proxy.obj("streamSettings") ?: JsonObject()
            require((stream.text("network") ?: "tcp") in setOf("tcp", "raw", "ws", "grpc", "xhttp", "splithttp") && stream.text("security") in setOf("tls", "reality")) { AppText.text(R.string.fragment_tls_only) }
            val socket = stream.obj("sockopt") ?: JsonObject()
            val masks = stream.obj("finalmask") ?: JsonObject()
            require(socket.text("dialerProxy").isNullOrBlank() && proxy.obj("proxySettings") == null &&
                (masks.get("tcp")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) == 0) { AppText.text(R.string.fragment_chain_conflict) }
            require(validRange(settings.fragmentLength, 1, 4096) && validRange(settings.fragmentInterval, 0, 1000)) { AppText.text(R.string.invalid_fragment_range) }
            // Native transport masking preserves the raw connection that Vision needs for splice.
            // A freedom/dialerProxy chain can corrupt Vision's switch to direct TLS copying.
            masks.add("tcp", JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "fragment")
                add("settings", JsonObject().apply {
                    addProperty("packets", "tlshello"); addProperty("length", settings.fragmentLength); addProperty("delay", settings.fragmentInterval)
                })
            }) })
            stream.add("finalmask", masks); proxy.add("streamSettings", stream)
        }
    }
}
