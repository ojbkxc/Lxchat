package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.TaskExecutionEngine.BridgeOutcome
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAutomationBridgeControllerTest {
    @Test
    fun startAndCloseAreIdempotentAndUseTheSameOwner() {
        val fixture = Fixture()

        fixture.controller.start()
        fixture.controller.start()
        fixture.controller.close()
        fixture.controller.close()

        assertEquals(1, fixture.attachments.size)
        assertEquals(1, fixture.detachedOwners.size)
        assertSame(fixture.attachments.single().first, fixture.detachedOwners.single())
    }

    @Test
    fun hiddenConversationIsNotDelegated() = runTest {
        val fixture = Fixture(currentConversationId = "other")
        fixture.controller.start()

        val outcome = fixture.bridge()("conversation", "input", "model")

        assertEquals(BridgeOutcome.NotDelegated, outcome)
        assertTrue(fixture.sendInputs.isEmpty())
        assertTrue(fixture.loadedConversationIds.isEmpty())
    }

    @Test
    fun busySendReturnsBusyWithoutReadingRoom() = runTest {
        val fixture = Fixture(sendOutcome = AutomationSendOutcome.SlotBusy)
        fixture.controller.start()

        val outcome = fixture.bridge()("conversation", "input", "model")

        assertEquals(BridgeOutcome.Busy(), outcome)
        assertEquals(listOf(Triple("conversation", "input", "model")), fixture.sendInputs)
        assertTrue(fixture.loadedConversationIds.isEmpty())
    }

    @Test
    fun deliveredSendReturnsTheExactSuccessfulRow() = runTest {
        val fixture = Fixture(
            sendOutcome = AutomationSendOutcome.Delivered("expected"),
            messages = listOf(
                modelMessage("tail", "unrelated"),
                modelMessage("expected", "answer"),
            ),
        )
        fixture.controller.start()

        val outcome = fixture.bridge()("conversation", "input", "model")

        assertEquals(BridgeOutcome.Completed("expected", "answer"), outcome)
        assertEquals(listOf("conversation"), fixture.loadedConversationIds)
    }

    @Test
    fun missingDeliveredRowReturnsFailure() = runTest {
        val fixture = Fixture(
            sendOutcome = AutomationSendOutcome.Delivered("missing"),
            messages = listOf(modelMessage("other", "answer")),
        )
        fixture.controller.start()

        val outcome = fixture.bridge()("conversation", "input", "model")

        assertEquals(BridgeOutcome.Failed("Generation row disappeared"), outcome)
    }

    @Test
    fun nonSuccessfulDeliveredRowReturnsItsErrorOrFallback() = runTest {
        val fixture = Fixture(
            sendOutcome = AutomationSendOutcome.Delivered("failed"),
            messages = listOf(modelMessage("failed", "provider error", MessageStatus.ERROR)),
        )
        fixture.controller.start()

        val explicit = fixture.bridge()("conversation", "input", "model")
        fixture.messages = listOf(modelMessage("failed", "", MessageStatus.STOPPED))
        val fallback = fixture.bridge()("conversation", "input", "model")

        assertEquals(BridgeOutcome.Failed("provider error"), explicit)
        assertEquals(BridgeOutcome.Failed("Generation failed"), fallback)
    }

    private class Fixture(
        currentConversationId: String? = "conversation",
        var sendOutcome: AutomationSendOutcome = AutomationSendOutcome.SlotBusy,
        var messages: List<MessageEntity> = emptyList(),
    ) {
        val attachments = mutableListOf<Pair<Any, ForegroundSendBridge>>()
        val detachedOwners = mutableListOf<Any>()
        val sendInputs = mutableListOf<Triple<String, String, String>>()
        val loadedConversationIds = mutableListOf<String>()
        val controller = ForegroundAutomationBridgeController(
            currentConversationId = MutableStateFlow(currentConversationId),
            send = { conversationId, text, modelId ->
                sendInputs += Triple(conversationId, text, modelId)
                sendOutcome
            },
            loadMessages = { conversationId ->
                loadedConversationIds += conversationId
                messages
            },
            attach = { owner, bridge -> attachments += owner to bridge },
            detach = detachedOwners::add,
        )

        fun bridge(): ForegroundSendBridge = attachments.single().second
    }

    private companion object {
        fun modelMessage(
            id: String,
            text: String,
            status: MessageStatus = MessageStatus.SUCCESS,
        ) = MessageEntity(
            id = id,
            conversationId = "conversation",
            text = text,
            status = status,
            participant = Participant.MODEL,
            timestamp = 1L,
            runId = "run",
        )
    }
}
