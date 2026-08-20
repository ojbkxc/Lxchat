package com.lxseek.chat.automation

import com.lxseek.chat.model.CompactOutcome
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.viewmodel.ConversationGenerationState
import com.lxseek.chat.viewmodel.QueuedSend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuntimeAdmissionTest {
    @Test
    fun idleConversation_entersTheExactNormalAcceptedInputContract() = runBlocking {
        val state = ConversationGenerationState("conversation")

        val decision = AutomationRuntimeAdmission.request(state, "run", "automation-send")

        val accepted = decision as AutomationRuntimeAdmission.Decision.Accepted
        assertSame(state, accepted.state)
        assertEquals("conversation", accepted.inputEffect.identity.conversationId)
        assertEquals("run", accepted.inputEffect.identity.runId)
        assertEquals("automation-send", accepted.inputEffect.identity.effectId)
        assertTrue(state.generating.value)
        assertTrue(state.inputPersisted(accepted.inputEffect.identity))
        assertEquals("run", state.currentRunId())
        assertTrue(
            finalizeBoundRun(state, accepted.inputEffect.identity.ownerToken, "run"),
        )
    }

    @Test
    fun activeConversation_returnsBusyWithoutChangingTheCurrentRun() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "active-run")

        val decision = AutomationRuntimeAdmission.request(state, "new-run", "automation-send")

        assertSame(AutomationRuntimeAdmission.Decision.Busy, decision)
        assertEquals("active-run", state.currentRunId())
        assertTrue(finalizeBoundRun(state, token, "active-run"))
    }

    @Test
    fun uninstalledAutomationClaim_canBeAbandonedByItsExactEffectIdentity() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val accepted = AutomationRuntimeAdmission.request(state, "run", "automation-send")
            as AutomationRuntimeAdmission.Decision.Accepted

        assertTrue(state.commands.abandonSendLaunch(accepted.inputEffect.identity))
        assertFalse(state.generating.value)
        assertFalse(state.inputPersisted(accepted.inputEffect.identity))
    }

    @Test
    fun pendingGuidance_cannotBeLeapfroggedByAnIdleAutomationSend() = runBlocking {
        val state = ConversationGenerationState("conversation")
        state.enqueueSend(
            QueuedSend(
                id = "guidance",
                text = "later",
                modelId = "OpenAI:model",
                attachments = emptyList(),
                runId = "origin-run",
            )
        )

        val decision = AutomationRuntimeAdmission.request(state, "new-run", "automation-send")

        assertSame(AutomationRuntimeAdmission.Decision.Busy, decision)
        assertFalse(state.generating.value)
        assertEquals(listOf("guidance"), state.queuedSends.value.map { it.id })
        state.dispose()
        Unit
    }

    @Test
    fun manualCompact_returnsBusyWithoutCreatingAnAutomationRun() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val compact = state.commands.requestManualCompact("compact-run", "compact-effect")!!

        val decision = AutomationRuntimeAdmission.request(state, "new-run", "automation-send")

        assertSame(AutomationRuntimeAdmission.Decision.Busy, decision)
        assertTrue(state.compacting.value)
        assertFalse(state.generating.value)
        assertTrue(state.commands.finishCompact(compact.identity, CompactOutcome.NOT_NEEDED).accepted)
    }

    private suspend fun finalizeBoundRun(
        state: ConversationGenerationState,
        ownerToken: Long,
        runId: String,
    ): Boolean {
        val identity = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = ownerToken,
            runId = runId,
            pass = 0,
            effectId = "finalize-$runId-0",
        )
        state.commands.requestRunFinalization(
            identity,
            RunStatus.COMPLETED,
            RunEndReason.MODEL_COMPLETED,
            markConversationUnread = true,
        )
        state.finishRunFinalization(identity, success = true)
        return state.endGeneration(ownerToken)
    }
}
