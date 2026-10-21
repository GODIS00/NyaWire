package com.gdhapp.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionParserTest {
    private val parser = SubscriptionParser()
    private val xray = """{"remarks":"Нидерланды #1","outbounds":[{"protocol":"freedom"},{"protocol":"vless","settings":{"vnext":[{"address":"nl.example","port":443}]} ,"streamSettings":{"network":"xhttp","security":"reality"}}]}"""

    @Test fun `country headings and provider metadata are accepted before links`() {
        val note = Base64.getEncoder().encodeToString("Обновление подписки".toByteArray())
        val body = """#profile-title: Haven vpn
            |#support-url: https://t.me/example
            |#announce: base64: $note
            |#profile-update-interval: 6
            |
            |🇵🇱 Польша
            |vless://00000000-0000-4000-8000-000000000077@pl.example:443?security=none&type=tcp#Poland
            |
            |🇩🇪 Германия
            |vless://00000000-0000-4000-8000-000000000078@de.example:443?security=none&type=tcp#Germany
        """.trimMargin()
        for (value in listOf(body, Base64.getEncoder().encodeToString(body.toByteArray()))) {
            val group = parser.parse(value)
            assertEquals("Haven vpn", group.title)
            assertEquals("https://t.me/example", group.supportUrl)
            assertEquals("Обновление подписки", group.announcementText)
            assertEquals(6, group.autoUpdateHours)
            assertEquals(listOf("pl.example", "de.example"), group.servers.map { it.host })
        }
        val encodedLinks = Base64.getEncoder().encodeToString(body.substringAfter("🇵🇱 Польша").toByteArray())
        assertEquals("Haven vpn", parser.parse("#profile-title: Haven vpn\n$encodedLinks").title)
    }

    @Test fun `downloaded Haven response imports all nine servers`() {
        val path = System.getenv("NyaWire_SUBSCRIPTION_FIXTURE")
        org.junit.Assume.assumeTrue("Opt-in downloaded provider fixture", path != null)
        val group = parser.parse(java.io.File(path!!).readText())
        assertEquals("Haven vpn", group.title)
        assertEquals(9, group.servers.size)
        assertTrue(group.servers.all { it.host != null && it.port != null })
        assertTrue(group.announcementText.isNotBlank())
        assertTrue(group.servers.all { it.protocolSummary.startsWith("VLESS /") })
    }

    @Test fun `Xray config finds proxy rather than direct outbound`() {
        val node = parser.parse(xray).servers.single()
        assertEquals("Нидерланды #1", node.name)
        assertEquals("VLESS / XHTTP / REALITY | JSON", node.protocolSummary)
        assertEquals("nl.example", node.host)
        assertEquals(443, node.port)
    }

    @Test fun `sing-box config retains all outbounds and derives reality`() {
        val json = """{"outbounds":[{"type":"selector","tag":"proxy","outbounds":["nl"]},{"type":"vless","tag":"nl","server":"edge.example","server_port":8443,"tls":{"enabled":true,"reality":{"enabled":true}},"transport":{"type":"grpc"}},{"type":"direct"}]}"""
        val node = parser.parse(json).servers.single()
        assertEquals("VLESS / GRPC / REALITY | JSON", node.protocolSummary)
        assertEquals("edge.example", node.host)
        assertEquals(8443, node.port)
        assertTrue(node.rawConfigJson.contains("selector"))
    }

    @Test fun `wrapper and duplicate configs produce unique stable node keys`() {
        val wrapper = """{"title":"RUnet","countryCode":"RU","servers":[{"name":"Первый","countryCode":"NL","config":$xray},{"name":"Второй","config":$xray}]}"""
        val group = parser.parse(wrapper)
        assertEquals("RUnet", group.title)
        assertEquals("NL", group.servers.first().flagResOrCode)
        assertNotEquals(group.servers[0].id, group.servers[1].id)
        assertEquals(group.servers.map { it.id }, parser.parse(wrapper).servers.map { it.id })
    }

    @Test fun `base64 JSON imports without modifying the config`() {
        val encoded = Base64.getEncoder().encodeToString(xray.toByteArray(Charsets.UTF_8))
        assertEquals(parser.parse(xray).servers.single().rawConfigJson, parser.parse(encoded).servers.single().rawConfigJson)
    }

    @Test fun `UTF8 BOM is accepted`() {
        assertEquals(1, parser.parse("\uFEFF$xray").servers.size)
    }

    @Test fun `support links reject non-web executable schemes`() {
        assertNull(SubscriptionParser.safeSupportUrl("javascript:alert(1)"))
        assertNull(SubscriptionParser.safeSupportUrl("file:///sdcard/secrets"))
        assertEquals("https://t.me/example", SubscriptionParser.safeSupportUrl("https://t.me/example"))
        assertEquals("tg://resolve?domain=example", SubscriptionParser.safeSupportUrl("tg://resolve?domain=example"))
    }

    @Test(expected = IllegalArgumentException::class) fun `non-config JSON is rejected`() {
        parser.parse("""{"message":"access denied"}""")
    }

    @Test(expected = IllegalArgumentException::class) fun `empty subscription is rejected`() {
        parser.parse("[]")
    }

    @Test(expected = IllegalArgumentException::class) fun `oversize import is rejected before JSON parsing`() {
        parser.parse(" ".repeat(SubscriptionParser.MAX_BYTES + 1))
    }

    @Test fun `bad endpoint port does not become a TCP target`() {
        assertNull(parser.parse(xray.replace("443", "99999")).servers.single().port)
    }

    @Test fun `commented plain links and wrapped urlsafe base64 import`() {
        val links = "# subscription\nvless://00000000-0000-4000-8000-000000000077@example.com:443?security=none&type=tcp#One"
        assertEquals(1, parser.parse(links).servers.size)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(links.substringAfter('\n').toByteArray())
        assertEquals(1, parser.parse(encoded.chunked(25).joinToString("\r\n")).servers.size)
    }
    @Test fun `installer URLs unwrap without damaging literal plus in token`() {
        assertEquals("https://example.com/sub?a=b+c", SubscriptionInput.normalize("happ://add/https://example.com/sub?a=b+c"))
        assertEquals("https://example.com/sub", SubscriptionInput.normalize("v2rayng://install-config?url=https%3A%2F%2Fexample.com%2Fsub"))
        assertEquals("https://example.com/sub", SubscriptionInput.normalize("v2raytun://import/https://example.com/sub"))
    }
}
