package com.lxseek.chat.skill

import kotlinx.serialization.Serializable

/**
 * A typed parameter for a [Skill], declared in SKILL.md frontmatter under the
 * `parameters` block. Each parameter is surfaced to the model as a JSON Schema
 * property on the `skill_<name>` tool, so the model can pass structured arguments
 * instead of free-form text.
 *
 * Supported [type] values: `"string"`, `"int"`, `"bool"`, `"enum"`. For
 * `"enum"`, [enumValues] lists the allowed options. [default] is the fallback
 * used when the model omits the parameter.
 *
 * @property name        Parameter key (becomes the JSON Schema property name).
 * @property type        One of `"string"`, `"int"`, `"bool"`, `"enum"`.
 * @property description Human-readable hint shown to the model.
 * @property required    Whether the model must supply this parameter.
 * @property default     Default value applied when the parameter is omitted.
 * @property enumValues  Allowed values when [type] == `"enum"` (ignored otherwise).
 */
@Serializable
data class SkillParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val default: String? = null,
    val enumValues: List<String> = emptyList(),
)

/**
 * Data model for a Skill, corresponding to the SKILL.md frontmatter format
 * (Markdown + YAML frontmatter) used by cc-haha, Operit, and Claude Skills.
 *
 * Progressive disclosure: only [name] + [description] are injected into the
 * model context as a directory entry. The full [body] is loaded on demand
 * only when the skill is activated (called by the model), saving tokens.
 *
 * @property name         frontmatter: name — the skill identifier.
 * @property description  frontmatter: description — one-line summary shown in
 *                        the disclosure directory; the token-cheap entry.
 * @property whenToUse    frontmatter: when_to_use — natural-language hint for
 *                        when the model should pick this skill.
 * @property allowedTools frontmatter: allowed-tools — the skill's self-imposed
 *                        tool scope (a third gate form, orthogonal to membership).
 * @property paths        frontmatter: paths — glob patterns for conditional
 *                        activation; the skill is only exposed when the current
 *                        file path matches one of these patterns (saves tokens).
 * @property context      frontmatter: context — "inline" (default) or "fork"
 *                        (sub-agent isolation with an independent token budget).
 * @property model        frontmatter: model — a cheaper model to run this skill
 *                        (saves cost).
 * @property body         The Markdown body (skill content / prompt), loaded on demand.
 * @property source       The file path or source identifier this skill was parsed from.
 * @property requiresMembership True if an active membership is required to disclose
 *                        and execute this skill. Orthogonal to [allowedTools].
 * @property parameters   Typed parameters declared in frontmatter; surfaced to the
 *                        model as the `skill_<name>` tool's JSON Schema. Empty by
 *                        default, so legacy skills without parameters are unchanged.
 * @property chainedTo    frontmatter: chained_to — name of another skill whose
 *                        input is this skill's output (skill composition). null
 *                        by default for backward compatibility.
 */
@Serializable
data class Skill(
    val name: String,
    val description: String,
    val whenToUse: String? = null,
    val allowedTools: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val context: String? = null,
    val model: String? = null,
    val body: String = "",
    val source: String = "",
    val requiresMembership: Boolean = false,
    val parameters: List<SkillParameter> = emptyList(),
    val chainedTo: String? = null,
)