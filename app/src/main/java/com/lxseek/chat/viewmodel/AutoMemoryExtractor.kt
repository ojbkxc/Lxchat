package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.LocalModelSerializer
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
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

        val prefixedModelId = settings.selectedModel.value.takeIf { it.isNotBlank() }
            ?: return Result.Failure("No model selected for memory extraction")
        val providerName = providers.providerForModel(prefixedModelId)
        val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
            ?: settings.resolveActiveKey(providerName).orEmpty()
        if (!providers.isConfigured(providerName, activeKey)) {
            return Result.Failure("Provider not configured: $providerName")
        }
        val modelId = ModelId.parse(prefixedModelId).modelName
        val provider = providers.getInstanceOrNull(providerName)
            ?: return Result.Failure("Provider not registered: $providerName")

        val extractConfig = buildConfig(systemPrompt = EXTRACTION_SYSTEM, activeKey, modelId, providerName)
        val extractResponse = try {
            runCompletion(provider, providerName, extractConfig, extractionUser(conversationText))
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
            val consolidateConfig = buildConfig(CONSOLIDATION_SYSTEM, activeKey, modelId, providerName)
            parseConsolidation(
                runCompletion(provider, providerName, consolidateConfig, consolidationUser(facts, existing)),
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

    private fun consolidationUser(facts: List<String>, existing: List<ExistingMemory>): List<ChatMessage> {
        val existingText = if (existing.isEmpty()) "No existing memories."
        else existing.joinToString("\n") { "${it.index}: ${it.text}" }
        val factsText = facts.withIndex().joinToString("\n") { (i, f) -> "F${i + 1}: $f" }
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

    private fun parseExtraction(raw: String): List<String> {
        val root = parseJsonObject(raw) ?: return emptyList()
        val arr = (root["memory"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { el ->
            val text = ((el as? JsonObject)?.get("text") as? JsonPrimitive)?.content?.trim().orEmpty()
            text.takeIf { it.isNotEmpty() }
        }
    }

    private data class Decision(val event: String, val id: String, val text: String)

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
            Decision(event, id, text)
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

    private class ExistingMemory(val index: String, val fileName: String, val text: String)

    private fun existingMemories(): List<ExistingMemory> =
        memoryManager.listFiles().take(MAX_EXISTING_MEMORIES).mapIndexedNotNull { i, info ->
            val body = runCatching { memoryManager.readFile(info.name) }.getOrDefault("")
                .trim().ifBlank { info.description }
            ExistingMemory("M${i + 1}", info.name, body.take(MAX_EXISTING_CHARS))
        }

    private fun applyAdd(facts: List<String>): Result {
        val taken = memoryManager.listFiles().map { it.name.removeSuffix(".md") }.toMutableSet()
        var added = 0
        for (fact in facts) {
            if (fact.isBlank()) continue
            val name = nextFreeName(fact, taken)
            if (runCatching { memoryManager.createFile(name, fact, fact) }.isSuccess) {
                taken.add(name)
                added++
            }
        }
        return Result.Success(added, 0, 0, 0)
    }

    private fun applyDecisions(decisions: List<Decision>, existing: List<ExistingMemory>): Result {
        val byIndex = existing.associateBy { it.index }
        val taken = memoryManager.listFiles().map { it.name.removeSuffix(".md") }.toMutableSet()
        var added = 0
        var updated = 0
        var deleted = 0
        var none = 0
        for (d in decisions) {
            when (d.event) {
                "ADD" -> {
                    if (d.text.isBlank()) continue
                    val name = nextFreeName(d.text, taken)
                    if (runCatching { memoryManager.createFile(name, d.text, d.text) }.isSuccess) {
                        taken.add(name)
                        added++
                    }
                }
                "UPDATE" -> {
                    val target = byIndex[d.id] ?: continue
                    if (d.text.isBlank()) continue
                    if (runCatching { memoryManager.editFile(target.fileName, content = d.text, description = d.text) }.isSuccess) {
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

    private fun nextFreeName(fact: String, taken: MutableSet<String>): String {
        val slug = fact.take(24)
            .replace(Regex("""[^\p{L}\p{N} _-]"""), "")
            .trim()
            .replace(Regex("""\s+"""), "-")
            .take(20)
            .ifBlank { "fact" }
        var i = 1
        var candidate = slug
        while (candidate in taken) {
            i++
            candidate = "$slug-$i"
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
                "Respond with ONLY a JSON object in this exact shape: {\"memory\": [{\"text\": \"fact 1\"}, {\"text\": \"fact 2\"}]}"

        // Source: mem0 DEFAULT_UPDATE_MEMORY_PROMPT (ADD/UPDATE/DELETE/NONE consolidation).
        private const val CONSOLIDATION_SYSTEM =
            "You are managing a memory store. You are given EXISTING memories (labeled M1, M2, ...) and a list of NEW " +
                "facts (labeled F1, F2, ...). For every NEW fact, decide how the store should change so it stays current, " +
                "free of duplicates, and free of contradictions. Output exactly one entry per new fact. " +
                "Rules: " +
                "ADD — the fact is genuinely new; no existing memory overlaps it. " +
                "UPDATE — the fact supersedes/refines an existing memory; set id to that memory's label (Mx), and " +
                "\"old_memory\" to the original text being replaced. " +
                "DELETE — an existing memory is now false, obsolete, or contradicted; set id to its label and " +
                "\"old_memory\" to its current text. " +
                "NONE — the fact is already covered by an existing memory, or is irrelevant/junk; change nothing. " +
                "If one new fact replaces several existing memories, emit multiple UPDATE entries with the same text " +
                "and different ids. " +
                "Respond with ONLY a JSON object in this exact shape: " +
                "{\"memory\": [{\"id\": \"M1\", \"text\": \"fact\", \"event\": \"UPDATE\", \"old_memory\": \"old\"}, " +
                "{\"id\": \"\", \"text\": \"fact\", \"event\": \"ADD\", \"old_memory\": \"\"}]}"
    }
}