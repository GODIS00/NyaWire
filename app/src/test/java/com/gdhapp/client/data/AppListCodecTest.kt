package com.gdhapp.client.data

import com.gdhapp.client.ui.main.AppEntry
import com.gdhapp.client.ui.settings.AppListCodec
import org.junit.Assert.*
import org.junit.Test

class AppListCodecTest {
    private val apps = listOf(AppEntry("com.android.chrome", "Google Chrome"), AppEntry("org.telegram.messenger", "Telegram"),
        AppEntry("app.one", "Same app"), AppEntry("app.two", "Same app"), AppEntry("com.sample.comma", "Name, with comma"))

    @Test fun acceptsMixedNamesPackagesAndCommonListSeparators() {
        val result = AppListCodec.parse("google chrome\nTelegram; com.future.app\npackage:org.telegram.messenger\ncom.a.app com.b.app", apps)
        assertEquals(setOf("com.android.chrome", "org.telegram.messenger", "com.future.app", "com.a.app", "com.b.app"), result.packages)
        assertTrue(result.unmatched.isEmpty())
    }
    @Test fun duplicateLabelsSelectEveryMatchingPackageAndKeepFullNames() {
        assertEquals(setOf("app.one", "app.two", "com.sample.comma"), AppListCodec.parse("Same app\nName, with comma", apps).packages)
    }
    @Test fun reportsUnknownLabelsWithoutGuessingAndNeverIncludesTheClient() {
        val result = AppListCodec.parse("Unknown app\nCOM.GDHAPP.CLIENT\nNyaWire\nhttps://example.org/app\nTelegram", apps)
        assertEquals(setOf("org.telegram.messenger"), result.packages)
        assertEquals(listOf("Unknown app", "https://example.org/app"), result.unmatched)
    }
    @Test fun jsonAndPlainExportsRoundTripAsPackageIdsOnly() {
        val result = AppListCodec.parse("[\"Google Chrome\",\"org.telegram.messenger\",\"Google Chrome\"]", apps)
        val exported = AppListCodec.export(result.packages)
        assertEquals("com.android.chrome\norg.telegram.messenger", exported)
        assertEquals(result.packages, AppListCodec.parse(exported, apps).packages)
    }
    @Test fun rejectsOversizedInputAndToleratesEmptyLists() {
        assertTrue(AppListCodec.parse("a".repeat(AppListCodec.MAX_LENGTH + 1), apps).tooLarge)
        assertTrue(AppListCodec.parse("\uFEFF\n\n", apps).packages.isEmpty())
    }
}
