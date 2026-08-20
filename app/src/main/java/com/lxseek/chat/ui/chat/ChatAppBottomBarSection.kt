package com.lxseek.chat.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lxseek.chat.api.util.ContextWindowUsage
import com.lxseek.chat.data.local.LoopEntity
import com.lxseek.chat.data.ShellDeviceConfig
import com.lxseek.chat.model.OpenAiServiceTiers
import com.lxseek.chat.ui.chat.bottombar.CHAT_BOTTOM_BAR_OUTER_SHAPE
import com.lxseek.chat.ui.chat.bottombar.ChatBottomBar
import com.lxseek.chat.ui.chat.bottombar.ChatComposerState
import com.lxseek.chat.ui.chat.bottombar.LoopStatusBackdrop
import com.lxseek.chat.ui.common.LxChatHaptics
import com.lxseek.chat.viewmodel.ChatViewModel
import com.lxseek.chat.viewmodel.QueuedSend

@Composable
internal fun ChatAppBottomBarSection(
    viewModel: ChatViewModel,
    haptics: LxChatHaptics,
    dialogState: ChatAppDialogState,
    scrollCoordinator: ChatScrollCoordinator,
    textFieldState: TextFieldState,
    composer: ChatComposerState,
    inputFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    isExpandAnimating: Boolean = false,
    outerSpacerHeightPx: Float = 0f,
    onBottomBarHeightChanged: (Float) -> Unit = {},
    isLoading: Boolean = false,
    isCompacting: Boolean = false,
    isSwitching: Boolean = false,
    isStopping: Boolean = false,
    currentConversationId: String? = null,
    currentLoop: LoopEntity? = null,
    runningLoopIds: Set<String> = emptySet(),
    enabledModels: Set<String> = emptySet(),
    selectedModel: String = "",
    modelAliases: Map<String, String> = emptyMap(),
    codeExecutionEnabled: Boolean = false,
    googleSearchEnabled: Boolean = false,
    thinkingEnabled: Boolean = false,
    thinkingLevel: String = "",
    thinkingBudgetEnabled: Boolean = false,
    thinkingBudgetTokens: Int = 0,
    openAiServiceTierAvailable: Boolean = false,
    openAiServiceTierEnabled: Boolean = false,
    openAiServiceTier: String = "",
    webSearchEnabled: Boolean = false,
    shellEnabled: Boolean = false,
    globalWebSearch: Boolean = false,
    globalShell: Boolean = false,
    shellDevices: List<ShellDeviceConfig> = emptyList(),
    contextUsage: ContextWindowUsage = ContextWindowUsage(0, 0, 0, false),
    compactModel: String? = null,
    compactPrompt: String = "",
    compactRetainCount: Int = 0,
    queuedSends: List<QueuedSend> = emptyList(),
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onPdfPagesClick: ((List<String>, Int) -> Unit)? = null,
    onPdfPreviewSelect: ((List<String>, Int) -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    voiceConversationState: com.lxseek.chat.viewmodel.VoiceConversationController.State = com.lxseek.chat.viewmodel.VoiceConversationController.State.IDLE,
    voiceConversationAmplitude: Float = 0f,
    voiceConversationEnabled: Boolean = false,
    voiceConversationActive: Boolean = false,
    singleAsrRecording: Boolean = false,
    onVoiceConversationToggle: () -> Unit = {},
    onSingleAsrToggle: () -> Unit = {},
    onStopSingleAsr: () -> Unit = {},
) {
    val density = LocalDensity.current
    val gradientTopPaddingPx = with(density) { 20.dp.toPx() }
    val gradientWidthPx = with(density) { 40.dp.toPx() }
    val bgColor = MaterialTheme.colorScheme.background
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isExpanded) Modifier.fillMaxHeight().statusBarsPadding() else Modifier)
            .drawBehind {
                val totalH = size.height
                if (totalH > 0f) {
                    val (transparentEnd, fadeEnd) = if (isExpanded) {
                        val h = gradientTopPaddingPx.coerceAtMost(totalH * 0.12f)
                        val w = gradientWidthPx.coerceAtMost(totalH * 0.24f)
                        (h / totalH) to ((h + w) / totalH)
                    } else {
                        val te = (gradientTopPaddingPx / totalH).coerceIn(0f, 1f)
                        val fe = ((gradientTopPaddingPx + gradientWidthPx) / totalH).coerceIn(0f, 1f)
                        te to fe
                    }
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                transparentEnd to Color.Transparent,
                                fadeEnd to bgColor,
                            ),
                            startY = 0f,
                            endY = totalH
                        )
                    )
                }
            },
        color = Color.Transparent
    ) {
        Column {
            if (outerSpacerHeightPx > 0f) {
                Spacer(modifier = Modifier.height(with(density) { outerSpacerHeightPx.toDp() }))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                    .onSizeChanged {
                        if (!isExpanded) onBottomBarHeightChanged(it.height.toFloat())
                    }
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(8.dp),
            ) {
                LoopStatusBackdrop(
                    loop = currentLoop,
                    isRunning = currentConversationId in runningLoopIds,
                    onStop = { viewModel.stopCurrentLoop() },
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isExpanded) Modifier.weight(1f) else Modifier),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    shape = CHAT_BOTTOM_BAR_OUTER_SHAPE,
                ) {
                    Box(
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        ChatBottomBar(
                            onSendMessage = { text, attachments, onAccepted ->
                                viewModel.sendMessage(
                                    text = text,
                                    attachments = attachments,
                                    onAccepted = onAccepted,
                                )
                            },
                            onStopGeneration = {
                                haptics.interrupt()
                                viewModel.stopGeneration()
                            },
                            isLoading = isLoading,
                            isCompacting = isCompacting,
                            isSwitching = isSwitching,
                            enabledModels = enabledModels,
                            selectedModel = selectedModel,
                            modelAliases = modelAliases,
                            codeExecutionEnabled = codeExecutionEnabled,
                            googleSearchEnabled = googleSearchEnabled,
                            thinkingEnabled = thinkingEnabled,
                            thinkingLevel = thinkingLevel,
                            thinkingBudgetEnabled = thinkingBudgetEnabled,
                            thinkingBudgetTokens = thinkingBudgetTokens,
                            openAiServiceTierAvailable = openAiServiceTierAvailable,
                            openAiServiceTierEnabled = openAiServiceTierEnabled,
                            openAiServiceTier = openAiServiceTier,
                            onCodeExecutionToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(codeExecutionEnabled = enabled) } },
                            onGoogleSearchToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(googleSearchEnabled = enabled) } },
                            onThinkingToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingEnabled = enabled) } },
                            onThinkingLevelChange = { level -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingLevel = level) } },
                            onThinkingBudgetEnabledChange = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingBudgetEnabled = enabled) } },
                            onThinkingBudgetTokensChange = { tokens -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingBudgetTokens = tokens) } },
                            onOpenAiServiceTierToggle = { enabled ->
                                haptics.toggle(enabled)
                                viewModel.updateConversationSetting(currentConversationId) {
                                    it.copy(openAiServiceTierEnabled = enabled)
                                }
                            },
                            onOpenAiServiceTierChange = { tier ->
                                haptics.selection()
                                viewModel.updateConversationSetting(currentConversationId) {
                                    it.copy(openAiServiceTier = OpenAiServiceTiers.normalize(tier))
                                }
                            },
                            webSearchEnabled = webSearchEnabled,
                            onWebSearchToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(webSearchEnabled = enabled) } },
                            shellEnabled = shellEnabled,
                            onShellToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(shellEnabled = enabled) } },
                            onModelSelect = { viewModel.setActiveModel(it) },
                            onImageClick = { url -> onMediaClick(listOf(url), 0) },
                            onAllMediaClick = { urls, idx -> onMediaClick(urls, idx) },
                            onFileContentClick = { name, content -> viewModel.showFilePreview(name, content) },
                            modifier = Modifier,
                            textFieldState = textFieldState,
                            composerState = composer,
                            focusRequester = inputFocusRequester,
                            onInputFocusChanged = { focused ->
                                scrollCoordinator.setComposerInputFocused(focused)
                            },
                            isExpanded = isExpanded,
                            isExpandAnimating = isExpandAnimating,
                            onCollapse = { onExpandedChange(false) },
                            onExpand = { onExpandedChange(true) },
                            showWebSearch = globalWebSearch,
                            showShell = shellDevices.isNotEmpty() && globalShell,
                            onPdfPagesClick = { pages, idx -> onPdfPagesClick?.invoke(pages, idx) },
                            onPdfPreviewSelect = { pages, idx -> onPdfPreviewSelect?.invoke(pages, idx) },
                            pdfViewerSelection = pdfViewerSelection,
                            onTogglePdfSelection = onTogglePdfSelection,
                            onInitPdfSelection = onInitPdfSelection,
                            fullScreenViewerUrls = fullScreenViewerUrls,
                            compactDefaultModel = compactModel,
                            compactDefaultPrompt = compactPrompt,
                            compactDefaultRetainCount = compactRetainCount,
                            contextEstimatedTokens = contextUsage.estimatedTokenCount,
                            contextLogicalMessageCount = contextUsage.logicalMessageCount,
                            contextTokenBudget = contextUsage.tokenBudget,
                            hasCompactBoundary = contextUsage.hasCompactBoundary,
                            canCompact = currentConversationId != null && !isLoading && !isSwitching && !isStopping,
                            onCompactClick = {
                                dialogState.showManualCompact()
                            },
                            onAdvancedClick = dialogState::showAdvanced,
                            queuedSends = queuedSends,
                            onRemoveQueuedSend = viewModel::removeQueuedSend,
                            isStopping = isStopping,
                            voiceConversationState = voiceConversationState,
                            voiceConversationAmplitude = voiceConversationAmplitude,
                            voiceConversationEnabled = voiceConversationEnabled,
                            voiceConversationActive = voiceConversationActive,
                            singleAsrRecording = singleAsrRecording,
                            onVoiceConversationToggle = onVoiceConversationToggle,
                            onSingleAsrToggle = onSingleAsrToggle,
                            onStopSingleAsr = onStopSingleAsr,
                            onToast = viewModel::emitSnackbar,
                        )
                    }
                }
            }
        }
    }
}
