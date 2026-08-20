package com.lxseek.chat.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.lxseek.chat.R
import com.lxseek.chat.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MessageExportController(
    private val service: ConversationForkShareService,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val emitSnackbar: suspend (SnackbarEvent) -> Unit,
) {
    fun copyMessagesAsPlainText(conversationId: String?, messageIds: Set<String>) = scope.launch {
        if (messageIds.isEmpty() || conversationId == null) return@launch
        when (val result = service.buildPlainText(
            conversationId = conversationId,
            messageIds = messageIds,
            userLabel = appContext.getString(R.string.share_label_user),
            aiLabel = appContext.getString(R.string.share_label_ai),
        )) {
            is ConversationForkShareService.ShareResult.Success -> {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@launch
                clipboard.setPrimaryClip(ClipData.newPlainText("lxchat", result.text))
                emitSnackbar(SnackbarEvent(appContext.getString(R.string.copied_to_clipboard)))
            }
            is ConversationForkShareService.ShareResult.Failure -> {
                emitSnackbar(SnackbarEvent(result.reason))
            }
        }
    }

    fun shareMessagesAsLongImage(
        conversationId: String?,
        messageIds: Set<String>,
        title: String,
    ) = scope.launch {
        if (messageIds.isEmpty() || conversationId == null) return@launch
        when (val result = service.buildPlainText(
            conversationId = conversationId,
            messageIds = messageIds,
            userLabel = appContext.getString(R.string.share_label_user),
            aiLabel = appContext.getString(R.string.share_label_ai),
        )) {
            is ConversationForkShareService.ShareResult.Success -> {
                val file = MessageLongImageRenderer.renderToCacheFile(appContext, title, result.text)
                if (file == null) {
                    emitSnackbar(SnackbarEvent(appContext.getString(R.string.share_failed)))
                    return@launch
                }
                val uri: Uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, appContext.getString(R.string.share_long_image))
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(chooser)
            }
            is ConversationForkShareService.ShareResult.Failure -> {
                emitSnackbar(SnackbarEvent(result.reason))
            }
        }
    }

    fun saveLongImageToGallery(
        conversationId: String?,
        messageIds: Set<String>,
        title: String,
    ) = scope.launch {
        if (messageIds.isEmpty() || conversationId == null) return@launch
        when (val result = service.buildPlainText(
            conversationId = conversationId,
            messageIds = messageIds,
            userLabel = appContext.getString(R.string.share_label_user),
            aiLabel = appContext.getString(R.string.share_label_ai),
        )) {
            is ConversationForkShareService.ShareResult.Success -> {
                val bitmap = MessageLongImageRenderer.renderToBitmap(title, result.text)
                if (bitmap == null) {
                    emitSnackbar(SnackbarEvent(appContext.getString(R.string.share_failed)))
                    return@launch
                }
                val filename = "LxChat_${title.take(40).replace(Regex("[^A-Za-z0-9]"), "_")}_${System.currentTimeMillis()}.png"
                val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveBitmapQPlus(bitmap, filename)
                } else {
                    saveBitmapLegacy(bitmap, filename)
                }
                if (saved) {
                    emitSnackbar(SnackbarEvent(appContext.getString(R.string.share_saved_to_gallery)))
                } else {
                    emitSnackbar(SnackbarEvent(appContext.getString(R.string.share_failed)))
                }
            }
            is ConversationForkShareService.ShareResult.Failure -> {
                emitSnackbar(SnackbarEvent(result.reason))
            }
        }
    }

    private fun saveBitmapQPlus(bitmap: android.graphics.Bitmap, filename: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LxChat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = appContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            appContext.contentResolver.update(uri, values, null, null)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun saveBitmapLegacy(bitmap: android.graphics.Bitmap, filename: String): Boolean {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
        val lxchatDir = java.io.File(dir, "LxChat").apply { mkdirs() }
        val file = java.io.File(lxchatDir, filename)
        return try {
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(file)
            appContext.sendBroadcast(mediaScanIntent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
