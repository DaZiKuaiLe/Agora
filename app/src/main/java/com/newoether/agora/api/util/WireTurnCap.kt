package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.tool.CompactTurnIndexer
import com.newoether.agora.util.Constants
import java.io.File

/**
 * Message ids left out of the next send. Restored from app files on startup.
 * An old debug file may still start with a turn count and a notices flag; those lines are ignored.
 */
private val suppressedWireMessageIds = linkedSetOf<String>()
private var debugWireCapFile: File? = null
private var debugWireCapPersist = false

internal fun installDebugWireCapPersistence(directory: File) {
    debugWireCapFile = File(directory, "debug-wire-cap.txt")
    val parsed = debugWireCapFile?.takeIf { it.isFile }?.let { file ->
        runCatching { decodeDebugWireCap(file.readText()) }.getOrNull()
    }
    if (parsed != null) {
        synchronized(suppressedWireMessageIds) {
            suppressedWireMessageIds.clear()
            suppressedWireMessageIds.addAll(parsed)
        }
    }
    debugWireCapPersist = true
}

internal fun copySuppressedDialogueTurnIds(): Set<String> =
    synchronized(suppressedWireMessageIds) { suppressedWireMessageIds.toSet() }

internal fun setDialogueTurnSuppressed(messageId: String, suppressed: Boolean) {
    synchronized(suppressedWireMessageIds) {
        if (suppressed) suppressedWireMessageIds.add(messageId)
        else suppressedWireMessageIds.remove(messageId)
    }
    persistDebugWireCap()
}

internal fun encodeDebugWireCap(suppressedIds: Set<String>): String =
    suppressedIds.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.contains('\n') && it != DEBUG_NOTICES_ON && it != DEBUG_NOTICES_OFF }
        .distinct()
        .joinToString("\n")

internal fun decodeDebugWireCap(text: String): Set<String> {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val body = if (lines.firstOrNull()?.toIntOrNull() != null) lines.drop(1) else lines
    return body.filter { it != DEBUG_NOTICES_ON && it != DEBUG_NOTICES_OFF }.toSet()
}

private fun persistDebugWireCap() {
    if (!debugWireCapPersist) return
    val file = debugWireCapFile ?: return
    runCatching { file.writeText(encodeDebugWireCap(copySuppressedDialogueTurnIds())) }
}

internal fun contextMessagesWithinTurnCap(
    messages: List<ChatMessage>,
    contextTokenBudget: Int,
): List<ChatMessage> {
    val canonical = canonicalContextMessages(messages)
    return stripEmptyTurns(
        mergeConsecutiveSameRole(limitContext(canonical, contextTokenBudget)),
    )
}

/**
 * Drops unchecked messages. An unchecked user opening drops the rest of that turn.
 * An unchecked tool call also drops the tool-result rows that follow it.
 */
internal fun omitSuppressedDialogueTurns(
    messages: List<ChatMessage>,
    suppressedIds: Set<String> = copySuppressedDialogueTurnIds(),
): List<ChatMessage> {
    val muted = mutedWireMessageIds(messages, suppressedIds)
    if (muted.isEmpty() || messages.isEmpty()) return messages
    return messages.filter { it.id !in muted }
}

/** Ids that stay out of the send and render dimmed. An unchecked user opening mutes the rest of its turn. An unchecked tool call also mutes the tool-result rows that follow it. */
internal fun mutedWireMessageIds(
    messages: List<ChatMessage>,
    suppressedIds: Set<String>,
): Set<String> {
    if (suppressedIds.isEmpty()) return emptySet()
    val muted = HashSet(suppressedIds)
    val openingIndexes = dialogueTurnOpeningIndexes(messages)
    openingIndexes.forEachIndexed { index, start ->
        if (messages[start].id !in suppressedIds) return@forEachIndexed
        val end = openingIndexes.getOrNull(index + 1) ?: messages.size
        for (cursor in start until end) muted += messages[cursor].id
    }
    for (unit in protocolAtomicUnits(messages)) {
        val head = unit.firstOrNull() ?: continue
        if (head.id.startsWith(Constants.TOOL_MSG_PREFIX) && head.id in suppressedIds) {
            unit.forEach { muted += it.id }
        }
    }
    return muted
}

internal fun showsWireSendCheckbox(message: ChatMessage): Boolean =
    !message.id.startsWith(Constants.RESULT_MSG_PREFIX)

private fun dialogueTurnOpeningIndexes(messages: List<ChatMessage>): List<Int> =
    messages.indices.filter { index -> CompactTurnIndexer.isDialogueTurnOpening(messages[index]) }

private const val DEBUG_NOTICES_ON = "notices=1"
private const val DEBUG_NOTICES_OFF = "notices=0"
