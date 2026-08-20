package com.lxseek.chat.api.openai

class CustomOpenAiProvider(
    override val name: String,
    override val defaultBaseUrl: String
) : BaseOpenAiProvider() {

    override val retryableStatusCodes: Set<Int> = setOf(401, 429, 502, 503, 504)

    override val retryMissingV1BaseUrl: Boolean = true

    // Reasoning arrives either as reasoning_content deltas (vLLM, DeepSeek-compatible servers)
    // or inline <think> tags in content (llama.cpp server, LM Studio) — parse both.
    override val parseInlineThinkTags: Boolean = true
}
