package com.lxseek.chat.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.model.AttachmentItem
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.util.AttachmentSourceReader
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun resolveAttachmentType(
    path: String,
    metaItem: AttachmentItem?,
): String {
    if (metaItem != null) return metaItem.type
    val normalized = path.substringBefore('?').substringBefore('#').lowercase()
    return when {
        normalized.endsWith(".pdf") -> "pdf"
        normalized.endsWith(".mp4") ||
            normalized.endsWith(".webm") ||
            normalized.endsWith(".mov") ||
            normalized.endsWith(".avi") ||
            normalized.contains("vid_original_") -> "video"
        // This fallback is used only for legacy image-list entries. Generic files are always
        // represented by AttachmentMeta, so MIME-provider IPC is unnecessary during composition.
        else -> "image"
    }
}

fun findMetaForIndex(meta: AttachmentMeta?, index: Int): AttachmentItem? {
    if (meta == null) return null
    meta.items.firstOrNull { it.imageIndex == index }?.let { return it }
    return meta.items.firstOrNull { m ->
        m.imageIndex != null && (m.pageCount ?: 1) > 0 &&
        index in m.imageIndex until m.imageIndex + (m.pageCount ?: 1)
    }
}

suspend fun readFileContent(
    context: Context,
    uriString: String,
    maxChars: Int = Constants.MAX_FILE_CONTENT_READ_LENGTH,
): String = withContext(Dispatchers.IO) {
    AttachmentSourceReader.readText(context, uriString, maxChars) ?: ""
}

@Composable
fun FileThumbnail(
    fileName: String?,
    isPdf: Boolean,
    modifier: Modifier = Modifier
) {
    if (isPdf) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text("PDF", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
    } else {
        val ext = (fileName ?: "").substringAfterLast('.', "").uppercase().take(4).ifEmpty { "TXT" }
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Text(ext, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

data class ThumbnailClickHandlers(
    val onMediaClick: ((urls: List<String>, index: Int) -> Unit)? = null,
    val onFileClick: ((fileName: String, content: String) -> Unit)? = null,
    val onPdfClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun AttachmentThumbnailItem(
    type: String,
    imagePath: String,
    fileName: String?,
    originalUri: String? = null,
    textContent: String? = null,
    pdfPages: List<String> = emptyList(),
    showFileName: Boolean = true,
    allMediaUrls: List<String> = emptyList(),
    mediaIndex: Int = 0,
    handlers: ThumbnailClickHandlers = ThumbnailClickHandlers(),
    modifier: Modifier = Modifier
) {
    val haptics = LocalLxChatHaptics.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbModifier = modifier
        .size(120.dp, 90.dp)
        .clip(RoundedCornerShape(8.dp))

    when (type) {
        "file" -> {
            val canOpen = handlers.onFileClick != null &&
                (textContent != null || originalUri != null)
            val clickMod = if (canOpen) {
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        scope.launch {
                            val content = textContent ?: originalUri?.let {
                                readFileContent(
                                    context = context,
                                    uriString = it,
                                    maxChars = Constants.MAX_FILE_CONTENT_READ_LENGTH,
                                )
                            }
                            if (content != null) {
                                handlers.onFileClick?.invoke(fileName ?: "", content)
                            }
                        }
                    }
            } else {
                Modifier
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).then(clickMod)) {
                FileThumbnail(fileName = fileName, isPdf = false, modifier = Modifier.size(64.dp))
                if (showFileName && fileName != null) {
                    Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        "pdf" -> {
            val hasPages = pdfPages.isNotEmpty()
            val clickMod = if (hasPages && handlers.onPdfClick != null)
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { handlers.onPdfClick(pdfPages, 0) } else Modifier
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                FileThumbnail(fileName = null, isPdf = true, modifier = Modifier.size(64.dp).then(clickMod))
                if (showFileName && fileName != null) {
                    Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        "video" -> {
            val clickMod = if (originalUri != null && handlers.onMediaClick != null)
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { handlers.onMediaClick(allMediaUrls, mediaIndex) } else Modifier
            Box(modifier = clickMod) {
                coil.compose.AsyncImage(
                    model = imagePath,
                    contentDescription = null,
                    modifier = thumbModifier,
                    contentScale = ContentScale.Crop
                )
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(4.dp)
                )
            }
        }
        else -> { // image
            if (imagePath.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { handlers.onMediaClick?.invoke(allMediaUrls, mediaIndex) },
                            onLongClick = { haptics.longPress() },
                            hapticFeedbackEnabled = false,
                        )
                ) {
                    coil.compose.AsyncImage(
                        model = imagePath,
                        contentDescription = null,
                        modifier = thumbModifier,
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                // No image data available (e.g. Claude import), show file-style thumbnail
                val clickMod = if (fileName != null && handlers.onFileClick != null)
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { handlers.onFileClick(fileName, textContent ?: "") } else Modifier
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                    Box(
                        modifier = Modifier.size(64.dp).then(clickMod)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val ext = (fileName ?: "").substringAfterLast('.', "").uppercase().take(4).ifEmpty { "IMG" }
                        Text(ext, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    if (showFileName && fileName != null) {
                        Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
