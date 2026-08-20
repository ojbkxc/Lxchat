package com.lxseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomModelReferencesTest {
    @Test
    fun renameMovesEverySetReferenceWithoutDuplicatingTheTarget() {
        assertEquals(
            setOf("OpenAI:gpt-4.1", "Gateway:gpt-5"),
            setOf("OpenAI:gpt-4.1", "Gateway:old", "Gateway:gpt-5")
                .replaceModelReference(
                    oldModelId = "Gateway:old",
                    newModelId = "Gateway:gpt-5",
                ),
        )
    }

    @Test
    fun deleteRemovesNullableAndSetReferences() {
        assertNull(
            "Gateway:old".replaceModelReference(
                oldModelId = "Gateway:old",
                newModelId = null,
            )
        )
        assertEquals(
            setOf("OpenAI:gpt-4.1"),
            setOf("OpenAI:gpt-4.1", "Gateway:old")
                .replaceModelReference(
                    oldModelId = "Gateway:old",
                    newModelId = null,
                ),
        )
    }

    @Test
    fun aliasMovesToRenamedModelAndBlankAliasClearsIt() {
        val renamed = mapOf(
            "Gateway:old" to "Fast model",
            "OpenAI:gpt-4.1" to "Default",
        ).replaceCustomModelAlias(
            oldModelId = "Gateway:old",
            newModelId = "Gateway:new",
            alias = "Renamed",
        )

        assertEquals(
            mapOf(
                "Gateway:new" to "Renamed",
                "OpenAI:gpt-4.1" to "Default",
            ),
            renamed,
        )
        assertEquals(
            emptyMap<String, String>(),
            mapOf("Gateway:new" to "Renamed").replaceCustomModelAlias(
                oldModelId = "Gateway:new",
                newModelId = "Gateway:new",
                alias = "",
            ),
        )
    }
}
