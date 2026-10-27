package com.gdhapp.client.data.backup

import com.gdhapp.client.GdhappApplication
import com.gdhapp.client.data.SubscriptionParser
import com.gdhapp.client.domain.model.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class BackupSnapshot(val schema: Int = 1, val createdAt: Long = System.currentTimeMillis(),
    val settings: ClientSettings, val groups: List<SubscriptionGroup>, val format: String = "NyaWireBackup")

class BackupManager(private val app: GdhappApplication) {
    private val gson = Gson()

    suspend fun create(password: CharArray? = null): ByteArray = withContext(Dispatchers.Default) {
        val plain = gson.toJson(BackupSnapshot(settings = app.settings.settings.value, groups = app.subscriptions.groups.value)).toByteArray(Charsets.UTF_8)
        if (plain.size > BackupCipher.MAX_BYTES) { plain.fill(0); password?.fill('\u0000'); throw BackupException(BackupProblem.TOO_LARGE) }
        if (password == null) return@withContext plain
        try { BackupCipher.encrypt(plain, password) } finally { plain.fill(0); password.fill('\u0000') }
    }

    suspend fun read(bytes: ByteArray, password: CharArray? = null): BackupSnapshot = withContext(Dispatchers.Default) {
        if (bytes.size > BackupCipher.MAX_BYTES) { password?.fill('\u0000'); throw BackupException(BackupProblem.TOO_LARGE) }
        val encrypted = BackupCipher.isEncrypted(bytes)
        val plain = try { if (encrypted) BackupCipher.decrypt(bytes, password ?: charArrayOf()) else bytes.copyOf() }
            finally { password?.fill('\u0000') }
        try {
            val json = JsonParser.parseString(plain.toString(Charsets.UTF_8)).asJsonObject
            require(encrypted || json.get("format")?.asString == "NyaWireBackup")
            if (json.get("schema")?.asInt != 1) throw BackupException(BackupProblem.UNSUPPORTED)
            require(json.get("settings")?.isJsonObject == true && json.get("groups")?.isJsonArray == true)
            validate(gson.fromJson(json, BackupSnapshot::class.java).copy(format = "NyaWireBackup"))
        } catch (error: BackupException) { throw error }
        catch (_: Exception) { throw BackupException(BackupProblem.DAMAGED) }
        finally { plain.fill(0) }
    }

    private fun validate(snapshot: BackupSnapshot): BackupSnapshot {
        require(snapshot.groups.size <= 2000 && snapshot.groups.sumOf { it.servers.size.toLong() } <= 2000)
        val groupIds = mutableSetOf<String>()
        val nodeIds = mutableSetOf<String>()
        val parser = SubscriptionParser()
        var selected = false
        val groups = snapshot.groups.map { group ->
            require(group.id.isNotBlank() && group.id.length <= 500 && groupIds.add(group.id))
            require(group.title.isNotBlank() && group.title.length <= 200)
            group.sourceUrl?.let { url -> require(url.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } == true) }
            group.copy(servers = group.servers.map { node ->
                require(node.id.isNotBlank() && node.id.length <= 500 && nodeIds.add(node.id) && node.name.isNotBlank())
                val parsed = parser.parse(node.rawConfigJson).servers.single()
                val use = node.isSelected && !selected
                selected = selected || use
                parsed.copy(id = node.id, name = node.name.take(200), isSelected = use, isFavorite = node.isFavorite)
            })
        }
        val first = groups.firstNotNullOfOrNull { it.servers.firstOrNull() }?.id
        val next = if (selected) groups else groups.map { g -> g.copy(servers = g.servers.map { it.copy(isSelected = it.id == first) }) }
        val backup = snapshot.settings.backupServerId?.takeIf { it in nodeIds }
        return snapshot.copy(groups = next, settings = snapshot.settings.copy(backupServerId = backup,
            failoverEnabled = snapshot.settings.failoverEnabled && backup != null))
    }

    suspend fun restore(snapshot: BackupSnapshot) {
        app.vpnSession.coreMutex.withLock {
            if (app.vpnSession.state.value == VpnState.Connected || app.vpnSession.state.value == VpnState.Connecting)
                throw BackupException(BackupProblem.VPN_ACTIVE)
            app.vpnSession.restoringBackup = true
            try { withContext(NonCancellable + Dispatchers.IO) {
                app.subscriptions.restore(snapshot.groups) { app.settings.restore(snapshot.settings) }
            } } finally { app.vpnSession.restoringBackup = false }
        }
    }
}
