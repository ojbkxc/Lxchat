package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationRenderStoreTest {
    @Test
    fun commitGraph_publishesOneSelfConsistentEditSnapshot() {
        val oldUser = message("old-user", null, Participant.USER)
        val oldModel = message("old-model", oldUser.id, Participant.MODEL)
        val newUser = message("new-user", null, Participant.USER)
        val placeholder = message(
            "new-model",
            newUser.id,
            Participant.MODEL,
            MessageStatus.SENDING,
        )
        val store = ConversationRenderStore()
        store.replaceConversation(
            allMessages = listOf(oldUser, oldModel),
            selectedChildren = mapOf(null to oldUser.id, oldUser.id to oldModel.id),
        )

        store.commitGraph(
            committedMessages = listOf(newUser, placeholder),
            selectedChildren = mapOf(null to newUser.id, newUser.id to placeholder.id),
            streamingMessage = placeholder,
        )

        val snapshot = store.snapshot.value
        assertSame(placeholder, snapshot.streamingMessage)
        assertEquals(newUser.id, snapshot.selectedChildren[null])
        assertEquals(
            listOf(newUser.id, placeholder.id),
            ConversationUiState.resolvePath(
                snapshot.allMessages,
                snapshot.streamingMessage,
                snapshot.selectedChildren,
            ).map { it.id },
        )
    }

    @Test
    fun terminalStreamingHandoff_neverExposesTheOlderRoomCheckpoint() {
        val user = message("user", null, Participant.USER)
        val staleCheckpoint = message(
            id = "model",
            parentId = user.id,
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            text = "partial",
        )
        val stopped = message(
            id = staleCheckpoint.id,
            parentId = user.id,
            participant = Participant.MODEL,
            status = MessageStatus.STOPPED,
            text = "partial response with the latest tokens",
        )
        val store = ConversationRenderStore()
        store.replaceConversation(
            allMessages = listOf(user, staleCheckpoint),
            selectedChildren = mapOf(null to user.id, user.id to stopped.id),
            streamingMessage = stopped,
        )

        store.commitTerminalStreamingMessage(stopped)

        val handedOff = store.snapshot.value
        assertNull(handedOff.streamingMessage)
        assertSame(stopped, handedOff.allMessages.single { it.id == stopped.id })
        assertEquals(
            stopped.text,
            ConversationUiState.resolvePath(
                handedOff.allMessages,
                handedOff.streamingMessage,
                handedOff.selectedChildren,
            ).last().text,
        )

        // A combine emission queued before the handoff cannot resurrect the retired overlay.
        store.setStreamingMessage(stopped)
        assertNull(store.snapshot.value.streamingMessage)

        // A different generation remains free to install its own overlay.
        val next = message(
            id = "next-model",
            parentId = stopped.id,
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
        )
        store.setStreamingMessage(next)
        assertSame(next, store.snapshot.value.streamingMessage)
    }

    @Test
    fun successfulTerminalHandoff_cannotRegressBackToAnswering() {
        val user = message("user", null, Participant.USER)
        val sending = message(
            id = "model",
            parentId = user.id,
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            text = "partial",
        )
        val success = sending.copy(
            status = MessageStatus.SUCCESS,
            text = "complete answer",
        )
        val store = ConversationRenderStore()
        store.replaceConversation(
            allMessages = listOf(user, sending),
            selectedChildren = mapOf(null to user.id, user.id to sending.id),
            streamingMessage = success,
        )

        store.commitTerminalStreamingMessage(success)
        store.setAllMessages(listOf(user, sending))

        val fenced = store.snapshot.value
        assertNull(fenced.streamingMessage)
        assertSame(success, fenced.allMessages.single { it.id == success.id })
        assertEquals(MessageStatus.SUCCESS, fenced.allMessages.last().status)

        // The terminal Room invalidation is accepted normally.
        store.setAllMessages(listOf(user, success))
        assertSame(success, store.snapshot.value.allMessages.last())

        // Even a mapped checkpoint queued before that invalidation remains monotonic.
        store.setAllMessages(listOf(user, sending))
        assertSame(success, store.snapshot.value.allMessages.last())

        // A later real deletion remains authoritative and cannot leave a ghost terminal row.
        store.setAllMessages(listOf(user))
        assertEquals(listOf(user.id), store.snapshot.value.allMessages.map { it.id })
    }

    @Test
    fun acceptedSendFence_withholdsRoomProjectionUntilComposerAcknowledgementCommit() {
        val oldUser = message("old-user", null, Participant.USER)
        val oldModel = message("old-model", oldUser.id, Participant.MODEL)
        val newUser = message("new-user", oldModel.id, Participant.USER)
        val placeholder = message(
            "new-model",
            newUser.id,
            Participant.MODEL,
            MessageStatus.SENDING,
        )
        val oldSelections = mapOf(null to oldUser.id, oldUser.id to oldModel.id)
        val newSelections = oldSelections + mapOf(
            oldModel.id to newUser.id,
            newUser.id to placeholder.id,
        )
        val store = ConversationRenderStore()
        store.replaceConversation(
            allMessages = listOf(oldUser, oldModel),
            selectedChildren = oldSelections,
        )

        val fence = store.beginRoomMessageProjectionFence()
        store.setAllMessages(listOf(oldUser, oldModel, newUser, placeholder))

        // Room has committed, but the composer has not acknowledged success yet.
        assertEquals(listOf(oldUser.id, oldModel.id), store.allMessages.map { it.id })

        store.commitGraph(
            committedMessages = listOf(newUser, placeholder),
            selectedChildren = newSelections,
            streamingMessage = placeholder,
            roomProjectionFence = fence,
        )

        val published = store.snapshot.value
        assertEquals(
            listOf(oldUser.id, oldModel.id, newUser.id, placeholder.id),
            published.allMessages.map { it.id },
        )
        assertSame(placeholder, published.streamingMessage)
        assertEquals(newUser.id, published.selectedChildren[oldModel.id])
    }

    @Test
    fun failedSendFence_releasesDeferredRoomProgressWithoutGraphCommit() {
        val original = message("original", null, Participant.USER)
        val checkpoint = original.copy(text = "new checkpoint")
        val store = ConversationRenderStore()
        store.replaceConversation(
            allMessages = listOf(original),
            selectedChildren = mapOf(null to original.id),
        )

        val fence = store.beginRoomMessageProjectionFence()
        store.setAllMessages(listOf(checkpoint))
        assertSame(original, store.allMessages.single())

        store.releaseRoomMessageProjectionFence(fence)

        assertSame(checkpoint, store.allMessages.single())
    }

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        status: MessageStatus = MessageStatus.SUCCESS,
        text: String = "",
    ) = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        participant = participant,
        status = status,
        timestamp = id.hashCode().toLong(),
        runId = "run-$id",
        runSequence = 0,
    )
}
