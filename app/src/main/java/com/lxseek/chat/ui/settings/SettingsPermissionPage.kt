package com.lxseek.chat.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lxseek.chat.androidcontrol.AndroidUiControllerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tiered permission management page. Centralizes the L1-L4 execution privilege ladder plus the
 * display/notification and storage/media permission groups, guiding the user to the matching
 * system Settings screen for each item. Every row shows a green/grey dot, a title, a description,
 * a status label ("已授权" / "未授权") and an optional "去设置" button that launches the system
 * settings intent wrapped in try-catch. Probing runs off the main thread inside [LaunchedEffect]
 * so heavy [PackageManager] / Root file checks never block the UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPermissionPage(onBack: () -> Unit) {
    val context = LocalContext.current

    // Probed permission states. Initialized to false and refreshed once on enter.
    var a11yGranted by remember { mutableStateOf(false) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var deviceAdminGranted by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(false) }
    var exactAlarmGranted by remember { mutableStateOf(false) }
    var storageGranted by remember { mutableStateOf(false) }
    var mediaGranted by remember { mutableStateOf(false) }
    var probed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // L1 Accessibility: the bridge is alive only when the system has bound our service.
            a11yGranted = AndroidUiControllerService.instance != null
            // L2 Shizuku / ADB: Shizuku Manager installed is the prerequisite for the authorization flow.
            shizukuInstalled = isPackageInstalled(context, "moe.shizuku.manager")
            // L3 Device Admin: device-owner is the strongest non-root admin form we can detect.
            deviceAdminGranted = runCatching {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                        as android.app.admin.DevicePolicyManager
                dpm.isDeviceOwnerApp(context.packageName)
            }.getOrDefault(false)
            // L4 Root: presence of a su binary under the well-known paths.
            rootAvailable = File("/system/bin/su").exists() || File("/system/xbin/su").exists()
            // Overlay: needed for floating windows and the desktop pet.
            overlayGranted = Settings.canDrawOverlays(context)
            // Notifications.
            notificationGranted = NotificationManagerCompat.from(context).areNotificationsEnabled()
            // Exact alarm: Android 12+ requires the explicit SCHEDULE_EXACT_ALARM permission.
            exactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.canScheduleExactAlarms()
            } else {
                true
            }
            // Storage access: MANAGE_EXTERNAL_STORAGE on R+ falls back to READ_EXTERNAL_STORAGE.
            storageGranted = isStorageGranted(context)
            // Media read: granular READ_MEDIA_* on T+, READ_EXTERNAL_STORAGE below.
            mediaGranted = isMediaGranted(context)
            probed = true
        }
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Section 1: tiered execution privileges L1 → L4 ──
            SectionCard(
                title = "分级执行权限",
                subtitle = "L1 → L4 能力递进，授权更高层级可替代较低层级",
            ) {
                PermissionRow(
                    granted = a11yGranted,
                    title = "L1 无障碍 (Accessibility)",
                    description = "通过 AccessibilityService 读取与操控界面，是 Lxchat 自动化的基础能力。",
                    settingAction = { launchSetting(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                )
                PermissionRow(
                    granted = shizukuInstalled,
                    title = "L2 Shizuku / ADB",
                    description = "以 Shizuku 或 ADB 身份执行 Shell 命令，无需 Root 的高级权限通道。",
                    settingAction = { launchShizuku(context) },
                )
                PermissionRow(
                    granted = deviceAdminGranted,
                    title = "L3 设备管理员 (Device Admin)",
                    description = "设备所有者 / 管理员权限，可施加策略与免 Root 的深度管控。",
                    settingAction = { launchSetting(context, Settings.ACTION_SECURITY_SETTINGS) },
                )
                PermissionRow(
                    granted = rootAvailable,
                    title = "L4 Root (su)",
                    description = "Root 授权通道，最高执行权限。需自行刷入，无法在此跳转授权。",
                    settingAction = null,
                )
            }

            // ── Section 2: display & notification permissions ──
            SectionCard(title = "显示与通知权限") {
                PermissionRow(
                    granted = overlayGranted,
                    title = "悬浮窗",
                    description = "在其他应用上方显示悬浮窗与桌面宠物。",
                    settingAction = {
                        launchSetting(
                            context,
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}",
                        )
                    },
                )
                PermissionRow(
                    granted = notificationGranted,
                    title = "通知",
                    description = "推送消息、前台服务通知与提醒。",
                    settingAction = { launchNotificationSettings(context) },
                )
                PermissionRow(
                    granted = exactAlarmGranted,
                    title = "精确闹钟",
                    description = "Android 12+ 需精确闹钟权限以设定定时任务。",
                    settingAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            launchSetting(
                                context,
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                "package:${context.packageName}",
                            )
                        }
                    },
                )
            }

            // ── Section 3: storage & media permissions ──
            SectionCard(title = "存储与媒体权限") {
                PermissionRow(
                    granted = storageGranted,
                    title = "存储访问",
                    description = "读取外部存储文件，或 MANAGE_EXTERNAL_STORAGE 全文件访问。",
                    settingAction = { launchStorageSettings(context) },
                )
                PermissionRow(
                    granted = mediaGranted,
                    title = "媒体读取",
                    description = "读取相册图片、视频与音频文件（Android 13+ 分区权限）。",
                    settingAction = {
                        launchSetting(
                            context,
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}",
                        )
                    },
                )
            }

            if (!probed) {
                Text(
                    text = "权限探测中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------- Private building blocks ----------------

/**
 * A rounded 14dp card with a 1dp outline border and a primary-colored section title. Mirrors the
 * ZorvAI QuroSystemStatusScreen SectionCard so the permission groups share the same visual language
 * as the rest of the status surfaces.
 */
@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * One permission row: a 10dp green/grey status dot, the title + description, a trailing status
 * label, and an optional "去设置" button that fires [settingAction]. When [settingAction] is null
 * (e.g. L4 Root) only the status is shown, with no affordance to authorize.
 */
@Composable
private fun PermissionRow(
    granted: Boolean,
    title: String,
    description: String,
    settingAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    if (granted) GrantedGreen else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (granted) "已授权" else "未授权",
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) GrantedGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settingAction != null) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = settingAction) {
                Text("去设置", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------- Permission probes & settings launchers ----------------

/** iOS-style system green used for the "granted" dot and status label. */
private val GrantedGreen = Color(0xFF34C759)

/** True when [pkg] is installed on the device. */
private fun isPackageInstalled(context: Context, pkg: String): Boolean =
    runCatching {
        context.packageManager.getPackageInfo(pkg, 0) != null
    }.getOrDefault(false)

/** Storage access: MANAGE_EXTERNAL_STORAGE on R+ otherwise READ_EXTERNAL_STORAGE. */
private fun isStorageGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager() ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/** Media read: granular READ_MEDIA_* on T+, READ_EXTERNAL_STORAGE below. */
private fun isMediaGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO,
        ).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    } else {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Launch a system settings screen by [action], optionally scoped to a `package:` [data] URI. All
 * failures (missing activity, SecurityException, ...) are swallowed so a broken intent never
 * crashes the page.
 */
private fun launchSetting(context: Context, action: String, data: String? = null) {
    runCatching {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (data != null) intent.data = Uri.parse(data)
        context.startActivity(intent)
    }
}

/** Launch the Shizuku authorization request, falling back to its app details page. */
private fun launchShizuku(context: Context) {
    val requested = runCatching {
        context.startActivity(
            Intent("moe.shizuku.manager.intent.action.REQUEST_AUTHORIZATION")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
    if (!requested) {
        launchSetting(
            context,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:moe.shizuku.manager",
        )
    }
}

/** Launch the app's notification settings page (API 26+), falling back to app details. */
private fun launchNotificationSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }.onFailure {
            launchSetting(
                context,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}",
            )
        }
    } else {
        launchSetting(
            context,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}",
        )
    }
}

/** Launch the all-files-access settings on R+, falling back to app details. */
private fun launchStorageSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        launchSetting(
            context,
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}",
        )
    } else {
        launchSetting(
            context,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}",
        )
    }
}