package com.lxseek.chat.api.util

/** One initial provider request plus five retries. */
internal object ProviderRetryPolicy {
    const val MAX_RETRIES = 5
    const val MAX_ATTEMPTS = MAX_RETRIES + 1

    /** Delay before retry number [retryNumber], where valid retries are 1 through 5. */
    fun delayMillis(retryNumber: Int): Long = when (retryNumber) {
        in 1..3 -> 5_000L
        in 4..5 -> 30_000L
        else -> error("Invalid retry number: $retryNumber")
    }

    /** Relay outcome used when the upstream accepted a request but failed to produce a generation. */
    fun isFailedToGenerateOutcome(raw: String?): Boolean =
        raw?.contains("failed to generate", ignoreCase = true) == true

    /** A relay may put the same transient outcome in a non-200 HTTP body instead of SSE JSON. */
    fun shouldRetryHttp(statusCode: Int, body: String?, retryableStatusCodes: Set<Int>): Boolean =
        statusCode in retryableStatusCodes || isFailedToGenerateOutcome(body)
}
