package com.gdhapp.client

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gdhapp.client.data.*
import com.gdhapp.client.data.backup.*
import com.gdhapp.client.domain.model.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private fun group() = SubscriptionParser().parse("vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=none&type=tcp#Primary\nvless://00000000-0000-4000-8000-000000000002@192.0.2.2:443?security=none&type=tcp#Reserve")
    @Test fun unprotectedCopyRestoresWithoutAskingForAPassword() = runBlocking {
        val oldGroups = app.subscriptions.groups.value
        val oldSettings = app.settings.settings.value
        val group = group()
        try {
            app.subscriptions.restore(listOf(group)) { app.settings.restore(oldSettings.copy(directDomains = "domain:example.org")) }
            val bytes = app.backups.create()
            assertFalse(BackupCipher.isEncrypted(bytes))
            assertEquals("NyaWireBackup", com.google.gson.JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.get("format").asString)
            val preview = app.backups.read(bytes)
            app.subscriptions.restore(emptyList()) { app.settings.restore(oldSettings) }
            app.backups.restore(preview)
            assertEquals(group.servers.map { it.rawConfigJson }, app.subscriptions.groups.value.single().servers.map { it.rawConfigJson })
            assertEquals("domain:example.org", app.settings.settings.value.directDomains)
            val before = app.settings.settings.value
            try { app.backups.read("""{"schema":1,"settings":{},"groups":[]}""".toByteArray()); fail("Unrelated JSON accepted") }
            catch (error: BackupException) { assertEquals(BackupProblem.DAMAGED, error.problem) }
            assertEquals(before, app.settings.settings.value)
        } finally { app.subscriptions.restore(oldGroups) { app.settings.restore(oldSettings) } }
    }

    @Test fun encryptedCopiesFromThePreviousVersionStillOpen() = runBlocking {
        val payload = Gson().toJsonTree(BackupSnapshot(settings = app.settings.settings.value, groups = listOf(group()))).asJsonObject
        payload.remove("format")
        val bytes = BackupCipher.encrypt(payload.toString().toByteArray(), "password1".toCharArray())
        assertTrue(BackupCipher.isEncrypted(bytes))
        assertEquals(2, app.backups.read(bytes, "password1".toCharArray()).groups.single().servers.size)
        try { app.backups.read(bytes); fail("Encrypted file opened without a password") }
        catch (error: BackupException) { assertEquals(BackupProblem.PASSWORD, error.problem) }
    }
    @Test fun encryptedCopyRestoresServersSettingsAndRoutingAcrossRepositories() = runBlocking {
        val oldGroups = app.subscriptions.groups.value
        val oldSettings = app.settings.settings.value
        val group = group().let { it.copy(servers = it.servers.mapIndexed { i, n -> n.copy(isSelected = i == 0, isFavorite = i == 1) }) }
        val wanted = oldSettings.copy(theme = ThemeMode.LIGHT, backgroundScene = BackgroundScene.RAIN,
            directDomains = "domain:example.org", perAppMode = PerAppMode.EXCLUDE, appPackages = setOf("org.example.app"),
            failoverEnabled = true, backupServerId = group.servers[1].id, connectOnOpen = false, refreshOnOpen = false)
        try {
            app.subscriptions.restore(listOf(group)) { app.settings.restore(wanted) }
            val bytes = app.backups.create("секрет 123".toCharArray())
            app.subscriptions.restore(emptyList()) { app.settings.restore(oldSettings.copy(theme = ThemeMode.DARK)) }
            val before = app.settings.settings.value
            try { app.backups.read(bytes, "не тот пароль".toCharArray()); fail("Accepted wrong password") }
            catch (error: BackupException) { assertEquals(BackupProblem.PASSWORD, error.problem) }
            assertEquals(before, app.settings.settings.value); assertTrue(app.subscriptions.groups.value.isEmpty())
            val preview = app.backups.read(bytes, "секрет 123".toCharArray())
            assertTrue(app.subscriptions.groups.value.isEmpty())
            app.backups.restore(preview)
            val stored = LocalSubscriptionRepository(app).groups.value.single()
            assertEquals(group.servers.map { it.id }, stored.servers.map { it.id })
            assertEquals(group.servers.map { it.rawConfigJson }, stored.servers.map { it.rawConfigJson })
            assertTrue(stored.servers[1].isFavorite); assertTrue(stored.servers[0].isSelected)
            val restored = SettingsRepository(app).settings.value
            assertEquals(wanted.theme, restored.theme); assertEquals(wanted.backgroundScene, restored.backgroundScene)
            assertEquals(wanted.directDomains, restored.directDomains); assertEquals(wanted.appPackages, restored.appPackages)
            assertTrue(restored.failoverEnabled); assertEquals(group.servers[1].id, restored.backupServerId)
        } finally { app.subscriptions.restore(oldGroups) { app.settings.restore(oldSettings) } }
    }
    @Test fun invalidRestoreAndActiveVpnDoNotChangeData() = runBlocking {
        val before = app.subscriptions.groups.value
        val settings = app.settings.settings.value
        val broken = BackupSnapshot(settings = settings, groups = listOf(group().copy(servers = group().servers.map { it.copy(rawConfigJson = "invalid") })))
        val bytes = BackupCipher.encrypt(Gson().toJson(broken).toByteArray(), "password1".toCharArray())
        try { app.backups.read(bytes, "password1".toCharArray()); fail("Invalid configs accepted") }
        catch (error: BackupException) { assertEquals(BackupProblem.DAMAGED, error.problem) }
        app.vpnSession.update(VpnState.Connected)
        try {
            try { app.backups.restore(BackupSnapshot(settings = settings, groups = emptyList())); fail("Restored with active VPN") }
            catch (error: BackupException) { assertEquals(BackupProblem.VPN_ACTIVE, error.problem) }
            assertEquals(before, app.subscriptions.groups.value); assertEquals(settings, app.settings.settings.value)
        } finally { app.vpnSession.update(VpnState.Disconnected) }
    }
    @Test fun failedSettingsWriteRollsBackTheServerFile() = runBlocking {
        val before = app.subscriptions.groups.value
        try { app.subscriptions.restore(listOf(group())) { error("Simulated disk failure") }; fail("No failure") }
        catch (_: IllegalStateException) { }
        assertEquals(before, app.subscriptions.groups.value)
        assertEquals(before.map { it.id }, LocalSubscriptionRepository(app).groups.value.map { it.id })
    }
}
