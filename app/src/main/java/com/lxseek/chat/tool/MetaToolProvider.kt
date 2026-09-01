package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.data.PromptItemType
import com.lxseek.chat.data.PromptTemplateItem
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.membership.MembershipTier
import com.lxseek.chat.plugin.PluginHost
import com.lxseek.chat.skill.SkillHost
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.agent.GenerationContext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Conversation-level meta tools: let the model tune the app configuration
 * (model, temperature, pet, …) and toggle skills/tools without leaving the
 * chat. Inspired by dph project4's /mobile customization meta ability — the
 * user says "switch to gpt-4" or "set temperature to 0.5" and the model calls
 * a tool instead of directing the user to the settings page.
 *
 * Eight tools are exposed (all [ToolTier.Extended]):
 * - `config_set` / `config_get` — read/write whitelisted config keys.
 * - `skill_toggle` / `tool_toggle` — enable/disable skills and tool plugins.
 * - `get_permission_matrix` — inspect the per-tool permission matrix (ReadOnly).
 * - `set_permission` — override a single tool's permission row (Moderate).
 * - `rollback_config` — revert to a prior config snapshot (Moderate).
 * - `get_audit_log` — query the configuration change audit log (ReadOnly).
 *
 * When [snapshotManager] and [auditLog] are provided, mutating tools
 * automatically capture a pre-change snapshot and record an audit entry,
 * giving the agent safe rollback and full traceability of every config change.
 */
