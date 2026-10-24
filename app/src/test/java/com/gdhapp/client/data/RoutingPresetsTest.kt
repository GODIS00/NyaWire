package com.gdhapp.client.data

import com.gdhapp.client.domain.model.*
import com.gdhapp.client.vpn.core.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RoutingPresetsTest {
    private val geo = GeoData(File("src/main/assets"))
    private val xrayProfile = """{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111"}]}]}}],"routing":{"rules":[{"type":"field","domain":["domain:existing.example"],"outboundTag":"proxy"}]}}"""
    private val singBoxProfile = """{"outbounds":[{"type":"tuic","tag":"proxy","server":"192.0.2.1","server_port":443,"uuid":"11111111-1111-4111-8111-111111111111","password":"secret"}]}"""
    private val imported = ImportedRouting(name = "Provider", proxySites = listOf("geosite:youtube"), proxyIps = listOf("203.0.113.0/24"),
        directSites = listOf("full:bank.example"), blockIps = listOf("geoip:private"))

    private fun build(raw: String, settings: ClientSettings): JsonObject =
        JsonParser.parseString(CoreConfigBuilder.build(raw, settings, 10808, geo = geo).json).asJsonObject

    @Test fun xrayPresetsUseBundledCategories() {
        val root = build(xrayProfile, ClientSettings(routingMode = RoutingMode.RULES, routeRussiaDirect = true, blockAds = true))
        val routing = root.getAsJsonObject("routing")
        val rules = routing.getAsJsonArray("rules").map { it.asJsonObject }
        val tags = root.getAsJsonArray("outbounds").map { it.asJsonObject }.associate { it.get("tag").asString to it.get("protocol").asString }
        assertEquals("blackhole", tags[rules[0].get("outboundTag").asString]); assertEquals("[\"geosite:category-ads-all\"]", rules[0].get("domain").toString())
        assertEquals("freedom", tags[rules[1].get("outboundTag").asString]); assertEquals("[\"geosite:category-ru\",\"geosite:tld-ru\"]", rules[1].get("domain").toString())
        assertEquals("[\"geoip:ru\"]", rules[2].get("ip").toString())
        assertTrue(rules[3].toString().contains("existing.example"))
        assertEquals(4, rules.size); assertEquals("IPIfNonMatch", routing.get("domainStrategy").asString)
    }

    @Test fun presetsDoNothingOutsideClientRules() {
        val settings = ClientSettings(routeRussiaDirect = true, blockAds = true)
        assertEquals(JsonParser.parseString(xrayProfile).asJsonObject.get("routing"), build(xrayProfile, settings).get("routing"))
    }

    @Test fun importedProfileOrdersBlockProxyDirectAndCanDefaultToDirect() {
        val settings = ClientSettings(routingMode = RoutingMode.RULES, directDomains = "mine.example", importedRouting = imported.copy(globalProxy = false))
        val root = build(xrayProfile, settings)
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals(listOf("[\"geoip:private\"]", "[\"geosite:youtube\"]", "[\"203.0.113.0/24\"]", "[\"domain:mine.example\",\"full:bank.example\"]"),
            rules.take(4).map { (it.get("ip") ?: it.get("domain")).toString() })
        assertEquals("proxy", rules[1].get("outboundTag").asString)
        // Profile rules stay after the client rules; everything unmatched then goes direct.
        assertTrue(rules[4].toString().contains("existing.example"))
        assertEquals("tcp,udp", rules[5].get("network").asString)
        assertEquals("freedom", root.getAsJsonArray("outbounds").first { it.asJsonObject.get("tag").asString == rules[5].get("outboundTag").asString }.asJsonObject.get("protocol").asString)
        // A disabled profile drops out entirely.
        val off = build(xrayProfile, settings.copy(importedRouting = imported.copy(enabled = false)))
        assertFalse(off.getAsJsonObject("routing").toString().contains("youtube"))
    }

    @Test fun singBoxExpandsCategoriesInline() {
        val root = build(singBoxProfile, ClientSettings(routingMode = RoutingMode.RULES, routeRussiaDirect = true, blockAds = true, importedRouting = imported))
        assertFalse(root.toString().contains("geosite:")); assertFalse(root.toString().contains("geoip:"))
        val rules = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("sniff", rules[0].get("action").asString)
        val reject = rules[1]
        assertEquals("reject", reject.get("action").asString); assertTrue(reject.getAsJsonArray("domain_suffix").size() > 100)
        assertEquals("resolve", rules[2].get("action").asString)
        assertTrue(rules[3].get("ip_is_private").asBoolean); assertEquals("reject", rules[3].get("action").asString)
        val youtube = rules[4]
        assertEquals("proxy", youtube.get("outbound").asString); assertTrue(youtube.getAsJsonArray("domain_suffix").any { it.asString == "youtube.com" })
        assertEquals("[\"203.0.113.0/24\"]", rules[5].get("ip_cidr").toString())
        val direct = rules[6]
        assertTrue(direct.getAsJsonArray("domain").any { it.asString == "bank.example" }); assertTrue(direct.getAsJsonArray("domain_suffix").any { it.asString == "ru" })
        val russianIps = rules[7]
        assertEquals(direct.get("outbound"), russianIps.get("outbound")); assertTrue(russianIps.getAsJsonArray("ip_cidr").size() > 1000)
        assertEquals(8, rules.size)
    }

    @Test fun singBoxNeedsGeodataForCategories() {
        val settings = ClientSettings(routingMode = RoutingMode.RULES, blockAds = true)
        assertTrue(runCatching { CoreConfigBuilder.build(singBoxProfile, settings, 10808) }.isFailure)
        // Plain client rules still work without it.
        assertTrue(runCatching { CoreConfigBuilder.build(singBoxProfile, ClientSettings(routingMode = RoutingMode.RULES, directDomains = "a.example"), 10808) }.isSuccess)
    }
}
