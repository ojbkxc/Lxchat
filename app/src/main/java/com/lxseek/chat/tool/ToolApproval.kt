package com.lxseek.chat.tool

/**
 * Approval request for a tool call that the agent mode policy or the provider's
 * [ToolProvider.requiresApprovalByDefault] flagged for human confirmation.
 */
data class ToolApprovalRequest(
    val toolName: String,
    val arguments: String,
    val riskLevel: RiskLevel,
    val agentMode: AgentMode,
    val summary: String,
)

/** Outcome of an approval check. */
sealed interface ToolApprovalResult {
    data object Approved : ToolApprovalResult
    data class Denied(val reason: String) : ToolApprovalResult
}

/**
 * Tools that already have an internal confirmation gate inside their provider
 * (e.g. ShellToolProvider's file_write/file_edit confirm callback). The dispatcher
 * skips the outer approval for these to avoid double-prompting the user.
 */
val TOOLS_WITH_INTERNAL_CONFIRM: Set<String> = setOf(
    "file_write",
    "file_edit",
    "kill_process",
)

/**
 * Determine whether a tool call needs outer dispatcher approval.
 *
 * Returns false when:
 * - the tool has an internal confirm gate (handled by the provider itself),
 * - the agent mode does not require confirmation for this risk level,
 * - the provider does not force approval.
 */
fun needsOuterApproval(
    toolName: String,
    riskLevel: RiskLevel,
    requiresApprovalByDefault: Boolean,
    agentMode: AgentMode,
): Boolean {
    if (toolName in TOOLS_WITH_INTERNAL_CONFIRM) return false
    if (agentMode == AgentMode.Auto && !requiresApprovalByDefault) return false
    if (agentMode == AgentMode.Plan) return false
    return agentMode.requiresConfirmation(riskLevel) || requiresApprovalByDefault
}
