package com.lxseek.chat.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Connection details for provider-backed (network) TTS synthesis via the OpenAI-compatible
 * `POST {base}/audio/speech` endpoint. Null config means "use the system TTS engine".
 */
data class NetworkTtsConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val voice: String,
)

data class TtsDiagnosticInfo(
    val initialized: Boolean,
    val available: Boolean,
    val engineName: String?,
    val availableEngines: List<String>,
    val langMissingData: Boolean,
    val lastInitStatus: String,
    val lastSpeakResult: String,
    val lastLanguageResult: String,
)

object TtsManager {
    private const val TAG = "TtsManager"
    private const val MAX_LOG = 300
    private const val WATCHDOG_TIMEOUT_MS = 30_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var watchdogJob: Job? = null

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initialized = false
    @Volatile private var initGeneration = 0
    @Volatile private var enginesToTry: List<String?> = emptyList()
    @Volatile private var currentEngineIndex = 0

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _langMissingData = MutableStateFlow(false)
    val langMissingData: StateFlow<Boolean> = _langMissingData.asStateFlow()
    private val _lastInitStatus = MutableStateFlow("IDLE")
    val lastInitStatus: StateFlow<String> = _lastInitStatus.asStateFlow()
    private val _lastSpeakResult = MutableStateFlow("")
    val lastSpeakResult: StateFlow<String> = _lastSpeakResult.asStateFlow()
    private val _lastLanguageResult = MutableStateFlow("")
    val lastLanguageResult: StateFlow<String> = _lastLanguageResult.asStateFlow()

    @Volatile private var pendingText: String? = null
    @Volatile private var pendingLanguage: String = "system"
    @Volatile private var pendingRate: Float = 1.0f
    @Volatile private var appContext: Context? = null

    /**
     * Optional resolver that returns provider-backed synthesis config when the user has chosen a
     * TTS provider model in settings. Null (or null result) routes through the system engine.
     * Injected from [com.lxseek.chat.viewmodel.ChatViewModel] after init.
     */
    @Volatile var networkTtsConfig: (() -> NetworkTtsConfig?)? = null
    @Volatile private var netPlayer: MediaPlayer? = null
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private const val NET_SPEECH_PATH = "/audio/speech"

    private fun log(level: String, msg: String) {
        val ts = logTimeFormat.format(Date())
        val entry = "$ts $level/$TAG: $msg"
        if (level == "E") Log.e(TAG, msg) else Log.d(TAG, msg)
        when (level) {
            "E" -> com.lxseek.chat.util.AppLog.e(TAG, msg)
            "W" -> com.lxseek.chat.util.AppLog.w(TAG, msg)
            "I" -> com.lxseek.chat.util.AppLog.i(TAG, msg)
            else -> com.lxseek.chat.util.AppLog.d(TAG, msg)
        }
        synchronized(logBuffer) {
            logBuffer.add(entry)
            if (logBuffer.size > MAX_LOG) logBuffer.removeAt(0)
        }
    }

