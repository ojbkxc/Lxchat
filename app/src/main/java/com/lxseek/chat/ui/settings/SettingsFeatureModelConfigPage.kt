package com.lxseek.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxseek.chat.membership.FunctionModelBinding
import com.lxseek.chat.membership.FunctionModelConfigRepository
import com.lxseek.chat.membership.FunctionType
import com.lxseek.chat.viewmodel.ChatViewModel

/**
 * 功能模型配置页面：为各类 AI 能力绑定模型。
 * 每个功能可「跟随主模型」或指定独立模型；独立模型从已配置的模型列表中选取或手动输入。
 * 参考设计：ZorvAI QuroFeatureModelConfigScreen。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFeatureModelConfigPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { FunctionModelConfigRepository(ctx) }
    var cfg by remember { mutableStateOf(repo.load()) }
    var pickerType by remember { mutableStateOf<FunctionType?>(null) }

    val selectedModel by viewModel.settings.selectedModel.collectAsState()
    val customModels by viewModel.settings.customModels.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("功能模型配置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            InfoBox("为各类 AI 能力绑定模型。每个功能可跟随主模型或指定独立模型。")
            Spacer(Modifier.height(8.dp))
            FunctionType.values().forEach { type ->
                FeatureModelRow(
                    type = type,
                    binding = cfg[type] ?: FunctionModelBinding(),
                    onToggleGlobal = {
                        repo.setBinding(
                            type,
                            (cfg[type] ?: FunctionModelBinding()).copy(useGlobal = it),
                        )
                        cfg = repo.load()
                    },
                    onPick = { pickerType = type },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "提示：默认所有能力跟随主模型。指定独立模型后，对应功能即改用该模型。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    // ── 模型选择弹窗 ──
    pickerType?.let { type ->
        val current = cfg[type] ?: FunctionModelBinding()
        var manual by remember(type) { mutableStateOf(current.model.ifBlank { selectedModel }) }
        val candidateModels = remember(customModels, selectedModel) {
            (customModels + selectedModel).filter { it.isNotBlank() }.distinct()
        }

        AlertDialog(
            onDismissRequest = { pickerType = null },
            confirmButton = {
                TextButton(onClick = {
                    repo.setBinding(
                        type,
                        FunctionModelBinding(useGlobal = false, model = manual.trim()),
                    )
                    cfg = repo.load()
                    pickerType = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { pickerType = null }) { Text("取消") }
            },
            title = { Text("选择 ${type.label} 模型", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    if (candidateModels.isNotEmpty()) {
                        LazyColumn(
                            Modifier.fillMaxWidth().weight(1f, fill = false),
                        ) {
                            items(candidateModels) { m ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { manual = m }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = manual == m, onClick = { manual = m })
                                    Spacer(Modifier.width(8.dp))
                                    Text(m, fontSize = 13.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        label = { Text("模型名（可手动输入）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}

/** Lightweight info banner with a soft surface background. */
@Composable
private fun InfoBox(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** A single function row: icon + name + description + global toggle, with a model picker card when independent. */
@Composable
private fun FeatureModelRow(
    type: FunctionType,
    binding: FunctionModelBinding,
    onToggleGlobal: (Boolean) -> Unit,
    onPick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(featureIcon(type), null, Modifier.size(22.dp), tint = cs.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    type.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
                Text(
                    type.desc,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(checked = binding.useGlobal, onCheckedChange = onToggleGlobal)
        }
        if (!binding.useGlobal) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onPick)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (binding.model.isBlank()) "点击选择模型" else binding.model,
                    fontSize = 13.sp,
                    color = if (binding.model.isBlank()) cs.onSurfaceVariant else cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    null,
                    Modifier.size(16.dp),
                    tint = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** Maps a [FunctionType] to its representative Material icon. */
private fun featureIcon(type: FunctionType): ImageVector = when (type) {
    FunctionType.CHAT -> Icons.Filled.Chat
    FunctionType.TITLE_GEN -> Icons.Filled.Edit
    FunctionType.SUMMARIZE -> Icons.Filled.Summarize
    FunctionType.VISION -> Icons.Filled.Image
    FunctionType.TRANSCRIPTION -> Icons.Filled.Mic
}