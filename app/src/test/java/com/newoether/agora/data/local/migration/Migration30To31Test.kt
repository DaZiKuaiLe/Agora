package com.newoether.agora.data.local.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration30To31Test {
    @Test
    fun migrationAddsTranscriptTokenTotal() {
        assertEquals(30, MIGRATION_30_31.startVersion)
        assertEquals(31, MIGRATION_30_31.endVersion)
        assertTrue(ADD_TRANSCRIPT_TOKEN_TOTAL_SQL.contains("transcriptTokenTotal"))
        assertTrue(ADD_TRANSCRIPT_TOKEN_TOTAL_SQL.startsWith("ALTER TABLE conversations ADD COLUMN"))
    }
}
