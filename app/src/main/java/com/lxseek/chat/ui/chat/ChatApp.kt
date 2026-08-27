package com.lxseek.chat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.lxseek.chat.data.isOpenAiProtocolProvider
import com.lxseek.chat.api.util.contextWindowUsage
import com.lxseek.chat.api.util.expandSelectedToolProtocolRows
import com.lxseek.chat.util.InboundTextBridge
import com.lxseek.chat.util.gradientBlur
import com.lxseek.chat.model.ContextBudget
import com.lxseek.chat.ui.components.AnimatedBlobBackground
import com.lxseek.chat.ui.components.clearFocusOnTap
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.ui.common.rememberLxChatHaptics
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.ui.motion.openWithMotionPolicy
import com.lxseek.chat.model.OpenAiServiceTiers
import com.lxseek.chat.model.StableMessageList
import com.lxseek.chat.model.StableModelAliases
import com.lxseek.chat.viewmodel.AnimatedScrollDestination
import com.lxseek.chat.viewmodel.ChatViewModel
import com.lxseek.chat.viewmodel.RegenerationTransitionStage
import com.lxseek.chat.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** CompositionLocal for the active-conversation loading flag, so descendants can read it
 *  without an explicit prop drill. Defaults to false. */
val LocalIsLoading = compositionLocalOf { false }
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun ChatApp(
    viewModel: ChatViewModel,
    onNavigateBack: (() -> Unit)? = null,
    drawerEnabled: Boolean = true,
    onOpenSettings: () -> Unit,
    onOpenTasks: (String?) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit,
    onFileContentClick: ((String, String) -> Unit)? = null,
    onPdfPagesClick: ((List<String>, Int) -> Unit)? = null,
    onPdfPreviewSelect: ((List<String>, Int) -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val motionPolicy = LocalLxChatMotionPolicy.current
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.toggleVoiceConversation()
        else viewModel.emitSnackbar(context.getString(R.string.voice_conversation_mic_permission))
    }
    ConversationShareEffect(viewModel, context)

    val latestDrawerEnabled by rememberUpdatedState(drawerEnabled)
    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed,
        confirmStateChange = { newValue ->
            val allowed = newValue == DrawerValue.Closed || latestDrawerEnabled
            if (allowed && newValue != DrawerValue.Closed) {
                focusManager.clearFocus()
            }
            allowed
        }
    )
    DrawerAvailabilityEffect(drawerEnabled, motionPolicy, drawerState)

    val conversations by viewModel.conversations.collectAsState()
    // Defer value reads to the narrow composition regions that actually render messages. The
    // State objects themselves are stable, so stream snapshots no longer recompose all ChatApp.
    val messagesState = viewModel.messages.collectAsState()
    val allMessagesState = viewModel.allMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isCompacting by viewModel.isCompacting.collectAsState()
    val ttsPlayingMessageId by viewModel.ttsPlayingMessageId.collectAsState()
    val voiceConversationState by viewModel.voiceConversation.state.collectAsState()
    val voiceConversationPartial by viewModel.voiceConversation.partialTranscript.collectAsState()
    val voiceConversationAmplitude by viewModel.voiceConversation.amplitude.collectAsState()
    val voiceConversationEnabled by viewModel.settings.voiceConversationEnabled.collectAsState()
    val voiceConversationMode by viewModel.voiceConversation.mode.collectAsState()
    val singleAsrResult by viewModel.voiceConversation.singleAsrResult.collectAsState()
    val singleAsrError by viewModel.voiceConversation.singleAsrError.collectAsState()
    val voiceConversationActive = voiceConversationMode == com.lxseek.chat.viewmodel.VoiceConversationController.Mode.CONVERSATION &&
        voiceConversationState != com.lxseek.chat.viewmodel.VoiceConversationController.State.IDLE
    val singleAsrRecording = voiceConversationMode == com.lxseek.chat.viewmodel.VoiceConversationController.Mode.SINGLE_ASR &&
        (voiceConversationState == com.lxseek.chat.viewmodel.VoiceConversationController.State.LISTENING ||
            voiceConversationState == com.lxseek.chat.viewmodel.VoiceConversationController.State.TRANSCRIBING)
    val compactPreview by viewModel.compactPreview.collectAsState()
    val compactModel by viewModel.settings.contextCompactModel.collectAsState()
    val compactPrompt by viewModel.settings.contextCompactPrompt.collectAsState()
    val compactRetainCount by viewModel.settings.contextCompactRetainCount.collectAsState()
    val manualCompactDialogVisible = rememberSaveable { mutableStateOf(false) }
    val dialogState = rememberChatAppDialogState(manualCompactDialogVisible)
    val queuedSends by viewModel.queuedSends.collectAsState()
    val isStopping by viewModel.isStopping.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val currentConversation by viewModel.currentConversation.collectAsState()
    val loadedMessagesConversationId by viewModel.loadedMessagesConversationId.collectAsState()
    val currentLoop by viewModel.currentLoop.collectAsState()
    val runningLoopIds by viewModel.runningLoopConversationIds.collectAsState()
    val generatingInConversationId by viewModel.generatingInConversationId.collectAsState()
    val selectedModel by viewModel.currentActiveModel.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val thoughtExpandedStates = remember(currentConversationId) { mutableStateMapOf<String, Boolean>() }
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val newChatEntryId by viewModel.newChatEntryId.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val regenerationTransition by viewModel.regenerationTransition.collectAsState()
    val isTransitioningToNewChat by viewModel.isTransitioningToNewChat.collectAsState()
    val totalTokens by viewModel.totalTokens.collectAsState()
    val visualizeContextRollout by viewModel.settings.visualizeContextRollout.collectAsState()
    val maxContextWindow by viewModel.settings.maxContextWindow.collectAsState()
    val globalCodeExecution by viewModel.settings.codeExecutionEnabled.collectAsState()
    val globalGoogleSearch by viewModel.settings.googleSearchEnabled.collectAsState()
    val globalThinkingEnabled by viewModel.settings.thinkingEnabled.collectAsState()
    val globalThinkingLevel by viewModel.settings.thinkingLevel.collectAsState()
    val globalThinkingBudgetEnabled by viewModel.settings.thinkingBudgetEnabled.collectAsState()
    val globalThinkingBudgetTokens by viewModel.settings.thinkingBudgetTokens.collectAsState()
    val globalOpenAiServiceTierEnabled by
        viewModel.settings.openAiServiceTierEnabled.collectAsState()
    val globalOpenAiServiceTier by viewModel.settings.openAiServiceTier.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val globalWebSearch by viewModel.settings.webSearchEnabled.collectAsState()
    val webSearchApiKeys by viewModel.settings.webSearchApiKeys.collectAsState()
    val globalShell by viewModel.settings.shellEnabled.collectAsState()
    val shellDevices by viewModel.settings.shellDevices.collectAsState()
    val toolCallDisplayMode by viewModel.settings.toolCallDisplayMode.collectAsState()
    val thinkingSegmentDisplayMode by viewModel.settings.thinkingSegmentDisplayMode.collectAsState()
    val autoExpandActiveGroup by viewModel.settings.autoExpandActiveGroup.collectAsState()
    val detailedTokenUsage by viewModel.settings.detailedTokenUsage.collectAsState()
    val conversationSettings by viewModel.settings.conversationSettings.collectAsState()
    val pendingSettings by viewModel.pendingConversationSettings.collectAsState()
    // Resolved per-conversation values: override → global default
    val convId = currentConversationId
    val convOverride = if (convId != null) conversationSettings[convId] else pendingSettings
    val codeExecutionEnabled = convOverride?.codeExecutionEnabled ?: globalCodeExecution
    val googleSearchEnabled = convOverride?.googleSearchEnabled ?: globalGoogleSearch
    val thinkingEnabled = convOverride?.thinkingEnabled ?: globalThinkingEnabled
    val thinkingLevel = convOverride?.thinkingLevel ?: globalThinkingLevel
    val thinkingBudgetEnabled = convOverride?.thinkingBudgetEnabled ?: globalThinkingBudgetEnabled
    val thinkingBudgetTokens = convOverride?.thinkingBudgetTokens ?: globalThinkingBudgetTokens
    val openAiServiceTierEnabled =
        convOverride?.openAiServiceTierEnabled ?: globalOpenAiServiceTierEnabled
    val openAiServiceTier = OpenAiServiceTiers.normalize(
        convOverride?.openAiServiceTier ?: globalOpenAiServiceTier,
    )
    val selectedProviderName = viewModel.getProviderForModel(selectedModel)
    val openAiServiceTierAvailable =
        isOpenAiProtocolProvider(selectedProviderName, customProviders)
    // Web Search and Shell: global switch OFF → always false, regardless of override
    val webSearchEnabled = globalWebSearch && (convOverride?.webSearchEnabled ?: true)
    val shellEnabled = globalShell && (convOverride?.shellEnabled ?: true)
    val contextWindow = ContextBudget.normalize(convOverride?.contextWindow ?: maxContextWindow)
    val contextUsage = remember(messagesState.value, allMessagesState.value, contextWindow) {
        contextWindowUsage(
            expandSelectedToolProtocolRows(messagesState.value, allMessagesState.value),
            contextWindow,
        )
    }
    val blurEffectsEnabled by viewModel.settings.blurEffectsEnabled.collectAsState()
    val reduceMotion = motionPolicy.reduceMotion
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val appName by viewModel.settings.appName.collectAsState()
    val haptics = rememberLxChatHaptics(hapticsEnabled)
    // The three send paths (manual Send, queue drain, loop cycle) converge in the Controller at
    // notifySendAccepted, the single choke point for Direct + Queued send acceptances. Wiring the
    // haptics there gives every accepted send exactly one confirm(), independent of which path
    // triggered it or which scroll policy applies.
    SendAcceptedHapticBindingEffect(viewModel, haptics)


    var isExpanded by remember { mutableStateOf(false) }
    // Composer-expand spacer collapse (44dp → 0). An Animatable driven from an effect replaces the
    // former hand-rolled clock, which wrote animation state DURING composition (Compose forbids
    // that — it makes the frame's output depend on when it happened to be composed) and ticked on
    // a fixed 16ms sleep that drifts against the real refresh rate.
    val composerSpacerAnimation = rememberComposerSpacerAnimation(
        isExpanded = isExpanded,
        allowSpatialTransitions = motionPolicy.allowSpatialTransitions,
        expandedHeightPx = with(density) { 44.dp.toPx() },
    )
    val isExpandAnimating = composerSpacerAnimation.isRunning
    val outerSpacerHeightPx = composerSpacerAnimation.outerHeightPx

    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeightDp = with(density) {
        windowSize.height.toDp().value.coerceAtLeast(1f)
    }
    val drawerWidth = with(density) { windowSize.width.toDp() } * 0.8f
    var bottomBarHeightPx by rememberSaveable { mutableFloatStateOf(0f) }
    val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    var drawerProgress by remember { mutableFloatStateOf(0f) }
    // Bottom offset to clear the Settings button in the drawer.
    var settingsButtonTopDp by remember { mutableFloatStateOf(80f) }
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // When expanded, the Surface fills the screen and the model-selector capsule sits
    // at the very bottom. Snackbar must clear: nav bar + IME + Surface outer padding + Box
    // bottom padding + Row height/margin + a small gap.
    val bottomInset = maxOf(navBarBottom, imeBottom)
    SnackbarOffsetEffect(
        drawerProgress = drawerProgress,
        isExpanded = isExpanded,
        bottomBarHeight = bottomBarHeight,
        settingsButtonTopDp = settingsButtonTopDp,
        bottomInset = bottomInset,
        onOffsetChanged = onSnackbarOffsetChanged,
    )
    val imeBottomPx = with(density) { imeBottom.roundToPx() }
    val scrollCoordinator = rememberChatScrollCoordinator(
        currentConversationId,
        imeBottomPx,
    )
    scrollCoordinator.BindLayoutObservation(
        currentConversationId = currentConversationId,
        loadedMessagesConversationId = loadedMessagesConversationId,
        imeBottomPx = imeBottomPx,
        density = density,
    )
    val listState = scrollCoordinator.listState
    val absoluteBottomScrollPhase = scrollCoordinator.absoluteBottomScrollPhase
    val isNearAbsoluteBottom = scrollCoordinator.isNearAbsoluteBottom
    val isWithinAbsoluteBottomAttachThreshold =
        scrollCoordinator.isWithinAbsoluteBottomAttachThreshold
    val imeBottomAnchorState = scrollCoordinator.imeBottomAnchorState
    val streamingTailController = scrollCoordinator.streamingTailController
    val messageLifecycleAppearanceRegistry = scrollCoordinator.messageLifecycleAppearanceRegistry
    val messageHeights = scrollCoordinator.messageHeights
    val viewportHeightPx = scrollCoordinator.viewportHeightPx
    val renderMessagesState = rememberScrollIsolatedMessages(
        conversationId = currentConversationId,
        upstream = messagesState,
        listState = listState,
        bypassScrollIsolation =
            streamingTailController.isAutoFollowing || absoluteBottomScrollPhase.isActive,
    )
    val conversationInteraction = rememberConversationInteractionState(
        currentConversationId = currentConversationId,
        messages = messagesState,
        listState = listState,
    )
    val conversationSearchActive = conversationInteraction.searchActive
    val conversationSearchQuery = conversationInteraction.searchQuery
    val conversationSearchMatchIndex = conversationInteraction.searchMatchIndex
    val shareSelectionActive = conversationInteraction.shareSelectionActive
    val selectedShareMessageIds = conversationInteraction.selectedShareMessageIds
    val selectableShareMessageIds = conversationInteraction.selectableShareMessageIds
    val shareSelectionBarSpace = if (shareSelectionActive) 68.dp else 0.dp
    val conversationSearchMatches = conversationInteraction.searchMatches
    val textFieldState = rememberSaveable(saver = androidx.compose.foundation.text.input.TextFieldState.Saver) { androidx.compose.foundation.text.input.TextFieldState() }
    LaunchedEffect(singleAsrResult) {
        val text = singleAsrResult
        if (!text.isNullOrEmpty()) {
            textFieldState.edit { replace(0, length, text) }
            viewModel.voiceConversation.clearSingleAsrResult()
        }
    }
    LaunchedEffect(singleAsrError) {
        val error = singleAsrError
        if (!error.isNullOrEmpty()) {
            viewModel.emitSnackbar(error)
            viewModel.voiceConversation.clearSingleAsrError()
        }
    }
    // System "select text" (PROCESS_TEXT) tokens: surface them into the composer once visible.
    val inboundText by InboundTextBridge.text.collectAsState()
    LaunchedEffect(inboundText) {
        val text = inboundText
        if (!text.isNullOrBlank()) {
            textFieldState.edit { replace(0, length, text) }
            InboundTextBridge.consume()
        }
    }
    val composer = com.lxseek.chat.ui.chat.bottombar.rememberChatComposerState()
    val inputFocusRequester = remember { FocusRequester() }

    var showLaunchContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        showLaunchContent = true
        inputFocusRequester.requestFocus()
    }


    scrollCoordinator.BindTransitionEffects(
        currentConversationId = currentConversationId,
        currentConversation = currentConversation,
        loadedMessagesConversationId = loadedMessagesConversationId,
        messages = messagesState,
        density = density,
        motionPolicy = motionPolicy,
        bottomBarHeight = bottomBarHeight,
        shareSelectionBarSpace = shareSelectionBarSpace,
        imeBottomPx = imeBottomPx,
        viewModel = viewModel,
        haptics = haptics,
    )

    ComposerDraftLifecycleEffect(
        currentConversationId = currentConversationId,
        viewModel = viewModel,
        composer = composer,
        textFieldState = textFieldState,
    )

    val animatedScrollRequest by viewModel.animatedScrollRequest.collectAsState()
    scrollCoordinator.BindRequestEffects(
        currentConversationId = currentConversationId,
        isNewChatMode = isNewChatMode,
        isLoading = isLoading,
        isStopping = isStopping,
        isSwitching = isSwitching,
        conversationSearchActive = conversationSearchActive,
        shareSelectionActive = shareSelectionActive,
        regenerationTransition = regenerationTransition,
        animatedScrollRequest = animatedScrollRequest,
        messages = messagesState,
        density = density,
        motionPolicy = motionPolicy,
        bottomBarHeight = bottomBarHeight,
        shareSelectionBarSpace = shareSelectionBarSpace,
        viewModel = viewModel,
    )

    ChatNavigationEffects(
        drawerState = drawerState,
        focusManager = focusManager,
        scope = scope,
        motionPolicy = motionPolicy,
        onNavigateBack = onNavigateBack,
        conversationInteraction = conversationInteraction,
        onCollapseComposer = { isExpanded = false },
    )

    AnsweringHapticEffect(
        messages = messagesState,
        isLoading = isLoading,
        generatingInConversationId = generatingInConversationId,
        currentConversationId = currentConversationId,
        hapticsEnabled = hapticsEnabled,
        haptics = haptics,
    )

    CompositionLocalProvider(
        LocalLxChatHaptics provides haptics,
        LocalIsLoading provides isLoading,
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerEnabled,
        scrimColor = DrawerDefaults.scrimColor,
        drawerContent = {
            ChatDrawerContent(
                viewModel = viewModel,
                drawerWidth = drawerWidth,
                drawerState = drawerState,
                scope = scope,
                inputFocusRequester = inputFocusRequester,
                onDrawerProgress = { drawerProgress = it },
                onSettingsButtonTop = { settingsButtonTopDp = it },
                onOpenSettings = onOpenSettings,
                onOpenTasks = { onOpenTasks(null) },
                onRequestRename = dialogState::requestRename,
                onRequestDelete = dialogState::requestDelete,
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clearFocusOnTap()
                .onSizeChanged { scrollCoordinator.recordViewportHeight(it.height) }
        ) {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val (targetCa, targetQa) = if (!dark) {
                0.00f to 0.00f
            } else if (isNewChatMode) {
                0.20f to 0.10f
            } else {
                0.02f to 0.01f
            }
            val ca by animateFloatAsState(targetCa, tween(800))
            val qa by animateFloatAsState(targetQa, tween(800))
            val newChatMotion = newChatMotionPolicy(
                reduceMotion = reduceMotion,
                isNewChatMode = isNewChatMode,
                isLoading = isLoading,
                isSwitching = isSwitching,
                newChatEntryId = newChatEntryId,
            )
            AnimatedBlobBackground(
                centerAlpha = ca,
                quarterAlpha = qa,
                blurRadius = 40f,
                dark = dark,
                blurEnabled = blurEffectsEnabled,
                motionEnabled = newChatMotion.animateBackground,
            )

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    ChatTopBar(
                        isNewChatMode = isNewChatMode,
                        conversations = conversations,
                        currentConversationId = currentConversationId,
                        currentConversationTitle = currentConversation?.title,
                        totalTokens = totalTokens,
                        contextWindow = contextWindow,
                        appName = appName,
                        searchActive = conversationSearchActive,
                        searchQuery = conversationSearchQuery,
                        searchMatchIndex = conversationSearchMatchIndex,
                        searchMatchCount = conversationSearchMatches.size,
                        conversationActionsEnabled =
                            !isNewChatMode && currentConversationId != null && !isLoading &&
                                !shareSelectionActive,
                        shareSelectionActive = shareSelectionActive,
                        shareSelectionCount = selectedShareMessageIds.size,
                        shareAllSelected = selectedShareMessageIds.isNotEmpty() &&
                            selectableShareMessageIds.isNotEmpty() &&
                            selectedShareMessageIds.containsAll(selectableShareMessageIds),
                        onDismissShareSelection = { conversationInteraction.dismissShareSelection() },
                        onShareToggleAll = { conversationInteraction.toggleAllShareMessages() },
                        onNavigateBack = onNavigateBack,
                        onOpenDrawer = {
                            if (drawerEnabled) {
                                focusManager.clearFocus()
                                scope.launch { drawerState.openWithMotionPolicy(motionPolicy) }
                            }
                        },
                        onSearchQueryChange = { query ->
                            conversationInteraction.updateSearchQuery(query)
                        },
                        onSearchPrevious = {
                            if (conversationInteraction.previousSearchMatch()) {
                                haptics.selection()
                            }
                        },
                        onSearchNext = {
                            if (conversationInteraction.nextSearchMatch()) {
                                haptics.selection()
                            }
                        },
                        onSearchDismiss = {
                            conversationInteraction.dismissSearch()
                            focusManager.clearFocus()
                        },
                        onSearchClick = {
                            conversationInteraction.activateSearch()
                        },
                        onSystemPromptClick = dialogState::showPrompt,
                        onForkConversation = {
                            viewModel.forkConversationFrom()
                        },
                        onShareConversation = {
                            conversationInteraction.dismissSearch()
                            focusManager.clearFocus()
                            conversationInteraction.activateShareSelection()
                        },
                        onNewChat = {
                            if (!isNewChatMode) {
                                isExpanded = false
                                viewModel.createNewChat()
                                inputFocusRequester.requestFocus()
                            }
                        },
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    val topBarH = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
                    val pivotY =
                        ((windowHeightDp + topBarH.value / 2f - bottomBarHeight.value) / 2f)
                            .coerceAtLeast(0f) / windowHeightDp
                    AnimatedContent(
                        targetState = Pair(isNewChatMode, showLaunchContent),
                        transitionSpec = {
                            val targetNewChat = targetState.first
                            val targetShowLaunch = targetState.second
                            val initialNewChat = initialState.first
                            val initialShowLaunch = initialState.second

                            if (targetNewChat && (targetShowLaunch != initialShowLaunch || targetNewChat != initialNewChat)) {
                                val fadeInSpec = tween<Float>(500)
                                val enter = if (motionPolicy.allowSpatialTransitions) {
                                    val enterSpec = tween<Float>(
                                        700,
                                        easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f),
                                    )
                                    fadeIn(animationSpec = fadeInSpec) +
                                        scaleIn(
                                            initialScale = 0.6f,
                                            transformOrigin = TransformOrigin(0.5f, pivotY),
                                            animationSpec = enterSpec,
                                        )
                                } else {
                                    fadeIn(animationSpec = fadeInSpec)
                                }
                                enter
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            } else if (!targetNewChat && !initialNewChat) {
                                // Switching between existing conversations: no animation
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                // Returning from new-chat to an existing conversation
                                fadeIn(animationSpec = tween(300))
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            }
                        },
                        label = "MainContentTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { (targetNewChat, targetShowLaunch) ->
                        if (!targetNewChat) {
                            val messageListModifier = if (blurEffectsEnabled) {
                                Modifier.fillMaxSize().gradientBlur(blurAtTopDp = 8f, blurAtBottomDp = 0f)
                            } else {
                                Modifier.fillMaxSize()
                            }
                            val streamingFollowAvailability = streamingTailAvailability(
                                generationActive = isLoading,
                                blocked =
                                    isStopping ||
                                        isSwitching ||
                                        conversationSearchActive ||
                                        shareSelectionActive ||
                                        !motionPolicy.allowProgrammaticScrollMotion,
                                programmaticHandoff =
                                    imeBottomAnchorState.active ||
                                        absoluteBottomScrollPhase.isActive ||
                                        animatedScrollRequest?.conversationId ==
                                            currentConversationId ||
                                        regenerationTransition?.conversationId ==
                                            currentConversationId,
                            )
                            Box(modifier = Modifier.fillMaxSize()) {
                            MessageList(
                                messages = StableMessageList(renderMessagesState.value),
                                allMessages = StableMessageList(allMessagesState.value),
                                conversationId = currentConversationId,
                                modifier = messageListModifier,
                                state = listState,
                                // Per-conversation generation gate: isLoading mirrors the OPEN
                                // conversation's slot only (ConversationGenerationState.onActive
                                // gates on current == id), so message actions freeze while THIS
                                // conversation generates — background conversations don't affect it.
                                isLoading = isLoading,
                                isCompacting = isCompacting,
                                compactPreview = compactPreview,
                                isStopping = isStopping,
                                isSwitching = isSwitching,
                                streamingAutoFollowEnabled =
                                    streamingFollowAvailability.enabled,
                                streamingAutoFollowPaused =
                                    streamingFollowAvailability.paused,
                                streamingTailWithinAttachThreshold =
                                    isWithinAbsoluteBottomAttachThreshold,
                                programmaticScrollActive =
                                    animatedScrollRequest?.conversationId ==
                                        currentConversationId,
                                streamingTailController = streamingTailController,
                                streamingIndicatorVisible =
                                    isLoading &&
                                        regenerationTransition?.stage !=
                                            RegenerationTransitionStage.ANIMATING,
                                regenerationTransition = regenerationTransition,
                                onRegenerationFadeOutFinished =
                                    viewModel::acknowledgeRegenerationFade,
                                visualizeContextRollout = visualizeContextRollout,
                                toolCallDisplayMode = toolCallDisplayMode,
                                thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                                autoExpandActiveGroup = autoExpandActiveGroup,
                                detailedTokenUsage = detailedTokenUsage,
                                maxContextWindow = contextWindow,
                                modelAliases = StableModelAliases(modelAliases),
                                bottomBarHeight = bottomBarHeight + shareSelectionBarSpace,
                                viewportHeight = viewportHeightPx,
                                messageHeights = messageHeights,
                                lifecycleAppearanceRegistry = messageLifecycleAppearanceRegistry,
                                lifecycleEntranceTargetMessageId = animatedScrollRequest
                                    ?.takeIf { it.conversationId == currentConversationId }
                                    ?.targetMessageId,
                                onEditMessage = { id, text ->
                                    val accepted = viewModel.editMessage(id, text)
                                    if (accepted) haptics.confirm()
                                    accepted
                                },
                                onSwitchBranch = { parentId, currentMessageId, direction ->
                                    haptics.selection()
                                    viewModel.switchBranch(parentId, currentMessageId, direction)
                                },
                                onRegenerate = { id ->
                                    val accepted = viewModel.regenerate(id)
                                    if (accepted) haptics.confirm()
                                    accepted
                                },
                                onResume = { id ->
                                    val accepted = viewModel.resume(id)
                                    if (accepted) haptics.confirm()
                                    accepted
                                },
                                onFork = { id ->
                                    viewModel.forkConversationFrom(id)
                                },
                                onShare = { id ->
                                    viewModel.stopTts()
                                    conversationInteraction.activateShareSelection(id)
                                },
                                onDelete = { id -> viewModel.deleteMessage(id) },
                                ttsPlayingMessageId = ttsPlayingMessageId,
                                onToggleTts = { id ->
                                    val msg = allMessagesState.value.firstOrNull { it.id == id }
                                    if (msg != null) viewModel.toggleTtsForMessage(msg)
                                },
                                searchQuery = if (conversationSearchActive) {
                                    conversationSearchQuery
                                } else {
                                    ""
                                },
                                activeSearchMatch = conversationSearchMatches
                                    .getOrNull(conversationSearchMatchIndex),
                                onSearchMatchDistance = { key, distance ->
                                    conversationInteraction.recordSearchMatchDistance(key, distance)
                                },
                                selectionMode = shareSelectionActive,
                                selectedMessageIds = selectedShareMessageIds,
                                onToggleMessageSelection = { messageId ->
                                    haptics.selection()
                                    conversationInteraction.toggleShareMessage(messageId)
                                },
                                onMessageLongPress = {
                                    if (!shareSelectionActive) {
                                        haptics.longPress()
                                        viewModel.stopTts()
                                        conversationInteraction.activateShareSelection()
                                    }
                                },
                                onMediaClick = { urls, index ->
                                    onMediaClick(urls, index)
                                },
                                onFileContentClick = onFileContentClick?.let { open ->
                                    { name, content ->
                                        open(name, content)
                                    }
                                },
                                onPdfPagesClick = { pages, idx ->
                                    onPdfPagesClick?.invoke(pages, idx)
                                },
                                thoughtExpandedStates = thoughtExpandedStates,
                                contentPadding = PaddingValues(
                                    start = 8.dp,
                                    end = 8.dp,
                                    top = 140.dp,
                                    bottom = bottomBarHeight + shareSelectionBarSpace + 8.dp
                                )
                            )
                            }
                        } else if (targetShowLaunch) {
                            ChatAppWelcomeContent(
                                bottomBarHeight = bottomBarHeight,
                                windowHeightDp = windowHeightDp,
                                topBarHeight = topBarH,
                                newChatEntryId = newChatEntryId,
                                animateWelcomeText = newChatMotion.animateWelcomeText,
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }

                    val showButton = rememberChatAppScrollToBottomButtonVisible(
                        currentConversationId = currentConversationId,
                        loadedMessagesConversationId = loadedMessagesConversationId,
                        isNewChatMode = isNewChatMode,
                        isSwitching = isSwitching,
                        shareSelectionActive = shareSelectionActive,
                        isNearAbsoluteBottom = isNearAbsoluteBottom,
                        absoluteBottomScrollPhase = absoluteBottomScrollPhase,
                        listState = listState,
                        streamingTailController = streamingTailController,
                        regenerationTransition = regenerationTransition,
                        imeBottomAnchorActive = imeBottomAnchorState.active,
                    )

                    ChatAppScrollToBottomFab(
                        showButton = showButton,
                        motionPolicy = motionPolicy,
                        bottomBarHeight = bottomBarHeight,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onRequestScroll = { scrollCoordinator.requestAbsoluteBottomScroll() },
                    )

                    ChatAppShareSelectionOverlay(
                        shareSelectionActive = shareSelectionActive,
                        motionPolicy = motionPolicy,
                        bottomBarHeight = bottomBarHeight,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        hasSelection = selectedShareMessageIds.isNotEmpty(),
                        onDismiss = { conversationInteraction.dismissShareSelection() },
                        onCopy = {
                            if (selectedShareMessageIds.isNotEmpty()) {
                                viewModel.copyMessagesAsPlainText(selectedShareMessageIds)
                            }
                        },
                        onShareMarkdown = {
                            if (selectedShareMessageIds.isNotEmpty()) {
                                viewModel.shareMessages(selectedShareMessageIds)
                            }
                        },
                        onShareImage = {
                            if (selectedShareMessageIds.isNotEmpty()) {
                                viewModel.shareMessagesAsLongImage(
                                    selectedShareMessageIds,
                                    currentConversation?.title ?: "",
                                )
                            }
                        },
                        onSaveToGallery = {
                            if (selectedShareMessageIds.isNotEmpty()) {
                                viewModel.saveLongImageToGallery(
                                    selectedShareMessageIds,
                                    currentConversation?.title ?: "",
                                )
                            }
                        },
                        onConfirm = {
                            val selection = conversationInteraction.takeShareSelection()
                            if (selection.isNotEmpty()) {
                                viewModel.shareMessages(selection)
                            }
                        },
                    )

                    ChatAppSwitchingOverlay(
                        isSwitching = isSwitching,
                        isTransitioningToNewChat = isTransitioningToNewChat,
                    )


                }
            }

            if (!shareSelectionActive) {
            ChatAppBottomBarSection(
                viewModel = viewModel,
                haptics = haptics,
                dialogState = dialogState,
                scrollCoordinator = scrollCoordinator,
                textFieldState = textFieldState,
                composer = composer,
                inputFocusRequester = inputFocusRequester,
                modifier = Modifier.align(Alignment.BottomCenter),
                isExpanded = isExpanded,
                onExpandedChange = { isExpanded = it },
                isExpandAnimating = isExpandAnimating,
                outerSpacerHeightPx = outerSpacerHeightPx,
                onBottomBarHeightChanged = { bottomBarHeightPx = it },
                isLoading = isLoading,
                isCompacting = isCompacting,
                isSwitching = isSwitching,
                isStopping = isStopping,
                currentConversationId = currentConversationId,
                currentLoop = currentLoop,
                runningLoopIds = runningLoopIds,
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
                webSearchEnabled = webSearchEnabled,
                shellEnabled = shellEnabled,
                globalWebSearch = globalWebSearch,
                globalShell = globalShell,
                shellDevices = shellDevices,
                contextUsage = contextUsage,
                compactModel = compactModel,
                compactPrompt = compactPrompt,
                compactRetainCount = compactRetainCount,
                queuedSends = queuedSends,
                onMediaClick = onMediaClick,
                onPdfPagesClick = onPdfPagesClick,
                onPdfPreviewSelect = onPdfPreviewSelect,
                pdfViewerSelection = pdfViewerSelection,
                onTogglePdfSelection = onTogglePdfSelection,
                onInitPdfSelection = onInitPdfSelection,
                fullScreenViewerUrls = fullScreenViewerUrls,
                voiceConversationState = voiceConversationState,
                voiceConversationAmplitude = voiceConversationAmplitude,
                voiceConversationEnabled = voiceConversationEnabled,
                voiceConversationActive = voiceConversationActive,
                singleAsrRecording = singleAsrRecording,
                onVoiceConversationToggle = {
                    val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        viewModel.toggleVoiceConversation()
                    } else {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onSingleAsrToggle = {
                    val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        if (singleAsrRecording) viewModel.stopSingleAsr()
                        else viewModel.startSingleAsr()
                    } else {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopSingleAsr = { viewModel.stopSingleAsr() },

            )
            } else {
                LaunchedEffect(Unit) { bottomBarHeightPx = 0f }
            }
            if (voiceConversationMode == com.lxseek.chat.viewmodel.VoiceConversationController.Mode.SINGLE_ASR) {
                // Single-shot ASR: compact bottom card, transcript lands in the composer.
                SingleAsrOverlay(
                    state = voiceConversationState,
                    partialTranscript = voiceConversationPartial,
                    amplitude = voiceConversationAmplitude,
                    onFinish = { viewModel.stopSingleAsr() },
                    onCancel = { viewModel.stopVoiceConversation() },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = bottomBarHeight + 8.dp),
                )
            } else {
                // Multi-turn real-time conversation: full-screen voiceprint overlay.
                VoiceConversationOverlay(
                    state = voiceConversationState,
                    partialTranscript = voiceConversationPartial,
                    amplitude = voiceConversationAmplitude,
                    // End gracefully: an in-flight recording is transcribed instead of discarded
                    // (single ASR → composer, conversation → sent), then the loop stops.
                    onExit = { viewModel.voiceConversation.finishConversationTurn() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        }
        }

    ChatAppDialogHost(
        state = dialogState,
        viewModel = viewModel,
        haptics = haptics,
        scope = scope,
        compactModel = compactModel,
        selectedModel = selectedModel,
        compactPrompt = compactPrompt,
        compactRetainCount = compactRetainCount,
        enabledModels = enabledModels,
        modelAliases = modelAliases,
        isCompacting = isCompacting,
    )
}
