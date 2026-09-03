package com.lxseek.chat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val MonoFamily = FontFamily.Monospace

val OutfitFamily = FontFamily.Default

/**
 * Default ratio between adjacent ChatType sizes (major second ≈ 1.15). This is a
 * design-time reference constant; the user-adjustable live scale factor is
 * [chatFontScale], which multiplies every ChatType font size at runtime.
 */
const val DEFAULT_CHAT_FONT_SCALE = 1.15f

// Geometric (modular) type scale: every distinct size is a term of a geometric
// sequence anchored at body = 16sp with common ratio r = 1.2 (minor third).
// Sizes: 11, 13, 16, 19, 23, 28, 33, 40, 48, 57. Line heights scale per tier
// (display 1.15× · headline 1.25× · title 1.3× · body 1.45× · label 1.4×).
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 66.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 48.sp,
        lineHeight = 55.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.5.sp
    )
)

// ─────────────────────────────────────────────────────────────────────────────
// ChatType — single source of truth for the chat surface's typographic scale.
//
// The chat page is an information-dense, immersive-reading surface and so uses a
// TIGHTER scale than the 1.2 (minor-third) geometric scale that drives Settings'
// big collapsing titles. Here the ratio is ~1.15 (major-second), anchored at the
// reading body (15sp). Outfit's tall x-height makes 15sp read like ~16sp Roboto.
//
// Five semantic tiers — never reach past them on a chat Text:
//   · Title   — brand 20 · sheet 19 · conversation 17   (the only sizes ≥17)
//   · Input   — 16 (slightly above body for a comfortable touch target)
//   · Body    — 15 (user + assistant message text; the anchor)
//   · Sub     — 13 (thought body; clearly subordinate to body)
//   · Meta    — 12 labels/status · 11 micro (token counts, badges)
//
// Hierarchy is carried by SIZE + WEIGHT + COLOR together: e.g. the collapsed
// "thought for Ns" eyebrow is meta(12) but Bold + primary, so it out-ranks the
// 13sp thought body it introduces despite being smaller. Call sites supply color.
/** Mutable font family for ChatType styles. Set from Theme.kt when font preference changes. */
internal var chatFontFamily: FontFamily = OutfitFamily

/**
 * Mutable scale factor applied to every ChatType font size and line height.
 * Set from Theme.kt when the user adjusts the chat font scale slider.
 * `1.0f` means no scaling — the default, preserving the designed sizes.
 */
internal var chatFontScale: Float = 1.0f

object ChatType {

    // Title tier
    // Brand wordmark in the new-chat capsule: prominent in the empty state, one
    // clean step above the active-conversation title (20 → 15).
    val brandTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp * chatFontScale, lineHeight = 26.sp * chatFontScale)
    val sheetTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp * chatFontScale, lineHeight = 25.sp * chatFontScale)
    // Active-conversation title: one step below the brand wordmark (16 → 15),
    // Bold so it still reads as a title against the 15sp Normal body.
    val conversationTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp * chatFontScale, lineHeight = 20.sp * chatFontScale)

    // Active-conversation title when it stands alone (no token subtitle): a touch
    // smaller than the 20sp brand wordmark so a lone title doesn't read as loud.
    val conversationTitleSolo get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp * chatFontScale, lineHeight = 22.sp * chatFontScale)

    // Input tier
    val input get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp * chatFontScale, lineHeight = 23.sp * chatFontScale, letterSpacing = 0.5.sp)

    // Body tier
    val body get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp * chatFontScale, lineHeight = 20.sp * chatFontScale)
    val userBody get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp * chatFontScale, lineHeight = 20.sp * chatFontScale)
    val thoughtBody get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp * chatFontScale, lineHeight = 12.sp * chatFontScale)
    val thoughtTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp * chatFontScale, lineHeight = 12.sp * chatFontScale)
    val thoughtFold get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp * chatFontScale, lineHeight = 12.sp * chatFontScale)
    val errorBody get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp * chatFontScale, lineHeight = 18.sp * chatFontScale)

    // Meta tier
    val meta get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp * chatFontScale, lineHeight = 17.sp * chatFontScale)
    val metaNormal get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp * chatFontScale, lineHeight = 17.sp * chatFontScale)
    val micro get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp * chatFontScale, lineHeight = 15.sp * chatFontScale)

    // Quote tier (reply reference chips in bubbles and the composer)
    val quoteLabel get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp * chatFontScale, lineHeight = 14.sp * chatFontScale)
    val quoteBody get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp * chatFontScale, lineHeight = 16.sp * chatFontScale)

    // Code / mono
    val code get() = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp * chatFontScale, lineHeight = 20.sp * chatFontScale)
    val thoughtCode get() = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp * chatFontScale, lineHeight = 11.sp * chatFontScale)
    val thoughtCodeLarge get() = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp * chatFontScale, lineHeight = 12.sp * chatFontScale)

    // Sheet
    val detailTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp * chatFontScale, lineHeight = 28.sp * chatFontScale)

    // Rating
    val ratingTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp * chatFontScale, lineHeight = 35.sp * chatFontScale)

    // Drawer
    val conversationsTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp * chatFontScale, lineHeight = 22.sp * chatFontScale)
    val drawerButton get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp * chatFontScale, lineHeight = 20.sp * chatFontScale)
    val drawerSearch get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp * chatFontScale, lineHeight = 23.sp * chatFontScale)

    // Assistant markdown headings — even ~1.15 steps; h1 reined in (22, not 24)
    // so the jump from h2 stays proportional and h1 doesn't shout over 15sp body.
    val mdH1 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp * chatFontScale, lineHeight = 28.sp * chatFontScale)
    val mdH2 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp * chatFontScale, lineHeight = 25.sp * chatFontScale)
    val mdH3 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp * chatFontScale, lineHeight = 23.sp * chatFontScale)
    val mdH4 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp * chatFontScale, lineHeight = 22.sp * chatFontScale)
    val mdH5 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp * chatFontScale, lineHeight = 22.sp * chatFontScale)
    val mdH6 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp * chatFontScale, lineHeight = 20.sp * chatFontScale)

    // Thought-block headings — one tier below assistant markdown.
    val thH1 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp * chatFontScale, lineHeight = 15.sp * chatFontScale)
    val thH2 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp * chatFontScale, lineHeight = 14.sp * chatFontScale)
    val thH3 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp * chatFontScale, lineHeight = 13.sp * chatFontScale)
    val thH4 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp * chatFontScale, lineHeight = 13.sp * chatFontScale)
    val thH5 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp * chatFontScale, lineHeight = 12.sp * chatFontScale)
    val thH6 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp * chatFontScale, lineHeight = 12.sp * chatFontScale)
}
