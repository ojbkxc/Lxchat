package com.lxseek.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 语音服务 Hub（导航中心）。
 *
 * 顶部 hero 区域点明「语音服务总入口」；
 * 下方以「能力分层」大号功能磁贴（TTS / STT / 语音设置）呈现，
 * 与全 App 视觉一致。点击各磁贴跳转到对应子设置页。
 *
 * 设计参考 ZorvAI 的 QuroVoiceServiceScreen，颜色统一使用 MaterialTheme.colorScheme，
 * 文案使用中文字符串字面量，不依赖 strings.xml。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsVoiceServicePage(
    onBack: () -> Unit,
    onOpenTts: () -> Unit,
    onOpenStt: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VoiceServiceHero()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CapabilityTile(
                    icon = Icons.Filled.VolumeUp,
                    title = "语音合成 (TTS)",
                    sub = "云端服务商 · 音色 · 试听",
                    onClick = onOpenTts,
                )
                CapabilityTile(
                    icon = Icons.Filled.Mic,
                    title = "语音识别 (ASR)",
                    sub = "本地 / 云端模型",
                    onClick = onOpenStt,
                )
                CapabilityTile(
                    icon = Icons.Filled.Settings,
                    title = "语音设置",
                    sub = "朗读 · 语音球 · 对话框语音按钮",
                    onClick = onOpenVoiceSettings,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 顶部 hero：渐变背景卡片 + 左侧图标盒 + 标题/副标。
 * 渐变使用 primary 的低透明度横向渐变，呈现「能力入口」氛围。
 */
@Composable
private fun VoiceServiceHero() {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(cs.primary.copy(alpha = 0.18f), cs.primary.copy(alpha = 0.05f))
                )
            )
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(24.dp), tint = cs.primary)
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("语音服务", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "语音合成 · 语音识别 · 语音设置，统一入口",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/**
 * 能力分层大号功能磁贴：圆角卡片 + 1dp 描边 + 左侧图标盒 + 标题/副标 + 右侧箭头。
 * 图标盒使用 primary 的低透明度柔和背景，与 hero 视觉呼应。
 */
@Composable
private fun CapabilityTile(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = cs.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant)
    }
}