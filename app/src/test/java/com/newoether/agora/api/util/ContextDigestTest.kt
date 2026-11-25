package com.newoether.agora.api.util

import com.newoether.agora.data.RESIDENCY_CHAPTER_STORAGE_MARK
import com.newoether.agora.data.RESIDENCY_CHAPTER_WIRE_BREAK
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextDigestTest {
    private val allowed = "batch_11111111-1111-1111-1111-111111111111"
    private val illegal = "batch_22222222-2222-2222-2222-222222222222"

    @Test
    fun stripIllegalBatchIdCitesRemovesUnknownUuids() {
        val raw = "See `$allowed` and also `$illegal` then done."
        val stripped = stripIllegalBatchIdCites(raw, setOf(allowed))
        assertTrue(stripped.contains(allowed))
        assertFalse(stripped.contains(illegal))
        assertTrue(stripped.contains("then done"))
    }

    @Test
    fun stripIllegalBatchIdCitesKeepsClosedSet() {
        val raw = "Facts in $allowed still hold."
        assertEquals(raw, stripIllegalBatchIdCites(raw, setOf(allowed)))
    }

    @Test
    fun illegalBatchIdCitesListsUnknownUuids() {
        val cases = listOf(
            Triple("see `$allowed`", setOf(allowed), emptySet()),
            Triple("see `$allowed` and `$illegal`", setOf(allowed), setOf(illegal)),
            Triple("no ids", setOf(allowed), emptySet()),
        )
        for ((digest, allowedIds, expected) in cases) {
            assertEquals(
                "digest=$digest allowed=$allowedIds",
                expected,
                illegalBatchIdCites(digest, allowedIds),
            )
        }
    }

    @Test
    fun missingClosedSetBatchIdCitesReportsUncitedIds() {
        val cases = listOf(
            Triple("see `$allowed`", setOf(allowed), emptySet()),
            Triple("no ids here", setOf(allowed), setOf(allowed)),
            Triple("see `$allowed`", setOf(allowed, illegal), setOf(illegal)),
            Triple("see `$allowed` and `$illegal`", setOf(allowed, illegal), emptySet()),
            Triple("anything", emptySet(), emptySet()),
        )
        for ((digest, allowedIds, expected) in cases) {
            assertEquals(
                "digest=$digest allowed=$allowedIds",
                expected,
                missingClosedSetBatchIdCites(digest, allowedIds),
            )
        }
    }

    @Test
    fun applyContextCompressionPrefersDigestOverSummaryBatches() {
        val t1 = turn("a1", "turn_1", "batch_x", "Sum A")
        val t2 = turn(
            "a2",
            "turn_2",
            "batch_y",
            "Sum B",
            digest = "Theme: shipping Kotlin. See `batch_y`.",
            digestRev = 3,
        )
        val projected = applyContextCompression(
            messages = listOf(t1.first, t1.second, t2.first, t2.second),
            projectSummaryBatches = true,
            retainTurns = 0,
            archiveBandEnabled = true,
        )
        val digestMsg = projected.single { it.id.startsWith("context_digest_") }
        assertEquals("context_digest_3", digestMsg.id)
        assertEquals("Theme: shipping Kotlin. See `batch_y`.", digestMsg.text)
        assertFalse(digestMsg.text.contains("\"type\""))
        assertFalse(digestMsg.text.contains("Sum A"))
        assertTrue(projected.none { it.id.startsWith("context_batch_") })
    }

    @Test
    fun applyContextCompressionFallsBackWithoutDigest() {
        val t1 = turn("a1", "turn_1", "batch_x", "Sum A")
        val projected = applyContextCompression(
            messages = listOf(t1.first, t1.second),
            projectSummaryBatches = true,
            retainTurns = 0,
            archiveBandEnabled = true,
        )
        assertTrue(projected.any { it.id.startsWith("context_batch_") })
        assertTrue(projected.none { it.id.startsWith("context_digest_") })
    }

    @Test
    fun digestProjectionDoesNotKeepLockedBatchedTurns() {
        val lockedBatched = turn(
            "a1",
            "turn_1",
            "batch_x",
            "A",
            locked = true,
            digest = "World",
            digestRev = 1,
        )
        val projected = applyDigestProjection(
            messages = listOf(lockedBatched.first, lockedBatched.second),
            retainTurns = 0,
            digest = PathDigest(body = "World", rev = 1),
        )
        assertTrue(projected.any { it.id.startsWith("context_digest_") })
        assertTrue(projected.none { it.id == lockedBatched.second.id })
    }

    @Test
    fun digestProjectionKeepsRetainWindowOriginals() {
        val older = turn("a1", "turn_1", "batch_x", "Old", digest = "D", digestRev = 2)
        val recent = turn("a2", "turn_2", "batch_y", "New")
        val projected = applyDigestProjection(
            messages = listOf(older.first, older.second, recent.first, recent.second),
            retainTurns = 1,
            digest = PathDigest(body = "D", rev = 2),
        )
        assertTrue(projected.any { it.id == recent.second.id })
        assertTrue(projected.any { it.id.startsWith("context_digest_") })
        assertTrue(projected.none { it.id == older.second.id })
    }

    @Test
    fun digestProjectionKeepsTrailingInFlightUser() {
        val batched = turn("a1", "turn_1", "batch_x", "Old", digest = "D", digestRev = 1)
        val retain = turn("a2", "turn_2", null, null)
        val inFlight = ChatMessage(
            id = "u_new",
            text = "fresh question",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            parentId = retain.second.id,
        )
        val projected = applyDigestProjection(
            messages = listOf(
                batched.first, batched.second,
                retain.first, retain.second,
                inFlight,
            ),
            retainTurns = 0,
            digest = PathDigest(body = "D", rev = 1),
        )
        assertEquals(inFlight.id, projected.last().id)
        assertTrue(projected.any { it.id.startsWith("context_digest_") })
        assertTrue(projected.any { it.id == retain.second.id })
    }

    @Test
    fun readingFormatRendersDigestBodyOnly() {
        val json = encodeContextDigestJson(
            rev = 4,
            digest = "Live status: shipping.",
        )
        val reading = formatContextDigestForReading(json)
        assertEquals("Live status: shipping.", reading)
        assertFalse(json.contains("\"notes\""))
    }

    @Test
    fun readingFormatRejectsSummaryBatchesJson() {
        val json = encodeSummaryBatchesJson(
            listOf(SummaryBatchItem("batch_1", "First")),
        )
        assertNull(formatContextDigestForReading(json))
    }

    @Test
    fun digestUserPromptIncludesClosedSetAndOriginals() {
        val prompt = buildDigestUserPrompt(
            oldDigest = "Previous world",
            closedSet = listOf(allowed to "Short dir"),
            currentBatchOriginals = "--- turn t1 ---\n[User]\nhi\n",
        )
        assertTrue(prompt.contains("Previous world"))
        assertTrue(prompt.contains(allowed))
        assertTrue(prompt.contains("Short dir"))
        assertTrue(prompt.contains("--- turn t1 ---"))
        assertTrue(prompt.contains("hi"))
        assertFalse(prompt.contains("Prefers Kotlin"))
        assertTrue(prompt.contains(CONTEXT_DIGEST_MAX_CHARS.toString()))
    }

    @Test
    fun digestUserPromptIncludesReadOnlyUserKnowledge() {
        val prompt = buildDigestUserPrompt(
            oldDigest = "Previous world",
            closedSet = emptyList(),
            currentBatchOriginals = null,
            activeMemory = "Prefers Kotlin",
            promptPersona = "Be terse.",
        )
        assertTrue(prompt.contains("Prefers Kotlin"))
        assertTrue(prompt.contains("Be terse."))
    }

    @Test
    fun resolvePathDigestUsesHighestRev() {
        val older = turn("a1", "turn_1", "batch_x", "A", digest = "old", digestRev = 1)
        val newer = turn("a2", "turn_2", "batch_y", "B", digest = "new", digestRev = 4)
        val resolved = resolvePathDigest(
            listOf(older.first, older.second, newer.first, newer.second),
        )
        assertEquals(PathDigest(body = "new", rev = 4), resolved)
    }

    @Test
    fun resolvePathDigestFormatsFrozenChaptersForWire() {
        val stored = "Old$RESIDENCY_CHAPTER_STORAGE_MARK New"
        val pair = turn("a3", "turn_3", "summary", "S", digest = stored, digestRev = 1)
        val resolved = resolvePathDigest(listOf(pair.first, pair.second))
        assertEquals(listOf("Old", "New"), resolved!!.chapters)
        assertTrue(resolved.body.contains("[整合段 1/2]"))
        assertTrue(resolved.body.contains("[整合段 2/2]"))
        assertTrue(RESIDENCY_CHAPTER_WIRE_BREAK.trim() in resolved.body)
        assertFalse(RESIDENCY_CHAPTER_STORAGE_MARK in resolved.body)
        assertTrue(resolved.body.contains("Old"))
        assertTrue(resolved.body.contains("New"))
    }

    private fun turn(
        assistantId: String,
        turnId: String,
        batchId: String?,
        summary: String?,
        locked: Boolean = false,
        digest: String? = null,
        digestRev: Int = 0,
    ): Pair<ChatMessage, ChatMessage> {
        val user = ChatMessage(
            id = "u_$assistantId",
            text = "user $assistantId",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val assistant = ChatMessage(
            id = assistantId,
            text = "assistant $assistantId",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = user.id,
            turnId = turnId,
            compactLocked = locked,
            compactBatchId = batchId,
            compactSummary = summary,
            compactDigest = digest,
            compactDigestRev = digestRev,
        )
        return user to assistant
    }
}
