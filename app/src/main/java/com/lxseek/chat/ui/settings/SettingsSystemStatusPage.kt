package com.lxseek.chat.ui.settings

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.lxseek.chat.BuildConfig
import com.lxseek.chat.androidcontrol.AndroidUiControllerService
import com.lxseek.chat.membership.DeviceIdCard
import com.lxseek.chat.membership.MembershipTier
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.runtime.RuntimeEnginePlugin
import com.lxseek.chat.runtime.RuntimeStatus
import com.lxseek.chat.viewmodel.ChatViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 系统状态诊断仪表盘：把分散在多处的关键运行态集中到一个屏幕，方便一次性排查
 * 「装了没 / 开了没 / 跑着没 / 是什么等级」。五分区：
 *   1) 设备信息：型号 / Android 版本 / 应用版本 / 可用存储
 *   2) 权限与能力：无障碍 / Shizuku / Root / 悬浮窗 / 通知（绿/灰点）
 *   3) 运行时状态：PRoot 沙箱 / Termux / Python / Node.js 引擎
 *   4) 会员状态：当前等级 / 激活状态 / 到期时间 / 设备身份证
 *   5) 模型配置：当前主模型 / 已启用模型数量
 *
 * 设计参考自 ZorvAI 的 QuroSystemStatusScreen 四分区布局，适配 Lxchat 的五分区需求。
 * 所有文案使用中文字面量，不依赖 strings.xml。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSystemStatusPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current

    // ── 设备信息（一次性读取） ──
    val device = remember { readDeviceInfo(context) }

    // ── 权限与能力（同步探测，均为主线程安全操作） ──
    val permissions = remember(context) {
        PermissionStatus(
            accessibility = AndroidUiControllerService.instance != null,
            shizuku = runCatching {
                context.packageManager.getPackageInfo("moe.shizuku.manager", 0)
                true
            }.getOrDefault(false),
            root = File("/system/bin/su").exists() || File("/system/xbin/su").exists(),
            overlay = Settings.canDrawOverlays(context),
            notification = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
    }

    // ── 运行时状态 ──
    val runtimeEngineManager = viewModel.pluginMarket.runtimeEngineManager
    val pythonStatus = remember(runtimeEngineManager) { runtimeEngineManager?.status("runtime-python") }
    val nodeStatus = remember(runtimeEngineManager) { runtimeEngineManager?.status("runtime-node") }
    val prootAvailable = remember(viewModel.sandboxManager) {
        viewModel.sandboxManager?.isAvailableSync() == true
    }
    val termuxAvailable = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        }.getOrDefault(false)
    }

    // ── 会员状态 ──
    val membershipStatus by viewModel.membership.status.collectAsState()
    val deviceIdDisplay = remember(context) { DeviceIdCard.getDeviceIdDisplay(context) }

    // ── 模型配置 ──
    val selectedModel by viewModel.settings.selectedModel.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系统状态", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DeviceSection(device)
            PermissionSection(permissions)
            RuntimeSection(
                prootAvailable = prootAvailable,
                termuxAvailable = termuxAvailable,
                pythonStatus = pythonStatus,
                nodeStatus = nodeStatus,
            )
            MembershipSection(
                tier = membershipStatus.tier,
                isActive = membershipStatus.isActive,
                expiryTimestamp = membershipStatus.expiryTimestamp,
                deviceId = deviceIdDisplay,
            )
            ModelSection(
                selectedModel = selectedModel,
                enabledCount = enabledModels.size,
                modelAliases = modelAliases,
            )
        }
    }
}

// ---------------- 设备信息 ----------------

private data class DeviceInfo(
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val storageFreeGb: Double,
)

private fun readDeviceInfo(ctx: Context): DeviceInfo {
    val model = "${Build.MANUFACTURER} ${Build.MODEL}".replaceFirstChar { it.uppercase() }
    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val appVersion = BuildConfig.VERSION_NAME
    val freeGb = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    }.getOrDefault(0.0)
    return DeviceInfo(model, androidVersion, appVersion, freeGb)
}

@Composable
private fun DeviceSection(info: DeviceInfo) {
    SectionCard(title = "设备信息") {
        InfoRow("设备型号", info.model)
        InfoRow("Android 版本", info.androidVersion)
        InfoRow("应用版本", "v${info.appVersion}")
        InfoRow("可用存储", "%.1f GB".format(info.storageFreeGb))
    }
}

// ---------------- 权限与能力 ----------------

private data class PermissionStatus(
    val accessibility: Boolean,
    val shizuku: Boolean,
    val root: Boolean,
    val overlay: Boolean,
    val notification: Boolean,
)

