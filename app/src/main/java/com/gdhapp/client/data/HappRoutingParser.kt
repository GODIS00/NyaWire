package com.gdhapp.client.data

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.domain.model.ImportedRouting
import com.gdhapp.client.domain.model.RouteTarget
import com.gdhapp.client.vpn.core.ClientCoreOptions
import com.gdhapp.client.vpn.core.GeoData
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.IDN
import java.util.Base64

/** Reads Happ routing profiles: `happ://routing/add/<base64>`, `happ://routing/onadd/<base64>`, base64 or raw JSON. */
object HappRoutingParser {
    private const val MAX_RULES = 5000
    private const val MAX_TEXT = 1_000_000
    private val strategies = setOf("AsIs", "IPIfNonMatch", "IPOnDemand")
    private val categoryName = Regex("[a-z0-9!_.-]+(@[a-z0-9!_.-]+)?")

    fun isRoutingLink(text: String) = text.trim().startsWith("happ://routing/", ignoreCase = true)

    fun parse(text: String): ImportedRouting {
        val input = text.trim()
        require(input.length <= MAX_TEXT) { AppText.text(R.string.happ_routing_invalid) }
        val payload = if (isRoutingLink(input)) {
            // happ://routing/<action>/<data>; the data may be percent-encoded by messengers.
            percentDecode(input.substringAfter("://").substringAfter('/').substringAfter('/', "").substringBefore('#').substringBefore('?'))
        } else input
        val json = if (payload.startsWith("{")) payload else decodeBase64(payload)
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException(AppText.text(R.string.happ_routing_invalid))
        // Happ capitalises keys ("DirectSites"); accept any casing.
        val fields = root.entrySet().associate { (key, value) -> key.lowercase() to value }
        val skipped = mutableListOf<String>()
        fun list(key: String): List<String> = when (val value = fields[key]) {
            null -> emptyList()
            else -> if (value.isJsonArray) value.asJsonArray.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asString }
                else if (value.isJsonPrimitive) value.asString.split(',') else emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        fun rules(key: String, normalize: (String) -> String?) =
            list(key).mapNotNull { raw -> normalize(raw) ?: run { skipped.add(raw); null } }.capped(skipped)
        val routing = ImportedRouting(
            name = fields["name"]?.text()?.trim()?.take(64)?.ifBlank { null } ?: "Happ",
            globalProxy = fields["globalproxy"]?.text()?.lowercase() != "false",
            order = order(fields["routeorder"]?.text()),
            domainStrategy = fields["domainstrategy"]?.text()?.let { value -> strategies.firstOrNull { it.equals(value, true) } } ?: "IPIfNonMatch",
            directSites = rules("directsites", ::site), directIps = rules("directip", ::ip),
            proxySites = rules("proxysites", ::site), proxyIps = rules("proxyip", ::ip),
            blockSites = rules("blocksites", ::site), blockIps = rules("blockip", ::ip),
            skipped = skipped,
        )
        require(routing.directCount + routing.proxyCount + routing.blockCount > 0 || !routing.globalProxy) {
            AppText.text(R.string.happ_routing_empty)
        }
        return routing
    }

    /** Drops categories the bundled geodata lacks: Xray refuses to start on an unknown `geosite:`/`geoip:` tag. */
    fun available(routing: ImportedRouting, geo: GeoData): ImportedRouting {
        val missing = mutableListOf<String>()
        fun sites(values: List<String>) = values.filter { value ->
            val tag = value.takeIf { it.startsWith("geosite:") }?.removePrefix("geosite:")
            (tag == null || geo.hasSite(tag)).also { if (!it) missing.add(value) }
        }
        fun ips(values: List<String>) = values.filter { value ->
            val tag = value.takeIf { it.startsWith("geoip:") }?.removePrefix("geoip:")
            (tag == null || geo.hasIp(tag)).also { if (!it) missing.add(value) }
        }
        return routing.copy(
            directSites = sites(routing.directSites), directIps = ips(routing.directIps),
            proxySites = sites(routing.proxySites), proxyIps = ips(routing.proxyIps),
            blockSites = sites(routing.blockSites), blockIps = ips(routing.blockIps),
            skipped = routing.skipped + missing,
        )
    }

    /** Normalises one domain entry to Xray syntax, or null when NyaWire cannot use it (for example `ext:` files). */
    internal fun site(raw: String): String? {
        val prefix = raw.substringBefore(':', "").lowercase()
        val value = raw.substringAfter(':', raw)
        return when (prefix) {
            "geosite" -> value.lowercase().takeIf { it.matches(categoryName) }?.let { "geosite:$it" }
            "domain", "full" -> domain(value)?.let { "$prefix:$it" }
            "keyword" -> value.takeIf { it.isNotBlank() && it.length <= 253 && it.none(Char::isWhitespace) }?.let { "keyword:$it" }
            "regexp" -> value.takeIf { it.length <= 512 && runCatching { Regex(it) }.isSuccess }?.let { "regexp:$it" }
            // Without a prefix Xray matches a substring, so keep the entry unchanged.
            "" -> domain(raw)
            else -> null
        }
    }

    internal fun ip(raw: String): String? {
        if (raw.startsWith("geoip:", ignoreCase = true)) {
            val tag = raw.substringAfter(':').lowercase()
            return tag.takeIf { it.removePrefix("!").matches(Regex("[a-z0-9_-]+")) }?.let { "geoip:$it" }
        }
        return raw.takeIf { runCatching { ClientCoreOptions.ips(it) }.isSuccess && ClientCoreOptions.lines(it).size == 1 }
    }

    private fun domain(value: String): String? = runCatching {
        val name = IDN.toASCII(value.removePrefix("*.").removePrefix(".")).lowercase()
        name.takeIf { it.length <= 253 && it.split('.').all { label -> label.matches(Regex("[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?")) } }
    }.getOrNull()

    private fun order(value: String?): List<RouteTarget> {
        val names = value?.lowercase()?.split('-', ',', ' ')?.filter { it.isNotBlank() } ?: return RouteTarget.entries
        val targets = names.mapNotNull { name -> RouteTarget.entries.firstOrNull { it.name.equals(name, true) } }
        return targets.takeIf { it.size == 3 && it.toSet().size == 3 } ?: RouteTarget.entries
    }

    private fun List<String>.capped(skipped: MutableList<String>): List<String> {
        if (size > MAX_RULES) skipped.add(AppText.text(R.string.happ_routing_truncated, size - MAX_RULES))
        return take(MAX_RULES)
    }

    private fun JsonElement.text(): String? = takeIf { it.isJsonPrimitive }?.asString

    private fun decodeBase64(value: String): String {
        val compact = value.filterNot(Char::isWhitespace).trimEnd('=')
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.recoverCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
            ?: throw IllegalArgumentException(AppText.text(R.string.happ_routing_invalid))
        return bytes.toString(Charsets.UTF_8)
    }

    /** Decodes only %XX escapes: a plus sign is part of standard base64, not a space. */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val output = java.io.ByteArrayOutputStream(); var index = 0
        while (index < value.length) {
            val hex = if (value[index] == '%' && index + 2 < value.length) value.substring(index + 1, index + 3).toIntOrNull(16) else null
            if (hex != null) { output.write(hex); index += 3 } else { output.write(value[index].toString().toByteArray()); index++ }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
