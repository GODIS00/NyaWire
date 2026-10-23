package com.gdhapp.client.data

import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.CoreEngine
import com.gdhapp.client.vpn.core.CoreConfigBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ShareLinkParserTest {
    private val parser = SubscriptionParser()
    @Test fun `raw country names spaces and hashes do not change connection parameters`() {
        val base = "vless://11111111-1111-4111-8111-111111111111@node.example:443?security=reality&type=tcp&sni=example.com&pbk=fixture&sid=aabb&flow=xtls-rprx-vision"
        val raw = ShareLinkParser.parse("$base#🇵🇱 Польша #1")
        val encoded = ShareLinkParser.parse("$base#%F0%9F%87%B5%F0%9F%87%B1%20%D0%9F%D0%BE%D0%BB%D1%8C%D1%88%D0%B0%20%231")
        assertEquals("🇵🇱 Польша #1", raw.get("remarks").asString)
        assertEquals(encoded, raw)
        assertEquals("Node + 50%", ShareLinkParser.parse("$base#Node + 50%").get("remarks").asString)
    }
    @Test fun `sing-box retains mixed listener and no Xray TUN inbound`() {
        val raw = """{"outbounds":[{"type":"vless","server":"example.com","server_port":443,"uuid":"11111111-1111-4111-8111-111111111111"}]}"""
        val result = CoreConfigBuilder.build(raw, ClientSettings(), 10808, nativeTun = true)
        val root = JsonParser.parseString(result.json).asJsonObject
        assertEquals(CoreEngine.SING_BOX, result.engine)
        assertEquals(1, root.getAsJsonArray("inbounds").size())
        assertEquals("mixed", root.getAsJsonArray("inbounds")[0].asJsonObject.get("type").asString)
    }
    @Test fun `native TUN counters get unique tags without changing raw profile`() {
        val raw = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[]}},{"protocol":"freedom","tag":"nyawire-out-0"}]}"""
        val result = CoreConfigBuilder.build(raw, ClientSettings(), 10808, nativeTun = true)
        val tags = JsonParser.parseString(result.json).asJsonObject.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertEquals(listOf("nyawire-out-0_", "nyawire-out-0"), tags)
        assertFalse(JsonParser.parseString(raw).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject.has("tag"))
    }
    @Test fun `native TUN retains REALITY Vision and routes both local inbounds`() {
        val profile = ShareLinkParser.parse("vless://11111111-1111-4111-8111-111111111111@node.example:443?security=reality&type=tcp&flow=xtls-rprx-vision&pbk=fixture-key&sid=aabb&sni=example.com&fp=chrome")
        profile.add("inbounds", JsonParser.parseString("""[{"tag":"old-inbound"}]"""))
        profile.add("routing", JsonParser.parseString("""{"rules":[{"type":"field","inboundTag":["old-inbound"],"outboundTag":"proxy"}]}"""))
        val originalOutbound = profile.getAsJsonArray("outbounds").deepCopy()
        val result = CoreConfigBuilder.build(profile.toString(), ClientSettings(), 10808, nativeTun = true)
        val root = JsonParser.parseString(result.json).asJsonObject
        assertEquals(CoreEngine.XRAY, result.engine)
        assertEquals(originalOutbound, root.getAsJsonArray("outbounds"))
        assertEquals(listOf("socks", "tun"), root.getAsJsonArray("inbounds").map { it.asJsonObject.get("protocol").asString })
        assertEquals(listOf("nyawire-in", "nyawire-tun"), root.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("inboundTag").map { it.asString })
        assertEquals(1500, root.getAsJsonArray("inbounds")[1].asJsonObject.getAsJsonObject("settings").get("mtu").asInt)
        assertFalse(CoreConfigBuilder.build(profile.toString(), ClientSettings(), 10808).json.contains("nyawire-tun"))
    }
    @Test fun `VLESS reality xhttp keeps authentication and transport`() {
        val node = parser.parse("vless://11111111-1111-4111-8111-111111111111@nl.example:443?security=reality&pbk=test-key&sid=aabb&sni=example.com&fp=chrome&type=xhttp&path=%2Fedge&mode=auto#%F0%9F%87%B3%F0%9F%87%B1%20Node").servers.single()
        assertEquals("NL", node.flagResOrCode); assertEquals("nl.example", node.host); assertEquals("example.com", node.serverName)
        val prepared = CoreConfigBuilder.build(node.rawConfigJson, ClientSettings(), 10808)
        assertEquals(CoreEngine.XRAY, prepared.engine)
        assertTrue(prepared.json.contains("test-key")); assertTrue(prepared.json.contains("xhttpSettings"))
        assertTrue(prepared.json.contains("/edge")); assertTrue(prepared.json.contains("11111111-1111-4111-8111-111111111111"))
    }
    @Test fun `Base64 multi-protocol subscription has correct endpoints`() {
        val input = "trojan://pass@example.org:8443?sni=cdn.example#Trojan\nhysteria2://secret@quic.example:443?sni=quic.example&obfs=salamander&obfs-password=obfs#Hysteria"
        val nodes = parser.parse(Base64.getEncoder().encodeToString(input.toByteArray())).servers
        assertEquals(2, nodes.size); assertEquals(8443, nodes[0].port); assertEquals("quic.example", nodes[1].host)
        assertEquals(CoreEngine.SING_BOX, CoreConfigBuilder.build(nodes[1].rawConfigJson, ClientSettings(), 12345).engine)
    }
    @Test fun `Happ style QUIC settings address can be pinged`() {
        val raw = """{"remarks":"LTE","outbounds":[{"protocol":"hysteria","settings":{"address":"udp.example","port":443,"auth":"secret","upMbps":10,"downMbps":50},"streamSettings":{"network":"hysteria","security":"tls","tlsSettings":{"serverName":"cdn.example"}}}]}"""
        val node = parser.parse(raw).servers.single()
        assertEquals("udp.example", node.host); assertEquals(443, node.port)
        val prepared = CoreConfigBuilder.build(node.rawConfigJson, ClientSettings(), 12345)
        assertEquals(CoreEngine.SING_BOX, prepared.engine)
        assertEquals("secret", JsonParser.parseString(prepared.json).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject.get("auth_str").asString)
    }
    @Test(expected = IllegalArgumentException::class) fun `forcing XHTTP into sing-box gives a clear error`() {
        val node = parser.parse("vless://11111111-1111-4111-8111-111111111111@node.example:443?type=xhttp&security=tls#Node").servers.single()
        CoreConfigBuilder.build(node.rawConfigJson, ClientSettings(coreEngine = CoreEngine.SING_BOX), 12345)
    }
    @Test fun `native Xray Hysteria2 preserves transport auth and routing`() {
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"hysteria","settings":{"version":2,"address":"udp.example","port":443},"streamSettings":{"network":"hysteria","security":"tls","hysteriaSettings":{"version":2,"auth":"transport-secret"}}}],"routing":{"rules":[{"type":"field","network":"tcp,udp","outboundTag":"proxy"}]}}"""
        val result = CoreConfigBuilder.build(raw, ClientSettings(), 10808)
        assertEquals(CoreEngine.XRAY, result.engine)
        assertTrue(result.json.contains("transport-secret")); assertTrue(result.json.contains("routing"))
    }
    @Test fun `Shadowsocks SIP002 accepts URL safe base64 password`() {
        val auth = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:a+password".toByteArray())
        val node = parser.parse("ss://$auth@ss.example:8388#Test").servers.single()
        assertEquals(8388, node.port); assertTrue(node.rawConfigJson.contains("a+password"))
    }
}
