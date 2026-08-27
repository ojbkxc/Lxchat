package com.lxseek.chat.plugin.adapters

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.plugin.Plugin
import com.lxseek.chat.plugin.PluginCategory
import com.lxseek.chat.plugin.PluginContext
import com.lxseek.chat.plugin.PluginManifest
import com.lxseek.chat.tool.ToolDescriptor
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Adapter for importing an Operit ToolPkg (a ZIP archive with a `manifest.json` and
 * subpackage scripts) as a Lxchat [Plugin].
 *
 * The ToolPkg format is documented in Operit's `TOOLPKG_FORMAT_GUIDE.md`. A `.toolpkg`
 * file is a ZIP whose entries include:
 * - `manifest.json` — package metadata (`toolpkg_id`, `version`, `display_name`,
 *   `subpackages`, ...);
 * - `packages/<subpackage>.js` — subpackage scripts, each carrying a
 *   `/* METADATA { ... } */` comment block declaring the tools it exposes
 *   (name, description, parameters).
 *
 * This adapter performs a **pure structural conversion**: it reads the manifest and the
 * METADATA blocks, and projects them into the Lxchat [Plugin] / [ToolProvider] /
 * [ToolDescriptor] contracts so the generation pipeline can discover and disclose the
 * tools. Executing the JS tool bodies is out of scope for this adapter — the ToolPkg JS
 * runtime is a separate concern; [ToolPkgToolProvider.execute] returns a structured
 * "runtime unavailable" result until that runtime is attached, so the pipeline surfaces
 * a clear error instead of crashing.
 *
 * The adapter never throws on a malformed package: it returns null so a single bad
 * `.toolpkg` cannot crash the host.
 *
 * Note: only `manifest.json` (standard JSON) is supported. `manifest.hjson` (HJSON with
 * comments) is intentionally not parsed here — HJSON requires a dedicated parser and is
 * out of scope for this adapter.
 */
