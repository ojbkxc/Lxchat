package com.lxseek.chat.api.openai

import com.lxseek.chat.util.Constants

class DeepSeekProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_DEEPSEEK
    override val defaultBaseUrl: String = "https://api.deepseek.com"
    // Reasoning/content parsing uses BaseOpenAiProvider's default (reasoning_content + content).
}
