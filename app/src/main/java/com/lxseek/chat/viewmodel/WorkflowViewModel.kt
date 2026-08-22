package com.lxseek.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lxseek.chat.automation.WorkflowManager
import com.lxseek.chat.data.local.WorkflowEntity
import com.lxseek.chat.data.local.WorkflowStepEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Thin UI-facing wrapper over the app-scoped [WorkflowManager]. Kept separate from
 * [ChatViewModel] so the (already large) chat view-model stays untouched while the workflow
 * editor gets its own lifecycle-scoped state.
 */
class WorkflowViewModel(private val manager: WorkflowManager) : ViewModel() {
    val workflows: StateFlow<List<WorkflowEntity>> = manager.workflows
    val runningWorkflowIds: StateFlow<Set<String>> = manager.runningWorkflowIds

    fun observeWorkflowSteps(workflowId: String): Flow<List<WorkflowStepEntity>> =
        manager.observeWorkflowSteps(workflowId)

    suspend fun getWorkflow(id: String): WorkflowEntity? = manager.getWorkflow(id)

    suspend fun getWorkflowSteps(workflowId: String): List<WorkflowStepEntity> =
        manager.getWorkflowSteps(workflowId)

    fun saveWorkflow(workflow: WorkflowEntity, steps: List<WorkflowStepEntity>) {
        viewModelScope.launch { manager.saveWorkflow(workflow, steps) }
    }

    fun deleteWorkflow(workflowId: String) {
        viewModelScope.launch { manager.deleteWorkflow(workflowId) }
    }

    fun runNow(workflow: WorkflowEntity) = manager.runNow(workflow)

    class Factory(private val manager: WorkflowManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WorkflowViewModel::class.java)) {
                return WorkflowViewModel(manager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
