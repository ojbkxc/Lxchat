package com.lxseek.chat.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Provider brand accent colors used by the [com.lxseek.chat.ui.chat.bottombar.ProviderBadge]
 * component. Kept outside the composable layer so the palette is a single source of truth
 * and can be shared (e.g. by settings pages) without hard-coded literals in each call site.
 */
object ProviderPalette {
    /** Anthropic Claude brand clay. */
    val Anthropic = Color(0xFFD97757)

    /** OpenAI brand teal. */
    val OpenAI = Color(0xFF74AA9C)
}