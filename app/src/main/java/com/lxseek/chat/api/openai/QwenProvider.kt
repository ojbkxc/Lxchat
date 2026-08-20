package com.lxseek.chat.api.openai

import com.lxseek.chat.util.Constants

class QwenProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_QWEN
    override val defaultBaseUrl: String = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    // Reasoning/content parsing uses BaseOpenAiProvider's default (reasoning_content + content).
}
