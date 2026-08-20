package com.lxseek.chat.speech

/**
 * Keyword-based classifiers that identify which synced provider models are capable of
 * speech-to-text (ASR) versus text-to-speech (TTS). This mirrors the image-gen page's
 * [IMAGE_MODEL_KEYWORDS] approach: providers expose many model ids in one list, and the app
 * must tell them apart so the ASR settings show only recognizer models and the TTS settings
 * show only synthesizer models. Matched case-insensitively against the bare model id
 * (provider prefix stripped). The picker offers a "show all" escape hatch, so a miss here is
 * recoverable and a rare false positive is harmless.
 */
object ModelCapability {

    private val ASR_MODEL_KEYWORDS = listOf(
        // OpenAI-compatible
        "whisper", "transcri", "stt", "speech-to-text", "speech2text",
        "audio-transcription", "audio_transcription",
        // Google
        "speechrecognition", "speech-recognition", "recognizer", "long-form-audio",
        "gemini-audio",
        // Fold/self-hosted naming
        "vosk", "wav2vec", "conformer", "sensevoice", "paraformer", "funasr",
        "mms-", "seamless", "asr",
        "audio-input", "audio_to_text",
    )

    private val TTS_MODEL_KEYWORDS = listOf(
        // OpenAI-compatible
        "tts", "speech", "voice", "audio-speech", "audio_speech", "speech-synth",
        "text-to-speech", "text2speech", "synthes", "say", "elven", "elevenlabs",
        // Google
        "neural2-", "wavenet-", "chirp", "studio-voice", "texttospeech", "pico-",
        // Vendors / community
        "kokoro", "piper", "bark", "vits", "espeak", "flite", "xtts", "coqui",
        "tortoise", "gpt-4o-mini-tts", "tts-1", "openvoice", "ember", "voicecraft",
    )

    fun isLikelyAsrModel(modelId: String): Boolean {
        val id = modelId.substringAfter(":").lowercase()
        return ASR_MODEL_KEYWORDS.any { id.contains(it) }
    }

    fun isLikelyTtsModel(modelId: String): Boolean {
        val id = modelId.substringAfter(":").lowercase()
        return TTS_MODEL_KEYWORDS.any { id.contains(it) }
    }
}