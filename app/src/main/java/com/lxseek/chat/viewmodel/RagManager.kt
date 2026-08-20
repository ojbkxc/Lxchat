package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.R
import com.lxseek.chat.api.EmbeddingClient
import com.lxseek.chat.api.LlamaEngine
import com.lxseek.chat.api.ProviderDefaults
import com.lxseek.chat.api.local.LocalProvider
import com.lxseek.chat.data.EmbeddingCacheLocks
import com.lxseek.chat.data.EmbeddingIndexer
import com.lxseek.chat.data.EmbeddingModelConfig
import com.lxseek.chat.data.EmbeddingModelType
import com.lxseek.chat.data.local.EmbeddingEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

internal fun isEmbeddingMessageIdEligible(messageId: String): Boolean =
    !messageId.startsWith(Constants.COMPACT_MSG_PREFIX) &&
        !messageId.startsWith(Constants.TOOL_MSG_PREFIX) &&
        !messageId.startsWith(Constants.RESULT_MSG_PREFIX)

/**
 * Owns the embedding subsystem: embedding-model CRUD, the RAG cache (per-model
 * embedding of all messages), single-message indexing, and embedding key/base-URL
 * resolution.
 *
 * Extracted out of [ChatViewModel] (Phase E4). The whole subsystem moves together
 * because embedding-model deletion and caching coordinate on the same per-model
 * lock ([EmbeddingCacheLocks]) and cancellation handle ([cacheJobs]). ChatViewModel
 * keeps thin delegating wrappers for the UI-facing API.
 */
