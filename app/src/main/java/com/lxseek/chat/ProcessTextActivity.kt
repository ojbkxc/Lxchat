package com.lxseek.chat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.lxseek.chat.util.InboundTextBridge

/**
 * Entry point for the system "select text" (PROCESS_TEXT) action. When the user highlights text and
 * picks LxChat from the toolbar, the selected text is handed to the chat composer via
 * [InboundTextBridge], and the main UI is brought up. The unmodified text is also returned so the
 * calling IME/keyboard contract stays valid.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        val text = selected?.toString().orEmpty()

        if (text.isNotBlank()) {
            InboundTextBridge.submit(text)
        }

        // The OS expects a reply payload; the identity transform keeps us a well-behaved handler
        // even though the "direct" behavior consumes the text inside LxChat.
        val reply = Intent().apply {
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        }
        setResult(RESULT_OK, reply)

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        finish()
    }
}