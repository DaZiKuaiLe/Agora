package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Compact algorithm/projection rev on conversations. 0 = written before format versioning.
 * Compared to [com.newoether.agora.data.CompactFormat.CURRENT_REV] on send.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE conversations ADD COLUMN compactFormatRev INTEGER NOT NULL DEFAULT 0",
        )
    }
}
