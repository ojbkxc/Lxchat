package com.lxseek.chat.ui.chat

import androidx.compose.runtime.mutableStateMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInteractionStateTest {
    @Test
    fun `search and share selection remain mutually exclusive`() {
        val projection = projection(selectableIds = setOf("one", "two"))

        projection.activateSearch()
        assertTrue(projection.searchActive)

        projection.activateShareSelection()
        assertFalse(projection.searchActive)
        assertTrue(projection.shareSelectionActive)

        projection.activateSearch()
        assertTrue(projection.searchActive)
        assertFalse(projection.shareSelectionActive)
        assertTrue(projection.selectedShareMessageIds.isEmpty())
    }

    @Test
    fun `search navigation respects current match bounds`() {
        val projection = projection(matchCount = 3, initialSearchMatchIndex = 0)

        assertFalse(projection.previousSearchMatch())
        assertTrue(projection.nextSearchMatch())
        assertEquals(1, projection.searchMatchIndex)
        assertTrue(projection.nextSearchMatch())
        assertEquals(2, projection.searchMatchIndex)
        assertFalse(projection.nextSearchMatch())
    }

    @Test
    fun `taking share selection clears it exactly once`() {
        val projection = projection(selectableIds = linkedSetOf("one", "two"))
        projection.activateShareSelection()
        projection.toggleShareMessage("one")

        assertEquals(setOf("one"), projection.takeShareSelection())
        assertFalse(projection.shareSelectionActive)
        assertTrue(projection.selectedShareMessageIds.isEmpty())
        assertTrue(projection.takeShareSelection().isEmpty())
    }

    private fun projection(
        selectableIds: Set<String> = emptySet(),
        matchCount: Int = 0,
        initialSearchMatchIndex: Int = -1,
    ): ConversationInteractionProjection {
        val state = ConversationInteractionState(
            initialSearchMatchIndex = initialSearchMatchIndex,
        )
        return ConversationInteractionProjection(
            state = state,
            selectableShareMessageIds = selectableIds,
            searchMatchDistances = mutableStateMapOf(),
            searchMatches = List(matchCount) { index ->
                ConversationSearchMatch(
                    messageId = "message-$index",
                    start = index,
                    endExclusive = index + 1,
                    occurrenceInMessage = index,
                )
            },
        )
    }

}
