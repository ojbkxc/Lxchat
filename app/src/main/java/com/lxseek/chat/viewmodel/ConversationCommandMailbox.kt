package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ConversationCommand
import com.lxseek.chat.model.Transition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun interface ConversationCommandFactory {
    fun create(): ConversationCommand
}

/**
 * Sequential command mailbox for one conversation.
 *
 * A command factory is evaluated by the mailbox handler, not by the submitting coroutine. This
 * lets the state adapter allocate its next owner identity at the same serialized boundary where
 * the reducer consumes it. Cancellation of a Send claim is explicit: if the caller never receives
 * an accepted direct effect, [cancellationCommand] releases that exact Preparing identity. Stop,
 * tool-effect, and settlement callers use a conversation-owned, non-cancellable effect/result
 * handoff instead.
 */
internal class ConversationCommandMailbox(
    private val scope: CoroutineScope,
    private val handler: (ConversationCommandFactory) -> Transition,
) {
    private class Submission(
        val commandFactory: ConversationCommandFactory,
        val cancellationCommand: ((Transition) -> ConversationCommand?)?,
    ) {
        val response = CompletableDeferred<Transition>()
        val abandoned = AtomicBoolean(false)
        val transition = AtomicReference<Transition?>(null)
    }

    private val channel = Channel<Submission>(
        capacity = MAILBOX_CAPACITY,
        onUndeliveredElement = { submission ->
            submission.response.completeExceptionally(
                CancellationException("Conversation command mailbox was disposed"),
            )
        },
    )

    private val consumer = scope.launch {
        try {
            for (submission in channel) process(submission)
        } finally {
            channel.cancel(CancellationException("Conversation command mailbox stopped"))
        }
    }

    suspend fun submit(
        commandFactory: ConversationCommandFactory,
        cancellationCommand: ((Transition) -> ConversationCommand?)? = null,
    ): Transition {
        val submission = Submission(commandFactory, cancellationCommand)
        try {
            channel.send(submission)
            return submission.response.await()
        } catch (cancelled: CancellationException) {
            submission.abandoned.set(true)
            val transition = submission.transition.get()
            if (transition != null) {
                cancellationCommand?.invoke(transition)?.let(::submitWithoutReply)
            }
            throw cancelled
        }
    }

    private fun process(submission: Submission) {
        if (submission.abandoned.get()) {
            submission.response.completeExceptionally(
                CancellationException("Conversation command submission was cancelled"),
            )
            return
        }
        try {
            val transition = handler(submission.commandFactory)
            submission.transition.set(transition)
            if (submission.abandoned.get()) {
                submission.cancellationCommand?.invoke(transition)?.let { command ->
                    handler(ConversationCommandFactory { command })
                }
            }
            submission.response.complete(transition)
        } catch (error: Exception) {
            submission.response.completeExceptionally(error)
        }
    }

    private fun submitWithoutReply(command: ConversationCommand) {
        val submission = Submission(ConversationCommandFactory { command }, null)
        if (channel.trySend(submission).isSuccess) return
        // A bounded mailbox may be full. The conversation-owned scope, rather than the cancelled
        // submitter, guarantees eventual delivery of the identity-gated rollback. If the runtime
        // is being disposed, channel cancellation completes the submission exceptionally instead.
        scope.launch {
            try {
                channel.send(submission)
            } catch (error: Exception) {
                submission.response.completeExceptionally(error)
            }
        }
    }

    internal fun isRunning(): Boolean = consumer.isActive

    private companion object {
        const val MAILBOX_CAPACITY = 64
    }
}
