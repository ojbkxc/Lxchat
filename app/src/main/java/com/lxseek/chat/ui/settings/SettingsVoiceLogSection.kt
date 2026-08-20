package com.lxseek.chat.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.speech.VoskTranscriber
import com.lxseek.chat.util.AppLog
import com.lxseek.chat.util.CrashReporter
import com.lxseek.chat.util.TtsDiagnosticInfo
import com.lxseek.chat.util.TtsManager
import kotlinx.coroutines.delay

private val ASR_LOG_TAGS = setOf(
    "VoiceConvCtrl",
    "SpeechRecognizerMgr",
    "VoskTranscriber",
    "WhisperTranscriber",
    "AudioCaptureManager",
)

@Composable
fun SettingsVoiceLogSection(
    context: Context,
    ttsDiagnostic: TtsDiagnosticInfo,
    ttsInitStatus: String,
    ttsSpeakResult: String,
    ttsLangResult: String,
    voskTranscriber: VoskTranscriber,
) {
    var ttsLogText by remember { mutableStateOf(TtsManager.getLogText()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000L)
            ttsLogText = TtsManager.getLogText()
        }
    }

    var asrLogText by remember { mutableStateOf(AppLog.getFilteredText(ASR_LOG_TAGS, 30)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000L)
            asrLogText = AppLog.getFilteredText(ASR_LOG_TAGS, 30)
        }
    }

    SettingsGroup(
        title = stringResource(R.string.voice_log_section_title),
        items = listOf(
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.voice_log_tts_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.tts_engine_info),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = if (ttsDiagnostic.availableEngines.isEmpty()) {
                            stringResource(R.string.tts_no_engine)
                        } else {
                            "${ttsDiagnostic.engineName ?: "unknown"} (${ttsDiagnostic.availableEngines.joinToString(", ")})"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "Init: $ttsInitStatus | Speak: ${ttsSpeakResult.ifEmpty { "—" }} | Lang: ${ttsLangResult.ifEmpty { "—" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (ttsLogText.isNotBlank()) {
                        Text(
                            text = ttsLogText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = {
                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                putExtra(android.content.Intent.EXTRA_TEXT, TtsManager.getLogText())
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, "TTS Log"))
                        }) { Text(stringResource(R.string.tts_export_log)) }
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TTS Log", TtsManager.getLogText()))
                        }) { Text(stringResource(R.string.tts_copy_log)) }
                        TextButton(onClick = {
                            val name = CrashReporter.exportDiagnostics(
                                context,
                                "=== AppLog (all modules) ===\n" +
                                    AppLog.getText() + "\n\n" +
                                    TtsManager.getLogText() + "\n\n" +
                                    voskTranscriber.getDiagnosticText(),
                            )
                            if (name != null) {
                                android.widget.Toast.makeText(context, "Saved to Downloads/LxChat/$name", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Failed to save", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(stringResource(R.string.tts_save_to_downloads)) }
                        TextButton(onClick = {
                            AppLog.clear()
                            TtsManager.clearLog()
                            ttsLogText = TtsManager.getLogText()
                            android.widget.Toast.makeText(context, "Logs cleared", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.log_clear)) }
                    }
                }
            },
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.voice_log_asr_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = asrLogText.ifBlank { stringResource(R.string.asr_no_logs_yet) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val payload = AppLog.getFilteredText(ASR_LOG_TAGS, 200) + "\n\n" + voskTranscriber.getDiagnosticText()
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ASR Log", payload))
                        }) { Text(stringResource(R.string.asr_copy_log)) }
                        TextButton(onClick = {
                            val payload = "=== ASR Log ===\n" +
                                AppLog.getFilteredText(ASR_LOG_TAGS, 200) + "\n\n" +
                                voskTranscriber.getDiagnosticText()
                            val name = CrashReporter.exportDiagnostics(context, payload)
                            if (name != null) {
                                android.widget.Toast.makeText(context, "Saved to Downloads/LxChat/$name", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Failed to save", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(stringResource(R.string.asr_save_to_downloads)) }
                        TextButton(onClick = {
                            AppLog.clear()
                            asrLogText = AppLog.getFilteredText(ASR_LOG_TAGS, 30)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.asr_log_cleared),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }) { Text(stringResource(R.string.asr_clear_log)) }
                    }
                }
            },
        ),
    )
}