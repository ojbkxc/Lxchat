package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.data.ActivityJournal
import com.lxseek.chat.skill.Skill
import com.lxseek.chat.skill.SkillHost
import com.lxseek.chat.skill.UserSkillStore
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 技能学习与维护工具集 —— 对应 Hermes「从对话沉淀技能（/learn）+ Curator 定期整理」。
 *
 * 让模型把一次对话里反复出现的流程沉淀为可复用技能（SKILL.md），落到
 * [UserSkillStore]（filesDir/skills_user/），并立即注册进 [SkillHost] 供后续调用；
 * 同时提供 [CURATE_SKILLS] 让模型基于 [SkillHost.usageSnapshot] 的使用统计发现
 * 无人使用的技能，向用户提议精简/合并/删除。
 *
 * 写操作（create/update/delete_skill）全部 requiresApproval —— 创建技能会写入文件并改变
 * 后续会话的模型工具面，必须经过用户确认（对应 skillify 的 Step 4 确认环节）。
 *
 * @param store     用户技能持久化存储。
 * @param skillHost 技能注册表（新增技能注册、删除技能注销、使用统计查询）。
 * @param journal   活动日志（journey 数据源）。
 */
class SkillLearnToolProvider(
    private val store: UserSkillStore,
    private val skillHost: SkillHost,
    private val journal: ActivityJournal,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = CREATE_SKILL,
                description = "Create a reusable skill from this conversation's repeatable process and persist it as a SKILL.md file. Requires user approval. Use at the end of a process worth capturing.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "name" to ToolProperty("string", "Short skill identifier (lowercase, hyphens ok, e.g. 'weekly-report')."),
                        "description" to ToolProperty("string", "One-line summary shown to the model in the skills directory."),
                        "body" to ToolProperty("string", "The Markdown skill body: goal, steps with success criteria, rules."),
                        "when_to_use" to ToolProperty("string", "When the model should auto-invoke this skill, with trigger phrases. Optional."),
                        "allowed_tools" to ToolProperty("array", "Comma-separated or array of tool names the skill is allowed to use. Optional.", items = ToolProperty("string", "A tool name.")),
                    ),
                    required = listOf("name", "description", "body"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = UPDATE_SKILL,
                description = "Update an existing user-created skill (name/description/body/frontmatter). Requires user approval.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "name" to ToolProperty("string", "The exact skill name to update."),
                        "description" to ToolProperty("string", "New one-line description. Omit to keep."),
                        "body" to ToolProperty("string", "New Markdown body. Omit to keep."),
                        "when_to_use" to ToolProperty("string", "New when_to_use. Omit to keep; empty string to clear."),
                        "allowed_tools" to ToolProperty("array", "New allowed-tools list. Omit to keep.", items = ToolProperty("string", "A tool name.")),
                    ),
                    required = listOf("name"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = DELETE_SKILL,
                description = "Delete a user-created skill. Destructive and requires user approval.",
                parameters = ToolParameters(
                    properties = mapOf("name" to ToolProperty("string", "The exact skill name to delete.")),
                    required = listOf("name"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = LIST_SKILLS,
                description = "List all registered skills with their enable state, usage counts, and first/last used timestamps.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = CURATE_SKILLS,
                description = "Report usage statistics to find skills nobody uses (curator). Returns per-skill call counts and staleness so you can propose pruning, merging, or keeping them — ask the user before deleting anything.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "min_calls" to ToolProperty("integer", "Skills with call count below this are flagged as underused. Default 3."),
                        "max_days" to ToolProperty("integer", "Skills not used within this many days are flagged as stale. Default 30."),
                    ),
                    required = emptyList(),
                ),
            ),
        ),
    )

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> = listOf(
        descriptor(CREATE_SKILL, "Create a skill from this conversation.", RiskLevel.Moderate, true),
        descriptor(UPDATE_SKILL, "Update a user skill.", RiskLevel.Moderate, true),
        descriptor(DELETE_SKILL, "Delete a user skill.", RiskLevel.HighRisk, true),
        descriptor(LIST_SKILLS, "List skills + usage.", RiskLevel.ReadOnly, false),
        descriptor(CURATE_SKILLS, "Find underused/stale skills.", RiskLevel.ReadOnly, false),
    )

    private fun descriptor(
        name: String,
        summary: String,
        risk: RiskLevel,
        approval: Boolean,
    ): ToolDescriptor = ToolDescriptor(
        definition = definitions(GenerationContext()).first { it.function.name == name },
        riskLevel = risk,
        tier = ToolTier.Extended,
        requiresApproval = approval,
        summary = summary,
    )

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override fun riskLevel(name: String): RiskLevel = when (name) {
        CREATE_SKILL, UPDATE_SKILL -> RiskLevel.Moderate
        DELETE_SKILL -> RiskLevel.HighRisk
        else -> RiskLevel.ReadOnly
    }

    override fun requiresApprovalByDefault(name: String): Boolean =
        name in setOf(CREATE_SKILL, UPDATE_SKILL, DELETE_SKILL)

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        }.getOrElse { return "Error: invalid arguments" }

        return when (name) {
            CREATE_SKILL -> createSkill(args)
            UPDATE_SKILL -> updateSkill(args)
            DELETE_SKILL -> deleteSkill(args)
            LIST_SKILLS -> listSkills()
            CURATE_SKILLS -> curateSkills(args)
            else -> "Error: Unknown tool: $name"
        }
    }

    // ── create_skill ─────────────────────────────────────────

    private suspend fun createSkill(args: JsonObject): String {
        val name = args.str("name")?.trim().orEmpty()
        if (name.isEmpty()) return "Error: name is required"
        val description = args.str("description")?.trim().orEmpty()
        if (description.isEmpty()) return "Error: description is required"
        val body = args.str("body")?.trim().orEmpty()
        if (body.isEmpty()) return "Error: body is required"
        val whenToUse = args.str("when_to_use")?.trim()?.takeIf { it.isNotEmpty() }
        val allowedTools = args.strList("allowed_tools")
        if (skillHost.skill(name) != null) {
            return "Error: a skill named '$name' already exists. Use update_skill to modify it."
        }

        val skill = Skill(
            name = name,
            description = description,
            whenToUse = whenToUse,
            allowedTools = allowedTools,
            body = body,
            source = store.fileFor(name).absolutePath,
        )
        val path = store.save(skill)
        skillHost.register(skill, enabled = true)
        journal.record(ActivityJournal.Kind.SKILL, "create_skill", name, path)

        return buildJsonObject {
            put("type", CREATE_SKILL)
            put("status", "created")
            put("name", name)
            put("path", path)
            put("note", "Skill saved and enabled. Users can invoke it via /$name or the model can auto-trigger it. Edit skills_user/$name.md directly to refine.")
        }.toString()
    }

    // ── update_skill ─────────────────────────────────────────

    private suspend fun updateSkill(args: JsonObject): String {
        val name = args.str("name")?.trim().orEmpty()
        if (name.isEmpty()) return "Error: name is required"
        if (!store.isUserSkill(name)) {
            return "Error: '$name' is not a user-created skill. Only skills created via create_skill can be updated."
        }
        val existing = skillHost.skill(name) ?: store.loadAll().firstOrNull { it.name == name }
            ?: return "Error: skill '$name' not found"

        val description = args.str("description")?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing.description
        val body = args.str("body")?.trim()?.takeIf { it.isNotEmpty() } ?: existing.body
        val whenToUse = if (args.containsKey("when_to_use")) {
            args.str("when_to_use")?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            existing.whenToUse
        }
        val allowedTools = if (args.containsKey("allowed_tools")) {
            args.strList("allowed_tools")
        } else {
            existing.allowedTools
        }

        val updated = existing.copy(
            description = description,
            body = body,
            whenToUse = whenToUse,
            allowedTools = allowedTools,
        )
        val path = store.save(updated)
        skillHost.register(updated, enabled = true)
        journal.record(ActivityJournal.Kind.SKILL, "update_skill", name, path)

        return buildJsonObject {
            put("type", UPDATE_SKILL)
            put("status", "updated")
            put("name", name)
            put("path", path)
        }.toString()
    }

    // ── delete_skill ─────────────────────────────────────────

    private suspend fun deleteSkill(args: JsonObject): String {
        val name = args.str("name")?.trim().orEmpty()
        if (name.isEmpty()) return "Error: name is required"
        if (!store.isUserSkill(name)) {
            return "Error: '$name' is not a user-created skill. Only user skills created via create_skill can be deleted."
        }
        store.delete(name)
        skillHost.unregister(name)
        journal.record(ActivityJournal.Kind.SKILL, "delete_skill", name)
        return buildJsonObject {
            put("type", DELETE_SKILL)
            put("status", "deleted")
            put("name", name)
        }.toString()
    }

    // ── list_skills ──────────────────────────────────────────

    private fun listSkills(): String {
        val now = System.currentTimeMillis()
        val usage = skillHost.usageSnapshot().associateBy { it.name }
        return buildJsonObject {
            put("type", LIST_SKILLS)
            putJsonArray("skills") {
                skillHost.skills.value.sortedBy { it.skill.name }.forEach { info ->
                    val u = usage[info.skill.name]
                    val lastUsed = u?.lastUsedAt ?: 0L
                    add(
                        buildJsonObject {
                            put("name", info.skill.name)
                            put("enabled", info.enabled)
                            put("user_created", store.isUserSkill(info.skill.name))
                            put("calls", u?.callCount ?: 0)
                            put("first_seen_at", u?.firstSeenAt ?: 0L)
                            put("last_used_at", lastUsed)
                            put("days_since_used", if (lastUsed > 0L) ((now - lastUsed) / 86_400_000L).toInt() else -1)
                            put("description", info.skill.description.take(200))
                        },
                    )
                }
            }
        }.toString()
    }

    // ── curate_skills ────────────────────────────────────────

    private fun curateSkills(args: JsonObject): String {
        val minCalls = (args["min_calls"] as? JsonPrimitive)?.longOrNull
            ?: args["min_calls"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
            ?: 3L
        val maxDays = (args["max_days"] as? JsonPrimitive)?.longOrNull
            ?: args["max_days"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
            ?: 30L
        val now = System.currentTimeMillis()
        val usage = skillHost.usageSnapshot().associateBy { it.name }

        return buildJsonObject {
            put("type", CURATE_SKILLS)
            put("thresholds", buildJsonObject {
                put("min_calls", minCalls)
                put("max_days", maxDays)
            })
            putJsonArray("underused") {
                skillHost.skills.value.sortedBy { it.skill.name }.forEach { info ->
                    val u = usage[info.skill.name]
                    val calls = u?.callCount ?: 0
                    val lastUsed = u?.lastUsedAt ?: 0L
                    val daysSince = if (lastUsed > 0L) ((now - lastUsed) / 86_400_000L).toInt() else Int.MAX_VALUE
                    val flagged = calls < minCalls || daysSince > maxDays
                    if (flagged) {
                        add(
                            buildJsonObject {
                                put("name", info.skill.name)
                                put("calls", calls)
                                put("days_since_used", if (daysSince == Int.MAX_VALUE) "never" else daysSince)
                                put("user_created", store.isUserSkill(info.skill.name))
                                put("description", info.skill.description.take(200))
                                put("reason", buildString {
                                    if (calls < minCalls) append("used $calls times (<$minCalls); ")
                                    if (daysSince > maxDays) append("not used in ${if (daysSince == Int.MAX_VALUE) "longer than" else "$daysSince days"} (>$maxDays days)")
                                    if (calls == 0 && daysSince == Int.MAX_VALUE) append("never invoked")
                                })
                            },
                        )
                    }
                }
            }
            put("recommendation", "Present the flagged skills to the user and propose keep / update / merge / delete. Only delete after explicit user confirmation via delete_skill.")
        }.toString()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.strList(key: String): List<String> {
        val raw = this[key] ?: return emptyList()
        return when {
            raw is JsonPrimitive -> raw.contentOrNull
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            else -> runCatching {
                (raw as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { v -> v.isNotBlank() } }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }

    private companion object {
        const val CREATE_SKILL = "create_skill"
        const val UPDATE_SKILL = "update_skill"
        const val DELETE_SKILL = "delete_skill"
        const val LIST_SKILLS = "list_skills"
        const val CURATE_SKILLS = "curate_skills"
        val TOOL_NAMES = setOf(CREATE_SKILL, UPDATE_SKILL, DELETE_SKILL, LIST_SKILLS, CURATE_SKILLS)
    }
}
