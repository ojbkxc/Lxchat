package com.lxseek.chat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class NewChatMotionPolicyTest {
    @Test
    fun reducedMotionDisablesAllDecorativeNewChatMotion() {
        assertEquals(
            NewChatMotionPolicy(
                animateBackground = false,
                animateWelcomeText = false,
            ),
            newChatMotionPolicy(
                reduceMotion = true,
                isNewChatMode = true,
                isLoading = false,
                isSwitching = false,
                newChatEntryId = 1L,
            ),
        )
    }

    @Test
    fun defaultPolicyPreservesExistingWelcomeBehavior() {
        assertEquals(
            NewChatMotionPolicy(
                animateBackground = true,
                animateWelcomeText = true,
            ),
            newChatMotionPolicy(
                reduceMotion = false,
                isNewChatMode = true,
                isLoading = false,
                isSwitching = false,
                newChatEntryId = 1L,
            ),
        )
    }

    @Test
    fun loadingAndConversationSwitchingStopAmbientLoop() {
        listOf(
            false to true,
            true to false,
        ).forEach { (isLoading, isSwitching) ->
            val policy = newChatMotionPolicy(
                reduceMotion = false,
                isNewChatMode = true,
                isLoading = isLoading,
                isSwitching = isSwitching,
                newChatEntryId = 2L,
            )

            assertEquals(false, policy.animateBackground)
            assertEquals(false, policy.animateWelcomeText)
        }
    }
}
