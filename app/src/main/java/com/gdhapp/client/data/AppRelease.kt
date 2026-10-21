package com.gdhapp.client.data

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal data class AppRelease(val version: String, val apkUrl: String)

/** Public releases only; never embeds GitHub credentials in the APK. */
internal object AppReleases {
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun latest(repository: String, currentVersion: String, arm64: Boolean): AppRelease? {
        require(validRepository(repository))
        val request = Request.Builder().url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NyaWire/$currentVersion").build()
        return client.newCall(request).readCancellable { response ->
            if (response.code == 404) return@readCancellable null
            check(response.isSuccessful) { "Release check HTTP ${response.code}" }
            val source = response.body?.source() ?: error("Empty release response")
            require(!source.request(1_048_577)) { "Release response too large" }
            parse(source.readUtf8(), repository, currentVersion, arm64)
        }
    }

    fun validRepository(value: String) = value.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
        value.split('/').none { it == "." || it == ".." }

    fun parse(json: String, repository: String, currentVersion: String, arm64: Boolean): AppRelease? {
        require(validRepository(repository))
        val release = JsonParser.parseString(json).asJsonObject
        if (release["draft"]?.asBoolean == true || release["prerelease"]?.asBoolean == true) return null
        val version = release["tag_name"]?.asString?.removePrefix("v") ?: error("Missing version")
        val remote = versionParts(version) ?: return null
        val current = versionParts(currentVersion) ?: return null
        if (remote.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } != true) return null
        val assets = release.getAsJsonArray("assets") ?: return null
        val names = buildList {
            if (arm64) add("NyaWire-$version-arm64.apk")
            add("NyaWire-$version-universal.apk")
        }
        for (name in names) {
            val matches = assets.filter { it.asJsonObject["name"]?.asString == name }
            if (matches.size != 1) continue
            val url = matches.single().asJsonObject["browser_download_url"]?.asString?.toHttpUrlOrNull() ?: continue
            val segments = url.pathSegments
            if (url.scheme != "https" || url.host != "github.com" || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
                segments.size != 6 || segments.take(2).joinToString("/") != repository ||
                segments[2] != "releases" || segments[3] != "download" ||
                segments[4] != release["tag_name"].asString || segments[5] != name) continue
            return AppRelease(version, url.toString())
        }
        return null
    }

    private fun versionParts(value: String): List<Int>? {
        if (!value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) return null
        return value.split('.').map { it.toIntOrNull() ?: return null }
    }
}
