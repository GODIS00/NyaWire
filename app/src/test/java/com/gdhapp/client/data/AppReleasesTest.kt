package com.gdhapp.client.data

import org.junit.Assert.*
import org.junit.Test

class AppReleasesTest {
    private fun response(version: String = "2.8.1", assets: String = asset(version, "universal"), flags: String = "") =
        """{"tag_name":"v$version","assets":[$assets]$flags}"""
    private fun asset(version: String, abi: String, url: String = "https://github.com/owner/NyaWire/releases/download/v$version/NyaWire-$version-$abi.apk") =
        """{"name":"NyaWire-$version-$abi.apk","browser_download_url":"$url"}"""
    private fun parse(json: String, current: String = "2.8.0", arm64: Boolean = true) =
        AppReleases.parse(json, "owner/NyaWire", current, arm64)

    @Test fun comparesVersionsNumericallyAndRejectsOlderReleases() {
        assertEquals("2.10.0", parse(response("2.10.0"), "2.9.9")?.version)
        assertNull(parse(response(), "2.8.1"))
        assertNull(parse(response(), "3.0.0"))
        assertNull(parse(response("2.8.2-beta")))
        assertNull(parse(response("99999999999999.0.0")))
    }

    @Test fun prefersArm64AndFallsBackToUniversal() {
        val both = response(assets = asset("2.8.1", "universal") + "," + asset("2.8.1", "arm64"))
        assertTrue(parse(both)!!.apkUrl.endsWith("-arm64.apk"))
        assertTrue(parse(both, arm64 = false)!!.apkUrl.endsWith("-universal.apk"))
        assertTrue(parse(response())!!.apkUrl.endsWith("-universal.apk"))
        assertNull(parse(response(assets = asset("2.8.1", "arm64")), arm64 = false))
    }

    @Test fun ignoresDraftsPrereleasesAndMissingApks() {
        assertNull(parse(response(flags = ",\"draft\":true")))
        assertNull(parse(response(flags = ",\"prerelease\":true")))
        assertNull(parse(response(assets = "")))
    }

    @Test fun downloadsOnlyFromTheConfiguredRepositoryAndMatchingTag() {
        listOf(
            "http://github.com/owner/NyaWire/releases/download/v2.8.1/NyaWire-2.8.1-universal.apk",
            "https://github.com.evil.test/owner/NyaWire/releases/download/v2.8.1/NyaWire-2.8.1-universal.apk",
            "https://github.com/other/NyaWire/releases/download/v2.8.1/NyaWire-2.8.1-universal.apk",
            "https://github.com/owner/NyaWire/releases/download/v2.8.0/NyaWire-2.8.1-universal.apk",
            "https://github.com/owner/NyaWire/releases/download/v2.8.1/NyaWire-2.8.1-universal.apk?token=secret"
        ).forEach { url -> assertNull(url, parse(response(assets = asset("2.8.1", "universal", url)))) }
    }

    @Test fun rejectsDuplicateAssetsAndInvalidRepositoryNames() {
        val item = asset("2.8.1", "universal")
        assertNull(parse(response(assets = "$item,$item")))
        listOf("", "owner", "../repo", "owner/repo/path", "owner/repo?token=a").forEach {
            assertFalse(AppReleases.validRepository(it))
        }
        assertTrue(AppReleases.validRepository("owner/NyaWire"))
    }
}
