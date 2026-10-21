package com.gdhapp.client

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gdhapp.client.data.LocalSubscriptionRepository
import com.gdhapp.client.data.SubscriptionParser
import com.gdhapp.client.domain.model.SubscriptionGroup
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryMigrationTest {
    private fun context(): ContextWrapper {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val directory = File(app.cacheDir, "migration-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(app) { override fun getFilesDir() = directory }
    }

    @Test fun aFreshInstallationStartsEmpty() {
        val context = context()
        try { assertTrue(LocalSubscriptionRepository(context).groups.value.isEmpty()) }
        finally { context.filesDir.deleteRecursively() }
    }

    @Test fun clearsOldMissingGeodataFailuresAndRetainsOtherResults() {
        val context = context()
        try {
            val node = SubscriptionParser().parse("vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=none&type=tcp#User").servers.single().copy(
                isSelected = true,
                probeResults = mapOf(
                    com.gdhapp.client.domain.model.ProbeMethod.HTTP_GET to com.gdhapp.client.domain.model.ProbeResult(com.gdhapp.client.domain.model.ProbeMethod.HTTP_GET, error = "config error > failed to open geoip.dat > no such file or directory"),
                    com.gdhapp.client.domain.model.ProbeMethod.TCP to com.gdhapp.client.domain.model.ProbeResult(com.gdhapp.client.domain.model.ProbeMethod.TCP, latencyMs = 17),
                    com.gdhapp.client.domain.model.ProbeMethod.HTTP_HEAD to com.gdhapp.client.domain.model.ProbeResult(com.gdhapp.client.domain.model.ProbeMethod.HTTP_HEAD, error = "HTTP 405"),
                ))
            val group = SubscriptionGroup("user-owned", "User", lastUpdated = "today", servers = listOf(node))
            context.filesDir.resolve("subscriptions.json").writeText(Gson().toJson(listOf(group)))
            val restored = LocalSubscriptionRepository(context).groups.value.single().servers.single()
            assertEquals(node.rawConfigJson, restored.rawConfigJson)
            assertEquals(node.id, restored.id); assertTrue(restored.isSelected)
            assertFalse(restored.probeResults.containsKey(com.gdhapp.client.domain.model.ProbeMethod.HTTP_GET))
            assertEquals(17, restored.probeResults.getValue(com.gdhapp.client.domain.model.ProbeMethod.TCP).latencyMs)
            assertEquals("HTTP 405", restored.probeResults.getValue(com.gdhapp.client.domain.model.ProbeMethod.HTTP_HEAD).error)
        } finally { context.filesDir.deleteRecursively() }
    }

    @Test fun migrationRemovesOnlyTheOldFixtureAndRepairsUserEndpoints() {
        val context = context()
        try {
            val raw = """{"remarks":"🇳🇱 Пользовательский сервер","outbounds":[{"protocol":"hysteria","settings":{"version":2,"address":"user.example","port":443},"streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2,"auth":"not-a-real-credential"}}}]}"""
            val userNode = SubscriptionParser().parse(raw).servers.single().copy(id = "user-node", host = null, port = null, isSelected = true)
            val user = SubscriptionGroup("user-group", "RUnet", lastUpdated = "19.10.2026", servers = listOf(userNode))
            val demo = user.copy(id = "demo-runet")
            val legacy = JsonParser.parseString(Gson().toJson(listOf(demo, user))).asJsonArray
            legacy.forEach { group -> group.asJsonObject.getAsJsonArray("servers").forEach {
                it.asJsonObject.remove("probeResults"); it.asJsonObject.remove("serverName"); it.asJsonObject.remove("isFavorite")
            } }
            context.filesDir.resolve("subscriptions.json").writeText(legacy.toString())
            val restored = LocalSubscriptionRepository(context).groups.value
            assertEquals(1, restored.size); assertEquals("user-group", restored.single().id)
            assertEquals("RUnet", restored.single().title)
            val node = restored.single().servers.single()
            assertEquals("user-node", node.id); assertTrue(node.isSelected)
            assertEquals("user.example", node.host); assertEquals(443, node.port); assertEquals("NL", node.flagResOrCode)
            assertTrue(node.probeResults.isEmpty())
        } finally { context.filesDir.deleteRecursively() }
    }
}