class ToolPkgAdapter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse a ToolPkg ZIP file and create a [Plugin]. Returns null on any structural error.
     *
     * @param overrideId when non-null, the resulting plugin's id is forced to this value
     *   (used by the plugin market so the registered id always matches the market record).
     */
    fun adapt(zipFile: File, overrideId: String? = null): Plugin? =
        runCatching { adaptOrThrow(zipFile, overrideId) }.getOrNull()

    private fun adaptOrThrow(zipFile: File, overrideId: String? = null): Plugin? {
        ZipFile(zipFile).use { zip ->
            val manifest = readManifest(zip) ?: return null
            val toolpkgId = primitiveString(manifest["toolpkg_id"]) ?: return null
            val subpackages = readSubpackages(manifest)
            val toolDescriptors = readToolDescriptors(zip, subpackages)
            val display = resolveLocalizedText(manifest["display_name"], "en")
            val description = resolveLocalizedText(manifest["description"], "en")
            val version = primitiveString(manifest["version"]) ?: "0.0.0"
            val author = resolveAuthor(manifest["author"])

            return ToolPkgPlugin(
                manifest = PluginManifest(
                    id = overrideId ?: toolpkgId,
                    name = display ?: toolpkgId,
                    version = version,
                    category = PluginCategory.External,
                    description = description,
                    author = author,
                    requiresMembership = false,
                    builtIn = false,
                    preferenceKey = "plugin.$toolpkgId.enabled",
                ),
                toolDescriptors = toolDescriptors,
            )
        }
    }

    // ── Manifest reading ─────────────────────────────────────────────────────

    /** Read and parse `manifest.json` from the ZIP. Returns null when absent or malformed. */
    private fun readManifest(zip: ZipFile): JsonObject? {
        val entry = zip.entries().asSequence().firstOrNull { it.name == "manifest.json" }
            ?: return null
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        return json.parseToJsonElement(text).jsonObject
    }

    /** Read the `subpackages` array from the manifest. */
    private fun readSubpackages(manifest: JsonObject): List<Subpackage> {
        val arr = manifest["subpackages"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            Subpackage(
                id = primitiveString(obj["id"]) ?: return@mapNotNull null,
                entry = primitiveString(obj["entry"]) ?: return@mapNotNull null,
                enabledByDefault = primitiveBool(obj["enabled_by_default"]),
                displayName = resolveLocalizedText(obj["display_name"], "en"),
                description = resolveLocalizedText(obj["description"], "en"),
            )
        }
    }

    // ── Tool descriptor extraction ───────────────────────────────────────────

    /**
     * Read each subpackage's entry script, extract its `/* METADATA { ... } */` block,
     * and project the declared tools into [ToolDescriptor]s. Tools are namespaced as
     * `<subpackage_id>:<tool_name>` per the ToolPkg convention so names stay unique
     * across packages.
     */
    private fun readToolDescriptors(
        zip: ZipFile,
        subpackages: List<Subpackage>,
    ): List<ToolDescriptor> {
        val descriptors = mutableListOf<ToolDescriptor>()
        for (sub in subpackages) {
            val entry = zip.entries().asSequence().firstOrNull { it.name == sub.entry } ?: continue
            val script = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val metadataJson = extractMetadataBlock(script) ?: continue
            val tools = parseMetadataTools(metadataJson)
            for (tool in tools) {
                val namespacedName = "${sub.id}:${tool.name}"
                descriptors.add(
                    ToolDescriptor(
                        definition = ToolDefinition(
                            function = ToolFunction(
                                name = namespacedName,
                                description = tool.description ?: tool.name,
                                parameters = ToolParameters(
                                    properties = tool.parameters.associate { param ->
                                        param.name to ToolProperty(
                                            type = param.type,
                                            description = param.description ?: param.name,
                                        )
                                    },
                                    required = tool.parameters.filter { it.required }.map { it.name },
                                ),
                            ),
                        ),
                        // External ToolPkg tools are untrusted: require explicit approval.
                        requiresApproval = true,
                        summary = tool.description,
                    ),
                )
            }
        }
        return descriptors
    }

    /** Extract the JSON text inside a `/* METADATA { ... } */` comment block. */
    private fun extractMetadataBlock(script: String): String? {
        val start = script.indexOf("/* METADATA")
        if (start < 0) return null
        val end = script.indexOf("*/", start)
        if (end < 0) return null
        return script.substring(start + "/* METADATA".length, end).trim()
    }

    /** Parse the METADATA JSON and extract the `tools` array. */
    private fun parseMetadataTools(metadataJson: String): List<MetadataTool> {
        val obj = runCatching { json.parseToJsonElement(metadataJson).jsonObject }.getOrNull()
            ?: return emptyList()
        val tools = obj["tools"] as? JsonArray ?: return emptyList()
        return tools.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            MetadataTool(
                name = primitiveString(t["name"]) ?: return@mapNotNull null,
                description = resolveLocalizedText(t["description"], "en"),
                parameters = parseMetadataParameters(t["parameters"]),
            )
        }
    }

    private fun parseMetadataParameters(raw: JsonElement?): List<MetadataParameter> {
        val arr = raw as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            MetadataParameter(
                name = primitiveString(p["name"]) ?: return@mapNotNull null,
                type = primitiveString(p["type"]) ?: "string",
                description = resolveLocalizedText(p["description"], "en"),
                required = primitiveBool(p["required"]),
            )
        }
    }

    // ── LocalizedText & scalar helpers ───────────────────────────────────────

    /**
     * Resolve a LocalizedText element. Supports both the simple-string form and the
     * multi-language object form. Preference order: [lang] → `default` → first
     * available value. Returns null for absent/non-scalar values.
     */
    private fun resolveLocalizedText(element: JsonElement?, lang: String): String? {
        if (element == null) return null
        if (element is JsonPrimitive) return element.content
        if (element is JsonObject) {
            primitiveString(element[lang])?.let { return it }
            primitiveString(element["default"])?.let { return it }
            element.values.firstNotNullOfOrNull { primitiveString(it) }?.let { return it }
        }
        return null
    }

    /** Resolve the `author` field which may be a string or an array of strings. */
    private fun resolveAuthor(element: JsonElement?): String? {
        if (element == null) return null
        if (element is JsonPrimitive) return element.content
        if (element is JsonArray) {
            return element.mapNotNull { primitiveString(it) }.joinToString(", ").ifEmpty { null }
        }
        return null
    }

    /** Safely extract a string/number/boolean primitive's textual content. */
    private fun primitiveString(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.content

    /** Safely extract a boolean primitive (accepts `true`/`"true"`/`1`). */
    private fun primitiveBool(element: JsonElement?): Boolean {
        val s = primitiveString(element) ?: return false
        return s == "true" || s == "1"
    }

    // ── Internal types ───────────────────────────────────────────────────────

    private data class Subpackage(
        val id: String,
        val entry: String,
        val enabledByDefault: Boolean,
        val displayName: String?,
        val description: String?,
    )

    private data class MetadataTool(
        val name: String,
        val description: String?,
        val parameters: List<MetadataParameter>,
    )

    private data class MetadataParameter(
        val name: String,
        val type: String,
        val description: String?,
        val required: Boolean,
    )
}

/**
 * [Plugin] wrapper around a parsed ToolPkg. Exposes the package's tools via a single
 * [ToolPkgToolProvider]; carries no skills or settings schema (ToolPkg UI DSL, workflow
 * and workspace templates are handled by their own subsystems and are out of scope for
 * this adapter).
 */
private class ToolPkgPlugin(
    override val manifest: PluginManifest,
    private val toolDescriptors: List<ToolDescriptor>,
) : Plugin {
    override fun toolProviders(context: PluginContext): List<ToolProvider> =
        listOf(ToolPkgToolProvider(manifest.id, toolDescriptors))
}

/**
 * [ToolProvider] backed by a ToolPkg's parsed METADATA tool declarations. The
 * descriptors are precomputed at adaptation time; execution is delegated to the
 * ToolPkg JS runtime, which is not part of this adapter — until that runtime is
 * attached, [execute] returns a structured "runtime unavailable" result so the
 * generation pipeline can surface a clear error instead of crashing.
 */
private class ToolPkgToolProvider(
    private val toolpkgId: String,
    private val descriptors: List<ToolDescriptor>,
) : ToolProvider {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> = descriptors

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        descriptors.map { it.definition }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        "{\"error\":\"toolpkg_runtime_unavailable\",\"toolpkg_id\":\"$toolpkgId\"," +
            "\"tool\":\"$name\",\"message\":\"ToolPkg JS runtime is not attached in this " +
            "build; the tool definition is exposed for disclosure but cannot be executed.\"}"

    override fun handles(name: String): Boolean =
        descriptors.any { it.definition.function.name == name }

    /** External ToolPkg tools are untrusted: always require explicit user approval. */
    override fun requiresApprovalByDefault(name: String): Boolean = true
}