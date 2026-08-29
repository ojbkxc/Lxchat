package com.lxseek.chat.api.openai

import com.lxseek.chat.util.Constants

/**
 * OpenAI 官方 ChatGPT 提供商(OpenAI 兼容)。
 *
 * 与普通 API Key 提供商不同,该提供商的密钥来源是「ChatGPT 官方账号登录」:
 * [com.lxseek.chat.openai.OpenAIXOAuthManager] 完成 OAuth 后,把 access token 通过
 * [com.lxseek.chat.data.repository.SettingsRepository.upsertApiKey] 写入为
 * [Constants.PROVIDER_CHATGPT] 的活动 API Key。这里的请求逻辑与其它 OpenAI 兼容
 * 提供商完全一致(见 [BaseOpenAiProvider])。
 */
class OpenAIXProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_CHATGPT
    override val defaultBaseUrl: String = "https://api.openai.com/v1"

    companion object {
        /** Preset ChatGPT model list (aligned with cc-haha-main models.ts). */
        val PRESET_MODELS = listOf(
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.3-codex",
            "gpt-5.4",
            "gpt-5.5",
            "gpt-5.4-mini",
        )
    }
}