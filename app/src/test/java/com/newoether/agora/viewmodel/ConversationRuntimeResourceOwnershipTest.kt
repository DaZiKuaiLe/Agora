package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RuntimeRunIdentity
import com.newoether.agora.model.SlotReleaseReason
import com.newoether.agora.model.Transition
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeResourceOwnershipTest {
    @Test
    fun streamClear_commitsFinalMessageBeforeRemovingOverlay() {
        val resources = activeResources()
        val finalMessage = message(MessageStatus.SUCCESS)
        val events = mutableListOf<String>()
        resources.streamUpdate(OWNER_TOKEN, finalMessage)

        val committed = resources.streamMessageForClear(OWNER_TOKEN)!!
        assertEquals(finalMessage, resources.streamingMessage.value)
        assertEquals(finalMessage, committed)
        events += "commit"
        resources.clearStreamingMessage()

        events += "cleared"
        assertEquals(listOf("commit", "cleared"), events)
        assertNull(resources.streamingMessage.value)
    }

    @Test
    fun terminalStreamUpdate_replacesStaleAnsweringSnapshotBeforeClearCommit() {
        val resources = activeResources()
        val answering = message(MessageStatus.SENDING)
        val terminal = answering.copy(status = MessageStatus.SUCCESS)
        resources.streamUpdate(OWNER_TOKEN, answering)

        resources.streamUpdate(OWNER_TOKEN, terminal)
        val committed = resources.streamMessageForClear(OWNER_TOKEN)!!
        resources.clearStreamingMessage()

        assertEquals(MessageStatus.SUCCESS, committed.status)
        assertEquals(terminal, committed)
        assertNull(resources.streamingMessage.value)
    }

    @Test
    fun streamClear_commitsStoppedFinalMessageBeforeRemovingOverlay() {
        val resources = activeResources()
        val stopped = message(MessageStatus.STOPPED)
        resources.streamUpdate(OWNER_TOKEN, stopped)

        val committed = resources.streamMessageForClear(OWNER_TOKEN)!!
        resources.clearStreamingMessage()

        assertEquals(MessageStatus.STOPPED, committed.status)
        assertEquals(stopped, committed)
        assertNull(resources.streamingMessage.value)
    }

    @Test
    fun streamClear_staleTokenCannotTakeAForceStopOverlay() {
        val resources = activeResources()
        resources.streamUpdate(OWNER_TOKEN, message(MessageStatus.SENDING))
        val bound = RuntimeRunIdentity("conversation", OWNER_TOKEN, runId = "run")
        val stopping = RunState.Stopping(
            identity = bound,
            finalizationEffectId = "stop-1",
            coroutineSettled = false,
            persistenceSettled = false,
        )
        resources.applyMailboxEffects(
            Transition(stopping, listOf(RunEffect.CancelProviderPass(bound))),
            stopping,
        )

        assertNull(resources.streamMessageForClear(OWNER_TOKEN))
        assertEquals(MessageStatus.STOPPED, resources.streamingMessage.value?.status)
    }

    @Test
    fun generationJobAndStreamsHaveOneCancellableResourceOwner() {
        val resources = ConversationRuntimeResources()
        val installed = Job()
        val rejected = Job()
        var streamCancellations = 0
        resources.streamScope.register(GenerationCancelHandle { streamCancellations += 1 })

        assertTrue(resources.installGenerationJob(installed))
        assertFalse(resources.installGenerationJob(rejected))

        resources.cancelStreamsAnd(resources.currentGenerationJob())

        assertTrue(installed.isCancelled)
        assertFalse(rejected.isCancelled)
        assertEquals(1, streamCancellations)
        rejected.cancel()
    }

    @Test
    fun queuedGuidanceRemainsMemoryOnlyAndPreservesOrder() {
        val store = GuidanceLeaseStore { "lease" }
        val first = queued("one", "first")
        val second = queued("two", "second")

        store.enqueue(first)
        store.enqueue(second)

        assertEquals(listOf("one", "two"), store.queuedSends.value.map { it.id })
        val lease = store.claim()!!
        assertEquals(listOf(first, second), lease.batch)
        assertTrue(store.queuedSends.value.isEmpty())
        assertTrue(store.settle(lease.id, durable = true))
    }

    @Test
    fun guidanceClaimRevisionRecordsAClaimAfterTheVisibleQueueIsDrained() {
        val store = GuidanceLeaseStore { "lease" }
        val revision = store.currentClaimRevision()
        assertFalse(store.hasPendingOrClaimedSince(revision))

        store.enqueue(queued("one", "first"))
        assertTrue(store.hasPendingOrClaimedSince(revision))
        store.claim()

        assertTrue(store.queuedSends.value.isEmpty())
        assertEquals(1L, store.currentClaimRevision())
        assertTrue(store.hasPendingOrClaimedSince(revision))
    }

    @Test
    fun failedGuidanceLeaseReturnsTheExactBatchToTheFront() {
        val store = GuidanceLeaseStore { "lease" }
        val first = queued("one", "first")
        val second = queued("two", "second")
        store.enqueue(first)
        store.enqueue(second)

        val lease = store.claim()!!
        val newer = queued("three", "third")
        store.enqueue(newer)

        assertTrue(store.settle(lease.id, durable = false))
        assertEquals(listOf(first, second, newer), store.queuedSends.value)
        assertFalse(store.settle(lease.id, durable = false))
    }

    @Test
    fun disposalCleansPendingAndFailedInflightGuidanceOwnership() {
        val store = GuidanceLeaseStore { "lease" }
        val pendingFile = java.nio.file.Files.createTempFile("agora-pending", ".tmp").toFile()
        val claimedFile = java.nio.file.Files.createTempFile("agora-claimed", ".tmp").toFile()
        try {
            val pending = queued("pending", "pending", pendingFile.absolutePath)
            val claimed = queued("claimed", "claimed", claimedFile.absolutePath)
            store.enqueue(claimed)
            val lease = store.claim()!!
            store.enqueue(pending)

            store.disposePending().forEach(QueuedSend::deleteOwnedFiles)

            assertFalse(pendingFile.exists())
            assertTrue(claimedFile.exists())
            assertTrue(store.settle(lease.id, durable = false))
            assertFalse(claimedFile.exists())
        } finally {
            pendingFile.delete()
            claimedFile.delete()
        }
    }

    @Test
    fun stopAndSlotLifecycleDoNotCrashOnUnboundOrLeftoverOverlay() {
        data class Case(
            val name: String,
            val run: (ConversationRuntimeResources) -> Unit,
            val overlayStatus: MessageStatus?,
            val currentToken: Long,
        )
        val unbound = RuntimeRunIdentity("conversation", OWNER_TOKEN)
        val bound = RuntimeRunIdentity("conversation", OWNER_TOKEN, runId = "run")
        val sending = message(MessageStatus.SENDING)
        val cases = listOf(
            Case(
                name = "activate clears leftover overlay",
                run = { resources ->
                    resources.activate(unbound, loading = false)
                    resources.streamUpdate(OWNER_TOKEN, sending)
                    resources.activate(
                        RuntimeRunIdentity("conversation", OWNER_TOKEN + 1),
                        loading = false,
                    )
                },
                overlayStatus = null,
                currentToken = OWNER_TOKEN + 1,
            ),
            Case(
                name = "unbound Stop with overlay is accepted",
                run = { resources ->
                    resources.activate(unbound, loading = false)
                    resources.streamUpdate(OWNER_TOKEN, sending)
                    val stopping = RunState.Stopping(
                        identity = unbound,
                        finalizationEffectId = null,
                        coroutineSettled = false,
                        persistenceSettled = true,
                    )
                    resources.applyMailboxEffects(
                        Transition(stopping, listOf(RunEffect.CancelProviderPass(unbound))),
                        stopping,
                    )
                },
                overlayStatus = null,
                currentToken = OWNER_TOKEN + 1,
            ),
            Case(
                name = "bound Stop keeps a STOPPED overlay",
                run = { resources ->
                    resources.activate(bound, loading = false)
                    resources.streamUpdate(OWNER_TOKEN, sending)
                    val stopping = RunState.Stopping(
                        identity = bound,
                        finalizationEffectId = "stop-1",
                        coroutineSettled = false,
                        persistenceSettled = false,
                    )
                    resources.applyMailboxEffects(
                        Transition(stopping, listOf(RunEffect.CancelProviderPass(bound))),
                        stopping,
                    )
                },
                overlayStatus = MessageStatus.STOPPED,
                currentToken = OWNER_TOKEN + 1,
            ),
            Case(
                name = "duplicate Stop after token advance is ignored",
                run = { resources ->
                    resources.activate(bound, loading = false)
                    resources.streamUpdate(OWNER_TOKEN, sending)
                    val stopping = RunState.Stopping(
                        identity = bound,
                        finalizationEffectId = "stop-1",
                        coroutineSettled = false,
                        persistenceSettled = false,
                    )
                    val transition = Transition(
                        stopping,
                        listOf(RunEffect.CancelProviderPass(bound)),
                    )
                    resources.applyMailboxEffects(transition, stopping)
                    resources.applyMailboxEffects(transition, stopping)
                },
                overlayStatus = MessageStatus.STOPPED,
                currentToken = OWNER_TOKEN + 1,
            ),
            Case(
                name = "release clears overlay",
                run = { resources ->
                    resources.activate(bound, loading = false)
                    resources.streamUpdate(OWNER_TOKEN, sending)
                    val idle = RunState.Idle("conversation")
                    resources.applyMailboxEffects(
                        Transition(
                            idle,
                            listOf(RunEffect.ReleaseSlot(bound, SlotReleaseReason.EMPTY_STOP)),
                        ),
                        idle,
                    )
                },
                overlayStatus = null,
                currentToken = OWNER_TOKEN,
            ),
        )
        cases.forEach { case ->
            val resources = ConversationRuntimeResources()
            case.run(resources)
            assertEquals(case.name, case.overlayStatus, resources.streamingMessage.value?.status)
            assertTrue(case.name, resources.isCurrentToken(case.currentToken))
        }
    }

    @Test
    fun cooperativeStopRequest_marksOnceAndClearsOnActivateReleaseOrForceStop() {
        data class Case(
            val name: String,
            val run: (ConversationRuntimeResources) -> Boolean,
            val accepted: Boolean,
            val requested: Boolean,
        )
        val bound = RuntimeRunIdentity("conversation", OWNER_TOKEN, runId = "run")
        val cases = listOf(
            Case(
                name = "idle cannot request",
                run = { it.markCooperativeStopRequested() },
                accepted = false,
                requested = false,
            ),
            Case(
                name = "active marks once",
                run = { resources ->
                    resources.activate(bound, loading = true)
                    resources.markCooperativeStopRequested()
                },
                accepted = true,
                requested = true,
            ),
            Case(
                name = "second mark is a no-op",
                run = { resources ->
                    resources.activate(bound, loading = true)
                    resources.markCooperativeStopRequested()
                    resources.markCooperativeStopRequested()
                },
                accepted = false,
                requested = true,
            ),
            Case(
                name = "consume takes the request",
                run = { resources ->
                    resources.activate(bound, loading = true)
                    resources.markCooperativeStopRequested()
                    resources.consumeCooperativeStopRequested()
                },
                accepted = true,
                requested = false,
            ),
            Case(
                name = "consume when unset is false",
                run = { resources ->
                    resources.activate(bound, loading = true)
                    resources.consumeCooperativeStopRequested()
                },
                accepted = false,
                requested = false,
            ),
            Case(
                name = "activate clears leftover",
                run = { resources ->
                    resources.activate(bound, loading = true)
                    resources.markCooperativeStopRequested()
                    resources.activate(
                        RuntimeRunIdentity("conversation", OWNER_TOKEN + 1, runId = "next"),
                        loading = true,
                    )
                    resources.markCooperativeStopRequested()
                },
                accepted = true,
                requested = true,
            ),
            Case(
                name = "release clears leftover",
                run = { resources ->
                    resources.activate(bound, loading = true)
                    resources.markCooperativeStopRequested()
                    val idle = RunState.Idle("conversation")
                    resources.applyMailboxEffects(
                        Transition(
                            idle,
                            listOf(RunEffect.ReleaseSlot(bound, SlotReleaseReason.EMPTY_STOP)),
                        ),
                        idle,
                    )
                    resources.markCooperativeStopRequested()
                },
                accepted = false,
                requested = false,
            ),
            Case(
                name = "force stop clears leftover",
                run = { resources ->
                    resources.activate(bound, loading = true)
                    resources.markCooperativeStopRequested()
                    val stopping = RunState.Stopping(
                        identity = bound,
                        finalizationEffectId = "stop-1",
                        coroutineSettled = false,
                        persistenceSettled = false,
                    )
                    resources.applyMailboxEffects(
                        Transition(stopping, listOf(RunEffect.CancelProviderPass(bound))),
                        stopping,
                    )
                    resources.markCooperativeStopRequested()
                },
                accepted = false,
                requested = false,
            ),
        )
        cases.forEach { case ->
            val resources = ConversationRuntimeResources()
            val accepted = case.run(resources)
            assertEquals(case.name, case.accepted, accepted)
            assertEquals(case.name, case.requested, resources.cooperativeStopRequested.value)
        }
    }

    @Test
    fun durableGuidanceLeaseTransfersFilesToRoomEvenAfterDisposal() {
        val store = GuidanceLeaseStore { "lease" }
        val durableFile = java.nio.file.Files.createTempFile("agora-durable", ".tmp").toFile()
        store.enqueue(queued("durable", "durable", durableFile.absolutePath))
        val lease = store.claim()!!
        store.disposePending()

        try {
            assertTrue(store.settle(lease.id, durable = true))
            assertTrue(durableFile.exists())
        } finally {
            durableFile.delete()
        }
    }

    private fun activeResources(): ConversationRuntimeResources =
        ConversationRuntimeResources().also { resources ->
            resources.activate(
                RuntimeRunIdentity("conversation", OWNER_TOKEN),
                loading = false,
            )
        }

    private fun message(status: MessageStatus) = ChatMessage(
        id = "model",
        text = "complete",
        participant = Participant.MODEL,
        status = status,
    )

    private fun queued(id: String, text: String, ownedPath: String? = null) = QueuedSend(
        id = id,
        text = text,
        modelId = "model",
        attachments = emptyList(),
        runId = "old-run",
        preparedOwnedPaths = listOfNotNull(ownedPath),
    )

    private companion object {
        const val OWNER_TOKEN = 1L
    }
}
