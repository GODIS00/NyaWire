package com.gdhapp.client.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.ThemeMode

val LocalMotionEnabled = staticCompositionLocalOf { true }
val LocalHapticsEnabled = staticCompositionLocalOf { true }
private val LocalBlockColorScheme = staticCompositionLocalOf<androidx.compose.material3.ColorScheme?> { null }

@Composable
internal fun BlockColors(content: @Composable () -> Unit) {
    val colors = LocalBlockColorScheme.current
    if (colors == null) content() else MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun isDarkAppearance(settings: ClientSettings): Boolean = when (settings.theme) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun GdhappTheme(settings: ClientSettings = ClientSettings(), content: @Composable () -> Unit) {
    val dark = isDarkAppearance(settings)
    val seed = Color(settings.customAccent ?: settings.accent.argb)
    // A dark source color still needs a readable accent tone on dark surfaces.
    val accent = if (dark && seed.luminance() < .18f) lerp(seed, Color.White, .55f) else seed
    val context = LocalContext.current
    com.gdhapp.client.i18n.AppText.initialize(context, settings.language)
    val darkBackground = if (settings.amoled) Color.Black else Color(0xFF0F121B)
    val baseColors = if (settings.dynamicColors && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context).let { if (settings.amoled) it.copy(background = Color.Black) else it }
        else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(
        primary = accent, onPrimary = if (accent.luminance() > .4f) Color(0xFF102724) else Color.White,
        primaryContainer = lerp(Color(0xFF1B2430), accent, .16f), onPrimaryContainer = lerp(Color.White, accent, .65f),
        secondary = lerp(accent, Color.White, .25f), onSecondary = Color(0xFF14252A),
        secondaryContainer = Color(0xFF293441), onSecondaryContainer = Color(0xFFD8E8F3),
        tertiary = Color(0xFFC2B5EE), background = darkBackground, onBackground = Color(0xFFE9EDF5),
        surface = if (settings.amoled) Color(0xFF0B0E13) else Color(0xFF151A25), onSurface = Color(0xFFE9EDF5),
        surfaceContainerLowest = darkBackground, surfaceContainerLow = Color(0xFF171D29),
        surfaceContainer = Color(0xFF1C2331), surfaceContainerHigh = Color(0xFF242D3D), surfaceContainerHighest = Color(0xFF303A4B),
        onSurfaceVariant = Color(0xFFA4AFC1), outline = Color(0xFF69758A), outlineVariant = Color(0xFF303A4B),
        error = Color(0xFFFFB2AF), errorContainer = Color(0xFF482A31), onErrorContainer = Color(0xFFFFDAD7),
    ) else {
        val primary = lerp(accent, Color(0xFF143A35), .63f)
        lightColorScheme(
            primary = primary, onPrimary = Color.White, primaryContainer = lerp(Color.White, accent, .4f), onPrimaryContainer = Color(0xFF12352E),
            secondary = Color(0xFF52666E), secondaryContainer = Color(0xFFDCE9EF), onSecondaryContainer = Color(0xFF1F343C),
            tertiary = Color(0xFF695387), background = Color(0xFFF5F7FB), onBackground = Color(0xFF19212C),
            surface = Color.White, onSurface = Color(0xFF19212C), surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF0F3F9), surfaceContainer = Color(0xFFEAEFF7), surfaceContainerHigh = Color(0xFFE3E9F3),
            surfaceContainerHighest = Color(0xFFDDE4EF), onSurfaceVariant = Color(0xFF586577),
            outline = Color(0xFF778495), outlineVariant = Color(0xFFD7DFEB),
        )
    }
    val colors = blockColorScheme(baseColors, settings, dark)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalMotionEnabled provides settings.animations,
        LocalHapticsEnabled provides settings.haptics,
        LocalBlockColorScheme provides colors.takeUnless { it === baseColors },
        LocalDensity provides Density(density.density, density.fontScale * settings.textScale),
    ) {
        MaterialTheme(colorScheme = baseColors, shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(settings.cornerRadius.dp),
            extraLarge = RoundedCornerShape((settings.cornerRadius + 8).dp),
        ), content = content)
    }
}

internal fun blockColorScheme(base: androidx.compose.material3.ColorScheme, settings: ClientSettings, dark: Boolean): androidx.compose.material3.ColorScheme {
    if (settings.customBlockColor == null && settings.blockStyle == com.gdhapp.client.domain.model.BlockStyle.AUTO) return base
    val block = Color(settings.customBlockColor ?: if (dark) settings.blockStyle.darkArgb else settings.blockStyle.lightArgb)
    // WCAG's crossover for white/black text keeps even custom light cards readable in dark mode.
    val text = if (block.luminance() > .179f) Color.Black else Color.White
    val primaryContrast = (maxOf(block.luminance(), base.primary.luminance()) + .05f) / (minOf(block.luminance(), base.primary.luminance()) + .05f)
    val primary = if (primaryContrast >= 3f) base.primary else lerp(base.primary, text, .65f)
    return base.copy(
        primary = primary, onPrimary = if (primary.luminance() > .179f) Color.Black else Color.White,
        primaryContainer = lerp(block, primary, .16f), onPrimaryContainer = text,
        surface = block, surfaceContainerLow = block,
        surfaceContainer = lerp(block, text, .04f), surfaceContainerHigh = lerp(block, text, .08f),
        surfaceContainerHighest = lerp(block, text, .12f),
        onSurface = text, onSurfaceVariant = lerp(block, text, .80f),
        outline = lerp(block, text, .6f), outlineVariant = lerp(block, text, .25f),
    )
}
