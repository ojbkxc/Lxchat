package com.lxseek.chat.ui.chat

import com.lxseek.chat.ui.components.DialogWindowEdgeToEdge

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.lxseek.chat.R
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.Locale

private fun directImageFile(url: String): File? {
    val path = if (url.startsWith("file://", ignoreCase = true)) {
        Uri.parse(url).path
    } else {
        url
    }
    return path?.let(::File)?.takeIf(File::isFile)
}

private fun openImageInput(context: Context, url: String): InputStream? =
    directImageFile(url)?.inputStream()
        ?: context.contentResolver.openInputStream(Uri.parse(url))

/** Save the image into the device gallery (Pictures/LxChat). Returns true on success. */
suspend fun saveImageToGallery(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var destination: Uri? = null
    try {
        val name = "lxchat_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LxChat")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false
        destination = uri
        val input = openImageInput(context, url)
            ?: throw IOException("Unable to open source image")
        input.use { source ->
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("Unable to open gallery destination")
            output.use { sink -> source.copyTo(sink) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (_: Exception) {
        destination?.let { runCatching { resolver.delete(it, null, null) } }
        false
    }
}

/** Share the image via a content Uri (copied into the exposed cache dir for FileProvider). */
suspend fun shareImage(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "lxchat_${System.currentTimeMillis()}.jpg")
        val input = openImageInput(context, url) ?: return@withContext false
        input.use { source ->
            file.outputStream().use { sink -> source.copyTo(sink) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.img_action_share))
            )
        }
        true
    } catch (_: Exception) {
        false
    }
}

private data class ImageInfo(val width: Int, val height: Int, val sizeBytes: Long)

private fun readImageInfo(context: Context, url: String): ImageInfo? {
    return try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openImageInput(context, url)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, opts)
        } ?: return null
        val size = directImageFile(url)?.length()
            ?: context.contentResolver
                .openAssetFileDescriptor(Uri.parse(url), "r")
                ?.use { descriptor -> descriptor.length.takeIf { it >= 0L } }
            ?: 0L
        ImageInfo(opts.outWidth, opts.outHeight, size)
    } catch (_: Exception) { null }
}

private fun formatBytes(n: Long): String = when {
    n >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", n / (1024.0 * 1024.0))
    n >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", n / 1024.0)
    else -> "$n B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageActionsSheet(url: String, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showInfo by remember { mutableStateOf(false) }
    var imageInfo by remember(url) { mutableStateOf<ImageInfo?>(null) }
    var imageInfoLoading by remember(url) { mutableStateOf(false) }
    var sheetVisible by remember(url) { mutableStateOf(true) }
    val motionPolicy = LocalLxChatMotionPolicy.current
    val sheetState = rememberModalBottomSheetState()

    // Animate the sheet down before running the action, so every option exits with a
    // collapse animation instead of vanishing abruptly. The composable stays in
    // composition during hide(), so actions that need it (the Info dialog, an in-flight
    // save) keep working.
    fun collapseThen(action: () -> Unit) {
        if (motionPolicy.allowSpatialTransitions) {
            scope.launch {
                try { sheetState.hide() } finally { action() }
            }
        } else {
            sheetVisible = false
            action()
        }
    }

    // Routed through the single global snackbar host (a new message dismisses the previous one).
    val savedMsg = stringResource(R.string.img_saved)
    val failMsg = stringResource(R.string.img_save_failed)
    fun doSave() {
        // Keep the sheet in composition until the save finishes — dismissing first would cancel
        // this scope (it's tied to the sheet) and abort both the save and the snackbar.
        scope.launch {
            val ok = saveImageToGallery(context, url)
            onMessage(if (ok) savedMsg else failMsg)
            onDismiss()
        }
    }
    fun doShare() {
        scope.launch {
            shareImage(context, url)
            onDismiss()
        }
    }
    LaunchedEffect(showInfo, url) {
        if (showInfo && imageInfo == null) {
            imageInfoLoading = true
            imageInfo = withContext(Dispatchers.IO) { readImageInfo(context, url) }
            imageInfoLoading = false
        }
    }
    // Pre-Q gallery writes need WRITE_EXTERNAL_STORAGE; request it then save.
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) doSave() else onMessage(failMsg) }

    if (sheetVisible) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            DialogWindowEdgeToEdge()
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                ActionRow(Icons.Default.Download, stringResource(R.string.img_action_save)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) collapseThen { doSave() }
                    else permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                ActionRow(Icons.Default.Share, stringResource(R.string.img_action_share)) {
                    collapseThen { doShare() }
                }
                ActionRow(Icons.Default.Info, stringResource(R.string.info)) {
                    collapseThen { showInfo = true }
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showInfo = false; onDismiss() },
            title = { Text(stringResource(R.string.info), fontWeight = FontWeight.Bold) },
            text = {
                if (imageInfoLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoLine(
                            stringResource(R.string.img_info_dimensions),
                            imageInfo?.let { "${it.width} × ${it.height}" } ?: "—",
                        )
                        InfoLine(
                            stringResource(R.string.img_info_size),
                            imageInfo?.let { formatBytes(it.sizeBytes) } ?: "—",
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false; onDismiss() }) { Text(stringResource(R.string.provider_close)) } }
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    // Matches the message info dialog: single "Label: value" line at bodyMedium 14/20.
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
    )
}
