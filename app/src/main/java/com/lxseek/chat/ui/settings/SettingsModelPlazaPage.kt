package com.lxseek.chat.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.ui.components.LxChatBrandMark
import com.lxseek.chat.ui.components.LxChatEmptyState
import com.lxseek.chat.ui.components.providerIcon
import com.lxseek.chat.util.Constants
import com.lxseek.chat.viewmodel.ChatViewModel

/**
 * Market-style model discovery ("模型广场"): browse a curated catalog of well-known models
 * grouped by provider, with one-tap add-to-custom-models, quick set-as-default, and an inline
 * API-key prompt when a provider has no configured key. Branded by [LxChatBrandMark] at the top
 * so the plaza reads as part of the same empty-state/onboarding identity.
 */
@Composable
fun SettingsModelPlazaPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val settings = viewModel.settings
    val customModels by settings.customModels.collectAsState()
    val selectedModel by settings.selectedModel.collectAsState()
    val apiKeys by settings.apiKeys.collectAsState()
    val activeApiKeyIds by settings.activeApiKeyIds.collectAsState()
    val availableModels by settings.availableModels.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    // Provider -> id of the active key, so cards can show "configured" status.
    val activeKeyByProvider = activeApiKeyIds
    val keyExistsByProvider = apiKeys.groupBy { it.provider }.keys.toSet()

    val catalog = rememberCatalog()
    val normalizedQuery = query.trim()
    val filteredFeatured = catalog.filter { entry ->
        if (normalizedQuery.isEmpty()) return@filter true
        val haystack = buildString {
            append(entry.provider)
            append(entry.model)
            append(entry.tagline)
        }
        haystack.contains(normalizedQuery, ignoreCase = true)
    }

    // Providers present in the live fetch, merged with the curated catalog for discovery depth.
    val discoverableProviders = remember(availableModels) {
        (catalog.map { it.provider } + availableModels.keys).distinct()
    }

    var pendingProvider by remember { mutableStateOf<String?>(null) }
    var keyName by rememberSaveable { mutableStateOf("") }
    var keySecret by rememberSaveable { mutableStateOf("") }

    @Composable
    fun resolveProviderKeyState(provider: String): Triple<Boolean, Boolean, String> {
        val hasKey = provider in keyExistsByProvider
        val isActive = !activeKeyByProvider[provider].isNullOrBlank()
        val label = if (isActive) {
            stringResource(R.string.plaza_key_active)
        } else if (hasKey) {
            stringResource(R.string.plaza_key_present)
        } else {
            stringResource(R.string.plaza_key_missing)
        }
        return Triple(hasKey, isActive, label)
    }

    fun addModel(provider: String, model: String, alias: String) {
        settings.addCustomModel(provider = provider, modelName = model, alias = alias)
        val modelId = ModelId(provider, model).prefixed
        settings.setSelectedModel(modelId)
    }

    fun onSubmitKey(provider: String) {
        val name = keyName.trim()
        val secret = keySecret.trim()
        if (name.isEmpty() || secret.isEmpty()) return
        settings.upsertApiKey(name = name, key = secret, provider = provider)
        keyName = ""
        keySecret = ""
        pendingProvider = null
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.plaza_title),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LxChatBrandMark(size = 56.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.plaza_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.plaza_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )

            SectionLabel(
                text = stringResource(R.string.plaza_featured),
                firstInPage = true,
            )

            if (filteredFeatured.isEmpty()) {
                LxChatEmptyState(
                    modifier = Modifier.padding(vertical = 8.dp),
                    title = stringResource(R.string.plaza_no_result_title),
                    description = stringResource(R.string.plaza_no_result_desc),
                    markSize = 56.dp,
                )
            } else {
                filteredFeatured.forEach { entry ->
                    keyStateEntry(
                        provider = entry.provider,
                        model = entry.model,
                        alias = entry.alias,
                        tagline = entry.tagline,
                        alreadyAdded = entry.model in customModels || ModelId(entry.provider, entry.model).prefixed in customModels,
                        isDefault = selectedModel == ModelId(entry.provider, entry.model).prefixed,
                        keyState = resolveProviderKeyState(entry.provider),
                        addModel = { addModel(entry.provider, entry.model, entry.alias) },
                        onSetDefault = { settings.setSelectedModel(ModelId(entry.provider, entry.model).prefixed) },
                        onNeedKey = { pendingProvider = entry.provider },
                    )
                }
            }

            SectionLabel(text = stringResource(R.string.plaza_discover))

            discoverableProviders.forEach { provider ->
                DiscoverProviderCard(
                    provider = provider,
                    count = availableModels[provider].orEmpty().size,
                    hasFetch = availableModels[provider].orEmpty().isNotEmpty(),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    pendingProvider?.let { provider ->
        AlertDialog(
            onDismissRequest = { pendingProvider = null },
            title = { Text(stringResource(R.string.plaza_key_dialog_title, provider)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = keyName,
                        onValueChange = { keyName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.plaza_key_name)) },
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = keySecret,
                        onValueChange = { keySecret = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.plaza_key_secret)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onSubmitKey(provider) },
                    enabled = keyName.isNotBlank() && keySecret.isNotBlank(),
                ) {
                    Text(stringResource(R.string.plaza_key_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProvider = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * The curated "featured" catalog shown in the plaza. Keeping entries here (rather than in a
 * resource file) lets each model carry a provider + short tagline in one place for the marketplace
 * feel; new popular models can be added without touching localization.
 */
private data class PlazaEntry(
    val provider: String,
    val model: String,
    val alias: String,
    val tagline: String,
) {
    val key: String get() = "$provider/$model"
}

@Composable
private fun rememberCatalog(): List<PlazaEntry> {
    val t = stringResource(R.string.plaza_tag_recommended)
    return remember(t) {
        listOf(
            PlazaEntry(Constants.PROVIDER_OPENAI, "gpt-4o", "GPT-4o", "$t · OpenAI"),
            PlazaEntry(Constants.PROVIDER_OPENAI, "gpt-4o-mini", "GPT-4o mini", "$t · OpenAI"),
            PlazaEntry(Constants.PROVIDER_OPENAI, "o3-mini", "o3 mini", "$t · OpenAI"),
            PlazaEntry(Constants.PROVIDER_GOOGLE, "gemini-2.5-flash", "Gemini 2.5 Flash", "$t · Google"),
            PlazaEntry(Constants.PROVIDER_GOOGLE, "gemini-2.5-pro", "Gemini 2.5 Pro", "$t · Google"),
            PlazaEntry(Constants.PROVIDER_ANTHROPIC, "claude-3-7-sonnet", "Claude 3.7 Sonnet", "$t · Anthropic"),
            PlazaEntry(Constants.PROVIDER_ANTHROPIC, "claude-3-5-haiku", "Claude 3.5 Haiku", "$t · Anthropic"),
            PlazaEntry(Constants.PROVIDER_DEEPSEEK, "deepseek-chat", "DeepSeek V3", "$t · DeepSeek"),
            PlazaEntry(Constants.PROVIDER_DEEPSEEK, "deepseek-reasoner", "DeepSeek R1", "$t · DeepSeek"),
            PlazaEntry(Constants.PROVIDER_QWEN, "qwen3-max", "Qwen3 Max", "$t · Qwen"),
            PlazaEntry(Constants.PROVIDER_QWEN, "qwen-turbo", "Qwen Turbo", "$t · Qwen"),
            PlazaEntry(Constants.PROVIDER_GROQ, "llama-3.3-70b-versatile", "LLaMA 3.3 70B", "$t · Groq"),
            PlazaEntry(Constants.PROVIDER_OLLAMA, "llama3.2", "LLaMA 3.2", "$t · Ollama"),
            PlazaEntry(Constants.PROVIDER_OLLAMA, "qwen2.5", "Qwen 2.5", "$t · Ollama"),
        )
    }
}

@Composable
private fun keyStateEntry(
    provider: String,
    model: String,
    alias: String,
    tagline: String,
    alreadyAdded: Boolean,
    isDefault: Boolean,
    keyState: Triple<Boolean, Boolean, String>,
    addModel: () -> Unit,
    onSetDefault: () -> Unit,
    onNeedKey: () -> Unit,
) {
    val icon = providerIcon(provider)
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = rememberIconPainter(icon),
                    contentDescription = provider,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alias,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isDefault) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = stringResource(R.string.plaza_default),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { },
                    label = { Text(tagline) },
                )
                Spacer(Modifier.weight(1f))
                if (alreadyAdded) {
                    TextButton(onClick = onSetDefault) {
                        Icon(
                            if (isDefault) Icons.Default.Check else Icons.Default.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isDefault) stringResource(R.string.plaza_active)
                            else stringResource(R.string.plaza_make_default),
                        )
                    }
                } else {
                    TextButton(onClick = { if (keyState.first) addModel() else onNeedKey() }) {
                        Icon(
                            if (keyState.first) Icons.Default.Add else Icons.Default.Key,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (keyState.first) stringResource(R.string.plaza_add)
                            else stringResource(R.string.plaza_configure),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverProviderCard(
    provider: String,
    count: Int,
    hasFetch: Boolean,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = rememberIconPainter(providerIcon(provider)),
                contentDescription = provider,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (hasFetch) stringResource(R.string.plaza_models_found, count)
                    else stringResource(R.string.plaza_provider_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun rememberIconPainter(res: Int): Painter {
    val context = LocalContext.current
    return androidx.compose.ui.res.painterResource(res)
}