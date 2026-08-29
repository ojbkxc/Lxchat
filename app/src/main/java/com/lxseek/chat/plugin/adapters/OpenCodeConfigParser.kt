package com.lxseek.chat.plugin.adapters

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reference to an external knowledge base or repository declared in `opencode.jsonc`.
 */
data class OpenCodeReference(
    val repository: String?,
    val path: String?,
    val description: String?,
)

/**
 * MCP server configuration declared in `opencode.jsonc`. Supports both stdio servers
 * (command + args + env) and remote servers (url).
 */
data class McpServerConfig(
    val command: String?,
    val args: List<String>,
    val env: Map<String, String>,
    val url: String?,
    val enabled: Boolean = true,
)

/**
 * Parsed `opencode.jsonc` configuration. Only the fields LxChat consumes are surfaced;
 * unknown fields (provider, etc.) are ignored.
 */
data class OpenCodeConfig(
    val references: Map<String, OpenCodeReference>,
    val mcpServers: Map<String, McpServerConfig>,
    val tools: Map<String, Boolean>,
    val permission: Map<String, String>,
)

/**
 * Parser for OpenCode `opencode.jsonc` configuration files.
 *
 * JSONC is JSON with comments: `//` line comments and C-style block comments are allowed.
 * Android's built-in [org.json.JSONObject] cannot parse JSONC directly, so comments are
 * stripped first with a small state machine that is aware of string literals (so `//`
 * inside a string value is preserved). The stripped text is then parsed with [JSONObject].
 *
 * Extracted fields: `references` (knowledge-base refs), `mcp` (MCP server configs),
 * `tools` (enable/disable flags), `permission` (permission key to value). All other
 * fields are ignored. Returns null on unrecoverable parse errors.
 */
object OpenCodeConfigParser {

    /** Parse a JSONC string into an [OpenCodeConfig]. Returns null on parse failure. */
    fun parse(content: String): OpenCodeConfig? {
        val stripped = stripJsoncComments(content)
        val root = runCatching { JSONObject(stripped) }.getOrElse { return null }
        return OpenCodeConfig(
            references = parseReferences(root),
            mcpServers = parseMcpServers(root),
            tools = parseTools(root),
            permission = parsePermission(root),
        )
    }

    /** Parse a JSONC file into an [OpenCodeConfig]. Returns null on IO or parse failure. */
    fun parseFile(file: File): OpenCodeConfig? {
        val content = runCatching { file.readText() }.getOrElse { return null }
        return parse(content)
    }

    private fun parseReferences(root: JSONObject): Map<String, OpenCodeReference> {
        val obj = root.optJSONObject("references") ?: return emptyMap()
        val result = LinkedHashMap<String, OpenCodeReference>()
        for (key in obj.keys()) {
            val item = obj.optJSONObject(key) ?: continue
            result[key] = OpenCodeReference(
                repository = item.optString("repository").takeIf { it.isNotEmpty() },
                path = item.optString("path").takeIf { it.isNotEmpty() },
                description = item.optString("description").takeIf { it.isNotEmpty() },
            )
        }
        return result
    }

    private fun parseMcpServers(root: JSONObject): Map<String, McpServerConfig> {
        val obj = root.optJSONObject("mcp") ?: return emptyMap()
        val result = LinkedHashMap<String, McpServerConfig>()
        for (key in obj.keys()) {
            val item = obj.optJSONObject(key) ?: continue
            result[key] = McpServerConfig(
                command = item.optString("command").takeIf { it.isNotEmpty() },
                args = parseStringArray(item.optJSONArray("args")),
                env = parseStringMap(item.optJSONObject("env")),
                url = item.optString("url").takeIf { it.isNotEmpty() },
                enabled = if (item.has("enabled")) item.optBoolean("enabled", true) else true,
            )
        }
        return result
    }

    private fun parseTools(root: JSONObject): Map<String, Boolean> {
        val obj = root.optJSONObject("tools") ?: return emptyMap()
        val result = LinkedHashMap<String, Boolean>()
        for (key in obj.keys()) {
            result[key] = obj.optBoolean(key, false)
        }
        return result
    }

    private fun parsePermission(root: JSONObject): Map<String, String> {
        val obj = root.optJSONObject("permission") ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            result[key] = obj.optString(key)
        }
        return result
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { arr.getString(i) }.getOrNull()
        }
    }

    private fun parseStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            result[key] = obj.optString(key)
        }
        return result
    }

    /**
     * Strip `//` line comments and C-style block comments from a JSONC string, preserving
     * those sequences when they appear inside string literals. Uses a single-pass state
     * machine over the character stream so the result is safe to feed to [JSONObject].
     */
    internal fun stripJsoncComments(content: String): String {
        val out = StringBuilder(content.length)
        var i = 0
        var inString = false
        while (i < content.length) {
            val c = content[i]
            if (inString) {
                out.append(c)
                if (c == '\\' && i + 1 < content.length) {
                    // Escape: copy the next char verbatim so an escaped quote is preserved.
                    out.append(content[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }
                '/' -> {
                    val next = content.getOrNull(i + 1)
                    if (next == '/') {
                        // Line comment: skip to end of line.
                        i += 2
                        while (i < content.length && content[i] != '\n') i++
                    } else if (next == '*') {
                        // Block comment: skip to the closing star-slash delimiter.
                        i += 2
                        while (i < content.length &&
                            !(content[i] == '*' && content.getOrNull(i + 1) == '/')
                        ) {
                            i++
                        }
                        i += 2 // skip closing delimiter
                    } else {
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}