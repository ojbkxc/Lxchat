package com.lxseek.chat.runtime

import com.lxseek.chat.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Routes auxiliary tasks (compaction, vision, title generation, sub-agent, …)
 * to dedicated model endpoints instead of always burning tokens on the primary
 * chat model. This is the configuration layer of the Token optimization strategy:
 * it lets the user pick a cheap / fast model for background work and reserve the
 * expensive main model for the conversation itself.
 *
 * Persistence reuses existing per-role model settings exposed by
 * [SettingsRepository] (e.g. [SettingsRepository.contextCompactModel],
 * [SettingsRepository.imageTranscriptionModel], …). The full
 * [AuxiliaryModelConfig] (providerId / maxTokens / temperature) is kept in an
 * in-memory map keyed by [AuxiliaryRole]; only the modelId round-trips to disk
 * through the existing settings keys, so no new DataStore schema entry is
 * required. The in-memory map is seeded from the current settings values on
 * first access via [getConfig].
 *
 * Designed to be injected as a process-scoped singleton via
 * [com.lxseek.chat.di.AppContainer].
 */
class AuxiliaryModelRouter(
    private val settings: SettingsRepository,
) {

    /** Logical role an auxiliary model can serve. */
    enum class AuxiliaryRole {
        /** Vision / image transcription (VLM). */
        VISION,

        /** Context compaction / summarization. */
        COMPACTION,

        /** Approval / safety check pass. */
        APPROVAL,

        /** Sub-agent worker model. */
        SUBAGENT,

        /** Conversation title generation. */
        TITLE,

        /** Image generation endpoint. */
        IMAGE_GEN,
    }

    /** Configuration for one auxiliary role. */
    data class AuxiliaryModelConfig(
        val role: AuxiliaryRole,
        val modelId: String,
        val providerId: String = "",
        val maxTokens: Int = 0,
        val temperature: Float = 0f,
    )

    /** Live map of role -> config (only present roles have an entry). */
    private val _configs = MutableStateFlow<Map<AuxiliaryRole, AuxiliaryModelConfig>>(emptyMap())
    val configs: StateFlow<Map<AuxiliaryRole, AuxiliaryModelConfig>> = _configs.asStateFlow()

    // ── Read ───────────────────────────────────────────────────

    /**
     * Returns the configured [AuxiliaryModelConfig] for [role], or `null` when
     * no model has been explicitly assigned. Falls back to the persisted
     * settings value for the role's backing key when the in-memory map has no
     * entry yet, so configs survive a process restart even before [setConfig]
     * is called again.
     */
    fun getConfig(role: AuxiliaryRole): AuxiliaryModelConfig? {
        _configs.value[role]?.let { return it }
        val persistedModelId = persistedModelId(role) ?: return null
        if (persistedModelId.isBlank()) return null
        val cfg = AuxiliaryModelConfig(role = role, modelId = persistedModelId)
        _configs.value = _configs.value + (role to cfg)
        return cfg
    }

    /**
     * Resolves the model id for [role], falling back to [defaultModel] when no
     * config is present (either in-memory or persisted). This is the main entry
     * point used by the generation loop.
     */
    fun resolveModel(role: AuxiliaryRole, defaultModel: String): String =
        getConfig(role)?.modelId?.takeIf { it.isNotBlank() } ?: defaultModel

    // ── Write ──────────────────────────────────────────────────

    /** Persists [config] for its role: updates the in-memory map and writes the
     *  modelId through the matching [SettingsRepository] setter. */
    fun setConfig(role: AuxiliaryRole, config: AuxiliaryModelConfig) {
        _configs.value = _configs.value + (role to config.copy(role = role))
        savePersistedModelId(role, config.modelId)
    }

    // ── Convenience accessors ─────────────────────────────────

    /** Resolves the compaction model, falling back to [default]. */
    fun compactionModel(default: String): String = resolveModel(AuxiliaryRole.COMPACTION, default)

    /** Resolves the vision / image-transcription model, falling back to [default]. */
    fun visionModel(default: String): String = resolveModel(AuxiliaryRole.VISION, default)

    /** Resolves the sub-agent worker model, falling back to [default]. */
    fun subagentModel(default: String): String = resolveModel(AuxiliaryRole.SUBAGENT, default)

    /** Resolves the title-generation model, falling back to [default]. */
    fun titleModel(default: String): String = resolveModel(AuxiliaryRole.TITLE, default)

    /** Resolves the approval / safety-check model, falling back to [default]. */
    fun approvalModel(default: String): String = resolveModel(AuxiliaryRole.APPROVAL, default)

    /** Resolves the image-generation model, falling back to [default]. */
    fun imageGenModel(default: String): String = resolveModel(AuxiliaryRole.IMAGE_GEN, default)

    // ── Persistence bridge ────────────────────────────────────
    //
    // Each role is mapped to an existing per-role model setting exposed by
    // SettingsRepository. Only the modelId round-trips to disk; the rest of
    // AuxiliaryModelConfig is held in memory. APPROVAL has no dedicated key
    // and is therefore memory-only.

    private fun persistedModelId(role: AuxiliaryRole): String? = when (role) {
        AuxiliaryRole.VISION     -> settings.imageTranscriptionModel.value
        AuxiliaryRole.COMPACTION -> settings.contextCompactModel.value
        AuxiliaryRole.TITLE      -> settings.titleGenerationModel.value
        AuxiliaryRole.IMAGE_GEN  -> settings.imageGenModel.value
        AuxiliaryRole.SUBAGENT   -> settings.simpleTaskModel.value
        AuxiliaryRole.APPROVAL   -> settings.complexTaskModel.value
    }

    private fun savePersistedModelId(role: AuxiliaryRole, modelId: String) = when (role) {
        AuxiliaryRole.VISION     -> settings.setImageTranscriptionModel(modelId.ifBlank { null })
        AuxiliaryRole.COMPACTION -> settings.setContextCompactModel(modelId.ifBlank { null })
        AuxiliaryRole.TITLE      -> settings.setTitleGenerationModel(modelId.ifBlank { null })
        AuxiliaryRole.IMAGE_GEN  -> settings.setImageGenModel(modelId.ifBlank { null })
        AuxiliaryRole.SUBAGENT   -> settings.setSimpleTaskModel(modelId.ifBlank { null })
        AuxiliaryRole.APPROVAL   -> settings.setComplexTaskModel(modelId.ifBlank { null })
    }
}