package com.newoether.agora.data.local.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration28To29Test {
    @Test
    fun migrationAddsSendTokenLedgerColumns() {
        assertEquals(28, MIGRATION_28_29.startVersion)
        assertEquals(29, MIGRATION_28_29.endVersion)
        val statements = listOf(
            ADD_CACHED_SEND_TOKENS_SQL,
            ADD_SEND_TOKEN_COUNTED_TURN_SQL,
            ADD_SEND_TOKEN_BUDGET_SQL,
            ADD_SEND_TOKEN_RETAIN_SQL,
            ADD_SEND_TOKEN_FIXED_SQL,
            ADD_SEND_TOKEN_CONFIG_KEY_SQL,
        )
        assertTrue(ADD_CACHED_SEND_TOKENS_SQL.contains("cachedSendTokens"))
        assertTrue(ADD_SEND_TOKEN_COUNTED_TURN_SQL.contains("sendTokenCountedTurnId"))
        assertTrue(ADD_SEND_TOKEN_BUDGET_SQL.contains("sendTokenBudget"))
        assertTrue(ADD_SEND_TOKEN_RETAIN_SQL.contains("sendTokenRetainTurns"))
        assertTrue(ADD_SEND_TOKEN_FIXED_SQL.contains("sendTokenFixedCost"))
        assertTrue(ADD_SEND_TOKEN_CONFIG_KEY_SQL.contains("sendTokenConfigKey"))
        assertEquals(6, statements.size)
        statements.forEach { sql ->
            assertTrue(sql, sql.startsWith("ALTER TABLE conversations ADD COLUMN"))
        }
    }
}
