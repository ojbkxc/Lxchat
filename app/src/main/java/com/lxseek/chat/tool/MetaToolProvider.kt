package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.data.PromptItemType
import com.lxseek.chat.data.PromptTemplateItem
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.plugin.PluginHost
import com.lxseek.chat.skill.SkillHost
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Conversation-level meta tools: let the model tune the app configuration
 * (model, temperature, pet, …) and toggle skills/tools without leaving the
 * chat. Inspired by dph project4's /mobile customization meta ability — the
 * user says "switch to gpt-4" or "set temperature to 0.5" and the model calls
 * a tool instead of directing the user to the settings page.
 *
 * All four tools are [ToolTier.Extended]; `config_get` is [RiskLevel.ReadOnly]
 * while the mutating three are [RiskLevel.Moderate].
 */
class MetaToolProvider(
    private val settings: SettingsRepository,
    private val skillHost: SkillHost,
    private val pluginHost: PluginHost? = null,
) : ToolProvider {

    /** Whitelisted config keys the model is allowed to read/write. */
    private val configKeys = setOf(
        "model", "temperature", "max_tokens", "top_p",
        "system_prompt_addon", "pet_enabled", "pet_character",
    )

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        definitions(ctx).map { def ->
            val name = def.function.name
            ToolDescriptor(
                definition = def,
                riskLevel = if (name == "config_get") RiskLevel.ReadOnly else RiskLevel.Moderate,
                tier = ToolTier.Extended,
            )
        }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun prop(t: String, d: String) = ToolProperty(t, d)
        return listOf(
            tool(
                "config_set",
                "Set a whitelisted app configuration key. Allowed keys: model, temperature, " +
                    "max_tokens, top_p, system_prompt_addon, pet_enabled, pet_character. " +
                    "value is always a string (e.g. temperature '0.5', pet_enabled 'true').",
                mapOf(
                    "key" to prop("string", "One of the whitelisted config keys."),
                    "value" to prop("string", "New value as a string."),
                ),
                listOf("key", "value"),
            ),
            tool(
                "config_get",
                "Read a whitelisted app configuration key and return its current value.",
                mapOf("key" to prop("string", "One of the whitelisted config keys.")),
                listOf("key"),
            ),
            tool(
                "skill_toggle",
                "Enable or disable a registered skill by name.",
                mapOf(
                    "name" to prop("string", "Skill name to toggle."),
                    "enabled" to prop("boolean", "true to enable, false to disable."),
                ),
                listOf("name", "enabled"),
            ),
            tool(
                "tool_toggle",
                "Enable or disable a tool plugin by id. Best-effort: returns a hint when the " +
                    "host does not support dynamic tool disabling.",
                mapOf(
                    "name" to prop("string", "Tool/plugin id to toggle."),
                    "enabled" to prop("boolean", "true to enable, false to disable."),
                ),
                listOf("name", "enabled"),
            ),
        )
    }

    override fun handles(name: String): Boolean =
        name == "config_set" || name == "config_get" ||
            name == "skill_toggle" || name == "tool_toggle"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        try {
            when (name) {
                "config_set" -> configSet(arguments)
                "config_get" -> configGet(arguments)
                "skill_toggle" -> skillToggle(arguments)
                "tool_toggle" -> toolToggle(arguments)
                else -> err("unknown_tool", "Unknown meta tool: $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("MetaTool", "meta $name failed", e)
            err("tool_error", e.message)
        }

    // ── config_set / config_get ───────────────────────────────

    private suspend fun configSet(arguments: String): String {
        val key = argString("key", arguments) ?: return err("missing_key", "key is required")
        if (key !in configKeys) return err("invalid_key", "key '$key' is not whitelisted")
        val value = argString("value", arguments) ?: return err("missing_value", "value is required")
        when (key) {
            "model" -> settings.setSelectedModel(value)
            "temperature" -> {
                val v = value.toFloatOrNull() ?: return err("invalid_value", "temperature must be a number")
                if (v < 0f || v > 2f) return err("out_of_range", "temperature must be 0.0..2.0")
                settings.setDefaultTemperature(v)
            }
            "max_tokens" -> {
                val v = value.toIntOrNull() ?: return err("invalid_value", "max_tokens must be an integer")
                if (v <= 0) return err("out_of_range", "max_tokens must be positive")
                settings.setDefaultMaxTokens(v)
            }
            "top_p" -> {
                val v = value.toFloatOrNull() ?: return err("invalid_value", "top_p must be a number")
                if (v < 0f || v > 1f) return err("out_of_range", "top_p must be 0.0..1.0")
                settings.setDefaultTopP(v)
            }
            "system_prompt_addon" -> settings.addSystemPrompt(
                title = "AI Addon",
                systemItems = listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = value)),
                userPrependItems = emptyList(),
                userPostpendItems = emptyList(),
            )
            "pet_enabled" -> {
                val v = value.toBooleanStrictOrNull()
                    ?: return err("invalid_value", "pet_enabled must be 'true'/'false'")
                settings.savePetOverlayEnabled(v)
            }
            "pet_character" -> settings.savePetOverlayCharacter(value)
        }
        return ok(key, value)
    }

    private fun configGet(arguments: String): String {
        val key = argString("key", arguments) ?: return err("missing_key", "key is required")
        if (key !in configKeys) return err("invalid_key", "key '$key' is not whitelisted")
        val current: String = when (key) {
            "model" -> settings.selectedModel.value
            "temperature" -> settings.defaultTemperature.value?.toString() ?: ""
            "max_tokens" -> settings.defaultMaxTokens.value?.toString() ?: ""
            "top_p" -> settings.defaultTopP.value?.toString() ?: ""
            "system_prompt_addon" -> {
                val id = settings.activeSystemPromptId.value
                settings.systemPrompts.value.firstOrNull { it.id == id }
                    ?.resolvedSystemItems?.joinToString("") { it.value } ?: ""
            }
            "pet_enabled" -> settings.petOverlayEnabled.value.toString()
            "pet_character" -> settings.petOverlayCharacter.value
        }
        return ok(key, current)
    }

    // ── skill_toggle / tool_toggle ────────────────────────────

    private fun skillToggle(arguments: String): String {
        val name = argString("name", arguments) ?: return err("missing_name", "name is required")
        val enabled = argBool("enabled", arguments) ?: return err("missing_enabled", "enabled is required")
        if (skillHost.skill(name) == null) return err("skill_not_found", "skill '$name' is not registered")
        skillHost.setEnabled(name, enabled)
        return toggleOk("skill_toggle", name, enabled)
    }

    private fun toolToggle(arguments: String): String {
        val name = argString("name", arguments) ?: return err("missing_name", "name is required")
        val enabled = argBool("enabled", arguments) ?: return err("missing_enabled", "enabled is required")
        val host = pluginHost
        if (host == null || host.plugins.value.none { it.manifest.id == name }) {
            return err("not_supported", "Dynamic tool disabling is not supported")
        }
        host.setEnabled(name, enabled)
        return toggleOk("tool_toggle", name, enabled)
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String>,
    ) = ToolDefinition(function = ToolFunction(
        name = name,
        description = description,
        parameters = ToolParameters(properties = properties, required = required),
    ))

    private fun argString(key: String, arguments: String): String? {
        val stripped = arguments.ifBlank { "{}" }
        return try {
            val el = Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]
            val v = el?.content ?: return null
            if (v == "null") null else v
        } catch (_: Exception) {
            null
        }
    }

    private fun argBool(key: String, arguments: String): Boolean? =
        argString(key, arguments)?.toBooleanStrictOrNull()

    private fun ok(key: String, value: String) = buildJsonObject {
        put("type", "config")
        put("key", key)
        put("value", value)
        put("ok", true)
    }.toString()

    private fun toggleOk(type: String, name: String, enabled: Boolean) = buildJsonObject {
        put("type", type)
        put("name", name)
        put("enabled", enabled)
        put("ok", true)
    }.toString()

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "meta_error")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}