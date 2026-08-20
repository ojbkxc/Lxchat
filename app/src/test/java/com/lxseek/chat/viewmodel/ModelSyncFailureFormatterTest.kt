package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.ModelFetchEmptyResultException
import com.lxseek.chat.api.ModelFetchHttpException
import com.lxseek.chat.api.ModelFetchInvalidResponseException
import com.lxseek.chat.api.ModelFetchTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSyncFailureFormatterTest {
    private val labels = ModelSyncFailureLabels(
        noModels = "No models returned",
        timeout = "Request timed out",
        invalidResponse = "Invalid response",
        unknown = "Unknown error",
    )

    @Test
    fun noFailuresProducesNoSnackbarMessage() {
        assertNull(providerModelSyncFailureMessage(emptyList()))
    }

    @Test
    fun eachFailedProviderOccupiesExactlyOneLine() {
        val message = providerModelSyncFailureMessage(
            listOf(
                ProviderModelSyncFailure(
                    providerName = "OpenAI",
                    reason = "HTTP 401 — Invalid API key",
                ),
                ProviderModelSyncFailure(
                    providerName = "Custom\nEndpoint",
                    reason = "line one\r\nline two",
                ),
            )
        )

        assertEquals(
            "OpenAI: HTTP 401 — Invalid API key\nCustom Endpoint: line one line two",
            message,
        )
    }

    @Test
    fun typedFailuresKeepSpecificOrLocalizedReasons() {
        assertEquals(
            "HTTP 429 — Rate limit exceeded",
            modelSyncFailureReason(
                ModelFetchHttpException(429, "Rate limit exceeded"),
                labels,
            ),
        )
        assertEquals(
            "No models returned",
            modelSyncFailureReason(ModelFetchEmptyResultException(), labels),
        )
        assertEquals(
            "Request timed out",
            modelSyncFailureReason(ModelFetchTimeoutException(), labels),
        )
        assertEquals(
            "Invalid response — Expected JSON object",
            modelSyncFailureReason(
                ModelFetchInvalidResponseException(
                    IllegalArgumentException("Expected JSON object"),
                ),
                labels,
            ),
        )
    }
}
