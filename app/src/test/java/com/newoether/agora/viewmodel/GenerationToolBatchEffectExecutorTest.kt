package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.citationRecords
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolPresentationMetadata
import com.newoether.agora.tool.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolBatchEffectExecutorTest {
    @Test
    fun `malformed citation segments fail closed during overlay restore and append`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) = null
            },
            providerName = "provider",
        )
        val answer = MessageSegment(type = "answer", content = "answer")
        val malformed = MessageSegment(type = "citation", content = "{")

        overlay.replaceAll(listOf(answer, malformed))
        overlay.append(malformed)

        assertEquals(listOf(answer), overlay.snapshot())
    }

    @Test
    fun `late thought signature updates the completed thought without adding a segment`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) = null
            },
            providerName = "provider",
        )
        overlay.append(MessageSegment(type = "thought", content = "reason"))
        overlay.append(MessageSegment(type = "answer", content = "answer"))

        assertTrue(
            overlay.updateLastThoughtMetadata(
                signature = "signature",
                signatureProvider = "provider",
            )
        )

        val snapshot = overlay.snapshot()
        assertEquals(listOf("answer", "thought", "answer"), snapshot.map { it.type })
        assertEquals("signature", snapshot[1].signature)
        assertEquals("provider", snapshot[1].signatureProvider)
    }

    @Test
    fun `overlay uniquely owns stream indices metadata and terminal presentation`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) =
                    ToolPresentationMetadata(displayName = "Display $name", target = "initial")
            },
            providerName = "provider",
        )

        assertTrue(overlay.upsert("stream", null, "tool", "{", "signature"))
        assertEquals(false, overlay.upsert("stream", "call", "", "{}", null))
        overlay.start(call())
        overlay.applyProgress("call", ToolExecutionEvent.TargetResolved("resolved"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputDelta("partial"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputSnapshot("first\n"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputSnapshot("first\nsecond\n"))
        assertEquals(
            "first\nsecond\n",
            overlay.snapshot().single { it.type == "tool" }.toolProgress,
        )
        val completed = overlay.complete(
            call(),
            ToolExecutionResult(text = "done", displayText = "shown"),
        )

        assertEquals("done", completed.data.result)
        assertEquals("Display tool", completed.data.displayName)
        assertEquals("resolved", completed.segment.toolTarget)
        assertEquals("first\nsecond\n", completed.segment.toolProgress)
        assertEquals(ToolExecutionStates.SUCCEEDED, completed.segment.toolState)
        assertEquals("provider", completed.segment.signatureProvider)

        overlay.replaceAll(emptyList())
        assertNull(overlay.snapshot().singleOrNull())
    }

    @Test
    fun localToolCardsAppearOnlyWhenThatCallStarts() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) = null
            },
            providerName = "provider",
        )
        data class Case(
            val name: String,
            val act: (GenerationToolOverlay) -> Unit,
            val ids: List<String>,
            val states: List<String>,
        )
        val cases = listOf(
            Case(
                "queued names never become cards",
                { it.stopIncompleteTools() },
                emptyList(),
                emptyList(),
            ),
            Case(
                "first start creates the running card",
                { it.start(call("one", "one")) },
                listOf("one"),
                listOf(ToolExecutionStates.RUNNING),
            ),
            Case(
                "later start does not rewrite the earlier card",
                { it.start(call("two", "two")) },
                listOf("one", "two"),
                listOf(ToolExecutionStates.RUNNING, ToolExecutionStates.RUNNING),
            ),
            Case(
                "stop marks started tools and does not invent a third",
                { it.stopIncompleteTools() },
                listOf("one", "two"),
                listOf(ToolExecutionStates.STOPPED, ToolExecutionStates.STOPPED),
            ),
        )
        cases.forEach { case ->
            case.act(overlay)
            val tools = overlay.snapshot().filter { it.type == "tool" }
            assertEquals(case.name, case.ids, tools.map { it.toolCallId })
            assertEquals(case.name, case.states, tools.map { it.toolState })
        }
    }

    @Test
    fun `tool timings stay on the segment and skip exec while a start process is still running`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) = null
            },
            providerName = "provider",
        )
        val remote = call(id = "ssh", streamKey = "ssh").copy(name = "execute_remote_command")
        overlay.upsert("ssh", "ssh", "execute_remote_command", "{}", null)
        overlay.start(remote)
        overlay.applyProgress("ssh", ToolExecutionEvent.Timing(connectMs = 320L))
        overlay.applyProgress("ssh", ToolExecutionEvent.Timing(execMs = 1_200L))
        val finished = overlay.complete(
            remote,
            ToolExecutionResult(text = """{"ok":true}"""),
            nowMs = 99_000L,
        )
        assertEquals(320L, finished.segment.toolConnectMs)
        assertEquals(1_200L, finished.segment.toolExecMs)
        val finishedFields = Json.parseToJsonElement(finished.data.result).jsonObject
        assertEquals(320L, finishedFields.getValue("connect_ms").jsonPrimitive.long)
        assertEquals(1_200L, finishedFields.getValue("command_ms").jsonPrimitive.long)

        val start = call(id = "start", streamKey = "start").copy(name = "start_remote_process")
        overlay.upsert("start", "start", "start_remote_process", "{}", null)
        overlay.start(start)
        overlay.applyProgress("start", ToolExecutionEvent.Timing(connectMs = 210L))
        val running = overlay.complete(
            start,
            ToolExecutionResult(text = """{"running":true,"process_id":"ssh:aa"}"""),
            nowMs = 99_000L,
        )
        assertEquals(210L, running.segment.toolConnectMs)
        assertNull(running.segment.toolExecMs)
        assertEquals(ToolExecutionStates.BACKGROUND_RUNNING, running.segment.toolState)
        val runningFields = Json.parseToJsonElement(running.data.result).jsonObject
        assertEquals(210L, runningFields.getValue("connect_ms").jsonPrimitive.long)
        assertEquals(false, "command_ms" in runningFields)
    }

    @Test
    fun `citation overlay preserves first source order and merges repeated terminal updates`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) = null
            },
            providerName = "provider",
        )
        val answer = "Alpha Beta"
        fun citation(
            title: String,
            url: String,
            anchor: CitationAnchor? = null,
        ) = requireNotNull(
            CitationPolicy.create(
                provider = "test",
                kind = "web",
                title = title,
                url = url,
                anchors = listOfNotNull(anchor),
                answerText = answer,
            ),
        )
        val firstInitial = citation("First", "https://example.com/first")
        val second = citation(
            "Second",
            "https://example.com/second",
            CitationAnchor(6, 10, "Beta"),
        )
        val firstFinal = citation(
            "First",
            "https://example.com/first",
            CitationAnchor(0, 5, "Alpha"),
        )

        assertTrue(overlay.upsertCitation(firstInitial))
        assertTrue(overlay.upsertCitation(second))
        assertTrue(overlay.upsertCitation(firstFinal))
        assertEquals(false, overlay.upsertCitation(firstFinal))

        val snapshot = overlay.snapshot()
        assertEquals(listOf("answer", "citation", "citation"), snapshot.map { it.type })
        val citations = snapshot.citationRecords(answer)
        assertEquals(listOf("First", "Second"), citations.map { it.title })
        assertEquals(listOf(CitationAnchor(0, 5, "Alpha")), citations[0].anchors)
        assertEquals(listOf(CitationAnchor(6, 10, "Beta")), citations[1].anchors)
    }

    @Test
    fun `provider hosted tool lifecycle becomes one terminal display segment`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) =
                    ToolPresentationMetadata(displayName = "OpenAI Search")
            },
            providerName = "OpenAI",
        )
        val active = StreamEvent.HostedToolCallUpdate(
            streamKey = "ws_1",
            name = "openai_search",
            arguments = "{}",
        )

        assertTrue(overlay.upsertHosted(active))
        assertEquals(ToolExecutionStates.RUNNING, overlay.snapshot().last().toolState)
        assertEquals(
            false,
            overlay.upsertHosted(
                active.copy(
                    arguments = """{"type":"search","query":"latest Agora"}""",
                    result = """{"type":"web_search_call","status":"completed"}""",
                ),
            ),
        )

        val segment = overlay.snapshot().last()
        assertEquals("openai_search", segment.toolName)
        assertEquals("OpenAI Search", segment.toolDisplayName)
        assertEquals(ToolExecutionStates.SUCCEEDED, segment.toolState)
        assertTrue(segment.toolResult?.contains("web_search_call") == true)
    }

    @Test
    fun `snapshot clipping never starts with half of a surrogate pair`() {
        assertEquals("😀b", takeLastWholeCodePoints("a😀b", 3))
        assertEquals("b", takeLastWholeCodePoints("a😀b", 2))
        assertEquals("", takeLastWholeCodePoints("😀", 1))
        assertEquals("😀", takeLastWholeCodePoints("😀", 2))
    }

    @Test
    fun `accepted batch executes in order and returns the same identity without continuation`() = runTest {
        val provider = StreamingToolProvider()
        val tools = GenerationToolExecutor.forTest(listOf(provider))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")
        val forces = mutableListOf<Boolean>()
        val publishedAt = mutableListOf<Long>()

        val outcome = executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(call()),
                context = GenerationContext(),
                conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = { forces += it },
                onPublishedAt = publishedAt::add,
            ),
        )

        assertEquals(IDENTITY, outcome.identity)
        assertEquals(listOf("tool"), provider.executedNames)
        assertEquals(listOf(true, false, false, false), forces)
        assertEquals(4, publishedAt.size)
        assertEquals("done", outcome.calls.single().result)
        assertEquals(ToolExecutionStates.SUCCEEDED, outcome.segments.single().toolState)
        assertTrue(outcome.generatedImages.isEmpty())
    }

    @Test
    fun serialBatchRevealsTheNextCardOnlyWhenThatCallStarts() = runTest {
        val provider = StreamingToolProvider()
        val tools = GenerationToolExecutor.forTest(listOf(provider))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")
        val revealed = mutableListOf<List<String>>()

        executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(call("one", "one"), call("two", "two")),
                context = GenerationContext(),
                conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = {
                    revealed += overlay.snapshot()
                        .filter { it.type == "tool" }
                        .map { "${it.toolCallId}:${it.toolState}" }
                },
                onPublishedAt = {},
            ),
        )

        assertEquals(listOf("one:running"), revealed.first())
        assertTrue(revealed.any { it == listOf("one:succeeded") })
        assertTrue(
            revealed.any {
                it.size == 2 &&
                    it[0] == "one:succeeded" &&
                    it[1].startsWith("two:")
            },
        )
        assertEquals(
            listOf("one:succeeded", "two:succeeded"),
            overlay.snapshot().filter { it.type == "tool" }
                .map { "${it.toolCallId}:${it.toolState}" },
        )
    }

    @Test
    fun `declared tool images are transcribed into consecutive thinking segments without polluting the result`() = runTest {
        val provider = ImageResultToolProvider()
        val tools = GenerationToolExecutor.forTest(listOf(provider))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")
        val transcribed = mutableListOf<String>()
        val progress = mutableListOf<String>()

        val outcome = executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(
                    call("call-a", "stream-a", "{\"path\":\"/private/a.png\"}"),
                    call("call-b", "stream-b", "{\"path\":\"/private/b.png\"}"),
                ),
                context = GenerationContext(),
                conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
                toolImageTranscriber = { image, onProgress ->
                    transcribed += image.path
                    val partial = "partial ${image.path}"
                    progress += partial
                    onProgress(partial)
                    "description of ${image.path}"
                },
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = {},
                onPublishedAt = {},
            ),
        )

        assertEquals(
            listOf("/private/a.png", "/private/b.png"),
            transcribed,
        )
        assertEquals(
            listOf("partial /private/a.png", "partial /private/b.png"),
            progress,
        )
        val transcriptionSegments = overlay.snapshot().filter { it.type == "transcription" }
        assertEquals(2, transcriptionSegments.size)
        assertEquals(
            listOf(
                "description of /private/a.png",
                "description of /private/b.png",
            ),
            transcriptionSegments.map { it.content },
        )
        // The description stays out of the tool result text — it travels on the result row
        // (ToolCallData.transcription / segment.toolTranscription) and reaches the model via
        // the image-context row (ToolMessagesTest).
        assertEquals(listOf("done", "done"), outcome.calls.map { it.result })
        assertEquals(
            listOf(
                "description of /private/a.png",
                "description of /private/b.png",
            ),
            outcome.calls.map { it.transcription },
        )
        assertEquals(
            listOf(
                "description of /private/a.png",
                "description of /private/b.png",
            ),
            outcome.segments.map { it.toolTranscription },
        )
    }

    @Test
    fun `declared tool images without a transcriber keep the raw result and add no segment`() = runTest {
        val tools = GenerationToolExecutor.forTest(listOf(ImageResultToolProvider()))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")

        val outcome = executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(call(arguments = "{\"path\":\"/private/a.png\"}")),
                context = GenerationContext(),
                conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = {},
                onPublishedAt = {},
            ),
        )

        assertEquals("done", outcome.calls.single().result)
        assertTrue(overlay.snapshot().none { it.type == "transcription" })
    }

    private fun call(
        id: String = "call",
        streamKey: String = "stream",
        arguments: String = "{}",
    ) = StreamEvent.ToolCallRequest(
        id = id,
        name = "tool",
        arguments = arguments,
        streamKey = streamKey,
        signature = "signature",
    )

    private class ImageResultToolProvider : ToolProvider {
        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override fun handles(name: String): Boolean = name == "tool"

        override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
            error("Streaming adapter expected")

        override fun executeEvents(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): Flow<ToolExecutionEvent> {
            val path = kotlinx.serialization.json.Json.parseToJsonElement(arguments)
                .jsonObject["path"]!!.jsonPrimitive.content
            return flowOf(
                ToolExecutionEvent.Completed(
                    ToolExecutionResult(
                        text = "done",
                        images = listOf(
                            com.newoether.agora.model.ToolImageAttachment(
                                path = path,
                                mimeType = "image/png",
                                sizeBytes = 1L,
                                width = 1,
                                height = 1,
                                sha256 = "sha",
                            ),
                        ),
                        transcribeImages = true,
                    ),
                ),
            )
        }
    }

    private class StreamingToolProvider : ToolProvider {
        val executedNames = mutableListOf<String>()

        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override fun handles(name: String): Boolean = name == "tool"

        override fun presentationMetadata(name: String) = ToolPresentationMetadata("Display")

        override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
            error("Streaming adapter expected")

        override fun executeEvents(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): Flow<ToolExecutionEvent> {
            executedNames += name
            return flowOf(
                ToolExecutionEvent.TargetResolved("target"),
                ToolExecutionEvent.OutputDelta("partial"),
                ToolExecutionEvent.Completed(ToolExecutionResult(text = "done")),
            )
        }
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 2,
            effectId = "tool-batch-provider-2-0",
        )
    }
}
