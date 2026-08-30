package com.lxseek.chat.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.lxseek.chat.R
import com.lxseek.chat.membership.MembershipTier
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.ui.settings.datacontrol.SettingsDataControlPage
import com.lxseek.chat.util.Constants
import com.lxseek.chat.viewmodel.ChatViewModel

/** When true, [SettingsGroup] inside a [SettingsGroupColumn] suppresses its own bottom padding
 *  (spacing is handled by the column's [Arrangement.spacedBy] instead). */
val LocalSettingsGroupSpacing = staticCompositionLocalOf { false }

/** Settings page content container: uniform 24dp spacing between groups (and any other elements),
 *  with zero trailing after the last element. */
@Composable
fun SettingsGroupColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalSettingsGroupSpacing provides true) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 24.dp,
    items: List<@Composable () -> Unit>
) {
    val effectiveBottom = if (LocalSettingsGroupSpacing.current) 0.dp else bottomPadding
    Column(modifier = modifier.fillMaxWidth().padding(bottom = effectiveBottom)) {
        // 分组标题：更小更轻的字体，类似 iOS section header
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        // 卡片包裹：圆角 + 微妙的 tonal elevation，营造 iOS 设置卡片的层次感
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, item ->
                    item()
                    if (index != items.lastIndex) {
                        // 更细更柔和的分割线，缩进以贴合卡片内边距
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Shared body for a [SettingsGroup] item with a primary-tinted leading icon and content column. */
@Composable
fun SettingsIconContent(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // 图标圆角背景容器（iOS 设置风格）：用 primaryContainer 作底，图标用 onPrimaryContainer
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
}

@Composable
fun SettingsItem(
    modifier: Modifier = Modifier,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    leadingSpacing: Dp = 16.dp,
    endPadding: Dp = 16.dp,
) {
    val verticalPadding = if (supportingContent == null) 14.dp else 16.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 20.dp,
                end = endPadding,
                top = verticalPadding,
                bottom = verticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                leadingContent()
            }
            Spacer(modifier = Modifier.width(leadingSpacing))
        }
        Column(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
                headlineContent()
            }
            if (supportingContent != null) {
                Spacer(modifier = Modifier.height(3.dp))
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    supportingContent()
                }
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(16.dp))
            trailingContent()
        }
    }
}

/**
 * Canonical centered add action for settings groups.
 *
 * Keep this aligned with the settings row contract instead of recreating its
 * dimensions in individual pages.
 */
@Composable
fun SettingsAddItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private data class SettingsCategory(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector? = null,
    @DrawableRes val iconRes: Int? = null,
    val requiresMembership: Boolean = false,
)

/** A searchable settings entry: [title] is the display name, [route] is the category key used by
 *  [SettingsScreen] to navigate, and [keywords] are extra terms (description, group name) used for
 *  matching the user's query. */
data class SearchableSettingItem(
    val title: String,
    val route: String,
    val keywords: List<String>,
    val requiresMembership: Boolean = false,
)

private data class SettingsGroupData(
    val titleRes: Int? = null,
    val items: List<SettingsCategory>
)

