package com.gdhapp.client.data

import com.gdhapp.client.domain.model.*
import com.gdhapp.client.vpn.core.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ClientCoreOptionsTest {
    private val raw = """{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111","flow":"xtls-rprx-vision"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"publicKey":"fixture","serverName":"example.com"}}}],"routing":{"rules":[{"type":"field","domain":["domain:existing.example"],"outboundTag":"proxy"}]}}"""
    @Test fun profileModePreservesRulesAndOutbound() {
        val before = JsonParser.parseString(raw).asJsonObject
        val after = JsonParser.parseString(CoreConfigBuilder.build(raw, ClientSettings(), 10808).json).asJsonObject
        assertEquals(before.get("routing"), after.get("routing")); assertEquals(before.get("outbounds"), after.get("outbounds"))
    }
    @Test fun clientRulesPrecedeProfileAndDoNotMutateSource() {
        val settings = ClientSettings(routingMode = RoutingMode.RULES, blockedDomains = "*.blocked.example", directDomains = "пример.рф", directIps = "192.168.0.0/16\nfd00::/8")
        val root = JsonParser.parseString(CoreConfigBuilder.build(raw, settings, 10808).json).asJsonObject
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals(4, rules.size()); assertTrue(rules[0].toString().contains("blocked.example")); assertTrue(rules[1].toString().contains("xn--"))
        assertTrue(rules[3].toString().contains("existing.example")); assertEquals(1, JsonParser.parseString(raw).asJsonObject.getAsJsonArray("outbounds").size())
    }
    @Test fun globalRoutingOverridesImportedRules() {
        val root = JsonParser.parseString(CoreConfigBuilder.build(raw, ClientSettings(routingMode = RoutingMode.GLOBAL), 10808).json).asJsonObject
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals(1, rules.size()); assertEquals("proxy", rules[0].asJsonObject.get("outboundTag").asString)
    }
    @Test fun fragmentRetainsRealityAndUsesNativeMaskWithoutAnExtraDialer() {
        val root = JsonParser.parseString(CoreConfigBuilder.build(raw, ClientSettings(fragmentEnabled = true), 10808).json).asJsonObject
        val out = root.getAsJsonArray("outbounds")
        assertEquals("xtls-rprx-vision", out[0].asJsonObject.getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject.getAsJsonArray("users")[0].asJsonObject.get("flow").asString)
        assertTrue(out[0].toString().contains("realitySettings")); assertFalse(out[0].toString().contains("dialerProxy"))
        assertEquals(1, out.size())
        val mask = out[0].asJsonObject.getAsJsonObject("streamSettings").getAsJsonObject("finalmask").getAsJsonArray("tcp")[0].asJsonObject
        assertEquals("fragment", mask.get("type").asString)
        assertEquals("tlshello", mask.getAsJsonObject("settings").get("packets").asString)
    }
    @Test fun validatesIpDomainAndFragmentRanges() {
        assertTrue(ClientCoreOptions.validRange("100-200", 1, 4096)); assertFalse(ClientCoreOptions.validRange("200-100", 1, 4096))
        assertFalse(ClientCoreOptions.validRange("-2", 0, 1000)); assertTrue(ClientCoreOptions.validRange("0", 0, 1000))
        assertEquals(listOf("example.com"), ClientCoreOptions.domains("*.example.com"))
        for (bad in listOf("999.1.2.3", "1.2.3.4/33", "abc/12", "fd00::/129")) assertTrue(runCatching { ClientCoreOptions.ips(bad) }.isFailure)
        assertTrue(runCatching { ClientCoreOptions.domains("https://example.com") }.isFailure)
    }
}
