package com.lxseek.chat.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.plugin.FieldType
import com.lxseek.chat.plugin.PluginSettingsSchema
import com.lxseek.chat.plugin.SettingsField

/**
 * Generic settings page driven by [PluginSettingsSchema].
 * Renders switches, text inputs, dropdowns etc. based on schema field types,
 * eliminating the need for a dedicated Compose page per plugin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPluginPage(
    pluginName: String,
    schema: PluginSettingsSchema,
    currentValues: Map<String, String?>,
    onValueChanged: (String, String?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pluginName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            schema.fields.forEach { field ->
                SettingsFieldRenderer(
                    field = field,
                    currentValue = currentValues[field.key] ?: field.defaultValue,
                    onValueChanged = { onValueChanged(field.key, it) },
                )
            }
        }
    }
}

@Composable
private fun SettingsFieldRenderer(
    field: SettingsField,
    currentValue: String?,
    onValueChanged: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (field.type) {
            FieldType.SWITCH -> SwitchField(field, currentValue, onValueChanged)
            FieldType.TEXT_INPUT -> TextField(field, currentValue, onValueChanged, isPassword = false)
            FieldType.PASSWORD -> TextField(field, currentValue, onValueChanged, isPassword = true)
            FieldType.DROPDOWN -> DropdownField(field, currentValue, onValueChanged)
            FieldType.NUMBER -> NumberField(field, currentValue, onValueChanged)
            FieldType.SLIDER -> SliderField(field, currentValue, onValueChanged)
        }
        if (field.description != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = field.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchField(
    field: SettingsField,
    currentValue: String?,
    onValueChanged: (String?) -> Unit,
) {
    val checked = currentValue?.toBooleanStrictOrNull() ?: false
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onValueChanged(it.toString()) },
        )
    }
}

@Composable
private fun TextField(
    field: SettingsField,
    currentValue: String?,
    onValueChanged: (String?) -> Unit,
    isPassword: Boolean,
) {
    var text by remember(currentValue) { mutableStateOf(currentValue ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChanged(it.ifBlank { null })
        },
        label = { Text(field.label) },
        placeholder = field.placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    field: SettingsField,
    currentValue: String?,
    onValueChanged: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(currentValue) { mutableStateOf(currentValue ?: field.options.firstOrNull() ?: "") }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(field.label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        selected = option
                        onValueChanged(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    field: SettingsField,
    currentValue: String?,
    onValueChanged: (String?) -> Unit,
) {
    var text by remember(currentValue) { mutableStateOf(currentValue ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }
            onValueChanged(text.ifBlank { null })
        },
        label = { Text(field.label) },
        placeholder = field.placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SliderField(
    field: SettingsField,
    currentValue: String?,
    onValueChanged: (String?) -> Unit,
) {
    val value = currentValue?.toFloatOrNull() ?: 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = { onValueChanged(it.toString()) },
        )
    }
}
