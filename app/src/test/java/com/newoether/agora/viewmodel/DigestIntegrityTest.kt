package com.newoether.agora.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class DigestIntegrityTest {
    @Test
    fun mergeDigestIntegrityIssuesDedupesByKind() {
        val merged = mergeDigestIntegrityIssues(
            listOf(
                DigestIntegrityIssue(
                    DigestIntegrityKind.MISSING_CLOSED_SET_CITES,
                    listOf("batch_a"),
                ),
                DigestIntegrityIssue(
                    DigestIntegrityKind.MISSING_CLOSED_SET_CITES,
                    listOf("batch_b", "batch_a"),
                ),
                DigestIntegrityIssue(
                    DigestIntegrityKind.DIGEST_WRITE_FAILED,
                    listOf("llm_empty"),
                ),
            ),
        )
        assertEquals(
            listOf(
                DigestIntegrityIssue(
                    DigestIntegrityKind.MISSING_CLOSED_SET_CITES,
                    listOf("batch_a", "batch_b"),
                ),
                DigestIntegrityIssue(
                    DigestIntegrityKind.DIGEST_WRITE_FAILED,
                    listOf("llm_empty"),
                ),
            ),
            merged,
        )
    }

    @Test
    fun mergeDigestIntegrityIssuesEmptyStaysEmpty() {
        assertEquals(emptyList<DigestIntegrityIssue>(), mergeDigestIntegrityIssues(emptyList()))
    }

    @Test
    fun mergeOptimizeResultsPrefersArchiveFailure() {
        val failed = CompactResult.Failed(CompactFailureReason.GENERIC)
        assertEquals(
            failed,
            mergeOptimizeResults(CompactResult.NotNeeded, failed),
        )
    }

    @Test
    fun mergeOptimizeResultsCarriesArchiveIssues() {
        val issues = listOf(
            DigestIntegrityIssue(DigestIntegrityKind.DIGEST_WRITE_FAILED, listOf("llm_empty")),
        )
        val archived = CompactResult.Created("archive_1", issues)
        val merged = mergeOptimizeResults(CompactResult.NotNeeded, archived)
        assertEquals(CompactResult.Created("archive_1", issues), merged)
    }
}
