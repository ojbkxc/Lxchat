package com.lxseek.chat.ui.companion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Surface
import com.lxseek.chat.ui.components.MetricCardRow
import com.lxseek.chat.ui.components.StatusBadge

/**
 * Self-contained "伴生柔软质感" preview screen. Does NOT depend on ChatViewModel so it can be
 * reviewed in isolation: it renders a taste of the new companion design language (large radii,
 * breathing motion, single muted accent, metric cards, soft input pill). Open from settings.
 */
@Composable
fun CompanionDesignPreview(onBack: () -> Unit) {
    val accent = remember { CompanionAccent.fromPreset() }
    var planted by remember { mutableStateOf(false) }
    val inputHeight by animateDpAsState(targetValue = if (planted) 232.dp else 56.dp, label = "inputPill")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("伴生柔软质感", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Companion Design Preview", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge("Preview", active = true)
        }
        Spacer(Modifier.height(8.dp))

        // Brand slot
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("想让它做什么？", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "从一句话想法开始，或交给它一条完整任务。",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))

        // Symmetric metric cards (your clean dashboard preference, no progress bars)
        MetricCardRow(
            metrics = listOf(
                "提供商" to "9+",
                "本地模型" to "GGUF",
                "IM 渠道" to "iLink",
                "任务" to "Cron",
            ),
            cardHeight = 76.dp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(16.dp))

        // Soft input pill (tap to breathe taller)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(CompanionShapes.pill)
                .clickable { planted = !planted },
            shape = CompanionShapes.pill,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            AnimatedVisibility(
                visible = planted,
                enter = fadeIn(animationSpec = CompanionMotion.fadeIn(260)),
                exit = fadeOut(animationSpec = CompanionMotion.fadeIn(200)),
            ) {
                if (planted) {
                    Box(Modifier.height(inputHeight - 56.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                        Text(
                            "呼吸动效演示：大圆角 + 柔软的展开。",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.height(56.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("问任何事，或使用 @工具…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CompanionShapes.pill)
                        .background(accent.color),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Capability cards row — large radii
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CapabilityCard("自由对话", "多提供商 BYOK", Modifier.weight(1f))
            CapabilityCard("Agent 任务", "工具 / 代码编排", Modifier.weight(1f))
            CapabilityCard("本地推理", "llama.cpp 离线", Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CapabilityCard(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(CompanionShapes.card)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}