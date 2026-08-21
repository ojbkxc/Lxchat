package com.lxseek.chat.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.androidcontrol.AndroidUiControllerService
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.util.TtsManager
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

/** Live snapshot of the accessibility bridge, mirroring the device-control page's cheap read. */
private data class RuntimeAccessibilityStatus(
    val enabled: Boolean,
    val connected: Boolean,
    val activePackage: String?,
) {
    companion object {
        fun read(): RuntimeAccessibilityStatus {
            val svc = AndroidUiControllerService.instance
            return RuntimeAccessibilityStatus(
                enabled = svc != null,
                connected = svc?.isConnected() == true,
                activePackage = svc?.activePackage(),
            )
        }
    }
}

/**
 * Agent / runtime status board. Aggregates the live state of everything that keeps LxChat running:
 * the accessibility bridge that powers device control, the TTS engine for speech, and the model
 * configuration for generation. Status is refreshed while the page is visible so toggles made in
 * other screens appear immediately.
 */
@Composable
fun SettingsRuntimeStatusPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = viewModel.settings
    var a11y by remember { mutableStateOf(RuntimeAccessibilityStatus.read()) }
    val ttsAvailable by TtsManager.isAvailable.collectAsState()
    val ttsPlaying by TtsManager.isPlaying.collectAsState()
    val selectedModel by settings.selectedModel.collectAsState()
    val customModels by settings.customModels.collectAsState()
    val enabledModels by settings.enabledModels.collectAsState()

    // Poll for the accessibility bridge's live state (it is tied to the system service lifecycle).
    LaunchedEffect(Unit) {
        while (true) {
            a11y = RuntimeAccessibilityStatus.read()
            delay(1_000L)
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_runtime_status),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Text(
            text = stringResource(R.string.runtime_status_howto),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        SettingsGroup(title = stringResource(R.string.runtime_accessibility_title), items = listOf(
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = if (a11y.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        StatusLine(
                            title = stringResource(
                                if (a11y.enabled) R.string.runtime_bridge_enabled else R.string.runtime_bridge_disabled,
                            ),
                            positive = a11y.enabled,
                        )
                        StatusLine(
                            title = stringResource(
                                if (a11y.connected) R.string.runtime_bridge_connected else R.string.runtime_bridge_disconnected,
                            ),
                            positive = a11y.connected,
                        )
                        if (!a11y.activePackage.isNullOrBlank()) {
                            StatusLine(
                                title = a11y.activePackage.orEmpty(),
                                positive = false,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { a11y = RuntimeAccessibilityStatus.read() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.runtime_refresh),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text(stringResource(R.string.runtime_open_settings))
                        }
                    }
                }
            },
        ))

        SettingsGroup(title = stringResource(R.string.runtime_tts_title), items = listOf(
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = if (ttsAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        StatusLine(
                            title = stringResource(
                                if (ttsAvailable) R.string.runtime_tts_ready else R.string.runtime_tts_missing,
                            ),
                            positive = ttsAvailable,
                        )
                        StatusLine(
                            title = stringResource(
                                if (ttsPlaying) R.string.runtime_tts_playing else R.string.runtime_tts_idle,
                            ),
                            positive = ttsPlaying,
                        )
                    }
                }
            },
        ))

        SettingsGroup(title = stringResource(R.string.runtime_model_title), items = listOf(
            {
                val parsed = ModelId.parse(selectedModel)
                val alias = settings.modelAliases.value[selectedModel]
                val displayName = alias ?: parsed.apiModelName
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        StatusLine(title = displayName.ifBlank { stringResource(R.string.runtime_model_unset) }, positive = displayName.isNotBlank())
                        StatusLine(
                            title = stringResource(R.string.runtime_model_counts, enabledModels.size, customModels.size),
                            positive = false,
                        )
                    }
                }
            },
        ))
    }
}

@Composable
private fun StatusLine(title: String, positive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (positive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}