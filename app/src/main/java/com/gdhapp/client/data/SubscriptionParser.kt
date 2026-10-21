package com.gdhapp.client.data

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.domain.model.SubscriptionGroup
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Complete JSON profiles, arrays, provider wrappers, Base64 and standard share links. */
class SubscriptionParser {
    fun parse(text: String, sourceUrl: String? = null): SubscriptionGroup {
        require(text.toByteArray().size <= MAX_BYTES) { AppText.text(R.string.the_subscription_exceeds_2_mb) }
        val input = text.trim().removePrefix("\uFEFF").trim()
        require(input.isNotEmpty()) { AppText.text(R.string.the_subscription_is_empty) }
        require(!input.startsWith("<")) { AppText.text(R.string.subscription_html_response) }
        // Link lists can contain comment metadata and section headings before the first URI.
        val lines = input.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith('#') }.joinToString("\n")
        var decoded = if (input.startsWith("{") || input.startsWith("[")) input
            else if (containsLinks(lines)) input else ShareLinkParser.decode64(lines.removePrefix("base64:"))
        decoded = decoded.trim().removePrefix("\uFEFF").trim()
        require(!decoded.startsWith("<")) { AppText.text(R.string.subscription_html_response) }
        val metadata = commentMetadata(decoded) + commentMetadata(input)
        if (!decoded.startsWith("{") && !decoded.startsWith("[") && containsLinks(decoded)) decoded = ShareLinkParser.parseLines(decoded)
        val root = runCatching { JsonParser.parseString(decoded) }
            .getOrElse { throw IllegalArgumentException(AppText.text(R.string.could_not_read_the_json_subscription)) }
        val wrapper = root.takeIf { it.isJsonObject }?.asJsonObject
        val entries = when {
            root.isJsonArray -> root.asJsonArray.toList()
            wrapper?.get("servers")?.isJsonArray == true -> wrapper.getAsJsonArray("servers").toList()
            wrapper?.get("configs")?.isJsonArray == true -> wrapper.getAsJsonArray("configs").toList()
            wrapper != null -> listOf(root)
            else -> error(AppText.text(R.string.the_subscription_must_contain_a_json_object))
        }
        require(entries.isNotEmpty() && entries.size <= 500) { AppText.text(R.string.a_subscription_must_contain_between_1_and) }
        val nodes = entries.mapIndexed { index, entry -> parseNode(entry, index) }
        return SubscriptionGroup(
            id = UUID.randomUUID().toString(),
            title = (wrapper?.string("title") ?: metadata["profile-title"])?.take(100)
                ?: AppText.text(if (sourceUrl == null) R.string.server_list else R.string.new_subscription),
            countryCode = wrapper?.string("countryCode") ?: "UN",
            lastUpdated = LocalDateTime.now().format(DATE_FORMAT),
            announcementText = (wrapper?.string("announcementText") ?: metadata["announce"])?.take(2000).orEmpty(),
            supportUrl = safeSupportUrl(wrapper?.string("supportUrl")) ?: safeSupportUrl(metadata["support-url"]),
            autoUpdateHours = metadata["profile-update-interval"]?.toIntOrNull()?.coerceIn(1, 168) ?: 1,
            servers = nodes,
            sourceUrl = sourceUrl,
        )
    }

    private fun containsLinks(text: String): Boolean = text.lineSequence().any {
        val line = it.trim()
        !line.startsWith('#') && ShareLinkParser.isLink(line)
    }

    private fun commentMetadata(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith('#')) return@mapNotNull null
        val pair = trimmed.removePrefix("#").split(':', limit = 2)
        if (pair.size != 2) return@mapNotNull null
        val key = pair[0].trim().lowercase(Locale.ROOT)
        if (key !in setOf("profile-title", "announce", "support-url", "profile-update-interval")) return@mapNotNull null
        val value = pair[1].trim().let { raw ->
            if (raw.startsWith("base64:", true)) runCatching { ShareLinkParser.decode64(raw.substringAfter(':').trim()) }.getOrNull()
            else raw
        }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        key to value
    }.toMap()

    private fun parseNode(element: JsonElement, index: Int): ServerNode {
        require(element.isJsonObject) { AppText.text(R.string.server_expected_a_json_object, index + 1) }
        val item = element.asJsonObject
        val configElement = item.get("config") ?: item.get("rawConfigJson") ?: item
        val config = if (configElement.isJsonPrimitive && configElement.asJsonPrimitive.isString) {
            runCatching { JsonParser.parseString(configElement.asString).asJsonObject }
                .getOrElse { throw IllegalArgumentException(AppText.text(R.string.server_invalid_json_configuration, index + 1)) }
        } else {
            require(configElement.isJsonObject) { AppText.text(R.string.the_configuration_must_be_a_json_object) }
            configElement.asJsonObject
        }
        if (!config.has("outbounds") && (config.has("protocol") || config.has("type"))) {
            val outboundOnly = config.deepCopy()
            config.entrySet().map { it.key }.forEach { config.remove(it) }
            config.add("outbounds", com.google.gson.JsonArray().apply { add(outboundOnly) })
        }
        require(config.get("outbounds")?.isJsonArray == true) { AppText.text(R.string.the_configuration_must_include_an_outbounds_array) }
        val outbound = config.getAsJsonArray("outbounds")
            .filter { it.isJsonObject }.map { it.asJsonObject }
            .firstOrNull { (it.string("protocol") ?: it.string("type"))?.let { protocol -> protocol.lowercase() !in NON_PROXY } == true }
            ?: error(AppText.text(R.string.no_proxy_server_found_in_the_configuration))
        val endpoint = endpoint(outbound)
        val raw = config.toString()
        val protocol = (outbound.string("protocol") ?: outbound.string("type") ?: "PROXY").uppercase(Locale.ROOT)
        val stream = outbound.get("streamSettings")?.takeIf { it.isJsonObject }?.asJsonObject
        val tls = outbound.get("tls")?.takeIf { it.isJsonObject }?.asJsonObject
        val reality = tls?.get("reality")?.takeIf { it.isJsonObject }?.asJsonObject
        val transport = stream?.string("network")
            ?: outbound.get("transport")?.takeIf { it.isJsonObject }?.asJsonObject?.string("type")
            ?: if (protocol.startsWith("HYSTERIA")) "HYSTERIA" else "TCP"
        val security = stream?.string("security")
            ?: if (reality?.get("enabled")?.asBoolean == true) "REALITY" else if (tls?.get("enabled")?.asBoolean == true) "TLS" else "NONE"
        val name = (item.string("name") ?: config.string("remarks") ?: config.string("tag") ?: AppText.text(R.string.server, index + 1)).take(200)
        val sni = sequenceOf("realitySettings", "tlsSettings").mapNotNull {
            stream?.get(it)?.takeIf { it.isJsonObject }?.asJsonObject?.string("serverName")
        }.firstOrNull() ?: tls?.string("server_name")
        return ServerNode(
            // Include the ordinal: identical configs in the same subscription must have distinct keys.
            id = UUID.nameUUIDFromBytes("${index}:${raw}".toByteArray(StandardCharsets.UTF_8)).toString(),
            name = name,
            flagResOrCode = item.string("flagResOrCode")?.takeUnless { it == "UN" }
                ?: item.string("countryCode") ?: inferCountry(name),
            protocolSummary = "${protocol} / ${transport.uppercase(Locale.ROOT)} / ${security.uppercase(Locale.ROOT)} | JSON",
            rawConfigJson = raw,
            host = endpoint.first,
            port = endpoint.second,
            serverName = sni,
        )
    }

    private fun endpoint(outbound: JsonObject): Pair<String?, Int?> {
        val settings = outbound.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject
        val server = sequenceOf("vnext", "servers").mapNotNull { key ->
            settings?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        }.firstOrNull() ?: settings?.takeIf { it.has("address") || it.has("server") || it.has("host") } ?: outbound
        var host = server.string("address") ?: server.string("server") ?: server.string("host")
        var port = runCatching { (server.get("port") ?: server.get("server_port"))?.asInt }.getOrNull()
            ?.takeIf { it in 1..65535 }
        val rawHost = host
        if (port == null && rawHost != null && rawHost.count { it == ':' } == 1) {
            rawHost.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }?.let { port = it; host = rawHost.substringBeforeLast(':') }
        }
        if (host == null) server.get("server")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
            host = it.string("address") ?: it.string("host"); port = it.get("port")?.asInt
        }
        return host to port
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        private val NON_PROXY = setOf("freedom", "blackhole", "direct", "block", "dns", "selector", "urltest")
        fun inferCountry(name: String): String {
            val points = name.codePoints().toArray()
            (0 until (points.size - 1)).firstOrNull { points[it] in 0x1F1E6..0x1F1FF && points[it + 1] in 0x1F1E6..0x1F1FF }?.let {
                return "${(points[it] - 0x1F1E6 + 65).toChar()}${(points[it + 1] - 0x1F1E6 + 65).toChar()}"
            }
            return when {
                Regex("(?i)нидерланд|netherland|\\bNL\\b").containsMatchIn(name) -> "NL"
                Regex("(?i)эстон|estonia|\\bEE\\b").containsMatchIn(name) -> "EE"
                Regex("(?i)польш|poland|\\bPL\\b").containsMatchIn(name) -> "PL"
                Regex("(?i)герман|germany|\\bDE\\b").containsMatchIn(name) -> "DE"
                Regex("(?i)финлянд|finland|\\bFI\\b").containsMatchIn(name) -> "FI"
                Regex("(?i)франц|france|\\bFR\\b").containsMatchIn(name) -> "FR"
                else -> "UN"
            }
        }
        fun safeSupportUrl(value: String?): String? = value?.takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("tg://resolve?", ignoreCase = true)
        }
    }
}

internal fun JsonObject.string(key: String): String? = get(key)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
