package com.gdhapp.client.data

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import android.content.Context
import android.util.AtomicFile
import com.gdhapp.client.domain.model.SubscriptionGroup
import com.gdhapp.client.domain.model.ProbeResult
import com.gdhapp.client.domain.model.RemovedServer
import com.gdhapp.client.domain.repository.SubscriptionRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

class LocalSubscriptionRepository(context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build(),
    private val sendHwid: () -> Boolean = { true }) : SubscriptionRepository {
    private val identity by lazy { SubscriptionIdentity(context) }
    private val file = AtomicFile(context.filesDir.resolve("subscriptions.json"))
    private val gson = Gson()
    private val parser = SubscriptionParser()
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val mutableGroups = MutableStateFlow(load())
    override val groups = mutableGroups.asStateFlow()

    override suspend fun importText(text: String) {
        val trimmed = SubscriptionInput.normalize(text)
        require(trimmed.isNotEmpty()) { AppText.text(R.string.clipboard_is_empty) }
        val group = withContext(Dispatchers.IO) {
            if (trimmed.startsWith("http://", ignoreCase = true)) throw IllegalArgumentException(AppText.text(R.string.subscriptions_must_use_https))
            if (trimmed.startsWith("https://", ignoreCase = true)) download(trimmed) else parser.parse(trimmed)
        }
        change { current ->
            require(group.sourceUrl == null || current.none { it.sourceUrl == group.sourceUrl }) { AppText.text(R.string.this_subscription_has_already_been_added) }
            val uniqueGroup = group.copy(servers = group.servers.map { it.copy(id = "${group.id}:${it.id}") })
            val next = if (group.sourceUrl == null) LocalProfiles.append(current, uniqueGroup) else current + uniqueGroup.copy(servers = uniqueGroup.servers.mapIndexed { index, node ->
                node.copy(isSelected = current.none { g -> g.servers.any { it.isSelected } } && index == 0)
            })
            require(next.sumOf { it.servers.size } <= 2000) { AppText.text(R.string.limit_reached_2_000_servers) }
            next
        }
    }

    override suspend fun refresh(id: String) = refreshMutex.withLock {
        val old = groups.value.firstOrNull { it.id == id } ?: return@withLock
        val url = old.sourceUrl ?: throw IllegalArgumentException(AppText.text(R.string.this_is_a_local_profile_add_a))
        val fresh = download(url)
        change { current -> current.map { group ->
            // The running core owns a config snapshot. Refresh only the saved server list.
            if (group.id != id) group else {
                val selected = group.servers.firstOrNull { it.isSelected }
                val matching = fresh.servers.indexOfFirst { "${group.id}:${it.id}" == selected?.id }
                    .takeIf { it >= 0 } ?: fresh.servers.indexOfFirst { it.name == selected?.name }
                fresh.copy(
                    id = group.id, isPinned = group.isPinned, isExpanded = group.isExpanded,
                    title = group.title,
                    servers = fresh.servers.mapIndexed { index, node -> node.copy(
                        id = "${group.id}:${node.id}",
                        isSelected = selected != null && index == if (matching >= 0) matching else 0,
                        isFavorite = group.servers.firstOrNull { it.name == node.name }?.isFavorite == true,
                    ) },
                )
            }
        } }
    }

    override suspend fun selectNode(id: String) = change { current ->
        require(current.any { group -> group.servers.any { it.id == id } }) { AppText.text(R.string.server_not_found) }
        current.map { group -> group.copy(servers = group.servers.map { it.copy(isSelected = it.id == id) }) }
    }

    override suspend fun toggleExpanded(id: String) = change { current -> current.map {
        if (it.id == id) it.copy(isExpanded = !it.isExpanded) else it
    } }

    override suspend fun collapseAll() = change { current -> current.map { it.copy(isExpanded = false) } }

    override suspend fun togglePinned(id: String) = change { current -> current.map {
        if (it.id == id) it.copy(isPinned = !it.isPinned) else it
    }.sortedByDescending { it.isPinned } }

    override suspend fun remove(id: String) = change { current ->
        val remaining = current.filterNot { it.id == id }
        if (remaining.any { group -> group.servers.any { it.isSelected } }) remaining else {
            remaining.mapIndexed { index, group -> if (index == 0) group.copy(
                servers = group.servers.mapIndexed { nodeIndex, node -> node.copy(isSelected = nodeIndex == 0) },
            ) else group }
        }
    }

    override suspend fun removeNode(id: String): RemovedServer? {
        var removed: RemovedServer? = null
        change { current ->
            val groupIndex = current.indexOfFirst { group -> group.servers.any { it.id == id } }
            if (groupIndex < 0) current else {
                val group = current[groupIndex]
                val nodeIndex = group.servers.indexOfFirst { it.id == id }
                removed = RemovedServer(group.copy(servers = emptyList()), group.servers[nodeIndex], groupIndex, nodeIndex)
                val remaining = current.map { entry ->
                    if (entry.id == group.id) entry.copy(servers = entry.servers.filterNot { it.id == id }) else entry
                }.filterNot { it.id == group.id && it.sourceUrl == null && it.servers.isEmpty() }
                if (remaining.any { entry -> entry.servers.any { it.isSelected } }) remaining else {
                    val first = remaining.firstNotNullOfOrNull { it.servers.firstOrNull() }?.id
                    remaining.map { entry -> entry.copy(servers = entry.servers.map { it.copy(isSelected = it.id == first) }) }
                }
            }
        }
        return removed
    }

    override suspend fun restoreNode(removed: RemovedServer) = change { current ->
        if (current.any { group -> group.servers.any { it.id == removed.node.id } }) current else {
            require(current.sumOf { it.servers.size } < 2000) { AppText.text(R.string.limit_reached_2_000_servers) }
            val selected = current.any { group -> group.servers.any { it.isSelected } }
            val restored = removed.node.copy(isSelected = !selected)
            val next = current.toMutableList()
            val groupIndex = next.indexOfFirst { it.id == removed.group.id }
            if (groupIndex >= 0) {
                val group = next[groupIndex]
                val nodes = group.servers.toMutableList().apply { add(removed.nodeIndex.coerceIn(0, size), restored) }
                next[groupIndex] = group.copy(servers = nodes)
            } else next.add(removed.groupIndex.coerceIn(0, next.size), removed.group.copy(servers = listOf(restored)))
            next
        }
    }

    override suspend fun updatePings(id: String, pings: Map<String, Int>) = change { current -> current.map { group ->
        if (group.id == id) group.copy(servers = group.servers.map { node ->
            node.copy(pingMs = pings[node.id] ?: node.pingMs)
        }) else group
    } }

    override suspend fun updateProbe(nodeId: String, result: ProbeResult) = change { current -> current.map { group ->
        group.copy(servers = group.servers.map { node -> if (node.id == nodeId) node.copy(
            pingMs = result.latencyMs ?: -1, probeResults = node.probeResults.orEmpty() + (result.method to result),
        ) else node })
    } }

    override suspend fun toggleFavorite(nodeId: String) = change { current -> current.map { group ->
        group.copy(servers = group.servers.map { if (it.id == nodeId) it.copy(isFavorite = !it.isFavorite) else it })
    } }

    override suspend fun updateNode(nodeId: String, name: String, config: String) {
        val parsed = withContext(Dispatchers.IO) { parser.parse(config).servers.singleOrNull()
            ?: throw IllegalArgumentException(AppText.text(R.string.select_a_single_configuration_to_edit)) }
        require(name.isNotBlank()) { AppText.text(R.string.enter_a_server_name) }
        change { current -> current.map { group -> group.copy(servers = group.servers.map { old ->
            if (old.id == nodeId) parsed.copy(id = old.id, name = name.trim().take(200), isFavorite = old.isFavorite,
                isSelected = old.isSelected, flagResOrCode = SubscriptionParser.inferCountry(name)) else old
        }) } }
    }

    override suspend fun renameGroup(id: String, title: String) {
        require(title.isNotBlank()) { AppText.text(R.string.enter_a_subscription_name) }
        change { current -> current.map { if (it.id == id) it.copy(title = title.trim().take(100)) else it } }
    }

    private suspend fun change(transform: (List<SubscriptionGroup>) -> List<SubscriptionGroup>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = LocalProfiles.combine(transform(mutableGroups.value))
                val stream = file.startWrite()
                try {
                    stream.write(gson.toJson(next).toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                    mutableGroups.value = next
                } catch (exception: Exception) {
                    file.failWrite(stream)
                    throw exception
                }
            }
        }
    }

    private fun load(): List<SubscriptionGroup> = runCatching {
        if (!file.baseFile.exists() && !file.baseFile.resolveSibling("subscriptions.json.bak").exists()) return emptyList()
        val type = object : TypeToken<List<SubscriptionGroup>>() {}.type
        val stored: List<SubscriptionGroup> = gson.fromJson(file.readFully().toString(Charsets.UTF_8), type)
        // Migrate only our old visual fixture. Never identify a user's group by title.
        LocalProfiles.combine(stored.filterNot { it.id == "demo-runet" }.map { group -> group.copy(servers = group.servers.map { node ->
            val refreshed = runCatching { parser.parse(node.rawConfigJson).servers.single() }.getOrNull()
            node.copy(
                host = refreshed?.host ?: node.host,
                port = refreshed?.port ?: node.port,
                serverName = refreshed?.serverName ?: node.serverName,
                flagResOrCode = refreshed?.flagResOrCode?.takeUnless { it == "UN" }
                    ?: node.flagResOrCode.takeUnless { it == "UN" } ?: SubscriptionParser.inferCountry(node.name),
                // These v2.0 failures came from our missing asset installation, not the server.
                probeResults = node.probeResults.orEmpty().filterValues { result ->
                    val error = result.error.orEmpty()
                    !error.contains("failed to open geoip.dat", true) && !error.contains("failed to open geosite.dat", true)
                },
            )
        }) })
    }.getOrElse {
        // Preserve unreadable data for recovery rather than replacing it with demo data.
        runCatching { if (file.baseFile.exists()) file.baseFile.copyTo(file.baseFile.resolveSibling("subscriptions-recovery.json"), overwrite = true) }
        emptyList()
    }

    private suspend fun download(value: String): SubscriptionGroup = withContext(Dispatchers.IO) {
        val url = value.toHttpUrlOrNull()
        require(url?.isHttps == true && url.username.isEmpty() && url.password.isEmpty()) { AppText.text(R.string.enter_a_valid_https_url) }
        // Keep the provider's v2rayNG profile selection compatible with the bundled Xray core.
        val request = Request.Builder().url(url).header("User-Agent", "v2rayNG/2.3.11 NyaWire/${com.gdhapp.client.BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json, text/plain, */*")
            .apply { if (sendHwid()) identity.headers().forEach { (key, value) -> header(key, value) } }.build()
        client.newCall(request).readCancellable { response ->
            require(response.header("x-hwid-max-devices-reached") != "true" && response.header("x-hwid-limit") != "true") { AppText.text(R.string.subscription_device_limit) }
            require(response.header("x-hwid-not-supported") != "true") { AppText.text(R.string.subscription_hwid_required) }
            require(response.isSuccessful) { AppText.text(R.string.the_subscription_server_returned_http, response.code) }
            require(response.request.url.isHttps) { AppText.text(R.string.subscriptions_must_use_https) }
            val body = response.body ?: error(AppText.text(R.string.the_subscription_is_empty))
            require(!body.contentType().toString().contains("text/html", true)) { AppText.text(R.string.subscription_html_response) }
            require(body.contentLength() <= SubscriptionParser.MAX_BYTES) { AppText.text(R.string.the_subscription_exceeds_2_mb) }
            // InputStream.readNBytes is unavailable on older Android releases.
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= SubscriptionParser.MAX_BYTES) { AppText.text(R.string.the_subscription_exceeds_2_mb) }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            var group = parser.parse(bytes.toString(Charsets.UTF_8), url.toString())
            val title = decodeHeader(response.header("profile-title"))?.take(100)
            val announcement = decodeHeader(response.header("announce"))?.take(2000)
            val stats = response.header("subscription-userinfo").orEmpty().split(';').mapNotNull { pair ->
                val fields = pair.trim().split('=', limit = 2)
                if (fields.size == 2) fields[1].toLongOrNull()?.takeIf { it >= 0 }?.let { fields[0] to it } else null
            }.toMap()
            val used = (stats["upload"] ?: 0).toDouble() + (stats["download"] ?: 0).toDouble()
            val total = stats["total"] ?: 0L
            val expiry = stats["expire"]?.takeIf { it > 0 }?.let { epoch -> runCatching {
                Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            }.getOrNull() }
            group = group.copy(
                title = title ?: group.title,
                autoUpdateHours = response.header("profile-update-interval")?.toIntOrNull()?.coerceIn(1, 168) ?: group.autoUpdateHours,
                supportUrl = SubscriptionParser.safeSupportUrl(response.header("support-url")) ?: group.supportUrl,
                announcementText = announcement ?: group.announcementText,
                usedTrafficText = if (stats.isEmpty()) group.usedTrafficText else traffic(used),
                totalTrafficText = if (total == 0L) "∞" else traffic(total.toDouble()),
                expiryDateText = expiry ?: group.expiryDateText,
                trafficFraction = if (total > 0) (used / total).toFloat().coerceIn(0f, 1f) else null,
                lastUpdated = LocalDateTime.now().format(SubscriptionParser.DATE_FORMAT),
            )
            group
        }
    }

    private fun decodeHeader(value: String?): String? = value?.let {
        if (it.startsWith("base64:")) runCatching {
            Base64.getDecoder().decode(it.removePrefix("base64:")).toString(Charsets.UTF_8)
        }.getOrNull() else it
    }

    private fun traffic(bytes: Double): String {
        var amount = bytes
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var index = 0
        while (amount >= 1024 && index < units.lastIndex) { amount /= 1024; index++ }
        return if (index == 0) "${amount.toLong()} B" else String.format(AppText.locale, "%.1f %s", amount, units[index])
    }
}
