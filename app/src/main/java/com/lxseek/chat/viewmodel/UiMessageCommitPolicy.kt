package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage

/**
 * Merges Controller-owned optimistic commits into the Room-backed UI snapshot by message ID.
 *
 * Room can publish a just-inserted row before the inserting coroutine reaches its UI commit.
 * Appending in that race creates duplicate in-memory rows (the database remains unique), which
 * projection code can then misread as real Edit/Regenerate siblings.
 */
internal object UiMessageCommitPolicy {
    fun upsert(
        existing: List<ChatMessage>,
        committed: List<ChatMessage>,
    ): List<ChatMessage> {
        if (committed.isEmpty()) return existing.distinctBy { it.id }
        val committedById = committed.associateBy { it.id }
        val emittedIds = hashSetOf<String>()
        return buildList(existing.size + committedById.size) {
            for (message in existing) {
                if (emittedIds.add(message.id)) {
                    add(committedById[message.id] ?: message)
                }
            }
            for (message in committedById.values) {
                if (emittedIds.add(message.id)) add(message)
            }
        }
    }
}
