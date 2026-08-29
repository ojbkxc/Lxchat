package com.lxseek.chat.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lxseek.chat.ui.common.LxChatHaptics
import com.lxseek.chat.viewmodel.ChatViewModel
import com.lxseek.chat.viewmodel.CompactResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
internal class ChatAppDialogState internal constructor(
    private val manualCompactVisibleState: MutableState<Boolean>,
) {
    var renameConversationId by mutableStateOf<String?>(null)
        private set
    var renameInitialName by mutableStateOf("")
        private set
    var deleteConversationId by mutableStateOf<String?>(null)
        private set
    var promptVisible by mutableStateOf(false)
        private set
    var advancedVisible by mutableStateOf(false)
        private set
    val manualCompactVisible: Boolean
        get() = manualCompactVisibleState.value

    fun requestRename(conversationId: String, initialName: String) {
        renameConversationId = conversationId
        renameInitialName = initialName
    }

    fun dismissRename() {
        renameConversationId = null
    }

    fun requestDelete(conversationId: String) {
        deleteConversationId = conversationId
    }

    fun dismissDelete() {
        deleteConversationId = null
    }

    fun showPrompt() {
        promptVisible = true
    }

    fun dismissPrompt() {
        promptVisible = false
    }

    fun showAdvanced() {
        advancedVisible = true
    }

    fun dismissAdvanced() {
        advancedVisible = false
    }

    fun showManualCompact() {
        manualCompactVisibleState.value = true
    }

    fun dismissManualCompact() {
        manualCompactVisibleState.value = false
    }
}

@Composable
internal fun rememberChatAppDialogState(
    manualCompactVisibleState: MutableState<Boolean>,
): ChatAppDialogState = remember(manualCompactVisibleState) {
    ChatAppDialogState(manualCompactVisibleState)
}

@Composable
internal fun ChatAppDialogHost(
    state: ChatAppDialogState,
    viewModel: ChatViewModel,
    haptics: LxChatHaptics,
    scope: CoroutineScope,
    compactModel: String?,
    selectedModel: String,
    compactPrompt: String,
    compactRetainCount: Int,
    enabledModels: Set<String>,
    modelAliases: Map<String, String>,
    isCompacting: Boolean,
) {
    state.renameConversationId?.let { id ->
        ChatRenameDialog(
            initialName = state.renameInitialName,
            onSave = { newName ->
                viewModel.renameConversation(id, newName)
                state.dismissRename()
            },
            onDismiss = state::dismissRename,
        )
    }

    state.deleteConversationId?.let { id ->
        ChatDeleteConfirmDialog(
            onConfirm = {
                haptics.destructiveConfirmed()
                viewModel.deleteConversation(id)
                state.dismissDelete()
            },
            onDismiss = state::dismissDelete,
        )
    }

    if (state.promptVisible) {
        ChatSystemPromptDialog(viewModel = viewModel, onDismiss = state::dismissPrompt)
    }

    if (state.advancedVisible) {
        ChatAdvancedSettingsDialog(
            viewModel = viewModel,
            onDismiss = state::dismissAdvanced,
            onSystemPromptClick = {
                state.dismissAdvanced()
                state.showPrompt()
            },
        )
    }

    if (state.manualCompactVisible) {
        ChatManualCompactDialog(
            initialModel = compactModel ?: selectedModel,
            initialPrompt = compactPrompt,
            initialRetainCount = compactRetainCount,
            enabledModels = enabledModels,
            modelAliases = modelAliases,
            isCompacting = isCompacting,
            onCompact = { model, prompt, retainCount ->
                state.dismissManualCompact()
                scope.launch {
                    val result = viewModel.compactContextManual(model, prompt, retainCount)
                    if (result is CompactResult.Failed) {
                        viewModel.emitSnackbar(result.message)
                    }
                }
            },
            onDismiss = state::dismissManualCompact,
        )
    }
}
