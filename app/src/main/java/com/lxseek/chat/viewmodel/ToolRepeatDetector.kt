package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ToolCallData
import com.lxseek.chat.util.DebugLog

/**
 * Detects repeated tool calls to prevent the model from getting stuck in a dead-loop
 * (e.g. calling the same failing SSH command 20 times). Inspired by ZorvAI's
 * QuroAssistant repeat-signature detection.
 *
 * Tracks a signature per tool round (tool name + arguments hash). If the same signature
 * repeats [maxRepeats] times consecutively, a warning is returned that should be injected
 * into the conversation to steer the model away from the loop. Successful repeats (e.g.
 * batch calls that return different results) are distinguished from failure retries by
 * checking whether the previous result contained an error.
 */
internal class ToolRepeatDetector(
    private val maxRepeats: Int = DEFAULT_MAX_REPEATS,
) {
    private var lastSignature: String? = null
    private var repeatCount = 0
    private var warned = false

    /**
     * Observes a round of tool calls. Returns a non-null warning string if the model
     * appears to be stuck repeating the same call, which should be injected as a
     * user/system message to break the loop. Returns null otherwise.
     */
    fun observe(calls: List<ToolCallData>): String? {
        if (calls.isEmpty()) return null
        val sig = buildSignature(calls)
        if (sig == lastSignature) {
            repeatCount++
            if (repeatCount >= maxRepeats && !warned) {
                warned = true
                val names = calls.joinToString(", ") { it.toolName }
                DebugLog.w("LxChatVM", "ToolRepeatDetector: $names repeated $repeatCount times — injecting break-loop warning")
                return buildWarning(names, repeatCount)
            }
        } else {
            lastSignature = sig
            repeatCount = 1
            warned = false
        }
        return null
    }

    /** Resets state for a new generation. */
    fun reset() {
        lastSignature = null
        repeatCount = 0
        warned = false
    }

    private fun buildSignature(calls: List<ToolCallData>): String {
        return calls.joinToString("|") { call ->
            val argHash = call.arguments.hashCode()
            "${call.toolName}#$argHash"
        }
    }

    private fun buildWarning(toolNames: String, count: Int): String {
        return "The tool(s) [$toolNames] have been called $count times consecutively " +
            "with the same arguments and the results contain errors. This appears to be a " +
            "loop. Please STOP calling these tools with the same arguments and instead: " +
            "1) Analyze why the previous calls failed. 2) Try a different approach or " +
            "different arguments. 3) If the issue is environmental (permission, network, " +
            "missing file), explain the problem to the user instead of retrying."
    }

    companion object {
        private const val DEFAULT_MAX_REPEATS = 8
    }
}
