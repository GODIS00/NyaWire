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
    fun apply(root: JsonObject, engine: CoreEngine, settings: ClientSettings, geo: GeoData? = null) {
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
            var next = "nyawire-proxy"
            while (outbounds.any { it.asJsonObject.text("tag") == next }) next += "_"
            out.addProperty("tag", next); return next
        }
        fun addedTag(base: String): String { var value = base; while (outbounds.any { it.asJsonObject.text("tag") == value }) value += "_"; return value }
        if (settings.routingMode == RoutingMode.GLOBAL) {
            val proxyTag = tag(proxy)
            if (xray) (root.obj("routing") ?: JsonObject()).also { root.add("routing", it) }.add("rules", JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "field"); addProperty("network", "tcp,udp"); addProperty("outboundTag", proxyTag)
            }) })
            else (root.obj("route") ?: JsonObject()).also { root.add("route", it) }.apply { add("rules", JsonArray()); addProperty("final", proxyTag) }
        } else if (settings.routingMode == RoutingMode.RULES) {
            val proxyTag = tag(proxy)
            val plan = rulePlan(settings)
            if (xray) {
                val routing = (root.obj("routing") ?: JsonObject()).also { root.add("routing", it) }
                plan.domainStrategy?.let { routing.addProperty("domainStrategy", it) }
                val directTag = addedTag("nyawire-direct"); val blockTag = addedTag("nyawire-block")
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
                for (group in plan.groups) {
                    val target = when (group.target) { RouteTarget.BLOCK -> blockTag; RouteTarget.PROXY -> proxyTag; RouteTarget.DIRECT -> directTag }
                    addRule("domain", group.domains, target); addRule("ip", group.ips, target)
                }
                routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { rules.add(it.deepCopy()) }
                // An imported profile without GlobalProxy sends everything unmatched directly.
                if (plan.defaultDirect) rules.add(JsonObject().apply {
                    addProperty("type", "field"); addProperty("network", "tcp,udp"); addProperty("outboundTag", directTag)
                })
                routing.add("rules", rules)
            } else {
                val route = (root.obj("route") ?: JsonObject()).also { root.add("route", it) }
                val directTag = addedTag("nyawire-direct")
                outbounds.add(JsonObject().apply { addProperty("tag", directTag); addProperty("type", "direct") })
                val rules = JsonArray()
                if (plan.groups.any { it.domains.isNotEmpty() }) rules.add(JsonObject().apply { addProperty("action", "sniff"); addProperty("timeout", "300ms") })
                fun target(rule: JsonObject, target: RouteTarget) = rule.apply { when (target) {
                    RouteTarget.BLOCK -> addProperty("action", "reject")
                    RouteTarget.PROXY -> { addProperty("action", "route"); addProperty("outbound", proxyTag) }
                    RouteTarget.DIRECT -> { addProperty("action", "route"); addProperty("outbound", directTag) }
                } }
                var resolving = false
                for (group in plan.groups) {
                    singBoxDomains(group.domains, geo)?.let { rules.add(target(it, group.target)) }
                    val ipRules = singBoxIps(group.ips, geo)
                    // IP rules need the destination resolved; add the lookup once, just before the first of them.
                    if (ipRules.isNotEmpty() && !resolving) { rules.add(resolveRule(root, route)); resolving = true }
                    ipRules.forEach { rules.add(target(it, group.target)) }
                }
                route.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { rules.add(it.deepCopy()) }; route.add("rules", rules)
                if (plan.defaultDirect) route.addProperty("final", directTag)
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

    /** Client rule groups in match order. Entries use Xray syntax; sing-box gets them expanded. */
    private data class RuleGroup(val target: RouteTarget, val domains: List<String>, val ips: List<String>)
    private data class RulePlan(val groups: List<RuleGroup>, val defaultDirect: Boolean, val domainStrategy: String?)

    // v2fly categories for the presets; tld-ru covers .ru, .su and .рф.
    private val russiaSites = listOf("geosite:category-ru", "geosite:tld-ru")
    private const val ADS_SITES = "geosite:category-ads-all"

    private fun rulePlan(settings: ClientSettings): RulePlan {
        val imported = settings.importedRouting?.takeIf { it.enabled }
        fun custom(value: String) = domains(value).map { "domain:$it" }
        val sites = mapOf(
            RouteTarget.BLOCK to custom(settings.blockedDomains) + imported?.blockSites.orEmpty() + listOfNotNull(ADS_SITES.takeIf { settings.blockAds }),
            RouteTarget.PROXY to imported?.proxySites.orEmpty(),
            RouteTarget.DIRECT to custom(settings.directDomains) + imported?.directSites.orEmpty() + if (settings.routeRussiaDirect) russiaSites else emptyList(),
        )
        val addresses = mapOf(
            RouteTarget.BLOCK to ips(settings.blockedIps) + imported?.blockIps.orEmpty(),
            RouteTarget.PROXY to imported?.proxyIps.orEmpty(),
            RouteTarget.DIRECT to ips(settings.directIps) + imported?.directIps.orEmpty() + listOfNotNull("geoip:ru".takeIf { settings.routeRussiaDirect }),
        )
        val groups = (imported?.order ?: RouteTarget.entries).map { RuleGroup(it, sites.getValue(it).distinct(), addresses.getValue(it).distinct()) }
        val strategy = when {
            // Hand-written IP lists keep their original on-demand lookup.
            settings.directIps.isNotBlank() || settings.blockedIps.isNotBlank() -> "IPOnDemand"
            // An imported profile brings its own lookup strategy for its IP rules.
            imported != null && (imported.directIps + imported.proxyIps + imported.blockIps).isNotEmpty() -> imported.domainStrategy
            // Preset IP rules match the connection's own address, which tunnelled traffic always has. A lookup for
            // every unmatched name would add latency and trust the ISP's resolver, which may answer with a stub.
            else -> null
        }
        return RulePlan(groups, imported?.globalProxy == false, strategy)
    }

    internal fun geoData(geo: GeoData?) = geo ?: throw IllegalArgumentException(AppText.text(R.string.routing_data_unavailable))
    private fun strings(values: Collection<String>) = JsonArray().apply { values.forEach(::add) }

    /** One sing-box rule matching any of the domain entries, or null when there are none. */
    internal fun singBoxDomains(entries: List<String>, geo: GeoData?): JsonObject? {
        if (entries.isEmpty()) return null
        val full = linkedSetOf<String>(); val suffix = linkedSetOf<String>(); val keyword = linkedSetOf<String>(); val regex = linkedSetOf<String>()
        for (entry in entries) {
            val value = entry.substringAfter(':')
            when (entry.substringBefore(':', "")) {
                "geosite" -> geoData(geo).site(value).let { full += it.full; suffix += it.suffix; keyword += it.keyword; regex += it.regex }
                "domain" -> suffix += value
                "full" -> full += value
                "keyword" -> keyword += value
                "regexp" -> regex += value
                // Xray matches an unprefixed entry as a substring; other prefixes (ext: files) have no equivalent.
                else -> if (':' !in entry) keyword += entry
            }
        }
        return JsonObject().apply {
            listOf("domain" to full, "domain_suffix" to suffix, "domain_keyword" to keyword, "domain_regex" to regex)
                .forEach { (key, values) -> if (values.isNotEmpty()) add(key, strings(values)) }
        }
    }

    /** sing-box rules for the IP entries: address lists and private ranges, each also in an inverted (`geoip:!…`) form. */
    internal fun singBoxIps(entries: List<String>, geo: GeoData?): List<JsonObject> {
        val cidrs = mapOf(false to linkedSetOf<String>(), true to linkedSetOf())
        val private = mutableSetOf<Boolean>()
        for (entry in entries) {
            if (!entry.startsWith("geoip:")) { cidrs.getValue(false) += if ('/' in entry) entry else "$entry/${if (':' in entry) 128 else 32}"; continue }
            val tag = entry.removePrefix("geoip:")
            val inverted = tag.startsWith("!")
            if (tag.removePrefix("!") == "private") private += inverted else cidrs.getValue(inverted) += geoData(geo).ip(tag.removePrefix("!"))
        }
        return listOf(false, true).flatMap { inverted -> listOfNotNull(
            cidrs.getValue(inverted).takeIf { it.isNotEmpty() }?.let { JsonObject().apply { add("ip_cidr", strings(it)) } },
            JsonObject().apply { addProperty("ip_is_private", true) }.takeIf { inverted in private },
        ).onEach { if (inverted) it.addProperty("invert", true) } }
    }

    private fun resolveRule(root: JsonObject, route: JsonObject): JsonObject {
        val dns = root.obj("dns") ?: JsonObject().also { root.add("dns", it) }
        val servers = dns.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { dns.add("servers", it) }
        val existing = route.get("default_domain_resolver")?.takeIf { it.isJsonPrimitive }?.asString
            ?: dns.text("final") ?: servers.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.text("tag")
        val resolver = existing ?: run {
            var name = "nyawire-rule-dns"
            while (servers.any { it.isJsonObject && it.asJsonObject.text("tag") == name }) name += "_"
            servers.add(JsonObject().apply { addProperty("type", "local"); addProperty("tag", name) }); name
        }
        return JsonObject().apply { addProperty("action", "resolve"); addProperty("server", resolver) }
    }
}
