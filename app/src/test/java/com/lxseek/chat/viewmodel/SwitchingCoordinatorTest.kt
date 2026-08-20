package com.lxseek.chat.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchingCoordinatorTest {

    @Test
    fun sameConversation_stillCreatesANewOwnedTransition() {
        val coordinator = SwitchingCoordinator()

        val first = coordinator.beginConversation("conversation")
        assertTrue(coordinator.complete(first.id))

        val second = coordinator.beginConversation("conversation")

        assertNotEquals(first.id, second.id)
        assertFalse(second.readyForUi)
        assertTrue(coordinator.isSwitching.value)
        assertTrue(coordinator.markConversationReady(second.id)?.readyForUi == true)
        assertTrue(coordinator.complete(second.id))
        assertFalse(coordinator.isSwitching.value)
    }

    @Test
    fun conversationCompletionHaptic_isScopedToItsOwnedRequest() {
        val coordinator = SwitchingCoordinator()

        val silentReturn = coordinator.beginConversation(
            conversationId = "task-origin",
            hapticOnCompletion = false,
        )
        assertFalse(silentReturn.hapticOnCompletion)

        val manualSelection = coordinator.beginConversation("manual")
        assertTrue(manualSelection.hapticOnCompletion)
        assertFalse(coordinator.complete(silentReturn.id))
        assertTrue(coordinator.request.value?.hapticOnCompletion == true)
    }

    @Test
    fun staleCompletion_cannotUncoverNewerTransition() {
        val coordinator = SwitchingCoordinator()
        val old = coordinator.beginConversation("old")
        val current = coordinator.beginConversation("current")

        assertFalse(coordinator.complete(old.id))
        assertTrue(coordinator.isSwitching.value)
        assertTrue(coordinator.isCurrent(current.id))

        assertTrue(coordinator.complete(current.id))
        assertFalse(coordinator.isSwitching.value)
        assertNull(coordinator.request.value)
    }

    @Test
    fun treeMutation_isNotUiReadyUntilDurableMutationChoosesTarget() {
        val coordinator = SwitchingCoordinator()
        val request = coordinator.beginTreeMutation("conversation")

        assertFalse(request.readyForUi)
        assertNull(request.targetMessageId)

        val ready = coordinator.markTreeMutationReady(request.id, "message")

        assertTrue(ready?.readyForUi == true)
        assertTrue(ready?.targetMessageId == "message")
        assertTrue(coordinator.complete(request.id))
    }

    @Test
    fun supersededTreeMutation_cannotPublishTargetIntoConversationSwitch() {
        val coordinator = SwitchingCoordinator()
        val mutation = coordinator.beginTreeMutation("old")
        val conversation = coordinator.beginConversation("new")

        assertNull(coordinator.markTreeMutationReady(mutation.id, "stale-message"))
        assertTrue(coordinator.isCurrent(conversation.id))
        assertTrue(coordinator.request.value?.targetMessageId == null)
    }

    @Test
    fun staleConversationJob_cannotPublishReadinessIntoNewerRequest() {
        val coordinator = SwitchingCoordinator()
        val old = coordinator.beginConversation("old")
        val current = coordinator.beginConversation("current")

        assertNull(coordinator.markConversationReady(old.id))
        assertFalse(coordinator.request.value?.readyForUi ?: true)
        assertTrue(coordinator.markConversationReady(current.id)?.readyForUi == true)
    }

    @Test
    fun newChatCompletion_isOwnerGated() {
        val coordinator = SwitchingCoordinator()
        val newChat = coordinator.beginNewChat()
        val conversation = coordinator.beginConversation("conversation")

        assertFalse(coordinator.complete(newChat.id))
        assertTrue(coordinator.isSwitching.value)
        assertTrue(coordinator.complete(conversation.id))
    }
}
