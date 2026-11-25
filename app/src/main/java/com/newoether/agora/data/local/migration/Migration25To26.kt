package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Snapshot of global knowledge revs used when the last conversation digest was written.
 * Compared on send to decide whether to rebuild L1 only (会话摘要).
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE conversations ADD COLUMN digestActiveMemoryRev INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE conversations ADD COLUMN digestPromptRev INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL("ALTER TABLE conversations ADD COLUMN digestPromptId TEXT")
    }
}
