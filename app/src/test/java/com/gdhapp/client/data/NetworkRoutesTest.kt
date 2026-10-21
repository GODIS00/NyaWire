package com.gdhapp.client.data

import com.gdhapp.client.vpn.NetworkRoutes
import org.junit.Assert.*
import org.junit.Test

class NetworkRoutesTest {
    @Test fun `LAN bypass routes cover public addresses and exclude private networks`() {
        val routes = NetworkRoutes.publicIpv4Routes()
        fun numeric(value: String) = value.split('.').fold(0L) { total, part -> (total shl 8) or part.toLong() }
        fun covered(ip: String) = routes.any { (base, prefix) -> val start = numeric(base); numeric(ip) in start until (start + (1L shl (32 - prefix))) }
        listOf("1.1.1.1", "8.8.8.8", "77.88.8.8", "223.5.5.5").forEach { assertTrue(it, covered(it)) }
        listOf("10.0.2.2", "192.168.1.1", "172.16.3.4", "127.0.0.1", "169.254.1.1", "224.0.0.1").forEach { assertFalse(it, covered(it)) }
    }
    @Test fun `DNS fields accept literal addresses without hostname resolution`() {
        assertTrue(NetworkRoutes.isLiteralAddress("1.1.1.1")); assertTrue(NetworkRoutes.isLiteralAddress("2606:4700:4700::1111"))
        assertFalse(NetworkRoutes.isLiteralAddress("999.1.1.1")); assertFalse(NetworkRoutes.isLiteralAddress("dns.example"))
    }
}
