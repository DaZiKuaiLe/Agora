package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactFormatTest {
    @Test
    fun compactFormatStale_onlyWhenArtifactsExistAndRevIsBehind() {
        data class Case(
            val name: String,
            val storedRev: Int,
            val hasArtifacts: Boolean,
            val currentRev: Int,
            val stale: Boolean,
        )
        val cases = listOf(
            Case("behind with artifacts", storedRev = 0, hasArtifacts = true, currentRev = 1, stale = true),
            Case("mid bump with artifacts", storedRev = 1, hasArtifacts = true, currentRev = 2, stale = true),
            Case("no artifacts even at rev 0", storedRev = 0, hasArtifacts = false, currentRev = 1, stale = false),
            Case("rev matches", storedRev = 1, hasArtifacts = true, currentRev = 1, stale = false),
        )
        for (c in cases) {
            assertEquals(
                c.name,
                c.stale,
                compactFormatStale(c.storedRev, c.hasArtifacts, c.currentRev),
            )
        }
        assertTrue(CompactFormat.CURRENT_REV >= 5)
    }
}
