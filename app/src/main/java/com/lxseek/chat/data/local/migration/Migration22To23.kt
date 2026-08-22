package com.lxseek.chat.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v22 → v23 adds the workflow layer: a `workflows` header table plus an ordered
 * `workflow_steps` table (generation steps and delays). No existing rows are touched.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workflows (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workflow_steps (
                id TEXT PRIMARY KEY NOT NULL,
                workflowId TEXT NOT NULL,
                position INTEGER NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                configJson TEXT NOT NULL,
                FOREIGN KEY(workflowId) REFERENCES workflows(id) ON DELETE CASCADE
            )
            """
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_workflow_steps_workflowId_position ON workflow_steps (workflowId, position)"
        )
    }
}
