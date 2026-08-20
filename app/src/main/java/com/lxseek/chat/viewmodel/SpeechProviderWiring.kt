package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.util.NetworkTtsConfig

/**
 * Provider-backed ASR / TTS wiring. Builds the lambdas that drive the online transcription engine
 * and the network text-to-speech synthesizer from whatever the user selected in settings:
 *
 *  - ASR: when an ASR provider model is chosen, resolve base URL / active key / model id from that
 *    provider; otherwise fall back to the legacy `asrRemote*` fields (which themselves fall back to
 *    the active chat provider).
 *  - TTS: resolve credentials for `POST {base}/audio/speech` from the selected TTS provider model,
 *    or return null so the system TTS engine remains in charge.
 *
 * Kept separate from [ChatViewModel] to honor the project's per-file Kotlin line budget.
 */
object SpeechProviderWiring {

    private const val FALLBACK_ASR_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val DEFAULT_WHISPER_MODEL = "whisper-large-v3"

    /** Online ASR API key: ASR provider model if set, else asrRemoteApiKey, else chat provider key. */
    fun whisperApiKey(settings: SettingsRepository, registry: ProviderRegistry): () -> String? = {
        val asrMid = settings.asrProviderModel.value
        if (asrMid != null) {
            settings.resolveActiveKey(ModelId.parse(asrMid).providerName) ?: ""
        } else {
            val p = registry.providerForModel(settings.selectedModel.value)
            settings.asrRemoteApiKey.value.takeIf { it.isNotBlank() } ?: settings.resolveActiveKey(p) ?: ""
        }
    }

    /** Online ASR base URL: from the ASR provider model, else asrRemoteBaseUrl, else chat provider. */
    fun whisperBaseUrl(settings: SettingsRepository, registry: ProviderRegistry): () -> String = {
        val asrMid = settings.asrProviderModel.value
        if (asrMid != null) {
            registry.getEffectiveBaseUrl(ModelId.parse(asrMid).providerName) ?: FALLBACK_ASR_URL
        } else {
            val p = registry.providerForModel(settings.selectedModel.value)
            settings.asrRemoteBaseUrl.value
                .takeIf { it.isNotBlank() && it != "https://api.openai.com/v1" }
                ?: registry.getEffectiveBaseUrl(p)
                ?: FALLBACK_ASR_URL
        }
    }

    /** Online ASR model id: from the ASR provider model, else asrRemoteModel, else default. */
    fun whisperModel(settings: SettingsRepository): () -> String = {
        val asrMid = settings.asrProviderModel.value
        if (asrMid != null) ModelId.parse(asrMid).apiModelName
        else settings.asrRemoteModel.value.takeIf { it.isNotBlank() } ?: DEFAULT_WHISPER_MODEL
    }

    /**
     * Resolves provider-backed TTS credentials from the selected TTS provider model (settings),
     * or null when no provider TTS model is configured (system engine stays active).
     */
    fun networkTtsConfig(
        settings: SettingsRepository,
        registry: ProviderRegistry,
    ): () -> NetworkTtsConfig? = {
        run {
            val mid = settings.ttsProviderModel.value ?: return@run null
            val parsed = ModelId.parse(mid)
            val baseUrl = registry.getEffectiveBaseUrl(parsed.providerName) ?: return@run null
            val apiKey = settings.resolveActiveKey(parsed.providerName)?.takeIf { it.isNotBlank() }
                ?: return@run null
            NetworkTtsConfig(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = parsed.apiModelName,
                voice = settings.ttsProviderVoice.value.ifBlank { "alloy" },
            )
        }
    }
}