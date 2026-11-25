package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AutomaticCompactSendDecision {
    CompactThenSend,
    SendWithoutCompact,
    AbortSend,
}

/** Holds one in-flight interactive send until the user confirms or skips automatic compact. */
internal class AutomaticCompactSendPrompt {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()
    private var deferred: CompletableDeferred<AutomaticCompactSendDecision>? = null

    suspend fun awaitDecision(): AutomaticCompactSendDecision {
        deferred?.complete(AutomaticCompactSendDecision.AbortSend)
        val next = CompletableDeferred<AutomaticCompactSendDecision>()
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

    fun confirm() = complete(AutomaticCompactSendDecision.CompactThenSend)

    fun skip() = complete(AutomaticCompactSendDecision.SendWithoutCompact)

    fun abort() = complete(AutomaticCompactSendDecision.AbortSend)

    private fun complete(decision: AutomaticCompactSendDecision) {
        deferred?.complete(decision)
    }
}
