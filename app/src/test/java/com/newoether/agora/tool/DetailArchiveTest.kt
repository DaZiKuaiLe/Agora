package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class DetailArchiveTest {
    private fun context(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns "搜索了网页"
        every { context.getString(any(), *anyVararg()) } returns "搜索 \"kotlin\" 找到 1 条结果"
        return context
    }

    @Test
    fun stubsPriorTurnSearchButKeepsLiveTail() {
        val store = DetailStore(createTempDirectory("detail-archive").toFile())
        val ctx = context()
        val payload = """{"type":"web_search","query":"kotlin","results":[{"title":"a","url":"https://a","description":"d"}]}"""
        val user1 = ChatMessage(id = "u1", text = "q1", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user1.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "web_search",
                    toolArgs = """{"query":"kotlin"}""",
                    toolResult = payload,
                    toolCallId = "call_search_1",
                ),
            ),
        )
        val result = ChatMessage(
            id = Constants.RESULT_MSG_PREFIX + "r1",
            parentId = tool.id,
            text = payload,
            participant = Participant.USER,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "web_search",
                    toolArgs = """{"query":"kotlin"}""",
                    toolResult = payload,
                    toolCallId = "call_search_1",
                ),
            ),
        )
        val assistant = ChatMessage(
            id = "a1",
            parentId = result.id,
            text = "done",
            participant = Participant.MODEL,
        )
        val user2 = ChatMessage(id = "u2", parentId = assistant.id, text = "next", participant = Participant.USER)

        val stubbed = DetailArchive.applyStubs(
            listOf(user1, tool, result, assistant, user2),
            store,
            ctx,
        )
        assertTrue(DetailArchive.isDetailRef(stubbed[1].segments!!.single().toolResult))
        assertTrue(DetailArchive.isDetailRef(stubbed[2].text))
        assertFalse(DetailArchive.isDetailRef(payload))
        val detailId = store.get("call_search_1")!!.detailId
        assertEquals("call_search_1", detailId)
        assertTrue(stubbed[1].segments!!.single().toolResult!!.contains(detailId))
        assertTrue(stubbed[1].segments!!.single().toolResult!!.contains("summary"))
        assertEquals(payload, store.get("call_search_1")!!.payload)
    }

    @Test
    fun keepsSearchInCurrentTurn() {
        val store = DetailStore(createTempDirectory("detail-live").toFile())
        val ctx = context()
        val payload = """{"type":"web_search","query":"now","results":[{"title":"a","url":"https://a","description":"d"}]}"""
        val user = ChatMessage(id = "u1", text = "q", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "web_search",
                    toolArgs = """{"query":"now"}""",
                    toolResult = payload,
                    toolCallId = "call_live",
                ),
            ),
        )
        val stubbed = DetailArchive.applyStubs(listOf(user, tool), store, ctx)
        assertEquals(payload, stubbed[1].segments!!.single().toolResult)
        assertNull(store.get("call_live"))
    }

    @Test
    fun persistEligibleToolsWritesLiveTailThenApplyStubs() {
        val store = DetailStore(createTempDirectory("detail-persist-live").toFile())
        val ctx = context()
        val payload = """{"type":"web_search","query":"now","results":[{"title":"a","url":"https://a","description":"d"}]}"""
        val user = ChatMessage(id = "u1", text = "q", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "web_search",
                    toolArgs = """{"query":"now"}""",
                    toolResult = payload,
                    toolCallId = "call_live",
                ),
            ),
        )
        val path = listOf(user, tool)
        val ids = DetailArchive.persistEligibleTools(path, store, ctx)
        assertEquals(setOf("call_live"), ids)
        assertEquals(payload, store.get("call_live")!!.payload)
        assertEquals(payload, tool.segments!!.single().toolResult)
        val stubbed = DetailArchive.applyStubs(path, store, ctx)
        assertTrue(DetailArchive.isDetailRef(stubbed[1].segments!!.single().toolResult))
    }

    @Test
    fun doesNotStubFailedToolPayload() {
        val store = DetailStore(createTempDirectory("detail-fail").toFile())
        val ctx = context()
        val payload = """{"type":"web_fetch","url":"https://x","error":"fetch_error","message":"boom"}"""
        val user1 = ChatMessage(id = "u1", text = "q1", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user1.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "web_fetch",
                    toolArgs = """{"url":"https://x"}""",
                    toolResult = payload,
                    toolCallId = "call_fetch_fail",
                ),
            ),
        )
        val user2 = ChatMessage(id = "u2", parentId = tool.id, text = "next", participant = Participant.USER)
        val stubbed = DetailArchive.applyStubs(listOf(user1, tool, user2), store, ctx)
        assertEquals(payload, stubbed[1].segments!!.single().toolResult)
        assertNull(store.get("call_fetch_fail"))
    }

    @Test
    fun stubsFailedShellAndKeepsOutputInDetailStore() {
        val store = DetailStore(createTempDirectory("detail-shell-fail").toFile())
        val ctx = context()
        val output = "x".repeat(200)
        val payload =
            """{"type":"execute_local_command","command":"ls","exit_code":1,"error":"execution_error","output":"$output"}"""
        val user1 = ChatMessage(id = "u1", text = "q1", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user1.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "execute_local_command",
                    toolArgs = """{"command":"ls"}""",
                    toolResult = payload,
                    toolCallId = "call_shell_fail",
                ),
            ),
        )
        val user2 = ChatMessage(id = "u2", parentId = tool.id, text = "next", participant = Participant.USER)
        val stubbed = DetailArchive.applyStubs(listOf(user1, tool, user2), store, ctx)
        val stub = stubbed[1].segments!!.single().toolResult!!
        assertTrue(DetailArchive.isDetailRef(stub))
        assertEquals(payload, store.get("call_shell_fail")!!.payload)
        assertFalse(stub.contains(output))
    }

    @Test
    fun stubsShellCommandWithExitCode() {
        val store = DetailStore(createTempDirectory("detail-shell").toFile())
        val ctx = context()
        val payload =
            """{"type":"execute_local_command","command":"ls","exit_code":0,"output":"${"x".repeat(200)}"}"""
        val user1 = ChatMessage(id = "u1", text = "q1", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user1.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "execute_local_command",
                    toolArgs = """{"command":"ls"}""",
                    toolResult = payload,
                    toolCallId = "call_shell_1",
                ),
            ),
        )
        val user2 = ChatMessage(id = "u2", parentId = tool.id, text = "next", participant = Participant.USER)
        val stubbed = DetailArchive.applyStubs(listOf(user1, tool, user2), store, ctx)
        assertTrue(DetailArchive.isDetailRef(stubbed[1].segments!!.single().toolResult))
        assertEquals(payload, store.get("call_shell_1")!!.payload)
    }

    @Test
    fun skipsStubWhenToolCallIdMissing() {
        val store = DetailStore(createTempDirectory("detail-noid").toFile())
        val ctx = context()
        val payload = """{"type":"web_fetch","url":"https://x","text":"${"a".repeat(100)}"}"""
        val user1 = ChatMessage(id = "u1", text = "q1", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user1.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "web_fetch",
                    toolArgs = """{"url":"https://x"}""",
                    toolResult = payload,
                    toolCallId = null,
                ),
            ),
        )
        val user2 = ChatMessage(id = "u2", parentId = tool.id, text = "next", participant = Participant.USER)
        val stubbed = DetailArchive.applyStubs(listOf(user1, tool, user2), store, ctx)
        assertEquals(payload, stubbed[1].segments!!.single().toolResult)
        assertTrue(store.get("call_missing") == null)
    }

    @Test
    fun deleteToolDetailsAfterBatchArchiveRemovesToolCopies() {
        val store = DetailStore(createTempDirectory("detail-gc").toFile())
        store.put("web_search", "s", "payload-a", preferredId = "call_a")
        store.put("web_fetch", "s", "payload-b", preferredId = "call_b")
        store.put("batch", "batch-summary", "full-turn-text", preferredId = "batch_1")
        DetailArchive.deleteToolDetailsAfterBatchArchive(
            store = store,
            toolDetailIds = listOf("call_a", "call_b", "batch_1"),
            batchId = "batch_1",
        )
        assertNull(store.get("call_a"))
        assertNull(store.get("call_b"))
        assertEquals("full-turn-text", store.get("batch_1")!!.payload)
    }

    @Test
    fun omitsPriorQueryLookupButKeepsLiveTailAndDoesNotWriteStore() {
        val store = DetailStore(createTempDirectory("detail-query-omit").toFile())
        store.put("web_search", "s", "archived-payload", preferredId = "archived_1")
        val ctx = context()
        val lookup = """{"type":"detail_store","detail_id":"archived_1","tool":"web_search","summary":"s","payload":"archived-payload"}"""
        val user1 = ChatMessage(id = "u1", text = "q1", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user1.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = DetailArchive.QUERY_TOOL,
                    toolArgs = """{"detail_id":"archived_1"}""",
                    toolResult = lookup,
                    toolCallId = "call_query_1",
                ),
            ),
        )
        val result = ChatMessage(
            id = Constants.RESULT_MSG_PREFIX + "r1",
            parentId = tool.id,
            text = lookup,
            participant = Participant.USER,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = DetailArchive.QUERY_TOOL,
                    toolArgs = """{"detail_id":"archived_1"}""",
                    toolResult = lookup,
                    toolCallId = "call_query_1",
                ),
            ),
        )
        val assistant = ChatMessage(
            id = "a1",
            parentId = result.id,
            text = "done",
            participant = Participant.MODEL,
        )
        val user2 = ChatMessage(id = "u2", parentId = assistant.id, text = "next", participant = Participant.USER)

        val stubbed = DetailArchive.applyStubs(
            listOf(user1, tool, result, assistant, user2),
            store,
            ctx,
        )
        val omittedTool = stubbed[1].segments!!.single().toolResult
        val omittedResult = stubbed[2].text
        assertTrue(DetailArchive.isQueryLookupOmitted(omittedTool))
        assertTrue(DetailArchive.isQueryLookupOmitted(omittedResult))
        assertTrue(omittedTool!!.contains("archived_1"))
        assertFalse(DetailArchive.isDetailRef(omittedTool))
        assertNull(store.get("call_query_1"))
        assertEquals("archived-payload", store.get("archived_1")!!.payload)

        val again = DetailArchive.applyStubs(stubbed, store, ctx)
        assertEquals(omittedTool, again[1].segments!!.single().toolResult)
        assertEquals(omittedResult, again[2].text)
    }

    @Test
    fun keepsQueryLookupInCurrentTurn() {
        val store = DetailStore(createTempDirectory("detail-query-live").toFile())
        val ctx = context()
        val lookup = """{"type":"detail_store","detail_id":"archived_1","tool":"web_search","summary":"s","payload":"${"x".repeat(80)}"}"""
        val user = ChatMessage(id = "u1", text = "q", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = DetailArchive.QUERY_TOOL,
                    toolArgs = """{"detail_id":"archived_1"}""",
                    toolResult = lookup,
                    toolCallId = "call_query_live",
                ),
            ),
        )
        val stubbed = DetailArchive.applyStubs(listOf(user, tool), store, ctx)
        assertEquals(lookup, stubbed[1].segments!!.single().toolResult)
        assertNull(store.get("call_query_live"))
    }

    @Test
    fun doesNotOmitFailedQueryLookup() {
        val store = DetailStore(createTempDirectory("detail-query-fail").toFile())
        val ctx = context()
        val payload = """{"type":"detail_store","detail_id":"missing","error":"not_found"}"""
        val user1 = ChatMessage(id = "u1", text = "q1", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user1.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = DetailArchive.QUERY_TOOL,
                    toolArgs = """{"detail_id":"missing"}""",
                    toolResult = payload,
                    toolCallId = "call_query_fail",
                ),
            ),
        )
        val user2 = ChatMessage(id = "u2", parentId = tool.id, text = "next", participant = Participant.USER)
        val stubbed = DetailArchive.applyStubs(listOf(user1, tool, user2), store, ctx)
        assertEquals(payload, stubbed[1].segments!!.single().toolResult)
        assertNull(store.get("call_query_fail"))
    }

    @Test
    fun stubsHugeShellStdoutWithoutKeepingThePayloadInTheStub() {
        val store = DetailStore(createTempDirectory("detail-huge").toFile())
        val ctx = context()
        val payload = "ok\n" + "x".repeat(200_000)
        val user1 = ChatMessage(id = "u1", text = "q1", participant = Participant.USER)
        val tool = ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "t1",
            parentId = user1.id,
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "execute_local_command",
                    toolArgs = """{"command":"cat huge"}""",
                    toolResult = payload,
                    toolCallId = "call_huge",
                ),
            ),
        )
        val user2 = ChatMessage(
            id = "u2",
            parentId = tool.id,
            text = "next",
            participant = Participant.USER,
        )
        val stubbed = DetailArchive.applyStubs(listOf(user1, tool, user2), store, ctx)
        val stub = stubbed[1].segments!!.single().toolResult
        assertTrue(DetailArchive.isDetailRef(stub))
        assertTrue((stub?.length ?: Int.MAX_VALUE) < 4_000)
        assertEquals(payload, store.get("call_huge")!!.payload)
    }
}
