package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Provides plan management tools (create_plan, update_plan_item, edit_plan) inspired by
 * Marcel SSH's plan system. The tools return structured metadata; the [PlanHandler]
 * processes that metadata to update [PlanStateHolder] and inject reflection intercepts.
 */
class PlanToolProvider(
    private val stateHolder: PlanStateHolder,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(function = ToolFunction(
            name = CREATE_PLAN,
            description = "Create a structured plan with up to $MAX_PLAN_ITEMS steps. Use this when the user asks for a multi-step task. Each step should be a single, verifiable action.",
            parameters = ToolParameters(
                properties = mapOf(
                    "steps" to ToolProperty(
                        "array",
                        "Array of step titles. Each title should be a concise description of one action.",
                        items = ToolProperty("string", "A step title."),
                    ),
                ),
                required = listOf("steps"),
            ),
        )),
        ToolDefinition(function = ToolFunction(
            name = UPDATE_PLAN_ITEM,
            description = "Update the status of a single plan item. Use 'in_progress' when starting a step, 'completed' when done, 'failed' on error (provide 'error' message), or 'skipped' to skip.",
            parameters = ToolParameters(
                properties = mapOf(
                    "item_id" to ToolProperty("string", "The id of the plan item to update."),
                    "status" to ToolProperty("string", "One of: in_progress, completed, failed, skipped."),
                    "error" to ToolProperty("string", "Error message when status is 'failed' (optional)."),
                ),
                required = listOf("item_id", "status"),
            ),
        )),
        ToolDefinition(function = ToolFunction(
            name = EDIT_PLAN,
            description = "Edit the plan structure: add, remove, or rename items. Pass an array of operations.",
            parameters = ToolParameters(
                properties = mapOf(
                    "operations" to ToolProperty(
                        "array",
                        "Array of edit operations. Each has an 'action' ('add', 'remove', or 'rename'), and 'item_id' for remove/rename, or 'title' for add/rename.",
                        items = ToolProperty("object", "An edit operation."),
                    ),
                ),
                required = listOf("operations"),
            ),
        )),
    )

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override fun riskLevel(name: String): RiskLevel = RiskLevel.ReadOnly

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val taskId = ctx.conversationId ?: return jsonError(name, "No conversation context")
        val args = parseArgs(arguments)
        return when (name) {
            CREATE_PLAN -> executeCreatePlan(taskId, args)
            UPDATE_PLAN_ITEM -> executeUpdatePlanItem(taskId, args)
            EDIT_PLAN -> executeEditPlan(taskId, args)
            else -> jsonError(name, "Unknown plan tool")
        }
    }

    private fun executeCreatePlan(taskId: String, args: JsonObject): String {
        val stepsArray = args["steps"] as? JsonArray
            ?: return jsonError(CREATE_PLAN, "steps must be an array")
        val steps = stepsArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (steps.isEmpty()) return jsonError(CREATE_PLAN, "steps cannot be empty")
        if (steps.size > MAX_PLAN_ITEMS) return jsonError(CREATE_PLAN, "too many steps (max $MAX_PLAN_ITEMS)")

        val items = steps.mapIndexed { index, title ->
            PlanItem(id = (index + 1).toString(), title = title)
        }
        val plan = AgentTaskPlan(
            taskId = taskId,
            items = items,
            currentIndex = -1,
            nextItemSeq = items.size + 1,
        )
        stateHolder.setPlan(taskId, plan)
        return buildJsonObject {
            put("type", CREATE_PLAN)
            put("plan_created", true)
            putJsonArray("items") {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id)
                        put("title", item.title)
                        put("status", item.status.name.lowercase())
                    })
                }
            }
        }.toString()
    }

    private fun executeUpdatePlanItem(taskId: String, args: JsonObject): String {
        val itemId = args["item_id"]?.jsonPrimitive?.contentOrNull
            ?: return jsonError(UPDATE_PLAN_ITEM, "item_id is required")
        val statusStr = args["status"]?.jsonPrimitive?.contentOrNull
            ?: return jsonError(UPDATE_PLAN_ITEM, "status is required")
        val status = parseStatus(statusStr)
            ?: return jsonError(UPDATE_PLAN_ITEM, "invalid status: $statusStr")
        val error = args["error"]?.jsonPrimitive?.contentOrNull

        val plan = stateHolder.getPlan(taskId)
            ?: return jsonError(UPDATE_PLAN_ITEM, "no plan exists for this conversation")
        val index = plan.items.indexOfFirst { it.id == itemId }
        if (index < 0) return jsonError(UPDATE_PLAN_ITEM, "item not found: $itemId")

        val updatedItem = plan.items[index].copy(status = status, error = error)
        val updatedPlan = plan.withItemUpdated(index, updatedItem).copy(
            currentIndex = if (status == PlanItemStatus.InProgress) index else plan.currentIndex,
        )
        stateHolder.setPlan(taskId, updatedPlan)
        return buildJsonObject {
            put("type", UPDATE_PLAN_ITEM)
            put("plan_item_updated", true)
            put("item_id", itemId)
            put("status", statusStr)
            if (error != null) put("error", error)
        }.toString()
    }

    private fun executeEditPlan(taskId: String, args: JsonObject): String {
        val opsArray = args["operations"] as? JsonArray
            ?: return jsonError(EDIT_PLAN, "operations must be an array")
        if (opsArray.size > MAX_PLAN_ITEMS) return jsonError(EDIT_PLAN, "too many operations (max $MAX_PLAN_ITEMS)")

        val plan = stateHolder.getPlan(taskId)
            ?: return jsonError(EDIT_PLAN, "no plan exists for this conversation")
        var current = plan
        for (opElement in opsArray) {
            val op = opElement as? JsonObject ?: continue
            val action = op["action"]?.jsonPrimitive?.contentOrNull ?: continue
            current = when (action) {
                "add" -> {
                    val title = op["title"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (current.items.size >= MAX_PLAN_ITEMS) continue
                    current.withItemAdded(title)
                }
                "remove" -> {
                    val id = op["item_id"]?.jsonPrimitive?.contentOrNull ?: continue
                    current.withItemRemoved(id)
                }
                "rename" -> {
                    val id = op["item_id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val title = op["title"]?.jsonPrimitive?.contentOrNull ?: continue
                    current.withItemRenamed(id, title)
                }
                else -> current
            }
        }
        stateHolder.setPlan(taskId, current)
        return buildJsonObject {
            put("type", EDIT_PLAN)
            put("plan_edited", true)
            putJsonArray("items") {
                current.items.forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id)
                        put("title", item.title)
                        put("status", item.status.name.lowercase())
                    })
                }
            }
        }.toString()
    }

    private fun parseStatus(s: String): PlanItemStatus? = when (s.lowercase()) {
        "pending" -> PlanItemStatus.Pending
        "in_progress" -> PlanItemStatus.InProgress
        "completed" -> PlanItemStatus.Completed
        "failed" -> PlanItemStatus.Failed
        "skipped" -> PlanItemStatus.Skipped
        else -> null
    }

    private fun parseArgs(arguments: String): JsonObject =
        runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

    private fun jsonError(tool: String, message: String): String = buildJsonObject {
        put("type", tool)
        put("error", message)
    }.toString()

    companion object {
        const val CREATE_PLAN = "create_plan"
        const val UPDATE_PLAN_ITEM = "update_plan_item"
        const val EDIT_PLAN = "edit_plan"
        val TOOL_NAMES = setOf(CREATE_PLAN, UPDATE_PLAN_ITEM, EDIT_PLAN)
    }
}
