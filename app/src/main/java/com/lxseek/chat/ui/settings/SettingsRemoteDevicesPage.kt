package com.lxseek.chat.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.onDispose
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.data.PeerInfo
import com.lxseek.chat.data.TrustResult
import com.lxseek.chat.service.DiscoveredDevice
import com.lxseek.chat.service.LanDeviceDiscovery
import com.lxseek.chat.service.RemoteDeviceLoop
import com.lxseek.chat.ui.components.MetricCardRow
import com.lxseek.chat.ui.components.QrCode
import com.lxseek.chat.ui.components.StatusBadge
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "SettingsRemoteDevicesPage"

/** 设备列表轮询刷新间隔（毫秒）。 */
private const val REFRESH_INTERVAL_MS = 2000L

/**
 * Multi-device management page. Integrates the remote-device collaboration loop
 * (discovery → auth → connect → execute → transfer) and surfaces the privacy
 * narrative (E2E encryption, TOFU trust model, LAN-only, local keys).
 *
 * Backed by [RemoteDeviceLoop] (T19) which composes [LanDeviceDiscovery] (T14),
 * [PeerTrustStore] + [DeviceIdentityManager] (T15). The QR pairing code and
 * metric cards come from the shared UI components (T18).
 *
 * @param onBack  invoked when the back arrow is pressed
 * @param modifier outer modifier
 */
@Composable
fun SettingsRemoteDevicesPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 远程设备协作闭环实例。localBaseDir 用 filesDir 作为文件传输信任基目录。
    val loop = remember(context) {
        RemoteDeviceLoop(
            context = context,
            localBaseDir = context.filesDir,
        )
    }

    // 发现开关状态
    var discoveryEnabled by remember { mutableStateOf(false) }
    // 列表刷新 tick：discoveredList/trustedPeers/connectedIds 不是 Compose 状态，
    // 用一个 tick 计数器定期触发重组以反映最新列表。
    var refreshTick by remember { mutableStateOf(0) }

    // 启动/停止发现 + 轮询刷新
    LaunchedEffect(discoveryEnabled) {
        if (discoveryEnabled) {
            val ok = loop.start()
            DebugLog.i(TAG, "start discovery: ok=$ok")
            // 开启期间定期刷新列表
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                refreshTick++
            }
        } else {
            loop.stop()
            DebugLog.i(TAG, "stop discovery")
        }
    }

    // 页面离开时确保释放资源
    DisposableEffect(Unit) {
        onDispose {
            loop.stop()
            DebugLog.i(TAG, "stopped discovery on page leave")
        }
    }

    // 当前列表快照（tick 变化时重新读取）
    val discoveredDevices = remember(refreshTick) { loop.discoveredList() }
    val trustedPeers = remember(refreshTick) { loop.trustedPeers() }
    val connectedIds = remember(refreshTick) { loop.connectedDeviceIds() }
    val trustedPeerIds = remember(refreshTick) { trustedPeers.map { it.deviceId }.toSet() }

    CollapsingSettingsLazyScaffold(
        title = "远程设备",
        onBack = onBack,
        modifier = modifier,
    ) {
        // 1. 本设备信息卡片
        item {
            SelfDeviceCard(
                deviceId = loop.selfDeviceId,
                deviceName = Build.MODEL,
                discoveredCount = discoveredDevices.size,
                connectedCount = connectedIds.size,
                trustedCount = trustedPeers.size,
            )
        }

        // 2. 设备发现开关
        item {
            Spacer(modifier = Modifier.height(12.dp))
            DiscoveryToggle(
                enabled = discoveryEnabled,
                onToggle = { discoveryEnabled = it },
            )
        }

        // 3. 已发现设备列表
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(
                title = "已发现设备",
                subtitle = "局域网内发现的其他 Lxchat 设备",
            )
        }
        if (discoveredDevices.isEmpty()) {
            item {
                EmptyHint("暂未发现其他设备，请确保其他设备已开启发现功能且在同一局域网")
            }
        } else {
            items(discoveredDevices, key = { it.name }) { device ->
                val deviceId = device.metadata[LanDeviceDiscovery.KEY_DEVICE_ID]
                DiscoveredDeviceItem(
                    device = device,
                    trustStatus = deviceTrustStatus(deviceId, trustedPeerIds),
                    isConnected = deviceId != null && deviceId in connectedIds,
                    onConnect = {
                        scope.launch {
                            val session = loop.openSession(device)
                            if (session != null) {
                                DebugLog.i(TAG, "connected to ${device.name}")
                            } else {
                                DebugLog.w(TAG, "failed to connect to ${device.name}")
                            }
                            refreshTick++
                        }
                    },
                    onRemoveTrust = {
                        val id = device.metadata[LanDeviceDiscovery.KEY_DEVICE_ID]
                        if (id != null) {
                            val removed = loop.forgetPeer(id)
                            DebugLog.i(TAG, "forget peer $id: removed=$removed")
                            refreshTick++
                        }
                    },
                )
            }
        }

        // 4. 已信任设备列表
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(
                title = "已信任设备",
                subtitle = "通过 TOFU 信任的对端设备",
            )
        }
        if (trustedPeers.isEmpty()) {
            item { EmptyHint("暂无已信任设备") }
        } else {
            items(trustedPeers, key = { it.deviceId }) { peer ->
                TrustedPeerItem(
                    peer = peer,
                    onRemoveTrust = {
                        val removed = loop.forgetPeer(peer.deviceId)
                        DebugLog.i(TAG, "forget peer ${peer.deviceId}: removed=$removed")
                        refreshTick++
                    },
                )
            }
        }

        // 5. 隐私叙事
        item {
            Spacer(modifier = Modifier.height(16.dp))
            PrivacyNarrativeCard()
        }
    }
}

