package com.lxseek.chat.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop-pet settings page. Lets the user show/hide the draggable floating bubble, prompts for the
 * system "Display over other apps" permission when missing, and deep-links to the system overlay
 * permission screen. Toggle state is persisted and drives the service via [PetOverlayController].
 *
 * The page also lets the user pick a custom image (transparent PNG) from the system gallery; the
 * image is copied into app-private storage and its path persisted, so the pet can be re-rendered
 * with the user's icon instead of the built-in Canvas bubble.
 */
@Composable
fun SettingsPetOverlayPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val enabled by viewModel.settings.petOverlayEnabled.collectAsState()
    val imagePath by viewModel.settings.petOverlayImagePath.collectAsState()
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

    // Image picker: copies the selected Uri into app-private storage, persists the path, and
    // refreshes the running overlay so the new icon shows up immediately.
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.viewModelScope.launch {
                    val destFile = withContext(Dispatchers.IO) {
                        copyUriToInternalStorage(context, uri)
                    }
                    if (destFile != null) {
                        viewModel.settings.savePetOverlayImagePath(destFile.absolutePath)
                        PetOverlayController.refreshImage(context)
                    }
                }
            }
        },
    )

    fun clearImage() {
        viewModel.viewModelScope.launch {
            viewModel.settings.savePetOverlayImagePath("")
            PetOverlayController.refreshImage(context)
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
                title = stringResource(R.string.pet_overlay_custom_image),
                items = listOf({
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.pet_overlay_image_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Thumbnail preview of the currently selected image (if any).
                            ImagePreview(
                                path = imagePath,
                                modifier = Modifier.size(56.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { imagePicker.launch("image/*") },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.pet_overlay_select_image))
                                }
                                if (imagePath.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = ::clearImage,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.pet_overlay_clear_image))
                                    }
                                }
                            }
                        }
                    }
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

/**
 * Decodes the bitmap at [path] (if it exists) and shows it inside a rounded box. Shows a
 * placeholder icon when the path is empty or the file cannot be decoded. Decoding runs on the IO
 * dispatcher to avoid jank; the result is held in local state keyed by the path so recomposition
 * is cheap.
 */
@Composable
private fun ImagePreview(path: String, modifier: Modifier = Modifier) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        bitmap = null
        failed = false
        if (path.isBlank()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) null
            else runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
        if (loaded != null) bitmap = loaded else failed = true
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (failed) {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Copies the content of [uri] into [context.filesDir]/[PET_IMAGE_FILE_NAME], returning the
 * destination [File] on success or `null` on failure. Must be called off the main thread.
 */
private fun copyUriToInternalStorage(context: android.content.Context, uri: android.net.Uri): File? {
    return try {
        val destFile = File(context.filesDir, PET_IMAGE_FILE_NAME)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        destFile
    } catch (e: Exception) {
        null
    }
}

private const val PET_IMAGE_FILE_NAME = "pet_overlay_image.png"