    fun getLogText(): String {
        val sb = StringBuilder()
        sb.append("=== TTS Diagnostic Log ===\n")
        sb.append("Date: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("App: LxChat v${com.lxseek.chat.BuildConfig.VERSION_NAME}\n")
        val info = getDiagnosticInfo()
        sb.append("Initialized: ${info.initialized}\n")
        sb.append("Available: ${info.available}\n")
        sb.append("Engine: ${info.engineName}\n")
        sb.append("Available engines: ${info.availableEngines}\n")
        sb.append("Lang missing data: ${info.langMissingData}\n")
        sb.append("Last init status: ${info.lastInitStatus}\n")
        sb.append("Last speak result: ${info.lastSpeakResult}\n")
        sb.append("Last language result: ${info.lastLanguageResult}\n")
        sb.append("=== Log Entries ===\n")
        synchronized(logBuffer) { for (e in logBuffer) sb.append(e).append('\n') }
        return sb.toString()
    }

    fun clearLog() { synchronized(logBuffer) { logBuffer.clear() } }

    fun init(context: Context) {

        if (tts != null && initialized) return
        if (tts != null && !initialized) {
            try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
            tts = null
        }
        val appCtx = context.applicationContext
        this.appContext = appCtx
        _lastInitStatus.value = "PENDING"
        val pm = appCtx.packageManager
        val ttsIntent = Intent("android.speech.tts.TTS_SERVICE")
        val resolvedEngines = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(ttsIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(ttsIntent, 0)
            }.map { it.serviceInfo.packageName }
        } catch (_: Throwable) { emptyList() }
        val defaultEngine = try {
            Settings.Secure.getString(appCtx.contentResolver, "tts_default_synth")
        } catch (_: Throwable) { null }
        log("D", "PM resolved engines: $resolvedEngines")
        log("D", "System default engine: $defaultEngine")
        for (e in resolvedEngines) {
            val installed = try { pm.getPackageInfo(e, 0) != null } catch (_: Throwable) { false }
            log("D", "  $e installed=$installed")
        }
        val knownEngines = setOfNotNull(defaultEngine, "com.google.android.tts", "com.xiaomi.mibrain.speech")
        for (pkg in knownEngines) {
            try {
                val info = pm.getPackageInfo(pkg, 0)
                val appInfo = info.applicationInfo
                val enabled = appInfo?.enabled ?: false
                val enabledStr = when {
                    appInfo == null -> "null"
                    !enabled -> "DISABLED"
                    else -> "ENABLED"
                }
                log("D", "Package $pkg: $enabledStr (versionCode=${info.versionCode} versionName=${info.versionName})")
            } catch (_: Throwable) {
                log("D", "Package $pkg: NOT INSTALLED")
            }
        }
        // Manual bindService diagnostic. Android 8.0+ (API 26+) requires an
        // explicit Intent for bindService; the old action-only implicit Intent
        // always threw "Service Intent must be explicit" and produced a misleading
        // E log while TTS kept working. Build explicit intents (setPackage) from
        // resolved + known enabled engines instead, and skip the test if none exist.
        val bindCandidates = (resolvedEngines + knownEngines).distinct().filter { pkg ->
            try { pm.getPackageInfo(pkg, 0).applicationInfo?.enabled == true } catch (_: Throwable) { false }
        }
        if (bindCandidates.isEmpty()) {
            log("D", "No TTS engine resolved; skipping manual bindService test")
            log("D", "Manual bindService(TTS_SERVICE) returned: false (skipped, no explicit engine available)")
        } else {
            var bindResult = false
            for (enginePkg in bindCandidates) {
                val explicitIntent = Intent("android.speech.tts.TTS_SERVICE").setPackage(enginePkg)
                val ok = try {
                    appCtx.bindService(explicitIntent, object : android.content.ServiceConnection {
                        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                            log("D", "Manual bindService($enginePkg): onServiceConnected $name")
                            try { appCtx.unbindService(this) } catch (_: Throwable) {}
                        }
                        override fun onServiceDisconnected(name: android.content.ComponentName?) {
                            log("D", "Manual bindService($enginePkg): onServiceDisconnected $name")
                        }
                    }, android.content.Context.BIND_AUTO_CREATE)
                } catch (e: Throwable) {
                    log("E", "Manual bindService exception ($enginePkg): ${e.message}")
                    false
                }
                log("D", "Manual bindService(TTS_SERVICE) via $enginePkg returned: $ok")
                if (ok) { bindResult = true; break }
            }
            log("D", "Manual bindService(TTS_SERVICE) returned: $bindResult")
        }
        // Brand-aware, "phone's-own-first" engine ordering. "System TTS" here means the TTS engine
        // the phone's manufacturer bundles: Xiaomi -> 小米引擎, OPPO -> OPPO 引擎, Samsung -> Samsung
        // TTS, etc. This is preferred over whatever engine the user currently has selected as the
        // system default (e.g. they may have switched to Google for English). Unknown brand mappings
        // are skipped silently, so this can never make things worse — the system default is next.
        val oemEngines = resolveOemTtsEngines(Build.MANUFACTURER, pm)
        enginesToTry = mutableListOf<String?>().apply {
            // 1) The device's own bundled OEM engine(s) — the deterministic stock voice per brand.
            for (e in oemEngines) if (e !in this) add(e)
            // 2) The directly-read system default engine (tts_default_synth) — identical to the
            //    OEM engine on a stock phone, kept first-class so all brands are covered.
            if (!defaultEngine.isNullOrEmpty() && defaultEngine !in this) add(defaultEngine)
            // 3) The neutral 2-arg constructor resolves the system default engine again, giving
            //    an equivalent fallback when the provider id cannot be read from settings.
            add(null)
            // 4) Other resolved engines (deduped).
            for (e in resolvedEngines) if (e !in this) add(e)
            // 5) Last-resort curated engines (Google / Xiaomi) for devices that resolve none.
            if ("com.google.android.tts" !in this) add("com.google.android.tts")
            if ("com.xiaomi.mibrain.speech" !in this) add("com.xiaomi.mibrain.speech")
        }
        currentEngineIndex = 0
        log("D", "OEM engines: $oemEngines; default=$defaultEngine; enginesToTry (null=2-arg): $enginesToTry")
        tryNextEngine(appCtx)
    }

    // OEM -> bundled TTS engine package(s). Only entries that resolve to a real, installed
    // engine are ever tried; any unmatched hint is skipped silently and never hurts behaviour.
    // (Vendor package ids can vary across ROMs, so a wrong hint simply falls through.)
    private val OEM_TTS_ENGINES = mapOf(
        "xiaomi" to listOf("com.xiaomi.mibrain.speech"),
        "redmi" to listOf("com.xiaomi.mibrain.speech"),
        "poco" to listOf("com.xiaomi.mibrain.speech"),
        "samsung" to listOf("com.samsung.SMT"),
        "huawei" to listOf("com.huawei.tts"),
        "honor" to listOf("com.hihonor.speechkit"),
        "oppo" to listOf("com.oppo.smart.tts"),
        "realme" to listOf("com.realme.tts"),
        "oneplus" to listOf("com.oneplus.tts"),
        "vivo" to listOf("com.vivo.vivoai.tts"),
        "iqoo" to listOf("com.vivo.vivoai.tts"),
        "google" to listOf("com.google.android.tts"),
    )

    private fun resolveOemTtsEngines(manufacturer: String?, packageManager: PackageManager): List<String> {
        val brand = manufacturer?.lowercase() ?: return emptyList()
        val mapped = OEM_TTS_ENGINES.entries
            .firstOrNull { (key, _) -> brand.contains(key) }
            ?.value
            ?: emptyList()
        return mapped.filter { isEngineInstalled(it, packageManager) }
    }

    private fun isEngineInstalled(pkg: String, packageManager: PackageManager): Boolean = try {
        packageManager.getPackageInfo(pkg, 0).applicationInfo?.enabled == true
    } catch (_: Throwable) {
        false
    }

    private fun tryNextEngine(ctx: Context) {
        if (currentEngineIndex >= enginesToTry.size) {
            log("E", "All engines exhausted")
            _lastInitStatus.value = "FAILED:all_exhausted"
            initialized = false; _isAvailable.value = false
            return
        }
        val engine = enginesToTry[currentEngineIndex]
        val generation = ++initGeneration
        val label = engine ?: "null(2-arg)"
        log("D", "Trying engine ${currentEngineIndex + 1}/${enginesToTry.size}: $label")
        tts = try {
            if (engine == null) {
                TextToSpeech(ctx) { status -> onInitResult(generation, status, label, ctx) }
            } else {
                TextToSpeech(ctx, { status -> onInitResult(generation, status, label, ctx) }, engine)
            }
        } catch (e: Throwable) {
            log("E", "Constructor exception for $label: ${e.message}")
            currentEngineIndex++
            mainHandler.postDelayed({ tryNextEngine(ctx) }, 300)
            null
        }
    }

    fun reinit(context: Context) { shutdown(); init(context) }

    private fun onInitResult(generation: Int, status: Int, engineLabel: String, ctx: Context) {
        if (generation != initGeneration) return
        if (status == TextToSpeech.SUCCESS) {
            initialized = true; _isAvailable.value = true
            _lastInitStatus.value = "SUCCESS:$engineLabel"
            log("D", "init SUCCESS with engine=$engineLabel, engines=${tts?.engines?.map { it.name }}")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { log("D", "onStart $utteranceId"); _isPlaying.value = true }
                override fun onDone(utteranceId: String?) { log("D", "onDone $utteranceId"); watchdogJob?.cancel(); watchdogJob = null; _isPlaying.value = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { log("D", "onError $utteranceId"); watchdogJob?.cancel(); watchdogJob = null; _isPlaying.value = false }
                override fun onError(utteranceId: String?, errorCode: Int) { log("E", "onError $utteranceId code=$errorCode"); watchdogJob?.cancel(); watchdogJob = null; _isPlaying.value = false }
            })
            pendingText?.let { text ->
                pendingText = null
                val lang = pendingLanguage; val rate = pendingRate
                log("D", "flushing pendingText on main thread")
                mainHandler.post { speakInternal(text, lang, rate) }
            }
        } else {
            log("E", "init FAILED for engine=$engineLabel status=$status")
            try { tts?.shutdown() } catch (_: Throwable) {}
            tts = null
            currentEngineIndex++
            mainHandler.postDelayed({ tryNextEngine(ctx) }, 300)
        }
    }

    fun speak(text: String, language: String = "system", rate: Float = 1.0f): Boolean {
        if (text.isBlank()) { log("D", "speak: text is blank"); return false }

        val config = networkTtsConfig?.invoke()
        if (config != null) {
            return speakNetwork(text, rate, config)
        }

        if (!initialized || tts == null) {
            log("D", "speak: buffering (initialized=$initialized tts=${tts != null})")
            pendingText = text; pendingLanguage = language; pendingRate = rate
            return true
        }
        return speakInternal(text, language, rate)
    }

    /**
     * Provider-backed TTS: synthesize the text over the network (OpenAI-compatible
     * `POST /audio/speech`) and stream the returned audio via [MediaPlayer]. Keeps [isPlaying]
     * in sync so the existing chat / voice-conversation observers behave identically to system TTS.
     */
    private fun speakNetwork(text: String, rate: Float, config: NetworkTtsConfig): Boolean {
        stop()
        _isPlaying.value = true
        networkScope.launch {
            val audio = synthesizeNetSpeech(text, rate, config)
            if (audio == null) {
                log("E", "Network TTS synthesis failed for model=${config.model}")
                _isPlaying.value = false
                return@launch
            }
            playNetAudio(audio, rate)
        }
        return true
    }

    private suspend fun synthesizeNetSpeech(
        text: String,
        rate: Float,
        config: NetworkTtsConfig,
    ): File? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(normalizeSpeechUrl(config.baseUrl))
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 60000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().apply {
                put("model", config.model)
                put("input", text)
                put("voice", config.voice)
                put("speed", rate.coerceIn(0.25f, 4f))
                put("response_format", "mp3")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val err = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                } catch (e: Exception) { "HTTP $code" }
                log("E", "Network TTS HTTP $code: ${err.take(300)}")
                return@withContext null
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                log("E", "Network TTS returned empty audio body")
                return@withContext null
            }
            val dir = File(appContext?.cacheDir ?: File(""), "net_tts")
            if (!dir.exists()) dir.mkdirs() else dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "net-tts-${System.currentTimeMillis()}.mp3")
            out.writeBytes(bytes)
            out
        } catch (e: Exception) {
            log("E", "Network TTS exception: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun playNetAudio(file: File, rate: Float) {
        try {
            netPlayer?.let { runCatching { it.release() } }
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    runCatching { it.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(0.5f, 2f)) }
                }
                it.start()
            }
            mp.setOnCompletionListener {
                log("D", "Network TTS onCompletion")
                _isPlaying.value = false
                runCatching { file.delete() }
                if (netPlayer === it) netPlayer = null
                runCatching { it.release() }
            }
            mp.setOnErrorListener { _, _, _ ->
                log("E", "Network TTS playback error")
                _isPlaying.value = false
                runCatching { file.delete() }
                if (netPlayer === mp) netPlayer = null
                runCatching { mp.release() }
                true
            }
            netPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            log("E", "Network TTS playback init exception: ${e.message}")
            _isPlaying.value = false
        }
    }

    /** Accepts either a full `/audio/speech` endpoint or an OpenAI-compatible base URL. */
    private fun normalizeSpeechUrl(raw: String): String {
        val base = raw.trim().trimEnd('/')
        if (base.isEmpty()) return base
        return if (base.endsWith(NET_SPEECH_PATH)) base else "$base$NET_SPEECH_PATH"
    }

    private fun speakInternal(text: String, language: String, rate: Float): Boolean {
        val engine = tts ?: run { log("E", "speakInternal: engine is null"); _lastSpeakResult.value = "ERROR:no_engine"; return false }
        val locale = when (language) { "en" -> Locale.US; "zh" -> Locale.SIMPLIFIED_CHINESE; else -> Locale.getDefault() }
        val langResult = engine.setLanguage(locale)
        val langResultStr = langResultToString(langResult)
        log("D", "setLanguage($locale)=$langResultStr lang=$language")
        _lastLanguageResult.value = "$language:$langResultStr"
        _langMissingData.value = (langResult == TextToSpeech.LANG_MISSING_DATA)
        if (langResult == TextToSpeech.LANG_NOT_SUPPORTED || langResult == TextToSpeech.LANG_MISSING_DATA) {
            val fb = engine.setLanguage(Locale.getDefault())
            log("D", "fallback setLanguage(default)=${langResultToString(fb)}")
            if (fb == TextToSpeech.LANG_MISSING_DATA) _langMissingData.value = true
            if (fb == TextToSpeech.LANG_NOT_SUPPORTED || fb == TextToSpeech.LANG_MISSING_DATA) engine.setLanguage(Locale.US)
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        val speakStr = if (speakResult == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR:$speakResult"
        log("D", "speak result=$speakStr textLen=${text.length} text='${text.take(80)}'")
        _lastSpeakResult.value = speakStr
        if (speakResult != TextToSpeech.SUCCESS) { _isPlaying.value = false; return false }
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            delay(WATCHDOG_TIMEOUT_MS)
            if (_isPlaying.value) {
                log("E", "Watchdog timeout (${WATCHDOG_TIMEOUT_MS}ms) — forcing isPlaying=false")
                _isPlaying.value = false
            }
        }
        return true
    }

    private fun langResultToString(result: Int): String = when (result) {
        TextToSpeech.LANG_AVAILABLE -> "AVAILABLE"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "COUNTRY_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "COUNTRY_VAR_AVAILABLE"
        TextToSpeech.LANG_NOT_SUPPORTED -> "NOT_SUPPORTED"
        TextToSpeech.LANG_MISSING_DATA -> "MISSING_DATA"
        else -> "UNKNOWN:$result"
    }

    fun stop() {
        watchdogJob?.cancel(); watchdogJob = null; tts?.stop()
        netPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }; netPlayer = null
        _isPlaying.value = false
    }
    fun shutdown() {
        initGeneration++; watchdogJob?.cancel(); watchdogJob = null; tts?.stop(); tts?.shutdown(); tts = null
        netPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }; netPlayer = null
        initialized = false; _isAvailable.value = false; _isPlaying.value = false; _langMissingData.value = false
        _lastInitStatus.value = "IDLE"; _lastSpeakResult.value = ""; _lastLanguageResult.value = ""
        pendingText = null
    }

    fun getDiagnosticInfo(): TtsDiagnosticInfo {
        val engine = tts
        return TtsDiagnosticInfo(
            initialized = initialized, available = _isAvailable.value,
            engineName = engine?.defaultEngine,
            availableEngines = engine?.engines?.map { it.name } ?: emptyList(),
            langMissingData = _langMissingData.value,
            lastInitStatus = _lastInitStatus.value, lastSpeakResult = _lastSpeakResult.value, lastLanguageResult = _lastLanguageResult.value,
        )
    }

    fun testSpeak(): Boolean = speak("Hello, this is a TTS test. 你好，这是语音测试。", "system", 1.0f)
    fun systemTtsSettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun installTtsDataIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
    fun installGoogleTtsIntent(): Intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.tts"))

    fun stripMarkdown(text: String): String =
        text
            .replace(Regex("`{1,3}[^`]*`{1,3}"), "")
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("#+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[*_~>|]"), "")
            .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
