package com.gdhapp.client.data

import com.gdhapp.client.vpn.core.GeoData
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GeoDataTest {
    // Unit tests run from the module directory; read the bundled v2fly files directly.
    private val geo = GeoData(File("src/main/assets"))

    @Test fun listsBundledCategories() {
        for (tag in listOf("category-ru", "tld-ru", "category-ads-all", "private")) assertTrue(tag, geo.hasSite(tag))
        assertTrue(geo.hasIp("ru")); assertTrue(geo.hasIp("private")); assertTrue(geo.hasIp("!ru"))
        assertTrue(geo.hasSite("CATEGORY-RU")); assertFalse(geo.hasSite("ru-blocked")); assertFalse(geo.hasIp("xx"))
    }

    @Test fun expandsSitesByMatchType() {
        val tld = geo.site("tld-ru")
        assertTrue("ru" in tld.suffix); assertTrue("xn--p1ai" in tld.suffix)
        val ads = geo.site("category-ads-all")
        assertTrue(ads.suffix.size > 100); assertTrue(ads.regex.isNotEmpty())
        // An attribute filter keeps only tagged domains.
        assertTrue(geo.site("category-ads-all@ads").let { it.suffix.size + it.full.size } < ads.suffix.size + ads.full.size)
    }

    @Test fun expandsIpCategoriesToCidrs() {
        assertTrue("10.0.0.0/8" in geo.ip("private"))
        val russia = geo.ip("ru")
        assertTrue(russia.size > 1000)
        assertTrue(russia.all { it.matches(Regex("[0-9a-f.:]+/\\d{1,3}")) })
        assertTrue(russia.any { ':' in it })
    }
}
