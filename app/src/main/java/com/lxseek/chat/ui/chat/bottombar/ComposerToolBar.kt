package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.model.ContextBudget
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.ui.common.openAiServiceTierShortLabel
import com.lxseek.chat.ui.common.thinkingControlShortLabel
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.lxseek.chat.ui.theme.LxDesign
import kotlinx.coroutines.delay

/**
 * Composer toolbar hosting the model picker, context-budget indicator and the
 * advanced tools menu.
 *
 * The three menus share a single [activeMenu] slot so they are mutually
 * exclusive — opening the model picker closes the context popover, and vice
 * versa. Each menu carries its own dismiss-timestamp guard so a dismiss
 * followed by an immediate re-tap (within 200ms) is ignored, which is needed
 * because the anchors live inside a row that also captures touch events.
 *
 * - **Model menu**: searchable list of enabled models, grouped by provider.
 * - **Context menu**: circular token-budget gauge with compact-boundary hint.
 * - **Tools menu**: thinking, web search, code execution, OpenAI service tier,
 *   shell, compact — plus a shortcut back to the model picker.
 *
 * The thinking and OpenAI service tier panels open in modal bottom sheets
 * owned by [ChatBottomBar]; this composable only requests their display via
 * [onShowThinkingSheet] / [onShowOpenAiServiceTierSheet] so the sheet state
 * remains hoisted at the container level.
 *
 * Extracted from [ChatBottomBar] to keep the bottom-bar container free of the
 * ~420 lines of menu branching and tool-toggle wiring.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerToolBar(
    enabledModels: Set<String>,
    selectedModel: String,
    modelAliases: Map<String, String>,
    isModelValid: Boolean,
    thinkingEnabled: Boolean,
    thinkingLevel: String,
    thinkingBudgetEnabled: Boolean,
    thinkingBudgetTokens: Int,
    openAiServiceTierAvailable: Boolean,
    openAiServiceTierEnabled: Boolean,
    openAiServiceTier: String,
    webSearchEnabled: Boolean,
    shellEnabled: Boolean,
    codeExecutionEnabled: Boolean,
    googleSearchEnabled: Boolean,
    showWebSearch: Boolean,
    showShell: Boolean,
    canCompact: Boolean,
    isCompacting: Boolean,
    contextEstimatedTokens: Int,
    contextLogicalMessageCount: Int,
    contextTokenBudget: Int,
    hasCompactBoundary: Boolean,
    onModelSelect: (String) -> Unit,
    onThinkingToggle: (Boolean) -> Unit,
    onThinkingLevelChange: (String) -> Unit,
    onThinkingBudgetEnabledChange: (Boolean) -> Unit,
    onThinkingBudgetTokensChange: (Int) -> Unit,
    onOpenAiServiceTierToggle: (Boolean) -> Unit,
    onOpenAiServiceTierChange: (String) -> Unit,
    onWebSearchToggle: (Boolean) -> Unit,
    onShellToggle: (Boolean) -> Unit,
    onCodeExecutionToggle: (Boolean) -> Unit,
    onGoogleSearchToggle: (Boolean) -> Unit,
    onCompactClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onShowThinkingSheet: () -> Unit,
    onShowOpenAiServiceTierSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeMenu by remember { mutableStateOf<String?>(null) }
    var modelSearchQuery by rememberSaveable { mutableStateOf("") }
    var lastModelDismissTime by remember { mutableLongStateOf(0L) }
    var lastContextDismissTime by remember { mutableLongStateOf(0L) }
    var lastToolsDismissTime by remember { mutableLongStateOf(0L) }

    val haptics = LocalLxChatHaptics.current
    val provider = com.lxseek.chat.model.ModelId.parse(selectedModel).providerName

    val currentModelLabel = when {
        isModelValid -> modelAliases[selectedModel] ?: selectedModel
        enabledModels.isNotEmpty() -> stringResource(R.string.select_model)
        else -> stringResource(R.string.no_model_selected)
    }

    // 工具栏按钮之间统一 4dp 间距
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModelPickerMenu(
            isActive = activeMenu == "model",
            onToggle = {
                val now = System.currentTimeMillis()
                if (activeMenu == "model") {
                    activeMenu = null
                } else if (now - lastModelDismissTime > 200) {
                    activeMenu = "model"
                }
            },
            onDismiss = {
                if (activeMenu == "model") {
                    activeMenu = null
                    lastModelDismissTime = System.currentTimeMillis()
                }
            },
            onClearDismissGuard = { lastModelDismissTime = 0L },
            enabledModels = enabledModels,
            modelAliases = modelAliases,
            currentModelLabel = currentModelLabel,
            modelSearchQuery = modelSearchQuery,
            onModelSearchQueryChange = { modelSearchQuery = it },
            onModelSelect = { model ->
                haptics.selection()
                onModelSelect(model)
                activeMenu = null
                lastModelDismissTime = 0L
            },
        )

        ContextBudgetMenu(
            isActive = activeMenu == "context",
            onToggle = {
                val now = System.currentTimeMillis()
                if (activeMenu == "context") {
                    activeMenu = null
                } else if (now - lastContextDismissTime > 200) {
                    activeMenu = "context"
                }
            },
            onDismiss = {
                if (activeMenu == "context") {
                    activeMenu = null
                    lastContextDismissTime = System.currentTimeMillis()
                }
            },
            contextEstimatedTokens = contextEstimatedTokens,
            contextLogicalMessageCount = contextLogicalMessageCount,
            contextTokenBudget = contextTokenBudget,
            hasCompactBoundary = hasCompactBoundary,
        )

        ToolsMenu(
            isActive = activeMenu == "tools",
            onToggle = {
                val now = System.currentTimeMillis()
                if (activeMenu == "tools") {
                    activeMenu = null
                } else if (now - lastToolsDismissTime > 200) {
                    activeMenu = "tools"
                }
            },
            onDismiss = {
                if (activeMenu == "tools") {
                    activeMenu = null
                    lastToolsDismissTime = System.currentTimeMillis()
                }
            },

            onOpenModelMenu = {
                activeMenu = null
                lastToolsDismissTime = System.currentTimeMillis()
                activeMenu = "model"
            },
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
            isModelValid = isModelValid,
            provider = provider,
            currentModelLabel = currentModelLabel,
            onThinkingToggle = onThinkingToggle,
            onWebSearchToggle = onWebSearchToggle,
            onCodeExecutionToggle = onCodeExecutionToggle,
            onGoogleSearchToggle = onGoogleSearchToggle,
            onOpenAiServiceTierToggle = onOpenAiServiceTierToggle,
            onShellToggle = onShellToggle,
            onCompactClick = {
                activeMenu = null
                onCompactClick()
            },
            onAdvancedClick = {
                activeMenu = null
                onAdvancedClick()
            },
            onShowThinkingSheet = {
                activeMenu = null
                onShowThinkingSheet()
            },
            onShowOpenAiServiceTierSheet = {
                activeMenu = null
                onShowOpenAiServiceTierSheet()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerMenu(
    isActive: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onClearDismissGuard: () -> Unit,
    enabledModels: Set<String>,
    modelAliases: Map<String, String>,
    currentModelLabel: String,
    modelSearchQuery: String,
    onModelSearchQueryChange: (String) -> Unit,
    onModelSelect: (String) -> Unit,
) {
    ExposedDropdownMenuBox(
        expanded = isActive,
        onExpandedChange = { },
    ) {
        FilterChip(
            selected = isActive,
            onClick = onToggle,
            label = { Text(currentModelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier
                .widthIn(max = 180.dp)
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = true,
                ),
        )
        ExposedDropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            expanded = isActive,
            onDismissRequest = onDismiss,
            matchTextFieldWidth = false,
            shape = MaterialTheme.shapes.medium,
        ) {
            if (enabledModels.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.models_no_models)) },
                    onClick = {
                        onClearDismissGuard()
                    },
                    enabled = false,
                )
            } else {
                val sortedModels = remember(enabledModels) {
                    enabledModels.sortedWith(
                        compareBy(
                            { com.lxseek.chat.model.ModelId.parse(it).providerName.lowercase() },
                            { com.lxseek.chat.model.ModelId.parse(it).apiModelName.lowercase() },
                        ),
                    )
                }
                val searchFocusRequester = remember { FocusRequester() }
                LaunchedEffect(isActive) {
                    if (isActive) {
                        delay(150)
                        runCatching { searchFocusRequester.requestFocus() }
                    }
                }
                OutlinedTextField(
                    value = modelSearchQuery,
                    onValueChange = onModelSearchQueryChange,
                    placeholder = { Text(stringResource(R.string.models_search_hint)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            stringResource(R.string.search),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = if (modelSearchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { onModelSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, stringResource(R.string.models_clear_search))
                            }
                        }
                    } else null,
                    modifier = Modifier
                        .widthIn(max = 180.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .focusRequester(searchFocusRequester),
                    shape = LxDesign.shapeS,
                )
                val normalizedQuery = modelSearchQuery.trim()
                val filteredModels = if (normalizedQuery.isBlank()) sortedModels else sortedModels.filter { model ->
                    model.contains(normalizedQuery, ignoreCase = true) ||
                        (modelAliases[model]?.contains(normalizedQuery, ignoreCase = true) == true) ||
                        com.lxseek.chat.model.ModelId.parse(model).providerName.contains(normalizedQuery, ignoreCase = true)
                }
                if (filteredModels.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.models_search_empty)) },
                        onClick = {},
                        enabled = false,
                    )
                } else {
                    filteredModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                val parsed = com.lxseek.chat.model.ModelId.parse(model)
                                val displayName = modelAliases[model] ?: parsed.apiModelName
                                Text("$displayName · ${parsed.providerName}")
                            },
                            onClick = { onModelSelect(model) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextBudgetMenu(
    isActive: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    contextEstimatedTokens: Int,
    contextLogicalMessageCount: Int,
    contextTokenBudget: Int,
    hasCompactBoundary: Boolean,
) {
    ExposedDropdownMenuBox(
        expanded = isActive,
        onExpandedChange = { },
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(40.dp)
                .then(
                    // 选中态：primaryContainer 圆形背景明确视觉反馈
                    if (isActive) Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer, CircleShape
                    ) else Modifier
                )
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
            expanded = isActive,
            onDismissRequest = onDismiss,
            matchTextFieldWidth = false,
            shape = LxDesign.shapeS,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsMenu(
    isActive: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,

    onOpenModelMenu: () -> Unit,
    thinkingEnabled: Boolean,
    thinkingLevel: String,
    thinkingBudgetEnabled: Boolean,
    thinkingBudgetTokens: Int,
    openAiServiceTierAvailable: Boolean,
    openAiServiceTierEnabled: Boolean,
    openAiServiceTier: String,
    webSearchEnabled: Boolean,
    shellEnabled: Boolean,
    codeExecutionEnabled: Boolean,
    googleSearchEnabled: Boolean,
    showWebSearch: Boolean,
    showShell: Boolean,
    canCompact: Boolean,
    isCompacting: Boolean,
    isModelValid: Boolean,
    provider: String,
    currentModelLabel: String,
    onThinkingToggle: (Boolean) -> Unit,
    onWebSearchToggle: (Boolean) -> Unit,
    onCodeExecutionToggle: (Boolean) -> Unit,
    onGoogleSearchToggle: (Boolean) -> Unit,
    onOpenAiServiceTierToggle: (Boolean) -> Unit,
    onShellToggle: (Boolean) -> Unit,
    onCompactClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onShowThinkingSheet: () -> Unit,
    onShowOpenAiServiceTierSheet: () -> Unit,
) {
    ExposedDropdownMenuBox(
        expanded = isActive,
        onExpandedChange = { },
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(40.dp)
                .then(
                    // 选中态：primaryContainer 圆形背景明确视觉反馈
                    if (isActive) Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer, CircleShape
                    ) else Modifier
                )
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = true,
                ),
        ) {
            Icon(
                Icons.Default.MoreVert,
                stringResource(R.string.tools),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExposedDropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            expanded = isActive,
            onDismissRequest = onDismiss,
            matchTextFieldWidth = false,
            shape = LxDesign.shapeS,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(R.string.tools_group_common),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(id = R.drawable.neurology_24),
                            stringResource(R.string.thinking),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.thinking))
                            Text(
                                text = thinkingControlShortLabel(
                                    thinkingEnabled,
                                    thinkingLevel,
                                    thinkingBudgetEnabled,
                                    thinkingBudgetTokens,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                trailingIcon = {
                    Switch(
                        checked = thinkingEnabled,
                        onCheckedChange = onThinkingToggle,
                        modifier = Modifier.scale(0.7f),
                    )
                },
                onClick = onShowThinkingSheet,
            )
            if (showWebSearch) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Language,
                                stringResource(R.string.web_search),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.web_search))
                        }
                    },
                    trailingIcon = {
                        Switch(
                            checked = webSearchEnabled,
                            onCheckedChange = onWebSearchToggle,
                            modifier = Modifier.scale(0.7f),
                        )
                    },
                    onClick = { onWebSearchToggle(!webSearchEnabled) },
                )
            }
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.select_model))
                        Text(
                            text = currentModelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = onOpenModelMenu,
            )
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(R.string.tools_group_advanced),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Tune,
                            stringResource(R.string.advanced_settings),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.advanced_settings))
                    }
                },
                onClick = onAdvancedClick,
            )
            val isGemini = provider.equals("google", ignoreCase = true) && isModelValid
            if (isGemini) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Terminal,
                                stringResource(R.string.code_execution),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.code_execution))
                            Spacer(modifier = Modifier.width(8.dp))
                            ProviderBadge("Gemini")
                        }
                    },
                    trailingIcon = {
                        Switch(
                            checked = codeExecutionEnabled,
                            onCheckedChange = onCodeExecutionToggle,
                            modifier = Modifier.scale(0.7f),
                        )
                    },
                    onClick = { onCodeExecutionToggle(!codeExecutionEnabled) },
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Language,
                                stringResource(R.string.google_search),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.google_search))
                            Spacer(modifier = Modifier.width(8.dp))
                            ProviderBadge("Gemini")
                        }
                    },
                    trailingIcon = {
                        Switch(
                            checked = googleSearchEnabled,
                            onCheckedChange = onGoogleSearchToggle,
                            modifier = Modifier.scale(0.7f),
                        )
                    },
                    onClick = { onGoogleSearchToggle(!googleSearchEnabled) },
                )
            }
            if (openAiServiceTierAvailable && isModelValid) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = stringResource(R.string.openai_service_tier_title),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
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
                    onClick = onShowOpenAiServiceTierSheet,
                )
            }
            if (showShell) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Terminal,
                                stringResource(R.string.shell_title),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.shell_title))
                        }
                    },
                    trailingIcon = {
                        Switch(
                            checked = shellEnabled,
                            onCheckedChange = onShellToggle,
                            modifier = Modifier.scale(0.7f),
                        )
                    },
                    onClick = { onShellToggle(!shellEnabled) },
                )
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Compress,
                            stringResource(R.string.context_compact),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.context_compact))
                    }
                },
                enabled = canCompact && !isCompacting,
                onClick = onCompactClick,
            )
        }
    }
}