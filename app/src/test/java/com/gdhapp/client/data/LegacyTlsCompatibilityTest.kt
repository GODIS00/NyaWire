package com.gdhapp.client.data

import com.gdhapp.client.vpn.core.LegacyTlsCompatibility
import org.junit.Assert.*
import org.junit.Test

class LegacyTlsCompatibilityTest {
    private fun profile(insecure: Boolean, pin: String = "") = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"node.example","port":443}]},"streamSettings":{"security":"tls","tlsSettings":{"serverName":"cdn.example","allowInsecure":$insecure${if (pin.isNotEmpty()) ",\"pinnedPeerCertSha256\":\"$pin\"" else ""}}}}]}"""
    @Test fun `secure legacy boolean is removed without any certificate fetch`() {
        var called = false
        val result = LegacyTlsCompatibility.normalize(profile(false)) { _, _, _, _ -> called = true; "" }
        assertFalse(called); assertFalse(result.contains("allowInsecure"))
    }
    @Test fun `explicit legacy insecure profile uses its endpoint fingerprint`() {
        val hash = "ab".repeat(32)
        val result = LegacyTlsCompatibility.normalize(profile(true)) { host, port, sni, quic ->
            assertEquals("node.example", host); assertEquals(443, port); assertEquals("cdn.example", sni); assertFalse(quic); hash
        }
        assertTrue(result.contains(hash)); assertFalse(result.contains("allowInsecure"))
    }
    @Test fun `existing certificate pins are never replaced`() {
        val hash = "cd".repeat(32)
        val result = LegacyTlsCompatibility.normalize(profile(true, hash)) { _, _, _, _ -> error("Must not fetch") }
        assertTrue(result.contains(hash))
    }
}
