package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.ui.theme.LxDesign

/**
 * Attachment picker anchor button with its dropdown menu.
 *
 * Exposes four launch targets — camera capture, photo gallery, video gallery
 * and arbitrary files — behind a single `+` icon. The actual launcher wiring
 * (ActivityResultContracts, permission gating, camera target creation) stays
 * in [ChatBottomBar] because those callbacks need access to the activity
 * scope and the [ChatComposerState]; this composable only renders the menu
 * and forwards the user's choice.
 *
 * A small dismiss-timestamp guard (`lastAddDismissTime`) prevents the menu
 * from re-opening immediately after dismissal when the anchor sits inside a
 * row that also captures touch events.
 *
 * Extracted from [ChatBottomBar] to isolate the attachment entry point from
 * the rest of the composer toolbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerAttachmentMenu(
    onLaunchCamera: () -> Unit,
    onLaunchPhotos: () -> Unit,
    onLaunchVideos: () -> Unit,
    onLaunchFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var lastAddDismissTime by remember { mutableLongStateOf(0L) }
    ExposedDropdownMenuBox(
        expanded = showAddMenu,
        onExpandedChange = { },
        modifier = modifier,
    ) {
        IconButton(
            onClick = {
                val now = System.currentTimeMillis()
                if (showAddMenu) {
                    showAddMenu = false
                } else if (now - lastAddDismissTime > 200) {
                    showAddMenu = true
                }
            },
            modifier = Modifier
                .size(48.dp)
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = true,
                ),
        ) {
            Icon(
                Icons.Default.Add,
                stringResource(R.string.add_attachment),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExposedDropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            expanded = showAddMenu,
            onDismissRequest = {
                if (showAddMenu) {
                    showAddMenu = false
                    lastAddDismissTime = System.currentTimeMillis()
                }
            },
            matchTextFieldWidth = false,
            shape = LxDesign.shapeS,
        ) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.camera))
                    }
                },
                onClick = {
                    showAddMenu = false
                    lastAddDismissTime = 0L
                    onLaunchCamera()
                },
            )
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Image,
                            stringResource(R.string.photos),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.photos))
                    }
                },
                onClick = {
                    showAddMenu = false
                    lastAddDismissTime = 0L
                    onLaunchPhotos()
                },
            )
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Videocam,
                            stringResource(R.string.videos),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.videos))
                    }
                },
                onClick = {
                    showAddMenu = false
                    lastAddDismissTime = 0L
                    onLaunchVideos()
                },
            )
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AttachFile,
                            stringResource(R.string.files),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.files))
                    }
                },
                onClick = {
                    showAddMenu = false
                    lastAddDismissTime = 0L
                    onLaunchFiles()
                },
            )
        }
    }
}