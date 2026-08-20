package com.lxseek.chat.tool

/**
 * Status of a single plan item, inspired by Marcel SSH's plan system.
 */
enum class PlanItemStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
    Skipped;

    val isTerminal: Boolean get() = this == Completed || this == Failed || this == Skipped

    val symbol: String get() = when (this) {
        Pending -> "[○]"
        InProgress -> "[▶]"
        Completed -> "[✓]"
        Failed -> "[✗]"
        Skipped -> "[–]"
    }
}

/**
 * A single step in an agent plan. The [id] is a 1-based numeric string that is never
 * reused after deletion (ids keep their gaps, e.g. 1, 3, 4) to avoid id drift between
 * the model and the user.
 */
data class PlanItem(
    val id: String,
    val title: String,
    val status: PlanItemStatus = PlanItemStatus.Pending,
    val error: String? = null,
)

/**
 * The full plan for a single agent task. The [reflectionReminded] flag ensures the
 * reflection intercept fires at most once per plan.
 */
data class AgentTaskPlan(
    val taskId: String,
    val items: List<PlanItem> = emptyList(),
    val currentIndex: Int = -1,
    val nextItemSeq: Int = 1,
    val reflectionReminded: Boolean = false,
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val isComplete: Boolean get() = items.isNotEmpty() && items.all { it.status.isTerminal }

    fun itemById(id: String): PlanItem? = items.find { it.id == id }

    fun withItemUpdated(index: Int, item: PlanItem): AgentTaskPlan {
        val newItems = items.toMutableList()
        if (index in newItems.indices) newItems[index] = item
        return copy(items = newItems)
    }

    fun withItemAdded(title: String): AgentTaskPlan {
        val id = nextItemSeq.toString()
        return copy(
            items = items + PlanItem(id = id, title = title),
            nextItemSeq = nextItemSeq + 1,
        )
    }

    fun withItemRemoved(id: String): AgentTaskPlan {
        return copy(items = items.filterNot { it.id == id })
    }

    fun withItemRenamed(id: String, newTitle: String): AgentTaskPlan {
        return copy(items = items.map { if (it.id == id) it.copy(title = newTitle) else it })
    }
}

/**
 * Result of processing a plan tool's output. [overrideText], when non-null, replaces the
 * tool's raw output before it is fed back to the model — used by the reflection intercept
 * to inject a reminder instead of the normal result.
 */
data class PlanToolOutputResult(
    val updatedPlan: AgentTaskPlan?,
    val overrideText: String?,
)

internal const val MAX_PLAN_ITEMS = 20
internal const val PLAN_CONTEXT_PREFIX = "[plan-context]"
