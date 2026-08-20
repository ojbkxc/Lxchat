package com.lxseek.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Processes plan tool outputs and manages the reflection intercept, inspired by Marcel SSH.
 *
 * The reflection intercept prevents the model from hastily declaring all items complete
 * without verifying evidence. When the last item transitions to a terminal state and all
 * items are terminal, the first time we roll back the last transition and inject a reminder
 * asking the model to verify each item with concrete evidence. The second time we let it
 * pass (reflectionReminded flag).
 */
object PlanHandler {

    /**
     * Process a plan tool's output. Returns a [PlanToolOutputResult] containing the updated
     * plan (if any) and an optional override text that replaces the tool's raw output.
     */
    fun handleToolOutput(
        toolName: String,
        toolOutput: String,
        stateHolder: PlanStateHolder,
        taskId: String,
    ): PlanToolOutputResult {
        if (toolName !in PlanToolProvider.TOOL_NAMES) {
            return PlanToolOutputResult(updatedPlan = null, overrideText = null)
        }

        val outputJson = runCatching {
            Json.parseToJsonElement(toolOutput).jsonObject
        }.getOrNull() ?: return PlanToolOutputResult(null, null)

        when (toolName) {
            PlanToolProvider.UPDATE_PLAN_ITEM -> {
                return handleUpdatePlanItem(outputJson, stateHolder, taskId)
            }
        }
        return PlanToolOutputResult(updatedPlan = stateHolder.getPlan(taskId), overrideText = null)
    }

    private fun handleUpdatePlanItem(
        output: JsonObject,
        stateHolder: PlanStateHolder,
        taskId: String,
    ): PlanToolOutputResult {
        val plan = stateHolder.getPlan(taskId) ?: return PlanToolOutputResult(null, null)
        val itemId = (output["item_id"] as? JsonPrimitive)?.contentOrNull ?: return PlanToolOutputResult(null, null)
        val statusStr = (output["status"] as? JsonPrimitive)?.contentOrNull ?: return PlanToolOutputResult(null, null)

        val index = plan.items.indexOfFirst { it.id == itemId }
        if (index < 0) return PlanToolOutputResult(null, null)

        val newStatus = parseStatus(statusStr) ?: return PlanToolOutputResult(null, null)
        val isTerminalTransition = newStatus.isTerminal && !plan.items[index].status.isTerminal

        if (!isTerminalTransition || plan.reflectionReminded || !plan.isComplete) {
            return PlanToolOutputResult(updatedPlan = plan, overrideText = null)
        }

        val originalItem = plan.items[index]
        val rolledBackPlan = plan.withItemUpdated(index, originalItem).copy(reflectionReminded = true)
        stateHolder.setPlan(taskId, rolledBackPlan)

        val reminder = buildReflectionReminder(rolledBackPlan)
        return PlanToolOutputResult(updatedPlan = rolledBackPlan, overrideText = reminder)
    }

    /**
     * Build the plan context string to inject as a temporary system message each round.
     * Returns null when there is no plan or the plan is empty.
     */
    fun buildPlanContext(stateHolder: PlanStateHolder, taskId: String): String? {
        val plan = stateHolder.getPlan(taskId) ?: return null
        if (plan.isEmpty) return null
        val lines = buildList {
            add("当前计划:")
            for (item in plan.items) {
                add("${item.status.symbol} ${item.id}. ${item.title}${if (item.error != null) " (error: ${item.error})" else ""}")
            }
            add("")
            add("请先完成当前步骤，然后调用 update_plan_item 标记状态为 \"completed\" 或 \"failed\"。")
            add("每一步完成后，用具体证据（命令输出、文件内容、请求结果）验证结果，而非仅描述\"已完成\"。")
        }
        return "$PLAN_CONTEXT_PREFIX\n${lines.joinToString("\n")}"
    }

    /**
     * Normalize the plan on task termination: downgrade any in_progress items to pending
     * so the UI does not show a permanently spinning state.
     */
    fun normalizeOnExit(stateHolder: PlanStateHolder, taskId: String) {
        val plan = stateHolder.getPlan(taskId) ?: return
        val normalizedItems = plan.items.map { item ->
            if (item.status == PlanItemStatus.InProgress) item.copy(status = PlanItemStatus.Pending) else item
        }
        stateHolder.setPlan(taskId, plan.copy(items = normalizedItems))
    }

    private fun buildReflectionReminder(plan: AgentTaskPlan): String {
        val itemsList = plan.items.joinToString("\n") { item ->
            "${item.status.symbol} ${item.id}. ${item.title}"
        }
        return buildString {
            append("所有计划步骤已标记为终态。在确认完成之前，请逐项核对：\n\n")
            append(itemsList)
            append("\n\n对每一项，请提供可验证的证据（如命令输出、文件内容、请求结果）")
            append("证明该步骤确实已完成，而非仅描述\"已完成\"。")
            append("如果某项实际未完成，请用 update_plan_item 更正其状态。")
            append("\n\n确认所有步骤都有证据支持后，再次标记完成即可结束。")
        }
    }

    private fun parseStatus(s: String): PlanItemStatus? = when (s.lowercase()) {
        "pending" -> PlanItemStatus.Pending
        "in_progress" -> PlanItemStatus.InProgress
        "completed" -> PlanItemStatus.Completed
        "failed" -> PlanItemStatus.Failed
        "skipped" -> PlanItemStatus.Skipped
        else -> null
    }
}
