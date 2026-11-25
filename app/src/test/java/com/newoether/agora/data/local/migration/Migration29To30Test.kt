package com.newoether.agora.data.local.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration29To30Test {
    @Test
    fun migrationAddsSendTokenPrefixColumn() {
        assertEquals(29, MIGRATION_29_30.startVersion)
        assertEquals(30, MIGRATION_29_30.endVersion)
        assertTrue(ADD_SEND_TOKEN_PREFIX_TOKENS_SQL.contains("sendTokenPrefixTokens"))
        assertTrue(ADD_SEND_TOKEN_PREFIX_TOKENS_SQL.startsWith("ALTER TABLE conversations ADD COLUMN"))
    }
}
