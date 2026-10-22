package com.gdhapp.client.data

import com.gdhapp.client.domain.model.*
import com.gdhapp.client.vpn.core.CoreConfigBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ConnectionOptionsTest {
    private val raw = """{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"example.com","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111"}]}]},"streamSettings":{"network":"tcp","security":"tls"}}]}"""
    private fun config(settings: ClientSettings, share: Boolean = false) = JsonParser.parseString(CoreConfigBuilder.build(raw, settings, 10808, shareProxy = share).json).asJsonObject

    @Test fun sharedProxyRequiresAuthenticationAndDoesNotExposeTheLocalListener() {
        val root = config(ClientSettings(hotspotEnabled = true, hotspotUser = "test", hotspotPassword = "test-password"), true)
        val listeners = root.getAsJsonArray("inbounds")
        assertEquals("127.0.0.1", listeners[0].asJsonObject.get("listen").asString)
        val shared = listeners[1].asJsonObject
        assertEquals("0.0.0.0", shared.get("listen").asString)
        assertEquals("password", shared.getAsJsonObject("settings").get("auth").asString)
        assertFalse(shared.getAsJsonObject("settings").get("udp").asBoolean)
        assertEquals("test", shared.getAsJsonObject("settings").getAsJsonArray("accounts")[0].asJsonObject.get("user").asString)
    }

    @Test fun probesNeverOpenSharedListeners() {
        assertEquals(1, config(ClientSettings(hotspotEnabled = true)).getAsJsonArray("inbounds").size())
    }

    @Test fun invalidOrCollidingSharedProxyIsRejected() {
        for (settings in listOf(ClientSettings(hotspotEnabled = true), ClientSettings(hotspotEnabled = true, hotspotPassword = "password", hotspotPort = 10808))) {
            assertTrue(runCatching { config(settings, true) }.isFailure)
        }
    }

    @Test fun muxOnlyChangesCompatibleProfilesAndDoesNotMutateTheSource() {
        assertTrue(config(ClientSettings(muxEnabled = true)).getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("mux").get("enabled").asBoolean)
        val vision = raw.replace("\"id\":", "\"flow\":\"xtls-rprx-vision\",\"id\":")
        val output = JsonParser.parseString(CoreConfigBuilder.build(vision, ClientSettings(muxEnabled = true), 10808).json).asJsonObject
        assertFalse(output.getAsJsonArray("outbounds")[0].asJsonObject.has("mux"))
        assertFalse(JsonParser.parseString(raw).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject.has("mux"))
    }

    @Test fun ipPreferenceOverridesOnlyTheDialStrategy() {
        val out = config(ClientSettings(ipMode = IpMode.IPV4)).getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("ForceIPv4", out.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("domainStrategy").asString)
        assertEquals("example.com", out.getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject.get("address").asString)
        assertFalse(config(ClientSettings()).getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("streamSettings").has("sockopt"))
    }
}
