package com.gdhapp.client.vpn.core

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import com.gdhapp.client.data.SubscriptionParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** New Xray versions use certificate pins instead of allowInsecure.
 * Secure profiles are unchanged. Only an explicitly insecure legacy profile fetches
 * its endpoint's leaf fingerprint for this session; existing pins take precedence. */
object LegacyTlsCompatibility {
    fun normalize(config: String, fingerprint: (String, Int, String, Boolean) -> String): String {
        val root = JsonParser.parseString(config).asJsonObject
        root.getAsJsonArray("outbounds").forEach { entry ->
            val outbound = entry.asJsonObject
            val stream = outbound.obj("streamSettings") ?: return@forEach
            val tls = stream.obj("tlsSettings") ?: return@forEach
            val insecure = tls.remove("allowInsecure")?.asBoolean == true
            if (insecure && tls.text("pinnedPeerCertSha256").isNullOrBlank()) {
                val profile = JsonObject().apply { add("outbounds", JsonArray().apply { add(outbound.deepCopy()) }) }
                val node = SubscriptionParser().parse(profile.toString()).servers.single()
                val host = node.host ?: error(AppText.text(R.string.tls_server_address_is_missing_from_the))
                val port = node.port ?: 443
                val hash = fingerprint(host, port, node.serverName ?: host, stream.text("network") == "hysteria")
                require(hash.matches(Regex("[0-9a-fA-F]{64}"))) { AppText.text(R.string.tls_the_server_did_not_return_a) }
                tls.addProperty("pinnedPeerCertSha256", hash)
            }
        }
        return root.toString()
    }
}
