package com.gdhapp.client

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.data.LocalSubscriptionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in provider regression; no remote subscriptions are bundled or added at app startup. */
@RunWith(AndroidJUnit4::class)
class HavenSubscriptionTest {
    @Test fun importsAndRefreshesActualProviderUrl(): Unit = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("subscriptionUrl")
        assumeTrue("Pass -e subscriptionUrl with the provider's HTTPS URL", url != null)
        assumeTrue(android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = app.cacheDir.resolve("haven-provider-import-test").apply { mkdirs() }
        }
        val repository = LocalSubscriptionRepository(context)
        val previousIds = repository.groups.value.map { it.id }.toSet()
        try {
            repository.importText(url!!)
            val group = repository.groups.value.single { it.id !in previousIds }
            assertEquals("Haven vpn", group.title)
            assertEquals(url, group.sourceUrl)
            assertEquals(9, group.servers.size)
            assertTrue(group.servers.all { it.host != null && it.port != null })
            assertTrue(group.announcementText.isNotBlank())
            assertNotNull(group.supportUrl)
            repository.refresh(group.id)
            val refreshed = repository.groups.value.single { it.id == group.id }
            assertEquals(group.servers.map { it.id }, refreshed.servers.map { it.id })
            assertEquals(group.servers.single { it.isSelected }.id, refreshed.servers.single { it.isSelected }.id)
        } finally {
            repository.groups.value.filter { it.id !in previousIds }.forEach { repository.remove(it.id) }
        }
    }
}
