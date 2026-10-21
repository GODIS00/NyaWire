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
    fun build(raw: String, settings: ClientSettings, port: Int, nativeTun: Boolean = false, includeSocks: Boolean = true, socksCredentials: Pair<String, String>? = null): PreparedCoreConfig {
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
            root.add("outbounds", JsonArray().apply { outbounds.forEach { add(xrayToSingBox(it.asJsonObject)) } })
            require(!root.has("routing") || root.obj("routing")?.get("rules")?.asJsonArray?.size() in listOf(null, 0)) {
                AppText.text(R.string.this_profile_uses_xray_routing_rules_select)
            }
            root.remove("routing"); root.remove("dns"); root.remove("policy"); root.remove("stats"); root.remove("api")
        } else if (engine == CoreEngine.XRAY && !xrayFormat) {
            throw IllegalArgumentException(AppText.text(R.string.this_is_a_sing_box_profile_select))
        }
        if (engine == CoreEngine.XRAY) {
            val oldTags = root.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
                .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.text("tag") }.toSet()
            root.add("inbounds", JsonArray().apply { add(JsonObject().apply {
                addProperty("tag", "gdhapp-in"); addProperty("listen", "127.0.0.1"); addProperty("port", port)
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
                addProperty("tag", "gdhapp-tun"); addProperty("protocol", "tun")
                add("settings", JsonObject().apply { addProperty("name", "gdhapp"); addProperty("mtu", settings.mtu) })
                add("sniffing", root.getAsJsonArray("inbounds")[0].asJsonObject.get("sniffing").deepCopy())
            })
            root.obj("routing")?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { rule ->
                rule.takeIf { it.isJsonObject }?.asJsonObject?.get("inboundTag")?.takeIf { it.isJsonArray }?.asJsonArray?.let { tags ->
                    if (tags.any { it.isJsonPrimitive && it.asString in oldTags }) rule.asJsonObject.add("inboundTag", arrayOfStrings("gdhapp-in"))
                    val mapped = rule.asJsonObject.getAsJsonArray("inboundTag")
                    if (nativeTun && mapped.any { it.asString == "gdhapp-in" } && mapped.none { it.asString == "gdhapp-tun" }) mapped.add("gdhapp-tun")
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
                        var tag = "gdhapp-out-$index"
                        while (tag in used) tag += "_"
                        used.add(tag); outbound.asJsonObject.addProperty("tag", tag)
                    }
                }
            }
        } else {
            root.add("inbounds", JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "mixed"); addProperty("tag", "gdhapp-in")
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
        }
        ClientCoreOptions.apply(root, engine, settings)
        return PreparedCoreConfig(engine, root.toString())
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
                    tls.text("fingerprint")?.let { add("utls", JsonObject().apply { addProperty("enabled", true); addProperty("fingerprint", it) }) }
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
