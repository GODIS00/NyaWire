package com.gdhapp.client.data

import java.net.URI
import java.net.URLDecoder

/** Explicit install links are unwrapped; arbitrary text is never scanned for embedded secrets/URLs. */
object SubscriptionInput {
    fun normalize(value: String): String {
        val input = value.trim().removePrefix("\uFEFF").trim()
        if (input.startsWith("happ://add/", true)) return input.substringAfter("/add/").let(::decodeUrl)
        if (input.startsWith("v2rayng://install-config", true) || input.startsWith("v2raytun://import/", true)) {
            val uri = runCatching { URI(input) }.getOrNull()
            val queryUrl = uri?.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "url" }?.substringAfter('=')
            return queryUrl?.let(::decodeUrl) ?: input.substringAfter("/import/", input).let(::decodeUrl)
        }
        return input
    }
    private fun decodeUrl(value: String): String = if (value.startsWith("https://", true) || value.startsWith("http://", true)) value
        else runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
