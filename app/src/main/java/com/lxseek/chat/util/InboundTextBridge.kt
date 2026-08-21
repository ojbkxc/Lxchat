package com.lxseek.chat.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge for text submitted from the system "select text" (PROCESS_TEXT) action.
 *
 * Because PROCESS_TEXT can be triggered at any time (including a cold start), the selected text
 * travels here in the same application process and is consumed by the chat composer once it is
 * visible. Only the latest non-blank submission is retained; each payload is cleared after the
 * composer applies it.
 */
object InboundTextBridge {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()

    fun submit(text: String) {
        if (text.isNotBlank()) {
            _text.value = text.trim()
        }
    }

    fun consume() {
        _text.value = null
    }
}