package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMemoryPromptTest {
    @Test
    fun appendsWrappedBlockWhenPromptHasNoPlaceholder() {
        val out = applyActiveMemoryToSystemPrompt(
            compiledSystemPrompt = "思考过程尽量使用中文返回",
            activeMemory = "User is INFP.",
        )
        assertEquals(
            "思考过程尽量使用中文返回\n\n" + wrapActiveMemoryContext("User is INFP."),
            out,
        )
    }

    @Test
    fun fillsExistingEmptyTagsWithoutDuplicating() {
        val compiled = """
            You are helpful.

            <active_memory_context>

            </active_memory_context>

            Tool use:
        """.trimIndent()
        val out = applyActiveMemoryToSystemPrompt(compiled, "glossary")
        assertTrue(out!!.contains(wrapActiveMemoryContext("glossary")))
        assertEquals(1, Regex(Regex.escape(ACTIVE_MEMORY_CONTEXT_OPEN)).findAll(out).count())
        assertTrue(out.contains("Tool use:"))
    }

    @Test
    fun stripsBlockWhenMemoryIsBlank() {
        val compiled = "Lead-in\n\n${wrapActiveMemoryContext("old")}\n\nTail"
        assertEquals("Lead-in\n\n\n\nTail".trim(), applyActiveMemoryToSystemPrompt(compiled, "  "))
        assertNull(applyActiveMemoryToSystemPrompt(wrapActiveMemoryContext("old"), ""))
    }

    @Test
    fun memoryAloneBecomesTheSystemPrompt() {
        val out = applyActiveMemoryToSystemPrompt(null, "only memory")
        assertEquals(wrapActiveMemoryContext("only memory"), out)
        assertFalse(out!!.contains("思考过程"))
    }
}
