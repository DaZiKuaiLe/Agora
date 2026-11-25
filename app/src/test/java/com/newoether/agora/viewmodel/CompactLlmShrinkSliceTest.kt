package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactLlmShrinkSliceTest {
    @Test
    fun compactLlmShouldShrinkSlice_onlyWindowOverflowAndOutputCap() {
        data class Case(
            val name: String,
            val error: GenerationError,
            val shrink: Boolean,
        )
        val cases = listOf(
            Case(
                "output token cap",
                GenerationError.OutputTruncated("openai", "max_tokens"),
                true,
            ),
            Case(
                "openai request exceeds window including completion",
                GenerationError.Api(
                    code = "invalid_request_error",
                    type = "invalid_request_error",
                    message = "This model's maximum context length is 1048576 tokens. " +
                        "However, you requested 1152372 tokens " +
                        "(890228 in the messages, 262144 in the completion). " +
                        "Please reduce the length of the messages or completion.",
                ),
                true,
            ),
            Case(
                "anthropic prompt too long",
                GenerationError.Api("invalid_request_error", null, "prompt is too long: 200000 tokens"),
                true,
            ),
            Case(
                "network 400 with context_length code in body",
                GenerationError.Network(400, "context_length_exceeded: reduce the length of the prompt"),
                true,
            ),
            Case(
                "auth is not overflow",
                GenerationError.Network(401, "Unauthorized"),
                false,
            ),
            Case(
                "rate limit is not overflow",
                GenerationError.Api("rate_limit", "rate_limit_error", "Too many requests"),
                false,
            ),
            Case(
                "timeout is not overflow",
                GenerationError.Timeout,
                false,
            ),
            Case(
                "generic invalid request is not overflow",
                GenerationError.Api("invalid_request_error", "invalid_request_error", "Missing required field"),
                false,
            ),
        )
        for (case in cases) {
            assertEquals(case.name, case.shrink, compactLlmShouldShrinkSlice(case.error))
        }
    }
}
