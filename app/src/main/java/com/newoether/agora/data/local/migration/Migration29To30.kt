package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val ADD_SEND_TOKEN_PREFIX_TOKENS_SQL =
    "ALTER TABLE conversations ADD COLUMN sendTokenPrefixTokens INTEGER NOT NULL DEFAULT 0"

/** Uncompressed prefix occupancy so send compact gating does not rebuild the provider path. */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(ADD_SEND_TOKEN_PREFIX_TOKENS_SQL)
    }
}
