package com.gdhapp.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import com.gdhapp.client.domain.model.BlockStyle
import com.gdhapp.client.domain.model.ClientSettings
import org.junit.Assert.*
import org.junit.Test

class BlockColorsTest {
    @Test fun `automatic cards preserve the whole Material You scheme`() {
        val original = lightColorScheme()
        assertSame(original, blockColorScheme(original, ClientSettings(), false))
    }

    @Test fun `preset and custom cards keep readable text in either theme`() {
        for (dark in listOf(true, false)) {
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val options = BlockStyle.entries.map { ClientSettings(blockStyle = it) } +
                listOf(0xFF000000L, 0xFFFFFFFFL, 0xFF777777L, 0xFFFF00FFL, 0xFF00FF00L).map { ClientSettings(customBlockColor = it) }
            for (settings in options) {
                val scheme = blockColorScheme(base, settings, dark)
                val a = scheme.surfaceContainerLow.luminance()
                val b = scheme.onSurface.luminance()
                assertTrue("$settings: unreadable cards", (maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f)
                assertEquals(base.background, scheme.background)
                assertEquals(base.onBackground, scheme.onBackground)
            }
        }
    }
}
