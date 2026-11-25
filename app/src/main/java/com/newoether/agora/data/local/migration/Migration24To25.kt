package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Working-context digest on messages: compactDigest + compactDigestRev.
 * Stored on the newest batched turn of a branch; projection prefers it over summary_batches.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN compactDigest TEXT")
        db.execSQL(
            "ALTER TABLE messages ADD COLUMN compactDigestRev INTEGER NOT NULL DEFAULT 0",
        )
    }
}
