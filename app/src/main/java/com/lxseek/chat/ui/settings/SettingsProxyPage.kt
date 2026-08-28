package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.data.SettingsManager
import com.lxseek.chat.util.noOpBringIntoView
import com.lxseek.chat.viewmodel.ChatViewModel

/**
 * Network settings page: combines HTTP/SOCKS proxy configuration and encrypted DNS (DoH)
 * upstreams into a single page so users can manage all network-routing concerns together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProxyPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    // ── Proxy state ──
    val enabled by viewModel.settings.proxyEnabled.collectAsState()
    val type by viewModel.settings.proxyType.collectAsState()
    val host by viewModel.settings.proxyHost.collectAsState()
    val port by viewModel.settings.proxyPort.collectAsState()
    val username by viewModel.settings.proxyUsername.collectAsState()
    val password by viewModel.settings.proxyPassword.collectAsState()
    val bypass by viewModel.settings.proxyBypass.collectAsState()

    // ── DNS state ──
    val mode by viewModel.settings.dnsMode.collectAsState()
    val primaryUrl by viewModel.settings.dnsPrimaryUrl.collectAsState()
    val fallbackUrl by viewModel.settings.dnsFallbackUrl.collectAsState()
    val whitelist by viewModel.settings.dnsWhitelist.collectAsState()

    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val proxyTypes = listOf("http" to "HTTP", "https" to "HTTPS", "socks5" to "SOCKS5")
    val dnsModes = listOf(
        SettingsManager.DNS_MODE_OFF to stringResource(R.string.dns_mode_off),
        SettingsManager.DNS_MODE_SELECTIVE to stringResource(R.string.dns_mode_selective),
        SettingsManager.DNS_MODE_ALL to stringResource(R.string.dns_mode_all),
    )

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_proxy),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("proxy.md") }
    ) {
        SettingsGroupColumn {
            // ── Proxy group ──
            SettingsGroup(
                title = stringResource(R.string.settings_proxy),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.proxy_enable)) },
                            supportingContent = { Text(stringResource(R.string.proxy_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Lan, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = enabled, onCheckedChange = { viewModel.settings.setProxyEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setProxyEnabled(!enabled) }
                        )
                    }
                    if (enabled) {
                        add {
                            SettingsIconContent(icon = Icons.Default.Lan) {
                                Text(
                                    stringResource(R.string.proxy_type),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(10.dp))
                                PillTabSwitcher(
                                    tabs = proxyTypes.map { it.second },
                                    selectedIndex = proxyTypes.indexOfFirst { it.first == type }.coerceAtLeast(0),
                                    onSelect = { viewModel.settings.setProxyType(proxyTypes[it].first) },
                                    allowLabelOverflow = true,
                                )
                            }
                        }
                        add {
                            SettingsIconContent(icon = Icons.Default.Dns) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    ProxyLabeledField(
                                        label = stringResource(R.string.proxy_host),
                                        value = host,
                                        onChange = { viewModel.settings.setProxyHost(it) },
                                        placeholder = "127.0.0.1",
                                        modifier = Modifier.weight(2f)
                                    )
                                    ProxyLabeledField(
                                        label = stringResource(R.string.proxy_port),
                                        value = port,
                                        onChange = { viewModel.settings.setProxyPort(it.filter { c -> c.isDigit() }) },
                                        placeholder = "7890",
                                        keyboard = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        add {
                            SettingsIconContent(icon = Icons.Default.Person) {
                                ProxyLabeledField(
                                    label = stringResource(R.string.proxy_username),
                                    value = username,
                                    onChange = { viewModel.settings.setProxyUsername(it) }
                                )
                                Spacer(Modifier.height(16.dp))
                                ProxyLabeledField(
                                    label = stringResource(R.string.proxy_password),
                                    value = password,
                                    onChange = { viewModel.settings.setProxyPassword(it) },
                                    password = true
                                )
                            }
                        }
                    }
                }
            )
            if (enabled) {
                SettingsGroup(
                    title = stringResource(R.string.proxy_bypass),
                    items = listOf({
                        SettingsIconContent(icon = Icons.AutoMirrored.Filled.AltRoute) {
                            ProxyLabeledField(
                                label = stringResource(R.string.proxy_bypass),
                                description = stringResource(R.string.proxy_bypass_desc),
                                value = bypass,
                                onChange = { viewModel.settings.setProxyBypass(it) },
                                singleLine = false
                            )
                        }
                    })
                )
            }

            // ── DNS group ──
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
                                tabs = dnsModes.map { it.second },
                                selectedIndex = dnsModes.indexOfFirst { it.first == mode }.coerceAtLeast(0),
                                onSelect = { viewModel.settings.setDnsMode(dnsModes[it].first) },
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

/** A labeled outlined field (no icon, no outer padding) for composing inside a
 *  [SettingsIconContent]:
 *  a [bodyLarge]/Medium label, an optional description, then an outlined field with body text. */
@Composable
private fun ProxyLabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true
) {
    var draft by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != draft) draft = value }
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; onChange(it) },
                placeholder = placeholder?.let { ph -> { Text(ph, style = MaterialTheme.typography.bodyMedium) } },
                singleLine = singleLine,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            )
        }
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
