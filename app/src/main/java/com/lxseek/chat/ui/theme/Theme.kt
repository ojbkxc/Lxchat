package com.lxseek.chat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ThemeMode { LIGHT, DARK, AMOLED, FOLLOW_DEVICE }

/**
 * Returns the effective [FontFamily] for non-mono typography based on the font preference.
 */
@Composable
private fun effectiveFontFamily(
    fontPreference: String,
    customFontPath: String
): FontFamily = produceState<FontFamily>(
    initialValue = FontFamily.Default,
    fontPreference,
    customFontPath,
) {
    value = when (fontPreference) {
        "custom" -> withContext(Dispatchers.IO) {
            val file = File(customFontPath)
            if (file.exists()) {
                runCatching {
                    FontFamily(
                        Font(file, FontWeight.Normal),
                        Font(file, FontWeight.Medium),
                        Font(file, FontWeight.Bold),
                    )
                }.getOrDefault(FontFamily.Default)
            } else {
                FontFamily.Default
            }
        }
        else -> FontFamily.Default
    }
}.value

/**
 * Builds the [Typography] with the given [FontFamily] replacing all non-mono styles.
 */
private fun typographyWithFont(family: FontFamily): Typography {
    fun TextStyle.withFamily(f: FontFamily) = copy(fontFamily = f)
    return Typography.copy(
        displayLarge = Typography.displayLarge.withFamily(family),
        displayMedium = Typography.displayMedium.withFamily(family),
        displaySmall = Typography.displaySmall.withFamily(family),
        headlineLarge = Typography.headlineLarge.withFamily(family),
        headlineMedium = Typography.headlineMedium.withFamily(family),
        headlineSmall = Typography.headlineSmall.withFamily(family),
        titleLarge = Typography.titleLarge.withFamily(family),
        titleMedium = Typography.titleMedium.withFamily(family),
        titleSmall = Typography.titleSmall.withFamily(family),
        bodyLarge = Typography.bodyLarge.withFamily(family),
        bodyMedium = Typography.bodyMedium.withFamily(family),
        bodySmall = Typography.bodySmall.withFamily(family),
        labelLarge = Typography.labelLarge.withFamily(family),
        labelMedium = Typography.labelMedium.withFamily(family),
        labelSmall = Typography.labelSmall.withFamily(family),
    )
}

@Composable
fun LxChatTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_DEVICE,
    colorSchemePreset: ColorSchemePreset = ColorSchemePreset.MINIMAL,
    schemeStyle: SchemeStyle = SchemeStyle.TONAL_SPOT,
    dynamicColor: Boolean = false,
    fontPreference: String = "app_default",
    customFontPath: String = "",
    chatFontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AMOLED -> true
        ThemeMode.FOLLOW_DEVICE -> systemDark
    }

    val colorScheme = when {
        // AMOLED is an explicit true-black choice; it overrides dynamic color and presets so the
        // pure-black background/surface are preserved on OLED panels.
        themeMode == ThemeMode.AMOLED -> remember { amoledDarkColorScheme() }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> remember(colorSchemePreset, schemeStyle, darkTheme) {
            colorSchemeForPreset(colorSchemePreset, schemeStyle, darkTheme)
        }
    }

    val fontFamily = effectiveFontFamily(fontPreference, customFontPath)
    chatFontFamily = fontFamily
    // Apply the user-adjustable chat font scale to all ChatType styles (same pattern
    // as chatFontFamily: a top-level var read by ChatType property getters).
    com.lxseek.chat.ui.theme.chatFontScale = chatFontScale
    val typography = remember(fontFamily) { typographyWithFont(fontFamily) }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
