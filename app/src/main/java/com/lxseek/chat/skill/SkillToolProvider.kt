package com.lxseek.chat.skill

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.tool.RiskLevel
import com.lxseek.chat.tool.ToolDescriptor
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.tool.ToolTier
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Exposes active skills as tools the LLM can call. Each active skill becomes a
 * `skill_<name>` tool whose execution returns the skill's Markdown body — the
 * on-demand load step of progressive disclosure.
 *
 * Tool design:
 * - **name**: `skill_<skill.name>` (prefixed to avoid collisions with built-in tools).
 * - **description**: the skill's one-line `description` (the token-cheap directory
 *   entry; the body is never injected into the tool definition).
 * - **tier**: [ToolTier.Extended] — skills are useful but non-essential.
 * - **riskLevel**: [RiskLevel.ReadOnly] — invoking a skill only returns prompt text;
 *   it performs no side effects itself.
 * - **summary**: the same `description`, so the disclosure layer can dedupe.
 * - **requiresMembership**: forwarded from the skill so the decorator / executor
 *   gate can hide it from non-members.
 *
 * The current file-path context for `paths` conditional activation is supplied via
 * [currentPathProvider] (defaults to null when no path context is wired in), keeping
 * the provider decoupled from any specific path source and avoiding changes to
 * [GenerationContext].
 *
 * @param skillHost            The skill registry.
 * @param currentPathProvider  Optional supplier of the current file path the model
 *                             is working on, used for `paths` conditional activation.
 */
class SkillToolProvider(
    private val skillHost: SkillHost,
    private val currentPathProvider: () -> String? = { null },
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        toolDescriptors(ctx).map { it.definition }

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> {
        val currentPath = currentPathProvider()
        return skillHost.activeSkills(currentPath, ctx.hasMembership).map { skill ->
            val toolName = toolNameFor(skill.name)
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = toolName,
                        description = skill.description,
                        parameters = ToolParameters(
                            properties = mapOf(
                                "input" to ToolProperty(
                                    "string",
                                    "Optional natural-language input or context for the skill.",
                                ),
                            ),
                            required = emptyList(),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.ReadOnly,
                tier = ToolTier.Extended,
                requiresApproval = false,
                requiresMembership = skill.requiresMembership,
                summary = skill.description,
            )
        }
    }

    override fun handles(name: String): Boolean =
        name.startsWith(PREFIX) && skillHost.skill(skillNameFromTool(name)) != null

    /** Look up the [Skill] backing a tool name, or null if the tool is not a skill tool. */
    fun skillFor(toolName: String): Skill? {
        if (!toolName.startsWith(PREFIX)) return null
        return skillHost.skill(skillNameFromTool(toolName))
    }

    override fun riskLevel(name: String): RiskLevel = RiskLevel.ReadOnly

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!name.startsWith(PREFIX)) return errorJson("Unknown tool: $name")
        val skillName = skillNameFromTool(name)
        val skill = skillHost.skill(skillName)
            ?: return errorJson("Unknown skill: $skillName")

        // Execution-layer membership gate (anti-bypass): the disclosure layer
        // (SkillHost.activeSkills) already hides gated skills from non-members,
        // but this is the definitive runtime check.
        if (skill.requiresMembership && !ctx.hasMembership) {
            return errorJson("This skill requires a membership subscription. Please upgrade to use it.")
        }

        val input = parseInput(arguments)
        return buildJsonObject {
            put("type", "skill")
            put("name", skill.name)
            put("description", skill.description)
            if (skill.whenToUse != null) put("when_to_use", skill.whenToUse)
            if (skill.allowedTools.isNotEmpty()) put("allowed_tools", skill.allowedTools.joinToString(", "))
            if (skill.context != null) put("context", skill.context)
            if (skill.model != null) put("model", skill.model)
            if (input != null) put("input", input)
            put("body", skill.body)
        }.toString()
    }

    private fun parseInput(arguments: String): String? =
        runCatching {
            val obj: JsonObject = Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
            obj["input"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    private fun errorJson(message: String): String = buildJsonObject {
        put("type", "skill")
        put("error", message)
    }.toString()

    companion object {
        private const val PREFIX = "skill_"

        /** Map a skill name to its tool name. */
        fun toolNameFor(skillName: String): String = PREFIX + skillName

        /** Inverse of [toolNameFor]; returns the skill name for a tool name. */
        fun skillNameFromTool(toolName: String): String = toolName.removePrefix(PREFIX)
    }
}