package com.lxseek.chat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.hct.Hct

enum class SchemeStyle { TONAL_SPOT, EXPRESSIVE, VIBRANT, NEUTRAL }

enum class ColorSchemePreset { MINIMAL, MIDNIGHT, NORDIC, FOREST, SUNSET, ROSE, LAVENDER, SLATE, OCEAN }

private val seedColors = mapOf(
    ColorSchemePreset.MIDNIGHT to 0xFF1A237E,
    ColorSchemePreset.NORDIC   to 0xFF546E7A,
    ColorSchemePreset.FOREST   to 0xFF2E7D32,
    ColorSchemePreset.SUNSET   to 0xFFE65100,
    ColorSchemePreset.ROSE     to 0xFFAD1457,
    ColorSchemePreset.LAVENDER to 0xFF7B1FA2,
    ColorSchemePreset.SLATE    to 0xFF455A64,
    ColorSchemePreset.OCEAN    to 0xFF0277BD,
)

fun colorSchemeForPreset(
    preset: ColorSchemePreset,
    style: SchemeStyle = SchemeStyle.TONAL_SPOT,
    isDark: Boolean = false
): ColorScheme {
    if (preset == ColorSchemePreset.MINIMAL) {
        return if (isDark) minimalDarkColorScheme() else minimalLightColorScheme()
    }
    val seedArgb = seedColors[preset]!!.toInt()
    val hct = Hct.fromInt(seedArgb)
    val scheme: DynamicScheme = when (style) {
        SchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, isDark, 0.0)
        SchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, isDark, 0.0)
        SchemeStyle.VIBRANT   -> SchemeVibrant(hct, isDark, 0.0)
        SchemeStyle.NEUTRAL   -> SchemeNeutral(hct, isDark, 0.0)
    }
    return scheme.toColorScheme()
}

private fun DynamicScheme.toColorScheme(): ColorScheme {
    val c = { argb: Int -> Color(argb) }
    return if (isDark) darkColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        outline = c(outline), outlineVariant = c(outlineVariant),
    ) else lightColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        outline = c(outline), outlineVariant = c(outlineVariant),
    )
}

/** Minimal light scheme — ChatGPT / DeepSeek style.
 *
 *  Near-white surfaces, near-black ink, and a single restrained indigo accent.
 *  Colour is treated as neutral scaffolding rather than a brand statement, which
 *  deliberately breaks away from the AI-blue-purple homogenisation of the older
 *  dynamic presets. */
fun minimalLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF4D6BFE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEFFF),
    onPrimaryContainer = Color(0xFF1C2B8C),
    secondary = Color(0xFF6B6B6F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F2),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF5A5A60),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDEDEF),
    onTertiaryContainer = Color(0xFF1C1C1E),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFF7F7F8),
    onSurfaceVariant = Color(0xFF6B6B6F),
    outline = Color(0xFFE5E5EA),
    outlineVariant = Color(0xFFEEEEF1),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF9DA6FF),
    surfaceTint = Color(0xFF4D6BFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color(0xFFF5F5F6),
    surfaceContainerHigh = Color(0xFFEFEFF1),
    surfaceContainerHighest = Color(0xFFE9E9EC),
)

/** Minimal dark scheme — the light scheme's neutral mirror: near-black grey surfaces
 *  (not blue-purple) with the same restrained indigo accent. */
fun minimalDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF9DA6FF),
    onPrimary = Color(0xFF1C2B8C),
    primaryContainer = Color(0xFF2E3B7A),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC6C6CC),
    onSecondary = Color(0xFF2E2E31),
    secondaryContainer = Color(0xFF3A3A3E),
    onSecondaryContainer = Color(0xFFE2E2E6),
    tertiary = Color(0xFFC6C6CC),
    onTertiary = Color(0xFF2E2E31),
    tertiaryContainer = Color(0xFF3A3A3E),
    onTertiaryContainer = Color(0xFFE2E2E6),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF131313),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF131313),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFC6C6CC),
    outline = Color(0xFF3F3F42),
    outlineVariant = Color(0xFF38383B),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF131313),
    inversePrimary = Color(0xFF4D6BFE),
    surfaceTint = Color(0xFF9DA6FF),
    surfaceContainerLowest = Color(0xFF101010),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF2E2E2E),
)

/** AMOLED true-black color scheme for OLED power savings.
 *
 *  Background and surface are pure #000000 so OLED panels can power off those pixels.
 *  The accent uses a low-saturation "misty indigo" (grey-tinted, not vivid blue) so it stays
 *  easy on the eyes at night, and containers stay near-black to avoid glare. Foreground tones
 *  are tuned for WCAG AA contrast against pure black. */
fun amoledDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF8E97C9),
    onPrimary = Color(0xFF14151F),
    primaryContainer = Color(0xFF2A2E45),
    onPrimaryContainer = Color(0xFFD2D5EA),
    secondary = Color(0xFFB8BCC8),
    onSecondary = Color(0xFF16171C),
    secondaryContainer = Color(0xFF26282F),
    onSecondaryContainer = Color(0xFFDDE0EA),
    tertiary = Color(0xFFBFBFBF),
    onTertiary = Color(0xFF171717),
    tertiaryContainer = Color(0xFF262626),
    onTertiaryContainer = Color(0xFFE6E6E6),
    error = Color(0xFFE88484),
    onError = Color(0xFF2A0B0B),
    errorContainer = Color(0xFF3A1616),
    onErrorContainer = Color(0xFFF6DADA),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE0E0E3),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE0E0E3),
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFC2C2C8),
    outline = Color(0xFF8A8A90),
    outlineVariant = Color(0xFF3A3A40),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE0E0E3),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF8E97C9),
    surfaceTint = Color(0xFF8E97C9),
)
