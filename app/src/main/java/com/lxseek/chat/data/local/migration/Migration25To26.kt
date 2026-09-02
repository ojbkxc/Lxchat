package com.lxseek.chat.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v25 → v26 adds quote replies: `replyToJson` on the messages table. It stores the serialized
 * [com.lxseek.chat.model.MessageReplyRef] snapshot; NULL keeps existing rows as plain messages.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN replyToJson TEXT")
    }
}
