package com.gdhapp.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.PowerBorder

@Composable
internal fun powerGradientColors(settings: ClientSettings): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return when (settings.powerBorder) {
        PowerBorder.NEON -> listOf(Color(0xFF538AFF), Color(0xFFBB69FF))
        PowerBorder.AURORA -> listOf(Color(0xFF42DFBD), Color(0xFF538AFF), Color(0xFFF194D8))
        PowerBorder.ACCENT -> listOf(scheme.primary, scheme.tertiary)
        PowerBorder.SUNSET -> listOf(Color(0xFFFFAD65), Color(0xFFEF658D))
        PowerBorder.CUSTOM -> listOf(Color(settings.powerGradientStart), Color(settings.powerGradientEnd))
    }
}