private val settingsGroups = listOf(
    // Group 1 — 外观与语言
    SettingsGroupData(titleRes = R.string.settings_group_appearance_language, items = listOf(
        SettingsCategory("appearance", R.string.settings_appearance, R.string.settings_appearance_desc, Icons.Default.Palette),
        SettingsCategory("language", R.string.language_title, R.string.language_desc, Icons.Default.Translate),
    )),
    // Group 1.5 — 语音（统一入口，所有用户可见）
    SettingsGroupData(titleRes = R.string.settings_group_voice, items = listOf(
        SettingsCategory("voice_service", R.string.settings_voice_service, R.string.settings_voice_service_desc, Icons.Default.Mic),
    )),
    // Group 2 — 模型与生成（转录作为媒体模型能力归入本组）
    SettingsGroupData(titleRes = R.string.settings_group_models_generation, items = listOf(
        SettingsCategory("provider", R.string.settings_provider, R.string.settings_provider_desc, Icons.Default.Cloud),
        SettingsCategory("models", R.string.settings_models, R.string.settings_models_desc, Icons.Default.Chat),
        SettingsCategory("model_plaza", R.string.settings_model_plaza, R.string.settings_model_plaza_desc, Icons.Default.AutoAwesome),
        SettingsCategory("generation", R.string.settings_generation, R.string.settings_generation_desc, Icons.Default.Tune),
        SettingsCategory("transcription", R.string.settings_transcription, R.string.settings_transcription_desc, Icons.Default.ImageSearch),
        SettingsCategory("feature_model_config", R.string.settings_feature_model_config, R.string.settings_feature_model_config_desc, Icons.Default.Category, requiresMembership = true),
    )),
    // Group 3 — 回复与内容（通知回复归入回复类）
    SettingsGroupData(titleRes = R.string.settings_group_responses, items = listOf(
        SettingsCategory("prompts", R.string.settings_prompts, R.string.settings_prompts_desc, Icons.Default.Psychology),
        SettingsCategory("context", R.string.context_title, R.string.context_desc, Icons.Default.Memory),
        SettingsCategory("routing", R.string.settings_complexity_routing, R.string.settings_complexity_routing_desc, Icons.Default.Route),
        SettingsCategory("titlegen", R.string.settings_title_gen, R.string.settings_title_gen_desc, Icons.Default.Edit),
        SettingsCategory("notification_reply", R.string.settings_notification_reply, R.string.settings_notification_reply_desc, Icons.Default.Notifications),
    )),
    // Group 4 — 能力与执行（本机执行环境归拢）
    // runtime_status 已合并进 sandbox 页面：三个运行时引擎（Python/Node/FFmpeg）
    // 现在都跑在 proot 沙箱里，沙箱页面顶部展示它们的就绪状态，不再单列菜单入口。
    SettingsGroupData(titleRes = R.string.settings_group_capabilities, items = listOf(
        SettingsCategory("adb_shell", R.string.settings_adb_shell, R.string.settings_adb_shell_desc, Icons.Default.Terminal),
        SettingsCategory("sandbox", R.string.settings_sandbox, R.string.settings_sandbox_desc, Icons.Default.Security),
        SettingsCategory("shell", R.string.shell_title, R.string.shell_desc, Icons.Default.Code),
    )),
    // Group 5 — 接入与自动化（连接/插件/代理/任务归位，proxy 归入网络接入）
    SettingsGroupData(titleRes = R.string.settings_group_access_automation, items = listOf(
        SettingsCategory("im_gateway", R.string.settings_im_gateway, R.string.settings_im_gateway_desc, Icons.Default.Message),
        SettingsCategory(
            "mcp",
            R.string.mcp_title,
            R.string.mcp_desc,
            iconRes = R.drawable.ic_mcp,
        ),
        SettingsCategory("plugins", R.string.settings_plugins, R.string.settings_plugins_desc, Icons.Default.Extension),
        SettingsCategory("market", R.string.settings_market, R.string.settings_market_desc, Icons.Default.Store),
        SettingsCategory("search", R.string.search_title, R.string.search_desc, Icons.Default.Search),
        SettingsCategory("proxy", R.string.settings_proxy, R.string.settings_proxy_desc, Icons.Default.Lan),
        SettingsCategory("automation", R.string.settings_automation, R.string.settings_automation_desc, Icons.Default.Repeat),
        SettingsCategory("cron", R.string.settings_cron, R.string.settings_cron_desc, Icons.Default.Schedule),
    )),
    // Group 6 — 数据与系统
    SettingsGroupData(titleRes = R.string.settings_group_data_system, items = listOf(
        SettingsCategory("memory", R.string.settings_memory, R.string.settings_memory_desc, Icons.Default.Description),
        SettingsCategory("datacontrol", R.string.settings_data_control, R.string.settings_data_control_desc, Icons.Default.Storage),
        SettingsCategory("logs", R.string.settings_logs, R.string.settings_logs_desc, Icons.Default.ReceiptLong),
        SettingsCategory("about", R.string.settings_about, R.string.settings_about_desc, Icons.Default.Info),
        SettingsCategory("statistics", R.string.settings_statistics, R.string.settings_statistics_desc, Icons.Default.BarChart),
        SettingsCategory("membership", R.string.settings_membership, R.string.settings_membership_desc, Icons.Default.WorkspacePremium),
    )),
    // Group 7 — 系统（权限与诊断，所有用户可见）
    SettingsGroupData(titleRes = R.string.settings_group_system, items = listOf(
        SettingsCategory("permission", R.string.settings_permission, R.string.settings_permission_desc, Icons.Default.Lock),
        SettingsCategory("system_status", R.string.settings_system_status, R.string.settings_system_status_desc, Icons.Default.Assessment),
    )),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isSyncingModels by viewModel.isSyncingModels.collectAsState()
    val fetchingModelsMessage = stringResource(R.string.snackbar_fetching_models)

    // Membership status: non-members see membership-gated entries (plugins/market)
    // grayed out and non-clickable.
    val membershipStatus by viewModel.membership.status.collectAsState()
    val hasMembership = membershipStatus.isActive

    // Value previews: read current values so each row can show what's configured.
    val selectedModel by viewModel.settings.selectedModel.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val appLanguage by viewModel.settings.appLanguage.collectAsState()
    val themeMode by viewModel.settings.themeMode.collectAsState()
    val proxyEnabled by viewModel.settings.proxyEnabled.collectAsState()
    val proxyHost by viewModel.settings.proxyHost.collectAsState()
    val proxyPort by viewModel.settings.proxyPort.collectAsState()
    val maxContextWindow by viewModel.settings.maxContextWindow.collectAsState()

    /** Current-value preview for a settings row, or null when the row has no concise value. */
    @Composable
    fun settingsPreview(key: String): String? = when (key) {
        "appearance" -> when (themeMode) {
            "LIGHT" -> stringResource(R.string.theme_mode_light)
            "DARK" -> stringResource(R.string.theme_mode_dark)
            "AMOLED" -> stringResource(R.string.theme_mode_amoled)
            else -> stringResource(R.string.theme_mode_follow_device)
        }
        "language" -> when (appLanguage) {
            "zh" -> stringResource(R.string.language_native_zh)
            "en" -> "English"
            else -> stringResource(R.string.language_system_default)
        }
        "provider" -> ModelId.parse(selectedModel).providerName
            .takeUnless { it == Constants.PROVIDER_UNKNOWN || it.isBlank() }
        "models" -> modelAliases[selectedModel] ?: ModelId.parse(selectedModel).apiModelName
        "context" -> "${maxContextWindow / 1000}k"
        "proxy" -> if (proxyEnabled) "$proxyHost:$proxyPort" else null
        "membership" -> when (membershipStatus.tier) {
            MembershipTier.Premium -> stringResource(R.string.membership_status_premium)
            MembershipTier.Pro -> stringResource(R.string.membership_status_pro)
            else -> stringResource(R.string.membership_status_free)
        }
        else -> null
    }

    LaunchedEffect(isSyncingModels) {
        if (isSyncingModels) {
            viewModel.emitSnackbar(fetchingModelsMessage)
        }
    }

    BackHandler {
        if (selectedCategory != null) {
            selectedCategory = null
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GuardedAnimatedContent(
            targetState = selectedCategory,
            forward = selectedCategory != null
        ) { category ->
            when (category) {
                "provider" -> SettingsProviderPage(viewModel, onBack = { selectedCategory = null })
                "model_plaza" -> SettingsModelPlazaPage(viewModel, onBack = { selectedCategory = null })
                "prompts" -> SettingsPromptsPage(viewModel, onBack = { selectedCategory = null })
                "models" -> SettingsModelsPage(viewModel, onBack = { selectedCategory = null })
                "generation" -> SettingsGenerationPage(viewModel, onBack = { selectedCategory = null })
                "context" -> SettingsContextPage(viewModel, onBack = { selectedCategory = null })
                "routing" -> SettingsRoutingPage(viewModel, onBack = { selectedCategory = null })

                "shell" -> SettingsShellPage(viewModel, onBack = { selectedCategory = null })

                "mcp" -> SettingsMcpPage(viewModel, onBack = { selectedCategory = null })

                "market" -> SettingsMarketPage(
                    viewModel,
                    onBack = { selectedCategory = null },
                    onOpenOnlineMarket = { selectedCategory = "online_market" },
                )

                "membership" -> SettingsMembershipPage(viewModel, onBack = { selectedCategory = null }, onNavigateToAbout = { selectedCategory = "about" })
                "online_market" -> SettingsPluginMarketPage(
                    viewModel,
                    onBack = { selectedCategory = null },
                    onOpenSources = { selectedCategory = "market_sources" },
                )

                "automation" -> SettingsAutomationPage(
                    viewModel,
                    onBack = { selectedCategory = null },
                    onOpenWorkflow = { selectedCategory = "workflow" },
                )
                "workflow" -> SettingsWorkflowPage(viewModel, onBack = { selectedCategory = null })
                "plugins" -> SettingsPluginsListPage(viewModel, onBack = { selectedCategory = null })


                "adb_shell" -> SettingsAdbPage(viewModel, onBack = { selectedCategory = null })
                "sandbox" -> {
                    val sandboxMgr = viewModel.sandboxManager
                    if (sandboxMgr != null) {
                        val sandboxSharedStorageEnabled by viewModel.settings.sandboxSharedStorageEnabled.collectAsState()
                        SettingsSandboxPage(
                            sandboxManager = sandboxMgr,
                            onBack = { selectedCategory = null },
                            sharedStorageEnabled = sandboxSharedStorageEnabled,
                            onSharedStorageEnabledChange = viewModel.settings::setSandboxSharedStorageEnabled,
                        )
                    } else {
                        // Sandbox flavor not available on this build: fall back to the shell page
                        // so the user still lands somewhere sensible instead of a blank screen.
                        SettingsShellPage(viewModel, onBack = { selectedCategory = null })
                    }
                }
                "im_gateway" -> SettingsImGatewayPage(viewModel, onBack = { selectedCategory = null })
                "notification_reply" -> NotificationReplySettingsPage(
                    viewModel,
                    onBack = { selectedCategory = null },
                    onNavigateToMembership = { selectedCategory = "membership" },
                )
                "cron" -> CronSettingsPage(viewModel, onBack = { selectedCategory = null })

                "proxy" -> SettingsProxyPage(viewModel, onBack = { selectedCategory = null })

                "language" -> SettingsLanguagePage(viewModel, onBack = { selectedCategory = null })
                "titlegen" -> SettingsTitleGenPage(viewModel, onBack = { selectedCategory = null })
                "transcription" -> SettingsTranscriptionPage(viewModel, onBack = { selectedCategory = null })
                "search" -> SettingsSearchPage(viewModel, onBack = { selectedCategory = null })
                "memory" -> SettingsMemoryPage(viewModel, onBack = { selectedCategory = null })
                "statistics" -> SettingsStatisticsPage(viewModel, onBack = { selectedCategory = null })
                "datacontrol" -> SettingsDataControlPage(viewModel, onBack = { selectedCategory = null })
                "appearance" -> SettingsAppearancePage(viewModel, onBack = { selectedCategory = null })
                "about" -> SettingsAboutPage(viewModel, onBack = { selectedCategory = null })
                "logs" -> SettingsLogsPage(viewModel, onBack = { selectedCategory = null })

                "voice_service" -> SettingsVoiceServicePage(
                    onBack = { selectedCategory = null },
                    onOpenTts = { selectedCategory = "generation" },
                    onOpenStt = { selectedCategory = "generation" },
                    onOpenVoiceSettings = { selectedCategory = "generation" },
                )
                "feature_model_config" -> SettingsFeatureModelConfigPage(viewModel, onBack = { selectedCategory = null })
                "permission" -> SettingsPermissionPage(onBack = { selectedCategory = null })
                "system_status" -> SettingsSystemStatusPage(viewModel, onBack = { selectedCategory = null })
                else -> {
                    // Build searchable entries from every group so the search field can filter them.
                    val searchableItems = settingsGroups.flatMap { group ->
                        group.items.map { cat ->
                            SearchableSettingItem(
                                title = stringResource(cat.titleRes),
                                route = cat.key,
                                keywords = buildList {
                                    add(stringResource(cat.descriptionRes))
                                    if (group.titleRes != null) add(stringResource(group.titleRes))
                                },
                                requiresMembership = cat.requiresMembership,
                            )
                        }
                    }
                    val trimmedQuery = searchQuery.trim()
                    val matchedItems = if (trimmedQuery.isEmpty()) emptyList()
                    else searchableItems.filter { item ->
                        item.title.contains(trimmedQuery, ignoreCase = true) ||
                            item.keywords.any { it.contains(trimmedQuery, ignoreCase = true) }
                    }
                    CollapsingSettingsLazyScaffold(
                        title = stringResource(R.string.settings_title),
                        onBack = onBack,
                        listState = listState,
                        header = {
                            SettingsSearchField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                            )
                        }
                    ) {
                        if (searchQuery.isNotBlank()) {
                            if (matchedItems.isEmpty()) {
                                item {
                                    Text(
                                        text = stringResource(R.string.settings_search_no_results),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                                    )
                                }
                            } else {
                                items(matchedItems) { item ->
                                    val itemEnabled = !item.requiresMembership || hasMembership
                                    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { if (item.requiresMembership && !hasMembership) { selectedCategory = "membership"; searchQuery = "" } else { selectedCategory = item.route; searchQuery = "" } }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = if (itemEnabled) MaterialTheme.colorScheme.primary else disabledColor,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                                color = if (itemEnabled) MaterialTheme.colorScheme.onSurface else disabledColor,
                                            )
                                        }
                                        if (item.requiresMembership && !hasMembership) {
                                            Icon(
                                                Icons.Default.Lock,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        } else {
                                            Icon(
                                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = if (itemEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else disabledColor
                                            )
                                        }
                                    }
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        } else {
                            items(settingsGroups.size) { groupIndex ->
                                val group = settingsGroups[groupIndex]
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (group.titleRes != null) {
                                        Text(
                                            text = stringResource(group.titleRes),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            group.items.forEachIndexed { index, cat ->
                                                val isLastItem = index == group.items.lastIndex
                                                val itemEnabled = !cat.requiresMembership || hasMembership
                                                val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                                val preview = settingsPreview(cat.key)
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { if (cat.requiresMembership && !hasMembership) selectedCategory = "membership" else selectedCategory = cat.key }
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    if (cat.iconRes != null) {
                                                        Icon(
                                                            painter = painterResource(cat.iconRes),
                                                            contentDescription = null,
                                                            tint = if (itemEnabled) MaterialTheme.colorScheme.primary else disabledColor,
                                                            modifier = Modifier.size(24.dp),
                                                        )
                                                    } else {
                                                        Icon(
                                                            imageVector = checkNotNull(cat.icon),
                                                            contentDescription = null,
                                                            tint = if (itemEnabled) MaterialTheme.colorScheme.primary else disabledColor,
                                                            modifier = Modifier.size(24.dp),
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(16.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = stringResource(cat.titleRes),
                                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                                            color = if (itemEnabled) MaterialTheme.colorScheme.onSurface else disabledColor,
                                                        )
                                                        Spacer(modifier = Modifier.height(3.dp))
                                                        Text(
                                                            text = stringResource(cat.descriptionRes),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = if (itemEnabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor
                                                        )
                                                    }
                                                    if (preview != null) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = preview,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = if (itemEnabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.widthIn(max = 120.dp),
                                                        )
                                                    }
                                                    if (cat.requiresMembership && !hasMembership) {
                                                        Icon(
                                                            Icons.Default.Lock,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                        )
                                                    } else {
                                                        Icon(
                                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                            contentDescription = null,
                                                            tint = if (itemEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else disabledColor
                                                        )
                                                    }
                                                }
                                                if (!isLastItem) {
                                                    HorizontalDivider(
                                                        thickness = 0.5.dp,
                                                        color = MaterialTheme.colorScheme.outlineVariant,
                                                        modifier = Modifier.padding(horizontal = 16.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (groupIndex < settingsGroups.size - 1) {
                                    Spacer(modifier = Modifier.height(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}

/** Search field rendered at the top of the settings list. Filters [settingsGroups] entries by
 *  title, description, and group name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )
}