class RagManager(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val localProvider: LocalProvider,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val emitSnackbar: suspend (SnackbarEvent) -> Unit,
) {
    val activeEmbeddingModel: StateFlow<EmbeddingModelConfig?> =
        combine(settings.embeddingModels, settings.activeEmbeddingModelId) { models, id ->
            models.find { it.id == id }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _cachingProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val cachingProgress: StateFlow<Map<String, Pair<Int, Int>>> = _cachingProgress.asStateFlow()
    // In-app caching coroutine per model, so deleteEmbeddingModel can cancel an
    // in-flight cache instead of queueing behind it on the mutex.
    private val cacheJobs = ConcurrentHashMap<String, Job>()
    private val _cacheCounts = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val cacheCounts: StateFlow<Map<String, Pair<Int, Int>>> = _cacheCounts.asStateFlow()

    fun loadCacheCounts() {
        scope.launch(Dispatchers.IO) { refreshCacheCounts() }
    }

    private suspend fun refreshCacheCounts() {
        val total = conversations.getIndexableMessageCount()
        val counts = settings.embeddingModels.value.associate { model ->
            val cached = conversations.getEmbeddingCountByModel(model.id).coerceAtMost(total)
            model.id to (cached to total)
        }
        _cacheCounts.value = counts
    }

    // ── Embedding-model CRUD ──────────────────────────────────────

    fun addEmbeddingModel(config: EmbeddingModelConfig) {
        scope.launch {
            val wasEmpty = settings.embeddingModels.value.isEmpty()
            val models = settings.embeddingModels.value.toMutableList()
            models.add(config)
            settings.saveEmbeddingModels(models)
            if (wasEmpty) {
                settings.setActiveEmbeddingModelId(config.id)
            }
            refreshCacheCounts()
        }
    }

    fun deleteEmbeddingModel(id: String) {
        // Stop the background WorkManager cache job for this model right away. cancel()
        // is async, so we await termination below before deleting rows — otherwise a
        // worker batch in flight would re-insert embeddings for the now-deleted model.
        val workManager = androidx.work.WorkManager.getInstance(appContext)
        val workName = com.lxseek.chat.service.EmbeddingCacheWorker.workNameFor(id)
        workManager.cancelUniqueWork(workName)

        scope.launch(Dispatchers.IO) {
            // Stop the in-app caching coroutine and wait for it to fully unwind (it
            // holds the model's cache lock for its whole loop, so cancel+join — not the
            // lock — is what actually halts it before we take the mutex ourselves).
            cacheJobs.remove(id)?.let { it.cancel(); it.join() }

            // Deterministically wait until the worker has reached a finished state
            // (CANCELLED/SUCCEEDED/FAILED) so no writer remains. Empty info list (work
            // never existed) satisfies the predicate immediately. Bounded so a stuck
            // worker can't hang deletion.
            withTimeoutOrNull(10_000) {
                workManager.getWorkInfosForUniqueWorkFlow(workName)
                    .first { infos -> infos.all { it.state.isFinished } }
            }

            EmbeddingCacheLocks.forModel(id).withLock {
                val model = settings.embeddingModels.value.find { it.id == id }
                if (model?.type == EmbeddingModelType.LOCAL && model.localFilePath.isNotBlank()) {
                    java.io.File(model.localFilePath).delete()
                }
                conversations.deleteEmbeddingsByModel(id)
                val models = settings.embeddingModels.value.filter { it.id != id }
                settings.saveEmbeddingModels(models)
                if (settings.activeEmbeddingModelId.value == id && models.isNotEmpty()) {
                    settings.setActiveEmbeddingModelId(models.first().id)
                }
                _cachingProgress.update { it - id }
                refreshCacheCounts()
            }
            EmbeddingCacheLocks.remove(id)
        }
    }

    fun renameEmbeddingModel(id: String, newName: String, batchSize: Int? = null) {
        scope.launch {
            val models = settings.embeddingModels.value.map {
                if (it.id == id) it.copy(name = newName, batchSize = batchSize ?: it.batchSize) else it
            }
            settings.saveEmbeddingModels(models)
        }
    }

    fun setActiveEmbeddingModel(id: String) {
        if (id == settings.activeEmbeddingModelId.value) return
        scope.launch(Dispatchers.IO) {
            settings.setActiveEmbeddingModelId(id)
            val model = settings.embeddingModels.value.find { it.id == id } ?: return@launch
            val total = conversations.getIndexableMessageCount()
            val cached = conversations.getEmbeddingCountByModel(id)
            val notCached = (total - cached).coerceAtLeast(0)
            if (notCached > 0) {
                if (cachingProgress.value.containsKey(id)) {
                    emitSnackbar(SnackbarEvent(appContext.getString(R.string.embedding_model_caching, model.name)))
                } else {
                    emitSnackbar(SnackbarEvent(
                        appContext.getString(R.string.messages_not_cached, notCached, total),
                        appContext.getString(R.string.cache_now)
                    ) { cacheMessagesForModel(id) })
                }
            }
        }
    }

    // ── RAG cache ─────────────────────────────────────────────────

    fun cacheMessagesForModel(modelId: String, recache: Boolean = false, silent: Boolean = false) {
        val workManager = androidx.work.WorkManager.getInstance(appContext)
        val workName = com.lxseek.chat.service.EmbeddingCacheWorker.workNameFor(modelId)
        val job = scope.launch(Dispatchers.IO) {
            EmbeddingCacheLocks.forModel(modelId).withLock {
                // Process-death continuation: enqueued AFTER this runner holds the lock, so
                // the worker can never outrace it (it blocks on the same process-wide lock).
                // It only does real work if the process dies mid-cache and WorkManager
                // restarts it in a fresh process; every in-process exit cancels it below.
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.lxseek.chat.service.EmbeddingCacheWorker>()
                    .setInputData(androidx.work.Data.Builder()
                        .putString(com.lxseek.chat.service.EmbeddingCacheWorker.KEY_MODEL_ID, modelId)
                        .build())
                    .addTag(com.lxseek.chat.service.EmbeddingCacheWorker.TAG)
                    .build()
                workManager.enqueueUniqueWork(workName, androidx.work.ExistingWorkPolicy.REPLACE, workRequest)
                try {
                    runCacheLoop(modelId, recache, silent)
                } finally {
                    // Every in-process exit (done, early return, error, cancellation) makes the
                    // continuation worker redundant. Process death skips finally — exactly the
                    // one case where the worker must survive and resume.
                    workManager.cancelUniqueWork(workName)
                }
            }
        }
        // Track the job so deleteEmbeddingModel can cancel an in-flight cache; self-remove
        // on completion (guard against clobbering a newer job for the same model).
        cacheJobs[modelId] = job
        job.invokeOnCompletion { cacheJobs.remove(modelId, job) }
    }

    /** The cache loop proper. Caller must hold [EmbeddingCacheLocks] for [modelId]. */
    private suspend fun runCacheLoop(modelId: String, recache: Boolean, silent: Boolean) {
        val model = settings.embeddingModels.value.find { it.id == modelId } ?: return
        if (recache) {
            conversations.deleteEmbeddingsByModel(modelId)
        }
        val total = conversations.getIndexableMessageCount()
        if (total == 0) {
            if (!silent) emitSnackbar(SnackbarEvent(appContext.getString(R.string.no_messages_to_cache)))
            refreshCacheCounts()
            return
        }
        val alreadyDone = conversations.getEmbeddingCountByModel(modelId).coerceAtMost(total)
        if (alreadyDone >= total) {
            if (!silent) emitSnackbar(SnackbarEvent(appContext.getString(R.string.all_messages_already_cached, total)))
            refreshCacheCounts()
            return
        }

        var succeeded = 0
        var attempted = 0
        val batchSize = model.batchSize.coerceIn(1, 100)
        val remoteConfig = if (model.type == EmbeddingModelType.LOCAL) {
            if (!LlamaEngine.isModelReady(model.localFilePath)) {
                if (!silent) emitSnackbar(SnackbarEvent(appContext.getString(R.string.local_model_not_found)))
                return
            }
            null
        } else {
            val apiKey = model.remoteApiKey.ifBlank { resolveEmbeddingApiKey() ?: "" }
            if (apiKey.isBlank()) {
                if (!silent) emitSnackbar(SnackbarEvent(appContext.getString(R.string.no_api_key_configured)))
                return
            }
            apiKey to model.remoteBaseUrl.ifBlank { resolveEmbeddingBaseUrl() }
        }

        _cachingProgress.update { it + (modelId to (alreadyDone to total)) }
        try {
            var afterMessageId: String? = null
            while (true) {
                if (settings.embeddingModels.value.none { it.id == modelId }) return
                val batch = conversations.getUnembeddedMessagesPage(
                    modelId = modelId,
                    afterId = afterMessageId,
                    limit = batchSize,
                )
                if (batch.isEmpty()) break
                afterMessageId = batch.last().id

                val texts = batch.map { it.text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH) }
                val embeddings = if (model.type == EmbeddingModelType.LOCAL) {
                    LlamaEngine.computeEmbeddings(texts, model.localFilePath) {
                        localProvider.releaseEngineBlocking()
                    }
                } else {
                    val (apiKey, baseUrl) = requireNotNull(remoteConfig)
                    EmbeddingClient.computeEmbeddings(
                        texts, apiKey, model.remoteModelName, baseUrl
                    )
                }

                attempted += batch.size
                batch.zip(embeddings).forEach { (message, embedding) ->
                    if (embedding != null) {
                        conversations.upsertEmbedding(EmbeddingEntity(
                            messageId = message.id,
                            modelId = modelId,
                            embedding = EmbeddingIndexer.floatsToBytes(embedding),
                            chunkText = message.text.take(Constants.MAX_CHUNK_TEXT_LENGTH),
                            dimension = embedding.size,
                        ))
                        succeeded++
                    }
                }
                val completed = (alreadyDone + attempted).coerceAtMost(total)
                _cachingProgress.update { it + (modelId to (completed to total)) }
            }
        } finally {
            _cachingProgress.update { it - modelId }
        }
        val failed = attempted - succeeded
        if (!silent) {
            if (failed == 0) {
                emitSnackbar(SnackbarEvent(appContext.getString(R.string.all_messages_cached, total)))
            } else {
                emitSnackbar(SnackbarEvent(
                    appContext.getString(R.string.cached_partial_failed, succeeded, attempted, failed),
                    appContext.getString(R.string.retry)
                ) { cacheMessagesForModel(modelId) })
            }
        }
        conversations.deleteOrphanedEmbeddings()
        refreshCacheCounts()
    }

    // ── Single-message indexing ───────────────────────────────────

    /**
     * The single gate for incremental indexing: the user's "Caching" switch plus a configured
     * embedding model. Deliberately NOT gated on the active search method — the setting reads
     * "automatically index new messages", and gating on `searchMethod == RAG` meant the default
     * ("keyword") silently indexed nothing, so switching to RAG later found an empty cache.
     * Caching is what makes RAG *available*; it must not depend on RAG already being selected.
     */
    private val autoIndexEnabled: Boolean
        get() = settings.autoCacheEnabled.value && activeEmbeddingModel.value != null

    /** Index one message if [autoIndexEnabled]. Safe to call from any persist path. */
    fun indexMessageForRag(messageId: String, text: String) {
        if (!isEmbeddingMessageIdEligible(messageId)) return
        if (!autoIndexEnabled) return
        scope.launch(Dispatchers.IO) {
            indexMessageForRagNow(messageId, text)
        }
    }

    private suspend fun indexMessageForRagNow(messageId: String, text: String) {
        if (!conversations.isMessageSearchable(messageId)) {
            // Task executions remain private to their Task History. Purge any stale pre-fix
            // embedding as well as refusing the new write.
            conversations.deleteEmbedding(messageId)
            DebugLog.d("LxChatVM", "RAG index: hidden/non-searchable message, skipping $messageId")
            return
        }
        val model = activeEmbeddingModel.value
        if (model == null) {
            DebugLog.d("LxChatVM", "RAG index: no active model, skipping $messageId")
            return
        }
        DebugLog.d("LxChatVM", "RAG index: indexing $messageId with model '${model.name}'")
        val toEmbed = text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH)
        val embedding: FloatArray? = if (model.type == EmbeddingModelType.LOCAL) {
            if (!LlamaEngine.isModelReady(model.localFilePath)) {
                DebugLog.w("LxChatVM", "RAG index: local model not ready, skipping")
                return
            }
            LlamaEngine.computeEmbedding(toEmbed, model.localFilePath) {
                localProvider.releaseEngineBlocking()
            }
        } else {
            val apiKey = model.remoteApiKey.ifBlank { resolveEmbeddingApiKey() ?: "" }
            if (apiKey.isBlank()) {
                DebugLog.w("LxChatVM", "RAG index: no API key, skipping")
                return
            }
            val baseUrl = model.remoteBaseUrl.ifBlank { resolveEmbeddingBaseUrl() }
            EmbeddingClient.computeEmbedding(toEmbed, apiKey, model.remoteModelName, baseUrl)
        }
        if (embedding != null) {
            val stored = conversations.upsertEmbeddingIfSearchable(EmbeddingEntity(
                messageId = messageId,
                modelId = model.id,
                embedding = EmbeddingIndexer.floatsToBytes(embedding),
                chunkText = text.take(Constants.MAX_CHUNK_TEXT_LENGTH),
                dimension = embedding.size
            ))
            if (stored) {
                DebugLog.d("LxChatVM", "RAG index: stored embedding (dim=${embedding.size}) for $messageId")
            } else {
                DebugLog.d("LxChatVM", "RAG index: visibility changed before write, skipped $messageId")
            }
        }
    }

    // ── Embedding key / base-URL resolution ───────────────────────

    fun resolveEmbeddingApiKey(): String? {
        val keys = settings.apiKeys.value
        for (entry in keys) {
            if (ProviderDefaults.isOpenAiCompatibleEmbedding(entry.provider)) {
                return entry.key
            }
        }
        return keys.firstOrNull()?.key
    }

    fun resolveEmbeddingBaseUrl(): String {
        return ProviderDefaults.openAiCompatibleBaseUrl(settings.providerBaseUrls.value)
    }

    data class EmbeddingKeyInfo(val provider: String, val key: String, val baseUrl: String)

    /** Exact match only — for UI display in the embedding dialog. No fallback. */
    fun resolveEmbeddingKeyForProviderExact(targetProvider: String): EmbeddingKeyInfo? {
        val keys = settings.apiKeys.value
        val match = keys.find { it.provider.equals(targetProvider, ignoreCase = true) }
        if (match != null) {
            val baseUrl = settings.providerBaseUrls.value[match.provider] ?: ProviderDefaults.embeddingBaseUrl(match.provider)
            return EmbeddingKeyInfo(match.provider, match.key, baseUrl)
        }
        return null
    }
}
