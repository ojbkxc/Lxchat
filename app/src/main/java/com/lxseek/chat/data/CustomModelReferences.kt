package com.lxseek.chat.data

internal fun Set<String>.replaceModelReference(
    oldModelId: String,
    newModelId: String?,
): Set<String> = buildSet {
    this@replaceModelReference.forEach { modelId ->
        when {
            modelId != oldModelId -> add(modelId)
            newModelId != null -> add(newModelId)
        }
    }
}

internal fun String?.replaceModelReference(
    oldModelId: String,
    newModelId: String?,
): String? = if (this == oldModelId) newModelId else this

internal fun Map<String, String>.replaceCustomModelAlias(
    oldModelId: String,
    newModelId: String?,
    alias: String,
): Map<String, String> = toMutableMap().apply {
    remove(oldModelId)
    if (newModelId != null && alias.isNotBlank()) {
        this[newModelId] = alias.trim()
    }
}
