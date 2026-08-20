package com.lxseek.chat.ui.settings

import android.content.Context
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.speech.VoskTranscriber
import com.lxseek.chat.util.AppLog
import com.lxseek.chat.util.CrashReporter
import com.lxseek.chat.util.TtsManager
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

private val ASR_LOG_TAGS = setOf(
    "VoiceConvCtrl",
    "SpeechRecognizerMgr",
    "VoskTranscriber",
    "WhisperTranscriber",
    "AudioCaptureManager",
)

/**
 * Single unified logs page. Consolidates the previously scattered app log, TTS log and
 * ASR/Vosk diagnostics into one settings menu, with per-block copy/export/clear actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsLogsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current

    val asrEnginePref by viewModel.settings.asrEnginePref.collectAsState()
    val asrUseRemote by viewModel.settings.asrUseRemote.collectAsState()
    val asrRemoteBaseUrl by viewModel.settings.asrRemoteBaseUrl.collectAsState()
    val asrRemoteApiKey by viewModel.settings.asrRemoteApiKey.collectAsState()
    val asrRemoteModel by viewModel.settings.asrRemoteModel.collectAsState()
    val voiceLanguage by viewModel.settings.voiceLanguage.collectAsState()
    val sessionState by viewModel.voiceConversation.state.collectAsState()
    val voskTranscriber = viewModel.voiceConversation.getVoskTranscriber()

    // Poll the in-memory log buffers every few seconds so the page stays live.
    var appLog by remember { mutableStateOf("") }
    var ttsLog by remember { mutableStateOf("") }
    var asrLog by remember { mutableStateOf("") }
    var voskDiag by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            appLog = AppLog.getText()
            ttsLog = TtsManager.getLogText()
            asrLog = AppLog.getFilteredText(ASR_LOG_TAGS, 200)
            voskDiag = voskTranscriber.getDiagnosticText()
            delay(3_000L)
        }
    }

    val voskReady = voskTranscriber.isReady()
    val voskCurrentLang = voskTranscriber.getCurrentLanguage()
    val downloadedLangs = voskTranscriber.getDownloadedLanguages()
    val voiceBaseLang = VoskTranscriber.getBaseLanguageCode(voiceLanguage)
    val modelForVoiceLang = downloadedLangs.any { VoskTranscriber.getBaseLanguageCode(it) == voiceBaseLang }
    val systemAvailable = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (_: Throwable) {
        false
    }
    val whisperConfigured = asrUseRemote &&
        asrRemoteBaseUrl.isNotBlank() &&
        asrRemoteApiKey.isNotBlank() &&
        asrRemoteModel.isNotBlank()

    val engineLabel = when (asrEnginePref) {
        "system" -> stringResource(R.string.asr_engine_system)
        "vosk" -> stringResource(R.string.asr_engine_vosk)
        "whisper" -> stringResource(R.string.asr_engine_whisper)
        else -> stringResource(R.string.asr_engine_auto)
    }

    // Strict engine readiness checks (hardcoded English diagnostic text, matching the
    // previous SettingsAsrDiagnosticsSection).
    val readinessLines = buildList {
        add(stringResource(R.string.asr_engine_status, engineLabel, sessionState.name))
        add("Engine readiness:")
        when (asrEnginePref) {
            "auto" -> {
                val candidates = buildList {
                    if (voskReady) add("vosk: ready")
                    if (whisperConfigured) add("whisper: configured")
                    if (systemAvailable) add("system: available")
                }
                if (candidates.isEmpty()) {
                    add("  ⚠ NO engine available!")
                } else {
                    candidates.forEach { add("  • $it") }
                }
            }
            "vosk" -> {
                add("  vosk.isReady() = $voskReady")
                add("  loadedLang = ${voskCurrentLang.ifBlank { "(none)" }}")
                add("  downloadedLangs = ${downloadedLangs.joinToString(", ").ifBlank { "(none)" }}")
                add("  modelForVoiceLang($voiceBaseLang) = $modelForVoiceLang")
                if (!voskReady) add("  ⚠ Vosk model not loaded")
                if (voskReady && !modelForVoiceLang) add("  ⚠ No model downloaded for voice language '$voiceBaseLang'")
            }
            "whisper" -> {
                add("  useRemote = $asrUseRemote")
                add("  baseUrl = ${if (asrRemoteBaseUrl.isNotBlank()) "set" else "MISSING"}")
                add("  apiKey = ${if (asrRemoteApiKey.isNotBlank()) "set" else "MISSING"}")
                add("  model = ${if (asrRemoteModel.isNotBlank()) "set" else "MISSING"}")
                if (!whisperConfigured) add("  ⚠ Whisper not fully configured")
            }
            "system" -> {
                add("  SpeechRecognizer.isRecognitionAvailable = $systemAvailable")
                if (!systemAvailable) add("  ⚠ System speech recognition not available")
            }
            else -> {
                add("  Unknown engine pref: '$asrEnginePref'")
            }
        }
    }

    val asrSummary = buildString {
        readinessLines.forEach { append(it).append('\n') }
        if (voiceBaseLang.isNotBlank() && !modelForVoiceLang) {
            append("⚠ Voice language '$voiceBaseLang' has no downloaded Vosk model. ASR may fall back or fail.\n")
        }
    }
    val asrLogText = buildString {
        append(asrSummary)
        if (voskDiag.isNotBlank()) append('\n').append(voskDiag) else append('\n')
        if (asrLog.isNotBlank()) append('\n').append(asrLog)
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_logs),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsLogBlock(
                title = stringResource(R.string.logs_app_log_title),
                text = appLog,
                noLogText = stringResource(R.string.logs_no_logs),
                onCopy = { copyText(context, viewModel, "App Log", appLog) },
                onExport = { exportLog(context, viewModel, "=== App Log (All Modules) ===\n$appLog") },
                onClear = {
                    AppLog.clear()
                    appLog = AppLog.getText()
                },
            )

            SettingsLogBlock(
                title = stringResource(R.string.logs_tts_log_title),
                text = ttsLog,
                noLogText = stringResource(R.string.logs_no_logs),
                onCopy = { copyText(context, viewModel, "TTS Log", ttsLog) },
                onExport = { exportLog(context, viewModel, ttsLog) },
                onClear = {
                    TtsManager.clearLog()
                    ttsLog = TtsManager.getLogText()
                },
            )

            SettingsLogBlock(
                title = stringResource(R.string.logs_asr_log_title),
                text = asrLogText,
                noLogText = stringResource(R.string.logs_no_logs),
                onCopy = { copyText(context, viewModel, "ASR Log", asrLogText) },
                onExport = { exportLog(context, viewModel, asrLogText) },
                onClear = null,
            )
        }
    }
}

/** Shared auto-scrolling log block with copy / export / optional clear actions. */
@Composable
private fun SettingsLogBlock(
    title: String,
    text: String,
    noLogText: String,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onClear: (() -> Unit)?,
) {
    SettingsGroup(
        title = title,
        items = listOf({
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = text.ifBlank { noLogText },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onCopy) { Text(stringResource(R.string.logs_copy)) }
                    TextButton(onClick = onExport) { Text(stringResource(R.string.logs_export)) }
                    if (onClear != null) {
                        TextButton(onClick = onClear) { Text(stringResource(R.string.log_clear)) }
                    }
                }
            }
        }),
    )
}

private fun copyText(context: Context, viewModel: ChatViewModel, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    viewModel.emitSnackbar(context.getString(R.string.logs_copied))
}

private fun exportLog(context: Context, viewModel: ChatViewModel, payload: String) {
    val name = CrashReporter.exportDiagnostics(context, payload)
    val msg = if (name != null) context.getString(R.string.logs_exported, name) else context.getString(R.string.logs_export_failed)
    viewModel.emitSnackbar(msg)
}