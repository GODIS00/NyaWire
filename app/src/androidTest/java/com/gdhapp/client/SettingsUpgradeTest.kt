package com.gdhapp.client

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gdhapp.client.data.SettingsRepository
import com.gdhapp.client.domain.model.AppLanguage
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.ProbeConnectionMode
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsUpgradeTest {
    @Test fun upgradeEnablesNativeTunWithoutResettingUserSettingsAndPersistsCompatibilityChoice() {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val preferenceName = "upgrade-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
        }
        val preferences = context.getSharedPreferences("client_settings", Context.MODE_PRIVATE)
        try {
            val legacy = Gson().toJsonTree(ClientSettings(mtu = 1420, language = AppLanguage.ENGLISH,
                probeConnectionMode = ProbeConnectionMode.KEEP_ALIVE)).asJsonObject
            legacy.remove("xrayCompatibilityMode")
            legacy.remove("localSocksEnabled"); legacy.remove("customAccent")
            legacy.remove("backgroundPictures"); legacy.remove("sendHwid")
            listOf("connectionMode", "routingMode", "fragmentEnabled", "autoUpdateSubscriptions", "connectOnOpen", "connectOnBoot").forEach { legacy.remove(it) }
            assertTrue(preferences.edit().putString("settings", legacy.toString()).commit())
            val upgraded = SettingsRepository(context)
            assertFalse(upgraded.settings.value.xrayCompatibilityMode)
            assertFalse(upgraded.settings.value.localSocksEnabled)
            assertNull(upgraded.settings.value.customAccent)
            assertTrue(upgraded.settings.value.backgroundPictures)
            assertTrue(upgraded.settings.value.sendHwid)
            assertEquals(com.gdhapp.client.domain.model.ConnectionMode.VPN, upgraded.settings.value.connectionMode)
            assertEquals(com.gdhapp.client.domain.model.RoutingMode.PROFILE, upgraded.settings.value.routingMode)
            assertTrue(upgraded.settings.value.autoUpdateSubscriptions)
            assertFalse(upgraded.settings.value.fragmentEnabled)
            assertFalse(upgraded.settings.value.connectOnBoot); assertFalse(upgraded.settings.value.connectOnOpen)
            assertEquals(1420, upgraded.settings.value.mtu)
            assertEquals(AppLanguage.ENGLISH, upgraded.settings.value.language)
            assertEquals(ProbeConnectionMode.KEEP_ALIVE, upgraded.settings.value.probeConnectionMode)
            upgraded.update { it.copy(xrayCompatibilityMode = true, backgroundPictures = false) }
            assertTrue(SettingsRepository(context).settings.value.xrayCompatibilityMode)
            assertFalse(SettingsRepository(context).settings.value.backgroundPictures)
            upgraded.update { it.copy(xrayCompatibilityMode = false) }
            val reloaded = SettingsRepository(context).settings.value
            assertFalse(reloaded.xrayCompatibilityMode)
            assertEquals(1420, reloaded.mtu)
            upgraded.resetAppearance()
            assertTrue(SettingsRepository(context).settings.value.backgroundPictures)
            assertEquals(1420, SettingsRepository(context).settings.value.mtu)
        } finally {
            preferences.edit().clear().commit()
            com.gdhapp.client.i18n.AppText.setLanguage(app.settings.settings.value.language)
        }
    }
}
