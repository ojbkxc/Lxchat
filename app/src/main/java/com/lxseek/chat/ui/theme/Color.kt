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

enum class ColorSchemePreset { MIDNIGHT, NORDIC, FOREST, SUNSET, ROSE, LAVENDER, SLATE, OCEAN }

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

/** AMOLED true-black color scheme for OLED power savings.
 *
 *  Background and surface are pure #000000 so OLED panels can power off those pixels.
 *  Foreground tones are tuned for WCAG AA contrast against pure black. Containers use a
 *  near-black (#1D1B26) so elevated surfaces remain distinguishable without grey elevation. */
fun amoledDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1D1B26),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1D1B26),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF1D1B26),
    onTertiaryContainer = Color(0xFFFFD8E4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF1D1B26),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE3E3E3),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF6750A4),
    surfaceTint = Color(0xFF6750A4),
)
