package com.newoether.agora.viewmodel

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticCompactSendPromptTest {
    @Test
    fun confirmResumesWithCompactThenSend() = runBlocking {
        val prompt = AutomaticCompactSendPrompt()
        val pending = async { prompt.awaitDecision() }
        prompt.pending.first { it }
        prompt.confirm()
        assertEquals(AutomaticCompactSendDecision.CompactThenSend, pending.await())
        assertFalse(prompt.pending.value)
    }

    @Test
    fun skipResumesWithSendWithoutCompact() = runBlocking {
        val prompt = AutomaticCompactSendPrompt()
        val pending = async { prompt.awaitDecision() }
        prompt.pending.first { it }
        prompt.skip()
        assertEquals(AutomaticCompactSendDecision.SendWithoutCompact, pending.await())
        assertFalse(prompt.pending.value)
    }

    @Test
    fun aSecondWaitAbortsTheFirst() = runBlocking {
        val prompt = AutomaticCompactSendPrompt()
        val first = async { prompt.awaitDecision() }
        prompt.pending.first { it }
        assertTrue(prompt.pending.value)
        val second = async { prompt.awaitDecision() }
        assertEquals(AutomaticCompactSendDecision.AbortSend, first.await())
        prompt.pending.first { it }
        prompt.skip()
        assertEquals(AutomaticCompactSendDecision.SendWithoutCompact, second.await())
    }
}
