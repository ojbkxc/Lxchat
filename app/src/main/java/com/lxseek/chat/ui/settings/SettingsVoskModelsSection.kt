package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.speech.VoskTranscriber
import kotlinx.coroutines.launch

// Friendly display names for base language codes used by Vosk models.
// Keys are the distinct base codes derived from VoskTranscriber.AVAILABLE_LANGUAGES.
private val BASE_LANGUAGE_DISPLAY_NAMES: Map<String, String> = mapOf(
    "en" to "English (en)",
    "zh" to "中文 (zh)",
    "ru" to "Русский (ru)",
    "de" to "Deutsch (de)",
    "es" to "Español (es)",
    "fr" to "Français (fr)",
    "it" to "Italiano (it)",
    "pt" to "Português (pt)",
    "ja" to "日本語 (ja)",
    "uk" to "Українська (uk)",
    "pl" to "Polski (pl)",
    "hi" to "हिन्दी (hi)",
    "ko" to "한국어 (ko)",
    "tr" to "Türkçe (tr)",
    "vi" to "Tiếng Việt (vi)",
    "nl" to "Nederlands (nl)",
    "ca" to "Català (ca)",
    "fa" to "فارسی (fa)",
    "kz" to "Қазақ (kz)",
    "sv" to "Svenska (sv)",
    "cs" to "Čeština (cs)",
    "el" to "Ελληνικά (el)",
    "id" to "Bahasa Indonesia (id)",
)

@Composable
fun SettingsVoskModelsSection(
    context: android.content.Context,
    voskTranscriber: VoskTranscriber,
    voiceLanguage: String,
    onVoiceLanguageChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val downloadProgress by voskTranscriber.downloadProgress.collectAsState()
    val downloadingFor = remember { mutableStateMapOf<String, String>() }

    // Language selector: derive the distinct base codes (en, zh, ...) from the
    // available Vosk model list so the user picks the recognition language that
    // is later passed to VoskTranscriber.initialize().
    val baseLanguages = remember {
        VoskTranscriber.AVAILABLE_LANGUAGES
            .map { VoskTranscriber.getBaseLanguageCode(it.code) }
            .distinct()
            .sorted()
    }
    var langMenuOpen by remember { mutableStateOf(false) }

    // Downloaded languages (recomputed on refresh tick).
    val downloaded = run { refresh; voskTranscriber.getDownloadedLanguages() }
    // Map base code -> whether at least one model for that base code is downloaded.
    val downloadedBaseCodes: Set<String> = downloaded
        .map { VoskTranscriber.getBaseLanguageCode(it) }
        .toSet()
    val currentBase = VoskTranscriber.getBaseLanguageCode(voiceLanguage.ifBlank { "en" })
    val currentLangHasModel = downloadedBaseCodes.contains(currentBase)

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.vosk_models_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ── Prominent recognition language selector ──
        // This card is the primary entry point for choosing the Vosk recognition
        // language. It surfaces the current language, its download status, and a
        // warning when the selected language has no downloaded model (the most
        // common reason Vosk ASR silently fails to transcribe).
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.asr_voice_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { langMenuOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = BASE_LANGUAGE_DISPLAY_NAMES[currentBase] ?: currentBase,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (currentLangHasModel) "✓" else "⚠",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (currentLangHasModel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    DropdownMenu(
                        expanded = langMenuOpen,
                        onDismissRequest = { langMenuOpen = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 6.dp,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        for (code in baseLanguages) {
                            val hasModel = downloadedBaseCodes.contains(code)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = (BASE_LANGUAGE_DISPLAY_NAMES[code] ?: code) +
                                            if (hasModel) "  ✓" else "",
                                        color = if (hasModel) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    langMenuOpen = false
                                    onVoiceLanguageChange(code)
                                },
                            )
                        }
                    }
                }
                // Mismatch warning: current language has no downloaded model.
                if (!currentLangHasModel) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "⚠ No Vosk model downloaded for \"$currentBase\". " +
                            "Vosk ASR will not work until you download a model below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ready: ${voskTranscriber.isReady()} | Loaded lang: ${voskTranscriber.getCurrentLanguage()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (voskTranscriber.isReady()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // ── Model list (download / delete) ──
        for (model in VoskTranscriber.AVAILABLE_LANGUAGES) {
            Spacer(modifier = Modifier.height(4.dp))
            val isDownloaded = downloaded.contains(model.code)
            val isDownloading = downloadingFor.containsKey(model.code)
            VoskModelRow(
                label = model.displayName,
                sizeHint = "${model.sizeBytes / 1_000_000}MB",
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                progress = if (isDownloading) downloadProgress else null,
                onDownload = {
                    scope.launch {
                        downloadingFor[model.code] = "downloading"
                        voskTranscriber.downloadModel(model.code).collect { state ->
                            when (state) {
                                is VoskTranscriber.DownloadState.Downloading -> {
                                    downloadingFor[model.code] = "downloading"
                                }
                                is VoskTranscriber.DownloadState.Extracting -> {
                                    downloadingFor[model.code] = "extracting"
                                }
                                is VoskTranscriber.DownloadState.Complete -> {
                                    downloadingFor.remove(model.code)
                                    refresh++
                                }
                                is VoskTranscriber.DownloadState.Error -> {
                                    downloadingFor.remove(model.code)
                                    refresh++
                                }
                                else -> {}
                            }
                        }
                    }
                },
                onDelete = {
                    voskTranscriber.deleteModel(model.code)
                    refresh++
                },
            )
        }
    }
}

@Composable
private fun VoskModelRow(
    label: String,
    sizeHint: String,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    progress: Int?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = sizeHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isDownloading && progress != null && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isDownloaded) {
            OutlinedButton(onClick = onDelete, enabled = !isDownloading) {
                Text(stringResource(R.string.vosk_model_delete))
            }
        } else {
            OutlinedButton(onClick = onDownload, enabled = !isDownloading) {
                Text(
                    if (isDownloading && progress != null && progress in 1..99)
                        stringResource(R.string.vosk_model_downloading, progress)
                    else if (isDownloading)
                        stringResource(R.string.vosk_model_extracting)
                    else
                        stringResource(R.string.vosk_model_download)
                )
            }
        }
    }
}
