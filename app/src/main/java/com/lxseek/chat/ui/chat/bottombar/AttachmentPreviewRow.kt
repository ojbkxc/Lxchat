package com.lxseek.chat.ui.chat.bottombar

import android.net.Uri
import android.os.Build
import com.lxseek.chat.util.DebugLog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.lxseek.chat.R
import com.lxseek.chat.ui.chat.FileThumbnail
import com.lxseek.chat.ui.chat.readFileContent
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.ui.theme.LxDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The horizontal strip of pending-attachment thumbnails shown above the composer
 * (images, video frames, files, PDFs), each with a processing overlay and a remove
 * button. Extracted from [ChatBottomBar]; tapping a thumbnail routes through the
 * media / file / PDF click handlers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentPreviewRow(
    composer: ChatComposerState,
    onAllMediaClick: ((urls: List<String>, index: Int) -> Unit)?,
    onFileContentClick: ((fileName: String, content: String) -> Unit)?,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)?,
) {
    val context = LocalContext.current
    val haptics = LocalLxChatHaptics.current
    val scope = rememberCoroutineScope()
    val allMediaUrls = composer.selectedAttachments.filter {
        it.type == "image" || it.type == "video"
    }.map { it.uri }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            count = composer.selectedAttachments.size,
            key = { index -> composer.selectedAttachments[index].localId },
        ) { index ->
            val attachment = composer.selectedAttachments[index]
            val uriStr = attachment.uri
            val isVideo = attachment.type == "video"
            val isPdf = attachment.type == "pdf"
            val isFile = attachment.type == "file"
            val isProcessing = uriStr in composer.processingStates
            val progress = composer.processingStates[uriStr] ?: 0f
            // Video extraction shows determinate progress; image/file copy is indeterminate
            val showIndeterminate = isProcessing && !isVideo

            var videoThumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(uriStr, isVideo) {
                if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        videoThumb = withContext(Dispatchers.IO) {
                            context.contentResolver.loadThumbnail(
                                Uri.parse(uriStr), android.util.Size(128, 128), null
                            )
                        }
                    } catch (e: Exception) { DebugLog.e("AttachmentPreview", "Failed to load video thumbnail", e) }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(64.dp).padding(top = 5.dp)
            ) {
                Box {
                    val clickableMod = when {
                        isFile -> {
                            if (onFileContentClick != null) Modifier.clickable {
                                scope.launch {
                                    val content = readFileContent(
                                        context,
                                        attachment.localPath ?: uriStr,
                                    )
                                    onFileContentClick(
                                        attachment.fileName ?: uriStr,
                                        content,
                                    )
                                }
                            } else Modifier
                        }
                        isPdf -> {
                            if (onPdfPagesClick != null) Modifier.clickable {
                                val allPaths = attachment.preRenderedPaths ?: emptyList()
                                val sel = attachment.selectedPages
                                val paths = if (sel != null && allPaths.isNotEmpty()) {
                                    allPaths.filterIndexed { i, _ -> i in sel }
                                } else allPaths
                                onPdfPagesClick(paths, 0)
                            } else Modifier
                        }
                        isVideo -> {
                            val mediaIndex = allMediaUrls.indexOf(uriStr).coerceAtLeast(0)
                            Modifier.combinedClickable(
                                onClick = { onAllMediaClick?.invoke(allMediaUrls, mediaIndex) },
                                onLongClick = { haptics.longPress() },
                                hapticFeedbackEnabled = false,
                            )
                        }
                        else -> {
                            val mediaIndex = allMediaUrls.indexOf(uriStr).coerceAtLeast(0)
                            Modifier.combinedClickable(
                                onClick = { onAllMediaClick?.invoke(allMediaUrls, mediaIndex) },
                                onLongClick = { haptics.longPress() },
                                hapticFeedbackEnabled = false,
                            )
                        }
                    }
                    val thumbModifier = Modifier
                        .size(64.dp)
                        .clip(LxDesign.shapeS)
                        .then(clickableMod)

                    val thumb = videoThumb
                    when {
                        isVideo && thumb != null -> {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = stringResource(R.string.video_thumbnail),
                                modifier = thumbModifier,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.play),
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .padding(4.dp)
                            )
                        }
                        isVideo -> {
                            Box(
                                modifier = thumbModifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Videocam,
                                    stringResource(R.string.video),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        isPdf -> {
                            FileThumbnail(fileName = null, isPdf = true, modifier = thumbModifier)
                        }
                        isFile -> {
                            FileThumbnail(fileName = attachment.fileName ?: uriStr, isPdf = false, modifier = thumbModifier)
                        }
                        else -> {
                            coil.compose.AsyncImage(
                                model = attachment.localPath ?: uriStr,
                                contentDescription = null,
                                modifier = thumbModifier,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                    }

                    // Processing indicator overlay
                    if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(LxDesign.shapeS)
                                .background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (showIndeterminate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 5.dp, y = (-5).dp)
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                        .clip(CircleShape)
                        .clickable {
                            composer.removeAttachmentAt(index)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.remove),
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
                }
                if ((isFile || isPdf) && attachment.fileName != null) {
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
