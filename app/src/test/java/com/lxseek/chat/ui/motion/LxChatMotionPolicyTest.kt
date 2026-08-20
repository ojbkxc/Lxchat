package com.lxseek.chat.ui.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LxChatMotionPolicyTest {
    @Test
    fun fullMotionRemainsAvailableWhenNeitherPreferenceRequestsReduction() {
        val policy = resolveLxChatMotionPolicy(
            appReduceMotion = false,
            systemAnimationsDisabled = false,
        )

        assertFalse(policy.reduceMotion)
        assertTrue(policy.allowContinuousMotion)
        assertTrue(policy.allowSpatialTransitions)
        assertTrue(policy.allowProgrammaticScrollMotion)
    }

    @Test
    fun appPreferenceDisablesMotionSensitiveCapabilities() {
        val policy = resolveLxChatMotionPolicy(
            appReduceMotion = true,
            systemAnimationsDisabled = false,
        )

        assertReduced(policy)
    }

    @Test
    fun systemRemoveAnimationsAlsoDisablesMotionSensitiveCapabilities() {
        val policy = resolveLxChatMotionPolicy(
            appReduceMotion = false,
            systemAnimationsDisabled = true,
        )

        assertReduced(policy)
    }

    private fun assertReduced(policy: LxChatMotionPolicy) {
        assertTrue(policy.reduceMotion)
        assertFalse(policy.allowContinuousMotion)
        assertFalse(policy.allowSpatialTransitions)
        assertFalse(policy.allowProgrammaticScrollMotion)
    }
}
