package com.lxseek.chat.tool

/**
 * Agent execution mode, inspired by Marcel SSH's Plan / Agent / Auto trichotomy.
 *
 * - [Plan]: read-only tools only — the model researches and proposes a plan but cannot mutate
 *   state. Writable and destructive tools are filtered out at registration time.
 * - [Agent]: all tools registered; destructive tools require explicit user approval before
 *   execution.
 * - [Auto]: all tools registered; the agent executes autonomously without per-call confirmation
 *   (only tools that force approval via [ToolProvider.requiresApprovalByDefault] still ask).
 */
enum class AgentMode {
    Plan,
    Agent,
    Auto;

    /** Whether a tool with the given [level] is allowed to be registered in this mode. */
    fun allowsRisk(level: RiskLevel): Boolean = when (this) {
        Plan -> level == RiskLevel.ReadOnly || level == RiskLevel.LowRisk
        Agent -> true
        Auto -> true
    }

    /**
     * Whether a tool with the given [level] needs an interactive confirmation in this mode.
     * This is the baseline policy; [ToolProvider.requiresApprovalByDefault] can force approval
     * regardless of mode.
     */
    fun requiresConfirmation(level: RiskLevel): Boolean = when (this) {
        Plan -> false
        Agent -> level.isDestructive()
        Auto -> false
    }
}
