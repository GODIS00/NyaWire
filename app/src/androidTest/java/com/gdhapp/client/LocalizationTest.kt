package com.gdhapp.client

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gdhapp.client.data.SettingsRepository
import com.gdhapp.client.domain.model.AppLanguage
import com.gdhapp.client.domain.model.ProbeConnectionMode
import com.gdhapp.client.domain.model.ThemeMode
import com.gdhapp.client.i18n.AppText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    @Test fun languageAndHttpModePersistAndAllResourcesExistInBothLanguages() {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val original = app.settings.settings.value
        val subscriptionsBefore = app.filesDir.resolve("subscriptions.json").takeIf { it.exists() }?.readBytes()
        try {
            for (language in listOf(AppLanguage.RUSSIAN, AppLanguage.ENGLISH)) {
                app.settings.update { it.copy(language = language, probeConnectionMode = ProbeConnectionMode.KEEP_ALIVE) }
                val reloaded = SettingsRepository(app).settings.value
                assertEquals(language, reloaded.language)
                assertEquals(ProbeConnectionMode.KEEP_ALIVE, reloaded.probeConnectionMode)
                assertEquals(if (language == AppLanguage.RUSSIAN) "Настройки" else "Settings", AppText.text(R.string.settings))
                assertEquals(if (language == AppLanguage.RUSSIAN) "Тёмная" else "Dark", ThemeMode.DARK.label)
                assertEquals(if (language == AppLanguage.RUSSIAN) "125 мс" else "125 ms", AppText.text(R.string.ms, 125))
                R.string::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }.forEach { field ->
                    assertTrue("Missing ${field.name} for $language", AppText.text(field.getInt(null)).isNotBlank())
                }
            }
            assertArrayEquals(subscriptionsBefore, app.filesDir.resolve("subscriptions.json").takeIf { it.exists() }?.readBytes())
        } finally { app.settings.update { original } }
    }
}
