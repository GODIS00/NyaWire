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
        // geoip:ru matches the tunnelled connection's own address; no extra lookups through the ISP resolver.
        assertEquals(4, rules.size); assertFalse(routing.has("domainStrategy"))
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

    @Test fun xrayPanelHysteriaProfileRunsOnSingBox() {
        val panel = """{"dns":{"servers":["1.1.1.1"]},"inbounds":[{"tag":"socks","port":10808,"protocol":"socks"}],
            "outbounds":[{"tag":"proxy","protocol":"hysteria","settings":{"version":2,"address":"hy.example.com","port":443},
              "streamSettings":{"network":"hysteria","security":"tls","tlsSettings":{"serverName":"hy.example.com","fingerprint":"chrome","alpn":["h3"]},
                "hysteriaSettings":{"version":2,"auth":"secret"},"finalmask":{"udp":[{"type":"salamander","settings":{"password":"obfs"}}]}}},
              {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"},{"tag":"dns-out","protocol":"dns"}],
            "routing":{"domainStrategy":"IPIfNonMatch","rules":[
              {"type":"field","protocol":["bittorrent"],"outboundTag":"block"},
              {"type":"field","ip":["geoip:private"],"outboundTag":"direct"},
              {"type":"field","domain":["geosite:category-ads-all","geosite:not-bundled"],"outboundTag":"block"},
              {"type":"field","domain":["geosite:not-bundled"],"outboundTag":"direct"},
              {"type":"field","port":"53,5353-5354","network":"udp","outboundTag":"dns-out"},
              {"type":"field","inboundTag":["socks"],"balancerTag":"auto"},
              {"type":"field","user":["someone"],"outboundTag":"direct"}]}}"""
        // Auto keeps native Xray Hysteria2; sing-box now accepts the same profile instead of asking for Xray.
        assertEquals(CoreEngine.XRAY, CoreConfigBuilder.build(panel, ClientSettings(), 10808, geo = geo).engine)
        val built = CoreConfigBuilder.build(panel, ClientSettings(coreEngine = CoreEngine.SING_BOX), 10808, geo = geo)
        assertEquals(CoreEngine.SING_BOX, built.engine)
        val root = JsonParser.parseString(built.json).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals(listOf("hysteria2", "direct"), outbounds.map { it.get("type").asString })
        assertEquals("secret", outbounds[0].get("password").asString); assertEquals("salamander", outbounds[0].getAsJsonObject("obfs").get("type").asString)
        // sing-box's QUIC clients refuse uTLS, so the panel's fingerprint must not turn it on.
        assertFalse(outbounds[0].getAsJsonObject("tls").has("utls"))
        assertFalse(root.has("routing"))
        assertEquals("udp", root.getAsJsonObject("dns").getAsJsonArray("servers")[0].asJsonObject.get("type").asString)
        val rules = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("sniff", rules[0].get("action").asString)
        assertEquals("[\"bittorrent\"]", rules[1].get("protocol").toString()); assertEquals("reject", rules[1].get("action").asString)
        assertTrue(rules[2].get("ip_is_private").asBoolean); assertEquals("direct", rules[2].get("outbound").asString)
        assertEquals("reject", rules[3].get("action").asString); assertTrue(rules[3].getAsJsonArray("domain_suffix").size() > 100)
        assertEquals("hijack-dns", rules[4].get("action").asString); assertEquals("[53]", rules[4].get("port").toString())
        assertEquals("[\"5353:5354\"]", rules[4].get("port_range").toString()); assertEquals("[\"udp\"]", rules[4].get("network").toString())
        // A rule left with only unknown categories, a balancer and a per-user rule are not guessed at.
        assertEquals(5, rules.size)
    }

    @Test fun realityUsesXrayByDefaultAndCanRunOnSingBox() {
        val reality = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,
            "users":[{"id":"11111111-1111-4111-8111-111111111111","flow":"xtls-rprx-vision"}]}]},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"example.com","fingerprint":"chrome","publicKey":"fixture","shortId":"aabb"}}}]}"""
        assertEquals(CoreEngine.XRAY, CoreConfigBuilder.build(reality, ClientSettings(), 10808, geo = geo).engine)
        val converted = CoreConfigBuilder.build(reality, ClientSettings(coreEngine = CoreEngine.SING_BOX), 10808, geo = geo)
        assertEquals(CoreEngine.SING_BOX, converted.engine)
        val outbound = JsonParser.parseString(converted.json).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("xtls-rprx-vision", outbound.get("flow").asString)
        assertEquals("fixture", outbound.getAsJsonObject("tls").getAsJsonObject("reality").get("public_key").asString)
        val noFingerprint = CoreConfigBuilder.build(reality.replace("\"fingerprint\":\"chrome\",", ""), ClientSettings(coreEngine = CoreEngine.SING_BOX), 10808, geo = geo)
        val tls = JsonParser.parseString(noFingerprint.json).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("tls")
        assertEquals("chrome", tls.getAsJsonObject("utls").get("fingerprint").asString)
    }

    @Test fun probesSendEverythingThroughTheTestedServer() {
        // The test URL must never leave through a direct rule: then every profile would "answer", working or not.
        val panel = """{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111"}]}]}},
            {"protocol":"freedom","tag":"direct"}],"routing":{"rules":[{"type":"field","domain":["domain:gstatic.com"],"outboundTag":"direct"}]}}"""
        val whitelist = ClientSettings(routingMode = RoutingMode.RULES, routeRussiaDirect = true, importedRouting = imported.copy(globalProxy = false))
        for ((raw, settings) in listOf(panel to ClientSettings(), xrayProfile to whitelist)) {
            val root = JsonParser.parseString(CoreConfigBuilder.build(raw, settings, 10808, geo = geo, forProbe = true).json).asJsonObject
            val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
            assertEquals(1, rules.size); assertEquals("tcp,udp", rules[0].get("network").asString)
            assertEquals("proxy", rules[0].get("outboundTag").asString)
        }
        val box = JsonParser.parseString(CoreConfigBuilder.build(singBoxProfile, whitelist, 10808, geo = geo, forProbe = true).json).asJsonObject
        assertEquals(0, box.getAsJsonObject("route").getAsJsonArray("rules").size()); assertEquals("proxy", box.getAsJsonObject("route").get("final").asString)
        // The connection itself keeps the rules.
        assertEquals("direct", build(panel, ClientSettings()).getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject.get("outboundTag").asString)
    }

    @Test fun singBoxNeedsGeodataForCategories() {
        val settings = ClientSettings(routingMode = RoutingMode.RULES, blockAds = true)
        assertTrue(runCatching { CoreConfigBuilder.build(singBoxProfile, settings, 10808) }.isFailure)
        // Plain client rules still work without it.
        assertTrue(runCatching { CoreConfigBuilder.build(singBoxProfile, ClientSettings(routingMode = RoutingMode.RULES, directDomains = "a.example"), 10808) }.isSuccess)
    }
}
