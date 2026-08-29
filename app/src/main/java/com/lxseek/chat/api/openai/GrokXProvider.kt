package com.lxseek.chat.api.openai

import com.lxseek.chat.util.Constants

/**
 * x.ai 官方 Grok 提供商(OpenAI 兼容)。
 *
 * 与普通 API Key 提供商不同,该提供商的密钥来源是「Grok 官方账号登录」:
 * [com.lxseek.chat.grok.GrokXOAuthManager] 完成 OAuth 后,把 access token 通过
 * [com.lxseek.chat.data.repository.SettingsRepository.upsertApiKey] 写入为
 * [Constants.PROVIDER_GROK] 的活动 API Key。这里的请求逻辑与其它 OpenAI 兼容
 * 提供商完全一致(见 [BaseOpenAiProvider])。
 */
class GrokXProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_GROK
    override val defaultBaseUrl: String = "https://api.x.ai/v1"

    companion object {
        /** Preset Grok model list (aligned with cc-haha-main grokOfficialProvider.ts). */
        val PRESET_MODELS = listOf(
            "grok-4.6",
            "grok-4.5",
            "grok-composer-2.5-fast",
        )
    }
}