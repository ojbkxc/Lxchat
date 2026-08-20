package com.lxseek.chat.viewmodel

import android.content.Context
import android.speech.SpeechRecognizer
import com.lxseek.chat.util.AppLog as Log
import com.lxseek.chat.speech.AudioCaptureManager
import com.lxseek.chat.speech.SpeechRecognizerManager
import com.lxseek.chat.speech.StreamingVAD
import com.lxseek.chat.speech.VoskTranscriber
import com.lxseek.chat.speech.WhisperTranscriber
import com.lxseek.chat.util.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TTS_START_GRACE_MS = 5_000L
private const val SYSTEM_FINAL_TIMEOUT_MS = 10_000L
private const val TAG = "VoiceConvCtrl"
// AudioCaptureManager's RMS of normal speech sits around 0.05-0.25 (16-bit PCM / Short.MAX),
// which is barely visible in the voiceprint. This boost maps it to a 0.25-1.0 display range
// so the bars visibly pulse with the speaker's volume. VAD keeps using the raw amplitude.
private const val AMPLITUDE_BOOST = 5f

class VoiceConversationController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val voiceLanguageProvider: () -> String,
    private val ttsAutoPlayOn: () -> Boolean,
    private val isLoading: StateFlow<Boolean>,
    private val sendMessage: suspend (String) -> Unit,
    private val asrEnginePref: () -> String,
    private val whisperApiKey: () -> String?,
    private val whisperBaseUrl: () -> String,
    private val whisperModel: () -> String,
) {
    enum class State { IDLE, LISTENING, TRANSCRIBING, PROCESSING, SPEAKING }
    enum class Mode { CONVERSATION, SINGLE_ASR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _mode = MutableStateFlow(Mode.CONVERSATION)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _singleAsrResult = MutableStateFlow<String?>(null)
    val singleAsrResult: StateFlow<String?> = _singleAsrResult.asStateFlow()

    private val _singleAsrError = MutableStateFlow<String?>(null)
    val singleAsrError: StateFlow<String?> = _singleAsrError.asStateFlow()

    fun clearSingleAsrError() {
        _singleAsrError.value = null
    }

    private val audioCaptureManager = AudioCaptureManager(appContext)
    private val voskTranscriber = VoskTranscriber(appContext)
    private val speechRecognizerManager = SpeechRecognizerManager(appContext)
    private val whisperTranscriber = WhisperTranscriber(
        apiKeyProvider = { whisperApiKey() },
        baseUrlProvider = { whisperBaseUrl() },
        modelProvider = { whisperModel() },
    )

    @Volatile private var active = false
    @Volatile private var currentEngine: String = "auto"
    @Volatile private var isStreamingConversation = false

    /** Whether the controller is currently in streaming conversation mode. */
    fun isConversationStreaming(): Boolean = isStreamingConversation

    private var captureJob: Job? = null
    private var observeJob: Job? = null

    private var sendJob: Job? = null
    private var partialJob: Job? = null
    private var stopSystemWatchdog: Job? = null
    private var singleAsrTimeoutJob: Job? = null
    @Volatile private var waitingForLlm = false
    @Volatile private var llmWasLoading = false

    companion object {
        /** Hard ceiling for a single-shot ASR recording. Auto-stops & transcribes after this. */
        private const val MAX_SINGLE_ASR_DURATION_MS = 90_000L
    }

    fun toggle() {
        Log.i(TAG, "toggle: state=${_state.value}, active=$active")
        when (_state.value) {
            State.IDLE, State.SPEAKING -> start()
            State.LISTENING -> stopCaptureAndTranscribe()
            else -> stop()
        }
    }

    fun start() {
        if (active) {
            Log.w(TAG, "start() called but active=true, state=${_state.value} 鈥?previous session not reset; forcing reset before restart")
            stop()
        }
        Log.i(TAG, "start: beginning voice conversation")
        _mode.value = Mode.CONVERSATION
        _singleAsrResult.value = null
        _singleAsrError.value = null
        active = true
        waitingForLlm = false
        llmWasLoading = false
        try {
            beginListening()
        } catch (e: Throwable) {
            Log.e(TAG, "start() crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            active = false
            _state.value = State.IDLE
            return
        }
        observeJob?.cancel()
        observeJob = scope.launch { observeLlmAndTts() }

    }

    /**
     * Single-shot ASR: record once, transcribe, publish result via [singleAsrResult].
     * Does NOT send the message or observe LLM/TTS 鈥?the UI inserts the text into the composer.
     */
    fun startSingleAsr() {
        if (active) {
            Log.w(TAG, "startSingleAsr: active=true, stopping previous")
            stop()
        }
        Log.i(TAG, "startSingleAsr: beginning single ASR")
        _mode.value = Mode.SINGLE_ASR
        _singleAsrResult.value = null
        _singleAsrError.value = null
        active = true
        waitingForLlm = false
        llmWasLoading = false
        try {
            beginListening()
            // Safety ceiling: auto-stop a single-shot recording after 90 s so a user
            // who walks away doesn't drain the battery / fill memory. The timeout
            // fires stopCaptureAndTranscribe() which transcribes whatever was captured.
            singleAsrTimeoutJob?.cancel()
            singleAsrTimeoutJob = scope.launch {
                delay(MAX_SINGLE_ASR_DURATION_MS)
                if (active && _mode.value == Mode.SINGLE_ASR && _state.value == State.LISTENING) {
                    Log.w(TAG, "startSingleAsr: 90 s ceiling reached, auto-stopping")
                    stopCaptureAndTranscribe()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startSingleAsr crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            active = false
            _state.value = State.IDLE
            _mode.value = Mode.CONVERSATION
        }
    }

    fun clearSingleAsrResult() {
        _singleAsrResult.value = null
    }

    /**
     * End a voice session gracefully from the overlay's exit affordance. Unlike [stop],
     * an in-flight recording is transcribed (single ASR 鈫?composer; conversation 鈫?sent)
     * instead of being discarded, and the auto-restart loop is cancelled so listening
     * does not begin again.
     */
    fun finishConversationTurn() {
        Log.i(TAG, "finishConversationTurn: mode=${_mode.value}, state=${_state.value}")
        observeJob?.cancel()
        observeJob = null

        singleAsrTimeoutJob?.cancel()
        singleAsrTimeoutJob = null
        when (_mode.value) {
            Mode.SINGLE_ASR -> stopSingleAsr()
            Mode.CONVERSATION -> {
                when (_state.value) {
                    State.LISTENING -> stopCaptureAndTranscribe()
                    State.TRANSCRIBING -> { /* in flight 鈥?handleTranscriptionResult settles it */ }
                    else -> stop()
                }
            }
        }
    }

    /**
     * Stop single ASR recording and transcribe. Result lands in [singleAsrResult].
     */
    fun stopSingleAsr() {
        if (_mode.value != Mode.SINGLE_ASR) {
            Log.w(TAG, "stopSingleAsr: not in SINGLE_ASR mode, ignoring")
            return
        }
        Log.i(TAG, "stopSingleAsr: engine=$currentEngine")
        singleAsrTimeoutJob?.cancel()
        singleAsrTimeoutJob = null
        stopCaptureAndTranscribe()
    }

    fun stop() {
        Log.i(TAG, "stop()")
        active = false
        waitingForLlm = false
        llmWasLoading = false
        isStreamingConversation = false
        observeJob?.cancel()
        observeJob = null
        sendJob?.cancel()
        sendJob = null
        partialJob?.cancel()
        partialJob = null
        stopSystemWatchdog?.cancel()
        stopSystemWatchdog = null
        singleAsrTimeoutJob?.cancel()
        singleAsrTimeoutJob = null
        captureJob?.cancel()
        captureJob = null
        try { audioCaptureManager.cancelCapture() } catch (e: Throwable) { Log.e(TAG, "cancelCapture failed", e) }
        try { speechRecognizerManager.stopListening() } catch (e: Throwable) { Log.e(TAG, "stopListening failed", e) }
        try { voskTranscriber.stopStreamingSession() } catch (e: Throwable) { Log.e(TAG, "stopStreamingSession failed", e) }
        TtsManager.stop()
        _state.value = State.IDLE
        _partialTranscript.value = ""
        _amplitude.value = 0f
        _mode.value = Mode.CONVERSATION
        _singleAsrResult.value = null
        _singleAsrError.value = null
    }

    /**
     * Release native/hardware resources owned by the speech engines.
     * Called from the ViewModel's onCleared so AudioRecord, SpeechRecognizer and
     * Vosk models do not leak across ViewModel destruction.
     */
    fun dispose() {
        Log.i(TAG, "dispose()")
        stop()
        stopSystemWatchdog?.cancel()
        stopSystemWatchdog = null
        try { audioCaptureManager.release() } catch (e: Throwable) { Log.e(TAG, "audioCaptureManager.release failed", e) }
        try { voskTranscriber.release() } catch (e: Throwable) { Log.e(TAG, "voskTranscriber.release failed", e) }
        try { speechRecognizerManager.destroy() } catch (e: Throwable) { Log.e(TAG, "speechRecognizerManager.destroy failed", e) }
    }

    private fun beginListening() {
        if (!active) return
        // Guard against duplicate starts from the merged TTS observer racing on a very
        // short reply: a second beginListening while already LISTENING would open a
        // second mic/session.
        if (_state.value == State.LISTENING) {
            Log.d(TAG, "beginListening: already listening, ignoring duplicate start")
            return
        }
        _state.value = State.LISTENING
        _partialTranscript.value = ""
        _amplitude.value = 0f

        val pref = try { asrEnginePref() } catch (e: Throwable) { Log.e(TAG, "asrEnginePref crashed: ${e.message}", e); "auto" }
        currentEngine = pref
        Log.i(TAG, "beginListening: pref=$pref, mode=${_mode.value}")

        try {
            // CONVERSATION mode with Vosk ready uses streaming real-time transcription
            // (partial transcript updates + VAD auto-segmentation), matching ChatGPT
            // Advanced Voice / Gemini live voice. SINGLE_ASR keeps record-then-transcribe
            // so the composer receives the full utterance.
            if (_mode.value == Mode.CONVERSATION && (pref == "vosk" || pref == "auto")) {
                val voskReady = try { voskTranscriber.isReady() } catch (e: Throwable) { false }
                if (voskReady) {
                    beginStreamingVoskCapture()
                    return
                }
            }
            when (pref) {
                "vosk" -> beginVoskCapture()
                "whisper" -> beginWhisperCapture()
                "system" -> beginSystemListening()
                else -> beginAutoListening()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "beginListening dispatch crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            _state.value = State.IDLE
            active = false
        }
    }

    private fun beginAutoListening() {
        // GrapheneOS AI checks isReady() synchronously and starts capture immediately;
        // it does NOT block on model init before recording. Blocking caused the smoke
        // test to fail: the user tapped stop while initialize() was still loading the
        // model, captureJob was still null, and stopCaptureAndTranscribe() discarded
        // the session without transcribing.
        val voskReady = try { voskTranscriber.isReady() } catch (e: Throwable) { false }
        if (voskReady) {
            Log.i(TAG, "auto: vosk ready, starting vosk capture")
            beginVoskCapture()
            return
        }
        // Offline-first: if a Vosk model is installed (even if not yet loaded), prefer it so
        // offline ASR actually runs. transcribeWithVosk() loads the model at stop time and
        // surfaces the exact failure if loading fails (e.g. missing native lib).
        val downloaded = try { voskTranscriber.getDownloadedLanguages() } catch (e: Throwable) { emptyList() }
        if (downloaded.isNotEmpty()) {
            Log.i(TAG, "auto: vosk model installed ($downloaded), preferring offline vosk")
            beginVoskCapture()
            return
        }
        val hasKey = !whisperApiKey().isNullOrBlank()
        if (hasKey) {
            Log.i(TAG, "auto: vosk not ready, whisper key available, starting whisper capture")
            beginWhisperCapture()
            return
        }
        val systemAvailable = try { speechRecognizerManager.isAvailable() } catch (e: Throwable) { false }
        if (systemAvailable) {
            Log.i(TAG, "auto: vosk not ready, no whisper key, using system")
            beginSystemListening()
            return
        }
        // Nothing ready 鈥?start capture anyway and init Vosk in background.
        // The WAV will be transcribed on stop; if Vosk still isn't ready by then,
        // the user gets a clear error message via _singleAsrError instead of a
        // silent failure.
        Log.i(TAG, "auto: no engine ready, starting capture + background vosk init")
        currentEngine = "auto"
        startAudioCapture { wavFile ->
            scope.launch { transcribeWithVosk(wavFile) }
        }
        scope.launch {
            try {
                val lang = resolveVoskLanguage()
                voskTranscriber.initialize(lang)
                Log.i(TAG, "auto: background vosk init complete, ready=${voskTranscriber.isReady()}")
            } catch (e: Throwable) {
                Log.e(TAG, "auto: background vosk init failed: ${e.message}", e)
            }
        }
    }

    /** Resolve the configured voice recognition language to a Vosk model code. Prefers an exact
     *  downloaded model, then a downloaded model with the same base code, then any installed
     *  model 鈥?so offline recognition still engages instead of being silently skipped. */
    private fun resolveVoskLanguage(): String {
        val pref = try { voiceLanguageProvider().trim().lowercase() } catch (e: Throwable) {
            Log.e(TAG, "voiceLanguageProvider crashed: ${e.message}", e); "en"
        }
        val downloaded = try { voskTranscriber.getDownloadedLanguages() } catch (e: Throwable) { emptyList() }

        // No offline model installed at all: return the requested code so initialize() fails
        // with a clear reason and the caller falls back to whisper/system.
        if (downloaded.isEmpty()) {
            return if (pref.isBlank() || pref == "system") "en" else pref.split("-").first()
        }

        // User didn't pin a language: engage offline with whatever model is installed instead
        // of defaulting to "en" and silently skipping an installed non-English model.
        if (pref.isBlank() || pref == "system") {
            Log.i(TAG, "No pinned Vosk language, using installed model: ${downloaded.first()}")
            return downloaded.first()
        }

        val base = pref.split("-").first()
        // Prefer an exact downloaded model; otherwise pick a downloaded model whose
        // base code matches (e.g. "zh" selected while "zh-full" is downloaded).
        if (downloaded.contains(base)) return base
        downloaded.firstOrNull { VoskTranscriber.getBaseLanguageCode(it) == base }?.let { return it }

        // No matching model downloaded: fall back to any installed model so offline
        // recognition still engages.
        Log.w(TAG, "No downloaded Vosk model for '$pref', falling back to ${downloaded.first()}")
        return downloaded.first()
    }

    // VAD thresholds and calibration constants have been moved to StreamingVAD.kt.

    /**
     * Streaming Vosk capture for CONVERSATION mode: PCM chunks feed Vosk in real
     * time, partial results update [_partialTranscript] live, and VAD silence
     * detection (amplitude < 0.05 for 1.5s after speech) auto-segments utterances.
     * Each final segment is sent to the LLM; after TTS playback, listening resumes.
     */
    private fun beginStreamingVoskCapture() {
        Log.i(TAG, "beginStreamingVoskCapture: starting streaming real-time transcription")
        currentEngine = "vosk"
        isStreamingConversation = true
        captureJob?.cancel()
        captureJob = scope.launch {
            if (!voskTranscriber.isReady()) {
                val lang = resolveVoskLanguage()
                val initialized = try { voskTranscriber.initialize(lang) } catch (e: Throwable) {
                    Log.e(TAG, "Streaming: vosk init crashed: ${e.message}", e); false
                }
                if (!initialized) {
                    Log.e(TAG, "Streaming: Vosk init failed for $lang")
                    handleTranscriptionResult("[Vosk model not loaded 鈥?download in Settings 鈫?Speech]")
                    return@launch
                }
            }

            val callback = object : VoskTranscriber.StreamingTranscriptionCallback {
                override fun onPartialResult(text: String) {
                    if (active) _partialTranscript.value = text
                }
                override fun onFinalResult(text: String) {
                    if (!active) return
                    Log.i(TAG, "Streaming Vosk final segment: '$text'")
                    _partialTranscript.value = ""
                    scope.launch { handleTranscriptionResult(text) }
                    // Do NOT stop capture 鈥?continue recording for next utterance
                }
                override fun onError(error: String) {
                    Log.e(TAG, "Streaming Vosk error: $error")
                    if (active) {
                        captureJob?.cancel()
                        captureJob = null
                        try { audioCaptureManager.cancelCapture() } catch (e: Throwable) {}
                        voskTranscriber.stopStreamingSession()
                        handleTranscriptionResult("[$error]")
                    }
                }
            }

            val langCode = resolveVoskLanguage()
            if (!voskTranscriber.startStreamingSession(langCode, callback)) {
                handleTranscriptionResult("[Failed to start streaming session]")
                return@launch
            }

            val vad = StreamingVAD(
                onSpeechEnd = { segmentMs ->
                    val finalText = voskTranscriber.endSegment()
                    if (finalText != null && finalText.isNotBlank()) {
                        _partialTranscript.value = ""
                        Log.i(TAG, "Streaming utterance sent asynchronously: '$finalText' (${segmentMs}ms)")
                        scope.launch { handleTranscriptionResult(finalText) }
                    } else {
                        Log.i(TAG, "VAD segment ended but empty text (${segmentMs}ms), skipping")
                    }
                },
            )
            // Slice-feed statistics: how many PCM chunks / bytes have been pushed into Vosk.
            var chunkCount = 0L
            var totalBytes = 0L
            var lastFeedLogChunks = 0L

            try {
                val captureFlow = audioCaptureManager.startCapture()
                captureFlow.collect { chunk ->
                    if (!active) return@collect
                    val rawAmp = audioCaptureManager.amplitude.value
                    // UI voiceprint uses the boosted value; VAD below stays on the raw RMS
                    // so its silence thresholds are unaffected by the display scaling.
                    _amplitude.value = (rawAmp * AMPLITUDE_BOOST).coerceIn(0f, 1f)
                    // With VOICE_RECOGNITION the system performs echo cancellation, so TTS
                    // playback no longer gates ASR feedback: audio keeps flowing into Vosk
                    // while the assistant speaks (real-time conversation is not paused).
                    voskTranscriber.acceptWaveform(chunk)
                    chunkCount++
                    totalBytes += chunk.size
                    if (chunkCount - lastFeedLogChunks >= 20) {
                        lastFeedLogChunks = chunkCount
                        Log.i(TAG, "Streaming feed: $chunkCount chunks (~${totalBytes / 32000}s audio) pushed to Vosk")
                    }

                    // VAD: feed raw amplitude to StreamingVAD which handles calibration,
                    // adaptive threshold, hysteresis, and min-segment filtering internally.
                    // On speech end it calls voskTranscriber.endSegment() and dispatches
                    // the transcript to the LLM via the onSpeechEnd callback above.
                    vad.feedAmplitude(rawAmp)
                }

            } catch (e: Throwable) {
                Log.e(TAG, "Streaming capture flow crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                if (active) { _state.value = State.IDLE }
            } finally {
                Log.i(TAG, "Streaming session ended: $chunkCount chunks, $totalBytes bytes (~${totalBytes / 32000}s audio) pushed to Vosk")
                voskTranscriber.stopStreamingSession()
            }
        }
    }

    private fun beginVoskCapture() {
        Log.i(TAG, "beginVoskCapture: starting audio capture for vosk")
        currentEngine = "vosk"
        startAudioCapture { wavFile ->
            scope.launch { transcribeWithVosk(wavFile) }
        }
    }

    private fun beginWhisperCapture() {
        Log.i(TAG, "beginWhisperCapture: starting audio capture for whisper")
        currentEngine = "whisper"
        startAudioCapture { wavFile ->
            scope.launch { transcribeWithWhisper(wavFile) }
        }
    }

    private fun startAudioCapture(onComplete: (File) -> Unit) {
        captureJob?.cancel()
        captureJob = scope.launch {
            try {
                val captureFlow = audioCaptureManager.startCapture()
                captureFlow.collect { chunk ->
                    if (!active) return@collect
                    // Boost the RMS into a 0-1 display range so the voiceprint visibly moves.
                    _amplitude.value = (audioCaptureManager.amplitude.value * AMPLITUDE_BOOST).coerceIn(0f, 1f)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Audio capture flow crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                if (active) {
                    _state.value = State.IDLE
                    active = false
                }
            }
        }
    }

    private fun stopCaptureAndTranscribe() {
        Log.i(TAG, "stopCaptureAndTranscribe: engine=$currentEngine")
        if (currentEngine == "system") {
            if (_state.value == State.TRANSCRIBING) {
                Log.w(TAG, "stopCaptureAndTranscribe: already transcribing, ignoring duplicate stop")
                return
            }
            // Graceful stop: the system recognizer finalizes the utterance and delivers the
            // result through the live flow (Final branch handles it). The previous hard stop
            // (stopListening + cancel + destroy) killed the onResults callback, so tapping
            // stop after speaking produced nothing — this is the smoke-test failure.
            _state.value = State.TRANSCRIBING
            try { speechRecognizerManager.stopListening(graceful = true) } catch (e: Throwable) {
                Log.e(TAG, "graceful stopListening failed", e)
            }
            stopSystemWatchdog?.cancel()
            stopSystemWatchdog = scope.launch {
                delay(SYSTEM_FINAL_TIMEOUT_MS)
                if (active && currentEngine == "system" && _state.value == State.TRANSCRIBING) {
                    Log.w(TAG, "system final result timed out; settling session")
                    try { speechRecognizerManager.stopListening() } catch (e: Throwable) {
                        Log.e(TAG, "watchdog stopListening failed", e)
                    }
                    _partialTranscript.value = ""
                    _amplitude.value = 0f
                    _state.value = State.IDLE
                    active = false
                    if (_mode.value == Mode.SINGLE_ASR) {
                        _singleAsrError.value = "Speech recognition timed out — try again"
                        _mode.value = Mode.CONVERSATION
                    }
                }
            }
            return
        }
        if (currentEngine == "vosk" || currentEngine == "whisper" || currentEngine == "auto") {
            if (_state.value == State.TRANSCRIBING) {
                Log.w(TAG, "stopCaptureAndTranscribe: already transcribing, ignoring duplicate stop")
                return
            }
            if (captureJob == null && !audioCaptureManager.isCapturing()) {
                // The engine was still resolving (e.g. async "auto" model load) when the user
                // stopped; there is no recording to transcribe. Reset instead of restarting.
                Log.w(TAG, "stopCaptureAndTranscribe: no active capture, cancelling session")
                active = false
                _state.value = State.IDLE
                _mode.value = Mode.CONVERSATION
                return
            }
            _state.value = State.TRANSCRIBING
            captureJob?.cancel()
            captureJob = null
            scope.launch {
                try {
                    val wavFile = audioCaptureManager.stopCapture()
                    Log.i(TAG, "WAV file: ${wavFile.absolutePath} (${wavFile.length()} bytes)")
                    _amplitude.value = 0f
                    when (currentEngine) {
                        "whisper" -> transcribeWithWhisper(wavFile)
                        "auto" -> {
                            if (voskTranscriber.isReady()) transcribeWithVosk(wavFile)
                            else if (!whisperApiKey().isNullOrBlank()) transcribeWithWhisper(wavFile)
                            else {
                                Log.w(TAG, "auto: no engine ready for transcription")
                                wavFile.delete()
                                // Surface the error instead of silently restarting listening,
                                // which discarded the recording and left the composer empty.
                                handleTranscriptionResult(
                                    "[No ASR engine ready 鈥?configure Vosk or Whisper in Settings]"
                                )
                            }
                        }
                        else -> transcribeWithVosk(wavFile)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "stopCaptureAndTranscribe crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                    if (active) {
                        _state.value = State.IDLE
                    }
                }
            }
        }
    }

    private suspend fun transcribeWithVosk(wavFile: File) {
        _state.value = State.TRANSCRIBING
        try {
            val langCode = resolveVoskLanguage()
            Log.i(TAG, "transcribeWithVosk: lang=$langCode, wav=${wavFile.length()} bytes, voskReady=${voskTranscriber.isReady()}")
            // Re-initialize when Vosk is not ready OR when the loaded language doesn't
            // match the requested one (e.g. user switched voice_language but Vosk still
            // holds the previous model). Without this check, transcribe() would run
            // against the wrong language model and produce garbage output.
            if (!voskTranscriber.isReady() || voskTranscriber.getCurrentLanguage() != langCode) {
                val initialized = voskTranscriber.initialize(langCode)
                Log.i(TAG, "transcribeWithVosk: initialize($langCode)=$initialized, ready=${voskTranscriber.isReady()}")
            }
            if (!voskTranscriber.isReady()) {
                if (!whisperApiKey().isNullOrBlank()) {
                    Log.w(TAG, "Vosk model failed to load for $langCode; falling back to whisper")
                    transcribeWithWhisper(wavFile)
                    return
                }
                Log.e(TAG, "No Vosk model ready for $langCode — download one in Settings → Speech")
                wavFile.delete()
                handleTranscriptionResult(
                    "[Vosk model not loaded — download in Settings → Speech]"
                )
                return
            }
            Log.i(TAG, "Vosk transcribing ${wavFile.name}...")
            val text = voskTranscriber.transcribe(wavFile)
            Log.i(TAG, "transcribeWithVosk: raw result='$text'")
            wavFile.delete()
            handleTranscriptionResult(text)
        } catch (e: Throwable) {
            Log.e(TAG, "transcribeWithVosk crashed: ${e.message}", e)
            wavFile.delete()
            if (active) {
                _state.value = State.IDLE
            }
        }
    }

    private suspend fun transcribeWithWhisper(wavFile: File) {
        _state.value = State.TRANSCRIBING
        try {
            val langCode = resolveVoskLanguage()
            val languageParam = when (langCode) { "en" -> "en"; "zh" -> "zh"; else -> null }
            Log.i(TAG, "Whisper transcribing ${wavFile.name}...")
            val result = whisperTranscriber.transcribe(wavFile, languageParam)
            if (result.isSuccess) {
                wavFile.delete()
                handleTranscriptionResult(result.getOrDefault(""))
            } else {
                Log.w(TAG, "Whisper failed: ${result.exceptionOrNull()?.message}")
                if (active && voskTranscriber.isReady()) {
                    Log.i(TAG, "Falling back to vosk with the same recording")
                    transcribeWithVosk(wavFile)
                } else {
                    wavFile.delete()
                    handleTranscriptionResult(
                        "[Whisper transcription failed: ${result.exceptionOrNull()?.message ?: "unknown"}]"
                    )
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "transcribeWithWhisper crashed: ${e.message}", e)
            wavFile.delete()
            if (active) {
                _state.value = State.IDLE
            }
        }
    }

    private fun beginSystemListening() {
        // GrapheneOS AI checks availability before dispatching to the system engine;
        // when unavailable, fall back to Vosk/Whisper instead of failing silently.
        val available = try { speechRecognizerManager.isAvailable() } catch (e: Throwable) {
            Log.e(TAG, "isAvailable check crashed: ${e.message}", e); false
        }
        if (!available) {
            Log.w(TAG, "System ASR unavailable, trying fallback engines")
            val voskReady = try { voskTranscriber.isReady() } catch (e: Throwable) { false }
            if (voskReady) {
                Log.i(TAG, "system unavailable, falling back to vosk")
                beginVoskCapture()
            } else if (!whisperApiKey().isNullOrBlank()) {
                Log.i(TAG, "system unavailable, falling back to whisper")
                beginWhisperCapture()
            } else {
                Log.e(TAG, "system unavailable and no fallback engine ready")
                if (active) {
                    _state.value = State.IDLE
                    active = false
                }
            }
            return
        }
        Log.i(TAG, "beginSystemListening: starting system ASR")
        currentEngine = "system"
        try {
            partialJob?.cancel()
            partialJob = scope.launch {
                // Live mic level for the voiceprint: the system recognizer reports RMS dB.
                // VoiceSpectrumRing's bars then move with the speaker's volume in real time.
                launch {
                    speechRecognizerManager.rms.collect { level ->
                        if (active && _state.value == State.LISTENING) _amplitude.value = level
                    }
                }
                speechRecognizerManager.startListening().collect { result ->
                    if (!active) return@collect
                    when (result) {
                        is SpeechRecognizerManager.RecognitionResult.Partial -> {
                            _partialTranscript.value = result.text
                        }
                        is SpeechRecognizerManager.RecognitionResult.Final -> {
                            Log.i(TAG, "System ASR final: '${result.text}'")
                            stopSystemWatchdog?.cancel()
                            stopSystemWatchdog = null
                            speechRecognizerManager.stopListening()
                            handleTranscriptionResult(result.text)
                        }
                        is SpeechRecognizerManager.RecognitionResult.Error -> {
                            Log.w(TAG, "System ASR error: ${result.message} (code=${result.code})")
                            stopSystemWatchdog?.cancel()
                            stopSystemWatchdog = null
                            // GrapheneOS AI: on ERROR_CLIENT(5) the system recognizer is
                            // broken; switch to Vosk so the conversation survives.
                            if (active) {
                                val shouldSwitchToVosk = result.code == SpeechRecognizer.ERROR_CLIENT &&
                                    voskTranscriber.isReady()
                                if (shouldSwitchToVosk) {
                                    Log.i(TAG, "Switching to vosk after system ERROR_CLIENT")
                                    speechRecognizerManager.stopListening()
                                    beginVoskCapture()
                                } else {
                                    _state.value = State.IDLE
                                    active = false
                                    // Surface the failure instead of silently dropping it.
                                    _singleAsrError.value = "Speech recognition failed: ${result.message}"
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "beginSystemListening crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            _state.value = State.IDLE
            active = false
        }
    }

    private fun handleTranscriptionResult(text: String) {
        if (!active) return
        val cleanText = text.trim()
        if (cleanText.isBlank() || cleanText.startsWith("[")) {
            Log.w(TAG, "Transcription empty or error: '$cleanText'")
            // GrapheneOS AI surfaces transcriber errors (e.g. "[Model not loaded]",
            // "[Voice recognition unavailable - download model in settings]") to the
            // user instead of silently dropping them. Mirror that for SINGLE_ASR so
            // the composer stays empty only when the user actually said nothing.
            val errorMsg = if (cleanText.startsWith("[")) {
                cleanText.removeSurrounding("[", "]")
            } else {
                "Transcription was empty 鈥?speak louder or closer to the mic"
            }
            when (_mode.value) {
                Mode.SINGLE_ASR -> {
                    singleAsrTimeoutJob?.cancel()
                    singleAsrTimeoutJob = null
                    _singleAsrError.value = errorMsg
                    _state.value = State.IDLE
                    active = false
                    _mode.value = Mode.CONVERSATION
                }
                Mode.CONVERSATION -> {
                    // Surface the failure via the same snackbar channel the single-shot
                    // card uses, instead of silently dropping the turn.
                    _singleAsrError.value = "Speech recognition failed: $errorMsg"
                    _state.value = State.IDLE
                    active = false
                }
            }
            return
        }
        Log.i(TAG, "Transcription result: '$cleanText' (mode=${_mode.value})")
        when (_mode.value) {
            Mode.SINGLE_ASR -> {
                singleAsrTimeoutJob?.cancel()
                singleAsrTimeoutJob = null
                _singleAsrResult.value = cleanText
                _state.value = State.IDLE
                active = false
                _partialTranscript.value = ""
                _amplitude.value = 0f
                _mode.value = Mode.CONVERSATION
            }
            Mode.CONVERSATION -> {
                if (isStreamingConversation) {
                    // Continuous streaming mode: don't change state, don't wait for LLM
                    // Keep recording — LLM response + TTS will play while recording continues
                    // Barge-in: stop any in-flight TTS so the new reply can play immediately.
                    TtsManager.stop()
                    scope.launch { sendMessage(cleanText) }
                } else {
                    _state.value = State.PROCESSING
                    waitingForLlm = true
                    llmWasLoading = false
                    sendJob = scope.launch {
                        sendMessage(cleanText)
                        if (observeJob == null) {
                            // The user ended the loop via the overlay exit while this utterance
                            // was being sent; settle the session instead of restarting listening.
                            active = false
                            _state.value = State.IDLE
                        }
                    }
                }
            }
        }
    }

    /**
     * Merged observer for LLM loading state and TTS playback. Runs two concurrent
     * collectors inside a single [coroutineScope] so one [observeJob] cancellation
     * tears down both, eliminating the separate ttsObserverJob.
     *
     *  - isLoading collector: when the LLM finishes, transitions to SPEAKING and
     *    waits for TTS to start (grace period). If TTS never starts, resumes listening.
     *  - isPlaying collector: when TTS finishes playing, resumes listening.
     */
    private suspend fun observeLlmAndTts() = coroutineScope {
        launch {
            isLoading.collectLatest { loading ->
                if (loading) {
                    llmWasLoading = true
                } else if (llmWasLoading && waitingForLlm) {
                    waitingForLlm = false
                    llmWasLoading = false
                    if (!active) return@collectLatest
                    if (isStreamingConversation || ttsAutoPlayOn()) {
                        _state.value = State.SPEAKING
                        withTimeoutOrNull(TTS_START_GRACE_MS) {
                            TtsManager.isPlaying.first { it }
                        }
                        if (!active) return@collectLatest
                        if (!TtsManager.isPlaying.value && _state.value == State.SPEAKING) {
                            if (!isStreamingConversation) beginListening()
                            else _state.value = State.LISTENING
                        }
                    } else {
                        if (!isStreamingConversation) beginListening()
                        else _state.value = State.LISTENING
                    }
                }
            }
        }
        launch {
            TtsManager.isPlaying.collect { playing ->
                if (!active) return@collect
                if (!playing && _state.value == State.SPEAKING) {
                    delay(300)
                    if (active && !TtsManager.isPlaying.value && _state.value == State.SPEAKING) {
                        if (!isStreamingConversation) beginListening()
                        else _state.value = State.LISTENING
                    }
                }
            }
        }
    }

    fun getVoskTranscriber(): VoskTranscriber = voskTranscriber
}
