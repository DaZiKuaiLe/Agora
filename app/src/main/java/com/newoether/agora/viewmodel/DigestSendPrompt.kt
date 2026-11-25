package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DigestSendDecision {
    UpdateThenSend,
    AbortSend,
}

/** Holds one in-flight interactive send until the user updates the conversation summary or aborts. */
internal class DigestSendPrompt {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()
    private var deferred: CompletableDeferred<DigestSendDecision>? = null

    suspend fun awaitDecision(): DigestSendDecision {
        deferred?.complete(DigestSendDecision.AbortSend)
        val next = CompletableDeferred<DigestSendDecision>()
        deferred = next
        _pending.value = true
        return try {
            next.await()
        } finally {
            if (deferred === next) {
                deferred = null
                _pending.value = false
            }
        }
    }

    fun confirm() = complete(DigestSendDecision.UpdateThenSend)

    fun abort() = complete(DigestSendDecision.AbortSend)

    private fun complete(decision: DigestSendDecision) {
        deferred?.complete(decision)
    }
}

internal enum class CompactFormatSendDecision {
    RecompactThenSend,
    AbortSend,
}

/** Holds one in-flight send until the user fully recompresses under the current format or aborts. */
internal class CompactFormatSendPrompt {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()
    private var deferred: CompletableDeferred<CompactFormatSendDecision>? = null

    suspend fun awaitDecision(): CompactFormatSendDecision {
        deferred?.complete(CompactFormatSendDecision.AbortSend)
        val next = CompletableDeferred<CompactFormatSendDecision>()
        deferred = next
        _pending.value = true
        return try {
            next.await()
        } finally {
            if (deferred === next) {
                deferred = null
                _pending.value = false
            }
        }
    }

    fun confirm() = complete(CompactFormatSendDecision.RecompactThenSend)

    fun abort() = complete(CompactFormatSendDecision.AbortSend)

    private fun complete(decision: CompactFormatSendDecision) {
        deferred?.complete(decision)
    }
}
