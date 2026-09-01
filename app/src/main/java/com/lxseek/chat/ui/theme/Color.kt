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
    ColorSchemePreset.MIDNIGHT to 0xFF303F9F,
    ColorSchemePreset.NORDIC   to 0xFF607D8B,
    ColorSchemePreset.FOREST   to 0xFF388E3C,
    ColorSchemePreset.SUNSET   to 0xFFEF6C00,
    ColorSchemePreset.ROSE     to 0xFFD81B60,
    ColorSchemePreset.LAVENDER to 0xFF8E24AA,
    ColorSchemePreset.SLATE    to 0xFF5E7C8B,
    ColorSchemePreset.OCEAN    to 0xFF0097A7,
)

fun colorSchemeForPreset(
    preset: ColorSchemePreset,
    style: SchemeStyle = SchemeStyle.TONAL_SPOT,
    isDark: Boolean = false
): ColorScheme {
    if (preset == ColorSchemePreset.MINIMAL) {
        return if (isDark) minimalDarkColorScheme() else minimalLightColorScheme()
    }
    // 非 MINIMAL 预设必须在 seedColors 中定义；带明确信息抛出，避免 !! 强解产生裸 NPE
    val seedArgb = requireNotNull(seedColors[preset]) { "No seed color defined for preset $preset" }
        .toInt()
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

/** Minimal light scheme — AURORA 青碧工作台风。
 *
 *  主色青碧 (#0E9384)，中性色偏暖灰而非冷蓝灰，页面底色几乎白，
 *  卡片与底色仅一档之差，靠 2dp 强调条与留白分区而非描边。
 *  辅色取琥珀作 tertiary，警示色为珊瑚红，整体是"仪器面板"的冷静感。 */
fun minimalLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF0E9384),           // AURORA 青碧
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCF0EA),
    onPrimaryContainer = Color(0xFF063F39),
    secondary = Color(0xFF4A635D),         // 暖灰绿
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E8E2),
    onSecondaryContainer = Color(0xFF1F3530),
    tertiary = Color(0xFF8A5A00),          // 琥珀
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E1BC),
    onTertiaryContainer = Color(0xFF4A3200),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FAF8),        // 近白微青底
    onBackground = Color(0xFF181D1B),
    surface = Color(0xFFFCFDFC),           // 与背景一档之差
    onSurface = Color(0xFF181D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2D322F),
    inverseOnSurface = Color(0xFFEDF2EF),
    inversePrimary = Color(0xFF4DDAC7),
    surfaceTint = Color(0xFF0E9384),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F9F7),
    surfaceContainer = Color(0xFFEFF4F1),
    surfaceContainerHigh = Color(0xFFE9EEEB),
    surfaceContainerHighest = Color(0xFFE3E8E5),
)

/** Minimal dark scheme — 深海仪表盘风格。
 *
 *  背景墨青 (#101514)，卡片面微亮一档，主色为发光青碧，
 *  前景文字使用带青调的浅灰，整体像深夜仪表面板。 */
fun minimalDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF4DDAC7),           // 发光青碧
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFF6FF7E3),
    secondary = Color(0xFFB1CCC5),         // 浅灰绿
    onSecondary = Color(0xFF1D352F),
    secondaryContainer = Color(0xFF344B45),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFF0C94E),          // 暗夜琥珀
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFE08D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101514),        // 墨青背景
    onBackground = Color(0xFFDFE4E1),
    surface = Color(0xFF151A19),           // 卡片面微亮一档
    onSurface = Color(0xFFDFE4E1),
    surfaceVariant = Color(0xFF3A4542),
    onSurfaceVariant = Color(0xFFBFC9C5),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFDFE4E1),
    inverseOnSurface = Color(0xFF151A19),
    inversePrimary = Color(0xFF0E9384),
    surfaceTint = Color(0xFF4DDAC7),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF121716),
    surfaceContainer = Color(0xFF181D1B),
    surfaceContainerHigh = Color(0xFF212625),
    surfaceContainerHighest = Color(0xFF2B312F),
)

/** AMOLED true-black color scheme — 纯黑 AMOLED 省电方案。
 *
 *  背景与卡片面保持纯黑 (#000000 / #0A0A0A) 以让 OLED 像素彻底断电，
 *  其余配色与 minimalDarkColorScheme 保持一致（深海仪表盘青碧），
 *  兼顾夜间护眼与 AMOLED 续航。 */
fun amoledDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF4DDAC7),           // 发光青碧（与 dark 一致）
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFF6FF7E3),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1D352F),
    secondaryContainer = Color(0xFF344B45),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFF0C94E),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFE08D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF000000),        // 纯黑背景（AMOLED 省电）
    onBackground = Color(0xFFDFE4E1),
    surface = Color(0xFF0A0A0A),           // 近黑卡片面
    onSurface = Color(0xFFDFE4E1),
    surfaceVariant = Color(0xFF3A4542),
    onSurfaceVariant = Color(0xFFBFC9C5),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFDFE4E1),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF0E9384),
    surfaceTint = Color(0xFF4DDAC7),
)
