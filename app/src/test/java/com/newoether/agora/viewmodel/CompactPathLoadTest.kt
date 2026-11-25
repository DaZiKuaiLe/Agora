package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.local.CompactMessageGraphRow
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.DetailStore
import com.newoether.agora.util.DebugLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class CompactPathLoadTest {
    @Test
    fun ensureFormalBatches_readsGraphThenHydratesOnlyUncoveredIds() {
        data class Case(
            val name: String,
            val extraTurns: Int,
            val expectCreated: Boolean,
            val hydrateIds: Set<String>,
        )
        val cases = listOf(
            Case("retain covers all", extraTurns = 0, expectCreated = false, hydrateIds = emptySet()),
            Case(
                name = "incremental hydrates uncovered prefix only",
                extraTurns = 1,
                expectCreated = true,
                hydrateIds = setOf("u0", "a0", "tool_round", "result_round"),
            ),
        )
        for (case in cases) {
            val conversations = mockk<ConversationRepository>()
            val provider = mockk<LlmProvider>()
            val providers = mockk<ProviderRegistry>(relaxed = true)
            val prompt = slot<List<com.newoether.agora.model.ChatMessage>>()
            coEvery { conversations.getCompactMessageGraph("conversation") } returns
                compactGraph(case.extraTurns)
            coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
            coEvery { conversations.assignTurnIdIfAbsent(any(), any()) } returns true
            coEvery { conversations.getMessagesByIds(any()) } answers {
                val ids = firstArg<List<String>>()
                ids.map { id ->
                    MessageEntity(
                        id = id,
                        conversationId = "conversation",
                        parentId = parentOf(id),
                        text = bodyOf(id),
                        participant = if (id.startsWith("u") || id.startsWith("result_")) {
                            Participant.USER
                        } else {
                            Participant.MODEL
                        },
                        status = MessageStatus.SUCCESS,
                        timestamp = 0L,
                        runId = "run",
                    )
                }
            }
            coEvery { conversations.getCompactDigest(any()) } returns null
            coEvery { conversations.getSendTokenCache(any()) } returns SendTokenCache()
            coEvery {
                conversations.commitCompactResidency(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns 1
            coEvery { providers.canonicalModelId(any()) } returns "openai:gpt"
            coEvery { provider.generateResponse(capture(prompt), any()) } returns
                flowOf(StreamEvent.TextChunk("folded chapter"))
            val result = runBlocking {
                BatchCompactEngine(
                    conversations = conversations,
                    detailStore = mockk(relaxed = true),
                    providers = providers,
                ).ensureFormalBatches(
                    conversationId = "conversation",
                    config = compactConfig(provider, retain = 2),
                )
            }
            if (case.expectCreated) {
                assertTrue(case.name, result is CompactResult.Created)
                assertTrue(case.name, prompt.captured.first().text.contains("huge tool stdout"))
                assertTrue(case.name, prompt.captured.first().text.contains("user-0"))
                coVerify { conversations.getMessagesByIds(match { it.toSet() == case.hydrateIds }) }
            } else {
                assertEquals(case.name, CompactResult.NotNeeded, result)
                coVerify(exactly = 0) { conversations.getMessagesByIds(any()) }
            }
        }
    }

    @Test
    fun ensureFormalBatches_fullRecompactHydratesOnePrefixTurnAtATime() {
        silenceDebugLog()
        val extraTurns = 3
        val conversations = mockk<ConversationRepository>()
        val provider = mockk<LlmProvider>()
        val providers = mockk<ProviderRegistry>(relaxed = true)
        val hydrateCalls = mutableListOf<List<String>>()
        val graph = compactGraph(extraTurns)
        coEvery { conversations.getCompactMessageGraph("conversation") } returns graph
        coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
        coEvery { conversations.assignTurnIdIfAbsent(any(), any()) } returns true
        coEvery { conversations.getSendTokenCache(any()) } returns SendTokenCache()
        coEvery {
            conversations.commitCompactResidency(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns 1
        coEvery { conversations.getMessagesByIds(any()) } answers {
            val ids = firstArg<List<String>>()
            hydrateCalls += ids
            ids.map { id ->
                MessageEntity(
                    id = id,
                    conversationId = "conversation",
                    parentId = parentOf(id),
                    text = bodyOf(id),
                    participant = if (id.startsWith("u") || id.startsWith("result_")) {
                        Participant.USER
                    } else {
                        Participant.MODEL
                    },
                    status = MessageStatus.SUCCESS,
                    timestamp = 0L,
                    runId = "run",
                )
            }
        }
        coEvery { conversations.getCompactDigest(any()) } returns null
        coEvery { providers.canonicalModelId(any()) } returns "openai:gpt"
        coEvery { provider.generateResponse(any(), any()) } returns
            flowOf(StreamEvent.TextChunk("folded chapter"))
        val result = runBlocking {
            BatchCompactEngine(
                conversations = conversations,
                detailStore = mockk(relaxed = true),
                providers = providers,
            ).ensureFormalBatches(
                conversationId = "conversation",
                config = compactConfig(provider, retain = 2),
                fullRecompact = true,
            )
        }
        assertTrue(result is CompactResult.Created)
        assertTrue(hydrateCalls.isNotEmpty())
        val allIds = graph.map { it.id }.toSet()
        assertTrue(hydrateCalls.none { it.toSet() == allIds })
        val turnIdSets = listOf(
            setOf("u0", "a0", "tool_round", "result_round"),
            setOf("u1", "a1"),
            setOf("u2", "a2"),
            setOf("u3", "a3"),
            setOf("u4", "a4"),
        )
        for (call in hydrateCalls) {
            val ids = call.toSet()
            assertTrue(
                "hydrate mixed turns: $ids",
                turnIdSets.any { turnIds -> ids.all { it in turnIds } },
            )
        }
    }

    @Test
    fun automaticNeeded_hydratesUncoveredTurnsOneAtATime() {
        val conversations = mockk<ConversationRepository>()
        val hydrateCalls = mutableListOf<List<String>>()
        coEvery { conversations.getSendTokenCache("conversation") } returns SendTokenCache()
        coEvery { conversations.getCompactMessageGraph("conversation") } returns compactGraph(3)
        coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
        coEvery { conversations.getMessagesByIds(any()) } answers {
            val ids = firstArg<List<String>>()
            hydrateCalls += ids
            ids.map { id ->
                MessageEntity(
                    id = id,
                    conversationId = "conversation",
                    parentId = parentOf(id),
                    text = bodyOf(id),
                    participant = if (id.startsWith("u") || id.startsWith("result_")) {
                        Participant.USER
                    } else {
                        Participant.MODEL
                    },
                    status = MessageStatus.SUCCESS,
                    timestamp = 0L,
                    runId = "run",
                )
            }
        }
        runBlocking {
            BatchCompactEngine(
                conversations = conversations,
                detailStore = mockk(relaxed = true),
                providers = mockk(relaxed = true),
            ).automaticNeeded("conversation", compactConfig(mockk(relaxed = true), retain = 2))
        }
        assertTrue(hydrateCalls.isNotEmpty())
        val allIds = compactGraph(3).map { it.id }.toSet()
        assertTrue(hydrateCalls.none { it.toSet() == allIds })
        assertTrue(hydrateCalls.all { it.size <= 4 })
    }

    @Test
    fun ensureFormalBatches_fullRecompactClosesASliceBeforeReadingLaterTurns() {
        silenceDebugLog()
        data class Case(
            val name: String,
            val llmFails: Boolean,
            val expectClear: Boolean,
            val archiveOriginalBand: Boolean = false,
        )
        val cases = listOf(
            Case("folds the crossing then keeps reading", llmFails = false, expectClear = true),
            Case("failed fold leaves the previous digest", llmFails = true, expectClear = false),
            Case(
                "archives tools only on turns that stay original",
                llmFails = false,
                expectClear = true,
                archiveOriginalBand = true,
            ),
            Case(
                "failed fold does not archive the original band",
                llmFails = true,
                expectClear = false,
                archiveOriginalBand = true,
            ),
        )
        val turnIdSets = listOf(
            setOf("u0", "a0", "tool_round", "result_round"),
            setOf("u1", "a1"),
            setOf("u2", "a2"),
            setOf("u3", "a3"),
            setOf("u4", "a4"),
        )
        for (case in cases) {
            val conversations = mockk<ConversationRepository>()
            val provider = mockk<LlmProvider>()
            val providers = mockk<ProviderRegistry>(relaxed = true)
            val events = mutableListOf<String>()
            val prompts = mutableListOf<String>()
            val progress = mutableListOf<CompactBatchProgress>()
            coEvery { conversations.getCompactMessageGraph("conversation") } returns compactGraph(3)
            coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
            coEvery { conversations.assignTurnIdIfAbsent(any(), any()) } returns true
            coEvery { conversations.getSendTokenCache(any()) } returns SendTokenCache(
                tokens = 500,
                countedTurnId = "turn-old|1",
                budget = 8_192,
                retainTurns = 2,
                fixedCost = 10,
                configKey = "model|8192|2|1",
                prefixTokens = 80,
            )
            coEvery {
                conversations.commitCompactResidency(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns 1
            val detailStore = if (case.archiveOriginalBand) {
                DetailStore(createTempDirectory("compact-original-band").toFile())
            } else {
                mockk(relaxed = true)
            }
            val appContext = if (case.archiveOriginalBand) detailArchiveContext() else null
            coEvery { conversations.getMessagesByIds(any()) } answers {
                val ids = firstArg<List<String>>()
                events += "load:" + ids.joinToString(",")
                ids.map { id ->
                    MessageEntity(
                        id = id,
                        conversationId = "conversation",
                        parentId = parentOf(id),
                        text = bodyOf(id),
                        participant = if (id.startsWith("u") || id.startsWith("result_")) {
                            Participant.USER
                        } else {
                            Participant.MODEL
                        },
                        status = MessageStatus.SUCCESS,
                        timestamp = 0L,
                        runId = "run",
                        toolCallJson = if (!case.archiveOriginalBand) {
                            null
                        } else {
                            when (id) {
                                "a0" -> toolJson("call_old")
                                "a4" -> toolJson("call_tail")
                                else -> null
                            }
                        },
                    )
                }
            }
            coEvery { conversations.getCompactDigest(any()) } returns null
            coEvery { providers.canonicalModelId(any()) } returns "openai:gpt"
            coEvery { provider.generateResponse(any(), any()) } answers {
                prompts += firstArg<List<com.newoether.agora.model.ChatMessage>>().first().text
                events += "llm"
                if (case.llmFails) {
                    flowOf(StreamEvent.Error(GenerationError.Unknown(IllegalStateException("compact failed"))))
                } else {
                    flowOf(StreamEvent.TextChunk("folded chapter"))
                }
            }
            val result = runBlocking {
                BatchCompactEngine(
                    conversations = conversations,
                    detailStore = detailStore,
                    providers = providers,
                ).ensureFormalBatches(
                    conversationId = "conversation",
                    config = compactConfig(provider, retain = 2, threshold = 1),
                    fullRecompact = true,
                    onProgress = { progress += it },
                    appContext = appContext,
                )
            }
            val loads = events.filter { it.startsWith("load:") }
            for (load in loads) {
                val ids = load.removePrefix("load:").split(",").filter { it.isNotEmpty() }
                assertTrue(case.name + " page too big: $ids", ids.size <= 4)
                assertTrue(
                    case.name + " mixed turns: $ids",
                    turnIdSets.any { turnIds -> ids.all { it in turnIds } },
                )
            }
            val firstLlm = events.indexOf("llm")
            val firstLaterTurn = events.indexOfFirst { event ->
                event.removePrefix("load:").split(",").any { it == "u1" }
            }
            assertTrue(case.name, firstLlm >= 0)
            if (case.llmFails) {
                assertTrue(case.name, result is CompactResult.Failed)
                assertEquals(case.name, -1, firstLaterTurn)
                coVerify(exactly = 0) {
                    conversations.commitCompactResidency(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    )
                }
            } else {
                assertTrue(case.name, result is CompactResult.Created)
                assertTrue(case.name, firstLaterTurn > firstLlm)
                assertEquals(case.name, 3, prompts.size)
                assertTrue(case.name, prompts[0].contains("--- turn t0 ---"))
                assertTrue(case.name, prompts[0].contains("user-0"))
                assertTrue(case.name, prompts[0].contains("huge tool stdout"))
                assertTrue(case.name, !prompts[0].contains("--- turn t1 ---"))
                assertTrue(case.name, prompts.none { it.contains("--- turn t3 ---") || it.contains("--- turn t4 ---") })
                val committed = slot<SendTokenCache>()
                coVerify(exactly = 1) {
                    conversations.commitCompactResidency(
                        "conversation",
                        true,
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        capture(committed),
                    )
                }
                coVerify(exactly = 0) { conversations.clearCompactBatches(any()) }
                coVerify(exactly = 0) { conversations.attachCompactBatch(any(), any(), any()) }
                coVerify(exactly = 0) { conversations.setCompactDigest(any(), any(), any()) }
                assertEquals(case.name, 0, committed.captured.prefixTokens)
                assertTrue(case.name, !committed.captured.countedTurnId.isNullOrBlank())
            }
            assertTrue(case.name, progress.isNotEmpty())
            assertTrue(case.name, progress.all { it.total == 12 && it.kind == CompactProgressKind.OPTIMIZE })
            assertEquals(case.name, 0, progress.first().completed)
            if (case.llmFails) {
                assertTrue(case.name, progress.all { it.completed == 0 })
            } else {
                assertEquals(case.name, 8, progress.last().completed)
                assertEquals(case.name, listOf(0, 4, 6, 8), progress.map { it.completed }.distinct())
            }
            if (case.archiveOriginalBand) {
                val archived = detailStore
                if (case.llmFails) {
                    assertTrue(case.name, loads.none { it.contains("a4") })
                    assertNull(case.name, archived.get("call_tail"))
                    assertNull(case.name, archived.get("call_old"))
                } else {
                    assertTrue(case.name, loads.any { it.contains("a4") })
                    assertNull(case.name, archived.get("call_old"))
                    assertTrue(case.name, archived.get("call_tail") != null)
                }
            }
        }
    }

    private fun detailArchiveContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns "搜索了网页"
        every { context.getString(any(), *anyVararg()) } returns "搜索了网页"
        return context
    }

    private fun toolJson(callId: String): String = Json.encodeToString(
        listOf(
            MessageSegment(
                type = "tool",
                toolName = "web_search",
                toolArgs = """{"query":"q"}""",
                toolResult =
                    """{"type":"web_search","query":"q","results":[{"title":"a","url":"https://a","description":"d"}]}""",
                toolCallId = callId,
            ),
        ),
    )

    private fun silenceDebugLog() {
        mockkObject(DebugLog)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
        every { DebugLog.e(any(), any(), any()) } just Runs
    }

    private fun compactGraph(extraTurns: Int): List<CompactMessageGraphRow> {
        val rows = mutableListOf<CompactMessageGraphRow>()
        rows += row("u0", null, Participant.USER, 0, "t0")
        rows += row("a0", "u0", Participant.MODEL, 1, "t0")
        rows += row("tool_round", "a0", Participant.MODEL, 2, null)
        rows += row("result_round", "tool_round", Participant.USER, 3, null)
        var parent = "a0"
        var seq = 4L
        repeat(2 + extraTurns - 1) { index ->
            val turn = index + 1
            val userId = "u$turn"
            val modelId = "a$turn"
            rows += row(userId, parent, Participant.USER, seq++, "t$turn")
            rows += row(modelId, userId, Participant.MODEL, seq++, "t$turn")
            parent = modelId
        }
        return rows
    }

    private fun row(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long,
        turnId: String?,
    ) = CompactMessageGraphRow(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        participant = participant,
        status = MessageStatus.SUCCESS,
        timestamp = sequence,
        runId = "run",
        runSequence = sequence,
        modelName = "gpt",
        consumedAtPass = if (participant == Participant.USER && !id.startsWith("result_")) 1 else null,
        turnId = turnId,
        compactLocked = false,
        compactBatchId = null,
        compactDigestRev = 0,
        hasCompactDigest = false,
    )

    private fun parentOf(id: String): String? = when (id) {
        "u0" -> null
        "a0" -> "u0"
        "tool_round" -> "a0"
        "result_round" -> "tool_round"
        else -> null
    }

    private fun bodyOf(id: String): String = when (id) {
        "u0" -> "user-0"
        "result_round" -> "huge tool stdout"
        else -> id
    }

    private fun compactConfig(
        provider: LlmProvider,
        retain: Int,
        threshold: Int = 1_000,
    ) = AutomaticCompactConfig(
        enabled = true,
        compactThreshold = threshold,
        request = CompactRequest(
            model = "openai:gpt",
            prompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM,
            retainLogicalMessages = retain,
        ),
        providerName = "openai",
        apiKey = "key",
        baseUrl = null,
        provider = provider,
        configured = true,
        generationConfig = GenerationConfig(
            providerName = "openai",
            modelId = "gpt",
            apiKey = "key",
            effectiveSystemPrompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM,
            codeExecutionEnabled = false,
            googleSearchEnabled = false,
            thinkingEnabled = false,
            baseUrl = null,
        ),
        providerInstances = emptyMap(),
        generationContext = GenerationContext(),
    )
}
