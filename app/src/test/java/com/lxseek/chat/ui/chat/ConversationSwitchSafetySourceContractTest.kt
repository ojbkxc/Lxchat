package com.lxseek.chat.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSwitchSafetySourceContractTest {
    @Test
    fun `conversation switch observes current projection without a fixed deadline`() {
        val source = File(
            locateMainSourceRoot(),
            "com/lxseek/chat/ui/chat/ChatScrollCoordinator.kt",
        ).readText()

        assertFalse(
            "conversation projection latency must not terminalize the switch on a timer",
            source.contains("CONVERSATION_RESOLVE_TIMEOUT_MS"),
        )
        assertTrue(
            "the switch effect must observe the latest selected conversation id",
            source.contains(
                "rememberUpdatedState(currentConversationId)",
            ),
        )
        assertTrue(
            "the switch effect must observe the latest durable conversation projection",
            source.contains(
                "rememberUpdatedState(currentConversation)",
            ),
        )
        assertTrue(
            "the switch effect must observe the latest Room message projection id",
            Regex(
                """rememberUpdatedState\(\s*loadedMessagesConversationId,?\s*\)""",
            ).containsMatchIn(source),
        )
        assertFalse(
            "projection latency must never be interpreted as a request to enter New Chat",
            source.contains("viewModel.createNewChat()"),
        )
    }

    @Test
    fun `scroll to bottom visibility remembers every captured plain value`() {
        val source = File(
            locateMainSourceRoot(),
            "com/lxseek/chat/ui/chat/ChatApp.kt",
        ).readText()
        val rememberStart = source.indexOf("val showButton by remember(")
        val derivedStart = source.indexOf("derivedStateOf", startIndex = rememberStart)
        assertTrue("scroll button derived state must exist", rememberStart >= 0 && derivedStart > 0)
        val rememberKeys = source.substring(rememberStart, derivedStart)

        listOf(
            "shareSelectionActive",
            "isNearAbsoluteBottom",
            "absoluteBottomScrollPhase",
        ).forEach { key ->
            assertTrue("scroll button must recreate its closure when $key changes", key in rememberKeys)
        }
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
