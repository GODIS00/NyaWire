package com.gdhapp.client.ui.settings

import com.gdhapp.client.ui.main.AppEntry
import com.google.gson.JsonParser
import java.util.Locale

data class AppListImport(val packages: Set<String>, val unmatched: List<String>, val tooLarge: Boolean = false)

/** Clipboard lists accept full package IDs or exact installed app labels. Ambiguous labels select all matches. */
object AppListCodec {
    const val MAX_LENGTH = 65536
    private const val SELF = "com.gdhapp.client"
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private fun key(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun parse(text: String, apps: List<AppEntry>): AppListImport {
        if (text.length > MAX_LENGTH) return AppListImport(emptySet(), emptyList(), tooLarge = true)
        val input = text.trim().removePrefix("\uFEFF")
        val packages = linkedSetOf<String>()
        val unmatched = linkedSetOf<String>()
        val byPackage = apps.associateBy { key(it.packageName) }
        val byLabel = apps.groupBy { key(it.label) }
        fun resolve(raw: String): Boolean {
            val token = raw.trim().removePrefix("- ").removePrefix("• ").trim().trim('"', '\'').removePrefix("package:").trim()
            if (token.isBlank()) return true
            if (token.equals(SELF, true) || token.equals("NyaWire", true) || token.equals("GDHAPP", true)) return true
            val installed = byPackage[key(token)]?.let { listOf(it) } ?: byLabel[key(token)]
            if (installed != null) {
                installed.filter { it.packageName != SELF }.forEach { packages += it.packageName }
                return true
            }
            if (packagePattern.matches(token)) { packages += token; return true }
            return false
        }
        val json = input.takeIf { it.startsWith('[') }?.let { runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull() }
        val lines = if (json != null) json.map { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else it.toString() }
            else input.lines()
        lines.forEach { line ->
            if (!resolve(line)) line.split(Regex("[,;]+")).forEach { part ->
                if (!resolve(part)) {
                    val tokens = part.trim().split(Regex("\\s+"))
                    if (tokens.size > 1 && tokens.all { packagePattern.matches(it.removePrefix("package:")) }) tokens.forEach { resolve(it) }
                    else unmatched += part.trim()
                }
            }
        }
        return AppListImport(packages, unmatched.filter { it.isNotBlank() })
    }

    fun export(packages: Set<String>): String = packages.filter { !it.equals(SELF, true) && packagePattern.matches(it) }.sorted().joinToString("\n")
}
