package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.LocalModelSerializer
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.data.MemoryImportanceScorer
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.ContextBudget
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * LLM-driven memory extraction and consolidation, mirroring the mem0 flow:
 *
 *  1. Extract independent, self-contained facts the user actually stated (extraction pass).
 *  2. Given existing memory files + the new facts, decide per-fact whether to ADD / UPDATE an
 *     existing file / DELETE a now-obsolete one / do nothing (consolidation pass).
 *  3. Apply the decisions to [MemoryManager] using its existing file CRUD, so no persistence
 *     schema change is required.
 *
 * It resolves a provider and API key exactly like [ConversationTitleGenerator]; it is safe to
 * run after a generation has released the single-engine lock (e.g. from a memory tool), not
 * re-entrantly inside an in-flight local-model generation.
 */
class AutoMemoryExtractor(
    private val memoryManager: MemoryManager,
    private val settings: SettingsRepository,
    private val providers: ProviderRegistry,
) {
    sealed interface Result {
        data class Success(val added: Int, val updated: Int, val deleted: Int, val none: Int) : Result
        data class Failure(val reason: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Persist durable facts found in [conversationText], consolidating against existing memories. */
    suspend fun extractAndApply(conversationText: String): Result {
        if (conversationText.isBlank()) return Result.Failure("No conversation text provided")

        settings.awaitInitialLoad()
        providers.awaitInitialSync()

        // 优先复用上下文压缩辅助模型（更便宜），未配置或不可用时回退到主聊天模型。
        val resolved = resolveModel()
            ?: return Result.Failure("No model available for memory extraction")

        val extractConfig = buildConfig(systemPrompt = EXTRACTION_SYSTEM, resolved.activeKey, resolved.modelId, resolved.providerName)
        val extractResponse = try {
            runCompletion(resolved.provider, resolved.providerName, extractConfig, extractionUser(conversationText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Extraction failed", e)
            return Result.Failure(e.localizedMessage ?: "Extraction failed")
        }
        val facts = parseExtraction(extractResponse)
        if (facts.isEmpty()) return Result.Success(0, 0, 0, 0)

        val existing = existingMemories()
        val decisions = try {
            val consolidateConfig = buildConfig(CONSOLIDATION_SYSTEM, resolved.activeKey, resolved.modelId, resolved.providerName)
            parseConsolidation(
                runCompletion(resolved.provider, resolved.providerName, consolidateConfig, consolidationUser(facts, existing)),
                existing,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Consolidation is best-effort: if it fails, persist everything as new facts rather
            // than dropping the extraction result.
            DebugLog.e(TAG, "Consolidation failed, falling back to ADD-all", e)
            return applyAdd(facts)
        }

        return applyDecisions(decisions, existing)
    }

    /**
     * 解析用于记忆提取/合并的模型。
     *
     * 优先使用上下文压缩辅助模型（[SettingsRepository.contextCompactModel]），未配置或不可用时
     * 回退到主聊天模型（[SettingsRepository.selectedModel]）。辅助模型的 provider 可能与主模型
     * 不同，因此 provider / apiKey / baseUrl 均按解析出的 provider 单独取用。
     */
    private suspend fun resolveModel(): ResolvedModel? {
        val auxiliary = settings.contextCompactModel.value?.takeIf { it.isNotBlank() }
        val primary = settings.selectedModel.value.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(auxiliary, primary)
        for (prefixedId in candidates) {
            val providerName = providers.providerForModel(prefixedId)
            val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
                ?: settings.resolveActiveKey(providerName).orEmpty()
            if (!providers.isConfigured(providerName, activeKey)) continue
            val provider = providers.getInstanceOrNull(providerName) ?: continue
            return ResolvedModel(
                provider = provider,
                providerName = providerName,
                activeKey = activeKey,
                modelId = ModelId.parse(prefixedId).modelName,
            )
        }
        return null
    }

    private data class ResolvedModel(
        val provider: com.lxseek.chat.api.LlmProvider,
        val providerName: String,
        val activeKey: String,
        val modelId: String,
    )

    private fun buildConfig(systemPrompt: String, activeKey: String, modelId: String, providerName: String): ProviderConfig =
        ProviderConfig(
            apiKey = activeKey,
            modelId = modelId,
            systemPrompt = systemPrompt,
            maxContextWindow = ContextBudget.MIN_TOKENS,
            thinkingEnabled = false,
            baseUrl = providers.getEffectiveBaseUrl(providerName),
        )

    private fun message(text: String): ChatMessage =
        ChatMessage(text = text, participant = Participant.USER, status = MessageStatus.SUCCESS)

    private fun extractionUser(conversationText: String): List<ChatMessage> =
        listOf(
            message(
                "The recent conversation excerpt is below.\n\n--- CONVERSATION ---\n" +
                    conversationText.take(MAX_EXTRACT_CHARS) + "\n--- END ---\n\n" +
                    "Extract the user-stated durable facts and return them as JSON."
            )
        )

    private fun consolidationUser(facts: List<ExtractedFact>, existing: List<ExistingMemory>): List<ChatMessage> {
        val existingText = if (existing.isEmpty()) "No existing memories."
        else existing.joinToString("\n") { "${it.index}: ${it.text}" }
        // 每个 NEW fact 附带其分类标签，便于 LLM 在 ADD 时继承、在 UPDATE/DELETE 时考虑分类。
        val factsText = facts.withIndex().joinToString("\n") { (i, f) ->
            "F${i + 1} [${f.category.name.lowercase()}]: ${f.text}"
        }
        return listOf(
            message(
                "EXISTING MEMORIES:\n$existingText\n\nNEW FACTS TO INTEGRATE:\n$factsText\n\n" +
                    "Return ONLY the JSON with the integration decisions."
            )
        )
    }

    private suspend fun runCompletion(
        provider: com.lxseek.chat.api.LlmProvider,
        providerName: String,
        config: ProviderConfig,
        messages: List<ChatMessage>,
    ): String {
        var text = ""
        var error: String? = null
        suspend fun collect() {
            provider.generateResponse(messages, config).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> text += event.text
                    is StreamEvent.Error -> error = event.message
                    else -> Unit
                }
            }
        }
        if (providerName == Constants.PROVIDER_LOCAL) {
            LocalModelSerializer.mutex.withLock {
                withContext(Dispatchers.IO) { collect() }
            }
        } else {
            collect()
        }
        error?.let { throw IllegalStateException(it) }
        return text
    }

    // ── Parsing ──────────────────────────────────────────────────

    private data class ExtractedFact(
        val text: String,
        val category: MemoryImportanceScorer.Category,
    )

    private fun parseExtraction(raw: String): List<ExtractedFact> {
        val root = parseJsonObject(raw) ?: return emptyList()
        val arr = (root["memory"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val text = (obj["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val category = MemoryImportanceScorer.Category.fromString(
                (obj["category"] as? JsonPrimitive)?.content
            )
            ExtractedFact(text, category)
        }
    }

    private data class Decision(
        val event: String,
        val id: String,
        val text: String,
        val category: MemoryImportanceScorer.Category,
    )

    private fun parseConsolidation(raw: String, existing: List<ExistingMemory>): List<Decision> {
        val root = parseJsonObject(raw) ?: return emptyList()
        val arr = (root["memory"] as? JsonArray) ?: return emptyList()
        val knownIndices = existing.map { it.index }.toSet()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val event = (obj["event"] as? JsonPrimitive)?.content?.uppercase()?.trim().orEmpty()
            if (event !in EVENTS) return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content?.trim()?.uppercase().orEmpty()
            val text = (obj["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
            // An action on an unknown id is dropped: it can neither be applied nor trusted as ADD.
            if (event != "ADD" && id !in knownIndices) return@mapNotNull null
            val category = MemoryImportanceScorer.Category.fromString(
                (obj["category"] as? JsonPrimitive)?.content
            )
            Decision(event, id, text, category)
        }
    }

    /** Tolerant JSON extraction: grabs the outermost object even if wrapped in fenced prose. */
    private fun parseJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(trimmed.substring(start, end + 1)).jsonObject }
            .getOrNull()
    }

    // ── Apply to MemoryManager ───────────────────────────────────

    private class ExistingMemory(val index: String, val fileName: String, val text: String, val description: String)

    private fun existingMemories(): List<ExistingMemory> =
        memoryManager.listFiles().take(MAX_EXISTING_MEMORIES).mapIndexedNotNull { i, info ->
            // 文件内容为空时回退到 description，但需剥离 [type:][score:] 标签，避免把元标签喂给 LLM。
            val body = runCatching { memoryManager.readFile(info.name) }.getOrDefault("")
                .trim().ifBlank { MemoryImportanceScorer.stripTags(info.description) }
            ExistingMemory("M${i + 1}", info.name, body.take(MAX_EXISTING_CHARS), info.description)
        }

    private fun applyAdd(facts: List<ExtractedFact>): Result {
        val now = System.currentTimeMillis()
        val taken = memoryManager.listFiles().map { it.name.removeSuffix(".md") }.toMutableSet()
        var added = 0
        for (fact in facts) {
            if (fact.text.isBlank()) continue
            val name = nextFreeName(fact, taken)
            val description = encodeDescription(fact.category, now)
            if (runCatching { memoryManager.createFile(name, fact.text, description) }.isSuccess) {
                taken.add(name)
                added++
            }
        }
        return Result.Success(added, 0, 0, 0)
    }

    private fun applyDecisions(decisions: List<Decision>, existing: List<ExistingMemory>): Result {
        val byIndex = existing.associateBy { it.index }
        val now = System.currentTimeMillis()
        val taken = memoryManager.listFiles().map { it.name.removeSuffix(".md") }.toMutableSet()
        var added = 0
        var updated = 0
        var deleted = 0
        var none = 0
        for (d in decisions) {
            when (d.event) {
                "ADD" -> {
                    if (d.text.isBlank()) continue
                    val fact = ExtractedFact(d.text, d.category)
                    val name = nextFreeName(fact, taken)
                    val description = encodeDescription(d.category, now)
                    if (runCatching { memoryManager.createFile(name, d.text, description) }.isSuccess) {
                        taken.add(name)
                        added++
                    }
                }
                "UPDATE" -> {
                    val target = byIndex[d.id] ?: continue
                    if (d.text.isBlank()) continue
                    // 分类优先用决策给出的；若决策未指定（OTHER）则沿用原文件分类。
                    val category = if (d.category != MemoryImportanceScorer.Category.OTHER) d.category
                        else MemoryImportanceScorer.parseCategory(target.description)
                    val description = encodeDescription(category, now)
                    if (runCatching { memoryManager.editFile(target.fileName, content = d.text, description = description) }.isSuccess) {
                        updated++
                    }
                }
                "DELETE" -> {
                    val target = byIndex[d.id] ?: continue
                    if (runCatching { memoryManager.deleteFile(target.fileName) }.isSuccess) {
                        deleted++
                    }
                }
                else -> none++
            }
        }
        return Result.Success(added, updated, deleted, none)
    }

    /** 把分类与初始评分编码为 description 标签前缀。 */
    private fun encodeDescription(category: MemoryImportanceScorer.Category, now: Long): String {
        val initialScore = MemoryImportanceScorer.score(
            MemoryImportanceScorer.MemoryEntry(category, now, 0), now
        )
        return MemoryImportanceScorer.encodeDescription(category, initialScore)
    }

    private fun nextFreeName(fact: ExtractedFact, taken: MutableSet<String>): String {
        // 文件名加分类前缀（如 pref-likes-coffee），便于人工浏览与按类检索。
        val prefix = fact.category.filePrefix
        val slug = fact.text.take(24)
            .replace(Regex("""[^\p{L}\p{N} _-]"""), "")
            .trim()
            .replace(Regex("""\s+"""), "-")
            .take(20)
            .ifBlank { "fact" }
        val base = "$prefix-$slug"
        var i = 1
        var candidate = base
        while (candidate in taken) {
            i++
            candidate = "$base-$i"
        }
        return candidate
    }

    companion object {
        private const val TAG = "AutoMemoryExtractor"
        private val EVENTS = setOf("ADD", "UPDATE", "DELETE", "NONE")
        private const val MAX_EXTRACT_CHARS = 6_000
        private const val MAX_EXISTING_MEMORIES = 50
        private const val MAX_EXISTING_CHARS = 500

        // Source: mem0 ADDITIVE_EXTRACTION_PROMPT (extraction of standalone user-stated facts).
        private const val EXTRACTION_SYSTEM =
            "You are part of an interview memory system that extracts and stores facts about the user from a conversation. " +
                "Analyze the conversation and extract facts the USER explicitly stated, such as: preferences, " +
                "dislikes, personal details, events, constraints, schedules, associations, and other durable information. " +
                "Rules: " +
                "1. Only extract facts the user explicitly stated. Do NOT invent, infer, or assume. " +
                "2. Each fact must be self-contained and understandable without the surrounding context. " +
                "3. Facts must be 15 to 80 characters — compact but complete. " +
                "4. Never combine multiple facts into one entry. " +
                "5. Write facts in the SAME language the user used. " +
                "6. Skip facts about the assistant, system internals, or transient chit-chat that is not durable. " +
                "7. Classify each fact with a \"category\" field: one of preference, fact, event, skill, contact, other. " +
                "Respond with ONLY a JSON object in this exact shape: " +
                "{\"memory\": [{\"text\": \"fact 1\", \"category\": \"preference\"}, {\"text\": \"fact 2\", \"category\": \"fact\"}]}"

        // Source: mem0 DEFAULT_UPDATE_MEMORY_PROMPT (ADD/UPDATE/DELETE/NONE consolidation).
        private const val CONSOLIDATION_SYSTEM =
            "You are managing a memory store. You are given EXISTING memories (labeled M1, M2, ...) and a list of NEW " +
                "facts (labeled F1, F2, ...). Each NEW fact carries its category in brackets (e.g. F1 [preference]); " +
                "consider the category when deciding overlaps: facts of different categories rarely supersede each other. " +
                "For every NEW fact, decide how the store should change so it stays current, " +
                "free of duplicates, and free of contradictions. Output exactly one entry per new fact. " +
                "Rules: " +
                "ADD — the fact is genuinely new; no existing memory overlaps it; include its \"category\". " +
                "UPDATE — the fact supersedes/refines an existing memory; set id to that memory's label (Mx), and " +
                "\"old_memory\" to the original text being replaced; set \"category\" to the refined category. " +
                "DELETE — an existing memory is now false, obsolete, or contradicted; set id to its label and " +
                "\"old_memory\" to its current text. " +
                "NONE — the fact is already covered by an existing memory, or is irrelevant/junk; change nothing. " +
                "If one new fact replaces several existing memories, emit multiple UPDATE entries with the same text " +
                "and different ids. " +
                "Respond with ONLY a JSON object in this exact shape: " +
                "{\"memory\": [{\"id\": \"M1\", \"text\": \"fact\", \"event\": \"UPDATE\", \"category\": \"preference\", \"old_memory\": \"old\"}, " +
                "{\"id\": \"\", \"text\": \"fact\", \"event\": \"ADD\", \"category\": \"fact\", \"old_memory\": \"\"}]}"
    }
}