package com.lxseek.chat.plugin.adapters

import android.graphics.Color
import androidx.compose.ui.graphics.Color as ComposeColor
import org.json.JSONObject
import java.io.File

/**
 * Parsed OpenCode theme. [colors] maps a theme key (e.g. "primary", "background") to its
 * dark-mode ARGB color value. Light-mode values are parsed but not surfaced here; callers
 * needing light mode can extend the adapter.
 */
data class OpenCodeTheme(
    val colors: Map<String, Int>,
) {
    /** Look up a color by theme key. Returns null when the key is absent. */
    fun color(name: String): Int? = colors[name]

    /** Convert all resolved colors to Compose [ComposeColor] values. */
    fun toComposeColors(): Map<String, ComposeColor> =
        colors.mapValues { (_, argb) -> ComposeColor(argb) }
}

/**
 * Adapter for parsing OpenCode theme JSON into Compose colors.
 *
 * OpenCode theme format:
 * - `defs`: a map of color names to hex strings (e.g. `"nord0": "#2E3440"`).
 * - `theme`: a map of semantic keys to `{ "dark": <ref>, "light": <ref> }`, where `<ref>`
 *   is either a defs name (e.g. `"nord10"`) or a literal hex string (e.g. `"#8B95A7"`).
 *
 * This adapter resolves each theme key's dark-mode reference to an ARGB [Int] via
 * [android.graphics.Color.parseColor], and exposes [OpenCodeTheme.toComposeColors] for
 * direct use in Jetpack Compose. Malformed entries are skipped; a fully malformed
 * document returns null.
 */
object OpenCodeThemeAdapter {

    /** Parse a theme JSON string into an [OpenCodeTheme]. Returns null on parse failure. */
    fun parse(json: String): OpenCodeTheme? {
        val root = runCatching { JSONObject(json) }.getOrElse { return null }
        val defs = parseDefs(root.optJSONObject("defs"))
        val themeObj = root.optJSONObject("theme")
        val colors = LinkedHashMap<String, Int>()
        if (themeObj != null) {
            for (key in themeObj.keys()) {
                val entry = themeObj.optJSONObject(key) ?: continue
                val darkRef = entry.optString("dark").takeIf { it.isNotEmpty() } ?: continue
                resolveColor(darkRef, defs)?.let { colors[key] = it }
            }
        }
        return OpenCodeTheme(colors = colors)
    }

    /** Parse a theme JSON file. Returns null on IO or parse failure. */
    fun parseFile(file: File): OpenCodeTheme? {
        val content = runCatching { file.readText() }.getOrElse { return null }
        return parse(content)
    }

    /** Parse the `defs` block into a name-to-ARGB-Int map. */
    private fun parseDefs(defsObj: JSONObject?): Map<String, Int> {
        if (defsObj == null) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for (key in defsObj.keys()) {
            val hex = defsObj.optString(key).takeIf { it.isNotEmpty() } ?: continue
            runCatching { Color.parseColor(hex) }.getOrNull()?.let { result[key] = it }
        }
        return result
    }

    /**
     * Resolve a color reference: if it names a def, use the def's ARGB value; otherwise
     * treat it as a literal hex string. Returns null when neither resolves.
     */
    private fun resolveColor(ref: String, defs: Map<String, Int>): Int? {
        defs[ref]?.let { return it }
        return runCatching { Color.parseColor(ref) }.getOrNull()
    }
}