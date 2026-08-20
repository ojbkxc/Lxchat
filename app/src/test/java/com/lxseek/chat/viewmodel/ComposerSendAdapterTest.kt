package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.SelectedAttachment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerSendAdapterTest {
    @Test
    fun rejectedSendDoesNotClearOrAcknowledgeComposer() = runTest {
        val fixture = Fixture(this, acceptance = null)
        var acknowledged = false

        val result = fixture.adapter.sendMessage("text", onAccepted = { acknowledged = true })
        runCurrent()

        assertNull(result)
        assertFalse(acknowledged)
        coVerify(exactly = 0) { fixture.drafts.clearAccepted(any()) }
        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
    }

    @Test
    fun directAcceptanceClearsExactDraftBeforeUiAndReclaimsAfterward() = runTest {
        val attachment = SelectedAttachment(uri = "uri", type = "file")
        val acceptance = SendAcceptance.Direct("message", "accepted-conversation")
        val fixture = Fixture(this, acceptance, listOf(attachment))

        val result = fixture.adapter.sendMessage(
            text = "text",
            images = listOf("image"),
            attachments = listOf(attachment),
            onAccepted = { fixture.events += "ui" },
        )
        runCurrent()

        assertEquals(acceptance, result)
        assertEquals(
            listOf("send:text:image:uri", "clear:accepted-conversation", "ui", "reclaim"),
            fixture.events,
        )
        coVerify(exactly = 1) { fixture.drafts.reclaimAttachments(listOf(attachment)) }
    }

    @Test
    fun queuedAcceptanceClearsDraftButRetainsMemoryOwnedAttachments() = runTest {
        val attachment = SelectedAttachment(uri = "uri", type = "file")
        val acceptance = SendAcceptance.Queued("queued", "conversation")
        val fixture = Fixture(this, acceptance, listOf(attachment))

        val result = fixture.adapter.sendMessage("text", onAccepted = { fixture.events += "ui" })
        runCurrent()

        assertEquals(acceptance, result)
        assertEquals(listOf("send:text::", "clear:conversation", "ui"), fixture.events)
        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        private val acceptance: SendAcceptance?,
        attachmentsToReclaim: List<SelectedAttachment> = emptyList(),
    ) {
        val drafts = mockk<ComposerDraftController>()
        val events = mutableListOf<String>()
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val adapter = ComposerSendAdapter(
            send = { text, images, attachments, onAccepted ->
                events += "send:$text:${images.joinToString()}:${attachments.joinToString { it.uri }}"
                acceptance?.let { onAccepted(it) }
                acceptance
            },
            drafts = drafts,
            scope = testScope.backgroundScope,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )

        init {
            coEvery { drafts.clearAccepted(any()) } answers {
                events += "clear:${firstArg<String>()}"
                attachmentsToReclaim
            }
            coEvery { drafts.reclaimAttachments(any()) } answers {
                events += "reclaim"
            }
        }
    }
}
