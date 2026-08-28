package com.lxseek.chat.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.util.Constants

// Shape constants matching SettingsGroup's per-position rounding (12dp outer edges, 4dp where cards meet).
// Each encodes top-corners / bottom-corners for its place in the group.
internal val FullRounded   = RoundedCornerShape(12.dp)
internal val TopRounded    = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
internal val BottomRounded = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
internal val MidRounded    = RoundedCornerShape(4.dp)
internal val FlatShape     = RoundedCornerShape(0.dp)
internal val FlatToBottom  = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
internal val FourBottom    = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)

internal val RemoteModelProviders = listOf(
    Constants.PROVIDER_GOOGLE,
    Constants.PROVIDER_OPENAI,
    Constants.PROVIDER_ANTHROPIC,
    Constants.PROVIDER_DEEPSEEK,
    Constants.PROVIDER_QWEN,
    Constants.PROVIDER_GROQ,
    Constants.PROVIDER_OLLAMA,
    Constants.PROVIDER_OPEN_ROUTER,
)

internal data class ModelProviderGroup(
    val providerName: String,
    val models: List<String>,
)

internal fun customModelGroups(
    customModels: Set<String>,
    providerOrder: List<String>,
): List<ModelProviderGroup> {
    val providerPositions = providerOrder.withIndex().associate { (index, name) -> name to index }
    return customModels
        .groupBy { ModelId.parse(it).providerName }
        .map { (providerName, models) ->
            ModelProviderGroup(
                providerName = providerName,
                models = models.sortedBy { ModelId.parse(it).apiModelName.lowercase() },
            )
        }
        .sortedWith(
            compareBy<ModelProviderGroup>(
                { providerPositions[it.providerName] ?: Int.MAX_VALUE },
                { it.providerName.lowercase() },
            )
        )
}

internal fun fetchedModelGroups(
    availableModels: Map<String, List<String>>,
    customModels: Set<String>,
    modelAliases: Map<String, String>,
    query: String,
): List<ModelProviderGroup> {
    val normalizedQuery = query.trim()
    return availableModels.mapNotNull { (providerName, models) ->
        val providerMatches =
            normalizedQuery.isNotEmpty() &&
                providerName.contains(normalizedQuery, ignoreCase = true)
        val filteredModels = models
            .asSequence()
            .filterNot { it in customModels }
            .distinct()
            .filter { model ->
                normalizedQuery.isEmpty() ||
                    providerMatches ||
                    ModelId.parse(model).apiModelName.contains(
                        normalizedQuery,
                        ignoreCase = true,
                    ) ||
                    modelAliases[model]?.contains(normalizedQuery, ignoreCase = true) == true
            }
            .toList()
        filteredModels.takeIf { it.isNotEmpty() }?.let {
            ModelProviderGroup(providerName = providerName, models = it)
        }
    }
}