package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.data.SettingsManager
import com.lxseek.chat.util.noOpBringIntoView
import com.lxseek.chat.viewmodel.ChatViewModel

/**
 * Encrypted-DNS (DoH) settings: protection mode (off / selective / all), the primary and fallback
 * DoH upstreams, and the protected-host whitelist used in selective mode.
 *
 * The feature is harmless by default: mode "off" leaves name resolution exactly on the system
 * resolver, and even when enabled any DoH failure falls back to it (fail-open + circuit breaker
 * live in [com.lxseek.chat.api.EncryptedDns]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDnsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val mode by viewModel.settings.dnsMode.collectAsState()
    val primaryUrl by viewModel.settings.dnsPrimaryUrl.collectAsState()
    val fallbackUrl by viewModel.settings.dnsFallbackUrl.collectAsState()
    val whitelist by viewModel.settings.dnsWhitelist.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    val modes = listOf(
        SettingsManager.DNS_MODE_OFF to stringResource(R.string.dns_mode_off),
        SettingsManager.DNS_MODE_SELECTIVE to stringResource(R.string.dns_mode_selective),
        SettingsManager.DNS_MODE_ALL to stringResource(R.string.dns_mode_all),
    )

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_dns),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("dns.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.settings_dns),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.settings_dns)) },
                            supportingContent = { Text(stringResource(R.string.settings_dns_desc)) },
                            leadingContent = { Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                if (mode != SettingsManager.DNS_MODE_OFF) {
                                    Text(
                                        stringResource(
                                            when (mode) {
                                                SettingsManager.DNS_MODE_ALL -> R.string.dns_mode_all
                                                else -> R.string.dns_mode_selective
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            modifier = Modifier.clickable { }
                        )
                    }
                    add {
                        SettingsIconContent(icon = Icons.Default.Dns) {
                            Text(
                                stringResource(R.string.dns_mode),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(10.dp))
                            PillTabSwitcher(
                                tabs = modes.map { it.second },
                                selectedIndex = modes.indexOfFirst { it.first == mode }.coerceAtLeast(0),
                                onSelect = { viewModel.settings.setDnsMode(modes[it].first) },
                                allowLabelOverflow = true,
                            )
                        }
                    }
                    if (mode != SettingsManager.DNS_MODE_OFF) {
                        add {
                            SettingsIconContent(icon = Icons.Default.Dns) {
                                DnsLabeledField(
                                    label = stringResource(R.string.dns_primary_url),
                                    value = primaryUrl,
                                    onChange = { viewModel.settings.setDnsPrimaryUrl(it) },
                                    placeholder = SettingsManager.DEFAULT_DNS_PRIMARY,
                                )
                                Spacer(Modifier.height(16.dp))
                                DnsLabeledField(
                                    label = stringResource(R.string.dns_fallback_url),
                                    value = fallbackUrl,
                                    onChange = { viewModel.settings.setDnsFallbackUrl(it) },
                                    placeholder = SettingsManager.DEFAULT_DNS_FALLBACK,
                                )
                            }
                        }
                    }
                    if (mode == SettingsManager.DNS_MODE_SELECTIVE) {
                        add {
                            SettingsIconContent(icon = Icons.Default.Dns) {
                                val joined = remember(whitelist) {
                                    val normalized = whitelist.filter { it.isNotBlank() }
                                    if (normalized.isEmpty()) "" else normalized.sorted().joinToString("\n")
                                }
                                var draft by remember { mutableStateOf(joined) }
                                LaunchedEffect(joined) { if (joined != draft) draft = joined }
                                Text(
                                    stringResource(R.string.dns_whitelist),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.dns_whitelist_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                    OutlinedTextField(
                                        value = draft,
                                        onValueChange = {
                                            draft = it
                                            viewModel.settings.setDnsWhitelist(
                                                it.split('\n').map(String::trim).filter(String::isNotEmpty).toSet()
                                            )
                                        },
                                        singleLine = false,
                                        minLines = 4,
                                        shape = RoundedCornerShape(12.dp),
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

/** A labeled outlined field for composing inside a [SettingsIconContent], mirroring the proxy page. */
@Composable
private fun DnsLabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    singleLine: Boolean = true,
) {
    var draft by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != draft) draft = value }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; onChange(it) },
                placeholder = placeholder?.let { ph -> { Text(ph, style = MaterialTheme.typography.bodyMedium) } },
                singleLine = singleLine,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}