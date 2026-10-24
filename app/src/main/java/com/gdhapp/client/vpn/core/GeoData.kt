package com.gdhapp.client.vpn.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the bundled v2fly geosite.dat/geoip.dat. Xray loads these files itself; the client needs them only
 * to check imported categories and to expand categories into plain rules for sing-box, which has no geodata.
 */
class GeoData(private val directory: File) {
    /** Domains of one geosite category, already split by match type. */
    data class Site(val full: List<String>, val suffix: List<String>, val keyword: List<String>, val regex: List<String>)

    @Volatile private var siteNames: Set<String>? = null
    @Volatile private var ipNames: Set<String>? = null
    private val sites = ConcurrentHashMap<String, Site>()
    private val ips = ConcurrentHashMap<String, List<String>>()

    fun hasSite(tag: String) = tag.substringBefore('@').lowercase() in siteTags()
    fun hasIp(tag: String) = tag.removePrefix("!").lowercase() in ipTags()

    fun siteTags(): Set<String> = siteNames ?: synchronized(this) { siteNames ?: names(SITE_FILE).also { siteNames = it } }
    fun ipTags(): Set<String> = ipNames ?: synchronized(this) { ipNames ?: names(IP_FILE).also { ipNames = it } }

    /** `tag` may carry an attribute filter, as in `geosite:category-ads-all@ads`. */
    fun site(tag: String): Site = sites.getOrPut(tag.lowercase()) {
        val name = tag.substringBefore('@')
        val attribute = tag.substringAfter('@', "").lowercase().takeIf { it.isNotEmpty() }
        val bytes = entry(SITE_FILE, name) ?: throw IllegalArgumentException("geosite:$name")
        val full = mutableListOf<String>(); val suffix = mutableListOf<String>()
        val keyword = mutableListOf<String>(); val regex = mutableListOf<String>()
        Fields(bytes).forEach { field, value ->
            if (field != 2 || value !is ByteArray) return@forEach
            var type = 0; var domain = ""; val attributes = mutableSetOf<String>()
            Fields(value).forEach { inner, item -> when (inner) {
                1 -> type = (item as Long).toInt()
                2 -> domain = String(item as ByteArray)
                3 -> Fields(item as ByteArray).forEach { key, text -> if (key == 1) attributes.add(String(text as ByteArray).lowercase()) }
            } }
            if (attribute != null && attribute !in attributes) return@forEach
            when (type) { 0 -> keyword.add(domain); 1 -> regex.add(domain); 2 -> suffix.add(domain); 3 -> full.add(domain) }
        }
        Site(full, suffix, keyword, regex)
    }

    /** CIDR strings of one geoip country or list, e.g. `ru` or `private`. */
    fun ip(tag: String): List<String> = ips.getOrPut(tag.lowercase()) {
        val bytes = entry(IP_FILE, tag) ?: throw IllegalArgumentException("geoip:$tag")
        val result = mutableListOf<String>()
        Fields(bytes).forEach { field, value ->
            if (field != 2 || value !is ByteArray) return@forEach
            var address = ByteArray(0); var prefix = 0
            Fields(value).forEach { inner, item -> when (inner) { 1 -> address = item as ByteArray; 2 -> prefix = (item as Long).toInt() } }
            val parsed = runCatching { InetAddress.getByAddress(address) }.getOrNull()
            // A 16-byte IPv4-mapped address would come back as IPv4 with a 128-bit prefix; v2fly stores IPv4 as 4 bytes.
            if (parsed != null && (address.size == 4) == (parsed is java.net.Inet4Address)) result.add("${parsed.hostAddress}/$prefix")
        }
        result
    }

    private fun names(file: String): Set<String> {
        val result = mutableSetOf<String>()
        scan(file) { code, _ -> result.add(code.lowercase()); false }
        return result
    }

    /** The message body of one top-level entry, without its country code. */
    private fun entry(file: String, code: String): ByteArray? {
        var found: ByteArray? = null
        scan(file) { name, read -> if (name.equals(code, ignoreCase = true)) { found = read(); true } else false }
        return found
    }

    /** Walks top-level list entries; [visit] reads the rest of an entry only when it needs it and returns true to stop. */
    private fun scan(file: String, visit: (String, () -> ByteArray) -> Boolean) {
        Reader(directory.resolve(file).inputStream()).use { reader ->
            while (!reader.atEnd()) {
                val key = reader.varint()
                require(key.toInt() and 7 == 2) { "$file: unexpected entry" }
                val length = reader.varint()
                val start = reader.position
                // v2fly writes the country code first; it is all a lookup needs.
                val first = reader.varint()
                require(first == (1L shl 3) or 2L) { "$file: entry without a code" }
                val code = String(reader.bytes(reader.varint().toInt()))
                var consumed = false
                val stop = visit(code) { consumed = true; reader.bytes((length - (reader.position - start)).toInt()) }
                if (stop) return
                if (!consumed) reader.skip(length - (reader.position - start))
            }
        }
    }

    /** Minimal protobuf field walker: varints as Long, length-delimited fields as ByteArray. */
    private class Fields(private val data: ByteArray) {
        fun forEach(action: (Int, Any) -> Unit) {
            var index = 0
            fun varint(): Long {
                var result = 0L; var shift = 0
                while (true) {
                    val byte = data[index++].toInt() and 0xff
                    result = result or ((byte and 0x7f).toLong() shl shift)
                    if (byte < 0x80) return result
                    shift += 7
                }
            }
            while (index < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> action(field, varint())
                    2 -> { val length = varint().toInt(); action(field, data.copyOfRange(index, index + length)); index += length }
                    1 -> index += 8
                    5 -> index += 4
                    else -> throw IllegalArgumentException("Unsupported protobuf wire type")
                }
            }
        }
    }

    private class Reader(input: InputStream) : AutoCloseable {
        private val stream = BufferedInputStream(input, 1 shl 16)
        var position = 0L; private set

        fun atEnd(): Boolean { stream.mark(1); val next = stream.read(); if (next < 0) return true; stream.reset(); return false }
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val byte = stream.read(); if (byte < 0) throw EOFException()
                position++
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte < 0x80) return result
                shift += 7
                require(shift < 64) { "Malformed varint" }
            }
        }
        fun bytes(count: Int): ByteArray {
            val result = ByteArray(count); var offset = 0
            while (offset < count) { val read = stream.read(result, offset, count - offset); if (read < 0) throw EOFException(); offset += read }
            position += count
            return result
        }
        fun skip(count: Long) {
            var left = count
            while (left > 0) {
                val skipped = stream.skip(left)
                if (skipped > 0) left -= skipped else if (stream.read() < 0) throw EOFException() else left--
            }
            position += count
        }
        override fun close() = stream.close()
    }

    companion object {
        const val SITE_FILE = "geosite.dat"
        const val IP_FILE = "geoip.dat"
    }
}
