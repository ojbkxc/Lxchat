package com.lxseek.chat.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v24 → v25 adds conversation pinning: `pinned` flag + `pinnedAt` timestamp on
 * the conversations table. Both default to 0/false so existing rows are unpinned.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0")
    }
}