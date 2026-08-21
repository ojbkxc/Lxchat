package com.lxseek.chat.ui.chat.bottombar

import com.lxseek.chat.ui.components.DialogWindowEdgeToEdge

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.model.ContextBudget
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.QueuedSend
import com.lxseek.chat.ui.chat.PdfPageSelectDialog
import com.lxseek.chat.ui.chat.VideoSliceDialog
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.ui.common.OpenAiServiceTierControlPanel
import com.lxseek.chat.ui.common.ThinkingControlPanel
import com.lxseek.chat.ui.common.openAiServiceTierShortLabel
import com.lxseek.chat.ui.common.thinkingControlShortLabel
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.lxseek.chat.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import com.lxseek.chat.ui.theme.ChatType
import com.lxseek.chat.util.noOpBringIntoView
import com.lxseek.chat.viewmodel.SendAcceptance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val CHAT_BOTTOM_BAR_OUTER_RADIUS = 12.dp
internal val CHAT_BOTTOM_BAR_OUTER_SHAPE = RoundedCornerShape(CHAT_BOTTOM_BAR_OUTER_RADIUS)

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
    // Forwarded to ComposerSendButton so transient prompts (e.g. "select a model first")
    // go through the ViewModel snackbar channel instead of a raw Toast.
    onToast: (String) -> Unit = {},
) {
    val allowSpatialTransitions = LocalLxChatMotionPolicy.current.allowSpatialTransitions
    val scrollState = rememberScrollState()
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)

    val composer = composerState

    val context = LocalContext.current
    val haptics = LocalLxChatHaptics.current
    var showThinkingSheet by rememberSaveable { mutableStateOf(false) }
    var showOpenAiServiceTierSheet by rememberSaveable { mutableStateOf(false) }
    val composerOcclusionColor = MaterialTheme.colorScheme.surfaceContainer
    val composerOcclusionShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
    )

    // Restore PDF dialog after viewer closes
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
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> composer.onPickImages(uris) }
    val videoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> composer.onPickVideos(uris) }
    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris -> composer.onPickFiles(uris, onInitPdfSelection) }
    val activityLaunchScope = rememberCoroutineScope()
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPermissionPath by rememberSaveable { mutableStateOf<String?>(null) }
    var internalCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
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

        TextField(
            state = textFieldState,
            scrollState = scrollState,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    onInputFocusChanged(focusState.isFocused)
                }
                .verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            placeholder = {
                Text(
                    stringResource(R.string.ask_lxchat),
                    style = ChatType.input,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            enabled = true,
            lineLimits = TextFieldLineLimits.MultiLine(1, 6),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            textStyle = ChatType.input.copy(color = MaterialTheme.colorScheme.onSurface),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noOpBringIntoView()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                var showAddMenu by remember { mutableStateOf(false) }
                var lastAddDismissTime by remember { mutableLongStateOf(0L) }
                ExposedDropdownMenuBox(
                    expanded = showAddMenu,
                    onExpandedChange = { }
                ) {
                    IconButton(
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (showAddMenu) {
                                showAddMenu = false
                            } else if (now - lastAddDismissTime > 200) {
                                showAddMenu = true
                            }
                        },
                        modifier = Modifier.size(28.dp).menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    ) {
                        Icon(Icons.Default.Add, stringResource(R.string.add_attachment), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        expanded = showAddMenu,
                        onDismissRequest = {
                            if (showAddMenu) {
                                showAddMenu = false
                                lastAddDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchTextFieldWidth = false,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.PhotoCamera,
                                        null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.camera))
                                }
                            },
                            onClick = {
                                showAddMenu = false
                                lastAddDismissTime = 0L
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
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Image, stringResource(R.string.photos), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.photos))
                                }
                            },
                            onClick = {
                                showAddMenu = false
                                lastAddDismissTime = 0L
                                photoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Videocam, stringResource(R.string.videos), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.videos))
                                }
                            },
                            onClick = {
                                showAddMenu = false
                                lastAddDismissTime = 0L
                                videoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AttachFile, stringResource(R.string.files), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.files))
                                }
                            },
                            onClick = {
                                showAddMenu = false
                                lastAddDismissTime = 0L
                                fileLauncher.launch("*/*")
                            }
                        )
                    }
                }
                var activeMenu by remember { mutableStateOf<String?>(null) }
                var modelSearchQuery by rememberSaveable { mutableStateOf("") }
                var lastModelDismissTime by remember { mutableLongStateOf(0L) }
                var lastContextDismissTime by remember { mutableLongStateOf(0L) }
                var lastToolsDismissTime by remember { mutableLongStateOf(0L) }

                val provider = com.lxseek.chat.model.ModelId.parse(selectedModel).providerName

                val currentModelLabel = when {
                    isModelValid -> modelAliases[selectedModel] ?: selectedModel
                    enabledModels.isNotEmpty() -> stringResource(R.string.select_model)
                    else -> stringResource(R.string.no_model_selected)
                }

                ExposedDropdownMenuBox(
                    expanded = activeMenu == "model",
                    onExpandedChange = { }
                ) {
                    FilterChip(
                        selected = activeMenu == "model",
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (activeMenu == "model") {
                                activeMenu = null
                            } else if (now - lastModelDismissTime > 200) {
                                activeMenu = "model"
                            }
                        },
                        label = { Text(currentModelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                    )
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        expanded = activeMenu == "model", 
                        onDismissRequest = { 
                            if (activeMenu == "model") {
                                activeMenu = null
                                lastModelDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchTextFieldWidth = false,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (enabledModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.models_no_models)) },
                                onClick = {
                                    activeMenu = null
                                    lastModelDismissTime = 0L // Reset to allow immediate re-open
                                },
                                enabled = false
                            )
                        } else {
                            // Grouped by provider, then alphabetical. enabledModels is a Set whose
                            // iteration order is insertion order (i.e. whenever each model was
                            // enabled), which scrambles providers together in the picker.
                            val sortedModels = remember(enabledModels) {
                                enabledModels.sortedWith(
                                    compareBy(
                                        { com.lxseek.chat.model.ModelId.parse(it).providerName.lowercase() },
                                        { com.lxseek.chat.model.ModelId.parse(it).apiModelName.lowercase() },
                                    )
                                )
                            }
                            val searchFocusRequester = remember { FocusRequester() }
                            LaunchedEffect(activeMenu == "model") {
                                if (activeMenu == "model") {
                                    delay(150)
                                    runCatching { searchFocusRequester.requestFocus() }
                                }
                            }
                            OutlinedTextField(
                                value = modelSearchQuery,
                                onValueChange = { modelSearchQuery = it },
                                placeholder = { Text(stringResource(R.string.models_search_hint)) },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, "搜索", modifier = Modifier.size(18.dp)) },
                                trailingIcon = if (modelSearchQuery.isNotEmpty()) {
                                    { IconButton(onClick = { modelSearchQuery = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.models_clear_search)) } }
                                } else null,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).focusRequester(searchFocusRequester),
                                shape = RoundedCornerShape(12.dp),
                            )
                            val normalizedQuery = modelSearchQuery.trim()
                            val filteredModels = if (normalizedQuery.isBlank()) sortedModels else sortedModels.filter { model ->
                                model.contains(normalizedQuery, ignoreCase = true) ||
                                    (modelAliases[model]?.contains(normalizedQuery, ignoreCase = true) == true) ||
                                    com.lxseek.chat.model.ModelId.parse(model).providerName.contains(normalizedQuery, ignoreCase = true)
                            }
                            if (filteredModels.isEmpty()) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.models_search_empty)) }, onClick = {}, enabled = false)
                            } else {
                                filteredModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            val parsed = com.lxseek.chat.model.ModelId.parse(model)
                                            val displayName = modelAliases[model] ?: parsed.apiModelName
                                            Text("$displayName · ${parsed.providerName}")
                                        },
                                        onClick = { haptics.selection(); onModelSelect(model); activeMenu = null; lastModelDismissTime = 0L }
                                    )
                                }
                            }
                        }
                    }
                }
                
                ExposedDropdownMenuBox(
                    expanded = activeMenu == "context",
                    onExpandedChange = { },
                ) {
                    IconButton(
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (activeMenu == "context") {
                                activeMenu = null
                            } else if (now - lastContextDismissTime > 200) {
                                activeMenu = "context"
                            }
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .menuAnchor(
                                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                enabled = true,
                            ),
                    ) {
                        CircularProgressIndicator(
                            progress = {
                                if (contextTokenBudget <= 0) 0f else
                                    (contextEstimatedTokens.toFloat() / contextTokenBudget)
                                        .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = if (contextEstimatedTokens >= contextTokenBudget) {
                                MaterialTheme.colorScheme.error
                            } else MaterialTheme.colorScheme.primary,
                        )
                    }
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        expanded = activeMenu == "context",
                        onDismissRequest = {
                            if (activeMenu == "context") {
                                activeMenu = null
                                lastContextDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchTextFieldWidth = false,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.context_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            CircularProgressIndicator(
                                progress = {
                                    if (contextTokenBudget <= 0) 0f else
                                        (contextEstimatedTokens.toFloat() / contextTokenBudget)
                                            .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally),
                                strokeWidth = 4.dp,
                            )
                            Text(
                                text = stringResource(
                                    R.string.context_usage_messages,
                                    ContextBudget.compactLabel(contextEstimatedTokens),
                                    ContextBudget.compactLabel(contextTokenBudget),
                                    contextLogicalMessageCount,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (hasCompactBoundary) {
                                    stringResource(R.string.context_boundary_active)
                                } else {
                                    stringResource(R.string.context_boundary_none)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = activeMenu == "tools",
                    onExpandedChange = { }
                ) {
                    IconButton(
                        onClick = { 
                            val now = System.currentTimeMillis()
                            if (activeMenu == "tools") {
                                activeMenu = null
                            } else if (now - lastToolsDismissTime > 200) {
                                activeMenu = "tools"
                            }
                        }, 
                        modifier = Modifier.size(28.dp).menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    ) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.tools), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        expanded = activeMenu == "tools",
                        onDismissRequest = {
                            if (activeMenu == "tools") {
                                activeMenu = null
                                lastToolsDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchTextFieldWidth = false,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(R.string.select_model))
                                    Text(
                                        text = currentModelLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                activeMenu = null
                                lastToolsDismissTime = System.currentTimeMillis()
                                activeMenu = "model"
                            }
                        )
                        HorizontalDivider()
                        val isGemini = provider.equals("google", ignoreCase = true) && isModelValid
                        if (isGemini) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Terminal, stringResource(R.string.code_execution), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.code_execution))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        ProviderBadge("Gemini")
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = codeExecutionEnabled,
                                        onCheckedChange = { onCodeExecutionToggle(it) },
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                onClick = { onCodeExecutionToggle(!codeExecutionEnabled) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Language, stringResource(R.string.google_search), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.google_search))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        ProviderBadge("Gemini")
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = googleSearchEnabled,
                                        onCheckedChange = { onGoogleSearchToggle(it) },
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                onClick = { onGoogleSearchToggle(!googleSearchEnabled) }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(androidx.compose.ui.res.painterResource(id = com.lxseek.chat.R.drawable.neurology_24), stringResource(R.string.thinking), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(stringResource(R.string.thinking))
                                        Text(
                                            text = thinkingControlShortLabel(
                                                thinkingEnabled,
                                                thinkingLevel,
                                                thinkingBudgetEnabled,
                                                thinkingBudgetTokens
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            trailingIcon = {
                                Switch(
                                    checked = thinkingEnabled,
                                    onCheckedChange = { onThinkingToggle(it) },
                                    modifier = Modifier.scale(0.7f)
                                )
                            },
                            onClick = {
                                activeMenu = null
                                showThinkingSheet = true
                            }
                        )
                        if (openAiServiceTierAvailable && isModelValid) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Speed,
                                            contentDescription = stringResource(R.string.openai_service_tier_title),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(stringResource(R.string.openai_service_tier_title))
                                            Text(
                                                text = openAiServiceTierShortLabel(
                                                    openAiServiceTierEnabled,
                                                    openAiServiceTier,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = openAiServiceTierEnabled,
                                        onCheckedChange = onOpenAiServiceTierToggle,
                                        modifier = Modifier.scale(0.7f),
                                    )
                                },
                                onClick = {
                                    activeMenu = null
                                    showOpenAiServiceTierSheet = true
                                },
                            )
                        }
                        if (showWebSearch) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Language, stringResource(R.string.web_search), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.web_search))
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = webSearchEnabled,
                                        onCheckedChange = { onWebSearchToggle(it) },
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                onClick = { onWebSearchToggle(!webSearchEnabled) }
                            )
                        }
                        if (showShell) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Terminal, stringResource(R.string.shell_title), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.shell_title))
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = shellEnabled,
                                        onCheckedChange = { onShellToggle(it) },
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                onClick = { onShellToggle(!shellEnabled) }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Compress, stringResource(R.string.context_compact), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.context_compact))
                                }
                            },
                            enabled = canCompact && !isCompacting,
                            onClick = { activeMenu = null; onCompactClick() },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Tune, stringResource(R.string.advanced_settings), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.advanced_settings))
                                }
                            },
                            // Unlike the toggle rows, this opens a dialog — collapse the menu first.
                            onClick = { activeMenu = null; onAdvancedClick() }
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onSingleAsrToggle,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = if (singleAsrRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (singleAsrRecording) R.string.voice_conversation_tap_to_stop
                            else R.string.voice_conversation_tap_to_speak
                        ),
                        tint = if (singleAsrRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
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
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            DialogWindowEdgeToEdge()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
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
                    animateSections = true
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

    // Attachment rejection / camera failure dialog
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
            }
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
            onSelectAll = { select -> onTogglePdfSelection?.let { toggle ->
                (0 until composer.pendingPdfPages.coerceAtLeast(1)).forEach { i ->
                    if ((i in pdfViewerSelection) != select) toggle(i)
                }
            }},
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
            }
        )
    }

    // Video slice dialog
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
            }
        )
    }
}