class MetaToolProvider(
    private val settings: SettingsRepository,
    private val skillHost: SkillHost,
    private val pluginHost: PluginHost? = null,
    private val permissionMatrix: ToolPermissionMatrix? = null,
    private val snapshotManager: ConfigSnapshotManager? = null,
    private val auditLog: ConfigAuditLog? = null,
) : ToolProvider {

    /** Whitelisted config keys the model is allowed to read/write. */
    private val configKeys = setOf(
        "model", "temperature", "max_tokens", "top_p",
        "system_prompt_addon", "pet_enabled", "pet_character",
    )

    /** Meta tools that only read state — they carry [RiskLevel.ReadOnly]. */
    private val readOnlyTools = setOf("config_get", "get_permission_matrix", "get_audit_log")

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        definitions(ctx).map { def ->
            val name = def.function.name
            ToolDescriptor(
                definition = def,
                riskLevel = if (name in readOnlyTools) RiskLevel.ReadOnly else RiskLevel.Moderate,
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
            tool(
                "get_permission_matrix",
                "Return the full per-tool permission matrix: every registered tool's enabled " +
                    "flag, required membership tier, approval flag, risk level, and quotas.",
                emptyMap(),
                emptyList(),
            ),
            tool(
                "set_permission",
                "Override a single tool's permission row. All fields except toolName are " +
                    "optional; omitted fields keep the current value. " +
                    "requiresMembership: 'Free'|'Premium'|'Pro'. " +
                    "riskLevel: 'ReadOnly'|'LowRisk'|'Moderate'|'HighRisk'|'Destructive'. " +
                    "dailyQuota/sessionQuota: null clears the quota.",
                mapOf(
                    "toolName" to prop("string", "Tool name to update."),
                    "enabled" to prop("boolean", "Enable/disable the tool."),
                    "requiresMembership" to prop("string", "Required membership tier."),
                    "requiresApproval" to prop("boolean", "Whether explicit approval is needed."),
                    "riskLevel" to prop("string", "Risk level classification."),
                    "dailyQuota" to prop("integer", "Daily invocation quota (null = unlimited)."),
                    "sessionQuota" to prop("integer", "Per-session invocation quota (null = unlimited)."),
                ),
                listOf("toolName"),
            ),
            tool(
                "rollback_config",
                "Roll back to a prior configuration snapshot. Pass snapshotId to target a " +
                    "specific snapshot; omit it to roll back to the latest one. " +
                    "Returns the snapshot id and reason.",
                mapOf(
                    "snapshotId" to prop("string", "Snapshot id to roll back to (optional)."),
                ),
                emptyList(),
            ),
            tool(
                "get_audit_log",
                "Query the configuration change audit log. Pass toolName to filter by the " +
                    "tool that performed the change; omit it to query all. " +
                    "Results are most-recent first, capped by limit (default 20).",
                mapOf(
                    "toolName" to prop("string", "Filter by tool name (optional)."),
                    "limit" to prop("integer", "Max entries to return (default 20)."),
                ),
                emptyList(),
            ),
        )
    }

    override fun handles(name: String): Boolean = name in HANDLED_TOOLS

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        try {
            when (name) {
                "config_set" -> configSet(arguments, ctx)
                "config_get" -> configGet(arguments)
                "skill_toggle" -> skillToggle(arguments)
                "tool_toggle" -> toolToggle(arguments, ctx)
                "get_permission_matrix" -> getPermissionMatrix()
                "set_permission" -> setPermission(arguments, ctx)
                "rollback_config" -> rollbackConfig(arguments, ctx)
                "get_audit_log" -> getAuditLog(arguments)
                else -> err("unknown_tool", "Unknown meta tool: $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("MetaTool", "meta $name failed", e)
            err("tool_error", e.message)
        }

    // ── config_set / config_get ───────────────────────────────

    private suspend fun configSet(arguments: String, ctx: GenerationContext): String {
        val key = argString("key", arguments) ?: return err("missing_key", "key is required")
        if (key !in configKeys) return err("invalid_key", "key '$key' is not whitelisted")
        val value = argString("value", arguments) ?: return err("missing_value", "value is required")
        // Validate before mutating so a bad value does not create a snapshot.
        validateConfigValue(key, value)?.let { return it }

        val oldValue = readConfigValue(key)
        // Capture pre-change snapshot so rollback restores the previous state.
        snapshotManager?.createSnapshot(
            configMap = captureCurrentConfig(),
            reason = "before config_set $key=$value",
        )
        applyConfigKey(key, value)
        auditLog?.log(
            AuditLogEntry(
                timestamp = System.currentTimeMillis(),
                toolName = "config_set",
                changeType = "config_set:$key",
                oldValue = oldValue,
                newValue = value,
                agentId = ctx.conversationId ?: "meta-tool",
            )
        )
        // Be honest about scope: conversation-level override still wins in the
        // active conversation, so switching the global default does not change
        // the model used by a conversation that pins one. Without this note the
        // tool would report success while the next message still uses the old
        // model, misleading the agent.
        return if (key == "model") {
            ok(key, value, "已更新全局默认模型，新对话立即生效；当前对话如已指定模型则保持原模型")
        } else {
            ok(key, value)
        }
    }

    private fun configGet(arguments: String): String {
        val key = argString("key", arguments) ?: return err("missing_key", "key is required")
        if (key !in configKeys) return err("invalid_key", "key '$key' is not whitelisted")
        return ok(key, readConfigValue(key))
    }

    /** Read the current string value of a whitelisted config key. */
    private fun readConfigValue(key: String): String = when (key) {
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
        else -> ""
    }

    /** Validate a config value; returns an error string on failure, null on success. */
    private fun validateConfigValue(key: String, value: String): String? = when (key) {
        "temperature" -> {
            val v = value.toFloatOrNull() ?: return err("invalid_value", "temperature must be a number")
            if (v < 0f || v > 2f) err("out_of_range", "temperature must be 0.0..2.0") else null
        }
        "max_tokens" -> {
            val v = value.toIntOrNull() ?: return err("invalid_value", "max_tokens must be an integer")
            if (v <= 0) err("out_of_range", "max_tokens must be positive") else null
        }
        "top_p" -> {
            val v = value.toFloatOrNull() ?: return err("invalid_value", "top_p must be a number")
            if (v < 0f || v > 1f) err("out_of_range", "top_p must be 0.0..1.0") else null
        }
        "pet_enabled" -> {
            if (value.toBooleanStrictOrNull() == null)
                err("invalid_value", "pet_enabled must be 'true'/'false'") else null
        }
        else -> null
    }

    /**
     * Apply a validated config key/value to [settings].
     *
     * 挂起函数：pet_enabled / pet_character 的保存走 suspend 存储接口，
     * 由调用方协程直接挂起等待，避免 runBlocking 阻塞调度线程。
     */
    private suspend fun applyConfigKey(key: String, value: String) {
        when (key) {
            "model" -> settings.setSelectedModel(value)
            "temperature" -> settings.setDefaultTemperature(value.toFloat())
            "max_tokens" -> settings.setDefaultMaxTokens(value.toInt())
            "top_p" -> settings.setDefaultTopP(value.toFloat())
            "system_prompt_addon" -> settings.addSystemPrompt(
                title = "AI Addon",
                systemItems = listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = value)),
                userPrependItems = emptyList(),
                userPostpendItems = emptyList(),
            )
            "pet_enabled" -> settings.savePetOverlayEnabled(value.toBooleanStrict())
            "pet_character" -> settings.savePetOverlayCharacter(value)
        }
    }

    /** Snapshot the full whitelisted config map for rollback. */
    private fun captureCurrentConfig(): Map<String, String> = linkedMapOf(
        "model" to settings.selectedModel.value,
        "temperature" to (settings.defaultTemperature.value?.toString() ?: ""),
        "max_tokens" to (settings.defaultMaxTokens.value?.toString() ?: ""),
        "top_p" to (settings.defaultTopP.value?.toString() ?: ""),
        "system_prompt_addon" to run {
            val id = settings.activeSystemPromptId.value
            settings.systemPrompts.value.firstOrNull { it.id == id }
                ?.resolvedSystemItems?.joinToString("") { it.value } ?: ""
        },
        "pet_enabled" to settings.petOverlayEnabled.value.toString(),
        "pet_character" to settings.petOverlayCharacter.value,
    )

    /** Best-effort restore of a config map from a snapshot. 挂起函数：内部经 [applyConfigKey] 触发 suspend 保存。 */
    private suspend fun applyConfigMap(configMap: Map<String, String>) {
        for ((key, value) in configMap) {
            if (key !in configKeys || value.isEmpty()) continue
            // Skip system_prompt_addon: addSystemPrompt appends rather than replaces,
            // so restoring it would create duplicates. All other keys are safe.
            if (key == "system_prompt_addon") continue
            // Validate before applying so a corrupted snapshot does not crash.
            if (validateConfigValue(key, value) != null) continue
            try {
                applyConfigKey(key, value)
            } catch (e: Exception) {
                DebugLog.w("MetaTool", "rollback skip $key=$value")
            }
        }
    }

    // ── skill_toggle / tool_toggle ────────────────────────────

    private fun skillToggle(arguments: String): String {
        val name = argString("name", arguments) ?: return err("missing_name", "name is required")
        val enabled = argBool("enabled", arguments) ?: return err("missing_enabled", "enabled is required")
        if (skillHost.skill(name) == null) return err("skill_not_found", "skill '$name' is not registered")
        skillHost.setEnabled(name, enabled)
        return toggleOk("skill_toggle", name, enabled)
    }

    private fun toolToggle(arguments: String, ctx: GenerationContext): String {
        val name = argString("name", arguments) ?: return err("missing_name", "name is required")
        val enabled = argBool("enabled", arguments) ?: return err("missing_enabled", "enabled is required")
        val host = pluginHost
        if (host == null || host.plugins.value.none { it.manifest.id == name }) {
            return err("not_supported", "Dynamic tool disabling is not supported")
        }
        host.setEnabled(name, enabled)
        auditLog?.log(
            AuditLogEntry(
                timestamp = System.currentTimeMillis(),
                toolName = "tool_toggle",
                changeType = "tool_toggle:$name",
                oldValue = (!enabled).toString(),
                newValue = enabled.toString(),
                agentId = ctx.conversationId ?: "meta-tool",
            )
        )
        return toggleOk("tool_toggle", name, enabled)
    }

    // ── get_permission_matrix / set_permission ───────────────

    private fun getPermissionMatrix(): String {
        val matrix = permissionMatrix ?: return err("not_supported", "Permission matrix is not configured")
        return buildJsonObject {
            put("type", "permission_matrix")
            putJsonObject("tools") {
                for ((name, perm) in matrix.getMatrix()) {
                    putJsonObject(name) {
                        put("enabled", perm.enabled)
                        put("requiresMembership", perm.requiresMembership.name)
                        put("requiresApproval", perm.requiresApproval)
                        put("riskLevel", perm.riskLevel.name)
                        put("dailyQuota", perm.dailyQuota)
                        put("sessionQuota", perm.sessionQuota)
                    }
                }
            }
            put("ok", true)
        }.toString()
    }

    private fun setPermission(arguments: String, ctx: GenerationContext): String {
        val toolName = argString("toolName", arguments)
            ?: return err("missing_toolName", "toolName is required")
        val matrix = permissionMatrix
            ?: return err("not_supported", "Permission matrix is not configured")
        val current = matrix.getPermission(toolName) ?: ToolPermission(toolName = toolName)
        val newPerm = current.copy(
            enabled = argBool("enabled", arguments) ?: current.enabled,
            requiresMembership = argString("requiresMembership", arguments)
                ?.let { MembershipTier.parse(it) } ?: current.requiresMembership,
            requiresApproval = argBool("requiresApproval", arguments) ?: current.requiresApproval,
            riskLevel = argString("riskLevel", arguments)?.let { parseRiskLevel(it) }
                ?: current.riskLevel,
            dailyQuota = argString("dailyQuota", arguments)?.let { it.toIntOrNull() }
                ?: current.dailyQuota,
            sessionQuota = argString("sessionQuota", arguments)?.let { it.toIntOrNull() }
                ?: current.sessionQuota,
        )
        matrix.setPermission(toolName, newPerm)
        auditLog?.log(
            AuditLogEntry(
                timestamp = System.currentTimeMillis(),
                toolName = toolName,
                changeType = "set_permission",
                oldValue = permSummary(current),
                newValue = permSummary(newPerm),
                agentId = ctx.conversationId ?: "meta-tool",
            )
        )
        return buildJsonObject {
            put("type", "set_permission")
            put("toolName", toolName)
            put("ok", true)
        }.toString()
    }

    private fun parseRiskLevel(s: String): RiskLevel? =
        RiskLevel.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }

    private fun permSummary(p: ToolPermission): String =
        "enabled=${p.enabled},tier=${p.requiresMembership.name},approval=${p.requiresApproval}," +
            "risk=${p.riskLevel.name},daily=${p.dailyQuota},session=${p.sessionQuota}"

    // ── rollback_config / get_audit_log ───────────────────────

    private suspend fun rollbackConfig(arguments: String, ctx: GenerationContext): String {
        val manager = snapshotManager
            ?: return err("not_supported", "Snapshot manager is not configured")
        val snapshotId = argString("snapshotId", arguments)
        val snapshot = if (snapshotId != null) {
            manager.rollback(snapshotId)
                ?: return err("snapshot_not_found", "Snapshot '$snapshotId' not found")
        } else {
            manager.getLatestSnapshot()
                ?: return err("no_snapshot", "No snapshot available to roll back to")
        }
        applyConfigMap(snapshot.configMap)
        auditLog?.log(
            AuditLogEntry(
                timestamp = System.currentTimeMillis(),
                toolName = "rollback_config",
                changeType = "rollback",
                oldValue = "",
                newValue = snapshot.snapshotId,
                agentId = ctx.conversationId ?: "meta-tool",
            )
        )
        return buildJsonObject {
            put("type", "rollback_config")
            put("snapshotId", snapshot.snapshotId)
            put("reason", snapshot.reason)
            put("ok", true)
        }.toString()
    }

    private fun getAuditLog(arguments: String): String {
        val log = auditLog ?: return err("not_supported", "Audit log is not configured")
        val toolName = argString("toolName", arguments)
        val limit = argString("limit", arguments)?.toIntOrNull()?.coerceAtLeast(0) ?: 20
        val entries = log.query(toolName, limit)
        val entriesArray = buildJsonArray {
            for (e in entries) {
                add(buildJsonObject {
                    put("timestamp", e.timestamp)
                    put("toolName", e.toolName)
                    put("changeType", e.changeType)
                    put("oldValue", e.oldValue)
                    put("newValue", e.newValue)
                    put("agentId", e.agentId)
                })
            }
        }
        return buildJsonObject {
            put("type", "audit_log")
            put("entries", entriesArray)
            put("count", entries.size)
            put("ok", true)
        }.toString()
    }

    // ── Helpers ───────────────────────────────────────────────


    private fun ok(key: String, value: String, note: String? = null) = buildJsonObject {
        put("type", "config")
        put("key", key)
        put("value", value)
        note?.let { put("note", it) }
        put("ok", true)
    }.toString()

    private fun toggleOk(type: String, name: String, enabled: Boolean) = buildJsonObject {
        put("type", type)
        put("name", name)
        put("enabled", enabled)
        put("ok", true)
    }.toString()

    private fun err(code: String, message: String?): String = toolError("meta_error", code, message)

    companion object {
        private val HANDLED_TOOLS = setOf(
            "config_set", "config_get", "skill_toggle", "tool_toggle",
            "get_permission_matrix", "set_permission", "rollback_config", "get_audit_log",
        )
    }
}
