package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.CompactTurnIndexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendTokenLedgerTest {
    @Test
    fun applyCompletedTurnTokens_addsWithoutOverflowingInt() {
        data class Case(val name: String, val previous: Int, val turn: Int, val expected: Int)
        val cases = listOf(
            Case("empty plus turn", 0, 40, 40),
            Case("accumulates", 100, 25, 125),
            Case("ignores negative turn", 50, -3, 50),
            Case("caps at Int.MAX_VALUE", Int.MAX_VALUE - 2, 10, Int.MAX_VALUE),
        )
        for (case in cases) {
            assertEquals(case.name, case.expected, applyCompletedTurnTokens(case.previous, case.turn))
        }
    }

    @Test
    fun canServe_requiresSeatedRowMatchingConfigBudgetAndRetain() {
        val seated = SendTokenCache(
            tokens = 120,
            countedTurnId = "turn-a|0",
            budget = 8_192,
            retainTurns = 8,
            fixedCost = 40,
            configKey = "model|8192|8|1",
        )
        data class Case(
            val name: String,
            val cache: SendTokenCache,
            val configKey: String,
            val budget: Int,
            val retain: Int,
            val expected: Boolean,
        )
        val cases = listOf(
            Case("matching seated", seated, "model|8192|8|1", 8_192, 8, true),
            Case("config key miss", seated, "other", 8_192, 8, false),
            Case("budget miss", seated, "model|8192|8|1", 4_096, 8, false),
            Case("retain miss", seated, "model|8192|8|1", 8_192, 4, false),
            Case("never seated", SendTokenCache(), "model|8192|8|1", 8_192, 8, false),
        )
        for (case in cases) {
            assertEquals(
                case.name,
                case.expected,
                case.cache.canServe(case.configKey, case.budget, case.retain),
            )
        }
    }

    @Test
    fun sendTokenMismatch_comparesPersistedAgainstComputedSendTokens() {
        data class Case(val name: String, val persisted: Int, val computed: Int, val mismatch: Boolean)
        val cases = listOf(
            Case("equal", 100, 100, false),
            Case("persisted behind", 80, 100, true),
            Case("persisted ahead", 120, 100, true),
        )
        for (case in cases) {
            assertEquals(case.name, case.mismatch, sendTokenMismatch(case.persisted, case.computed))
        }
    }

    @Test
    fun sendLedgerCursor_ignoresBodiesAndMovesOnCompactOrFinishedTurn() {
        val first = listOf(
            user("u1"),
            assistant("a1", turnId = "t1", digestRev = 0),
            user("u2"),
            assistant("a2", turnId = "t2", digestRev = 1),
        )
        val sameIdentity = first.map { message ->
            message.copy(text = message.text + " longer body")
        }
        val compacted = first.map { message ->
            if (message.id == "a2") message.copy(compactDigestRev = 2) else message
        }
        val nextTurn = first + listOf(
            user("u3"),
            assistant("a3", turnId = "t3", digestRev = 2),
        )
        val liveTail = first + user("u3")

        assertEquals(sendLedgerCursor(first), sendLedgerCursor(sameIdentity))
        assertEquals(sendLedgerCursor(first), sendLedgerCursor(liveTail))
        assertNotEquals(sendLedgerCursor(first), sendLedgerCursor(compacted))
        assertNotEquals(sendLedgerCursor(first), sendLedgerCursor(nextTurn))
        assertEquals("seated|0", sendLedgerCursor(emptyList()))
        assertTrue(sendLedgerCursor(first).startsWith("t2|"))

        data class StructureCase(
            val name: String,
            val previous: List<ChatMessage>,
            val next: List<ChatMessage>,
            val same: Boolean,
        )
        val seated = listOf(user("u1"), assistant("a1", "t1", 0))
        val structureCases = listOf(
            StructureCase(
                "longer body",
                seated,
                seated.map { message ->
                    message.copy(text = message.text + " more", thoughts = "thinking")
                },
                true,
            ),
            StructureCase(
                "thinking to sending",
                seated.map { message ->
                    if (message.id == "a1") message.copy(status = MessageStatus.THINKING) else message
                },
                seated.map { message ->
                    if (message.id == "a1") message.copy(status = MessageStatus.SENDING) else message
                },
                false,
            ),
            StructureCase(
                "compact revision",
                seated,
                seated.map { message ->
                    if (message.id == "a1") message.copy(compactDigestRev = 4) else message
                },
                false,
            ),
            StructureCase("appended turn", seated, seated + user("u2"), false),
        )
        for (case in structureCases) {
            assertEquals(
                case.name,
                case.same,
                sameSendLedgerStructure(case.previous, case.next),
            )
        }
        val cache = SendLedgerCursorCache()
        assertEquals(sendLedgerCursor(seated), cache.cursor(seated))
        val grown = seated.map { message -> message.copy(text = message.text + " streamed") }
        assertEquals(sendLedgerCursor(seated), cache.cursor(grown))
    }

    @Test
    fun advanceSendTokenCache_isIncrementalForOneFinishedTurn() {
        val t1 = CompactTurnIndexer.finishedDialogueTurns(listOf(user("u1"), assistant("a1", "t1", 0)))
        val t2Messages = listOf(
            user("u1"),
            assistant("a1", "t1", 0),
            user("u2"),
            assistant("a2", "t2", 0),
        )
        val t2 = CompactTurnIndexer.finishedDialogueTurns(t2Messages)
        val cache = SendTokenCache(
            tokens = 100,
            countedTurnId = sendLedgerCursor(listOf(user("u1"), assistant("a1", "t1", 0))),
            budget = 8_192,
            retainTurns = 8,
            fixedCost = 40,
            configKey = "model|8192|8|1",
        )
        data class Case(
            val name: String,
            val cache: SendTokenCache,
            val cursor: String,
            val turns: List<com.newoether.agora.tool.CompactTurn>,
            val kind: String,
            val tokens: Int?,
        )
        val seatedCursor = cache.countedTurnId!!
        val nextCursor = sendLedgerCursor(t2Messages)
        val cases = listOf(
            Case("already seated", cache, seatedCursor, t1, "seated", 100),
            Case("one new turn", cache, nextCursor, t2, "incremental", 140),
            Case(
                "config miss",
                cache.copy(configKey = "other"),
                nextCursor,
                t2,
                "full",
                null,
            ),
            Case(
                "compact jump",
                cache,
                sendLedgerCursor(
                    listOf(user("u1"), assistant("a1", "t1", 0), user("u2"), assistant("a2", "t2", 3)),
                ),
                CompactTurnIndexer.finishedDialogueTurns(
                    listOf(user("u1"), assistant("a1", "t1", 0), user("u2"), assistant("a2", "t2", 3)),
                ),
                "full",
                null,
            ),
        )
        for (case in cases) {
            val advanced = advanceSendTokenCache(
                cache = case.cache,
                configKey = "model|8192|8|1",
                tokenBudget = 8_192,
                retainTurns = 8,
                nextCursor = case.cursor,
                turns = case.turns,
                estimateTurn = { 40 },
                fixedCost = 40,
            )
            val kind = when (advanced) {
                is SendTokenAdvance.Seated -> "seated"
                is SendTokenAdvance.Incremental -> "incremental"
                SendTokenAdvance.NeedsFullRecompute -> "full"
            }
            assertEquals(case.name, case.kind, kind)
            val tokens = when (advanced) {
                is SendTokenAdvance.Seated -> advanced.cache.tokens
                is SendTokenAdvance.Incremental -> advanced.cache.tokens
                SendTokenAdvance.NeedsFullRecompute -> null
            }
            assertEquals(case.name, case.tokens, tokens)
        }

        val retainTurns = (1..9).flatMap { index ->
            listOf(
                user("u$index"),
                assistant("a$index", "t$index", 0),
            )
        }
        val rolled = advanceSendTokenCache(
            cache = cache.copy(
                tokens = 320,
                countedTurnId = sendLedgerCursor(retainTurns.dropLast(2)),
            ),
            configKey = "model|8192|8|1",
            tokenBudget = 8_192,
            retainTurns = 8,
            nextCursor = sendLedgerCursor(retainTurns),
            turns = CompactTurnIndexer.finishedDialogueTurns(retainTurns),
            estimateTurn = { 40 },
            fixedCost = 40,
        )
        assertTrue(rolled is SendTokenAdvance.Incremental)
        assertEquals(360, (rolled as SendTokenAdvance.Incremental).cache.tokens)
        assertEquals(40, rolled.cache.prefixTokens)

        val batchedPrefix = (1..9).flatMap { index ->
            listOf(
                user("bu$index"),
                assistant("ba$index", "bt$index", 0, batchId = if (index == 1) "summary" else null),
            )
        }
        val batchedRolled = advanceSendTokenCache(
            cache = cache.copy(
                tokens = 320,
                countedTurnId = sendLedgerCursor(batchedPrefix.dropLast(2)),
            ),
            configKey = "model|8192|8|1",
            tokenBudget = 8_192,
            retainTurns = 8,
            nextCursor = sendLedgerCursor(batchedPrefix),
            turns = CompactTurnIndexer.finishedDialogueTurns(batchedPrefix),
            estimateTurn = { 40 },
            fixedCost = 40,
        )
        assertEquals(SendTokenAdvance.NeedsFullRecompute, batchedRolled)
    }

    @Test
    fun automaticCompactNeededFromLedger_usesPrefixPlusFixedCostNotOccupancy() {
        val seated = SendTokenCache(
            tokens = 400_000,
            countedTurnId = "turn-a|0",
            budget = 524_288,
            retainTurns = 8,
            fixedCost = 40,
            configKey = "k",
            prefixTokens = 100,
        )
        data class Case(
            val name: String,
            val cache: SendTokenCache,
            val threshold: Int,
            val needed: Boolean,
        )
        val cases = listOf(
            Case("unseated", SendTokenCache(), 50, false),
            Case("empty prefix even if occupancy is huge", seated.copy(prefixTokens = 0), 50, false),
            Case("prefix plus fixed crosses", seated.copy(prefixTokens = 10, fixedCost = 40), 50, true),
            Case("prefix alone crosses", seated.copy(prefixTokens = 50, fixedCost = 0), 50, true),
            Case("under threshold", seated.copy(prefixTokens = 9, fixedCost = 40), 50, false),
        )
        for (case in cases) {
            assertEquals(
                case.name,
                case.needed,
                automaticCompactNeededFromLedger(case.cache, case.threshold),
            )
        }
    }

    @Test
    fun resolveSendLedgerForInteractive_doesNotFullScanWhenUnseated() {
        val seated = SendTokenCache(
            tokens = 100,
            countedTurnId = sendLedgerCursor(listOf(user("u1"), assistant("a1", "t1", 0))),
            budget = 8_192,
            retainTurns = 8,
            fixedCost = 40,
            configKey = "k",
            prefixTokens = 0,
        )
        val t1 = CompactTurnIndexer.finishedDialogueTurns(listOf(user("u1"), assistant("a1", "t1", 0)))
        data class Case(
            val name: String,
            val cache: SendTokenCache,
            val budget: Int,
            val retain: Int,
            val kind: String,
        )
        val cases = listOf(
            Case("seated match", seated, 8_192, 8, "seated"),
            Case("never seated", SendTokenCache(), 8_192, 8, "full"),
            Case("budget miss", seated, 4_096, 8, "full"),
            Case("retain miss", seated, 8_192, 4, "full"),
        )
        for (case in cases) {
            val advanced = resolveSendLedgerForInteractive(
                cache = case.cache,
                tokenBudget = case.budget,
                retainTurns = case.retain,
                nextCursor = seated.countedTurnId!!,
                turns = t1,
                estimateTurn = { 40 },
            )
            val kind = when (advanced) {
                is SendTokenAdvance.Seated -> "seated"
                is SendTokenAdvance.Incremental -> "incremental"
                SendTokenAdvance.NeedsFullRecompute -> "full"
            }
            assertEquals(case.name, case.kind, kind)
        }
    }

    @Test
    fun refreshDisplayedOccupancy_keepsSavedNumberWhenTheCursorDoesNotMatch() {
        val seatedMessages = listOf(user("u1"), assistant("a1", "t1", 0))
        val nextMessages = seatedMessages + listOf(user("u2"), assistant("a2", "t2", 0))
        val cache = SendTokenCache(
            tokens = 100,
            countedTurnId = sendLedgerCursor(seatedMessages),
            budget = 8_192,
            retainTurns = 8,
            fixedCost = 40,
            configKey = "model|8192|8|1",
            prefixTokens = 12,
        )
        val configKey = checkNotNull(cache.configKey)
        val jumped = sendLedgerCursor(
            listOf(user("u1"), assistant("a1", "t1", 4), user("u2"), assistant("a2", "t2", 0)),
        )
        data class Case(
            val name: String,
            val onLastPage: Boolean,
            val cursor: String,
            val turns: List<com.newoether.agora.tool.CompactTurn>,
            val configKey: String,
            val tokens: Int,
            val persists: Boolean,
        )
        val cases = listOf(
            Case(
                "seated match",
                true,
                cache.countedTurnId!!,
                CompactTurnIndexer.finishedDialogueTurns(seatedMessages),
                configKey,
                100,
                false,
            ),
            Case(
                "one new turn",
                true,
                sendLedgerCursor(nextMessages),
                CompactTurnIndexer.finishedDialogueTurns(nextMessages),
                configKey,
                140,
                true,
            ),
            Case(
                "compact signature changed",
                true,
                jumped,
                CompactTurnIndexer.finishedDialogueTurns(
                    listOf(user("u1"), assistant("a1", "t1", 4), user("u2"), assistant("a2", "t2", 0)),
                ),
                configKey,
                100,
                false,
            ),
            Case(
                "earlier page does not advance",
                false,
                sendLedgerCursor(nextMessages),
                CompactTurnIndexer.finishedDialogueTurns(nextMessages),
                configKey,
                100,
                false,
            ),
            Case("config changed", true, sendLedgerCursor(nextMessages), emptyList(), "other", 100, false),
        )
        for (case in cases) {
            val display = refreshDisplayedOccupancy(
                cache = cache,
                configKey = case.configKey,
                tokenBudget = 8_192,
                retainTurns = 8,
                ledgerCursor = case.cursor,
                onLastPage = case.onLastPage,
                turns = case.turns,
                estimateTurn = { 40 },
            )
            assertEquals(case.name, case.tokens, display.tokens)
            assertEquals(case.name, case.persists, display.persist != null)
            assertEquals(case.name, 100, cache.tokens)
        }
    }

    @Test
    fun cacheAfterCompactCommit_movesCursorWithTheDigestAndClearsPrefix() {
        val before = listOf(
            user("u1"),
            assistant("a1", "t1", digestRev = 2, batchId = "old"),
            user("u2"),
            assistant("a2", "t2", digestRev = 0),
        )
        val turns = CompactTurnIndexer.finishedDialogueTurns(before)
        data class Case(
            val name: String,
            val clear: Boolean,
            val covered: Set<String>,
            val digestTurnId: String?,
            val digestRev: Int,
            val after: List<ChatMessage>,
            val seated: Boolean,
            val summary: Int,
            val kept: Int,
            val tokens: Int,
        )
        val seated = SendTokenCache(
            tokens = 1_000,
            countedTurnId = "turn-old|9",
            budget = 8_192,
            retainTurns = 8,
            fixedCost = 40,
            configKey = "model|8192|8|1",
            prefixTokens = 300,
        )
        val cases = listOf(
            Case(
                "full replace",
                true,
                setOf("t1"),
                "t1",
                1,
                listOf(
                    user("u1"),
                    assistant("a1", "t1", digestRev = 1, batchId = "summary"),
                    user("u2"),
                    assistant("a2", "t2", digestRev = 0),
                ),
                true,
                50,
                80,
                170,
            ),
            Case(
                "incremental keeps older rev",
                false,
                setOf("t1", "t2"),
                "t2",
                4,
                listOf(
                    user("u1"),
                    assistant("a1", "t1", digestRev = 2, batchId = "summary"),
                    user("u2"),
                    assistant("a2", "t2", digestRev = 4, batchId = "summary"),
                ),
                true,
                20,
                40,
                100,
            ),
            Case(
                "unseated number is replaced",
                true,
                setOf("t1"),
                "t1",
                1,
                listOf(
                    user("u1"),
                    assistant("a1", "t1", digestRev = 1, batchId = "summary"),
                    user("u2"),
                    assistant("a2", "t2", digestRev = 0),
                ),
                false,
                50,
                80,
                130,
            ),
        )
        for (case in cases) {
            val cursor = cursorAfterCompactCommit(
                turns = turns,
                clearExistingBatches = case.clear,
                coveredTurnIds = case.covered,
                digestTurnId = case.digestTurnId,
                digestRev = case.digestRev,
                batchId = "summary",
            )
            assertEquals(case.name, sendLedgerCursor(case.after), cursor)
            val previous = if (case.seated) seated else SendTokenCache()
            val written = cacheAfterCompactCommit(previous, case.summary, case.kept, cursor)
            assertEquals(case.name, case.tokens, written.tokens)
            assertEquals(case.name, 0, written.prefixTokens)
            assertEquals(case.name, cursor, written.countedTurnId)
            assertEquals(case.name, previous.configKey, written.configKey)
        }
        val replaced = cacheAfterCompactCommit(
            seated,
            summaryTokens = 12,
            keptOriginalTokens = 30,
            nextCursor = "t2|1",
        )
        assertEquals(82, replaced.tokens)
        assertEquals(0, replaced.prefixTokens)
    }

    @Test
    fun cacheCorrectedByContextDetail_overwritesTheStoredSendNumber() {
        val previous = SendTokenCache(
            tokens = 0,
            countedTurnId = null,
            budget = 1,
            retainTurns = 1,
            fixedCost = 0,
            configKey = null,
            prefixTokens = 9,
        )
        data class Case(
            val name: String,
            val sent: Int,
            val prefix: Int,
            val fixed: Int,
            val cursor: String,
            val budget: Int,
            val retain: Int,
            val key: String,
            val tokens: Int,
            val storedCursor: String?,
            val storedKey: String?,
        )
        val cases = listOf(
            Case("writes the measured send", 4_200, 0, 40, "t9|3", 8_192, 18, "model|8192|18|1", 4_200, "t9|3", "model|8192|18|1"),
            Case("blank cursor keeps the old one", 10, 2, 0, "", 8_192, 8, "k", 10, null, "k"),
            Case("blank key keeps the old one", 10, 2, 0, "t1|1", 8_192, 8, "", 10, "t1|1", null),
        )
        for (case in cases) {
            val written = cacheCorrectedByContextDetail(
                previous = previous,
                sentTokens = case.sent,
                prefixTokens = case.prefix,
                fixedCost = case.fixed,
                ledgerCursor = case.cursor,
                tokenBudget = case.budget,
                retainTurns = case.retain,
                configKey = case.key,
            )
            assertEquals(case.name, case.tokens, written.tokens)
            assertEquals(case.name, case.prefix, written.prefixTokens)
            assertEquals(case.name, case.fixed, written.fixedCost)
            assertEquals(case.name, case.storedCursor, written.countedTurnId)
            assertEquals(case.name, case.storedKey, written.configKey)
            assertEquals(case.name, case.budget, written.budget)
            assertEquals(case.name, case.retain, written.retainTurns)
        }
    }

    @Test
    fun withThoughtPlaceholderIfNeeded_keepsChipWhenBodyWasOmitted() {
        val withBody = ChatMessage(
            id = "a",
            text = "answer",
            participant = Participant.MODEL,
            thoughts = "secret",
            thoughtTimeMs = 1_200,
            segments = listOf(
                com.newoether.agora.model.MessageSegment(type = "thought", content = "secret"),
            ),
        )
        val omitted = ChatMessage(
            id = "b",
            text = "answer",
            participant = Participant.MODEL,
            thoughtTimeMs = 1_200,
        )
        val plain = ChatMessage(
            id = "c",
            text = "answer",
            participant = Participant.MODEL,
        )
        assertEquals(withBody, withBody.withThoughtPlaceholderIfNeeded())
        val placeholder = omitted.withThoughtPlaceholderIfNeeded()
        assertEquals(1, placeholder.segments?.count { it.type == "thought" })
        assertTrue(placeholder.segments!!.first { it.type == "thought" }.content.isNotBlank())
        assertEquals(plain, plain.withThoughtPlaceholderIfNeeded())
    }

    private fun user(id: String) = ChatMessage(
        id = id,
        text = "user",
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
    )

    private fun assistant(
        id: String,
        turnId: String,
        digestRev: Int,
        batchId: String? = null,
    ) = ChatMessage(
        id = id,
        text = "assistant",
        participant = Participant.MODEL,
        status = MessageStatus.SUCCESS,
        turnId = turnId,
        compactDigestRev = digestRev,
        compactBatchId = batchId,
    )
}