@Composable
private fun PermissionSection(permissions: PermissionStatus) {
    val rows = listOf(
        "无障碍服务" to permissions.accessibility,
        "Shizuku / ADB" to permissions.shizuku,
        "Root (su)" to permissions.root,
        "悬浮窗" to permissions.overlay,
        "通知权限" to permissions.notification,
    )
    val granted = rows.count { it.second }
    SectionCard(title = "权限与能力", subtitle = "已授权：$granted / ${rows.size}") {
        rows.forEach { (label, ok) ->
            StatusDotRow(label = label, available = ok)
        }
    }
}

// ---------------- 运行时状态 ----------------

@Composable
private fun RuntimeSection(
    prootAvailable: Boolean,
    termuxAvailable: Boolean,
    pythonStatus: RuntimeStatus?,
    nodeStatus: RuntimeStatus?,
) {
    SectionCard(title = "运行时状态") {
        // PRoot 沙箱（Lxchat 内置 proot + Alpine）
        StatusDotRow(
            label = "PRoot 沙箱",
            available = prootAvailable,
            detail = if (prootAvailable) "已安装" else "未安装",
        )
        // Termux（外部应用）
        StatusDotRow(
            label = "Termux",
            available = termuxAvailable,
            detail = if (termuxAvailable) "已安装" else "未安装",
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        // Python 引擎（由 Linux 沙箱提供，无独立版本号）
        EngineRow(
            name = RuntimeEnginePlugin.engineDisplayName("runtime-python"),
            status = pythonStatus,
            sandboxProvided = true,
        )
        // Node.js 引擎
        EngineRow(
            name = RuntimeEnginePlugin.engineDisplayName("runtime-node"),
            status = nodeStatus,
        )
    }
}

@Composable
private fun EngineRow(name: String, status: RuntimeStatus?, sandboxProvided: Boolean = false) {
    val installed = status?.installed == true
    val running = status?.running == true
    val version = status?.installedVersion
    // For sandbox-provided engines (python) readiness == installed; for resident engines
    // (node/ffmpeg) the green dot still tracks the running process, unchanged from before.
    val active = if (sandboxProvided) installed else running
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).background(
                if (active) Color(0xFF34C759) else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            val detail = when {
                status == null -> "引擎管理器未就绪"
                sandboxProvided -> if (installed) "已就绪（沙箱）" else "未就绪（需安装沙箱）"
                !installed -> "未安装"
                running -> "运行中" + version?.takeIf { it.isNotBlank() }?.let { " · v$it" }.orEmpty()
                else -> "已安装" + version?.takeIf { it.isNotBlank() }?.let { " · v$it" }.orEmpty() + " · 已停止"
            }
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when {
                sandboxProvided -> if (installed) "已就绪" else "未就绪"
                running -> "运行中"
                installed -> "已停止"
                else -> "未安装"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------- 会员状态 ----------------

@Composable
private fun MembershipSection(
    tier: MembershipTier,
    isActive: Boolean,
    expiryTimestamp: Long?,
    deviceId: String,
) {
    val tierLabel = when (tier) {
        MembershipTier.Free -> "免费版"
        MembershipTier.Premium -> "高级版"
        MembershipTier.Pro -> "专业版"
        MembershipTier.Enterprise -> "企业版"
    }
    SectionCard(title = "会员状态") {
        InfoRow("当前等级", tierLabel)
        InfoRow("激活状态", if (isActive) "已激活" else "未激活")
        InfoRow("到期时间", formatExpiry(expiryTimestamp))
        InfoRow("设备身份证", deviceId)
    }
}

private fun formatExpiry(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0) return "无"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault("无")
}

// ---------------- 模型配置 ----------------

@Composable
private fun ModelSection(
    selectedModel: String,
    enabledCount: Int,
    modelAliases: Map<String, String>,
) {
    val displayName = if (selectedModel.isBlank()) {
        "未设置"
    } else {
        val alias = modelAliases[selectedModel]
        val name = alias?.takeIf { it.isNotBlank() } ?: ModelId.parse(selectedModel).apiModelName
        name.ifBlank { selectedModel }
    }
    SectionCard(title = "模型配置") {
        InfoRow("当前主模型", displayName)
        InfoRow("已启用模型数", "$enabledCount 个")
    }
}

// ---------------- 通用组件 ----------------

/**
 * 分区卡片：圆角 14dp + 1dp 描边 + 标题用 primary 色。
 */
@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
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
 * 信息行：label（固定宽度）+ value（占满剩余空间）。
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 状态点行：绿点（可用）/灰点（不可用）+ 标签 + 右侧状态文案。
 */
@Composable
private fun StatusDotRow(label: String, available: Boolean, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).background(
                if (available) Color(0xFF34C759) else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            if (available) "可用" else "不可用",
            style = MaterialTheme.typography.labelSmall,
            color = if (available) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}