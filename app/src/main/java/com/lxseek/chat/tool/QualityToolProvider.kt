package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.data.ActivityJournal
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 交付前自检工具 —— 对应 cc-haha 的 `verify` 内置技能与 Hermes 的「self-verification」。
 *
 * 模型在向用户交付结果前调用 [VERIFY_OUTPUT]，传入「任务是什么」和「我打算交付什么」，
 * 工具返回一份结构化自检清单，模型据此逐项核对（可执行性、完整性、正确性、副作用、
 * 用户原始诉求是否被满足），并把核对结果一并汇报给用户。
 *
 * 这是纯提示性工具：不执行任何外部操作、不调用子模型，只产出引导模型自检的指令文本，
 * 并把每次校验记入 [ActivityJournal]（journey 的 VERIFY 维度），供 journey 汇报成长轨迹。
 *
 * 纯读操作：RiskLevel.ReadOnly、ToolTier.Extended，无需审批。
 */
class QualityToolProvider(
    private val journal: ActivityJournal,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = VERIFY_OUTPUT,
                description = "Self-check your output before delivering it to the user. Call this when a task is complete but before presenting the final answer, to verify it actually satisfies the request.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "task" to ToolProperty("string", "What the user asked for (restate the original request)."),
                        "output_summary" to ToolProperty("string", "Brief summary of what you are about to deliver."),
                        "success_criteria" to ToolProperty("string", "Optional explicit success criteria from the plan; omit to let the checklist infer them."),
                    ),
                    required = listOf("task", "output_summary"),
                ),
            ),
        ),
    )

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            definition = definitions(ctx).single(),
            riskLevel = RiskLevel.ReadOnly,
            tier = ToolTier.Extended,
            requiresApproval = false,
            summary = "Self-check output before delivery.",
        ),
    )

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        if (name != VERIFY_OUTPUT) return "Error: Unknown tool: $name"
        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }

        val task = args.str("task")?.trim().orEmpty()
        val outputSummary = args.str("output_summary")?.trim().orEmpty()
        if (task.isEmpty() || outputSummary.isEmpty()) {
            return "Error: Both 'task' and 'output_summary' are required."
        }
        val successCriteria = args.str("success_criteria")?.trim()?.takeIf { it.isNotEmpty() }

        journal.record(ActivityJournal.Kind.VERIFY, "verify_output", task.take(120))

        return buildJsonObject {
            put("type", VERIFY_OUTPUT)
            put("instruction", "Go through every checklist item below against your proposed output. For each item answer PASS or FAIL with a one-line reason. If any FAIL, fix the output first, then re-run this check. Report the final PASS/FAIL list to the user together with your delivery.")
            putJsonArray("checklist") {
                add("Completeness: Does the output fully address the original task '${task.take(300)}'? Nothing the user asked for is missing?")
                add("Correctness: Is every claim, file path, command, or fact accurate and verified rather than guessed?")
                add("Actionability: Can the user act on this directly? Are concrete next steps, commands, or locations given where relevant?")
                add("Side effects: Did this work change anything outside the current scope (files, tasks, skills, memory)? If so, was it intentional and confirmed?")
                if (successCriteria != null) {
                    add("Success criteria: Does the output meet the agreed criteria — $successCriteria?")
                }
                add("Safety: Does the output avoid leaking secrets, fabricating references, or recommending destructive actions without explicit confirmation?")
            }
            put("pass_guideline", "Only report the delivery as done when all items PASS; otherwise state what remains.")
        }.toString()
    }

    override fun handles(name: String): Boolean = name == VERIFY_OUTPUT

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        const val VERIFY_OUTPUT = "verify_output"
    }
}
