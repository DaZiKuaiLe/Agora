package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Lets tools ask the UI to prompt for all-files / legacy shared-storage access, then
 * suspend until the user finishes (or dismisses) the flow.
 *
 * Unlike [ShellConfirmationController], visiting system Settings backgrounds the app, so this
 * controller must not treat backgrounding as an automatic refusal.
 */
class AllFilesAccessController(
    private val appContext: Context,
    private val appForeground: StateFlow<Boolean> = AppForegroundTracker.foreground,
) {
    enum class Outcome {
        GRANTED,
        DENIED,
        DISMISSED,
        UNAVAILABLE,
    }

    data class PendingRequest(
        val reason: String?,
        val deferred: CompletableDeferred<Outcome>,
    )

    private val _pendingRequest = MutableStateFlow<PendingRequest?>(null)
    val pendingRequest: StateFlow<PendingRequest?> = _pendingRequest.asStateFlow()

    private val promptMutex = Mutex()

    fun isGranted(): Boolean = SandboxSharedStorageAccess.isGranted(appContext)

    /**
     * Suspends until the UI resolves the request. Returns immediately when already granted.
     * [Outcome.UNAVAILABLE] when there is no foreground UI to show a prompt.
     */
    suspend fun request(reason: String? = null): Outcome {
        if (isGranted()) return Outcome.GRANTED
        if (!appForeground.value) return Outcome.UNAVAILABLE
        return promptMutex.withLock {
            if (isGranted()) return@withLock Outcome.GRANTED
            if (!appForeground.value) return@withLock Outcome.UNAVAILABLE
            val deferred = CompletableDeferred<Outcome>()
            _pendingRequest.value = PendingRequest(reason?.trim()?.ifBlank { null }, deferred)
            try {
                withTimeout(Constants.SHELL_CONFIRM_TIMEOUT_MS) {
                    deferred.await()
                }
            } catch (_: TimeoutCancellationException) {
                Outcome.UNAVAILABLE
            } finally {
                if (_pendingRequest.value?.deferred === deferred) {
                    _pendingRequest.value = null
                }
            }
        }
    }

    /** User chose "Not now" / dismissed the in-app dialog without opening Settings. */
    fun dismiss() {
        val pending = _pendingRequest.value ?: return
        pending.deferred.complete(Outcome.DISMISSED)
        _pendingRequest.value = null
    }

    /**
     * Called after the Settings / legacy permission activity returns. Resolves with
     * [Outcome.GRANTED] or [Outcome.DENIED] based on the current grant state.
     */
    fun resolveAfterSettingsAttempt() {
        val pending = _pendingRequest.value ?: return
        val outcome = if (isGranted()) Outcome.GRANTED else Outcome.DENIED
        pending.deferred.complete(outcome)
        _pendingRequest.value = null
    }
}
