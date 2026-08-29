package com.lxseek.chat.plugin.adapters

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.tool.ToolProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Metadata for one OpenCode tool, extracted from a plugin manifest. Only the
 * model-facing fields are kept: [name], [description], and [parameters] (a JSON Schema
 * object). The TypeScript execute function is intentionally not represented — LxChat
 * cannot execute TypeScript on Android, so only static metadata is imported.
 */
data class OpenCodeToolDef(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

/**
 * Metadata for an OpenCode plugin, extracted from a manifest JSON. The [id] is the
 * plugin identifier; [tools] lists the tool definitions the plugin declares.
 */
data class OpenCodePluginMeta(
    val id: String,
    val tools: List<OpenCodeToolDef>,
)

/**
 * Adapter for projecting OpenCode plugin metadata onto LxChat's tool model.
 *
 * OpenCode plugins are TypeScript modules (`PluginModule = { id?, server, tui? }`) whose
 * `Hooks.tool` map exposes `ToolDefinition`s built with Zod schemas. LxChat runs on
 * Kotlin/Android and cannot execute TypeScript, so this adapter only parses a static
 * manifest JSON (a snapshot of the plugin's tool metadata) — it never executes plugin
 * code. The manifest shape is:
 *
 * ```
 * { "id": "plugin-id",
 *   "tools": [ { "name": "...", "description": "...", "parameters": { ...schema... } } ] }
 * ```
 *
 * Each [OpenCodeToolDef] can be projected to a read-only [ToolProvider.ToolDescriptor]
 * via [toToolDescriptor]; the resulting descriptor carries no executable behavior and is
 * marked requiresApproval so the host can surface it safely.
 */
object OpenCodePluginAdapter {

    /** Parse a plugin manifest JSON string into [OpenCodePluginMeta]. Returns null on failure. */
    fun parsePluginManifest(json: String): OpenCodePluginMeta? {
        val root = runCatching { JSONObject(json) }.getOrElse { return null }
        val id = root.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val toolsArr = root.optJSONArray("tools") ?: JSONArray()
        val tools = (0 until toolsArr.length()).mapNotNull { i ->
            val item = toolsArr.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val description = item.optString("description")
            val parameters = item.optJSONObject("parameters") ?: JSONObject()
            OpenCodeToolDef(name = name, description = description, parameters = parameters)
        }
        return OpenCodePluginMeta(id = id, tools = tools)
    }

    /** Parse a plugin manifest file. Returns null on IO or parse failure. */
    fun parsePluginFile(file: File): OpenCodePluginMeta? {
        val content = runCatching { file.readText() }.getOrElse { return null }
        return parsePluginManifest(content)
    }

    /**
     * Project an [OpenCodeToolDef] to a read-only [ToolProvider.ToolDescriptor]. The
     * descriptor's [ToolDefinition] is built from the tool's JSON Schema parameters; the
     * descriptor is marked ReadOnly (default) and requiresApproval so the host surfaces
     * it safely — the tool body cannot be executed on Android.
     */
    fun toToolDescriptor(tool: OpenCodeToolDef): ToolProvider.ToolDescriptor {
        val parameters = jsonSchemaToToolParameters(tool.parameters)
        val definition = ToolDefinition(
            function = ToolFunction(
                name = tool.name,
                description = tool.description,
                parameters = parameters,
            ),
        )
        return ToolProvider.ToolDescriptor(
            definition = definition,
            requiresApproval = true,
            summary = tool.description.takeIf { it.isNotEmpty() },
        )
    }

    /** Convert a JSON Schema object to a [ToolParameters] model. */
    private fun jsonSchemaToToolParameters(schema: JSONObject): ToolParameters {
        val propsObj = schema.optJSONObject("properties")
        val properties = LinkedHashMap<String, ToolProperty>()
        if (propsObj != null) {
            for (key in propsObj.keys()) {
                val propSchema = propsObj.optJSONObject(key) ?: continue
                properties[key] = jsonSchemaToToolProperty(propSchema)
            }
        }
        val requiredArr = schema.optJSONArray("required")
        val required = mutableListOf<String>()
        if (requiredArr != null) {
            for (i in 0 until requiredArr.length()) {
                runCatching { requiredArr.getString(i) }.getOrNull()?.let { required.add(it) }
            }
        }
        return ToolParameters(
            type = schema.optString("type").takeIf { it.isNotEmpty() } ?: "object",
            properties = properties,
            required = required,
        )
    }

    /** Convert a single JSON Schema property to a [ToolProperty]. */
    private fun jsonSchemaToToolProperty(prop: JSONObject): ToolProperty {
        val type = prop.optString("type").takeIf { it.isNotEmpty() } ?: "string"
        val description = prop.optString("description")
        val items = prop.optJSONObject("items")?.let { jsonSchemaToToolProperty(it) }
        return ToolProperty(type = type, description = description, items = items)
    }
}