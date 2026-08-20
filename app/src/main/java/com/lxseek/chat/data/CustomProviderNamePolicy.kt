package com.lxseek.chat.data

import com.lxseek.chat.util.Constants

internal data class CustomProviderSanitizationResult(
    val accepted: List<CustomProviderConfig>,
    val rejected: List<CustomProviderConfig>,
)

/**
 * Owns the namespace invariant for custom providers.
 *
 * Provider names are persisted as identifiers throughout settings and model IDs, so a custom
 * provider must never share a name with a built-in provider. Comparisons are case-insensitive
 * because several provider-facing screens already classify names that way.
 */
internal object CustomProviderNamePolicy {
    private val reservedNames = listOf(
        Constants.PROVIDER_GOOGLE,
        Constants.PROVIDER_OPENAI,
        Constants.PROVIDER_ANTHROPIC,
        Constants.PROVIDER_DEEPSEEK,
        Constants.PROVIDER_QWEN,
        Constants.PROVIDER_GROQ,
        Constants.PROVIDER_OLLAMA,
        Constants.PROVIDER_OPEN_ROUTER,
        Constants.PROVIDER_LOCAL,
    )

    fun isAllowed(name: String): Boolean {
        val candidate = name.trim()
        return candidate.isNotEmpty() &&
            reservedNames.none { it.equals(candidate, ignoreCase = true) }
    }

    fun hasConflict(
        name: String,
        existingNames: Iterable<String>,
        currentName: String? = null,
    ): Boolean {
        val candidate = name.trim()
        if (!isAllowed(candidate)) return true

        return existingNames.any { existing ->
            val isCurrentEntry = currentName != null && existing == currentName
            !isCurrentEntry && existing.trim().equals(candidate, ignoreCase = true)
        }
    }

    /**
     * Quarantines invalid persisted/imported configs without rewriting their name-keyed settings.
     * Rewriting those keys would be unsafe for a collision such as `Local`, where model data may
     * belong to the real on-device provider. For duplicate custom names, the first entry wins.
     */
    fun sanitize(configs: List<CustomProviderConfig>): CustomProviderSanitizationResult {
        val accepted = mutableListOf<CustomProviderConfig>()
        val rejected = mutableListOf<CustomProviderConfig>()
        val acceptedNames = mutableListOf<String>()

        configs.forEach { config ->
            if (hasConflict(config.name, acceptedNames)) {
                rejected += config
            } else {
                accepted += config
                acceptedNames += config.name
            }
        }

        return CustomProviderSanitizationResult(
            accepted = accepted,
            rejected = rejected,
        )
    }
}
