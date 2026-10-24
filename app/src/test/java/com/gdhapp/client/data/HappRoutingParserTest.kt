package com.gdhapp.client.data

import com.gdhapp.client.domain.model.RouteTarget
import com.gdhapp.client.vpn.core.GeoData
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.util.Base64

class HappRoutingParserTest {
    private val profile = """{
        "Name": "Provider RU", "GlobalProxy": "true", "DomainStrategy": "IPIfNonMatch",
        "DirectSites": ["geosite:category-ru", "domain:yandex.ru", "ext:custom.dat:ru"],
        "DirectIp": ["geoip:ru", "geoip:private", "192.168.0.0/16"],
        "ProxySites": ["geosite:youtube", "full:chatgpt.com", "keyword:openai"],
        "ProxyIp": [],
        "BlockSites": ["geosite:category-ads-all", "regexp:^ads\\..*"],
        "BlockIp": ["999.1.1.1"],
        "RemoteDNSType": "DoH", "Geositeurl": "https://example.invalid/geosite.dat"
    }"""

    @Test fun parsesLinkAndNormalisesRules() {
        val link = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(profile.toByteArray())
        assertTrue(HappRoutingParser.isRoutingLink(link))
        val routing = HappRoutingParser.parse(link)
        assertEquals("Provider RU", routing.name)
        assertTrue(routing.globalProxy)
        assertEquals(listOf("geosite:category-ru", "domain:yandex.ru"), routing.directSites)
        assertEquals(listOf("geoip:ru", "geoip:private", "192.168.0.0/16"), routing.directIps)
        assertEquals(listOf("geosite:youtube", "full:chatgpt.com", "keyword:openai"), routing.proxySites)
        assertEquals(listOf("geosite:category-ads-all", "regexp:^ads\\..*"), routing.blockSites)
        // External geodata files and invalid addresses cannot be used.
        assertEquals(listOf("ext:custom.dat:ru", "999.1.1.1"), routing.skipped)
    }

    @Test fun acceptsUrlSafePercentEncodedAndRawForms() {
        val json = """{"Name":"Direct","GlobalProxy":false,"ProxySites":["domain:example.com"]}"""
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val standard = Base64.getEncoder().encodeToString(json.toByteArray())
        for (input in listOf("happ://routing/add/$urlSafe", "happ://routing/onadd/$urlSafe?source=bot#top", "happ://routing/add/" + URLEncoder.encode(standard, "UTF-8"), json, standard)) {
            val routing = HappRoutingParser.parse(input)
            assertEquals(input, "Direct", routing.name); assertFalse(routing.globalProxy)
            assertEquals(listOf("domain:example.com"), routing.proxySites)
        }
    }

    @Test fun readsRouteOrderAndRejectsOtherText() {
        val json = """{"directsites":["domain:a.example"],"routeorder":"direct-block-proxy"}"""
        assertEquals(listOf(RouteTarget.DIRECT, RouteTarget.BLOCK, RouteTarget.PROXY), HappRoutingParser.parse(json).order)
        assertEquals(RouteTarget.entries, HappRoutingParser.parse("""{"DirectSites":["domain:a.example"],"RouteOrder":"block-block"}""").order)
        for (bad in listOf("vless://abc@example.com:443", "happ://routing/add/%%%", """{"Name":"Empty"}""")) {
            assertTrue(bad, runCatching { HappRoutingParser.parse(bad) }.isFailure)
        }
    }

    @Test fun dropsCategoriesMissingFromBundledGeodata() {
        val json = """{"DirectSites":["geosite:category-ru","geosite:ru-available-only-inside"],"DirectIp":["geoip:ru","geoip:qq"],"BlockSites":["geosite:category-ads-all@ads"]}"""
        val routing = HappRoutingParser.available(HappRoutingParser.parse(json), GeoData(File("src/main/assets")))
        assertEquals(listOf("geosite:category-ru"), routing.directSites)
        assertEquals(listOf("geoip:ru"), routing.directIps)
        assertEquals(listOf("geosite:category-ads-all@ads"), routing.blockSites)
        assertEquals(listOf("geosite:ru-available-only-inside", "geoip:qq"), routing.skipped)
    }
}
