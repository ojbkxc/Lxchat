package com.lxseek.chat.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lxseek.chat.ui.theme.LxDesign
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.mcp.McpElicitationController
import com.lxseek.chat.mcp.McpElicitationMode
import com.lxseek.chat.mcp.McpElicitationRequest
import com.lxseek.chat.mcp.McpElicitationResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 渲染 MCP 服务器主动发起的 elicitation 请求（MCP 2025-11-25）。
 *
 * FORM 模式按服务器的 JSON schema 渲染表单字段，用户填写后以
 * [McpElicitationResult] 返回；URL 模式展示链接并支持在浏览器中打开，
 * 用户确认完成后再应答服务器。
 */
@Composable
fun McpElicitationPanel(
    pending: McpElicitationController.PendingElicitation,
    onResolve: (McpElicitationResult) -> Unit,
    onCancel: () -> Unit,
) {
    when (pending.request.mode) {
        McpElicitationMode.FORM -> FormElicitationDialog(
            request = pending.request,
            onResolve = onResolve,
            onCancel = onCancel,
        )
        McpElicitationMode.URL -> UrlElicitationDialog(
            request = pending.request,
            onResolve = onResolve,
            onCancel = onCancel,
        )
    }
}

private enum class FormFieldType { TEXT, NUMBER, BOOLEAN, ENUM }

private data class FormField(
    val name: String,
    val label: String,
    val type: FormFieldType,
    val description: String,
    val required: Boolean,
    val options: List<String>,
)

private fun parseFields(request: McpElicitationRequest): List<FormField> {
    val schema = request.requestedSchema ?: buildJsonObject {}
    val properties = (schema["properties"] as? JsonObject) ?: buildJsonObject {}
    val required = (schema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.toSet()
        .orEmpty()
    return properties.entries.map { (name, value) ->
        val prop = value as? JsonObject
        val type = (prop?.get("type") as? JsonPrimitive)?.contentOrNull
        val enumValues = (prop?.get("enum") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        FormField(
            name = name,
            label = (prop?.get("title") as? JsonPrimitive)?.contentOrNull ?: name,
            type = when {
                type == "boolean" -> FormFieldType.BOOLEAN
                enumValues.isNotEmpty() -> FormFieldType.ENUM
                type == "number" || type == "integer" -> FormFieldType.NUMBER
                else -> FormFieldType.TEXT
            },
            description = (prop?.get("description") as? JsonPrimitive)?.contentOrNull.orEmpty(),
            required = name in required,
            options = enumValues,
        )
    }
}

@Composable
private fun FormElicitationDialog(
    request: McpElicitationRequest,
    onResolve: (McpElicitationResult) -> Unit,
    onCancel: () -> Unit,
) {
    val fields = remember(request) { parseFields(request) }
    val textValues = remember(request) { mutableStateMapOf<String, String>() }
    val boolValues = remember(request) { mutableStateMapOf<String, Boolean>() }

    fun buildContent(): JsonObject = buildJsonObject {
        fields.forEach { field ->
            when (field.type) {
                FormFieldType.BOOLEAN -> put(field.name, boolValues[field.name] ?: false)
                FormFieldType.NUMBER -> put(field.name, textValues[field.name]?.toDoubleOrNull() ?: 0.0)
                else -> put(field.name, textValues[field.name].orEmpty())
            }
        }
    }

    val valid = fields
        .filter { it.required && it.type != FormFieldType.BOOLEAN }
        .all { !textValues[it.name].isNullOrBlank() }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onCancel,
        icon = {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                "${request.serverName} · ${stringResource(R.string.mcp_elicitation_title)}",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(request.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                if (fields.isEmpty()) {
                    Text(
                        stringResource(R.string.mcp_elicitation_no_fields),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                fields.forEach { field ->
                    Spacer(Modifier.height(12.dp))
                    when (field.type) {
                        FormFieldType.BOOLEAN -> {
                            val checked = boolValues[field.name] ?: false
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(LxDesign.cornerXS)),
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { boolValues[field.name] = it },
                                )
                                Column {
                                    Text(field.label, fontWeight = FontWeight.Medium)
                                    if (field.description.isNotBlank()) {
                                        Text(
                                            field.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        FormFieldType.ENUM -> {
                            val selected = textValues[field.name]
                            Text(field.label, fontWeight = FontWeight.Medium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                field.options.forEach { option ->
                                    FilterChip(
                                        selected = selected == option,
                                        onClick = { textValues[field.name] = option },
                                        label = { Text(option) },
                                    )
                                }
                            }
                        }
                        else -> {
                            OutlinedTextField(
                                value = textValues[field.name].orEmpty(),
                                onValueChange = { textValues[field.name] = it },
                                label = {
                                    Text(
                                        field.label +
                                            if (field.required) " *" else "",
                                    )
                                },
                                supportingText = field.description
                                    .takeIf(String::isNotBlank)
                                    ?.let { { Text(it) } },
                                singleLine = field.type != FormFieldType.TEXT || field.description.isBlank(),
                                keyboardOptions = if (field.type == FormFieldType.NUMBER) {
                                    androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                    )
                                } else {
                                    androidx.compose.foundation.text.KeyboardOptions.Default
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onResolve(
                        McpElicitationResult(
                            action = McpElicitationResult.Accept,
                            content = buildContent(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.mcp_elicitation_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.mcp_elicitation_cancel))
            }
        },
    )
}

@Composable
private fun UrlElicitationDialog(
    request: McpElicitationRequest,
    onResolve: (McpElicitationResult) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val url = request.url.orEmpty()
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onCancel,
        icon = {
            Icon(
                Icons.Default.Link,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                "${request.serverName} · ${stringResource(R.string.mcp_elicitation_title)}",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(request.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                if (url.isNotBlank()) {
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }) { Text(stringResource(R.string.mcp_elicitation_open_url)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onResolve(McpElicitationResult(action = McpElicitationResult.Accept))
            }) { Text(stringResource(R.string.mcp_elicitation_done)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.mcp_elicitation_cancel))
            }
        },
    )
}
