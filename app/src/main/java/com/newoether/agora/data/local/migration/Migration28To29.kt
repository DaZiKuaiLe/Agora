package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val ADD_CACHED_SEND_TOKENS_SQL =
    "ALTER TABLE conversations ADD COLUMN cachedSendTokens INTEGER NOT NULL DEFAULT 0"
internal const val ADD_SEND_TOKEN_COUNTED_TURN_SQL =
    "ALTER TABLE conversations ADD COLUMN sendTokenCountedTurnId TEXT"
internal const val ADD_SEND_TOKEN_BUDGET_SQL =
    "ALTER TABLE conversations ADD COLUMN sendTokenBudget INTEGER NOT NULL DEFAULT 0"
internal const val ADD_SEND_TOKEN_RETAIN_SQL =
    "ALTER TABLE conversations ADD COLUMN sendTokenRetainTurns INTEGER NOT NULL DEFAULT 0"
internal const val ADD_SEND_TOKEN_FIXED_SQL =
    "ALTER TABLE conversations ADD COLUMN sendTokenFixedCost INTEGER NOT NULL DEFAULT 0"
internal const val ADD_SEND_TOKEN_CONFIG_KEY_SQL =
    "ALTER TABLE conversations ADD COLUMN sendTokenConfigKey TEXT"

/** Persisted next-send token ledger so the context bar does not rebuild the provider path. */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(ADD_CACHED_SEND_TOKENS_SQL)
        db.execSQL(ADD_SEND_TOKEN_COUNTED_TURN_SQL)
        db.execSQL(ADD_SEND_TOKEN_BUDGET_SQL)
        db.execSQL(ADD_SEND_TOKEN_RETAIN_SQL)
        db.execSQL(ADD_SEND_TOKEN_FIXED_SQL)
        db.execSQL(ADD_SEND_TOKEN_CONFIG_KEY_SQL)
    }
}