// ── 本设备信息卡片 ──────────────────────────────────────────────────────────

/**
 * Shows this device's identity (ID + name), pairing QR code, and discovery /
 * connection / trust metric counts.
 */
@Composable
private fun SelfDeviceCard(
    deviceId: String,
    deviceName: String,
    discoveredCount: Int,
    connectedCount: Int,
    trustedCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "本设备",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "设备名称：$deviceName",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "设备 ID：${shortenId(deviceId)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            // 配对二维码：对端扫描后可发起 TOFU 握手
            QrCode(
                content = "lxchat://pair/$deviceId",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                size = 180.dp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            MetricCardRow(
                metrics = listOf(
                    "已发现" to discoveredCount.toString(),
                    "已连接" to connectedCount.toString(),
                    "已信任" to trustedCount.toString(),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── 设备发现开关 ─────────────────────────────────────────────────────────────

/** Toggle card for enabling/disabling LAN device discovery. */
@Composable
private fun DiscoveryToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "设备发现",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "开启后在局域网内发现其他 Lxchat 设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

// ── 已发现设备项 ─────────────────────────────────────────────────────────────

/** A single discovered device row with trust/connection badges and actions. */
@Composable
private fun DiscoveredDeviceItem(
    device: DiscoveredDevice,
    trustStatus: TrustResult,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onRemoveTrust: () -> Unit,
) {
    val deviceId = device.metadata[LanDeviceDiscovery.KEY_DEVICE_ID]
    val protocols = device.metadata[LanDeviceDiscovery.KEY_PROTOCOLS]
    val hostPort = device.host?.hostAddress?.let { "$it:${device.port}" } ?: "未知"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TrustStatusBadge(trustStatus)
                Spacer(modifier = Modifier.width(6.dp))
                ConnectionStatusBadge(isConnected)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hostPort,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (deviceId != null) {
                Text(
                    text = "ID：${shortenId(deviceId)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (protocols != null) {
                Text(
                    text = "协议：$protocols",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onConnect) { Text("连接") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onRemoveTrust) { Text("移除信任") }
            }
        }
    }
}

// ── 已信任设备项 ─────────────────────────────────────────────────────────────

/** A single trusted peer row with first-seen / last-active timestamps. */
@Composable
private fun TrustedPeerItem(
    peer: PeerInfo,
    onRemoveTrust: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = peer.name.ifBlank { "未命名设备" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRemoveTrust) { Text("移除信任") }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "ID：${shortenId(peer.deviceId)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "首次信任：${formatTime(peer.firstSeenAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "最后活跃：${formatTime(peer.lastSeenAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 状态徽章 ─────────────────────────────────────────────────────────────────

/** Trust-status badge: Trusted(green) / New(yellow) / Untrusted(red). */
@Composable
private fun TrustStatusBadge(status: TrustResult) {
    val (text, color) = when (status) {
        TrustResult.Trusted -> "✅ 已信任" to MaterialTheme.colorScheme.primary
        TrustResult.New -> "🆕 新设备" to MaterialTheme.colorScheme.tertiary
        TrustResult.Untrusted -> "⚠️ 不信任" to MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Connection-status badge: Connected(active) / Disconnected(inactive). */
@Composable
private fun ConnectionStatusBadge(connected: Boolean) {
    StatusBadge(
        text = if (connected) "已连接" else "未连接",
        active = connected,
    )
}

// ── 隐私叙事 ─────────────────────────────────────────────────────────────────

/** Privacy & security narrative card shown at the bottom of the page. */
@Composable
private fun PrivacyNarrativeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🔒",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "隐私与安全",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            PrivacyItem(
                icon = "🔒",
                title = "端到端加密",
                desc = "设备间通信使用 Ed25519 密钥交换，数据加密传输",
            )
            PrivacyItem(
                icon = "🤝",
                title = "TOFU 信任模型",
                desc = "首次连接时信任对端公钥（Trust On First Use），后续连接校验公钥一致性，防止 MITM",
            )
            PrivacyItem(
                icon = "🏠",
                title = "局域网直连",
                desc = "设备发现与通信在局域网内直接进行，数据不经过任何第三方服务器",
            )
            PrivacyItem(
                icon = "🔑",
                title = "本地密钥",
                desc = "设备密钥对在本地生成并存储，永不上传",
            )
        }
    }
}

/** One privacy narrative bullet: emoji + title + description. */
@Composable
private fun PrivacyItem(icon: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 辅助 Composable ──────────────────────────────────────────────────────────

/** Section header with optional subtitle. */
@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (subtitle != null) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Empty-state hint text. */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

// ── 辅助函数 ─────────────────────────────────────────────────────────────────

/** Truncate a long device ID to a readable prefix…suffix form. */
private fun shortenId(id: String): String {
    if (id.length <= 20) return id
    return id.take(12) + "…" + id.takeLast(4)
}

/** Format a millisecond timestamp as "yyyy-MM-dd HH:mm"; "未知" for non-positive. */
private fun formatTime(ts: Long): String {
    if (ts <= 0) return "未知"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

/**
 * Derive the trust status of a discovered device from the trusted-peer ID set.
 *
 * [TrustResult.Untrusted] is never returned here because untrusted devices are
 * rejected by [RemoteDeviceLoop.onDeviceFound] and never appear in the
 * discovered list — kept in the signature for completeness.
 */
private fun deviceTrustStatus(deviceId: String?, trustedPeerIds: Set<String>): TrustResult {
    if (deviceId == null) return TrustResult.New
    return if (deviceId in trustedPeerIds) TrustResult.Trusted else TrustResult.New
}