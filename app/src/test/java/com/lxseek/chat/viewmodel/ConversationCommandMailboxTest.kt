package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ConversationCommand
import com.lxseek.chat.model.ConversationRuntimeReducer
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunState
import com.lxseek.chat.model.RuntimeRunIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConversationCommandMailboxTest {
    @Test
    fun `commands are reduced in submission order`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var state: RunState = RunState.Idle(CONVERSATION_ID)
        val commands = mutableListOf<String>()
        val mailbox = ConversationCommandMailbox(scope) { factory ->
            val command = factory.create()
            commands += command::class.simpleName.orEmpty()
            ConversationRuntimeReducer.reduce(state, command).also { state = it.newState }
        }

        val identity = RuntimeRunIdentity(CONVERSATION_ID, ownerToken = 1)
        mailbox.submit(ConversationCommandFactory { ConversationCommand.AcquireSlot(identity) })
        mailbox.submit(ConversationCommandFactory { ConversationCommand.CoroutineSettled(identity) })

        assertEquals(listOf("AcquireSlot", "CoroutineSettled"), commands)
        assertEquals(RunState.Idle(CONVERSATION_ID), state)
        scope.cancel()
    }

    @Test
    fun `cancelled direct claim emits exact abandonment command`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val lock = Any()
        var blockFirst = true
        var state: RunState = RunState.Idle(CONVERSATION_ID)
        val commands = mutableListOf<ConversationCommand>()
        val mailbox = ConversationCommandMailbox(scope) { factory ->
            if (blockFirst) {
                blockFirst = false
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
            }
            synchronized(lock) {
                val command = factory.create()
                commands += command
                ConversationRuntimeReducer.reduce(state, command).also { state = it.newState }
            }
        }
        val effectIdentity = RunEffectIdentity(
            conversationId = CONVERSATION_ID,
            ownerToken = 1,
            runId = "run",
            pass = 0,
            effectId = "send",
        )

        val submission = async(Dispatchers.Default) {
            mailbox.submit(
                commandFactory = ConversationCommandFactory {
                    ConversationCommand.SendRequested(
                        identity = effectIdentity,
                        directOnly = false,
                        hasPendingGuidance = false,
                    )
                },
                cancellationCommand = { transition ->
                    transition.effects.filterIsInstance<RunEffect.PersistAcceptedInput>()
                        .singleOrNull()
                        ?.let { ConversationCommand.SendLaunchAbandoned(it.identity) }
                },
            )
        }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        submission.cancelAndJoin()
        proceed.countDown()

        withTimeout(5_000) {
            while (synchronized(lock) { commands.size < 2 }) delay(10)
        }
        assertEquals(
            listOf("SendRequested", "SendLaunchAbandoned"),
            synchronized(lock) { commands.map { it::class.simpleName } },
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), synchronized(lock) { state })
        scope.cancel()
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
