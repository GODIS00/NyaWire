package com.gdhapp.client

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gdhapp.client.data.*
import com.gdhapp.client.background.SubscriptionUpdater
import com.gdhapp.client.vpn.VpnSessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionImportTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val profile = "vless://00000000-0000-4000-8000-000000000077@example.com:443?security=none&type=tcp#Import%20test"

    @Test fun hwidIsStableAndUpdatesOnOpenRespectTheSwitch(): Unit = runBlocking {
        // Separate repository files: this test never writes a user's subscriptions.
        val context = object : android.content.ContextWrapper(app) {
            override fun getFilesDir() = app.cacheDir.resolve("subscription-import-test").apply { mkdirs() }
        }
        val calls = mutableListOf<Request>()
        var mode = "ok"
        var enabled = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .apply { if (mode == "limit") header("x-hwid-max-devices-reached", "true") }
                .body((if (mode == "html") "<html>Account page</html>" else profile).toResponseBody((if (mode == "html") "text/html" else "text/plain").toMediaType())).build()
        }.build()
        val repository = LocalSubscriptionRepository(context, client, sendHwid = { enabled })
        repository.groups.value.toList().forEach { repository.remove(it.id) }
        val preferences = app.settings
        val original = preferences.settings.value
        try {
            repository.importText("https://example.com/sub")
            val group = repository.groups.value.single()
            val hwid = calls.last().header("x-hwid")!!
            assertTrue(hwid.matches(Regex("[a-f0-9]{64}")))
            assertEquals(hwid, SubscriptionIdentity(context).hwid)
            assertEquals("Android", calls.last().header("x-device-os"))
            assertNotNull(calls.last().header("x-device-model"))
            repository.refresh(group.id)
            assertEquals(hwid, calls.last().header("x-hwid"))
            enabled = false
            repository.refresh(group.id)
            assertNull(calls.last().header("x-hwid"))
            mode = "html"
            val before = repository.groups.value
            assertTrue(runCatching { repository.refresh(group.id) }.exceptionOrNull()?.message.orEmpty().contains("Base64").not())
            assertEquals(before, repository.groups.value)
            mode = "limit"
            assertTrue(runCatching { repository.refresh(group.id) }.isFailure)
            assertEquals(before, repository.groups.value)
            mode = "ok"
            preferences.update { it.copy(refreshOnOpen = true, autoUpdateSubscriptions = false) }
            val running = VpnSessionStore().apply { setActiveNode(repository.groups.value.single().servers.single()); update(com.gdhapp.client.domain.model.VpnState.Connected) }
            val updater = SubscriptionUpdater(repository, preferences, running)
            val progress = mutableListOf<Set<String>>()
            val count = calls.size
            assertEquals(0, updater.refreshOnOpen { progress += it })
            assertEquals(count + 1, calls.size)
            assertEquals(setOf(group.id), progress.first())
            assertTrue(progress.last().isEmpty())
            preferences.update { it.copy(refreshOnOpen = false) }
            updater.refreshOnOpen { error("Disabled refresh must not show a dialog") }
            assertEquals(count + 1, calls.size)
        } finally {
            repository.groups.value.toList().forEach { repository.remove(it.id) }
            preferences.update { original }
        }
    }
}
