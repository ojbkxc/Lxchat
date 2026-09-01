package com.lxseek.chat.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBudgetTest {
    @Test
    fun legacyMessageWindowsMigrateToUsefulTokenBudgets() {
        assertEquals(20_480, ContextBudget.normalize(20))
        assertEquals(102_400, ContextBudget.normalize(100))
    }

    @Test
    fun tokenBudgetsAreClampedAndNullUsesNewDefault() {
        assertEquals(ContextBudget.DEFAULT_TOKENS, ContextBudget.normalize(null))
        assertEquals(ContextBudget.MIN_TOKENS, ContextBudget.normalize(1_000))
        assertEquals(ContextBudget.MAX_TOKENS, ContextBudget.normalize(Int.MAX_VALUE))
    }

    @Test
    fun compactLabelsDoNotMigrateLiveLowTokenUsageAsLegacySettings() {
        assertEquals("0", ContextBudget.compactLabel(0))
        assertEquals("64", ContextBudget.compactLabel(64))
        assertEquals("4K", ContextBudget.compactLabel(4_096))
    }
}
