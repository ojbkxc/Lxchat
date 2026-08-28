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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.lxseek.chat.R
import com.lxseek.chat.ui.settings.datacontrol.SettingsDataControlPage
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
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                item()
                if (index != items.lastIndex) {
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

/** Shared body for a [SettingsGroup] item with a primary-tinted leading icon and content column. */
@Composable
fun SettingsIconContent(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
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
    val verticalPadding = if (supportingContent == null) 12.dp else 16.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
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
    SettingsGroupData(titleRes = R.string.settings_group_services, items = listOf(
        SettingsCategory("provider", R.string.settings_provider, R.string.settings_provider_desc, Icons.Default.Cloud),
        SettingsCategory("models", R.string.settings_models, R.string.settings_models_desc, Icons.Default.Chat),
        SettingsCategory("model_plaza", R.string.settings_model_plaza, R.string.settings_model_plaza_desc, Icons.Default.AutoAwesome),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_responses, items = listOf(
        SettingsCategory("prompts", R.string.settings_prompts, R.string.settings_prompts_desc, Icons.Default.Psychology),
        SettingsCategory("generation", R.string.settings_generation, R.string.settings_generation_desc, Icons.Default.Tune),
        SettingsCategory("context", R.string.context_title, R.string.context_desc, Icons.Default.Memory),
        SettingsCategory("routing", R.string.settings_complexity_routing, R.string.settings_complexity_routing_desc, Icons.Default.Route),
        SettingsCategory("titlegen", R.string.settings_title_gen, R.string.settings_title_gen_desc, Icons.Default.Edit),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_multimodal, items = listOf(
        SettingsCategory("transcription", R.string.settings_transcription, R.string.settings_transcription_desc, Icons.Default.ImageSearch),
        SettingsCategory("imagegen", R.string.settings_image_gen, R.string.settings_image_gen_desc, Icons.Default.AddPhotoAlternate),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_tools, items = listOf(
        SettingsCategory("websearch", R.string.settings_web_search, R.string.settings_web_search_desc, Icons.Default.Language),
        SettingsCategory("search", R.string.search_title, R.string.search_desc, Icons.Default.Search),
        SettingsCategory("shell", R.string.shell_title, R.string.shell_desc, Icons.Default.Terminal),
        SettingsCategory("adb_shell", R.string.settings_adb_shell, R.string.settings_adb_shell_desc, Icons.Default.Terminal),
        SettingsCategory(
            "mcp",
            R.string.mcp_title,
            R.string.mcp_desc,
            iconRes = R.drawable.ic_mcp,
        ),
        SettingsCategory("automation", R.string.settings_automation, R.string.settings_automation_desc, Icons.Default.Repeat),
        SettingsCategory("workflow", R.string.settings_workflow, R.string.settings_workflow_desc, Icons.Default.AccountTree),
        SettingsCategory("device_control", R.string.settings_device_control, R.string.settings_device_control_desc, Icons.Default.Android),
        SettingsCategory("runtime_status", R.string.settings_runtime_status, R.string.settings_runtime_status_desc, Icons.Default.Speed),
        SettingsCategory("pet_overlay", R.string.settings_pet_overlay, R.string.settings_pet_overlay_desc, Icons.Default.Android),
        SettingsCategory("im_gateway", R.string.settings_im_gateway, R.string.settings_im_gateway_desc, Icons.Default.Message),
        SettingsCategory("notification_reply", R.string.settings_notification_reply, R.string.settings_notification_reply_desc, Icons.Default.Notifications),
        SettingsCategory("cron", R.string.settings_cron, R.string.settings_cron_desc, Icons.Default.Schedule),
        SettingsCategory("trigger", R.string.settings_trigger, R.string.settings_trigger_desc, Icons.Default.Bolt),
        SettingsCategory("sms_command", R.string.settings_sms_command, R.string.settings_sms_command_desc, Icons.Default.Sms),
        SettingsCategory("reply_channel", R.string.settings_reply_channel, R.string.settings_reply_channel_desc, Icons.Default.Send),
        SettingsCategory("plugins", R.string.settings_plugins, R.string.settings_plugins_desc, Icons.Default.Extension),
        SettingsCategory("market", R.string.settings_market, R.string.settings_market_desc, Icons.Default.Store),

        SettingsCategory("membership", R.string.settings_membership, R.string.settings_membership_desc, Icons.Default.WorkspacePremium),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_network, items = listOf(
        SettingsCategory("proxy", R.string.settings_proxy, R.string.settings_proxy_desc, Icons.Default.Lan),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_memory_data, items = listOf(
        SettingsCategory("memory", R.string.settings_memory, R.string.settings_memory_desc, Icons.Default.Description),
        SettingsCategory("datacontrol", R.string.settings_data_control, R.string.settings_data_control_desc, Icons.Default.Storage),
        SettingsCategory("logs", R.string.settings_logs, R.string.settings_logs_desc, Icons.Default.ReceiptLong),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_appearance_language, items = listOf(
        SettingsCategory("appearance", R.string.settings_appearance, R.string.settings_appearance_desc, Icons.Default.Palette),
        SettingsCategory("language", R.string.language_title, R.string.language_desc, Icons.Default.Translate),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_insights, items = listOf(
        SettingsCategory("statistics", R.string.settings_statistics, R.string.settings_statistics_desc, Icons.Default.BarChart),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_about, items = listOf(
        SettingsCategory("about", R.string.settings_about, R.string.settings_about_desc, Icons.Default.Info),
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
                "websearch" -> SettingsWebSearchPage(viewModel, onBack = { selectedCategory = null })
                "imagegen" -> SettingsImageGenPage(viewModel, onBack = { selectedCategory = null })
                "shell" -> SettingsShellPage(viewModel, onBack = { selectedCategory = null })
                "adb_shell" -> SettingsAdbPage(viewModel, onBack = { selectedCategory = null })
                "mcp" -> SettingsMcpPage(viewModel, onBack = { selectedCategory = null })
                "plugins" -> SettingsPluginsListPage(viewModel, onBack = { selectedCategory = null })
                "market" -> SettingsMarketPage(
                    viewModel,
                    onBack = { selectedCategory = null },
                    onOpenOnlineMarket = { selectedCategory = "online_market" },
                )
                "membership" -> SettingsMembershipPage(viewModel, onBack = { selectedCategory = null })
                "online_market" -> SettingsPluginMarketPage(
                    viewModel,
                    onBack = { selectedCategory = null },
                    onOpenSources = { selectedCategory = "market_sources" },
                )
                "market_sources" -> SettingsMarketSourcesPage(
                    viewModel,
                    onBack = { selectedCategory = "online_market" },
                )
                "automation" -> SettingsAutomationPage(
                    viewModel,
                    onBack = { selectedCategory = null },
                    onOpenWorkflow = { selectedCategory = "workflow" },
                )
                "workflow" -> SettingsWorkflowPage(viewModel, onBack = { selectedCategory = null })
                "device_control" -> SettingsDeviceControlPage(viewModel, onBack = { selectedCategory = null })
                "pet_overlay" -> SettingsPetOverlayPage(viewModel, onBack = { selectedCategory = null })
                "runtime_status" -> SettingsRuntimeStatusPage(viewModel, onBack = { selectedCategory = null })
                "im_gateway" -> SettingsImGatewayPage(viewModel, onBack = { selectedCategory = null })
                "notification_reply" -> NotificationReplySettingsPage(viewModel, onBack = { selectedCategory = null })
                "cron" -> CronSettingsPage(viewModel, onBack = { selectedCategory = null })
                "trigger" -> TriggerSettingsPage(viewModel, onBack = { selectedCategory = null })
                "sms_command" -> SmsCommandSettingsPage(viewModel, onBack = { selectedCategory = null })
                "reply_channel" -> ReplyChannelSettingsPage(onBack = { selectedCategory = null })
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
                                            .clickable(enabled = itemEnabled) { selectedCategory = item.route; searchQuery = "" }
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
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = if (itemEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else disabledColor
                                        )
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
                                    group.items.forEachIndexed { index, cat ->
                                        val isLastItem = index == group.items.lastIndex
                                        val itemEnabled = !cat.requiresMembership || hasMembership
                                        val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = itemEnabled) { selectedCategory = cat.key }
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
                                            Icon(
                                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = if (itemEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else disabledColor
                                            )
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
