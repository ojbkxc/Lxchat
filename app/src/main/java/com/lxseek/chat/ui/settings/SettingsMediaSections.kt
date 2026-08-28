package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.ui.components.providerIcon
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.noOpBringIntoView
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════
// Image Generation Section
// ═══════════════════════════════════════════════════════════════

/**
 * Substrings that identify text-to-image models across the major families/vendors. Aims for ~90%
 * coverage of models commonly exposed by OpenAI-compatible providers and aggregators. Matched
 * case-insensitively against the bare model id (provider prefix stripped). The picker also offers a
 * "show all" escape hatch, so a miss here is recoverable and a rare false positive is harmless.
 */
private val IMAGE_MODEL_KEYWORDS = listOf(
    // OpenAI
    "dall-e", "dalle", "gpt-image",
    // Google
    "imagen", "image-generation", "imagegeneration", "nano-banana",
    // Stability AI
    "stable-diffusion", "stablediffusion", "stable-image", "stable-cascade",
    "sdxl", "sd3", "sd-turbo", "sd1.5", "sd-1", "sd-2", "sd2.",
    // Black Forest Labs (FLUX)
    "flux", "kontext",
    // Midjourney
    "midjourney", "niji",
    // ByteDance
    "seedream", "seededit", "dreamina", "jimeng",
    // Alibaba
    "wanx", "wanxiang", "qwen-image",
    // Baidu
    "ernie-vilg", "irag",
    // Tencent
    "hunyuan-image", "hunyuan-dit", "hunyuandit",
    // Zhipu
    "cogview",
    // Kuaishou
    "kolors",
    // Other vendors
    "ideogram", "recraft", "playground-v", "playgroundai",
    "photon", "firefly", "titan-image", "nova-canvas",
    "kandinsky", "pixart", "wuerstchen", "deepfloyd",
    "janus", "hidream", "auraflow", "omnigen", "lumina",
    "step1x", "step-1x", "sana-",
    // popular community SD fine-tunes seen on aggregators
    "dreamshaper", "juggernaut", "epicrealism", "realistic-vision",
    "pony", "animagine", "illustrious", "noobai",
)

