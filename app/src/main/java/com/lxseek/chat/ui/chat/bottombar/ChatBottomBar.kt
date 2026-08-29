package com.lxseek.chat.ui.chat.bottombar

import com.lxseek.chat.ui.components.DialogWindowEdgeToEdge

import androidx.compose.foundation.background
import com.lxseek.chat.model.ContextBudget
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.QueuedSend
import com.lxseek.chat.ui.chat.PdfPageSelectDialog
import com.lxseek.chat.ui.chat.VideoSliceDialog
import com.lxseek.chat.ui.common.OpenAiServiceTierControlPanel
import com.lxseek.chat.ui.common.ThinkingControlPanel
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import com.lxseek.chat.util.noOpBringIntoView
import com.lxseek.chat.viewmodel.SendAcceptance

import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

internal val CHAT_BOTTOM_BAR_OUTER_RADIUS = 12.dp
internal val CHAT_BOTTOM_BAR_OUTER_SHAPE = RoundedCornerShape(CHAT_BOTTOM_BAR_OUTER_RADIUS)

/**
 * Bottom-bar container for the chat composer.
 *
 * Owns the mode-switching state machine (text vs. voice, idle vs. generating,
 * compact vs. switch) and the activity-result launchers that the attachment
 * menu and camera flow depend on. The actual UI for each concern is delegated
 * to focused composables:
 *
 * - [ComposerTextInput]      — text field + conversation variable insertion
 * - [ComposerAttachmentMenu] — camera / photos / videos / files picker
 * - [ComposerToolBar]        — model picker, context gauge, tools menu
 * - [ComposerVoiceButton]    — single-shot ASR trigger
 * - [ComposerSendButton]     — send / stop / voice-conversation FAB
 *
 * The container also hosts the modal bottom sheets (thinking control, OpenAI
 * service tier) and the dialog surfaces (internal camera capture, rejected
 * attachment, PDF page selection, video slicing) that need to be hoisted at
 * this level so they survive configuration changes driven by the composer
 * state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatBottomBar(
    onSendMessage: suspend (
        String,
        List<com.lxseek.chat.model.SelectedAttachment>,
        suspend () -> Unit,
    ) -> SendAcceptance?,
    onStopGeneration: () -> Unit = {},
    isLoading: Boolean,
    isCompacting: Boolean = false,
    isSwitching: Boolean = false,
    enabledModels: Set<String>,
    selectedModel: String,
    modelAliases: Map<String, String> = emptyMap(),
    codeExecutionEnabled: Boolean = false,
    googleSearchEnabled: Boolean = false,
    thinkingEnabled: Boolean = true,
    thinkingLevel: String = "medium",
    thinkingBudgetEnabled: Boolean = false,
    thinkingBudgetTokens: Int = 4096,
    openAiServiceTierAvailable: Boolean = false,
    openAiServiceTierEnabled: Boolean = false,
    openAiServiceTier: String = "auto",
    webSearchEnabled: Boolean = false,
    shellEnabled: Boolean = false,
    onCodeExecutionToggle: (Boolean) -> Unit = {},
    onGoogleSearchToggle: (Boolean) -> Unit = {},
    onThinkingToggle: (Boolean) -> Unit = {},
    onThinkingLevelChange: (String) -> Unit = {},
    onThinkingBudgetEnabledChange: (Boolean) -> Unit = {},
    onThinkingBudgetTokensChange: (Int) -> Unit = {},
    onOpenAiServiceTierToggle: (Boolean) -> Unit = {},
    onOpenAiServiceTierChange: (String) -> Unit = {},
    onWebSearchToggle: (Boolean) -> Unit = {},
    onShellToggle: (Boolean) -> Unit = {},
    onModelSelect: (String) -> Unit,
    onImageClick: (String) -> Unit = {},
    onAllMediaClick: ((urls: List<String>, index: Int) -> Unit)? = null,
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onPdfPreviewSelect: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onPdfViewerClosed: (() -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() },
    composerState: ChatComposerState = rememberChatComposerState(),
    conversations: List<ConversationMention> = emptyList(),
    onSwitchConversation: (String) -> Unit = {},
    focusRequester: FocusRequester = FocusRequester(),
    onInputFocusChanged: (Boolean) -> Unit = {},
    isExpanded: Boolean = false,
    isExpandAnimating: Boolean = false,
    onCollapse: () -> Unit = {},
    onExpand: () -> Unit = {},
    showWebSearch: Boolean = true,
    showShell: Boolean = true,
    onAdvancedClick: () -> Unit = {},
    compactDefaultModel: String? = null,
    compactDefaultPrompt: String = "",
    compactDefaultRetainCount: Int = 6,
    contextEstimatedTokens: Int = 0,
    contextLogicalMessageCount: Int = 0,
    contextTokenBudget: Int = ContextBudget.DEFAULT_TOKENS,
    hasCompactBoundary: Boolean = false,
    canCompact: Boolean = false,
    onCompactClick: () -> Unit = {},
    queuedSends: List<QueuedSend> = emptyList(),
    onRemoveQueuedSend: (String) -> Unit = {},
    isStopping: Boolean = false,
    voiceConversationState: com.lxseek.chat.viewmodel.VoiceConversationController.State = com.lxseek.chat.viewmodel.VoiceConversationController.State.IDLE,
    voiceConversationAmplitude: Float = 0f,
    voiceConversationEnabled: Boolean = false,
    voiceConversationActive: Boolean = false,
    singleAsrRecording: Boolean = false,
    onVoiceConversationToggle: () -> Unit = {},
    onSingleAsrToggle: () -> Unit = {},
    onStopSingleAsr: () -> Unit = {},
    onToast: (String) -> Unit = {},
) {
    val allowSpatialTransitions = LocalLxChatMotionPolicy.current.allowSpatialTransitions
    val scrollState = rememberScrollState()
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)

    val composer = composerState

    val context = LocalContext.current
    var showThinkingSheet by rememberSaveable { mutableStateOf(false) }
    var showOpenAiServiceTierSheet by rememberSaveable { mutableStateOf(false) }
    val composerOcclusionColor = MaterialTheme.colorScheme.surfaceContainer
    val composerOcclusionShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
    )

    LaunchedEffect(fullScreenViewerUrls) {
        if (fullScreenViewerUrls == null && composer.pdfDialogHiddenForPreview && composer.pendingPdfUri != null) {
            composer.showPdfPageDialog = true
            composer.pdfDialogHiddenForPreview = false
        }
    }
    LaunchedEffect(openAiServiceTierAvailable) {
        if (!openAiServiceTierAvailable) showOpenAiServiceTierSheet = false
    }

    val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> composer.onPickImages(uris) }
    val videoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> composer.onPickVideos(uris) }
    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris -> composer.onPickFiles(uris, onInitPdfSelection) }
    val activityLaunchScope = rememberCoroutineScope()
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPermissionPath by rememberSaveable { mutableStateOf<String?>(null) }
    var internalCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { captured ->
        pendingCameraPath?.let { privatePath ->
            composer.completeCameraCapture(privatePath, captured)
        }
        pendingCameraPath = null
    }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val privatePath = pendingCameraPermissionPath
        pendingCameraPermissionPath = null
        if (granted && privatePath != null) {
            internalCameraPath = privatePath
        } else if (privatePath != null) {
            composer.completeCameraCapture(privatePath, captured = false)
            composer.reportCameraPreparationFailure()
        }
    }

    fun launchInternalCamera(privatePath: String) {
        if (
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            internalCameraPath = privatePath
        } else {
            pendingCameraPermissionPath = privatePath
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    Box(modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ComposerStatusColumn(
                queuedSends = queuedSends,
                onRemoveQueuedSend = onRemoveQueuedSend,
                modifier = Modifier.zIndex(0f),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (allowSpatialTransitions) {
                            Modifier.animateContentSize(
                                animationSpec = tween(durationMillis = 400),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(composerOcclusionShape)
                    .background(composerOcclusionColor)
                    .zIndex(1f),
            ) {
                if (composer.selectedAttachments.isNotEmpty()) {
                    AttachmentPreviewRow(
                        composer = composer,
                        onAllMediaClick = onAllMediaClick,
                        onFileContentClick = onFileContentClick,
                        onPdfPagesClick = onPdfPagesClick,
                    )
                }

                ComposerTextInput(
                    textFieldState = textFieldState,
                    scrollState = scrollState,
                    focusRequester = focusRequester,
                    onInputFocusChanged = onInputFocusChanged,
                    conversations = conversations,
                    onSwitchConversation = onSwitchConversation,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .noOpBringIntoView()
                        // 输入框与工具栏/按钮之间 8dp 间距，左右 4dp 贴边
                        .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerAttachmentMenu(
                        onLaunchCamera = {
                            activityLaunchScope.launch {
                                val target = composer.createCameraCaptureTarget()
                                if (target == null) {
                                    composer.reportCameraPreparationFailure()
                                    return@launch
                                }
                                if (canLaunchSystemImageCapture(context)) {
                                    pendingCameraPath = target.privatePath
                                    runCatching {
                                        cameraLauncher.launch(target.uri)
                                    }.onFailure {
                                        pendingCameraPath = null
                                        launchInternalCamera(target.privatePath)
                                    }
                                } else {
                                    launchInternalCamera(target.privatePath)
                                }
                            }
                        },
                        onLaunchPhotos = {
                            photoLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onLaunchVideos = {
                            videoLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly,
                                ),
                            )
                        },
                        onLaunchFiles = {
                            fileLauncher.launch("*/*")
                        },
                    )

                    ComposerToolBar(
                        enabledModels = enabledModels,
                        selectedModel = selectedModel,
                        modelAliases = modelAliases,
                        isModelValid = isModelValid,
                        thinkingEnabled = thinkingEnabled,
                        thinkingLevel = thinkingLevel,
                        thinkingBudgetEnabled = thinkingBudgetEnabled,
                        thinkingBudgetTokens = thinkingBudgetTokens,
                        openAiServiceTierAvailable = openAiServiceTierAvailable,
                        openAiServiceTierEnabled = openAiServiceTierEnabled,
                        openAiServiceTier = openAiServiceTier,
                        webSearchEnabled = webSearchEnabled,
                        shellEnabled = shellEnabled,
                        codeExecutionEnabled = codeExecutionEnabled,
                        googleSearchEnabled = googleSearchEnabled,
                        showWebSearch = showWebSearch,
                        showShell = showShell,
                        canCompact = canCompact,
                        isCompacting = isCompacting,
                        contextEstimatedTokens = contextEstimatedTokens,
                        contextLogicalMessageCount = contextLogicalMessageCount,
                        contextTokenBudget = contextTokenBudget,
                        hasCompactBoundary = hasCompactBoundary,
                        onModelSelect = onModelSelect,
                        onThinkingToggle = onThinkingToggle,
                        onThinkingLevelChange = onThinkingLevelChange,
                        onThinkingBudgetEnabledChange = onThinkingBudgetEnabledChange,
                        onThinkingBudgetTokensChange = onThinkingBudgetTokensChange,
                        onOpenAiServiceTierToggle = onOpenAiServiceTierToggle,
                        onOpenAiServiceTierChange = onOpenAiServiceTierChange,
                        onWebSearchToggle = onWebSearchToggle,
                        onShellToggle = onShellToggle,
                        onCodeExecutionToggle = onCodeExecutionToggle,
                        onGoogleSearchToggle = onGoogleSearchToggle,
                        onCompactClick = onCompactClick,
                        onAdvancedClick = onAdvancedClick,
                        onShowThinkingSheet = { showThinkingSheet = true },
                        onShowOpenAiServiceTierSheet = { showOpenAiServiceTierSheet = true },
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    ComposerVoiceButton(
                        singleAsrRecording = singleAsrRecording,
                        onSingleAsrToggle = onSingleAsrToggle,
                    )

                    ComposerSendButton(
                        textFieldState = textFieldState,
                        composer = composer,
                        isLoading = isLoading,
                        isCompacting = isCompacting,
                        isSwitching = isSwitching,
                        isStopping = isStopping,
                        isModelValid = isModelValid,
                        voiceConversationState = voiceConversationState,
                        voiceConversationEnabled = voiceConversationEnabled,
                        voiceConversationActive = voiceConversationActive,
                        singleAsrRecording = singleAsrRecording,
                        onSendMessage = onSendMessage,
                        onStopGeneration = onStopGeneration,
                        onCollapse = onCollapse,
                        onVoiceConversationToggle = onVoiceConversationToggle,
                        onStopSingleAsr = onStopSingleAsr,
                        onToast = onToast,
                    )
                }
            }
        }
    }

    if (showThinkingSheet) {
        ModalBottomSheet(
            onDismissRequest = { showThinkingSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            DialogWindowEdgeToEdge()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                ThinkingControlPanel(
                    enabled = thinkingEnabled,
                    level = thinkingLevel,
                    budgetEnabled = thinkingBudgetEnabled,
                    budgetTokens = thinkingBudgetTokens,
                    onEnabledChange = onThinkingToggle,
                    onLevelChange = onThinkingLevelChange,
                    onBudgetEnabledChange = onThinkingBudgetEnabledChange,
                    onBudgetTokensChange = onThinkingBudgetTokensChange,
                    providerName = com.lxseek.chat.model.ModelId.parse(selectedModel).providerName,
                    animateSections = true,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showOpenAiServiceTierSheet && openAiServiceTierAvailable) {
        ModalBottomSheet(
            onDismissRequest = { showOpenAiServiceTierSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            DialogWindowEdgeToEdge()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                OpenAiServiceTierControlPanel(
                    enabled = openAiServiceTierEnabled,
                    tier = openAiServiceTier,
                    onEnabledChange = onOpenAiServiceTierToggle,
                    onTierChange = onOpenAiServiceTierChange,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    internalCameraPath?.let { privatePath ->
        InternalCameraCaptureDialog(
            targetPath = privatePath,
            onCaptured = {
                internalCameraPath = null
                composer.completeCameraCapture(privatePath, captured = true)
            },
            onCancelled = {
                internalCameraPath = null
                composer.completeCameraCapture(privatePath, captured = false)
            },
            onFailure = {
                internalCameraPath = null
                composer.completeCameraCapture(privatePath, captured = false)
                composer.reportCameraPreparationFailure()
            },
        )
    }

    val rejectedMsg = composer.rejectedMessage
    if (rejectedMsg != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { composer.rejectedMessage = null },
            title = { Text(stringResource(composer.rejectedTitleRes), fontWeight = FontWeight.Bold) },
            text = { Text(rejectedMsg) },
            confirmButton = {
                TextButton(onClick = { composer.rejectedMessage = null }) {
                    Text(stringResource(R.string.provider_close))
                }
            },
        )
    }

    // PDF page selection dialog
    if (composer.showPdfPageDialog && composer.pendingPdfUri != null) {
        PdfPageSelectDialog(
            totalPages = composer.pendingPdfPages,
            thumbnailPaths = composer.pendingPdfRenderedPaths,
            isLoading = composer.pendingPdfIsRendering,
            renderProgress = composer.pendingPdfRenderProgress,
            selectedPages = pdfViewerSelection,
            onTogglePage = { onTogglePdfSelection?.invoke(it) },
            onSelectAll = { select ->
                onTogglePdfSelection?.let { toggle ->
                    (0 until composer.pendingPdfPages.coerceAtLeast(1)).forEach { i ->
                        if ((i in pdfViewerSelection) != select) toggle(i)
                    }
                }
            },
            onPreviewPage = { index ->
                composer.showPdfPageDialog = false
                composer.pdfDialogHiddenForPreview = true
                onPdfPreviewSelect?.invoke(composer.pendingPdfRenderedPaths, index)
            },
            onConfirm = { selection ->
                composer.confirmPendingPdfSelection(selection.selectedPages)
            },
            onDismiss = {
                composer.dismissPendingPdf()
            },
        )
    }

    val pendingVideo = composer.pendingVideoUri
    if (composer.showVideoSliceDialog && pendingVideo != null) {
        VideoSliceDialog(
            videoUri = pendingVideo,
            durationMs = composer.pendingVideoDurationMs,
            onConfirm = { result ->
                composer.showVideoSliceDialog = false
                composer.addSlicedVideo(result.uri, result.frameCount, result.intervalMs)
                composer.processNextVideo()
            },
            onDismiss = {
                composer.showVideoSliceDialog = false
                composer.processNextVideo()
            },
        )
    }
}
