package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Summary-batch compression metadata on messages:
 * turnId, compactLocked, compactBatchId, compactSummary (precompress cache).
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN turnId TEXT")
        db.execSQL(
            "ALTER TABLE messages ADD COLUMN compactLocked INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL("ALTER TABLE messages ADD COLUMN compactBatchId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN compactSummary TEXT")
    }
}
