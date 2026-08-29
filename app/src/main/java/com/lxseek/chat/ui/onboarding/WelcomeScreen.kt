package com.lxseek.chat.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.data.CustomEndpointProtocol
import com.lxseek.chat.data.LocalChatModelConfig
import com.lxseek.chat.ui.components.CustomEndpointProtocolSelector
import com.lxseek.chat.ui.components.TypewriterMode
import com.lxseek.chat.ui.components.TypewriterText
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.ui.components.clearFocusOnTap
import com.lxseek.chat.ui.components.providerIcon
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.util.Constants
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import androidx.compose.ui.platform.LocalConfiguration

data class WelcomePage(
    val title: String,
    val description: String,
    val icon: ImageVector? = null,
)

// Page indices — these map directly to positions in the `pages` list below.
// Indices 1 (BYOK intro), 4 (model video) and 7 (done) are transitional pages
// that don't need a named constant, which is why the constants appear to skip.
// The underlying pager indices are already contiguous 0..7.
private const val PAGE_WELCOME = 0
private const val PAGE_PROVIDER = 2
private const val PAGE_API_KEY = 3
private const val PAGE_MODEL_CONFIG = 5
private const val PAGE_AUTO_BACKUP = 6

@Composable
fun WelcomeScreen(
    onComplete: () -> Unit,
    isDarkTheme: Boolean = true,
    viewModel: ChatViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motionPolicy = LocalLxChatMotionPolicy.current

    // ── Onboarding state ──
    val builtInProviders = listOf(
        Constants.PROVIDER_GOOGLE, Constants.PROVIDER_OPENAI, Constants.PROVIDER_ANTHROPIC,
        Constants.PROVIDER_DEEPSEEK, Constants.PROVIDER_QWEN, Constants.PROVIDER_GROQ,
        Constants.PROVIDER_OLLAMA, Constants.PROVIDER_OPEN_ROUTER
    )
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val allProviders = (builtInProviders + customProviders.map { it.name } + "Custom" + Constants.PROVIDER_LOCAL).distinct()
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    // True for any user-defined endpoint (the "Custom" slot or an already-created
    // provider). Its selected wire protocol determines request and response handling.
    val isCustomProvider = selectedProvider != null &&
        selectedProvider != Constants.PROVIDER_LOCAL && selectedProvider != Constants.PROVIDER_OLLAMA &&
        selectedProvider !in builtInProviders
    var apiKeyText by remember { mutableStateOf("") }
    var baseUrlText by remember { mutableStateOf("") }
    var customProtocol by remember { mutableStateOf(CustomEndpointProtocol.OPENAI) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    val autoBackupEnabled by viewModel.settings.autoBackupEnabled.collectAsState()
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val localChatModels by viewModel.settings.localChatModels.collectAsState()
    val existingApiKeys by viewModel.settings.apiKeys.collectAsState()
    val existingProviderUrls by viewModel.settings.providerBaseUrls.collectAsState()

    // Pre-fill API key / URL when switching to a configured provider
    LaunchedEffect(selectedProvider, customProviders) {
        val p = selectedProvider ?: return@LaunchedEffect
        customProtocol = customProviders
            .firstOrNull { it.name == p }
            ?.protocol
            ?: CustomEndpointProtocol.OPENAI
        when {
            p == Constants.PROVIDER_OLLAMA -> {
                val url = existingProviderUrls[Constants.PROVIDER_OLLAMA]
                if (!url.isNullOrBlank()) apiKeyText = url
            }
            p == Constants.PROVIDER_LOCAL -> { /* no pre-fill */ }
            p != "Custom" && p !in builtInProviders -> {
                // Existing custom provider: pre-fill both its URL and key.
                existingProviderUrls[p]?.takeIf { it.isNotBlank() }?.let { baseUrlText = it }
                existingApiKeys.find { it.provider == p }?.key?.takeIf { it.isNotBlank() }?.let { apiKeyText = it }
            }
            else -> {
                val key = existingApiKeys.find { it.provider == p }?.key
                if (!key.isNullOrBlank()) apiKeyText = key
            }
        }
    }

    // ── GGUF import ──
    var showGgufError by remember { mutableStateOf(false) }
    var isImportingGGUF by remember { mutableStateOf(false) }
    val ggufPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isImportingGGUF = true
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val dest = File(context.filesDir, "chat_model_${UUID.randomUUID()}.gguf")
                        try {
                            val aliasName =
                                com.lxseek.chat.util.FileValidator.resolveFileName(context, uri)
                                    ?.let {
                                        if (
                                            it.substringAfterLast('.', "")
                                                .equals("gguf", ignoreCase = true)
                                        ) {
                                            it.substringBeforeLast('.')
                                        } else {
                                            it
                                        }
                                    }
                                    ?.trim()
                                    ?.ifBlank { null }
                                    ?: dest.nameWithoutExtension
                            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                                true
                            } == true
                            if (!copied) {
                                dest.delete()
                                return@withContext null
                            }
                            val magic = ByteArray(4)
                            val bytesRead = dest.inputStream().use { it.read(magic) }
                            val valid = bytesRead == magic.size &&
                                magic[0] == 'G'.code.toByte() &&
                                magic[1] == 'G'.code.toByte() &&
                                magic[2] == 'U'.code.toByte() &&
                                magic[3] == 'F'.code.toByte()
                            if (valid) {
                                Triple(dest.nameWithoutExtension, aliasName, dest.absolutePath)
                            } else {
                                dest.delete()
                                null
                            }
                        } catch (error: Exception) {
                            dest.delete()
                            throw error
                        }
                    }
                    if (imported == null) {
                        showGgufError = true
                    } else {
                        val (modelId, aliasName, path) = imported
                        localChatModels.forEach { viewModel.modelManager.deleteLocalChatModel(it.id) }
                        viewModel.modelManager.addLocalChatModel(
                            LocalChatModelConfig(
                                modelId = modelId,
                                alias = aliasName,
                                localFilePath = path,
                            )
                        )
                    }
                } catch (_: Exception) {
                    showGgufError = true
                } finally {
                    isImportingGGUF = false
                }
            }
        }
    }

    // ── Pages ──
    val pages = listOf(
        WelcomePage(stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_desc),
            icon = Icons.Filled.AutoAwesome),
        WelcomePage(stringResource(R.string.onboarding_byok_title), stringResource(R.string.onboarding_byok_desc),
            icon = Icons.Filled.Lock),
        WelcomePage(stringResource(R.string.onboarding_provider_title), stringResource(R.string.onboarding_provider_desc)),
        WelcomePage(stringResource(R.string.onboarding_api_key_title), stringResource(R.string.onboarding_api_key_desc)),
        WelcomePage(stringResource(R.string.onboarding_model_video_title), stringResource(R.string.onboarding_model_video_desc),
            icon = Icons.Filled.Cloud),
        WelcomePage(stringResource(R.string.onboarding_model_select_title), stringResource(R.string.onboarding_model_select_desc)),
        WelcomePage(stringResource(R.string.onboarding_auto_backup_title), stringResource(R.string.onboarding_auto_backup_desc)),
        WelcomePage(stringResource(R.string.onboarding_done_title), stringResource(R.string.onboarding_done_desc),
            icon = Icons.Filled.CheckCircle)
    )

    val visitedPages = remember { mutableSetOf<Int>() }
    val typedPages = remember { mutableSetOf<Int>() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    var exiting by remember { mutableStateOf(false) }
    var showContent by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(if (showContent) 1f else 0f, tween(600))

    // Persist whatever the API Key page collected for the selected provider. Custom
    // providers register their base URL (creating the provider if new) plus key; the
    // built-in/Ollama/Local paths stay as before. Blank fields are skipped.
    val saveProviderCredentials: () -> Unit = save@{
        val p = selectedProvider ?: return@save
        when {
            p == Constants.PROVIDER_LOCAL -> { /* handled by GGUF import */ }
            p == Constants.PROVIDER_OLLAMA -> if (apiKeyText.isNotBlank()) viewModel.settings.setProviderBaseUrl(Constants.PROVIDER_OLLAMA, apiKeyText)
            isCustomProvider -> {
                if (baseUrlText.isNotBlank()) {
                    val existing = customProviders.firstOrNull { it.name == p }
                    if (existing == null) {
                        viewModel.addCustomProvider(p, baseUrlText, customProtocol)
                    } else {
                        viewModel.settings.setProviderBaseUrl(p, baseUrlText)
                        if (existing.protocol != customProtocol) {
                            viewModel.updateCustomProviderProtocol(p, customProtocol)
                        }
                    }
                }
                if (apiKeyText.isNotBlank()) viewModel.settings.upsertApiKey(p, apiKeyText, p)
            }
            else -> if (apiKeyText.isNotBlank()) viewModel.settings.upsertApiKey(p, apiKeyText, p)
        }
    }

    val fm = LocalFocusManager.current
    var prevPage by remember { mutableIntStateOf(0) }
    var fetchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isFetchingModels by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) {
        // Save provider credentials when leaving the API Key page (swipe or button).
        if (prevPage == PAGE_API_KEY) saveProviderCredentials()
        prevPage = pagerState.currentPage
        fm.clearFocus()
        if (pagerState.currentPage !in visitedPages) {
            visitedPages.add(pagerState.currentPage)

        }
        // Models are fetched only while the Model Select page is visible. (Re)fetch
        // on every entry with the latest key; cancel on leave so an in-flight request
        // never lands off-screen (no list jump) and a stale key's result never wins.
        fetchJob?.cancel()
        val provider = selectedProvider
        if (pagerState.currentPage == PAGE_MODEL_CONFIG && provider != null && provider != Constants.PROVIDER_LOCAL) {
            isFetchingModels = true
            fetchJob = scope.launch {
                try {
                    kotlinx.coroutines.delay(300) // debounce swipe-through + let async key save commit
                    viewModel.fetchModelsForProvider(provider)
                } catch (_: Exception) {
                    // Cancellation or network failure: keep whatever the list already shows.
                } finally {
                    isFetchingModels = false
                }
            }
        } else {
            isFetchingModels = false
        }
    }

    LaunchedEffect(motionPolicy.reduceMotion) {
        if (!motionPolicy.reduceMotion) {
            kotlinx.coroutines.delay(2000)
        }
        showContent = true
    }

    LaunchedEffect(exiting) { if (exiting) { kotlinx.coroutines.delay(300); onComplete() } }

    // GGUF error dialog
    if (showGgufError) AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { showGgufError = false },
        title = { Text(stringResource(R.string.onboarding_invalid_gguf_title), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.onboarding_invalid_gguf_desc)) },
        confirmButton = { TextButton(onClick = { showGgufError = false }) { Text(stringResource(R.string.ok)) } }
    )

    AnimatedVisibility(visible = !exiting, exit = fadeOut(tween(300))) {
        Box(modifier = Modifier.fillMaxSize().clearFocusOnTap()) {
            // No imePadding here: onboarding keeps a stable centered layout while
            // the keyboard is open; chat and settings surfaces handle IME insets.
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {

                // Skip button
                Box(Modifier.fillMaxWidth().padding(top = 48.dp, end = 16.dp).alpha(contentAlpha), contentAlignment = Alignment.CenterEnd) {
                    TextButton(
                        onClick = { if (pagerState.currentPage < pages.size - 1) exiting = true },
                        enabled = showContent && pagerState.currentPage < pages.size - 1,
                        modifier = Modifier.alpha(if (pagerState.currentPage < pages.size - 1) 1f else 0f)
                    ) { Text(stringResource(R.string.onboarding_skip)) }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f), userScrollEnabled = showContent, beyondViewportPageCount = 1) { index ->
                    Column(
                        Modifier.fillMaxSize().graphicsLayer {
                            // Layer reads invalidate only drawing; pager motion no longer
                            // recomposes the full onboarding page on every scroll frame.
                            val pageOffset =
                                (index - pagerState.currentPage) -
                                    pagerState.currentPageOffsetFraction
                            val absOffset = pageOffset.absoluteValue.coerceIn(0f, 1f)
                            scaleX = 1f - absOffset * 0.12f
                            scaleY = 1f - absOffset * 0.12f
                            alpha = 1f - absOffset * 0.4f
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Main content area (video or config card)
                        Box(Modifier.fillMaxWidth().weight(1.8f), contentAlignment = Alignment.Center) {
                            when (index) {
                                PAGE_PROVIDER -> ProviderPage(
                                    providers = allProviders,
                                    selected = selectedProvider,
                                    onSelect = { selectedProvider = it; apiKeyText = ""; baseUrlText = "" },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).alpha(contentAlpha),
                                    configuredProviders = existingApiKeys.map { it.provider }.toSet() + existingProviderUrls.filter { it.value.isNotBlank() }.keys
                                )
                                PAGE_API_KEY -> ApiKeyPage(
                                    provider = selectedProvider,
                                    isCustom = isCustomProvider,
                                    apiKeyText = apiKeyText,
                                    onApiKeyChange = { apiKeyText = it },
                                    baseUrlText = baseUrlText,
                                    onBaseUrlChange = { baseUrlText = it },
                                    customProtocol = customProtocol,
                                    onCustomProtocolChange = { customProtocol = it },
                                    apiKeyVisible = apiKeyVisible,
                                    onToggleVisibility = { apiKeyVisible = !apiKeyVisible },
                                    isImporting = isImportingGGUF,
                                    onImportGGUF = { ggufPicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).alpha(contentAlpha),
                                    localModels = localChatModels
                                )
                                PAGE_MODEL_CONFIG -> {
                                    val pModels = if (selectedProvider != null) availableModels[selectedProvider] ?: emptyList() else emptyList()
                                    val lModels = localChatModels.map { "${Constants.PROVIDER_LOCAL}:${it.modelId}" }
                                    val models = if (selectedProvider == Constants.PROVIDER_LOCAL) lModels else pModels
                                    val applyModel: (String) -> Unit = { id ->
                                        selectedModelId = id
                                        viewModel.settings.setSelectedModel(id)
                                        viewModel.settings.setEnabledModels(setOf(id))
                                    }
                                    // Auto-apply the first model whenever the current selection
                                    // isn't in the list (initial load, or after a provider/key change).
                                    LaunchedEffect(models) {
                                        if (models.isNotEmpty() && selectedModelId !in models) {
                                            applyModel(models.first())
                                        }
                                    }
                                    ModelPage(
                                        models = models,
                                        modelAliases = modelAliases,
                                        selectedId = selectedModelId,
                                        isLoading = isFetchingModels,
                                        onSelect = applyModel,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).alpha(contentAlpha)
                                    )
                                }
                                PAGE_AUTO_BACKUP -> AutoBackupPage(
                                    enabled = autoBackupEnabled,
                                    onToggle = { viewModel.dataControl.setAutoBackupEnabled(it) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).alpha(contentAlpha)
                                )
                                else -> {
                                    val page = pages[index]
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        page.icon?.let { icon ->
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(72.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // Title + description
                        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).alpha(contentAlpha)) {
                            val page = pages[index]
                            val title = when {
                                index == PAGE_API_KEY && selectedProvider == Constants.PROVIDER_LOCAL -> stringResource(R.string.onboarding_gguf_title)
                                index == PAGE_API_KEY && selectedProvider == Constants.PROVIDER_OLLAMA -> stringResource(R.string.onboarding_server_url_title)
                                index == PAGE_API_KEY && isCustomProvider -> stringResource(R.string.onboarding_custom_title)
                                else -> page.title
                            }
                            // Capture delegated property to local val for smart cast (selectedProvider is `by remember { mutableStateOf }`)
                            val providerForDesc = selectedProvider
                            val desc = when {
                                index == PAGE_API_KEY && providerForDesc == Constants.PROVIDER_LOCAL -> stringResource(R.string.onboarding_gguf_desc)
                                index == PAGE_API_KEY && providerForDesc == Constants.PROVIDER_OLLAMA -> stringResource(R.string.onboarding_ollama_desc)
                                index == PAGE_API_KEY && isCustomProvider -> stringResource(R.string.onboarding_custom_desc)
                                index == PAGE_API_KEY && providerForDesc != null -> stringResource(R.string.onboarding_api_key_for, providerForDesc)
                                else -> page.description
                            }
                            val isCurrent = pagerState.currentPage == index
                            val show = isCurrent || index in typedPages
                            val anim = isCurrent && index !in typedPages
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                val delay = if (index == 0) 2000 else 0
                                TypewriterText(
                                    text = title,
                                    animationKey = "onboarding-title-$index",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Start,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    typeSpeedMs = 50,
                                    initialDelayMs = if (anim) delay else 0,
                                    animate = anim && motionPolicy.allowContinuousMotion,
                                    showText = show,
                                    mode = TypewriterMode.TEXT_GRADIENT,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                TypewriterText(
                                    text = desc,
                                    animationKey = "onboarding-description-$index",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Start,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    typeSpeedMs = 30,
                                    initialDelayMs = if (anim) delay + 200 else 0,
                                    animate = anim && motionPolicy.allowContinuousMotion,
                                    showText = show,
                                    mode = TypewriterMode.TEXT_GRADIENT,
                                    modifier = Modifier.fillMaxWidth(),
                                    onDone = { if (anim) typedPages.add(index) },
                                )
                            }
                        }

                        Spacer(Modifier.height(48.dp))
                    }
                }

                // Dot indicators
                Row(Modifier.padding(bottom = 16.dp).alpha(contentAlpha), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    repeat(pages.size) { idx ->
                        val sel = pagerState.currentPage == idx
                        val sz by animateDpAsState(if (sel) 10.dp else 8.dp, spring(0.7f, 400f))
                        val cl by animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, spring(0.7f, 400f))
                        Box(Modifier.padding(horizontal = 4.dp).size(sz).clip(CircleShape).background(cl))
                    }
                }

                // Continue / Get Started
                Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 48.dp).navigationBarsPadding().alpha(contentAlpha)) {
                    val last = pagerState.currentPage == pages.size - 1
                    val isWelcome = pagerState.currentPage == PAGE_WELCOME
                    if (isWelcome) {
                        Button(onClick = { exiting = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = showContent, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Text(stringResource(R.string.onboarding_start_now), modifier = Modifier.padding(vertical = 4.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                            scope.launch {
                                if (motionPolicy.allowProgrammaticScrollMotion) {
                                    pagerState.animateScrollToPage(
                                        PAGE_PROVIDER,
                                        animationSpec = tween<Float>(
                                            500,
                                            easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
                                        ),
                                    )
                                } else {
                                    pagerState.scrollToPage(PAGE_PROVIDER)
                                }
                            }
                        }, Modifier.fillMaxWidth(), enabled = showContent) {
                            Text(stringResource(R.string.onboarding_connect_ai))
                        }
                    } else {
                        Button(onClick = {
                            if (last) { exiting = true }
                            else {
                                // Credentials are saved by the page-leave effect (covers both
                                // swipe and this button), so we only advance here.
                                if (pagerState.currentPage == PAGE_PROVIDER && selectedProvider != null && selectedProvider != Constants.PROVIDER_LOCAL) apiKeyText = ""
                                scope.launch {
                                    val targetPage = pagerState.currentPage + 1
                                    if (motionPolicy.allowProgrammaticScrollMotion) {
                                        pagerState.animateScrollToPage(
                                            targetPage,
                                            animationSpec = tween<Float>(
                                                500,
                                                easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
                                            ),
                                        )
                                    } else {
                                        pagerState.scrollToPage(targetPage)
                                    }
                                }
                            }
                        }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = showContent, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Text(if (last) stringResource(R.string.onboarding_get_started) else stringResource(R.string.onboarding_continue), modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}


// ── Merged from WelcomeSteps.kt (P3 preventive split) ─────────────────────

// Step pages extracted from WelcomeScreen.kt to keep that file under the
// 999-line ceiling. Each page is a self-contained card surfaced inside the
// onboarding pager; the orchestrator in WelcomeScreen.kt wires state and
// callbacks into them.

@Composable
internal fun ProviderPage(
    providers: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    configuredProviders: Set<String> = emptySet(),
) {
    val scrollState = rememberScrollState()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    // Responsive cap: 50% of screen height so the provider card no longer
    // overflows on small screens nor wastes space on tablets.
    val maxProviderHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Surface(
        modifier = modifier.heightIn(max = maxProviderHeight),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxWidth().drawBehind {
            if (scrollState.maxValue > 0) {
                val progress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
                val barW = 4.dp.toPx()
                val barX = size.width - barW - 8.dp.toPx()
                val barH = size.height - 40.dp.toPx()
                val barY = 20.dp.toPx()
                drawRoundRect(trackColor, topLeft = Offset(barX, barY), size = Size(barW, barH), cornerRadius = CornerRadius(2.dp.toPx()))
                val thumbH = barH * 0.35f
                val thumbY = barY + (barH - thumbH) * progress
                drawRoundRect(thumbColor, topLeft = Offset(barX, thumbY), size = Size(barW, thumbH), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }) {
            Column(Modifier.verticalScroll(scrollState)) {
                Spacer(Modifier.height(10.dp))
                providers.forEach { p ->
                    val iconRes = providerIcon(p)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(p) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == p, onClick = { onSelect(p) })
                        Spacer(Modifier.width(8.dp))
                        when {
                            iconRes != 0 -> Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                            p == Constants.PROVIDER_LOCAL -> Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                            p == "Custom" -> Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                            else -> Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            p,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selected == p) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
internal fun ApiKeyPage(
    provider: String?,
    isCustom: Boolean,
    apiKeyText: String,
    onApiKeyChange: (String) -> Unit,
    baseUrlText: String,
    onBaseUrlChange: (String) -> Unit,
    customProtocol: CustomEndpointProtocol,
    onCustomProtocolChange: (CustomEndpointProtocol) -> Unit,
    apiKeyVisible: Boolean,
    onToggleVisibility: () -> Unit,
    isImporting: Boolean,
    onImportGGUF: () -> Unit,
    modifier: Modifier,
    localModels: List<LocalChatModelConfig> = emptyList(),
) {
    Surface(modifier, RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        if (provider == null) {
            Column(Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.onboarding_no_provider), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (provider == Constants.PROVIDER_LOCAL) {
            val label = if (isImporting) stringResource(R.string.onboarding_importing)
                else localModels.lastOrNull()?.alias ?: stringResource(R.string.onboarding_import_gguf)
            Column(Modifier.padding(32.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onImportGGUF, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !isImporting) {
                    Text(label, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        } else if (provider == Constants.PROVIDER_OLLAMA) {
            Column(Modifier.padding(32.dp).fillMaxWidth().clearFocusOnTap()) {
                val iconRes = providerIcon(provider)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconRes != 0) {
                        Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    } else {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_ollama_hint)) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                )
            }
        } else if (isCustom) {
            Column(Modifier.padding(32.dp).fillMaxWidth().clearFocusOnTap()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                CustomEndpointProtocolSelector(
                    selected = customProtocol,
                    onSelected = onCustomProtocolChange,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseUrlText, onValueChange = onBaseUrlChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_custom_base_url_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_api_key_hint)) },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (apiKeyVisible) R.string.onboarding_hide_key else R.string.onboarding_show_key), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                )
            }
        } else {
            Column(Modifier.padding(32.dp).fillMaxWidth().clearFocusOnTap()) {
                val iconRes = providerIcon(provider)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconRes != 0) {
                        Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    } else {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_api_key_hint)) },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (apiKeyVisible) R.string.onboarding_hide_key else R.string.onboarding_show_key), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                )
            }
        }
    }
}

@Composable
internal fun ModelPage(
    models: List<String>,
    modelAliases: Map<String, String>,
    selectedId: String?,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    Surface(modifier, RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        if (models.isEmpty()) {
            // While a fetch is in flight show a quiet spinner instead of the empty
            // state, so the list never flashes "no models" then jumps into view.
            // Fixed-height slot keeps the card identical between both states, and
            // Crossfade fades the spinner in/out rather than popping.
            Box(Modifier.fillMaxWidth().padding(32.dp).height(40.dp), contentAlignment = Alignment.Center) {
                Crossfade(targetState = isLoading, animationSpec = tween(400), label = "modelLoading") { loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(stringResource(R.string.onboarding_no_models), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        } else {
            val scrollState = rememberScrollState()
            val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            val maxProviderHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
            Box(Modifier.fillMaxWidth().heightIn(max = maxProviderHeight).drawBehind {
                if (scrollState.maxValue > 0) {
                    val progress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
                    val barW = 4.dp.toPx()
                    val barX = size.width - barW - 8.dp.toPx()
                    val barH = size.height - 24.dp.toPx()
                    val barY = 12.dp.toPx()
                    drawRoundRect(trackColor, topLeft = Offset(barX, barY), size = Size(barW, barH), cornerRadius = CornerRadius(2.dp.toPx()))
                    val thumbH = barH * 0.35f
                    val thumbY = barY + (barH - thumbH) * progress
                    drawRoundRect(thumbColor, topLeft = Offset(barX, thumbY), size = Size(barW, thumbH), cornerRadius = CornerRadius(2.dp.toPx()))
                }
            }) {
                Column(Modifier.verticalScroll(scrollState)) {
                    Spacer(Modifier.height(10.dp))
                    models.forEach { m ->
                        val name = modelAliases[m] ?: com.lxseek.chat.model.ModelId.parse(m).apiModelName
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(m) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedId == m, onClick = { onSelect(m) })
                            Spacer(Modifier.width(8.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (selectedId == m) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
internal fun AutoBackupPage(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier) {
    Surface(modifier, RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Column(Modifier.padding(32.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.auto_backup_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onToggle(!enabled) }.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_backup_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.auto_backup_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}