private fun isLikelyImageModel(modelId: String): Boolean {
    val id = modelId.substringAfter(":").lowercase()
    return IMAGE_MODEL_KEYWORDS.any { id.contains(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageGenSection(viewModel: ChatViewModel) {
    val enabled by viewModel.settings.imageGenEnabled.collectAsState()
    val selectedModel by viewModel.settings.imageGenModel.collectAsState()
    val size by viewModel.settings.imageGenSize.collectAsState()
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    var showModelDialog by remember { mutableStateOf(false) }
    var showAllModels by remember { mutableStateOf(false) }

    // Source from ALL synced models (image models needn't be enabled for chat). Default to the
    // image-likely subset so the list stays short; "show all" is the escape hatch for odd names.
    val allModels = remember(availableModels) { availableModels.values.flatten().distinct().sorted() }
    val imageModels = remember(allModels) { allModels.filter { isLikelyImageModel(it) } }
    val pickList = if (showAllModels || imageModels.isEmpty()) allModels else imageModels

    SettingsGroupColumn {
        SettingsGroup(title = stringResource(R.string.settings_image_gen), items = listOf({
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.image_gen_enable)) },
                supportingContent = { Text(stringResource(R.string.image_gen_enable_desc)) },
                leadingContent = { Icon(Icons.Default.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = { viewModel.settings.setImageGenEnabled(it) })
                },
                modifier = Modifier.clickable { viewModel.settings.setImageGenEnabled(!enabled) }
            )
        }))

        if (enabled) {
            SettingsGroup(title = stringResource(R.string.image_gen_model), items = listOf({
                // Only a properly prefixed "Provider:modelId" counts as a real selection;
                // legacy/bare ids (e.g. an old "gpt-image-1") render as "no model selected".
                val parsed = selectedModel?.takeIf { it.contains(":") }?.let { ModelId.parse(it) }
                val displayName = parsed?.let { modelAliases[selectedModel] ?: it.apiModelName }
                    ?: stringResource(R.string.image_gen_no_model)
                val providerName = parsed?.providerName
                val iconRes = providerName?.let { providerIcon(it) } ?: 0
                SettingsItem(
                    headlineContent = {
                        Text(displayName, color = if (parsed == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    },
                    supportingContent = if (providerName != null) {
                        { Text(providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
                    } else null,
                    leadingContent = {
                        when {
                            parsed == null -> Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            providerName.equals(Constants.PROVIDER_LOCAL, ignoreCase = true) -> Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            iconRes != 0 -> Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            else -> Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        }
                    },
                    modifier = Modifier.heightIn(min = 64.dp).clickable { showModelDialog = true }
                )
            }))

            // Default size — width × height
            SettingsGroup(title = stringResource(R.string.image_gen_size), items = listOf({
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.AspectRatio, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.image_gen_size), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                            val parts = remember { size.split("x", "X", limit = 2) }
                            val wState = remember { TextFieldState(parts.getOrNull(0)?.trim().orEmpty().ifEmpty { "1024" }) }
                            val hState = remember { TextFieldState(parts.getOrNull(1)?.trim().orEmpty().ifEmpty { "1024" }) }
                            LaunchedEffect(wState.text, hState.text) {
                                delay(500)
                                val w = wState.text.toString().trim()
                                val h = hState.text.toString().trim()
                                if (w.isNotEmpty() && h.isNotEmpty()) viewModel.settings.setImageGenSize("${w}x${h}")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).noOpBringIntoView(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    state = wState,
                                    placeholder = { Text("1024") },
                                    lineLimits = TextFieldLineLimits.SingleLine,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                Text("×", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                                OutlinedTextField(
                                    state = hState,
                                    placeholder = { Text("1024") },
                                    lineLimits = TextFieldLineLimits.SingleLine,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }
            }))
        }
    }

    if (showModelDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showModelDialog = false },
            title = { Text(stringResource(R.string.image_gen_select_model), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (imageModels.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { showAllModels = !showAllModels }
                        ) {
                            Checkbox(checked = showAllModels, onCheckedChange = { showAllModels = it })
                            Text(stringResource(R.string.image_gen_show_all), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (pickList.isEmpty()) {
                        Text(stringResource(R.string.transcription_no_models_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(pickList, key = { it }) { model ->
                            val parsed = ModelId.parse(model)
                            val displayName = modelAliases[model] ?: parsed.apiModelName
                            SettingsItem(
                                headlineContent = { Text(displayName, fontWeight = if (selectedModel == model) FontWeight.Bold else FontWeight.Normal) },
                                supportingContent = { Text(parsed.providerName, style = MaterialTheme.typography.bodySmall) },
                                leadingContent = {
                                    RadioButton(selected = selectedModel == model, onClick = {
                                        viewModel.settings.setImageGenModel(model); showModelDialog = false
                                    })
                                },
                                modifier = Modifier.clickable {
                                    viewModel.settings.setImageGenModel(model); showModelDialog = false
                                }
                            )
                        }
                    }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Web Search Section
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebSearchSection(viewModel: ChatViewModel) {
    val webSearchEnabled by viewModel.settings.webSearchEnabled.collectAsState()
    val webSearchProvider by viewModel.settings.webSearchProvider.collectAsState()
    val webSearchApiKeys by viewModel.settings.webSearchApiKeys.collectAsState()
    val webSearchNumResults by viewModel.settings.webSearchNumResults.collectAsState()
    val webSearchBaseUrl by viewModel.settings.webSearchBaseUrl.collectAsState()
    var showProviderDialog by remember { mutableStateOf(false) }
    var apiKeyText by remember(webSearchProvider) { mutableStateOf(webSearchApiKeys[webSearchProvider] ?: "") }
    LaunchedEffect(webSearchProvider) { apiKeyText = webSearchApiKeys[webSearchProvider] ?: "" }

    SettingsGroupColumn {
        SettingsGroup(title = stringResource(R.string.web_search_title), items = buildList {
            add {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.web_search_enable)) },
                    supportingContent = { Text(stringResource(R.string.web_search_enable_desc)) },
                    leadingContent = { Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = webSearchEnabled, onCheckedChange = { viewModel.settings.setWebSearchEnabled(it) })
                    },
                    modifier = Modifier.clickable { viewModel.settings.setWebSearchEnabled(!webSearchEnabled) }
                )
            }

            if (webSearchEnabled) {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.web_search_provider_label)) },
                        supportingContent = {
                            Text(
                                when (webSearchProvider) {
                                    "searxng" -> stringResource(R.string.web_search_searxng)
                                    "kagi" -> stringResource(R.string.web_search_kagi)
                                    "serper" -> stringResource(R.string.web_search_serper)
                                    "tavily" -> stringResource(R.string.web_search_tavily)
                                    "duckduckgo" -> stringResource(R.string.web_search_duckduckgo)
                                    else -> stringResource(R.string.web_search_brave)
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { showProviderDialog = true }
                    )
                }

                if (webSearchProvider != "searxng" && webSearchProvider != "duckduckgo") {
                    add {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(
                                            when (webSearchProvider) {
                                                "kagi" -> R.string.web_search_kagi_key
                                                "serper" -> R.string.web_search_serper_key
                                                "tavily" -> R.string.web_search_tavily_key
                                                else -> R.string.web_search_brave_key
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                        OutlinedTextField(
                                            value = apiKeyText,
                                            onValueChange = { apiKeyText = it; viewModel.settings.setWebSearchApiKey(webSearchProvider, it) },
                                            placeholder = {
                                                Text(
                                                    stringResource(
                                                        when (webSearchProvider) {
                                                            "kagi" -> R.string.web_search_kagi_key_hint
                                                            "serper" -> R.string.web_search_serper_key_hint
                                                            "tavily" -> R.string.web_search_tavily_key_hint
                                                            else -> R.string.web_search_brave_key_hint
                                                        }
                                                    )
                                                )
                                            },
                                            visualTransformation = PasswordVisualTransformation(),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (webSearchProvider == "searxng") {
                    add {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(painter = painterResource(id = com.lxseek.chat.R.drawable.link_24), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.web_search_searxng_url), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                                    // Don't key on webSearchBaseUrl — that causes TextFieldState to be
                                    // recreated every time the debounced save writes to DataStore.
                                    val urlState = remember { TextFieldState(webSearchBaseUrl) }
                                    // Sync external changes (e.g. import) back into the text field.
                                    LaunchedEffect(webSearchBaseUrl) {
                                        val cur = urlState.text.toString()
                                        if (webSearchBaseUrl.isNotEmpty() && webSearchBaseUrl != cur) {
                                            urlState.edit { replace(0, length, webSearchBaseUrl) }
                                        }
                                    }
                                    // Save user input with 500ms debounce.
                                    LaunchedEffect(urlState.text) {
                                        delay(500)
                                        viewModel.settings.setWebSearchBaseUrl(urlState.text.toString())
                                    }
                                    Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                        OutlinedTextField(
                                            state = urlState,
                                            placeholder = { Text(stringResource(R.string.web_search_searxng_url_hint)) },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                    Text(
                                        stringResource(R.string.web_search_searxng_fallback_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        })

        if (webSearchEnabled) {
            SettingsGroup(title = stringResource(R.string.web_search_advanced), items = buildList {
                add {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.web_search_num_results),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.web_search_num_results_desc, webSearchNumResults),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Slider(
                                    value = webSearchNumResults.toFloat(),
                                    onValueChange = { viewModel.settings.setWebSearchNumResults(it.toInt()) },
                                    valueRange = 1f..10f,
                                    steps = 8,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            })
        }
    }

    if (showProviderDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showProviderDialog = false },
            title = { Text(stringResource(R.string.web_search_select_provider), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    val providers = listOf(
                        "brave" to R.string.web_search_brave,
                        "kagi" to R.string.web_search_kagi,
                        "serper" to R.string.web_search_serper,
                        "tavily" to R.string.web_search_tavily,
                        "searxng" to R.string.web_search_searxng,
                        "duckduckgo" to R.string.web_search_duckduckgo
                    )
                    providers.forEach { (key, labelRes) ->
                        SettingsItem(
                            headlineContent = { Text(stringResource(labelRes), fontWeight = if (webSearchProvider == key) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        when (key) {
                                            "brave" -> R.string.web_search_brave_desc
                                            "kagi" -> R.string.web_search_kagi_desc
                                            "serper" -> R.string.web_search_serper_desc
                                            "tavily" -> R.string.web_search_tavily_desc
                                            "searxng" -> R.string.web_search_searxng_desc
                                            "duckduckgo" -> R.string.web_search_duckduckgo_desc
                                            else -> R.string.web_search_brave_desc
                                        }
                                    )
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = webSearchProvider == key,
                                    onClick = {
                                        viewModel.settings.setWebSearchProvider(key)
                                        showProviderDialog = false
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setWebSearchProvider(key)
                                showProviderDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProviderDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}