package com.lxseek.chat.ui.companion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lxseek.chat.ui.theme.ColorSchemePreset
import com.lxseek.chat.ui.theme.SchemeStyle
import com.lxseek.chat.ui.theme.colorSchemeForPreset

/**
 * Companion ("伴生柔软质感") design tokens.
 *
 * These reuse LxChat's existing Material3 dynamic shaper, but override the *shape* and *motion*
 * surfaces with a softer, more organic feel than the default material kit. They strictly
 * ADD new tokens and never mutate the production `LxChatTheme`; adopt them per-screen so an
 * existing page degrades to its current look if a token is removed.
 */
object CompanionShapes {

    /** Large, soft radii — the first visual signature of the companion language. */
    val card = RoundedCornerShape(22.dp)
    val medium = RoundedCornerShape(18.dp)
    val small = RoundedCornerShape(14.dp)
    val extraSmall = RoundedCornerShape(10.dp)

    val pill = RoundedCornerShape(percent = 50)

    fun materialLike(): Shapes = Shapes(
        extraSmall = extraSmall,
        small = small,
        medium = medium,
        large = pill,
        extraLarge = pill,
    )
}

/** Motion constants tuned for a "breathing" feel instead of snap or hard cut. */
object CompanionMotion {

    /** Gentle, emphasized easing for soft entrances. */
    val breathingEasing = FastOutSlowInEasing

    /** Soft spring-like crossfade used for in-place state changes. */
    fun crossfade(durationMs: Int = 350) =
        TweenSpec<Float>(durationMillis = durationMs, easing = breathingEasing)

    fun fadeIn(durationMs: Int = 380) =
        TweenSpec<Float>(durationMillis = durationMs, easing = breathingEasing)
}

/**
 * A single, muted accent used throughout companion surfaces. Returning a single [Color] (instead
 * of a full scheme) enforces the "one accent, everything else neutral" signature. Slots map to a
 * lightweight color derived from an existing preset so dark/light stay readable.
 */
@Immutable
class CompanionAccent private constructor(val color: Color) {
    companion object {
        fun fromPreset(
            preset: ColorSchemePreset = ColorSchemePreset.MIDNIGHT,
            isDark: Boolean = false,
        ): CompanionAccent {
            // Use the preset's primary as the single accent family.
            val scheme = colorSchemeForPreset(preset, SchemeStyle.TONAL_SPOT, isDark)
            return CompanionAccent(scheme.primary)
        }
    }
}