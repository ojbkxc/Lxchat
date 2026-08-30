package com.lxseek.chat.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.ChatViewModel

/**
 * Marketplace page: a unified view of built-in skills and plugins registered with
 * [com.lxseek.chat.plugin.PluginHost]. Each row shows the item name, description, a
 * membership badge (star) when the item's `requiresMembership` flag is set, and an
 * enable/disable switch. A [TabRow] filters the list by All / Skills / Plugins.
 *
 * Skills are sourced from `viewModel.pluginHost.skillHost.skills` and plugins from
 * `viewModel.pluginHost.plugins` — both StateFlows, so the UI reacts to runtime
 * registration / toggle changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMarketPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenOnlineMarket: () -> Unit,
) {
    val skills by viewModel.pluginHost.skillHost.skills.collectAsState()
    val plugins by viewModel.pluginHost.plugins.collectAsState()
    val membershipStatus by viewModel.membership.status.collectAsState()
    val hasMembership = membershipStatus.isActive

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val tabs = listOf(
        stringResource(R.string.settings_market_tab_all),
        stringResource(R.string.settings_market_tab_skills),
        stringResource(R.string.settings_market_tab_plugins),
        stringResource(R.string.settings_online_market),
    )

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_market)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            // Build the visible item set based on the selected tab.
            val showOnline = selectedTab == 3
            val showSkills = selectedTab == 0 || selectedTab == 1
            val showPlugins = selectedTab == 0 || selectedTab == 2

            if (showOnline) {
                // Online Tab: prompt the user to open the online plugin market.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.settings_online_market_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onOpenOnlineMarket) {
                        Text(stringResource(R.string.settings_online_market))
                    }
                }
            } else {
                // Search bar: filter skills/plugins by name or description.
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.market_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )

                // Apply tab + query filters to both lists.
                val query = searchQuery.trim()
                val filteredSkills = if (showSkills) {
                    if (query.isEmpty()) skills else skills.filter { info ->
                        info.skill.name.contains(query, ignoreCase = true) ||
                            info.skill.description?.contains(query, ignoreCase = true) == true
                    }
                } else {
                    emptyList()
                }
                val filteredPlugins = if (showPlugins) {
                    if (query.isEmpty()) plugins else plugins.filter { info ->
                        info.manifest.name.contains(query, ignoreCase = true) ||
                            info.manifest.description?.contains(query, ignoreCase = true) == true
                    }
                } else {
                    emptyList()
                }

                val isEmpty = filteredSkills.isEmpty() && filteredPlugins.isEmpty()
                // Distinguish "no data" from "no search matches".
                val noMatches = query.isNotEmpty() && isEmpty

                if (isEmpty) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (noMatches) R.string.market_search_no_results
                                else R.string.settings_market_empty
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (filteredSkills.isNotEmpty()) {
                            item(key = "section_skills") {
                                SectionHeader(stringResource(R.string.settings_market_section_skills))
                            }
                            items(filteredSkills, key = { "skill_${it.skill.name}" }) { skillInfo ->
                                val skill = skillInfo.skill
                                MarketRow(
                                    name = skill.name,
                                    description = skill.description,
                                    requiresMembership = skill.requiresMembership,
                                    hasMembership = hasMembership,
                                    enabled = skillInfo.enabled,
                                    onToggle = { viewModel.pluginHost.skillHost.setEnabled(skill.name, it) },
                                )
                            }
                        }
                        if (filteredPlugins.isNotEmpty()) {
                            item(key = "section_plugins") {
                                SectionHeader(stringResource(R.string.settings_market_section_plugins))
                            }
                            items(filteredPlugins, key = { "plugin_${it.manifest.id}" }) { pluginInfo ->
                                val manifest = pluginInfo.manifest
                                MarketRow(
                                    name = manifest.name,
                                    description = manifest.description,
                                    requiresMembership = manifest.requiresMembership,
                                    hasMembership = hasMembership,
                                    enabled = pluginInfo.enabled,
                                    onToggle = { viewModel.pluginHost.setEnabled(manifest.id, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Sticky section header used inside the market list to separate Skills from Plugins. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** A single market row: name + optional membership star + description + enable switch. */
@Composable
private fun MarketRow(
    name: String,
    description: String?,
    requiresMembership: Boolean,
    hasMembership: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val switchEnabled = !requiresMembership || hasMembership
    val nameColor = if (switchEnabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = nameColor,
                )
                if (requiresMembership) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Membership required",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
            if (!description.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            enabled = switchEnabled,
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}