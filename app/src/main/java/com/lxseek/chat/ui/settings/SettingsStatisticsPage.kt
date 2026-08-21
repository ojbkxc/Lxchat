package com.lxseek.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.R
import com.lxseek.chat.data.local.DailyUsageRow
import com.lxseek.chat.data.local.ModelUsageRow
import com.lxseek.chat.data.local.UsageStatistics
import com.lxseek.chat.ui.components.LxChatEmptyState
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private data class UsageStatsSnapshot(
    val overall: UsageStatistics,
    val byModel: List<ModelUsageRow>,
    val byDay: List<DailyUsageRow>,
)

@Composable
fun SettingsStatisticsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LxChatApplication
    val dao = remember(app) { app.container.chatDao }
    var snapshot by remember { mutableStateOf<UsageStatsSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    val unknownError = stringResource(R.string.unknown_error)
    val errorTitle = stringResource(R.string.stats_error_title)
    val errorDesc = stringResource(R.string.stats_error_desc)

    LaunchedEffect(refreshTick) {
        loading = true
        failed = false
        withContext(Dispatchers.IO) {
            runCatching {
                UsageStatsSnapshot(
                    overall = dao.getUsageStatistics(),
                    byModel = dao.getUsageByModel(),
                    byDay = dao.getUsageByDay(System.currentTimeMillis() - 29L * 86400000L),
                )
            }
        }.onSuccess { snapshot = it }
            .onFailure { error ->
                failed = true
                viewModel.emitSnackbar(error.localizedMessage ?: unknownError)
            }
        loading = false
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_statistics),
        onBack = onBack,
        actions = {
            IconButton(onClick = { refreshTick++ }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.stats_refresh))
            }
        },
    ) {
        when (val s = snapshot) {
            null -> {
                if (failed) {
                    LxChatEmptyState(
                        title = errorTitle,
                        description = errorDesc,
                        markSize = 52.dp,
                        action = {
                            TextButton(onClick = { refreshTick++ }) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.height(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.stats_retry))
                            }
                        },
                    )
                } else if (loading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            else -> StatisticsContent(s)
        }
    }
}

@Composable
private fun StatisticsContent(s: UsageStatsSnapshot) {
    val overall = s.overall
    if (overall.conversationCount <= 0 && overall.messageCount <= 0) {
        EmptyUsageState()
        return
    }

    SettingsGroupColumn {
        SettingsGroup(
            title = stringResource(R.string.stats_overview),
            items = listOf(
                { StatGrid(overall, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) },
            ),
        )

        SettingsGroup(
            title = stringResource(R.string.stats_token_breakdown),
            items = listOf(
                { StatRow(stringResource(R.string.stats_input_tokens), formatCompact(overall.inputTokenCount)) },
                { StatRow(stringResource(R.string.stats_output_tokens), formatCompact(overall.outputTokenCount)) },
                { StatRow(stringResource(R.string.stats_reasoning_tokens), formatCompact(overall.reasoningTokenCount)) },
            ),
        )

        SettingsGroup(
            title = stringResource(R.string.stats_details),
            items = listOf(
                { StatRow(stringResource(R.string.stats_user_messages), overall.userMessageCount.toString()) },
                { StatRow(stringResource(R.string.stats_model_messages), overall.modelMessageCount.toString()) },
                { StatRow(stringResource(R.string.stats_scheduled_tasks), overall.taskCount.toString()) },
            ),
        )

        val modelItems: List<@Composable () -> Unit> = if (s.byModel.isEmpty()) {
            listOf<@Composable () -> Unit>(
                {
                    SettingsItem(
                        headlineContent = {
                            Text(stringResource(R.string.stats_no_models), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    )
                },
            )
        } else {
            s.byModel.map { row ->
                @Composable {
                    SettingsItem(
                        headlineContent = { Text(row.modelName.orEmpty()) },
                        supportingContent = { Text(stringResource(R.string.stats_messages_count, row.messageCount)) },
                        trailingContent = { Text(formatCompact(row.outputTokenCount), fontWeight = FontWeight.Medium) },
                    )
                }
            }
        }
        SettingsGroup(title = stringResource(R.string.stats_by_model), items = modelItems)

        SettingsGroup(
            title = stringResource(R.string.stats_trend),
            items = listOf<@Composable () -> Unit>(
                {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (s.byDay.isEmpty()) {
                            Text(
                                text = stringResource(R.string.stats_no_activity),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TrendChart(s.byDay)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = formatDay(s.byDay.first().dayStart),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.stats_messages_per_day),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = formatDay(s.byDay.last().dayStart),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            ),
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    SettingsItem(
        headlineContent = { Text(label) },
        trailingContent = { Text(value, fontWeight = FontWeight.Medium) },
    )
}

@Composable
private fun StatGrid(overall: UsageStatistics, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(stringResource(R.string.stats_conversations), overall.conversationCount.toString(), Modifier.weight(1f))
            MetricCard(stringResource(R.string.stats_messages), overall.messageCount.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(stringResource(R.string.stats_output_tokens), formatCompact(overall.outputTokenCount), Modifier.weight(1f))
            MetricCard(stringResource(R.string.stats_runs), overall.runCount.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrendChart(days: List<DailyUsageRow>) {
    val maxCount = (days.maxOfOrNull { it.messageCount } ?: 0).coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth().height(128.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (day in days) {
            val fraction = (day.messageCount.toFloat() / maxCount).coerceIn(0.05f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun EmptyUsageState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.stats_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.stats_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatCompact(value: Long): String = when {
    value >= 1_000_000_000L -> formatFloat(value / 1_000_000_000.0, "B")
    value >= 1_000_000L -> formatFloat(value / 1_000_000.0, "M")
    value >= 1_000L -> formatFloat(value / 1_000.0, "K")
    else -> value.toString()
}

private fun formatFloat(v: Double, suffix: String): String =
    String.format(Locale.US, "%.1f%s", v, suffix)

private fun formatDay(dayStartMs: Long): String {
    val format = SimpleDateFormat("MM/dd", Locale.getDefault())
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(dayStartMs))
}