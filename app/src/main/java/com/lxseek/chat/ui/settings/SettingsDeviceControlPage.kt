package com.lxseek.chat.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.androidcontrol.AndroidUiControllerService
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

/** Live snapshot of the Android accessibility bridge, read cheaply from the connected service. */
private data class DeviceControlStatus(
    val enabled: Boolean,
    val connected: Boolean,
    val activePackage: String?,
) {
    companion object {
        fun read(): DeviceControlStatus {
            val svc = AndroidUiControllerService.instance
            return DeviceControlStatus(
                enabled = svc != null,
                connected = svc?.isConnected() == true,
                activePackage = svc?.activePackage(),
            )
        }
    }
}

/**
 * Device-control status page. Shows whether the accessibility bridge that powers
 * [com.lxseek.chat.tool.AndroidAppControllerToolProvider] is enabled and connected, which app is
 * in the foreground, and deep-links into the system accessibility settings so the user can toggle
 * the bridge on. The status is polled while the page is visible.
 */
@Composable
fun SettingsDeviceControlPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(DeviceControlStatus.read()) }

    // Poll while the page is shown so the card reflects settings toggles made elsewhere.
    LaunchedEffect(Unit) {
        while (true) {
            status = DeviceControlStatus.read()
            delay(1_000L)
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_device_control),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Text(
            text = stringResource(R.string.device_control_howto),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        SettingsGroup(title = stringResource(R.string.device_control_accessibility), items = listOf(
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = if (status.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (status.enabled) R.string.device_control_enabled else R.string.device_control_disabled,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (status.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(
                                if (status.connected) R.string.device_control_connected else R.string.device_control_disconnected,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { status = DeviceControlStatus.read() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.device_control_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.device_control_foreground),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = status.activePackage?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.device_control_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        ))

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.device_control_open_settings))
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}