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
                            properties = buildToolProperties(skill),
                            required = buildRequiredParams(skill),
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

    /**
     * Build the JSON Schema properties for a skill tool. Always includes the legacy
     * `input` string property (free-form context, backward compatibility) plus one
     * property per declared [SkillParameter].
     */
    private fun buildToolProperties(skill: Skill): Map<String, ToolProperty> {
        val props = LinkedHashMap<String, ToolProperty>()
        props["input"] = ToolProperty(
            "string",
            "Optional natural-language input or context for the skill.",
        )
        for (param in skill.parameters) {
            props[param.name] = ToolProperty(
                type = mapParamTypeToJson(param.type),
                description = buildParamDescription(param),
            )
        }
        return props
    }

    /** Map a [SkillParameter.type] to a JSON Schema type. Unknown types default to string. */
    private fun mapParamTypeToJson(type: String): String = when (type.lowercase()) {
        "int" -> "integer"
        "bool" -> "boolean"
        "string", "enum" -> "string"
        else -> "string"
    }

    /**
     * Build the human-readable description for a parameter. For enum types the
     * allowed values are appended (since [ToolProperty] has no native enum field),
     * and a default value is noted when present.
     */
    private fun buildParamDescription(param: SkillParameter): String {
        val sb = StringBuilder(param.description)
        if (param.type.lowercase() == "enum" && param.enumValues.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("One of: ").append(param.enumValues.joinToString(", "))
        }
        if (param.default != null) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("(default: ").append(param.default).append(")")
        }
        return sb.toString()
    }

    /** Required parameter names (those flagged required and with a non-empty name). */
    private fun buildRequiredParams(skill: Skill): List<String> =
        skill.parameters.asSequence()
            .filter { it.required && it.name.isNotEmpty() }
            .map { it.name }
            .toList()

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

        skillHost.recordUsage(skillName)

        val input = parseInput(arguments)
        val paramValues = parseParameterValues(arguments, skill.parameters)
        val renderedBody = renderBody(skill, paramValues)
        return buildJsonObject {
            put("type", "skill")
            put("name", skill.name)
            put("description", skill.description)
            if (skill.whenToUse != null) put("when_to_use", skill.whenToUse)
            if (skill.allowedTools.isNotEmpty()) put("allowed_tools", skill.allowedTools.joinToString(", "))
            if (skill.context != null) put("context", skill.context)
            if (skill.model != null) put("model", skill.model)
            if (input != null) put("input", input)
            if (paramValues.isNotEmpty()) {
                put("parameters", buildJsonObject {
                    paramValues.forEach { (k, v) -> put(k, v) }
                })
            }
            if (skill.chainedTo != null) put("chained_to", skill.chainedTo)
            put("body", renderedBody)
        }.toString()
    }

    /**
     * Parse declared parameter values from the tool arguments. For each declared
     * [SkillParameter], takes the value supplied by the model, falling back to the
     * parameter's `default` when omitted. Returns an empty map when the skill has
     * no parameters (legacy skills), preserving backward compatibility.
     */
    private fun parseParameterValues(arguments: String, params: List<SkillParameter>): Map<String, String> {
        if (params.isEmpty()) return emptyMap()
        val obj: JsonObject = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: return emptyMap()

        val result = LinkedHashMap<String, String>()
        for (param in params) {
            if (param.name.isEmpty()) continue
            val raw = runCatching { obj[param.name]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val value = raw ?: param.default
            if (value != null) result[param.name] = value
        }
        return result
    }

    /**
     * Render the final body returned to the model. When the skill has parameters,
     * a `--- Parameters ---` block is prepended with the resolved key/value pairs.
     * When the skill is chained to another skill (`chainedTo`), a `--- Next Skill ---`
     * trailer is appended instructing the model to invoke the next skill with the
     * result. When neither applies, the body is returned unchanged (backward compat).
     */
    private fun renderBody(skill: Skill, paramValues: Map<String, String>): String {
        if (paramValues.isEmpty() && skill.chainedTo == null) return skill.body

        val sb = StringBuilder()
        if (paramValues.isNotEmpty()) {
            sb.append("--- Parameters ---\n")
            paramValues.forEach { (k, v) ->
                sb.append(k).append(": ").append(v).append('\n')
            }
            sb.append('\n')
        }
        sb.append(skill.body)
        if (skill.chainedTo != null) {
            sb.append("\n\n--- Next Skill ---\n")
            sb.append("After completing this skill, invoke skill_")
            sb.append(skill.chainedTo)
            sb.append(" with the result.\n")
        }
        return sb.toString()
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