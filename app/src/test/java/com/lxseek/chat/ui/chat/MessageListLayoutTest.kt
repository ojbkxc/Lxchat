package com.lxseek.chat.ui.chat

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.ui.chat.message.assistantActionAvailability
import com.lxseek.chat.ui.chat.message.assistantActionsVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListLayoutTest {
    @Test
    fun lifecycleRegistryMarksOnlyActuallyComposedMessages() {
        val registry = MessageLifecycleAppearanceRegistry()

        assertFalse(registry.isKnown("projected-but-not-composed"))
        registry.markKnown("composed")

        assertTrue(registry.isKnown("composed"))
        assertFalse(registry.isKnown("projected-but-not-composed"))
    }

    @Test
    fun attachmentDraftMutationsBypassTheTextDebounce() {
        val attachment = SelectedAttachment(uri = "file:///draft", type = "file")

        assertEquals(
            0L,
            composerDraftWriteDelayMillis(
                previousAttachments = emptyList(),
                nextAttachments = listOf(attachment),
                hasPendingRemovals = false,
            ),
        )
        assertEquals(
            0L,
            composerDraftWriteDelayMillis(
                previousAttachments = listOf(attachment),
                nextAttachments = emptyList(),
                hasPendingRemovals = true,
            ),
        )
    }

    @Test
    fun textOnlyDraftMutationsRemainCoalesced() {
        val attachment = SelectedAttachment(uri = "file:///draft", type = "file")

        assertEquals(
            300L,
            composerDraftWriteDelayMillis(
                previousAttachments = listOf(attachment),
                nextAttachments = listOf(attachment),
                hasPendingRemovals = false,
            ),
        )
    }

    @Test
    fun appendingUserKeepsPreviousAssistantInTheSameTurn() {
        val user1 = message("user-1", Participant.USER)
        val assistant1 = message("assistant-1", Participant.MODEL)
        val user2 = message("user-2", Participant.USER)

        val beforeSend = buildMessageListTurns(listOf(user1, assistant1))
        val afterSend = buildMessageListTurns(listOf(user1, assistant1, user2))

        assertEquals(beforeSend.single(), afterSend.first())
        assertEquals("user-1", afterSend.first().key)
        assertEquals(listOf("user-1", "assistant-1"), afterSend.first().messages.map { it.id })
        assertEquals(listOf("user-2"), afterSend.last().messages.map { it.id })
    }

    @Test
    fun turnCache_reusesHistoryAndReplacesOnlyTheStreamingTurn() {
        val cache = MessageListTurnCache()
        val user1 = message("user-1", Participant.USER)
        val assistant1 = message("assistant-1", Participant.MODEL)
        val user2 = message("user-2", Participant.USER)
        val firstStream = message("assistant-2", Participant.MODEL).copy(text = "a")
        val before = cache.update(listOf(user1, assistant1, user2, firstStream))

        val nextStream = firstStream.copy(text = "ab")
        val after = cache.update(listOf(user1, assistant1, user2, nextStream))

        assertSame(before.first(), after.first())
        assertNotSame(before.last(), after.last())
        assertEquals("ab", after.last().messages.last().text)
    }

    @Test
    fun everyMessageInATurnMapsToTheSameLazyItemIndex() {
        val turns = buildMessageListTurns(
            listOf(
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
                message("error-1", Participant.ERROR),
                message("user-2", Participant.USER),
                message("assistant-2", Participant.MODEL),
            ),
        )

        assertEquals(0, messageListTurnIndex(turns, "user-1"))
        assertEquals(0, messageListTurnIndex(turns, "assistant-1"))
        assertEquals(0, messageListTurnIndex(turns, "error-1"))
        assertEquals(1, messageListTurnIndex(turns, "user-2"))
        assertEquals(1, messageListTurnIndex(turns, "assistant-2"))
        assertEquals(-1, messageListTurnIndex(turns, "missing"))
    }

    @Test
    fun turnHeightEstimateSumsChildrenForForcedAnimatedScroll() {
        val turn = buildMessageListTurns(
            listOf(
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
                message("error-1", Participant.ERROR),
            ),
        ).single()

        assertEquals(
            372f,
            estimateMessageListTurnHeightPx(
                turn = turn,
                messageHeights = mapOf("user-1" to 120, "assistant-1" to 180),
                fallbackHeightPx = 72f,
            ),
            0f,
        )
    }

    @Test
    fun leadingNonUserMessagesRemainStableSingletonItems() {
        val turns = buildMessageListTurns(
            listOf(
                message("error-1", Participant.ERROR),
                message("assistant-0", Participant.MODEL),
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
            ),
        )

        assertEquals(
            listOf(
                listOf("error-1"),
                listOf("assistant-0"),
                listOf("user-1", "assistant-1"),
            ),
            turns.map { turn -> turn.messages.map { it.id } },
        )
    }

    @Test
    fun shortTailUsesTheAvailableViewportAsItsMinimumHeight() {
        val viewport = 1_000
        val top = 140
        val bottom = 180
        val content = 260

        val minimum = calculateTailMinHeightPx(viewport, top, bottom)
        val layoutHeight = calculateTailLayoutHeightPx(minimum, content)

        assertEquals(680, minimum)
        assertEquals(680, layoutHeight)
        assertEquals(viewport - top, layoutHeight + bottom)
    }

    @Test
    fun componentGrowthAndShrinkBelowMinimumNeverChangeTailGeometry() {
        val beforeContent = 500
        val afterContent = 220
        val minimum = calculateTailMinHeightPx(1_000, 140, 180)
        val beforeHeight = calculateTailLayoutHeightPx(minimum, beforeContent)
        val afterHeight = calculateTailLayoutHeightPx(minimum, afterContent)

        assertEquals(minimum, beforeHeight)
        assertEquals(minimum, afterHeight)
    }

    @Test
    fun bottomBarGrowthReducesTheTailMinimumDirectly() {
        val beforeBottom = 120
        val afterBottom = 260
        val beforeMinimum = calculateTailMinHeightPx(1_000, 140, beforeBottom)
        val afterMinimum = calculateTailMinHeightPx(1_000, 140, afterBottom)

        assertEquals(140, beforeMinimum - afterMinimum)
    }

    @Test
    fun longTailGrowsNaturallyPastTheMinimum() {
        val minimum = calculateTailMinHeightPx(1_000, 140, 180)

        assertEquals(
            2_000,
            calculateTailLayoutHeightPx(minimum, contentHeightPx = 2_000),
        )
    }

    @Test
    fun embeddedTailAnchorLeavesBlankSpaceAfterCurrentContent() {
        val viewport = 1_000
        val top = 140
        val composer = 180

        val messageTail = calculateTailMinHeightPx(
            viewportHeightPx = viewport,
            targetTopPx = top,
            bottomObstructionPx = composer,
        )

        assertEquals(viewport - top, messageTail + composer)
    }

    @Test
    fun absoluteBottomStateRetargetsWhenStreamingExtentChanges() {
        var phase = reduceAbsoluteBottomScroll(
            AbsoluteBottomScrollPhase.IDLE,
            AbsoluteBottomScrollEvent.Requested,
        )
        assertEquals(AbsoluteBottomScrollPhase.SEEKING, phase)

        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.TargetAvailable,
        )
        assertEquals(AbsoluteBottomScrollPhase.FOLLOWING, phase)

        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.BottomReached,
        )
        assertEquals(AbsoluteBottomScrollPhase.SETTLING, phase)

        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.ExtentChanged,
        )
        assertEquals(AbsoluteBottomScrollPhase.FOLLOWING, phase)

        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.BottomReached,
        )
        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.Finished,
        )
        assertEquals(AbsoluteBottomScrollPhase.IDLE, phase)
    }

    @Test
    fun userCancellationReleasesEveryAbsoluteBottomPhase() {
        AbsoluteBottomScrollPhase.entries
            .filter { phase -> phase.isActive }
            .forEach { phase ->
                assertEquals(
                    AbsoluteBottomScrollPhase.IDLE,
                    reduceAbsoluteBottomScroll(
                        phase,
                        AbsoluteBottomScrollEvent.Cancelled,
                    ),
                )
        }
    }

    @Test
    fun imeRiseAnchorsOnlyAPreviouslyBottomAlignedViewport() {
        var state = ImeBottomAnchorState(
            observedInsetPx = 0,
            bottomEligibleBeforeInsetChange = false,
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 0,
                bottomEligibleNow = true,
            ),
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 120,
                bottomEligibleNow = false,
            ),
        )
        assertTrue(state.active)

        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.CorrectionSettled,
        )
        assertFalse(state.active)
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 240,
                bottomEligibleNow = false,
            ),
        )
        assertTrue(state.active)

        val detached = reduceImeBottomAnchor(
            ImeBottomAnchorState(
                observedInsetPx = 0,
                bottomEligibleBeforeInsetChange = false,
            ),
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 240,
                bottomEligibleNow = false,
            ),
        )
        assertFalse(detached.active)
    }

    @Test
    fun imeRiseCannotAnchorWithoutComposerFocusAuthorization() {
        var state = ImeBottomAnchorState(
            observedInsetPx = 0,
            bottomEligibleBeforeInsetChange = true,
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 120,
                bottomEligibleNow = true,
                anchorAllowed = false,
            ),
        )

        assertEquals(120, state.observedInsetPx)
        assertFalse(state.bottomEligibleBeforeInsetChange)
        assertFalse(state.active)

        state = reduceImeBottomAnchor(
            state.copy(active = true),
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 160,
                bottomEligibleNow = true,
                anchorAllowed = false,
            ),
        )
        assertFalse(state.active)
    }

    @Test
    fun userDragSuppressesImeReattachmentUntilTheInsetFalls() {
        var state = ImeBottomAnchorState(
            observedInsetPx = 80,
            bottomEligibleBeforeInsetChange = true,
            active = true,
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.UserDragStarted,
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 160,
                bottomEligibleNow = true,
            ),
        )
        assertFalse(state.active)
        assertTrue(state.suppressedUntilInsetFalls)

        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 0,
                bottomEligibleNow = false,
            ),
        )
        assertFalse(state.active)
        assertFalse(state.suppressedUntilInsetFalls)
    }

    @Test
    fun userDragWhileImeIsClosedCanRearmAtTheBottomThreshold() {
        var state = ImeBottomAnchorState(
            observedInsetPx = 0,
            bottomEligibleBeforeInsetChange = true,
        )

        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.UserDragStarted,
        )
        assertFalse(state.active)
        assertFalse(state.suppressedUntilInsetFalls)

        // Once the drag settles inside the threshold, the normal proximity observation arms
        // anchoring without requiring an explicit scroll-to-bottom button click.
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 0,
                bottomEligibleNow = true,
            ),
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 120,
                bottomEligibleNow = false,
            ),
        )

        assertTrue(state.active)
    }

    @Test
    fun absoluteBottomDistanceIncludesAfterContentPadding() {
        val snapshot = AbsoluteBottomLayoutSnapshot(
            totalItemsCount = 4,
            canScrollForward = true,
            viewportStartOffsetPx = -8,
            viewportEndOffsetPx = 1_000,
            afterContentPaddingPx = 24,
            sentinelOffsetPx = 1_040,
            sentinelSizePx = 1,
        )

        assertEquals(65f, snapshot.remainingDistancePx ?: -1f, 0f)
    }

    @Test
    fun absoluteBottomThresholdUsesTheFinalPreSentinelGap() {
        val snapshot = AbsoluteBottomLayoutSnapshot(
            totalItemsCount = 4,
            canScrollForward = true,
            viewportStartOffsetPx = 0,
            viewportEndOffsetPx = 1_000,
            afterContentPaddingPx = 24,
            sentinelOffsetPx = null,
            sentinelSizePx = null,
            lastVisibleIndex = 2,
            lastVisibleEndOffsetPx = 1_030,
        )
        val remaining = snapshot.estimatedRemainingDistancePx(3f)

        assertEquals(57f, remaining ?: -1f, 0f)
        assertTrue(
            isWithinAbsoluteBottomAttachThreshold(
                snapshot = snapshot,
                remainingDistancePx = remaining,
                thresholdPx = 64f,
            ),
        )
    }

    @Test
    fun coarseAbsoluteBottomEstimateTargetsThePhysicalEnd() {
        assertEquals(
            421f,
            estimateAbsoluteBottomDistancePx(
                lastVisibleIndex = 2,
                lastVisibleEndOffsetPx = 900,
                viewportEndOffsetPx = 1_000,
                afterContentPaddingPx = 20,
                totalItemsCount = 6,
                estimatedItemSizePx = { index ->
                    when (index) {
                        3 -> 200f
                        4 -> 300f
                        else -> 1f
                    }
                },
            ),
            0f,
        )
    }

    @Test
    fun scrollToBottomButtonUsesRealScrollableExtentAndLocksDuringSeek() {
        assertTrue(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.SEEKING,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = false,
                isNearBottom = true,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = true,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = true,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = false,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
                competingProgrammaticScrollActive = true,
            ),
        )
    }

    @Test
    fun scrollToBottomProximityUsesHysteresis() {
        var nearBottom = reduceAbsoluteBottomProximity(
            wasNearBottom = false,
            canScrollForward = true,
            remainingDistancePx = 63f,
            hideThresholdPx = 64f,
            showThresholdPx = 96f,
        )
        assertTrue(nearBottom)

        nearBottom = reduceAbsoluteBottomProximity(
            wasNearBottom = nearBottom,
            canScrollForward = true,
            remainingDistancePx = 80f,
            hideThresholdPx = 64f,
            showThresholdPx = 96f,
        )
        assertTrue(nearBottom)

        nearBottom = reduceAbsoluteBottomProximity(
            wasNearBottom = nearBottom,
            canScrollForward = true,
            remainingDistancePx = 97f,
            hideThresholdPx = 64f,
            showThresholdPx = 96f,
        )
        assertFalse(nearBottom)
    }

    @Test
    fun scrollStateMachineOnlyCorrectsStableVisibleLayouts() {
        assertEquals(
            MessageListLayoutMode.STABLE,
            messageListLayoutMode(isSwitching = false, isScrollInProgress = false),
        )
        assertEquals(
            MessageListLayoutMode.ACTIVE_SCROLL,
            messageListLayoutMode(isSwitching = false, isScrollInProgress = true),
        )
        assertEquals(
            MessageListLayoutMode.COVERED_TRANSITION,
            messageListLayoutMode(isSwitching = true, isScrollInProgress = false),
        )
    }

    @Test
    fun reversingMutationKeepsTheOriginalPreChangeAnchor() {
        val lock = MessageListMutationAnchorLock()
        val original = MessageListViewportAnchor("message-a", 37)

        assertEquals(original, lock.begin("thinking-card", original))
        assertEquals(original, lock.begin(
            "thinking-card",
            MessageListViewportAnchor("already-shifted", 91),
        ))

        assertEquals(1, lock.activeMutationCount)
        assertEquals(original, lock.anchor)
        assertEquals(original, lock.finish("thinking-card"))
        assertNull(lock.anchor)
    }

    @Test
    fun overlappingMutationsReleaseOnlyAfterTheLastAnimationSettles() {
        val lock = MessageListMutationAnchorLock()
        val original = MessageListViewportAnchor("message-a", 12)

        lock.begin("card-a", original)
        lock.begin("card-b", MessageListViewportAnchor("message-b", 99))

        assertNull(lock.finish("card-a"))
        assertEquals(original, lock.anchor)
        assertEquals(original, lock.finish("card-b"))
    }

    @Test
    fun userScrollCancelsPendingMutationCorrection() {
        val lock = MessageListMutationAnchorLock()
        lock.begin(
            "thinking-card",
            MessageListViewportAnchor("message-a", 12),
        )

        lock.cancel()

        assertEquals(0, lock.activeMutationCount)
        assertNull(lock.anchor)
        assertNull(lock.finish("thinking-card"))
    }

    @Test
    fun appendOnlyTextCanBeCoalescedDuringActiveScroll() {
        val before = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
            text = "a",
            segments = listOf(MessageSegment(type = "answer", content = "a")),
        )
        val after = before.copy(
            text = "append-only",
            segments = listOf(MessageSegment(type = "answer", content = "append-only")),
        )

        assertEquals(
            true,
            sameStreamingRenderStructure(listOf(before), listOf(after)),
        )
    }

    @Test
    fun newToolSegmentCannotBeDeferredDuringActiveScroll() {
        val before = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.THINKING,
            segments = listOf(MessageSegment(type = "thought", content = "reasoning")),
        )
        val after = before.copy(
            status = MessageStatus.TOOL_CALLING,
            segments = checkNotNull(before.segments) + MessageSegment(
                type = "tool",
                toolName = "arbitrary_tool",
                toolCallId = "call",
            ),
        )

        assertEquals(
            false,
            sameStreamingRenderStructure(listOf(before), listOf(after)),
        )
    }

    @Test
    fun terminalStateCannotBeDeferredDuringActiveScroll() {
        val before = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
            text = "complete",
        )

        assertEquals(
            false,
            sameStreamingRenderStructure(
                listOf(before),
                listOf(before.copy(status = MessageStatus.SUCCESS)),
            ),
        )
    }

    @Test
    fun lifecycleEntrancePlaysOnlyForNewActiveChainNodes() {
        val user = message("user", Participant.USER)
        val sending = message("assistant", Participant.MODEL).copy(
            parentId = user.id,
            status = MessageStatus.SENDING,
        )

        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = user,
                isKnown = false,
                isLoading = true,
                isStreaming = false,
                lastUserMessageId = user.id,
                requestedTargetMessageId = user.id,
            )
        )
        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = sending,
                isKnown = false,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = user.id,
                requestedTargetMessageId = user.id,
            )
        )
        assertFalse(
            shouldAnimateMessageLifecycleEntrance(
                message = sending,
                isKnown = true,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = user.id,
                requestedTargetMessageId = user.id,
            )
        )
        assertFalse(
            shouldAnimateMessageLifecycleEntrance(
                message = message("historical", Participant.MODEL),
                isKnown = false,
                isLoading = false,
                isStreaming = false,
                lastUserMessageId = user.id,
                requestedTargetMessageId = null,
            )
        )
    }

    @Test
    fun fastTerminalReplyCanStillUseTheSendTargetEntrance() {
        val completed = message("assistant", Participant.MODEL).copy(
            parentId = "user",
            status = MessageStatus.SUCCESS,
        )

        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = completed,
                isKnown = false,
                isLoading = false,
                isStreaming = false,
                lastUserMessageId = "user",
                requestedTargetMessageId = "user",
            )
        )
    }

    @Test
    fun acceptedSendTargetAnimatesBeforeLoadingStateIsObserved() {
        val user = message("user", Participant.USER)

        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = user,
                isKnown = false,
                isLoading = false,
                isStreaming = false,
                lastUserMessageId = user.id,
                requestedTargetMessageId = user.id,
            ),
        )
    }

    @Test
    fun protocolRowsNeverReceiveMessageEntranceAnimations() {
        val tool = message("tool_call", Participant.MODEL).copy(
            status = MessageStatus.TOOL_CALLING,
        )

        assertFalse(
            shouldAnimateMessageLifecycleEntrance(
                message = tool,
                isKnown = false,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = "user",
                requestedTargetMessageId = "user",
            )
        )
    }

    @Test
    fun assistantActionRowFadesForStreamingAndRegenerateOnly() {
        assertFalse(
            assistantActionsVisible(
                isStreaming = true,
                regenerateRequested = false,
            )
        )
        assertTrue(
            assistantActionsVisible(
                isStreaming = false,
                regenerateRequested = false,
            )
        )
        assertFalse(
            assistantActionsVisible(
                isStreaming = false,
                regenerateRequested = true,
            )
        )
    }

    @Test
    fun currentStreamingActionsHideWhileCompletedMessageInfoStaysEnabled() {
        val currentStreaming = assistantActionAvailability(
            isStreaming = true,
            isLoading = true,
        )
        assertFalse(currentStreaming.informationVisible)
        assertFalse(currentStreaming.informationEnabled)
        assertFalse(currentStreaming.terminalVisible)
        assertFalse(currentStreaming.terminalEnabled)

        val previousCompletedDuringGeneration = assistantActionAvailability(
            isStreaming = false,
            isLoading = true,
        )
        assertTrue(previousCompletedDuringGeneration.informationVisible)
        assertTrue(previousCompletedDuringGeneration.informationEnabled)
        assertTrue(previousCompletedDuringGeneration.terminalVisible)
        assertFalse(previousCompletedDuringGeneration.terminalEnabled)

        val completeAndIdle = assistantActionAvailability(
            isStreaming = false,
            isLoading = false,
        )
        assertTrue(completeAndIdle.informationVisible)
        assertTrue(completeAndIdle.informationEnabled)
        assertTrue(completeAndIdle.terminalVisible)
        assertTrue(completeAndIdle.terminalEnabled)

        val regenerating = assistantActionAvailability(
            isStreaming = false,
            isLoading = true,
            regenerateRequested = true,
        )
        assertFalse(regenerating.informationVisible)
        assertFalse(regenerating.informationEnabled)
        assertFalse(regenerating.terminalVisible)
        assertFalse(regenerating.terminalEnabled)
    }

    @Test
    fun editReplacementResolvesOnlyAfterTheSourceLeavesTheVisiblePath() {
        val source = message("source", Participant.USER).copy(
            parentId = "parent",
            text = "old",
        )
        val pending = PendingEditVisualReplacement(
            sourceMessageId = source.id,
            sourceParentId = source.parentId,
            submittedText = "edited",
            stableVisualKey = source.id,
        )
        val replacement = message("replacement", Participant.USER).copy(
            parentId = "parent",
            text = "edited",
        )

        assertNull(
            resolvePendingEditReplacement(
                messages = listOf(source, replacement),
                pending = pending,
            ),
        )
        assertEquals(
            replacement,
            resolvePendingEditReplacement(
                messages = listOf(replacement),
                pending = pending,
            ),
        )
    }

    @Test
    fun editReplacementRejectsAnUnrelatedUserWithTheSameText() {
        val pending = PendingEditVisualReplacement(
            sourceMessageId = "source",
            sourceParentId = "parent",
            submittedText = "edited",
            stableVisualKey = "source",
        )
        val unrelated = message("unrelated", Participant.USER).copy(
            parentId = "different-parent",
            text = "edited",
        )

        assertNull(
            resolvePendingEditReplacement(
                messages = listOf(unrelated),
                pending = pending,
            ),
        )
    }

    @Test
    fun regenerationExitIncludesEveryVisibleElementAfterTheOldAnswer() {
        val messages = listOf(
            message("user-1", Participant.USER),
            message("answer-1", Participant.MODEL),
            message("user-2", Participant.USER),
            message("answer-2", Participant.MODEL),
        )

        assertEquals(
            linkedSetOf("answer-1", "user-2", "answer-2"),
            regenerationExitMessageIds(messages, oldMessageId = "answer-1"),
        )
    }

    @Test
    fun regenerationKeepsFadedComponentsAfterTheNewSendingMessage() {
        val user = message("user-1", Participant.USER)
        val oldAnswer = message("answer-old", Participant.MODEL)
        val downstreamUser = message("user-2", Participant.USER)
        val downstreamAnswer = message("answer-2", Participant.MODEL)
        val oldPath = listOf(user, oldAnswer, downstreamUser, downstreamAnswer)
        val retained = regenerationExitMessages(oldPath, oldAnswer.id)
        val sending = message("answer-new", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )

        assertEquals(
            listOf("user-1", "answer-new", "answer-old", "user-2", "answer-2"),
            mergeRegenerationPresentationMessages(
                activeMessages = listOf(user, sending),
                retainedExitMessages = retained,
            ).map { message -> message.id },
        )
        assertEquals(
            oldPath,
            mergeRegenerationPresentationMessages(
                activeMessages = oldPath,
                retainedExitMessages = retained,
            ),
        )
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
