package com.lxseek.chat.ui.plugins

import kotlinx.serialization.json.Json

/**
 * Parses a JSON string into a [UiDslDocument].
 *
 * Unknown keys are ignored and the parser is lenient so that forward-compatible
 * DSL documents (with extra fields added in later versions) do not break older
 * builds. On any parse failure [parse] returns `null` instead of throwing, so
 * callers can safely fall back to a default UI.
 */
object UiDslParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Decodes [jsonString] into a [UiDslDocument], or `null` on failure.
     */
    fun parse(jsonString: String): UiDslDocument? {
        return try {
            json.decodeFromString(UiDslDocument.serializer(), jsonString)
        } catch (e: Exception) {
            null
        }
    }
}