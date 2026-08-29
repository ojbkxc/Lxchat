package com.lxseek.chat.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.plugin.market.MarketInstallation
import com.lxseek.chat.plugin.market.MarketPluginKind
import com.lxseek.chat.plugin.market.MarketPluginMeta
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Online plugin market: browse the merged catalog of all enabled market sources and
 * install / uninstall / toggle market plugins (Skill / MCP / ToolPkg).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPluginMarketPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val market = viewModel.pluginMarket
    val sources by market.sources.collectAsState()
    val catalog by market.catalog.collectAsState()
    val installations by market.installations.collectAsState()
    val refreshing by market.refreshing.collectAsState()
    val refreshError by market.lastRefreshError.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installingId by remember { mutableStateOf<String?>(null) }
    var uninstallTarget by remember { mutableStateOf<String?>(null) }
    var editingFor by remember { mutableStateOf<MarketInstallation?>(null) }
    var urlDraft by remember { mutableStateOf("") }
    var headerDrafts by remember { mutableStateOf<List<MarketHeaderDraft>>(emptyList()) }

    // ── Sources sub-page navigation (merged from SettingsMarketSourcesPage) ──
    var showSources by remember { mutableStateOf(false) }

    // ── Category tab filter: 0=All, 1=Skill, 2=MCP, 3=Runtime, 4=ToolPkg ──
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabKinds = listOf<MarketPluginKind?>(null, MarketPluginKind.SKILL, MarketPluginKind.MCP, MarketPluginKind.RUNTIME, MarketPluginKind.TOOLPKG)
    val tabLabels = listOf(
        stringResource(R.string.settings_market_tab_all),
        stringResource(R.string.settings_market_tab_skills),
        stringResource(R.string.market_kind_mcp),
        stringResource(R.string.market_kind_runtime),
        stringResource(R.string.market_kind_toolpkg),
    )
    val filterKind = tabKinds[selectedTab]
    val filteredInstallations = remember(installations, filterKind) {
        if (filterKind == null) installations else installations.filter { it.kind == filterKind }
    }
    val filteredCatalog = remember(catalog, filterKind) {
        if (filterKind == null) catalog else catalog.filter { it.kind == filterKind }
    }

    val installedIds = remember(installations) { installations.map { it.pluginId }.toSet() }

    // 首次进入自动拉取目录。
    LaunchedEffect(Unit) {
        if (sources.isEmpty() || catalog.isEmpty()) market.refreshCatalog()
    }

    BackHandler(enabled = showSources) { showSources = false }
    BackHandler(enabled = !showSources) { onBack() }

    if (showSources) {
        MarketSourcesSection(
            viewModel = viewModel,
            onBack = { showSources = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_online_market)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSources = true }) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = stringResource(R.string.market_sources),
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { market.refreshCatalog() } },
                        enabled = !refreshing,
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.market_refresh),
                            )
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
            ) {
                tabLabels.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label, maxLines = 1) },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (filteredInstallations.isNotEmpty()) {
                    item(key = "section_installed") {
                        MarketSectionHeader(stringResource(R.string.market_section_installed))
                    }
                    items(filteredInstallations, key = { "inst_${it.pluginId}" }) { inst ->
                        InstalledPluginRow(
                            installation = inst,
                            onToggle = { market.setEnabled(inst.pluginId, it) },
                            onUninstall = { uninstallTarget = inst.pluginId },
                            onEdit = if (inst.kind == MarketPluginKind.MCP) {
                                {
                                    urlDraft = inst.serverUrl
                                    headerDrafts = inst.headers.map { (name, value) ->
                                        MarketHeaderDraft(name = name, value = value)
                                    }
                                    editingFor = inst
                                }
                            } else {
                                null
                            },
                        )
                    }
                }

                item(key = "section_catalog") {
                    MarketSectionHeader(stringResource(R.string.market_section_catalog))
                }
                when {
                    refreshing && filteredCatalog.isEmpty() -> {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    sources.isEmpty() -> {
                        item(key = "no_sources") {
                            MarketEmptyState(
                                title = stringResource(R.string.market_no_sources_title),
                                hint = stringResource(R.string.market_no_sources_hint),
                                actionLabel = stringResource(R.string.market_add_source),
                                onAction = { showSources = true },
                            )
                        }
                    }
                    filteredCatalog.isEmpty() -> {
                        item(key = "empty_catalog") {
                            MarketEmptyState(
                                title = stringResource(R.string.market_catalog_empty),
                                hint = refreshError?.takeIf { !refreshing }.orEmpty(),
                            )
                        }
                    }
                    else -> {
                        items(filteredCatalog, key = { "cat_${it.id}" }) { meta ->
                            val installed = meta.id in installedIds
                            CatalogPluginRow(
                                meta = meta,
                                installed = installed,
                                installing = installingId == meta.id,
                                onInstall = {
                                    installingId = meta.id
                                    scope.launch {
                                        try {
                                            market.install(meta)
                                            viewModel.emitSnackbar(
                                                context.getString(R.string.market_installed_ok),
                                            )
                                        } catch (e: Exception) {
                                            viewModel.emitSnackbar(
                                                context.getString(R.string.market_operation_failed) +
                                                    "：${e.message}",
                                            )
                                        } finally {
                                            installingId = null
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }


    val target = uninstallTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text(stringResource(R.string.market_uninstall)) },
            text = { Text(stringResource(R.string.market_uninstall_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        uninstallTarget = null
                        scope.launch {
                            try {
                                market.uninstall(target)
                                viewModel.emitSnackbar(
                                    context.getString(R.string.market_uninstalled_ok),
                                )
                            } catch (e: Exception) {
                                viewModel.emitSnackbar(
                                    context.getString(R.string.market_operation_failed) +
                                        "：${e.message}",
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { uninstallTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    editingFor?.let { inst ->
        AlertDialog(
            onDismissRequest = { editingFor = null },
            title = { Text(stringResource(R.string.market_edit_url_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        label = { Text(stringResource(R.string.market_edit_url_label)) },
                        placeholder = { Text(stringResource(R.string.market_edit_url_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.market_headers),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (headerDrafts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.market_headers_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    headerDrafts.forEach { header ->
                        MarketHeaderRow(
                            header = header,
                            onChange = { updated ->
                                headerDrafts = headerDrafts.map {
                                    if (it.id == updated.id) updated else it
                                }
                            },
                            onDelete = {
                                headerDrafts = headerDrafts.filterNot { it.id == header.id }
                            },
                        )
                    }
                    TextButton(
                        onClick = { headerDrafts = headerDrafts + MarketHeaderDraft() },
                    ) {
                        Text(stringResource(R.string.market_add_header))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        market.updateMcpConfig(
                            inst.pluginId,
                            urlDraft,
                            buildMarketHeaders(headerDrafts),
                        )
                        editingFor = null
                        viewModel.emitSnackbar(
                            context.getString(R.string.market_config_saved),
                        )
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingFor = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Section header inside the market list. */
@Composable
private fun MarketSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Shared empty-state block with an optional primary action button. */
@Composable
fun MarketEmptyState(
    title: String,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
        if (hint.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/** A small colored badge showing the plugin kind. */
@Composable
private fun KindBadge(kind: MarketPluginKind) {
    val label = when (kind) {
        MarketPluginKind.SKILL -> stringResource(R.string.market_kind_skill)
        MarketPluginKind.MCP -> stringResource(R.string.market_kind_mcp)
        MarketPluginKind.TOOLPKG -> stringResource(R.string.market_kind_toolpkg)
        MarketPluginKind.RUNTIME -> stringResource(R.string.market_kind_runtime)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Installed market plugin row: name + kind + version + enable switch + uninstall. */
@Composable
private fun InstalledPluginRow(
    installation: MarketInstallation,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = installation.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                KindBadge(installation.kind)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "v${installation.version}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = installation.enabled, onCheckedChange = onToggle)
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.market_edit_url),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onUninstall) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.market_uninstall),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** Catalog row: name + kind + membership star + description + install / installed / installing. */
@Composable
private fun CatalogPluginRow(
    meta: MarketPluginMeta,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                KindBadge(meta.kind)
                if (meta.requiresMembership) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (!meta.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = meta.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            val author = meta.author?.let { "$it · " }.orEmpty()
            Text(
                text = "${author}v${meta.version}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        when {
            installing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
            }
            installed -> {
                Text(
                    text = stringResource(R.string.market_installed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                OutlinedButton(onClick = onInstall) {
                    Text(stringResource(R.string.market_install))
                }
            }
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** A draft request-header row inside the MCP config editor. */
private data class MarketHeaderDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val value: String = "",
    val reveal: Boolean = false,
)

/** Compact header name/value row with a reveal toggle and a delete button. */
@Composable
private fun MarketHeaderRow(
    header: MarketHeaderDraft,
    onChange: (MarketHeaderDraft) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = header.name,
            onValueChange = { onChange(header.copy(name = it)) },
            label = { Text(stringResource(R.string.market_header_name)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedTextField(
            value = header.value,
            onValueChange = { onChange(header.copy(value = it)) },
            label = { Text(stringResource(R.string.market_header_value)) },
            singleLine = true,
            visualTransformation = if (header.reveal) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { onChange(header.copy(reveal = !header.reveal)) }) {
                    Icon(
                        imageVector = if (header.reveal) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = null,
                    )
                }
            },
            modifier = Modifier.weight(1.4f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.market_remove_header),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Convert draft header rows into a config map (blank rows are dropped). */
private fun buildMarketHeaders(rows: List<MarketHeaderDraft>): Map<String, String> =
    buildMap {
        rows.filterNot { it.name.isBlank() && it.value.isBlank() }
            .forEach { header -> put(header.name.trim(), header.value.trim()) }
    }
