package com.lxseek.chat.tool

/**
 * Capability registry: binds abstract capabilities (e.g. "image.generate",
 * "web.search") to concrete provider implementations. This is the extension
 * ecosystem layer of the Token optimization strategy — instead of hard-coding
 * "use DALL·E for image generation", the runtime asks the registry for the
 * best enabled binding for a capability id, and providers register their
 * implementations at startup.
 *
 * Storage is in-memory only: capabilities and bindings are re-registered on
 * every process start by the provider plugins / built-in modules. Quality is
 * a free-form string (e.g. "high", "medium", "low") compared lexicographically
 * when resolving the best binding — callers should use a consistent ordering
 * convention within their deployment.
 *
 * Designed to be injected as a process-scoped singleton via
 * [com.lxseek.chat.di.AppContainer].
 */
class CapabilityRegistry {

    /** Abstract capability descriptor (id + schema metadata). */
    data class Capability(
        /** Dotted identifier, e.g. "image.generate", "web.search". */
        val id: String,
        /** Human-readable name for UI display. */
        val displayName: String,
        /** Short description of what the capability does. */
        val description: String,
        /** Input parameter schema: parameter name -> type string (e.g. "string", "int"). */
        val inputSchema: Map<String, String> = emptyMap(),
        /** Output type string (e.g. "image", "text", "json"). */
        val outputType: String = "text",
    )

    /** Concrete implementation binding for a capability. */
    data class CapabilityBinding(
        /** Id of the [Capability] this binding implements. */
        val capabilityId: String,
        /** Provider that owns this binding (e.g. "openai", "stability"). */
        val providerId: String,
        /** Implementation handle (function reference, endpoint, plugin id, …). */
        val implementation: String,
        /** Quality tier string — higher lexicographic value wins. */
        val quality: String = "medium",
        /** Whether this binding is currently selectable. */
        val enabled: Boolean = true,
    )

    private val capabilities = LinkedHashMap<String, Capability>()
    private val bindings = mutableListOf<CapabilityBinding>()

    // ── Registration ──────────────────────────────────────────

    /** Registers (or replaces) a [Capability] by its id. */
    fun registerCapability(cap: Capability) {
        capabilities[cap.id] = cap
    }

    /** Registers a [CapabilityBinding]. Multiple bindings per capability are allowed. */
    fun registerBinding(binding: CapabilityBinding) {
        // Replace an existing binding with the same (capabilityId, providerId, implementation)
        // triple so re-registration is idempotent.
        val idx = bindings.indexOfFirst {
            it.capabilityId == binding.capabilityId &&
                it.providerId == binding.providerId &&
                it.implementation == binding.implementation
        }
        if (idx >= 0) bindings[idx] = binding else bindings += binding
    }

    // ── Lookup ────────────────────────────────────────────────

    /** Returns the capability with [id], or `null` if not registered. */
    fun getCapability(id: String): Capability? = capabilities[id]

    /** Returns every binding registered for [capabilityId] (enabled and disabled). */
    fun getBindings(capabilityId: String): List<CapabilityBinding> =
        bindings.filter { it.capabilityId == capabilityId }

    /**
     * Resolves the best binding for [capabilityId]: only enabled bindings are
     * considered, and the one with the lexicographically highest [CapabilityBinding.quality]
     * wins. Returns `null` when the capability is unknown or has no enabled binding.
     */
    fun resolveBinding(capabilityId: String): CapabilityBinding? =
        bindings
            .asSequence()
            .filter { it.capabilityId == capabilityId && it.enabled }
            .maxByOrNull { it.quality }

    /** Lists every registered capability in registration order. */
    fun listCapabilities(): List<Capability> = capabilities.values.toList()

    /** Lists every registered binding in registration order. */
    fun listBindings(): List<CapabilityBinding> = bindings.toList()

    // ── Default capabilities ──────────────────────────────────
    //
    // A small set of built-in capabilities is registered at construction so the
    // registry is non-empty even before providers plug in their bindings.

    init {
        registerCapability(
            Capability(
                id = CAP_IMAGE_GENERATE,
                displayName = "Image Generation",
                description = "Generate an image from a text prompt.",
                inputSchema = mapOf("prompt" to "string", "size" to "string"),
                outputType = "image",
            )
        )
        registerCapability(
            Capability(
                id = CAP_IMAGE_TRANSCRIBE,
                displayName = "Image Transcription",
                description = "Describe / transcribe an image into text.",
                inputSchema = mapOf("image" to "string"),
                outputType = "text",
            )
        )
        registerCapability(
            Capability(
                id = CAP_WEB_SEARCH,
                displayName = "Web Search",
                description = "Search the public web and return ranked results.",
                inputSchema = mapOf("query" to "string", "numResults" to "int"),
                outputType = "json",
            )
        )
        registerCapability(
            Capability(
                id = CAP_TEXT_SUMMARIZE,
                displayName = "Text Summarization",
                description = "Produce a concise summary of a longer text.",
                inputSchema = mapOf("text" to "string", "maxTokens" to "int"),
                outputType = "text",
            )
        )
        registerCapability(
            Capability(
                id = CAP_CODE_EXECUTE,
                displayName = "Code Execution",
                description = "Run a code snippet in a sandbox and return stdout/stderr.",
                inputSchema = mapOf("language" to "string", "code" to "string"),
                outputType = "json",
            )
        )
        registerCapability(
            Capability(
                id = CAP_EMBEDDING,
                displayName = "Text Embedding",
                description = "Compute the embedding vector for a text input.",
                inputSchema = mapOf("text" to "string"),
                outputType = "vector",
            )
        )
    }

    companion object {
        const val CAP_IMAGE_GENERATE  = "image.generate"
        const val CAP_IMAGE_TRANSCRIBE = "image.transcribe"
        const val CAP_WEB_SEARCH      = "web.search"
        const val CAP_TEXT_SUMMARIZE  = "text.summarize"
        const val CAP_CODE_EXECUTE    = "code.execute"
        const val CAP_EMBEDDING       = "text.embedding"
    }
}