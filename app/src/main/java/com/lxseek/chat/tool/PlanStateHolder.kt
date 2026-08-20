package com.lxseek.chat.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped holder for agent task plans. One plan per task id.
 * In-memory only for phase 3; persistence can be added later.
 */
class PlanStateHolder {
    private val _plans = MutableStateFlow<Map<String, AgentTaskPlan>>(emptyMap())
    val plans: StateFlow<Map<String, AgentTaskPlan>> = _plans.asStateFlow()

    fun getPlan(taskId: String): AgentTaskPlan? = _plans.value[taskId]

    fun setPlan(taskId: String, plan: AgentTaskPlan) {
        _plans.value = _plans.value + (taskId to plan)
    }

    fun updatePlan(taskId: String, transformer: (AgentTaskPlan) -> AgentTaskPlan) {
        val current = _plans.value[taskId] ?: return
        _plans.value = _plans.value + (taskId to transformer(current))
    }

    fun clearPlan(taskId: String) {
        _plans.value = _plans.value - taskId
    }

    fun clearAll() {
        _plans.value = emptyMap()
    }
}
