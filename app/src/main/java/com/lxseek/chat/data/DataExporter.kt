package com.lxseek.chat.data

import android.content.Context
import android.net.Uri
import com.lxseek.chat.automation.LoopPolicy
import com.lxseek.chat.data.local.ChatDao
import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.MessageAttachmentReference
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.MessageToolMediaReference
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.SelectedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataExporter(
    private val context: Context,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager
) {
    private val userSkillStore = com.lxseek.chat.skill.UserSkillStore(context)

    companion object {
        /** Bounds entity/string expansion while exporting databases with large chat histories. */
        private const val MESSAGE_PAGE_SIZE = 64
    }

    enum class ExportCategory(val manifestKey: String) {
        CONVERSATIONS("conversations"),
        MEMORIES("memories"),
        SYSTEM_PROMPTS("system_prompts"),
        SETTINGS("settings"),
        API_KEYS("api_keys"),
        SKILLS("skills");

        companion object {
            fun fromManifestKey(key: String): ExportCategory? =
                entries.find { it.manifestKey == key }
        }
    }

    @Serializable
    private data class ExportManifest(
        @SerialName("lxchat_export_version") val version: Int,
        @SerialName("app_version") val appVersion: String,
        @SerialName("exported_at") val exportedAt: String,
        val categories: List<String>,
        @SerialName("has_api_keys") val hasApiKeys: Boolean = false
    )

    @Serializable
    private data class ExportChatEntity(
        val id: String,
        val title: String,
        val lastUpdated: Long,
        val selectedBranchesJson: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null,
        val taskId: String? = null,
        val origin: String = "user",
        val graduated: Boolean = false,
        val selectedRunBranchesJson: String? = null,
        val draftText: String = "",
        val draftAttachments: String? = null,
        val conversationSettings: ConversationSettings? = null,
    )

    @Serializable
    private data class ExportRunEntity(
        val id: String,
        val conversationId: String,
        val parentRunId: String? = null,
        val status: String,
        val startedAt: Long,
        val lastCheckpointAt: Long,
        val stopRequestedAt: Long? = null,
        val endedAt: Long? = null,
        val endReason: String? = null,
        val currentPass: Int = 0,
        val legacyAmbiguous: Boolean = false,
    )

    @Serializable
    private data class ExportTaskEntity(
        val id: String,
        val name: String,
        val prompt: String,
        val systemPrompt: String? = null,
        val modelId: String? = null,
        val cronExpr: String,
        /** One-shot fire instant; null for a recurring (cron) task. */
        val runAt: Long? = null,
        val createdAt: Long,
        val lastRunAt: Long? = null
    )

    @Serializable
    private data class ExportLoopEntity(
        val conversationId: String,
        val intervalMs: Long,
        val prompt: String? = null,
        val cycleCount: Int = 0,
        /** New v2 archives always emit the bounded default for legacy null values. */
        val maxCycles: Int? = LoopPolicy.DEFAULT_MAX_CYCLES,
    )

    @Serializable
    private data class ExportMessageEntity(
        val id: String,
        val conversationId: String,
        val parentId: String? = null,
        val text: String,
        val images: List<String> = emptyList(),
        val thoughts: String? = null,
        val thoughtTitle: String? = null,
        val tokenCount: Int = 0,
        val inputTokenCount: Int? = null,
        val cachedInputTokenCount: Int? = null,
        val uncachedInputTokenCount: Int? = null,
        val outputTokenCount: Int? = null,
        val reasoningTokenCount: Int? = null,
        val status: String = "SUCCESS",
        val participant: String = "MODEL",
        val timestamp: Long,
        val thoughtTimeMs: Long? = null,
        val modelName: String? = null,
        val toolCallJson: String? = null,
        val attachmentMeta: String? = null,
        val runId: String,
        val runSequence: Long,
        val consumedAtPass: Int? = null,
    )

    data class ExportResult(
        val imagesExported: Int = 0
    )

    private data class MediaExportPlan(
        val messageImages: Map<String, List<String>>,
        val messageAttachmentMeta: Map<String, String?>,
        val draftAttachments: Map<String, String?>,
        val sourceToArchiveEntry: Map<String, String>,
        val copiedImageCount: Int,
    )

    private fun openImageStream(imgUri: String): java.io.InputStream? {
        val uri = Uri.parse(imgUri)
        // Handle content:// and file:// URIs
        if (uri.scheme == "content" || uri.scheme == "file") {
            return try { context.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
        }
        // Handle bare file paths (from processImages)
        val file = java.io.File(imgUri)
        if (file.exists()) return try { file.inputStream() } catch (_: Exception) { null }
        return null
    }

    private fun mediaSourceKey(source: String): String {
        val raw = source.removePrefix("file://")
        return when {
            source.startsWith("content://") -> source
            source.startsWith("file://") || File(raw).exists() ->
                runCatching { File(raw).canonicalPath }.getOrElse { File(raw).absolutePath }
            else -> source
        }
    }

    private fun archiveMediaEntry(prefix: String, source: String): String {
        val extension = runCatching {
            val withoutQuery = source.substringBefore('?').substringBefore('#')
            withoutQuery.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        }.getOrNull()
        return buildString {
            append(prefix)
            append(UUID.randomUUID())
            if (extension != null) {
                append('.')
                append(extension)
            }
        }
    }

    private suspend fun forEachMessagePage(
        block: suspend (List<MessageEntity>) -> Unit,
    ) {
        var afterId: String? = null
        while (true) {
            val page = chatDao.getMessagesPage(afterId, MESSAGE_PAGE_SIZE)
            if (page.isEmpty()) break
            block(page)
            afterId = page.last().id
            if (page.size < MESSAGE_PAGE_SIZE) break
        }
    }

    private suspend fun forEachAttachmentReferencePage(
        block: suspend (List<MessageAttachmentReference>) -> Unit,
    ) {
        var afterId: String? = null
        while (true) {
            val page = chatDao.getMessageAttachmentReferencesPage(afterId, MESSAGE_PAGE_SIZE)
            if (page.isEmpty()) break
            block(page)
            afterId = page.last().id
            if (page.size < MESSAGE_PAGE_SIZE) break
        }
    }

    private suspend fun forEachToolMediaReferencePage(
        block: suspend (List<MessageToolMediaReference>) -> Unit,
    ) {
        var afterId: String? = null
        while (true) {
            val page = chatDao.getMessageToolMediaReferencesPage(afterId, MESSAGE_PAGE_SIZE)
            if (page.isEmpty()) break
            block(page)
            afterId = page.last().id
            if (page.size < MESSAGE_PAGE_SIZE) break
        }
    }

    /** Copies one media stream directly into the archive without a heap-sized byte array. */
    private fun copyStreamToZipEntry(
        zip: ZipOutputStream,
        entryName: String,
        input: InputStream?,
    ): Boolean {
        if (input == null) return false
        return input.use { stream ->
            zip.putNextEntry(ZipEntry(entryName))
            try {
                stream.copyTo(zip) > 0L
            } finally {
                zip.closeEntry()
            }
        }
    }

    private fun ZipOutputStream.writeJsonToken(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }

    private suspend fun buildMediaExportPlan(
        zip: ZipOutputStream,
        conversations: List<ChatEntity>,
    ): MediaExportPlan {
        val messageImages = mutableMapOf<String, List<String>>()
        val messageAttachmentMeta = mutableMapOf<String, String?>()
        val draftAttachments = mutableMapOf<String, String?>()
        val sourceToArchiveEntry = mutableMapOf<String, String>()
        var copiedImageCount = 0

        fun copySource(source: String, prefix: String): String? {
            if (source.isBlank()) return null
            val sourceKey = mediaSourceKey(source)
            sourceToArchiveEntry[sourceKey]?.let { return it }
            val entry = archiveMediaEntry(prefix, source)
            val copied = try {
                copyStreamToZipEntry(
                    zip = zip,
                    entryName = entry,
                    input = openImageStream(source),
                )
            } catch (_: Exception) {
                false
            }
            return entry.takeIf { copied }?.also { sourceToArchiveEntry[sourceKey] = it }
        }

        forEachAttachmentReferencePage { page ->
            page.forEach { message ->
                val oldToNewImageIndex = mutableMapOf<Int, Int>()
                val archivedImages = buildList {
                    message.images.forEachIndexed { oldIndex, source ->
                        copySource(source, NativeBackupFormat.IMAGE_MEDIA_PREFIX)?.let { entry ->
                            oldToNewImageIndex[oldIndex] = size
                            add(entry)
                            copiedImageCount++
                        }
                    }
                }
                messageImages[message.id] = archivedImages

                val meta = message.attachmentMeta?.let {
                    runCatching { Json.decodeFromString<AttachmentMeta>(it) }.getOrNull()
                }
                meta?.items
                    ?.asSequence()
                    ?.filter { it.type == "video" }
                    ?.mapNotNull { it.originalUri }
                    ?.forEach { source ->
                        copySource(source, NativeBackupFormat.VIDEO_MEDIA_PREFIX)
                    }
                messageAttachmentMeta[message.id] =
                    NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                    raw = message.attachmentMeta,
                    oldToNewImageIndex = oldToNewImageIndex,
                    archiveEntryForSource = { source ->
                        sourceToArchiveEntry[mediaSourceKey(source)]
                    },
                )
            }
        }

        forEachToolMediaReferencePage { page ->
            page.forEach { message ->
                NativeBackupMediaPolicy.toolImagePaths(message.toolCallJson).forEach { source ->
                    if (copySource(source, NativeBackupFormat.IMAGE_MEDIA_PREFIX) != null) {
                        copiedImageCount++
                    }
                }
            }
        }

        conversations.forEach { conversation ->
            val attachments = conversation.draftAttachments?.let { raw ->
                runCatching { Json.decodeFromString<List<SelectedAttachment>>(raw) }.getOrNull()
            } ?: return@forEach
            val archived = attachments.mapNotNull { attachment ->
                val primarySource = listOfNotNull(
                    attachment.localPath,
                    attachment.uri.takeIf(String::isNotBlank),
                ).firstNotNullOfOrNull { source ->
                    copySource(source, NativeBackupFormat.DRAFT_MEDIA_PREFIX)
                } ?: return@mapNotNull null
                val processedFrames = attachment.processedFrames
                    ?.mapNotNull { copySource(it, NativeBackupFormat.DRAFT_MEDIA_PREFIX) }
                    ?.takeIf(List<String>::isNotEmpty)
                val preRenderedPaths = attachment.preRenderedPaths
                    ?.mapNotNull { copySource(it, NativeBackupFormat.DRAFT_MEDIA_PREFIX) }
                    ?.takeIf(List<String>::isNotEmpty)
                attachment.copy(
                    uri = primarySource,
                    localPath = primarySource,
                    processedFrames = processedFrames,
                    preRenderedPaths = preRenderedPaths,
                )
            }
            draftAttachments[conversation.id] = archived
                .takeIf(List<SelectedAttachment>::isNotEmpty)
                ?.let { Json.encodeToString(it) }
        }

        return MediaExportPlan(
            messageImages = messageImages,
            messageAttachmentMeta = messageAttachmentMeta,
            draftAttachments = draftAttachments,
            sourceToArchiveEntry = sourceToArchiveEntry,
            copiedImageCount = copiedImageCount,
        )
    }

    /**
     * Writes the existing conversations.json shape one entity at a time. The archive format stays
     * compatible, but message bodies are never duplicated into an all-messages DTO list.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeConversationArchive(
        zip: ZipOutputStream,
        conversations: List<ChatEntity>,
        mediaPlan: MediaExportPlan,
        conversationSettings: Map<String, ConversationSettings>,
    ) {
        zip.putNextEntry(ZipEntry(NativeBackupFormat.CONVERSATIONS_ENTRY))
        try {
            zip.writeJsonToken("{\"conversations\":[")
            var first = true
            conversations.forEach { conversation ->
                if (!first) zip.write(','.code)
                first = false
                Json.encodeToStream(
                    ExportChatEntity(
                        id = conversation.id,
                        title = conversation.title,
                        lastUpdated = conversation.lastUpdated,
                        selectedBranchesJson = conversation.selectedBranchesJson,
                        systemPromptId = conversation.systemPromptId,
                        modelId = conversation.modelId,
                        taskId = conversation.taskId,
                        origin = conversation.origin,
                        graduated = conversation.graduated,
                        selectedRunBranchesJson = conversation.selectedRunBranchesJson,
                        draftText = conversation.draftText,
                        draftAttachments = mediaPlan.draftAttachments[conversation.id],
                        conversationSettings = conversationSettings[conversation.id],
                    ),
                    zip,
                )
            }

            zip.writeJsonToken("],\"runs\":[")
            first = true
            for (conversation in conversations) {
                for (run in chatDao.getRunsForConversation(conversation.id).first()) {
                    if (!first) zip.write(','.code)
                    first = false
                    Json.encodeToStream(
                        ExportRunEntity(
                            id = run.id,
                            conversationId = run.conversationId,
                            parentRunId = run.parentRunId,
                            status = run.status.name,
                            startedAt = run.startedAt,
                            lastCheckpointAt = run.lastCheckpointAt,
                            stopRequestedAt = run.stopRequestedAt,
                            endedAt = run.endedAt,
                            endReason = run.endReason?.name,
                            currentPass = run.currentPass,
                            legacyAmbiguous = run.legacyAmbiguous,
                        ),
                        zip,
                    )
                }
            }

            zip.writeJsonToken("],\"messages\":[")
            first = true
            forEachMessagePage { page ->
                page.forEach { message ->
                    if (!first) zip.write(','.code)
                    first = false
                    Json.encodeToStream(
                        ExportMessageEntity(
                            id = message.id,
                            conversationId = message.conversationId,
                            parentId = message.parentId,
                            text = message.text,
                            images = mediaPlan.messageImages[message.id] ?: emptyList(),
                            thoughts = message.thoughts,
                            thoughtTitle = message.thoughtTitle,
                            tokenCount = message.tokenCount,
                            inputTokenCount = message.inputTokenCount,
                            cachedInputTokenCount = message.cachedInputTokenCount,
                            uncachedInputTokenCount = message.uncachedInputTokenCount,
                            outputTokenCount = message.outputTokenCount,
                            reasoningTokenCount = message.reasoningTokenCount,
                            status = message.status.name,
                            participant = message.participant.name,
                            timestamp = message.timestamp,
                            thoughtTimeMs = message.thoughtTimeMs,
                            modelName = message.modelName,
                            toolCallJson = NativeBackupMediaPolicy.rewriteToolImagePathsForExport(
                                raw = message.toolCallJson,
                                archiveEntryForSource = { source ->
                                    mediaPlan.sourceToArchiveEntry[mediaSourceKey(source)]
                                },
                            ),
                            attachmentMeta = mediaPlan.messageAttachmentMeta[message.id],
                            runId = message.runId,
                            runSequence = message.runSequence,
                            consumedAtPass = message.consumedAtPass,
                        ),
                        zip,
                    )
                }
            }

            zip.writeJsonToken("],\"tasks\":[")
            first = true
            chatDao.getAllTasksList().forEach { task ->
                if (!first) zip.write(','.code)
                first = false
                Json.encodeToStream(
                    ExportTaskEntity(
                        id = task.id,
                        name = task.name,
                        prompt = task.prompt,
                        systemPrompt = task.systemPrompt,
                        modelId = task.modelId,
                        cronExpr = task.cronExpr,
                        runAt = task.runAt,
                        createdAt = task.createdAt,
                        lastRunAt = task.lastRunAt,
                    ),
                    zip,
                )
            }

            zip.writeJsonToken("],\"loops\":[")
            first = true
            chatDao.getAllLoopsList().forEach { loop ->
                if (!first) zip.write(','.code)
                first = false
                val sanitized = sanitizeImportedLoop(loop)
                Json.encodeToStream(
                    ExportLoopEntity(
                        conversationId = sanitized.conversationId,
                        intervalMs = sanitized.intervalMs,
                        prompt = sanitized.prompt,
                        cycleCount = sanitized.cycleCount,
                        maxCycles = sanitized.maxCycles,
                    ),
                    zip,
                )
            }
            zip.writeJsonToken("]}")
        } finally {
            zip.closeEntry()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun export(
        uri: Uri,
        categories: Set<ExportCategory>,
        includeApiKeys: Boolean,
        onProgress: (Float) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersion = appInfo.versionName ?: "unknown"
        val exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        val manifest = ExportManifest(
            version = NativeBackupFormat.CURRENT_VERSION,
            appVersion = appVersion,
            exportedAt = exportedAt,
            categories = categories.map { it.manifestKey },
            hasApiKeys = includeApiKeys && categories.contains(ExportCategory.API_KEYS)
        )

        var imagesExportedTotal = 0
        val totalSteps = categories.size + 1 // +1 for manifest
        var completed = 0
        fun step() { completed++; onProgress(completed.toFloat() / totalSteps) }

        val rawOutput = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open the selected backup destination")
        try {
            rawOutput.use { raw ->
                val zip = ZipOutputStream(BufferedOutputStream(raw))

                // Manifest
                zip.putNextEntry(ZipEntry(NativeBackupFormat.MANIFEST_ENTRY))
                Json.encodeToStream(manifest, zip)
                zip.closeEntry()
                step()

                // Conversations
                if (ExportCategory.CONVERSATIONS in categories) {
                    val conversations = chatDao.getAllConversationsList()
                    val mediaPlan = buildMediaExportPlan(zip, conversations)
                    imagesExportedTotal += mediaPlan.copiedImageCount
                    writeConversationArchive(
                        zip = zip,
                        conversations = conversations,
                        mediaPlan = mediaPlan,
                        conversationSettings = settingsManager.conversationSettings.first(),
                    )
                    step()
                }

                // Memories
                if (ExportCategory.MEMORIES in categories) {
                    val activeMemory = memoryManager.getActiveMemory()
                    if (activeMemory.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("memories/active_memory.md"))
                        zip.write(activeMemory.toByteArray())
                        zip.closeEntry()
                    }
                    for (file in memoryManager.listFiles()) {
                        val content = memoryManager.readFile(file.name)
                        zip.putNextEntry(ZipEntry("memories/memory_db/${file.name}"))
                        zip.write(content.toByteArray())
                        zip.closeEntry()
                    }
                    val metaJson = memoryManager.getMetaJson()
                    if (metaJson != "{}") {
                        zip.putNextEntry(ZipEntry("memories/memory_db/memory_meta.json"))
                        zip.write(metaJson.toByteArray())
                        zip.closeEntry()
                    }
                    step()
                }

                // System Prompts
                if (ExportCategory.SYSTEM_PROMPTS in categories) {
                    val prompts = settingsManager.systemPrompts.first()
                    zip.putNextEntry(ZipEntry(NativeBackupFormat.SYSTEM_PROMPTS_ENTRY))
                    Json.encodeToStream(prompts, zip)
                    zip.closeEntry()
                    step()
                }

                // User Skills (self-authored SKILL.md files)
                if (ExportCategory.SKILLS in categories) {
                    userSkillStore.listSkillFiles().forEach { file ->
                        zip.putNextEntry(ZipEntry(NativeBackupFormat.SKILLS_ENTRY_PREFIX + file.name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    step()
                }

                // Settings
                if (ExportCategory.SETTINGS in categories) {
                    val fontFile = settingsManager.customFontPath.first()
                        .takeIf(String::isNotBlank)
                        ?.let(::File)
                        ?.takeIf(File::isFile)
                    if (fontFile != null) {
                        zip.putNextEntry(ZipEntry(NativeBackupFormat.CUSTOM_FONT_ENTRY))
                        fontFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    val settings = PortableSettingsArchive.toJsonObject(
                        sm = settingsManager,
                        customFontIncluded = fontFile != null,
                    )
                    zip.putNextEntry(ZipEntry(NativeBackupFormat.SETTINGS_ENTRY))
                    Json.encodeToStream(settings, zip)
                    zip.closeEntry()
                    step()
                }

                // API Keys (opt-in)
                if (includeApiKeys && ExportCategory.API_KEYS in categories) {
                    val keys = NativeBackupSecretsPolicy.capture(settingsManager)
                    zip.putNextEntry(ZipEntry(NativeBackupFormat.SECRETS_ENTRY))
                    Json.encodeToStream(keys, zip)
                    zip.closeEntry()
                    step()
                }

                zip.finish()
                zip.flush()
            }
        } catch (error: Exception) {
            // 导出中断（磁盘写满/媒体流抛错等）时会留下一个截断的 zip。不清除的话，
            // 用户可能把半截文件误当有效备份归档，直到导入时才发现数据缺失。
            // SAF Uri 的 delete 由 DocumentsProvider 实现，失败（不支持/权限）则静默保留原文件。
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onFailure {
                    com.lxseek.chat.util.DebugLog.w(
                        "DataExporter",
                        "Failed to remove the partial export at $uri",
                        it,
                    )
                }
            throw error
        }

        onProgress(1f)
        ExportResult(imagesExported = imagesExportedTotal)
    }
}
