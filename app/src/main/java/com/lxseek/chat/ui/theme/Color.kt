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

/** Minimal light scheme — Apple 系统配色风格。
 *
 *  纯白卡片配以 Apple System Gray 6 略灰背景，主色采用 Apple Blue (#007AFF)，
 *  辅以 Apple Purple / Green / Red 形成清新、柔和、高级的现代视觉。
 *  分隔线使用 Apple Separator，整体克制而不失辨识度。 */
fun minimalLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),           // Apple Blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3F2FD),  // 浅蓝容器
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF5856D6),         // Apple Purple
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDEDF7),
    onSecondaryContainer = Color(0xFF1E1E5E),
    tertiary = Color(0xFF34C759),          // Apple Green
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8F8ED),
    onTertiaryContainer = Color(0xFF1B5E20),
    error = Color(0xFFFF3B30),             // Apple Red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFFB71C1C),
    background = Color(0xFFF2F2F7),        // Apple System Gray 6（略灰背景）
    onBackground = Color(0xFF1C1C1E),      // Apple Label
    surface = Color(0xFFFFFFFF),           // 纯白卡片
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFF2F2F7),    // Apple System Gray 6
    onSurfaceVariant = Color(0xFF3C3C43),  // Apple Secondary Label
    outline = Color(0xFFC6C6C8),           // Apple Separator
    outlineVariant = Color(0xFFE5E5EA),    // Apple Separator Opaque
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF5E9EFF),    // 反相主色对应暗色方案
    surfaceTint = Color(0xFF007AFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color(0xFFF5F5F6),
    surfaceContainerHigh = Color(0xFFEFEFF1),
    surfaceContainerHighest = Color(0xFFE9E9EC),
)

/** Minimal dark scheme — Linear 暗色风格。
 *
 *  深邃而非纯黑的背景 (#0D0D0F)，柔和的蓝紫主色调，微妙的对比与层次。
 *  卡片面略亮于背景以形成轻盈的浮起感，前景文字使用低饱和的浅灰，
 *  整体氛围沉静、专注、不刺眼，适合长时间夜间阅读。 */
fun minimalDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF5E9EFF),           // 柔和蓝
    onPrimary = Color(0xFF0A1929),
    primaryContainer = Color(0xFF1A3A5C),
    onPrimaryContainer = Color(0xFFB0D6FF),
    secondary = Color(0xFF8B89E8),         // 柔和紫
    onSecondary = Color(0xFF1A1A3E),
    secondaryContainer = Color(0xFF2A2A52),
    onSecondaryContainer = Color(0xFFC8C7F0),
    tertiary = Color(0xFF4ECD7A),          // 柔和绿
    onTertiary = Color(0xFF0A2A14),
    tertiaryContainer = Color(0xFF1A3D28),
    onTertiaryContainer = Color(0xFFA0E8B8),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFB8B8),
    background = Color(0xFF0D0D0F),        // 深邃背景（非纯黑）
    onBackground = Color(0xFFE4E4E7),
    surface = Color(0xFF1A1A1E),           // 卡片面
    onSurface = Color(0xFFE4E4E7),
    surfaceVariant = Color(0xFF252528),
    onSurfaceVariant = Color(0xFF9E9EA3),
    outline = Color(0xFF3A3A3E),
    outlineVariant = Color(0xFF2A2A2E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E4E7),
    inverseOnSurface = Color(0xFF1A1A1E),
    inversePrimary = Color(0xFF007AFF),    // 反相主色对应亮色方案
    surfaceTint = Color(0xFF5E9EFF),
    surfaceContainerLowest = Color(0xFF08080A),
    surfaceContainerLow = Color(0xFF111113),
    surfaceContainer = Color(0xFF1A1A1E),
    surfaceContainerHigh = Color(0xFF222226),
    surfaceContainerHighest = Color(0xFF2A2A2E),
)

/** AMOLED true-black color scheme — 纯黑 AMOLED 省电方案。
 *
 *  背景与卡片面保持纯黑 (#000000 / #0A0A0A) 以让 OLED 像素彻底断电，
 *  其余配色与 minimalDarkColorScheme 保持一致（Linear 风格柔和蓝紫），
 *  兼顾夜间护眼与 AMOLED 续航。 */
fun amoledDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF5E9EFF),           // 柔和蓝（与 dark 一致）
    onPrimary = Color(0xFF0A1929),
    primaryContainer = Color(0xFF1A3A5C),
    onPrimaryContainer = Color(0xFFB0D6FF),
    secondary = Color(0xFF8B89E8),         // 柔和紫
    onSecondary = Color(0xFF1A1A3E),
    secondaryContainer = Color(0xFF2A2A52),
    onSecondaryContainer = Color(0xFFC8C7F0),
    tertiary = Color(0xFF4ECD7A),          // 柔和绿
    onTertiary = Color(0xFF0A2A14),
    tertiaryContainer = Color(0xFF1A3D28),
    onTertiaryContainer = Color(0xFFA0E8B8),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFB8B8),
    background = Color(0xFF000000),        // 纯黑背景（AMOLED 省电）
    onBackground = Color(0xFFE4E4E7),
    surface = Color(0xFF0A0A0A),           // 近黑卡片面
    onSurface = Color(0xFFE4E4E7),
    surfaceVariant = Color(0xFF252528),
    onSurfaceVariant = Color(0xFF9E9EA3),
    outline = Color(0xFF3A3A3E),
    outlineVariant = Color(0xFF2A2A2E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E4E7),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF007AFF),
    surfaceTint = Color(0xFF5E9EFF),
)
