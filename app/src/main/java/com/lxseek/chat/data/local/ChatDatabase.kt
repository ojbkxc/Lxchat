package com.lxseek.chat.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lxseek.chat.data.local.migration.MIGRATION_16_17
import com.lxseek.chat.data.local.migration.MIGRATION_17_18
import com.lxseek.chat.data.local.migration.MIGRATION_18_19
import com.lxseek.chat.data.local.migration.MIGRATION_19_20
import com.lxseek.chat.data.local.migration.MIGRATION_20_21
import com.lxseek.chat.data.local.migration.MIGRATION_21_22
import com.lxseek.chat.data.local.migration.MIGRATION_22_23

@Database(
    entities = [
        ChatEntity::class,
        RunEntity::class,
        MessageEntity::class,
        EmbeddingEntity::class,
        TaskEntity::class,
        LoopEntity::class,
        WorkflowEntity::class,
        WorkflowStepEntity::class,
    ],
    version = ChatDatabase.CURRENT_VERSION,
    exportSchema = true
)@TypeConverters(MessageConverters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        const val CURRENT_VERSION = 23
        const val DB_NAME = "lxchat_db"

        val ALL_MIGRATIONS = listOf(
            // v1 → v2 added messages.images (List<String> stored as TEXT via converter,
            // NOT NULL with "" representing an empty list). This step was missing, so any
            // device still on schema v1 crashed on launch with "migration 1 to 2 not found".
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN images TEXT NOT NULL DEFAULT ''")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN selectedBranchesJson TEXT")
                }
            },
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN thoughtTimeMs INTEGER")
                }
            },
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN modelName TEXT")
                }
            },
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN systemPromptId TEXT")
                }
            },
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN modelId TEXT")
                }
            },
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN thoughtTitle TEXT")
                }
            },
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN toolCallJson TEXT")
                }
            },
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS embeddings (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            messageId TEXT NOT NULL,
                            embedding BLOB NOT NULL,
                            chunkText TEXT NOT NULL,
                            dimension INTEGER NOT NULL
                        )
                    """)
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_messageId ON embeddings (messageId)")
                }
            },
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE embeddings ADD COLUMN modelId TEXT NOT NULL DEFAULT ''")
                    db.execSQL("DROP INDEX IF EXISTS index_embeddings_messageId")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_messageId_modelId ON embeddings (messageId, modelId)")
                }
            },
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN attachmentMeta TEXT")
                }
            },
            // v12 → v13 adds the automation layer: tasks + loops tables, and the
            // task-execution columns on conversations (origin/taskId/graduated).
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN taskId TEXT")
                    db.execSQL("ALTER TABLE conversations ADD COLUMN origin TEXT NOT NULL DEFAULT 'user'")
                    db.execSQL("ALTER TABLE conversations ADD COLUMN graduated INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_taskId ON conversations (taskId)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS tasks (
                            id TEXT PRIMARY KEY NOT NULL,
                            name TEXT NOT NULL,
                            prompt TEXT NOT NULL,
                            systemPrompt TEXT,
                            modelId TEXT,
                            cronExpr TEXT NOT NULL,
                            nextRunAt INTEGER NOT NULL,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            createdAt INTEGER NOT NULL,
                            lastRunAt INTEGER
                        )
                    """)
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS loops (
                            conversationId TEXT PRIMARY KEY NOT NULL,
                            intervalMs INTEGER NOT NULL,
                            prompt TEXT,
                            nextFireAt INTEGER NOT NULL,
                            cycleCount INTEGER NOT NULL DEFAULT 0,
                            maxCycles INTEGER,
                            active INTEGER NOT NULL DEFAULT 1,
                            FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                        )
                    """)
                }
            },
            // v13 → v14 adds an optimistic revision to loop state. A cycle that was
            // already running can then observe a stop/restart and avoid overwriting it.
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE loops ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                }
            },
            // v14 → v15 adds per-conversation draft persistence for the composer.
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN draftText TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE conversations ADD COLUMN draftAttachments TEXT")
                }
            },
            // v15 → v16 adds one-shot ("run once at an instant") tasks, which a cron cannot express.
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE tasks ADD COLUMN runAt INTEGER")
                }
            },
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
        )

        fun getStoredVersion(context: Context): Int {
            val dbPath = context.getDatabasePath(DB_NAME)
            if (!dbPath.exists()) return 0
            return try {
                val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
                val version = db.version
                db.close()
                version
            } catch (e: Exception) {
                0
            }
        }

        fun build(context: Context): ChatDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                DB_NAME
            ).addMigrations(*ALL_MIGRATIONS.toTypedArray())
                .fallbackToDestructiveMigration(false)
                .build()
        }
    }
}
