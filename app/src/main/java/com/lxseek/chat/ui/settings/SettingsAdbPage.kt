package com.lxseek.chat.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.adb.AdbLog
import com.lxseek.chat.adb.RootDetector
import com.lxseek.chat.adb.ShizukuManager
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

    // Shizuku manager (only needed for non-root)
    val shizukuManager = remember { ShizukuManager(context) }

    // Shizuku status (recomputed on a refresh trigger so the UI reflects user actions)
    var refreshTick by remember { mutableStateOf(0) }
    val shizukuInstalled = remember(refreshTick) { shizukuManager.isShizukuInstalled() }
    val shizukuRunning = remember(refreshTick) { shizukuManager.isShizukuRunning() }
    val shizukuGranted = remember(refreshTick) { shizukuManager.isPermissionGranted() }
    val shizukuReady = shizukuInstalled && shizukuRunning && shizukuGranted

    // Test result
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // Helper: open an Intent safely.
    fun launchIntent(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            AdbLog.log("SettingsAdbPage: startActivity failed — ${e.message}")
        }
    }

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

            // ── Shizuku (Non-Root) Path ────────────────────
            if (!rootAvailable) {
                SettingsGroup(title = stringResource(R.string.adb_shizuku), items = buildList {
                    // Description
                    add {
                        SettingsIconContent(icon = Icons.Default.Info) {
                            Text(
                                stringResource(R.string.adb_shizuku_description),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    // State-driven status row
                    add {
                        when {
                            !shizukuInstalled -> {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.adb_shizuku_not_installed)) },
                                    supportingContent = { Text(ShizukuManager.SHIZUKU_PLAY_URL) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Download,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            launchIntent(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(ShizukuManager.SHIZUKU_PLAY_URL),
                                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }) { Text(stringResource(R.string.adb_shizuku_download)) }
                                    },
                                )
                            }
                            !shizukuRunning -> {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.adb_shizuku_not_running)) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            launchIntent(
                                                context.packageManager.getLaunchIntentForPackage(
                                                    ShizukuManager.SHIZUKU_PACKAGE
                                                )?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    ?: Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(ShizukuManager.SHIZUKU_WEBSITE_URL),
                                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                            // Give the user a moment to start the service, then re-check.
                                            scope.launch {
                                                kotlinx.coroutines.delay(1500)
                                                refreshTick++
                                            }
                                        }) { Text(stringResource(R.string.adb_shizuku_open_app)) }
                                    },
                                )
                            }
                            !shizukuGranted -> {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.adb_shizuku_not_authorized)) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Key,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            shizukuManager.requestPermission()
                                            // Permission dialog is async; re-check after a short delay.
                                            scope.launch {
                                                kotlinx.coroutines.delay(1500)
                                                refreshTick++
                                            }
                                        }) { Text(stringResource(R.string.adb_shizuku_authorize)) }
                                    },
                                )
                            }
                            else -> {
                                // Ready
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.adb_shizuku_ready)) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        TextButton(enabled = !testing, onClick = {
                                            testing = true
                                            testResult = null
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    testResult = shizukuManager.executeCommand("id")
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
                        }
                    }
                    // Refresh action (lets the user re-check state after acting outside the app)
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.adb_shizuku_refresh)) },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Refresh,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = { refreshTick++ }) {
                                    Text(stringResource(R.string.adb_shizuku_refresh))
                                }
                            },
                        )
                    }
                })
            }

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
