package com.lxseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeConversationGraphImporterTest {
    @Test
    fun selectedBranchesRoundTripNullAndMessageParents() {
        val selections = linkedMapOf<String?, String>(
            null to "root-message",
            "parent-message" to "selected-child",
        )

        val encoded = encodeStoredSelections(selections)

        assertEquals(
            mapOf<String?, String>(
                null to "root-message",
                "parent-message" to "selected-child",
            ),
            decodeStoredSelections(encoded),
        )
    }

    @Test
    fun malformedSelectedBranchesFailClosed() {
        assertEquals(emptyMap<String?, String>(), decodeStoredSelections("not-json"))
        assertEquals(emptyMap<String?, String>(), decodeStoredSelections(null))
    }
}
