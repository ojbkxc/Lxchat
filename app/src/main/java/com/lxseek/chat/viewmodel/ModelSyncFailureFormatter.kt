package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.ModelFetchEmptyResultException
import com.lxseek.chat.api.ModelFetchHttpException
import com.lxseek.chat.api.ModelFetchInvalidResponseException
import com.lxseek.chat.api.ModelFetchTimeoutException
import com.lxseek.chat.api.normalizeModelFetchDetail
import java.net.SocketTimeoutException

internal data class ModelSyncFailureLabels(
    val noModels: String,
    val timeout: String,
    val invalidResponse: String,
    val unknown: String,
)

internal data class ProviderModelSyncFailure(
    val providerName: String,
    val reason: String,
)

internal fun modelSyncFailureReason(
    error: Throwable,
    labels: ModelSyncFailureLabels,
): String = when (error) {
    is ModelFetchHttpException -> error.message.orEmpty()
    is ModelFetchEmptyResultException -> labels.noModels
    is ModelFetchTimeoutException,
    is SocketTimeoutException,
    -> labels.timeout
    is ModelFetchInvalidResponseException -> {
        val detail = error.cause?.localizedMessage
            ?.let { value -> normalizeModelFetchDetail(value) }
            ?.takeIf(String::isNotBlank)
        if (detail == null) labels.invalidResponse else "${labels.invalidResponse} — $detail"
    }
    else -> error.localizedMessage
        ?.let { value -> normalizeModelFetchDetail(value) }
        ?.takeIf(String::isNotBlank)
        ?: labels.unknown
}

internal fun providerModelSyncFailureMessage(
    failures: List<ProviderModelSyncFailure>,
): String? = failures.takeIf { items -> items.isNotEmpty() }?.joinToString("\n") { failure ->
    val provider = normalizeModelFetchDetail(failure.providerName, maxLength = 80)
        .ifBlank { "?" }
    val reason = normalizeModelFetchDetail(failure.reason).ifBlank { "?" }
    "$provider: $reason"
}
