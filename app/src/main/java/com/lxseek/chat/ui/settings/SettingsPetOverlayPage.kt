package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.lxseek.chat.R
import com.lxseek.chat.pet.PetOverlayController
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Desktop-pet settings page. Lets the user show/hide the draggable floating bubble, prompts for the
 * system "Display over other apps" permission when missing, and deep-links to the system overlay
 * permission screen. Toggle state is persisted and drives the service via [PetOverlayController].
 */
@Composable
fun SettingsPetOverlayPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val enabled by viewModel.settings.petOverlayEnabled.collectAsState()
    var overlayGranted by remember { mutableStateOf(PetOverlayController.canDrawOverlay(context)) }

    // Re-read the system permission when the user returns from the overlay-permission screen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = PetOverlayController.canDrawOverlay(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun setEnabled(target: Boolean) {
        viewModel.viewModelScope.launch {
            if (target) {
                if (!PetOverlayController.canDrawOverlay(context)) {
                    PetOverlayController.setEnabled(context, false)
                    PetOverlayController.openPermissionSettings(context)
                    return@launch
                }
            }
            PetOverlayController.setEnabled(context, target)
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_pet_overlay),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.pet_overlay_howto),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        SettingsGroupColumn(modifier = Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = stringResource(R.string.settings_group_appearance_language),
                items = listOf({
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.pet_overlay_enabled)) },
                        supportingContent = { Text(stringResource(R.string.pet_overlay_enabled_desc)) },
                        leadingContent = {
                            Icon(
                                Icons.Default.Android,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = enabled && overlayGranted,
                                onCheckedChange = ::setEnabled,
                            )
                        },
                        modifier = Modifier.clickable {
                            setEnabled(!(enabled && overlayGranted))
                        },
                    )
                }),
            )

            SettingsGroup(
                title = stringResource(R.string.settings_group_permissions),
                items = listOf({
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = if (overlayGranted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    if (overlayGranted) {
                                        R.string.pet_overlay_permission_granted
                                    } else {
                                        R.string.pet_overlay_permission_missing
                                    },
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (overlayGranted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Text(
                                text = stringResource(R.string.pet_overlay_permission_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }),
            )

            if (!overlayGranted) {
                OutlinedButton(
                    onClick = { PetOverlayController.openPermissionSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.pet_overlay_grant_permission))
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}