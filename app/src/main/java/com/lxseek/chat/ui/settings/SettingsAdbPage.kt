package com.lxseek.chat.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.adb.AdbExtensionManager
import com.lxseek.chat.adb.AdbLog
import com.lxseek.chat.adb.LadbManager
import com.lxseek.chat.adb.RootDetector
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAdbPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Root detection
    var rootAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        rootAvailable = withContext(Dispatchers.IO) { RootDetector.isRootAvailable() }
    }

    // Extension manager
    val extensionManager = remember { AdbExtensionManager(context) }
    var extensionState by remember { mutableStateOf<AdbExtensionManager.State>(AdbExtensionManager.State.NotDownloaded) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    // Initialize state from manager
    LaunchedEffect(Unit) {
        extensionState = if (extensionManager.isInstalled()) {
            AdbExtensionManager.State.Installed
        } else {
            AdbExtensionManager.State.NotDownloaded
        }
        downloadError = extensionManager.lastError()
    }

    // LADB manager (only needed for non-root)
    val ladbManager = remember { LadbManager.getInstance(context) }
    val ladbRunning by ladbManager.running.collectAsState()
    var isPaired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isPaired = ladbManager.isPaired() }

    // Pairing inputs
    var portInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var pairResult by remember { mutableStateOf<String?>(null) }

    // Test result
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // Reconnecting
    var reconnecting by remember { mutableStateOf(false) }
    var reconnectResult by remember { mutableStateOf<String?>(null) }

    // Live pairing / shell diagnostic logs (visible in-app, no logcat required)
    val adbLogs by AdbLog.entries.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_adb_shell),
        onBack = onBack,
        scrollState = scrollState,
    ) {
        SettingsGroupColumn {
            // ── Status Section ──────────────────────────────
            SettingsGroup(title = stringResource(R.string.adb_status), items = buildList {
                // Root status
                add {
                    SettingsItem(
                        headlineContent = {
                            Text(if (rootAvailable) stringResource(R.string.adb_root_detected)
                                 else stringResource(R.string.adb_root_not_detected))
                        },
                        leadingContent = {
                            Icon(
                                if (rootAvailable) Icons.Default.Check else Icons.Default.Block,
                                null,
                                tint = if (rootAvailable) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        },
                        supportingContent = if (rootAvailable) {
                            { Text(stringResource(R.string.adb_root_mode_active)) }
                        } else null,
                    )
                }
                // Extension status
                add {
                    when (val state = extensionState) {
                        is AdbExtensionManager.State.NotDownloaded -> {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.adb_extension_not_installed)) },
                                supportingContent = { Text("${stringResource(R.string.adb_download_extension)} (${AdbExtensionManager.DOWNLOAD_SIZE_HINT})") },
                                leadingContent = { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    TextButton(onClick = {
                                        extensionState = AdbExtensionManager.State.Downloading(0)
                                        downloadError = null
                                        scope.launch(Dispatchers.IO) {
                                            val ok = extensionManager.download { pct ->
                                                extensionState = AdbExtensionManager.State.Downloading(pct)
                                            }
                                            if (ok) {
                                                extensionState = AdbExtensionManager.State.Installed
                                            } else {
                                                extensionState = AdbExtensionManager.State.Failed(extensionManager.lastError() ?: "Download failed")
                                                downloadError = extensionManager.lastError()
                                            }
                                        }
                                    }) { Text(stringResource(R.string.adb_download)) }
                                },
                            )
                        }
                        is AdbExtensionManager.State.Downloading -> {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.adb_downloading, state.progress)) },
                                leadingContent = { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    LinearProgressIndicator(
                                        progress = { state.progress / 100f },
                                        modifier = Modifier.width(80.dp),
                                    )
                                },
                            )
                        }
                        is AdbExtensionManager.State.Installed -> {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.adb_extension_installed)) },
                                leadingContent = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            extensionManager.uninstall()
                                            extensionState = AdbExtensionManager.State.NotDownloaded
                                            isPaired = false
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) { Text(stringResource(R.string.adb_remove)) }
                                },
                            )
                        }
                        is AdbExtensionManager.State.Failed -> {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.adb_download_failed)) },
                                supportingContent = { Text(state.message, color = MaterialTheme.colorScheme.error) },
                                leadingContent = { Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error) },
                                trailingContent = {
                                    TextButton(onClick = {
                                        extensionState = AdbExtensionManager.State.NotDownloaded
                                    }) { Text(stringResource(R.string.cancel)) }
                                },
                            )
                        }
                    }
                }
            })

            // ── Root Path ───────────────────────────────────
            if (rootAvailable) {
                SettingsGroup(title = stringResource(R.string.adb_root_path), items = listOf {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.adb_root_mode_active)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.adb_root_desc))
                                testResult?.let {
                                    Spacer(Modifier.height(8.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            TextButton(enabled = !testing, onClick = {
                                testing = true
                                testResult = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                                        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                                        val out = p.inputStream.bufferedReader().use { it.readText() }
                                        testResult = out.ifBlank { "exit=${p.exitValue()}" }
                                    } catch (e: Exception) {
                                        testResult = "Error: ${e.message}"
                                    }
                                    testing = false
                                }
                            }) { Text(stringResource(R.string.adb_test)) }
                        },
                    )
                })
            }

            // ── Non-Root Path ───────────────────────────────
            if (!rootAvailable && extensionState is AdbExtensionManager.State.Installed) {
                if (!isPaired) {
                    // Pairing UI
                    SettingsGroup(title = stringResource(R.string.adb_pairing_title), items = buildList {
                        add {
                            SettingsIconContent(icon = Icons.Default.Info) {
                                Text(
                                    stringResource(R.string.adb_pairing_instructions),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        add {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                OutlinedTextField(
                                    value = portInput,
                                    onValueChange = { portInput = it.filter { c -> c.isDigit() } },
                                    label = { Text(stringResource(R.string.adb_port)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = codeInput,
                                    onValueChange = { codeInput = it },
                                    label = { Text(stringResource(R.string.adb_pairing_code)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    enabled = !pairing && portInput.isNotBlank() && codeInput.isNotBlank(),
                                    onClick = {
                                        pairing = true
                                        pairResult = null
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                ladbManager.startPortDiscovery()
                                                val ok = ladbManager.pair(portInput.trim(), codeInput.trim())
                                                if (ok) {
                                                    isPaired = true
                                                    pairResult = "Paired successfully"
                                                } else {
                                                    pairResult = "Pairing failed — check port and code"
                                                }
                                            } catch (e: Exception) {
                                                pairResult = "Error: ${e.message}"
                                            }
                                            pairing = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (pairing) stringResource(R.string.adb_pairing) else stringResource(R.string.adb_pair)) }
                                pairResult?.let {
                                    Spacer(Modifier.height(8.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = if (it.startsWith("Paired")) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    })
                } else {
                    // Paired — connection info + reconnect
                    SettingsGroup(title = stringResource(R.string.adb_connection), items = buildList {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.adb_paired)) },
                                supportingContent = {
                                    Column {
                                        Text(if (ladbRunning == true) stringResource(R.string.adb_connected_running)
                                             else stringResource(R.string.adb_connected_idle))
                                        Text(stringResource(R.string.adb_auto_reconnect),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        reconnectResult?.let {
                                            Spacer(Modifier.height(4.dp))
                                            Text(it, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                leadingContent = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    TextButton(enabled = !reconnecting, onClick = {
                                        reconnecting = true
                                        reconnectResult = null
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                ladbManager.kill()
                                                ladbManager.startPortDiscovery()
                                                val ok = ladbManager.initServer()
                                                reconnectResult = if (ok) "Reconnected" else "Reconnect failed"
                                            } catch (e: Exception) {
                                                reconnectResult = "Error: ${e.message}"
                                            }
                                            reconnecting = false
                                        }
                                    }) { Text(stringResource(R.string.adb_reconnect)) }
                                },
                            )
                        }
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.adb_test)) },
                                leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    TextButton(enabled = !testing && ladbRunning == true, onClick = {
                                        testing = true
                                        testResult = null
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                testResult = ladbManager.sendCommand("id")
                                            } catch (e: Exception) {
                                                testResult = "Error: ${e.message}"
                                            }
                                            testing = false
                                        }
                                    }) { Text(stringResource(R.string.adb_test)) }
                                },
                                supportingContent = {
                                    testResult?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                            )
                        }
                    })
                }
            }

            // ── Live Logs ──────────────────────────────────
            SettingsGroup(title = stringResource(R.string.adb_logs_title), items = listOf {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = adbLogs.isNotEmpty(), onClick = { AdbLog.clear() }) {
                            Text(stringResource(R.string.adb_logs_clear))
                        }
                    }
                    if (adbLogs.isEmpty()) {
                        Text(
                            stringResource(R.string.adb_logs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        adbLogs.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                }
            })

            // ── Info ────────────────────────────────────────
            SettingsGroup(title = stringResource(R.string.adb_info), items = listOf {
                SettingsIconContent(icon = Icons.Default.Info) {
                    Text(
                        stringResource(R.string.adb_info_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            })
        }
    }
}