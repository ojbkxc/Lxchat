package com.lxseek.chat

import com.lxseek.chat.BuildConfig
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.key
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lxseek.chat.ui.settings.RatingForm
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lxseek.chat.data.SettingsManager
import com.lxseek.chat.membership.ActivationManager
import com.lxseek.chat.membership.ActivationResult
import com.lxseek.chat.membership.MembershipTier
import com.lxseek.chat.membership.PendingOrderStore
import com.lxseek.chat.membership.RemoteCloudApi
import com.lxseek.chat.membership.YipayCallbackResult
import com.lxseek.chat.membership.YipayConfig
import com.lxseek.chat.membership.YipayPaymentManager
import com.lxseek.chat.service.LxChatForegroundService
import com.lxseek.chat.service.AppForegroundTracker
import com.lxseek.chat.data.local.ChatDatabase
import com.lxseek.chat.ui.chat.ChatApp
import com.lxseek.chat.ui.chat.FullScreenMediaViewer
import com.lxseek.chat.ui.chat.AskUserQuestionPanel
import com.lxseek.chat.ui.chat.McpElicitationPanel
import com.lxseek.chat.ui.chat.message.ChatMarkdownCodeBlock
import com.lxseek.chat.ui.onboarding.WelcomeScreen
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.ui.motion.ProvideLxChatMotionPolicy
import com.lxseek.chat.ui.settings.SettingsScreen
import com.lxseek.chat.ui.tasks.TaskHistoryPreviewPhase
import com.lxseek.chat.ui.tasks.TaskHistoryPreviewState
import com.lxseek.chat.ui.tasks.TaskHistoryPreviewStateSaver
import com.lxseek.chat.ui.theme.LxChatTheme
import com.lxseek.chat.util.CrashReporter
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private val notificationConversationId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Yipay callback result surfaced to the UI (Snackbar). Reset to Idle after consumption. */
    private val yipayCallbackResult = kotlinx.coroutines.flow.MutableStateFlow<YipayCallbackResult>(YipayCallbackResult.Idle)

    companion object {
        const val EXTRA_CONVERSATION_ID = "com.lxseek.chat.extra.CONVERSATION_ID"
        /** DeepLink host for the Yipay return_url: lxchat://yipay-callback */
        private const val YIPAY_CALLBACK_HOST = "yipay-callback"
        /** Membership duration (days) granted on a successful yipay purchase. */
        private const val YIPAY_MEMBERSHIP_DURATION_DAYS = 30
    }

    override fun attachBaseContext(newBase: Context) {
        // attachBaseContext runs before Application.onCreate, so CrashReporter is NOT installed
        // yet 鈥?an uncaught exception here is an invisible silent crash. DataStore's first read
        // can throw IOException (corrupted file, first-install race, scoped-storage issues).
        // Fall back to the system default locale on any failure rather than killing the process.
        val locale = try {
            val langCode = kotlinx.coroutines.runBlocking {
                SettingsManager(newBase).appLanguage.first()
            }
            when (langCode) {
                "zh" -> java.util.Locale("zh", "CN")
                "en" -> java.util.Locale("en")
                else -> null
            }
        } catch (e: Throwable) {
            // Swallow 鈥?locale customization is non-essential. Use system default.
            null
        }
        if (locale != null) {
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build())
        }
        val splashScreen = installSplashScreen()
        var startupReady = false
        splashScreen.setKeepOnScreenCondition { !startupReady }
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)

        // Defensive: DebugLog.init and notification channel creation must not crash onCreate.
        // Both are non-essential for the app to function and can fail on exotic OEM ROMs.
        try {
            com.lxseek.chat.util.DebugLog.init(this)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "DebugLog.init failed", e)
        }
        try {
            LxChatForegroundService.createChannel(this)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Notification channel creation failed", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        val settingsManager = SettingsManager(applicationContext)
        lifecycleScope.launch {
            val storedVersion = withContext(Dispatchers.IO) {
                ChatDatabase.getStoredVersion(this@MainActivity)
            }
            val needsErrorDialog = storedVersion > ChatDatabase.CURRENT_VERSION
            withContext(Dispatchers.IO) {
                runCatching {
                    settingsManager.initializeFirstInstallDefaults(
                        locale = java.util.Locale.getDefault()
                    )
                }.onFailure { error ->
                    com.lxseek.chat.util.DebugLog.e(
                        "MainActivity",
                        "First-install settings initialization failed",
                        error,
                    )
                }
            }

            // 重装恢复：本地无凭证时查服务端 device_status，有有效激活则恢复到本地。
            // 异步执行，不阻塞 UI 启动；恢复成功后刷新会员状态。
            launch {
                try {
                    val activationManager = ActivationManager(RemoteCloudApi(applicationContext), applicationContext)
                    if (!activationManager.hasActiveCredential()) {
                        val deviceId = com.lxseek.chat.membership.DeviceIdCard.getDeviceId(applicationContext)
                        val restored = withContext(Dispatchers.IO) {
                            activationManager.restoreActivation(deviceId)
                        }
                        if (restored) {
                            (application as LxChatApplication).container.membershipProvider.refresh()
                            com.lxseek.chat.util.DebugLog.i("MainActivity", "Activation restored after reinstall")
                        }
                    }
                } catch (e: Exception) {
                    com.lxseek.chat.util.DebugLog.e("MainActivity", "restoreActivation failed", e)
                }
            }

            enableEdgeToEdge()
            // Remove navigation bar scrim so it blends with app content
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            setContent {
            val themeMode by settingsManager.themeMode.collectAsState(initial = "FOLLOW_DEVICE")
            val colorSchemeName by settingsManager.colorScheme.collectAsState(initial = "MINIMAL")
            val schemeStyleName by settingsManager.schemeStyle.collectAsState(initial = "TONAL_SPOT")
            val dynamicColor by settingsManager.dynamicColor.collectAsState(initial = false)
            val fontPreference by settingsManager.fontPreference.collectAsState(initial = "app_default")
            val customFontPath by settingsManager.customFontPath.collectAsState(initial = "")
            val chatFontScale by settingsManager.chatFontScale.collectAsState(initial = 1.0f)
            val appReduceMotion by settingsManager.reduceMotion.collectAsState(initial = false)
            val appName by settingsManager.appName.collectAsState(initial = "LxChat")

            LaunchedEffect(appName) {
                title = appName
            }

            val themeModeEnum = try { com.lxseek.chat.ui.theme.ThemeMode.valueOf(themeMode) } catch (_: Exception) { com.lxseek.chat.ui.theme.ThemeMode.FOLLOW_DEVICE }
            val colorSchemePreset = try { com.lxseek.chat.ui.theme.ColorSchemePreset.valueOf(colorSchemeName) } catch (_: Exception) { com.lxseek.chat.ui.theme.ColorSchemePreset.MINIMAL }
            val schemeStyle = try { com.lxseek.chat.ui.theme.SchemeStyle.valueOf(schemeStyleName) } catch (_: Exception) { com.lxseek.chat.ui.theme.SchemeStyle.TONAL_SPOT }

            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeModeEnum) {
                com.lxseek.chat.ui.theme.ThemeMode.LIGHT -> false
                com.lxseek.chat.ui.theme.ThemeMode.DARK -> true
                com.lxseek.chat.ui.theme.ThemeMode.AMOLED -> true
                com.lxseek.chat.ui.theme.ThemeMode.FOLLOW_DEVICE -> systemDark
            }

            SideEffect {
                val window = this@MainActivity.window
                val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }

            LxChatTheme(
                themeMode = themeModeEnum,
                colorSchemePreset = colorSchemePreset,
                schemeStyle = schemeStyle,
                dynamicColor = dynamicColor,
                fontPreference = fontPreference,
                customFontPath = customFontPath,
                chatFontScale = chatFontScale
            ) {
                ProvideLxChatMotionPolicy(appReduceMotion = appReduceMotion) {
                val activity = LocalActivity.current

                if (needsErrorDialog) {
                    val databaseScope = rememberCoroutineScope()
                    var clearingDatabase by remember { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = { activity?.finish() },
                        title = { Text(stringResource(R.string.database_incompatible), fontWeight = FontWeight.Bold) },
                        text = { Text(stringResource(R.string.database_incompatible_desc)) },
                        dismissButton = {
                            TextButton(onClick = { activity?.finish() }) { Text(stringResource(R.string.quit)) }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (!clearingDatabase) {
                                        clearingDatabase = true
                                        databaseScope.launch {
                                            withContext(Dispatchers.IO) {
                                                applicationContext.deleteDatabase(ChatDatabase.DB_NAME)
                                            }
                                            activity?.recreate()
                                        }
                                    }
                                },
                                enabled = !clearingDatabase,
                            ) { Text(stringResource(R.string.clear_database)) }
                        }
                    )
                } else {
                    var showOnboarding by remember { mutableStateOf<Boolean?>(null) }
                    val onboardingScope = rememberCoroutineScope()

                    LaunchedEffect(Unit) {
                        showOnboarding = !settingsManager.onboardingCompleted.first()
                    }

                    // Create ViewModel via the process-scoped DI container (owned by LxChatApplication),
                    // so the same shared singletons back both the UI and background task execution.
                    val container = (application as LxChatApplication).container
                    val factory = remember { container.chatViewModelFactory() }
                    val viewModel: ChatViewModel = viewModel(factory = factory)

                    when (showOnboarding) {
                        null -> { /* loading 鈥?splash screen covers this */ }
                        true -> {
                            WelcomeScreen(
                                onComplete = {
                                    onboardingScope.launch {
                                        settingsManager.saveOnboardingCompleted(true)
                                    }
                                    showOnboarding = false
                                },
                                isDarkTheme = isDark,
                                viewModel = viewModel
                            )
                        }
                        false -> {
                            MainNavigation(
                                viewModel = viewModel,
                                settingsManager = settingsManager,
                                notificationConversationId = notificationConversationId,
                                onNotificationConversationConsumed = { expectedId ->
                                    consumeNotificationTarget(notificationConversationId, expectedId)
                                },
                                yipayCallbackResult = yipayCallbackResult,
                                onYipayCallbackConsumed = {
                                    yipayCallbackResult.value = YipayCallbackResult.Idle
                                },
                            )
                        }
                    }
                }
            }
            }
            }
            startupReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.setInForeground(true)
        // Yipay fallback: query any pending order in case the DeepLink callback was lost.
        checkPendingYipayOrder()
    }

    override fun onPause() {
        super.onPause()
        AppForegroundTracker.setInForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data

        // Yipay payment callback DeepLink: lxchat://yipay-callback?...
        if (data != null && data.scheme == "lxchat" && data.host == YIPAY_CALLBACK_HOST) {
            handleYipayCallback(data)
            return
        }

        // Notification conversation DeepLink: lxchat://conversation/{id} or extra
        notificationConversationId.value = intent.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: data?.takeIf { uri ->
                uri.scheme == "lxchat" && uri.host == "conversation"
            }?.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    /**
     * Parse + verify the Yipay callback signature, then ask the activation server to
     * confirm the payment and issue a signed credential.
     *
     * Flow: parse callback 鈫?verify MD5 signature 鈫?check trade status 鈫?     *       publish [YipayCallbackResult.Confirming] 鈫?     *       RemoteCloudApi.activateByOrder(deviceId, outTradeNo) 鈫?     *       server queries the gateway, confirms paid, returns credential 鈫?     *       publish [YipayCallbackResult.Success].
     *
     * The signature check alone is NOT enough to activate: a user can forge a
     * DeepLink URL with valid-looking params. The server must independently query
     * the payment gateway to confirm the order is actually paid. This is why we
     * never call [com.lxseek.chat.membership.LocalMembershipProvider.applyYipayPurchase]
     * directly from the callback anymore.
     */
    private fun handleYipayCallback(uri: Uri) {
        val config = YipayConfig.DEFAULT
        val manager = YipayPaymentManager()
        val params = manager.parseCallback(uri)
        if (params == null) {
            yipayCallbackResult.value = YipayCallbackResult.Failed
            return
        }
        // Verify signature AND trade status.
        if (!manager.verifyCallback(config, params)) {
            yipayCallbackResult.value = YipayCallbackResult.Failed
            return
        }
        val verifier = com.lxseek.chat.membership.YipayCallbackVerifier(config.merchantKey)
        if (!verifier.isTradeSuccess(params)) {
            yipayCallbackResult.value = YipayCallbackResult.Failed
            return
        }
        // Signature valid 鈫?ask server to confirm payment & activate.
        // Map amount 鈫?tier for UI feedback only; the server is the source of truth.
        val tier = mapMoneyToTier(params.money)
        val store = PendingOrderStore(this)
        val activationManager = ActivationManager(RemoteCloudApi(this), this)
        yipayCallbackResult.value = YipayCallbackResult.Confirming
        lifecycleScope.launch {
            // Don't double-activate if the onResume fallback already succeeded.
            if (yipayCallbackResult.value is YipayCallbackResult.Success) {
                store.clear()
                return@launch
            }
            val result = activationManager.activateByOrder(params.outTradeNo)
            when (result) {
                is ActivationResult.Success -> {
                    yipayCallbackResult.value = YipayCallbackResult.Success(tier)
                    store.clear()
                    // Refresh membership status so the UI reflects the new tier.
                    (application as LxChatApplication).container.membershipProvider.refresh()
                }
                else -> {
                    yipayCallbackResult.value = YipayCallbackResult.Failed
                }
            }
        }
    }

    /**
     * Fallback for lost DeepLink callbacks: when the App returns to the foreground with a
     * pending Yipay order, ask the activation server to confirm the payment and activate.
     *
     * Skipped when a callback already succeeded (DeepLink beat us to it) 鈥?the store is
     * cleared and we do nothing. Expired orders (older than 10 min) are also cleared.
     *
     * Unlike the old path which queried the Yipay gateway directly (requiring the merchant
     * key in the App), this now calls [RemoteCloudApi.activateByOrder] so the server does
     * the gateway query. The merchant key never lives in the App.
     */
    private fun checkPendingYipayOrder() {
        val store = PendingOrderStore(this)
        val pending = store.get() ?: return
        // DeepLink already activated membership 鈥?don't double-activate.
        if (yipayCallbackResult.value is YipayCallbackResult.Success) {
            store.clear()
            return
        }
        if (store.isExpired()) {
            store.clear()
            return
        }
        val activationManager = ActivationManager(RemoteCloudApi(this), this)
        if (yipayCallbackResult.value !is YipayCallbackResult.Confirming) {
            yipayCallbackResult.value = YipayCallbackResult.Confirming
        }
        lifecycleScope.launch {
            val result = activationManager.activateByOrder(pending.outTradeNo)
            if (result is ActivationResult.Success) {
                // A DeepLink may have arrived between get() and the response 鈥?re-check.
                if (yipayCallbackResult.value is YipayCallbackResult.Success) {
                    store.clear()
                    return@launch
                }
                yipayCallbackResult.value = YipayCallbackResult.Success(pending.tier)
                store.clear()
                (application as LxChatApplication).container.membershipProvider.refresh()
            } else {
                // Activation failed (server didn't confirm payment or network error).
                // Reset to Idle so the user can retry; keep the pending order for onResume retry.
                if (yipayCallbackResult.value is YipayCallbackResult.Confirming) {
                    yipayCallbackResult.value = YipayCallbackResult.Idle
                }
            }
        }
    }

    /** Map the yipay money string to a [MembershipTier]. Unknown amounts fall back to Premium. */
    private fun mapMoneyToTier(money: String): MembershipTier = when (money.trim()) {
        "0.30", "0.3" -> MembershipTier.Premium
        "0.50", "0.5" -> MembershipTier.Pro
        else -> MembershipTier.Premium
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(
    viewModel: ChatViewModel,
    settingsManager: SettingsManager,
    notificationConversationId: kotlinx.coroutines.flow.StateFlow<String?>,
    onNotificationConversationConsumed: (String) -> Unit,
    yipayCallbackResult: kotlinx.coroutines.flow.StateFlow<YipayCallbackResult> = kotlinx.coroutines.flow.MutableStateFlow(YipayCallbackResult.Idle),
    onYipayCallbackConsumed: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val motionPolicy = LocalLxChatMotionPolicy.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTasks by rememberSaveable { mutableStateOf(false) }
    val tasksListState = rememberLazyListState()
    var taskToOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var taskHistoryPreview by rememberSaveable(
        stateSaver = TaskHistoryPreviewStateSaver,
    ) {
        mutableStateOf(TaskHistoryPreviewState.Idle)
    }
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val notificationTarget by notificationConversationId.collectAsState()
    LaunchedEffect(notificationTarget) {
        val id = notificationTarget ?: return@LaunchedEffect
        try {
            val exists = withContext(Dispatchers.IO) {
                (appContext as LxChatApplication).container.conversationRepository
                    .getConversation(id) != null
            }
            if (exists) {
                showSettings = false
                showTasks = false
                taskToOpen = null
                taskHistoryPreview = TaskHistoryPreviewState.Idle
                viewModel.selectConversation(id)
            }
        } finally {
            // A newer notification may have replaced [id] while this effect was suspended.
            // Only consume the event this effect actually handled.
            onNotificationConversationConsumed(id)
        }
    }
    var fullScreenMediaUrls by remember { mutableStateOf<List<String>?>(null) }
    var fullScreenMediaIndex by remember { mutableIntStateOf(0) }
    var pdfViewerSelection by remember { mutableStateOf(setOf<Int>()) }
    val onTogglePdfSelection: (Int) -> Unit = { page ->
        pdfViewerSelection = if (page in pdfViewerSelection) pdfViewerSelection - page else pdfViewerSelection + page
    }
    val onInitPdfSelection: (Set<Int>) -> Unit = { selection ->
        pdfViewerSelection = selection
    }
    var pdfPreviewFromDialog by remember { mutableStateOf(false) }
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val pdfPages by viewModel.previewPdfPages.collectAsState()
    val pdfIndex by viewModel.previewPdfIndex.collectAsState()
    var savedPdfPages by remember { mutableStateOf<List<String>>(emptyList()) }
    if (pdfPages.isNotEmpty()) { savedPdfPages = pdfPages } else { savedPdfPages = emptyList() }
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarVersion by remember { mutableIntStateOf(0) }

    // Yipay payment callback 鈫?Snackbar feedback (success / failed / confirming).
    val yipayResult by yipayCallbackResult.collectAsState()
    LaunchedEffect(yipayResult) {
        when (yipayResult) {
            is YipayCallbackResult.Success -> {
                snackbarHostState.showSnackbar(appContext.getString(R.string.membership_payment_success))
                onYipayCallbackConsumed()
            }
            YipayCallbackResult.Failed -> {
                snackbarHostState.showSnackbar(appContext.getString(R.string.membership_payment_failed))
                onYipayCallbackConsumed()
            }
            YipayCallbackResult.Confirming -> {
                snackbarHostState.showSnackbar(appContext.getString(R.string.membership_payment_confirming))
            }
            YipayCallbackResult.Idle -> Unit
        }
    }
    val accessibilityManager = LocalAccessibilityManager.current
    var chatSnackbarOffset by remember { mutableStateOf(0.dp) }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Full-screen media viewer (and settings) drop the snackbar to the bottom (nav-bar inset only);
    // in chat it floats above the bottom bar. The animateDpAsState below turns the change into a
    // rise/fall animation as the viewer opens/closes.
    val targetSnackbarPadding = if (showSettings || fullScreenMediaUrls != null) navBarPadding else chatSnackbarOffset
    val snackbarBottomPadding by animateDpAsState(
        targetValue = targetSnackbarPadding,
        animationSpec = if (motionPolicy.allowSpatialTransitions) {
            spring(dampingRatio = 1.0f, stiffness = 1000f)
        } else {
            snap()
        },
        label = "snackbarPadding"
    )
    val focusManager = LocalFocusManager.current
    val ratingScope = rememberCoroutineScope()

    // Update dialog
    val updateDialogData by viewModel.updateDialogData.collectAsState()
    if (updateDialogData != null) {
        val info = updateDialogData!!
        val ctx = LocalContext.current
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { viewModel.dismissUpdateDialog() },
            icon = { Icon(Icons.Default.Download, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) },
            title = {
                Text(
                    text = stringResource(R.string.about_update_available, info.version),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.about_available_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (info.body.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            // Lightweight markdown render of the release notes, kept on the
                            // shared type scale: '## ' 鈫?bold section label, '- ' 鈫?indented
                            // bullet, blank line 鈫?vertical gap, everything else 鈫?paragraph.
                            info.body.split("\n").forEach { line ->
                                when {
                                    line.startsWith("## ") -> Text(
                                        text = line.removePrefix("## "),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                                    )
                                    line.startsWith("- ") -> Text(
                                        text = "鈥? ${line.removePrefix("- ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 3.dp, start = 2.dp)
                                    )
                                    line.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
                                    else -> Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                    viewModel.dismissUpdateDialog()
                }) { Text(stringResource(R.string.about_view_release)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                    Text(stringResource(R.string.about_later))
                }
            }
        )
    }

    // Remote shell action confirmation gate
    val pendingShellCommand by viewModel.pendingShellCommand.collectAsState()
    pendingShellCommand?.let { pending ->
        var alwaysAllow by remember(pending) { mutableStateOf(false) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { viewModel.resolveShellConfirmation(allow = false) },
            icon = { Icon(Icons.Default.Terminal, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.shell_confirm_title, pending.server), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ChatMarkdownCodeBlock(code = pending.summary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) { detectTapGestures { alwaysAllow = !alwaysAllow } }
                    ) {
                        Checkbox(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it })
                        Text(stringResource(R.string.shell_confirm_always), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveShellConfirmation(allow = true, alwaysAllowServer = alwaysAllow) }) {
                    Text(stringResource(R.string.shell_confirm_allow))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.resolveShellConfirmation(allow = false) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.shell_confirm_deny)) }
            }
        )
    }

    // ask_user tool dialog 鈥?agent asks user a question
    val pendingQuestion by viewModel.pendingQuestion.collectAsState()
    pendingQuestion?.let { pending ->
        AskUserQuestionPanel(
            pending = pending,
            onConfirm = { answers -> viewModel.resolveAskUser(answers) },
            onCancel = { viewModel.cancelAskUser() },
        )
    }

    // MCP server 鈫?client elicitation dialog (form / URL confirmation)
    val pendingElicitation by viewModel.pendingElicitation.collectAsState()
    pendingElicitation?.let { pending ->
        McpElicitationPanel(
            pending = pending,
            onResolve = { result -> viewModel.resolveElicitation(result) },
            onCancel = { viewModel.cancelElicitation() },
        )
    }

    // Crash report 鈥?opt-in, shown once on the first launch after an unexpected exit
    val crashContext = LocalContext.current
    var pendingCrash by remember { mutableStateOf<Pair<String, String>?>(null) }
    val crashSubmittedMsg = stringResource(R.string.crash_submitted)
    LaunchedEffect(Unit) {
        pendingCrash = withContext(Dispatchers.IO) {
            CrashReporter.pendingReport(crashContext)?.let { report ->
                val trace = runCatching {
                    org.json.JSONObject(report).optString("trace", "")
                }.getOrDefault("")
                report to trace
            }
        }
    }
    fun dismissPendingCrash() {
        pendingCrash = null
        ratingScope.launch(Dispatchers.IO) { CrashReporter.clear(crashContext) }
    }
    pendingCrash?.let { (report, trace) ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = ::dismissPendingCrash,
            icon = { Icon(Icons.Default.BugReport, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.crash_title), fontWeight = FontWeight.Bold) },
            text = {
                val clipboard = LocalClipboardManager.current
                Column {
                    Text(
                        stringResource(R.string.crash_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(14.dp))
                    // Privacy reassurance as a distinct fine-print block, not just smaller text.
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                null,
                                modifier = Modifier.size(15.dp).padding(top = 1.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.crash_privacy_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (trace.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.crash_log_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(trace)) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    stringResource(R.string.copy),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = trace,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 16.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = CrashReporter.issueUrl(report)
                    crashContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    dismissPendingCrash()
                    ratingScope.launch {
                        try {
                            snackbarHostState.showSnackbar(crashSubmittedMsg)
                        } finally {
                            snackbarVersion++
                        }
                    }
                }) { Text(stringResource(R.string.crash_submit)) }
            },
            dismissButton = {
                TextButton(onClick = ::dismissPendingCrash) {
                    Text(stringResource(R.string.crash_dismiss))
                }
            }
        )
    }

    // Rating prompt 鈥?read from flow directly to avoid collectAsState initial-value race
    var showRatingPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val firstLaunch = settingsManager.firstLaunchTime.first()
        if (firstLaunch == null) {
            settingsManager.saveFirstLaunchTime(now)
        }

        val submitted = settingsManager.ratingPromptSubmitted.first()
        val dismissed = settingsManager.ratingPromptDismissed.first()
        val msgCount = settingsManager.totalMessagesSent.first()
        if (!submitted && !dismissed && firstLaunch != null && msgCount >= 3) {
            val daysElapsed = (now - firstLaunch) / (1000 * 60 * 60 * 24)
            if (daysElapsed >= 7) {
                showRatingPrompt = true
            }
        }
    }

    if (showRatingPrompt) {
        Dialog(
            onDismissRequest = {
                showRatingPrompt = false
                ratingScope.launch {
                    settingsManager.saveRatingPromptDismissed(true)
                }
            }
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                RatingForm(
                    onSubmitted = {
                        showRatingPrompt = false
                        ratingScope.launch {
                            settingsManager.saveRatingPromptSubmitted(true)
                        }
                    }
                )
            }
        }
    }

    // Sandbox events piped into the same global SnackbarHost.
    // Uses a launch+Job pattern so a new message cancels the
    // previous showSnackbar suspension immediately.
    LaunchedEffect(Unit) {
        var snackbarJob: Job? = null
        viewModel.sandboxManager?.snackbarMessage?.collect { msg ->
            if (msg != null) {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarJob?.cancel()
                snackbarJob = launch {
                    try {
                        snackbarHostState.showSnackbar(msg)
                    } finally {
                        snackbarVersion++
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        var snackbarJob: Job? = null
        viewModel.snackbarMessage.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarJob?.cancel()
            snackbarJob = launch {
                try {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = if (event.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        event.onAction?.invoke()
                    }
                } finally {
                    snackbarVersion++
                }
            }
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ChatApp(
                viewModel = viewModel,
                onNavigateBack = taskHistoryPreview.taskId
                    ?.takeIf { taskHistoryPreview.active }
                    ?.let { taskId ->
                        {
                            taskToOpen = taskId
                            taskHistoryPreview = taskHistoryPreview.requestReturn()
                            showTasks = true
                        }
                    },
                drawerEnabled = !taskHistoryPreview.active,
                onOpenSettings = {
                    showSettings = true
                },
                onOpenTasks = { taskId ->
                    taskToOpen = taskId
                    showTasks = true
                },
                onMediaClick = { urls, index ->
                    focusManager.clearFocus()
                    fullScreenMediaUrls = urls
                    fullScreenMediaIndex = index
                },
                onFileContentClick = { name, content ->
                    focusManager.clearFocus()
                    viewModel.showFilePreview(name, content)
                },
                onPdfPagesClick = { pages, idx ->
                    focusManager.clearFocus()
                    viewModel.showPdfPreview(pages, idx)
                    fullScreenMediaUrls = pages
                    fullScreenMediaIndex = idx
                    pdfPreviewFromDialog = false
                },
                onPdfPreviewSelect = { pages, idx ->
                    focusManager.clearFocus()
                    viewModel.showPdfPreview(pages, idx)
                    fullScreenMediaUrls = pages
                    fullScreenMediaIndex = idx
                    pdfPreviewFromDialog = true
                },
                pdfViewerSelection = pdfViewerSelection,
                onTogglePdfSelection = onTogglePdfSelection,
                onInitPdfSelection = onInitPdfSelection,
                fullScreenViewerUrls = fullScreenMediaUrls,
                onSnackbarOffsetChanged = { chatSnackbarOffset = it }
            )

            SettingsOverlayHost(
                visible = showSettings,
                onDismiss = { showSettings = false }
            ) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = {
                        showSettings = false
                    }
                )
            }

            SettingsOverlayHost(
                visible = showTasks,
                onDismiss = { showTasks = false },
                onEnterFinished = {
                    val preview = taskHistoryPreview
                    if (preview.phase == TaskHistoryPreviewPhase.RETURNING) {
                        if (preview.originWasNewChat) {
                            viewModel.createNewChat()
                        } else {
                            preview.originConversationId?.let { conversationId ->
                                viewModel.selectConversation(
                                    id = conversationId,
                                    hapticOnCompletion = false,
                                )
                            }
                        }
                        taskHistoryPreview = TaskHistoryPreviewState.Idle
                    }
                },
            ) {
                com.lxseek.chat.ui.tasks.TasksScreen(
                    viewModel = viewModel,
                    taskListState = tasksListState,
                    initialTaskId = taskToOpen,
                    onInitialTaskHandled = { taskToOpen = null },
                    onBack = { showTasks = false },
                    onOpenConversation = { taskId, conversationId ->
                        taskHistoryPreview = taskHistoryPreview.open(
                            taskId = taskId,
                            currentConversationId = currentConversationId,
                            isNewChatMode = isNewChatMode,
                        )
                        showTasks = false
                        viewModel.selectConversation(conversationId)
                    }
                )
            }

            // Full screen image preview
            AnimatedVisibility(
                visible = fullScreenMediaUrls != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                // Keep the last values for the duration of the exit animation
                var lastUrls by remember { mutableStateOf<List<String>?>(null) }
                var lastIndex by remember { mutableIntStateOf(0) }
                var lastPdfPages by remember { mutableStateOf<List<String>>(emptyList()) }
                var lastPdfTogglePage by remember { mutableStateOf<((Int) -> Unit)?>(null) }
                LaunchedEffect(fullScreenMediaUrls) {
                    if (fullScreenMediaUrls != null) {
                        lastUrls = fullScreenMediaUrls
                        lastIndex = fullScreenMediaIndex
                        lastPdfPages = savedPdfPages
                        lastPdfTogglePage = if (pdfPreviewFromDialog) onTogglePdfSelection else null
                    }
                }

                val urls = lastUrls ?: return@AnimatedVisibility
                FullScreenMediaViewer(
                    urls = urls,
                    initialIndex = lastIndex,
                    pdfPages = lastPdfPages,
                    pdfSelectedPages = if (lastPdfPages.isNotEmpty() && pdfPreviewFromDialog) pdfViewerSelection else null,
                    onTogglePdfPage = lastPdfTogglePage,
                    onClose = { viewModel.clearPreviews(); fullScreenMediaUrls = null; pdfPreviewFromDialog = false },
                    onNavigate = { idx -> fullScreenMediaIndex = idx },
                    onMessage = { viewModel.emitSnackbar(it) },
                    hapticsEnabled = hapticsEnabled
                )
            }

            // Text file viewer
            val fileContent by viewModel.previewFileContent.collectAsState()
            val fileName by viewModel.previewFileName.collectAsState()
            var savedContent by remember { mutableStateOf(fileContent) }
            var savedName by remember { mutableStateOf(fileName) }
            if (fileContent != null) { savedContent = fileContent; savedName = fileName }
            AnimatedVisibility(
                visible = fileContent != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (savedContent != null && savedName != null) {
                    com.lxseek.chat.ui.chat.TextFileViewer(content = savedContent!!, fileName = savedName!!, onClose = { viewModel.clearPreviews() })
                }
            }

            val current = snackbarHostState.currentSnackbarData
            var showing by remember { mutableStateOf(false) }
            var content by remember { mutableStateOf<SnackbarData?>(null) }

            LaunchedEffect(current, snackbarVersion) {
                if (current != null) {
                    if (showing) { showing = false; delay(200) }
                    content = current
                    showing = true
                } else {
                    showing = false
                    delay(400)
                    content = null
                }
            }

            LaunchedEffect(content, accessibilityManager) {
                val data = content ?: return@LaunchedEffect
                val timeoutMillis = snackbarTimeoutMillis(data.visuals, accessibilityManager)
                if (timeoutMillis != Long.MAX_VALUE) {
                    delay(timeoutMillis)
                    if (snackbarHostState.currentSnackbarData === data) {
                        data.dismiss()
                    }
                }
            }

            AnimatedVisibility(
                visible = showing,
                enter = if (motionPolicy.allowSpatialTransitions) {
                    fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.8f)
                } else {
                    fadeIn(tween(400))
                },
                exit = if (motionPolicy.allowSpatialTransitions) {
                    fadeOut(tween(400)) + scaleOut(tween(400), targetScale = 0.8f)
                } else {
                    fadeOut(tween(400))
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = snackbarBottomPadding + 2.dp)
            ) {
                content?.let { data ->
                    Snackbar(
                        modifier = Modifier.padding(horizontal = 12.dp).padding(vertical = 10.dp).shadow(6.dp, RoundedCornerShape(12.dp), clip = false),
                        shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionContentColor = MaterialTheme.colorScheme.primary,
                        dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        dismissAction = @Composable {
                            Box(modifier = Modifier.padding(end = 8.dp)) {
                                IconButton(onClick = { data.dismiss() }, modifier = Modifier.size(28.dp).clip(CircleShape)) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        action = data.visuals.actionLabel?.let { label ->
                            @Composable { TextButton(onClick = { data.performAction() }) { Text(label) } }
                        },
                        content = { Text(data.visuals.message) }
                    )
                }
            }
        }
    }
}

internal fun consumeNotificationTarget(
    target: kotlinx.coroutines.flow.MutableStateFlow<String?>,
    expectedId: String,
): Boolean = target.compareAndSet(expectedId, null)

private fun snackbarTimeoutMillis(
    visuals: SnackbarVisuals,
    accessibilityManager: AccessibilityManager?
): Long {
    val durationMillis = when (visuals.duration) {
        SnackbarDuration.Short -> 4000L
        SnackbarDuration.Long -> 10000L
        SnackbarDuration.Indefinite -> Long.MAX_VALUE
    }
    if (durationMillis == Long.MAX_VALUE) return durationMillis
    return accessibilityManager?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = durationMillis,
        containsIcons = true,
        containsText = true,
        containsControls = visuals.actionLabel != null
    ) ?: durationMillis
}
