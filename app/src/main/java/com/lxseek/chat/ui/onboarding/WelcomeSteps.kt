package com.lxseek.chat.ui.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.lxseek.chat.R
import com.lxseek.chat.data.CustomEndpointProtocol
import com.lxseek.chat.data.LocalChatModelConfig
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.ui.components.CustomEndpointProtocolSelector
import com.lxseek.chat.ui.components.clearFocusOnTap
import com.lxseek.chat.ui.components.providerIcon
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.lxseek.chat.util.Constants

// Step pages extracted from WelcomeScreen.kt to keep that file under the
// 999-line ceiling. Each page is a self-contained card surfaced inside the
// onboarding pager; the orchestrator in WelcomeScreen.kt wires state and
// callbacks into them.

@Composable
internal fun ProviderPage(
    providers: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    configuredProviders: Set<String> = emptySet(),
) {
    val scrollState = rememberScrollState()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    // Responsive cap: 50% of screen height so the provider card no longer
    // overflows on small screens nor wastes space on tablets.
    val maxProviderHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Surface(
        modifier = modifier.heightIn(max = maxProviderHeight),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxWidth().drawBehind {
            if (scrollState.maxValue > 0) {
                val progress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
                val barW = 4.dp.toPx()
                val barX = size.width - barW - 8.dp.toPx()
                val barH = size.height - 40.dp.toPx()
                val barY = 20.dp.toPx()
                drawRoundRect(trackColor, topLeft = Offset(barX, barY), size = Size(barW, barH), cornerRadius = CornerRadius(2.dp.toPx()))
                val thumbH = barH * 0.35f
                val thumbY = barY + (barH - thumbH) * progress
                drawRoundRect(thumbColor, topLeft = Offset(barX, thumbY), size = Size(barW, thumbH), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }) {
            Column(Modifier.verticalScroll(scrollState)) {
                Spacer(Modifier.height(10.dp))
                providers.forEach { p ->
                    val iconRes = providerIcon(p)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(p) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == p, onClick = { onSelect(p) })
                        Spacer(Modifier.width(8.dp))
                        when {
                            iconRes != 0 -> Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                            p == Constants.PROVIDER_LOCAL -> Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                            p == "Custom" -> Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                            else -> Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            p,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selected == p) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
internal fun ApiKeyPage(
    provider: String?,
    isCustom: Boolean,
    apiKeyText: String,
    onApiKeyChange: (String) -> Unit,
    baseUrlText: String,
    onBaseUrlChange: (String) -> Unit,
    customProtocol: CustomEndpointProtocol,
    onCustomProtocolChange: (CustomEndpointProtocol) -> Unit,
    apiKeyVisible: Boolean,
    onToggleVisibility: () -> Unit,
    isImporting: Boolean,
    onImportGGUF: () -> Unit,
    modifier: Modifier,
    localModels: List<LocalChatModelConfig> = emptyList(),
) {
    Surface(modifier, RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        if (provider == null) {
            Column(Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.onboarding_no_provider), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (provider == Constants.PROVIDER_LOCAL) {
            val label = if (isImporting) stringResource(R.string.onboarding_importing)
                else localModels.lastOrNull()?.alias ?: stringResource(R.string.onboarding_import_gguf)
            Column(Modifier.padding(32.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onImportGGUF, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !isImporting) {
                    Text(label, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        } else if (provider == Constants.PROVIDER_OLLAMA) {
            Column(Modifier.padding(32.dp).fillMaxWidth().clearFocusOnTap()) {
                val iconRes = providerIcon(provider)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconRes != 0) {
                        Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    } else {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_ollama_hint)) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                )
            }
        } else if (isCustom) {
            Column(Modifier.padding(32.dp).fillMaxWidth().clearFocusOnTap()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                CustomEndpointProtocolSelector(
                    selected = customProtocol,
                    onSelected = onCustomProtocolChange,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseUrlText, onValueChange = onBaseUrlChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_custom_base_url_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_api_key_hint)) },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (apiKeyVisible) R.string.onboarding_hide_key else R.string.onboarding_show_key), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                )
            }
        } else {
            Column(Modifier.padding(32.dp).fillMaxWidth().clearFocusOnTap()) {
                val iconRes = providerIcon(provider)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconRes != 0) {
                        Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    } else {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_api_key_hint)) },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (apiKeyVisible) R.string.onboarding_hide_key else R.string.onboarding_show_key), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                )
            }
        }
    }
}

@Composable
internal fun ModelPage(
    models: List<String>,
    modelAliases: Map<String, String>,
    selectedId: String?,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    Surface(modifier, RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        if (models.isEmpty()) {
            // While a fetch is in flight show a quiet spinner instead of the empty
            // state, so the list never flashes "no models" then jumps into view.
            // Fixed-height slot keeps the card identical between both states, and
            // Crossfade fades the spinner in/out rather than popping.
            Box(Modifier.fillMaxWidth().padding(32.dp).height(40.dp), contentAlignment = Alignment.Center) {
                Crossfade(targetState = isLoading, animationSpec = tween(400), label = "modelLoading") { loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(stringResource(R.string.onboarding_no_models), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        } else {
            val scrollState = rememberScrollState()
            val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            val maxProviderHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
            Box(Modifier.fillMaxWidth().heightIn(max = maxProviderHeight).drawBehind {
                if (scrollState.maxValue > 0) {
                    val progress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
                    val barW = 4.dp.toPx()
                    val barX = size.width - barW - 8.dp.toPx()
                    val barH = size.height - 24.dp.toPx()
                    val barY = 12.dp.toPx()
                    drawRoundRect(trackColor, topLeft = Offset(barX, barY), size = Size(barW, barH), cornerRadius = CornerRadius(2.dp.toPx()))
                    val thumbH = barH * 0.35f
                    val thumbY = barY + (barH - thumbH) * progress
                    drawRoundRect(thumbColor, topLeft = Offset(barX, thumbY), size = Size(barW, thumbH), cornerRadius = CornerRadius(2.dp.toPx()))
                }
            }) {
                Column(Modifier.verticalScroll(scrollState)) {
                    Spacer(Modifier.height(10.dp))
                    models.forEach { m ->
                        val name = modelAliases[m] ?: com.lxseek.chat.model.ModelId.parse(m).apiModelName
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(m) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedId == m, onClick = { onSelect(m) })
                            Spacer(Modifier.width(8.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (selectedId == m) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
internal fun AutoBackupPage(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier) {
    Surface(modifier, RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Column(Modifier.padding(32.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.auto_backup_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onToggle(!enabled) }.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_backup_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.auto_backup_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}