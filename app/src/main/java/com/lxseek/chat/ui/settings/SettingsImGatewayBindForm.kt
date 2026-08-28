package com.lxseek.chat.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.data.SystemPromptEntry
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImPlatform
import com.lxseek.chat.im.weixin.WeixinBindingFlow
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.ui.components.QrCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

// ── Bind form ──────────────────────────────────────────────────────────────

@Composable
internal fun BindFormSection(
    platform: ImPlatform,
    agentPresets: List<SystemPromptEntry>,
    onConfirm: (ImGatewayConfig) -> Unit,
    onCancel: () -> Unit,
) {
    // 微信走 iLink 扫码绑定流程（WeixinBindingFlow），不使用表单字段。
    if (platform == ImPlatform.WECHAT) {
        WeixinQrBindSection(onConfirm = onConfirm, onCancel = onCancel)
        return
    }

    val context = LocalContext.current
    val method = platform.bindMethod()
    val fields = platform.credentialFields()

    // Per-field mutable state keyed by field key.
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, "") } } }
    val showSecret = remember { mutableStateMapOf<String, Boolean>().apply { fields.forEach { put(it.key, false) } } }
    var validationError by remember { mutableStateOf(false) }

    // T31: Agent Preset 选择状态。空串 = 跟随默认。
    var selectedPreset by remember { mutableStateOf("") }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    val strPresetLabel = stringResource(R.string.im_channel_agent_preset)
    val strPresetHint = stringResource(R.string.im_channel_agent_preset_hint)
    val strPresetFollow = stringResource(R.string.im_channel_agent_preset_follow_default)

    Column(modifier = Modifier.fillMaxWidth()) {
        if (method == BindMethod.QR) {
            // QR scan placeholder area.
            Surface(
                onClick = {
                    Toast.makeText(context, context.getString(R.string.im_channel_bind_qr_placeholder), Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "📷", style = MaterialTheme.typography.displaySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.im_channel_bind_qr),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.im_channel_bind_qr_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.im_channel_manual_config),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        // Credential fields.
        fields.forEach { field ->
            val value = values[field.key].orEmpty()
            val isSecret = field.kind == FieldKind.SECRET
            val placeholderText = field.placeholder
            OutlinedTextField(
                value = value,
                onValueChange = {
                    values[field.key] = it
                    validationError = false
                },
                label = { Text(stringResource(field.labelRes)) },
                placeholder = if (placeholderText != null) { { Text(placeholderText) } } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.kind) {
                        FieldKind.NUMBER -> KeyboardType.Number
                        FieldKind.URL -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    },
                ),
                visualTransformation = if (isSecret && showSecret[field.key] != true) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (isSecret) {
                    {
                        IconButton(onClick = { showSecret[field.key] = !(showSecret[field.key] ?: false) }) {
                            Icon(
                                imageVector = if (showSecret[field.key] == true) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    }
                } else null,
                isError = validationError && field.required && value.isBlank(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        // T31: Agent Preset 选择器（Dropdown）。
        // 候选项 = "跟随默认" + 全局 System Prompts；选中后写入 selectedPreset（空串=跟随默认）。
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedTextField(
                value = if (selectedPreset.isBlank()) strPresetFollow
                        else agentPresets.firstOrNull { it.id == selectedPreset }?.title ?: selectedPreset,
                onValueChange = { /* 只读，由下方 Dropdown 选择 */ },
                readOnly = true,
                label = { Text(strPresetLabel) },
                supportingText = { Text(strPresetHint) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { presetMenuExpanded = true }) {
                        Icon(imageVector = Icons.Default.ExpandMore, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = presetMenuExpanded, onDismissRequest = { presetMenuExpanded = false }) {
                DropdownMenuItem(text = { Text(strPresetFollow) }, onClick = { selectedPreset = ""; presetMenuExpanded = false })
                agentPresets.forEach { entry ->
                    DropdownMenuItem(text = { Text(entry.title) }, onClick = { selectedPreset = entry.id; presetMenuExpanded = false })
                }
            }
        }

        if (validationError) {
            Text(
                text = stringResource(R.string.im_channel_validation_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.im_channel_cancel)) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val config = buildBotConfig(platform, values.toMap(), selectedPreset)
                    if (config == null) validationError = true else onConfirm(config)
                },
            ) { Text(stringResource(R.string.im_channel_bind)) }
        }
    }
}

// ── 微信 iLink 扫码绑定 ────────────────────────────────────────────────────

/**
 * 微信 iLink 扫码绑定 UI：进入即启动 [WeixinBindingFlow.bind]，显示二维码图片 →
 * 轮询扫码状态 → 成功后构建 [ImGatewayConfig] 并回调 [onConfirm]。
 * 协程在 [DisposableEffect] 中取消，避免离开 Composable 后继续轮询。
 */
@Composable
internal fun WeixinQrBindSection(
    onConfirm: (ImGatewayConfig) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var bindJob by remember { mutableStateOf<Job?>(null) }
    var qrcodeUrl by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val strWaiting = stringResource(R.string.im_channel_wechat_qr_waiting)
    val strScanned = stringResource(R.string.im_channel_wechat_qr_scanned)
    val strConfirming = stringResource(R.string.im_channel_wechat_qr_confirming)
    val strVerify = stringResource(R.string.im_channel_wechat_qr_verify)
    val strFailed = stringResource(R.string.im_channel_wechat_qr_failed)

    fun startBind() {
        DebugLog.d("WeixinQrBind", "startBind: 开始扫码绑定流程")
        bindJob?.cancel(); qrcodeUrl = null; statusText = null; errorMsg = null; loading = true
        val flow = WeixinBindingFlow()
        bindJob = scope.launch {
            flow.bind { event ->
                when (event) {
                    is WeixinBindingFlow.Event.QrcodeReady -> {
                        DebugLog.d("WeixinQrBind", "收到二维码 URL: ${event.qrcodeUrl}")
                        loading = false; qrcodeUrl = event.qrcodeUrl; statusText = strWaiting
                    }
                    is WeixinBindingFlow.Event.StatusChanged -> {
                        DebugLog.d("WeixinQrBind", "扫码状态变化: ${event.status}")
                        statusText = when (event.status) {
                            "wait" -> strWaiting; "scaned" -> strScanned; "confirmed" -> strConfirming
                            "need_verifycode" -> strVerify; else -> event.status
                        }
                    }
                    is WeixinBindingFlow.Event.Success -> {
                        DebugLog.d("WeixinQrBind", "扫码绑定成功: baseUrl=${event.baseUrl}")
                        loading = false
                        onConfirm(ImGatewayConfig(
                            enabled = true, platform = ImPlatform.WECHAT.id, baseUrl = event.baseUrl,
                            token = event.token, channelId = "wechat:${UUID.randomUUID()}", pollIntervalMs = 5_000L,
                            botId = event.botId, // G1: 写入 ilink_bot_id
                        ))
                    }
                    is WeixinBindingFlow.Event.Failure -> {
                        DebugLog.e("WeixinQrBind", "扫码绑定失败: ${event.error.code} - ${event.error.message}")
                        loading = false; qrcodeUrl = null; errorMsg = event.error.message ?: strFailed
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { startBind() }
    DisposableEffect(Unit) { onDispose { bindJob?.cancel() } }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            loading -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.im_channel_wechat_qr_loading),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            qrcodeUrl != null -> {
                QrCode(
                    content = qrcodeUrl!!,
                    modifier = Modifier.padding(4.dp),
                    size = 220.dp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (statusText != null) {
                    Text(text = statusText!!, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
            errorMsg != null -> {
                Text(text = "❌", style = MaterialTheme.typography.displaySmall)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = stringResource(R.string.im_channel_wechat_qr_failed), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = errorMsg!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { bindJob?.cancel(); onCancel() }) { Text(stringResource(R.string.im_channel_cancel)) }
            if (errorMsg != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { startBind() }) { Text(stringResource(R.string.im_channel_wechat_qr_retry)) }
            }
        }
    }
}