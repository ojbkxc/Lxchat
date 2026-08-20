package com.lxseek.chat.api.openai

import com.lxseek.chat.util.Constants

class GroqProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_GROQ
    override val defaultBaseUrl: String = "https://api.groq.com/openai/v1"
}
