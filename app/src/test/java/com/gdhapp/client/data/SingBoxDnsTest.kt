package com.gdhapp.client.data

import com.gdhapp.client.domain.model.*
import com.gdhapp.client.vpn.core.CoreConfigBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SingBoxDnsTest {
    private val profile = """{"outbounds":[{"type":"hysteria2","tag":"proxy","server":"hy.example","server_port":443,"password":"fixture","tls":{"enabled":true,"server_name":"hy.example"}}]}"""
    private fun build(raw: String = profile, settings: ClientSettings = ClientSettings()) =
        JsonParser.parseString(CoreConfigBuilder.build(raw, settings, 10808).json).asJsonObject

    @Test fun standaloneCoreGetsExplicitEndpointResolver() {
        val root = build(settings = ClientSettings(dnsPrimary = "8.8.8.8"))
        val resolver = root.getAsJsonObject("dns").getAsJsonArray("servers").single().asJsonObject
        assertEquals("udp", resolver.get("type").asString)
        assertEquals("8.8.8.8", resolver.get("server").asString)
        assertEquals(resolver.get("tag"), root.getAsJsonObject("route").get("default_domain_resolver"))
        assertFalse(JsonParser.parseString(profile).asJsonObject.has("dns"))
    }
    @Test fun importedDnsAndResolverRemainUnchanged() {
        val input = JsonParser.parseString(profile).asJsonObject
        val dns = JsonParser.parseString("""{"servers":[{"type":"https","tag":"custom","server":"1.1.1.1","path":"/dns-query"}],"final":"custom"}""")
        input.add("dns", dns.deepCopy())
        input.add("route", JsonParser.parseString("""{"default_domain_resolver":{"server":"custom","strategy":"ipv4_only"}}"""))
        val output = build(input.toString())
        assertEquals(dns, output.get("dns"))
        assertEquals(input.get("route"), output.get("route"))
    }
    @Test fun ipModeUsesExplicitResolverInsteadOfCreatingBrokenLocalDns() {
        val output = build(settings = ClientSettings(ipMode = IpMode.IPV4))
        val resolver = output.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("domain_resolver")
        assertEquals("ipv4_only", resolver.get("strategy").asString)
        assertEquals(output.getAsJsonObject("dns").get("final"), resolver.get("server"))
        assertEquals(1, output.getAsJsonObject("dns").getAsJsonArray("servers").size())
    }
}
