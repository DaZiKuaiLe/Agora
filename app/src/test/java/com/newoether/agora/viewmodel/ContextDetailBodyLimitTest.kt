package com.newoether.agora.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextDetailBodyLimitTest {
    @Test
    fun retainedBodyLimit() {
        val cases = listOf(
            0L to false,
            CONTEXT_DETAIL_MAX_RETAINED_CHARS to false,
            CONTEXT_DETAIL_MAX_RETAINED_CHARS + 1L to true,
        )
        cases.forEach { (characters, tooLarge) ->
            assertEquals(tooLarge, contextDetailRetainedBodyTooLarge(characters))
        }
    }
}
