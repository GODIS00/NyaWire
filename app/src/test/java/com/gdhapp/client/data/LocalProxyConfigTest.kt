package com.gdhapp.client.data

import com.gdhapp.client.domain.model.*
import com.gdhapp.client.vpn.core.CoreConfigBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class LocalProxyConfigTest {
    private val raw = """{"inbounds":[{"tag":"old"}],"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000099"}]}]}}],"routing":{"rules":[{"type":"field","inboundTag":["old"],"outboundTag":"proxy"}]}}"""

    @Test fun nativeVpnHasNoSocksInboundAndPreservesTunRouting() {
        assertFalse(ClientSettings().localSocksEnabled)
        val json = JsonParser.parseString(CoreConfigBuilder.build(raw, ClientSettings(), 10808, nativeTun = true, includeSocks = false).json).asJsonObject
        val inbounds = json.getAsJsonArray("inbounds")
        assertEquals(1, inbounds.size())
        assertEquals("tun", inbounds[0].asJsonObject.get("protocol").asString)
        assertTrue(json.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("inboundTag").any { it.asString == "nyawire-tun" })
    }

    @Test fun xrayCompatibilityBridgeRequiresPassword() {
        val json = JsonParser.parseString(CoreConfigBuilder.build(raw, ClientSettings(), 34567, socksCredentials = "bridge" to "random-secret").json).asJsonObject
        val socks = json.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("settings")
        assertEquals("password", socks.get("auth").asString)
        assertEquals("random-secret", socks.getAsJsonArray("accounts")[0].asJsonObject.get("pass").asString)
    }

    @Test fun singBoxBridgeRequiresPassword() {
        val profile = JsonParser.parseString(raw).asJsonObject.apply { remove("routing") }.toString()
        val json = JsonParser.parseString(CoreConfigBuilder.build(profile, ClientSettings(coreEngine = CoreEngine.SING_BOX), 34567, socksCredentials = "bridge" to "random-secret").json).asJsonObject
        assertEquals("random-secret", json.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonArray("users")[0].asJsonObject.get("password").asString)
    }
}
