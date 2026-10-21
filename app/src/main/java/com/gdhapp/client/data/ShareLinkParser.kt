package com.gdhapp.client.data

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Converts standard share links to complete, native-core-compatible profiles. */
object ShareLinkParser {
    private val schemes = setOf("vless", "vmess", "trojan", "ss", "hysteria", "hysteria2", "hy2", "tuic")
    fun isLink(value: String) = value.substringBefore("://").lowercase() in schemes

    fun parseLines(value: String): String {
        val links = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        // Providers may insert country/section headings between links. Only URI lines are
        // profiles; an explicit unsupported URI must still produce a useful error.
        val uriLines = links.filter { Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(it) }
        require(uriLines.isNotEmpty() && uriLines.size <= 500) { AppText.text(R.string.a_subscription_must_contain_between_1_and) }
        return JsonArray().apply { uriLines.forEach { add(parse(it)) } }.toString()
    }

    fun parse(value: String): JsonObject {
        val scheme = value.substringBefore("://").lowercase()
        require(scheme in schemes) { AppText.text(R.string.unsupported_link_use_vless_vmess_trojan_ss) }
        if (scheme == "vmess") return vmess(value)
        var normalized = value
        if (scheme == "ss" && '@' !in value.substringBefore('#')) {
            val body = value.substringAfter("://").substringBefore('#')
            normalized = "ss://${decode64(body)}" + value.substringAfter('#', "").let { if (it.isEmpty()) "" else "#${it}" }
        }
        // The fragment is display text. Public lists often leave emoji, spaces and a
        // second '#' unescaped there; do not feed that text into strict URI parsing.
        val fragment = normalized.substringAfter('#', "")
        val uri = runCatching { URI(normalized.substringBefore('#')) }.getOrElse { throw IllegalArgumentException(AppText.text(R.string.invalid_server_link)) }
        val host = uri.host?.removeSurrounding("[", "]") ?: throw IllegalArgumentException(AppText.text(R.string.server_address_is_missing_from_the_link))
        val port = if (uri.port > 0) uri.port else 443
        require(port in 1..65535) { AppText.text(R.string.server_port_must_be_between_1_and) }
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) decode(pair[0]) to decode(pair[1]) else null
        }.toMap()
        val name = fragment.takeIf { it.isNotBlank() }?.let { runCatching { decode(it) }.getOrDefault(it) } ?: host
        val user = uri.rawUserInfo.orEmpty().let(::decode)
        val outbound = when (scheme) {
            "vless", "trojan" -> {
                require(user.isNotBlank()) { AppText.text(R.string.authentication_details_are_missing_from_the_link) }
                JsonObject().apply {
                    addProperty("protocol", scheme); addProperty("tag", "proxy")
                    add("settings", JsonObject().apply {
                        if (scheme == "vless") add("vnext", JsonArray().apply { add(JsonObject().apply {
                            addProperty("address", host); addProperty("port", port)
                            add("users", JsonArray().apply { add(JsonObject().apply {
                                addProperty("id", user); addProperty("encryption", query["encryption"] ?: "none")
                                query["flow"]?.takeIf { it.isNotBlank() }?.let { addProperty("flow", it) }
                            }) })
                        }) }) else add("servers", JsonArray().apply { add(JsonObject().apply {
                            addProperty("address", host); addProperty("port", port); addProperty("password", user)
                        }) })
                    })
                    add("streamSettings", xrayStream(query, if (scheme == "trojan") "tls" else "none"))
                }
            }
            "ss" -> {
                val auth = if (':' in user) user else decode64(user)
                require(':' in auth) { AppText.text(R.string.invalid_shadowsocks_credentials) }
                require(query["plugin"].isNullOrBlank()) { AppText.text(R.string.shadowsocks_plugins_are_not_supported_yet_import) }
                JsonObject().apply {
                    addProperty("protocol", "shadowsocks"); addProperty("tag", "proxy")
                    add("settings", JsonObject().apply { add("servers", JsonArray().apply { add(JsonObject().apply {
                        addProperty("address", host); addProperty("port", port)
                        addProperty("method", auth.substringBefore(':')); addProperty("password", auth.substringAfter(':'))
                    }) }) })
                }
            }
            else -> JsonObject().apply {
                addProperty("type", when (scheme) { "hy2" -> "hysteria2"; else -> scheme })
                addProperty("tag", "proxy"); addProperty("server", host); addProperty("server_port", port)
                when (scheme) {
                    "hysteria2", "hy2" -> {
                        addProperty("password", user.ifBlank { query["auth"].orEmpty() })
                        query["obfs"]?.takeIf { it == "salamander" }?.let {
                            add("obfs", JsonObject().apply { addProperty("type", it); addProperty("password", query["obfs-password"].orEmpty()) })
                        }
                    }
                    "hysteria" -> {
                        addProperty("auth_str", query["auth"] ?: user)
                        addProperty("up_mbps", query["upmbps"]?.toIntOrNull() ?: 10)
                        addProperty("down_mbps", query["downmbps"]?.toIntOrNull() ?: 50)
                    }
                    "tuic" -> {
                        require(':' in user) { AppText.text(R.string.tuic_requires_a_uuid_and_password) }
                        addProperty("uuid", user.substringBefore(':')); addProperty("password", user.substringAfter(':'))
                        addProperty("congestion_control", query["congestion_control"] ?: "bbr")
                    }
                }
                add("tls", JsonObject().apply {
                    addProperty("enabled", true); addProperty("server_name", query["sni"] ?: query["peer"] ?: host)
                    addProperty("insecure", query["insecure"] == "1" || query["allowInsecure"] == "1")
                    query["alpn"]?.let { add("alpn", JsonArray().apply { it.split(',').forEach(::add) }) }
                })
            }
        }
        return JsonObject().apply { addProperty("remarks", name); add("outbounds", JsonArray().apply { add(outbound) }) }
    }

    private fun vmess(value: String): JsonObject {
        val data = runCatching { JsonParser.parseString(decode64(value.substringAfter("://").substringBefore('#'))).asJsonObject }
            .getOrElse { throw IllegalArgumentException(AppText.text(R.string.invalid_vmess_link)) }
        fun text(key: String) = data.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val port = text("port").toIntOrNull() ?: 443
        require(text("add").isNotBlank() && text("id").isNotBlank() && port in 1..65535) { AppText.text(R.string.vmess_invalid_address_port_or_uuid) }
        val query = mapOf("type" to text("net").ifBlank { "tcp" }, "security" to text("tls").ifBlank { "none" },
            "host" to text("host"), "path" to text("path"), "sni" to text("sni"), "fp" to text("fp"), "alpn" to text("alpn"))
        return JsonObject().apply {
            addProperty("remarks", text("ps").ifBlank { text("add") })
            add("outbounds", JsonArray().apply { add(JsonObject().apply {
                addProperty("protocol", "vmess"); addProperty("tag", "proxy")
                add("settings", JsonObject().apply { add("vnext", JsonArray().apply { add(JsonObject().apply {
                    addProperty("address", text("add")); addProperty("port", port)
                    add("users", JsonArray().apply { add(JsonObject().apply {
                        addProperty("id", text("id")); addProperty("alterId", text("aid").toIntOrNull() ?: 0)
                        addProperty("security", text("scy").ifBlank { "auto" })
                    }) })
                }) }) })
                add("streamSettings", xrayStream(query, "none"))
            }) })
        }
    }

    private fun xrayStream(query: Map<String, String>, defaultSecurity: String): JsonObject = JsonObject().apply {
        val network = query["type"].orEmpty().ifBlank { "tcp" }.let { if (it == "h2") "http" else it }
        val security = query["security"].orEmpty().ifBlank { defaultSecurity }
        addProperty("network", network); addProperty("security", security)
        if (security in setOf("tls", "reality")) add(if (security == "tls") "tlsSettings" else "realitySettings", JsonObject().apply {
            query["sni"]?.ifBlank { null }?.let { addProperty("serverName", it) }
            query["fp"]?.ifBlank { null }?.let { addProperty("fingerprint", it) }
            if (security == "reality") {
                addProperty("fingerprint", query["fp"].orEmpty().ifBlank { "chrome" })
                addProperty("publicKey", query["pbk"].orEmpty()); addProperty("shortId", query["sid"].orEmpty())
                query["spx"]?.let { addProperty("spiderX", it) }
            } else {
                addProperty("allowInsecure", query["allowInsecure"] == "1" || query["insecure"] == "1")
                query["alpn"]?.takeIf { it.isNotBlank() }?.let { add("alpn", JsonArray().apply { it.split(',').forEach(::add) }) }
            }
        })
        val path = query["path"].orEmpty().ifBlank { "/" }
        val host = query["host"].orEmpty()
        when (network) {
            "ws" -> add("wsSettings", JsonObject().apply {
                addProperty("path", path)
                if (host.isNotBlank()) add("headers", JsonObject().apply { addProperty("Host", host) })
            })
            "grpc" -> add("grpcSettings", JsonObject().apply {
                addProperty("serviceName", query["serviceName"] ?: query["path"].orEmpty())
                addProperty("multiMode", query["mode"] == "multi")
            })
            "xhttp", "splithttp" -> add("xhttpSettings", JsonObject().apply {
                addProperty("path", path); if (host.isNotBlank()) addProperty("host", host)
                addProperty("mode", query["mode"].orEmpty().ifBlank { "auto" })
                query["extra"]?.let { extra -> runCatching { JsonParser.parseString(extra).asJsonObject }.getOrNull()?.entrySet()?.forEach { add(it.key, it.value) } }
            })
            "http" -> add("httpSettings", JsonObject().apply { addProperty("path", path); if (host.isNotBlank()) add("host", JsonArray().apply { add(host) }) })
        }
    }

    fun decode64(value: String): String {
        val raw = value.filterNot { it.isWhitespace() || it == '\uFEFF' }.replace('-', '+').replace('_', '/')
        return runCatching { Base64.getDecoder().decode(raw).toString(Charsets.UTF_8) }
            .getOrElse { throw IllegalArgumentException(AppText.text(R.string.invalid_base64_subscription_data)) }
    }
    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}
