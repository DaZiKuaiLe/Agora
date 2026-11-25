package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationLoopExitStatusTest {
    @Test
    fun generationLoopExitStatus_keepsCooperativeStopAndExistingSuccessRules() {
        data class Case(
            val name: String,
            val current: MessageStatus,
            val cooperative: Boolean,
            val inactive: Boolean,
            val hasContent: Boolean,
            val cancelled: Boolean,
            val expected: MessageStatus,
        )
        val cases = listOf(
            Case("cooperative with text is STOPPED", MessageStatus.SENDING, true, false, true, false, MessageStatus.STOPPED),
            Case("cooperative without text is STOPPED", MessageStatus.SENDING, true, false, false, false, MessageStatus.STOPPED),
            Case("natural text is SUCCESS", MessageStatus.SENDING, false, false, true, false, MessageStatus.SUCCESS),
            Case("empty turn is ERROR", MessageStatus.SENDING, false, false, false, false, MessageStatus.ERROR),
            Case("provider error stays ERROR", MessageStatus.ERROR, false, false, true, false, MessageStatus.ERROR),
            Case("job cancel after text is STOPPED", MessageStatus.SENDING, false, false, true, true, MessageStatus.STOPPED),
            Case("inactive coroutine with text still SUCCESS", MessageStatus.SENDING, false, true, true, false, MessageStatus.SUCCESS),
        )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                generationLoopExitStatus(
                    currentStatus = case.current,
                    endedAtCooperativeStop = case.cooperative,
                    coroutineInactive = case.inactive,
                    hasSuccessfulTurnContent = case.hasContent,
                    jobCancelled = case.cancelled,
                ),
            )
        }
    }

    @Test
    fun thoughtOnlyVendorCompletion_requiresRealThoughtsAndNoAnswer() {
        data class Case(
            val name: String,
            val answer: String,
            val thoughts: String,
            val hasImages: Boolean = false,
            val guidance: Boolean = false,
            val followUp: Boolean = false,
            val stopped: Boolean = false,
            val failed: Boolean = false,
            val cancelled: Boolean = false,
            val inactive: Boolean = false,
            val expected: Boolean,
        )
        val cases = listOf(
            Case("thought then stop", "", "reasoned", expected = true),
            Case("answer present", "done", "reasoned", expected = false),
            Case("placeholder only", "", "…", expected = false),
            Case("blank turn", "", "", expected = false),
            Case("image delivered", "", "reasoned", hasImages = true, expected = false),
            Case("guidance boundary", "", "reasoned", guidance = true, expected = false),
            Case("follow up", "", "reasoned", followUp = true, expected = false),
            Case("user stop", "", "reasoned", stopped = true, expected = false),
            Case("provider already failed", "", "reasoned", failed = true, expected = false),
            Case("cancelled", "", "reasoned", cancelled = true, expected = false),
            Case("inactive", "", "reasoned", inactive = true, expected = false),
        )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                isThoughtOnlyVendorCompletion(
                    answer = case.answer,
                    thoughts = case.thoughts,
                    thinkingPlaceholder = "…",
                    hasImages = case.hasImages,
                    endedAtGuidanceBoundary = case.guidance,
                    endedForFollowUp = case.followUp,
                    endedAtCooperativeStop = case.stopped,
                    providerAlreadyFailed = case.failed,
                    jobCancelled = case.cancelled,
                    coroutineInactive = case.inactive,
                ),
            )
        }
        assertTrue(
            isThoughtOnlyVendorCompletion(
                answer = "  ",
                thoughts = "reasoned",
                thinkingPlaceholder = "…",
                hasImages = false,
                endedAtGuidanceBoundary = false,
                endedForFollowUp = false,
                endedAtCooperativeStop = false,
                providerAlreadyFailed = false,
                jobCancelled = false,
                coroutineInactive = false,
            ),
        )
    }
}
