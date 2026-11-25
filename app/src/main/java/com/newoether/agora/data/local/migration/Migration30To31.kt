package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val ADD_TRANSCRIPT_TOKEN_TOTAL_SQL =
    "ALTER TABLE conversations ADD COLUMN transcriptTokenTotal INTEGER"

/** Last top-bar token total. Null means it has not been seated yet. */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(ADD_TRANSCRIPT_TOKEN_TOTAL_SQL)
    }
}
