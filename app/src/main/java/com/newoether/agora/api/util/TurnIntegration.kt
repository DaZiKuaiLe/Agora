package com.newoether.agora.api.util

import com.newoether.agora.data.TimeBandPolicy
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.CompactTurn
import com.newoether.agora.tool.CompactTurnIndexer
import com.newoether.agora.tool.isLiveTail
import com.newoether.agora.util.Constants
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class TurnIntegrationsPayload(
    val type: String = TimeBandPolicy.TURN_INTEGRATIONS_TYPE,
    val turns: List<TurnIntegrationItem>,
)

@Serializable
internal data class TurnIntegrationItem(
    val turn_id: String,
    val integration: String,
)

@Serializable
internal data class TurnIntegrationPayload(
    val type: String = TimeBandPolicy.TURN_INTEGRATION_TYPE,
    val turn_id: String,
    val integration: String,
)

private val turnIntegrationJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun encodeTurnIntegrationsJson(turns: List<TurnIntegrationItem>): String =
    turnIntegrationJson.encodeToString(
        TurnIntegrationsPayload(
            type = TimeBandPolicy.TURN_INTEGRATIONS_TYPE,
            turns = turns,
        ),
    )

internal fun encodeTurnIntegrationJson(turnId: String, integration: String): String =
    encodeTurnIntegrationsJson(
        listOf(TurnIntegrationItem(turn_id = turnId, integration = integration)),
    )

internal fun formatTurnIntegrationForReading(text: String): String? {
    val trimmed = text.trim()
    parseTurnIntegrations(trimmed)?.let { payload ->
        if (payload.turns.isEmpty()) return null
        return buildString {
            payload.turns.forEachIndexed { index, item ->
                if (index > 0) append("\n\n")
                append("======== 轮 ")
                append(index + 1)
                append(" / turn_id=")
                append(item.turn_id)
                append(" ========\n\n")
                append(item.integration.trim())
                append("\n\n（原文在细节库；需要时 query_detail_store，detail_id 用上面的 turn_id）")
            }
        }
    }
    val legacy = runCatching {
        turnIntegrationJson.decodeFromString<TurnIntegrationPayload>(trimmed)
    }.getOrNull() ?: return null
    if (legacy.type != TimeBandPolicy.TURN_INTEGRATION_TYPE || legacy.integration.isBlank()) {
        return null
    }
    return legacy.integration.trim()
}

internal fun parseTurnIntegrations(text: String): TurnIntegrationsPayload? {
    val payload = runCatching {
        turnIntegrationJson.decodeFromString<TurnIntegrationsPayload>(text.trim())
    }.getOrNull() ?: return null
    if (payload.type != TimeBandPolicy.TURN_INTEGRATIONS_TYPE || payload.turns.isEmpty()) {
        return null
    }
    return payload
}

internal fun compressionRatio(originalTokens: Int, integratedTokens: Int): Double? {
    if (originalTokens <= 0 || integratedTokens <= 0) return null
    return (kotlin.math.round(integratedTokens.toDouble() / originalTokens.toDouble() * 100.0) / 100.0)
}

internal fun estimateIntegratedTurnTokens(integration: String): Int =
    ContextTokenEstimator.estimateMessage(
        ChatMessage(
            text = integration.trim(),
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        ),
    )

internal fun formatTurnOriginals(
    turn: CompactTurn,
    includeHeading: Boolean = true,
): String = buildString {
    if (includeHeading) {
        append("--- turn ")
        append(turn.turnId)
        append(" ---\n")
    }
    turn.messages.forEach { message ->
        append('\n')
        when {
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                append("[Assistant tool request]\n")
                append(message.text)
            }
            message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                append("[Tool result]\n")
                append(message.text)
            }
            message.participant == Participant.USER -> {
                append("[User]\n")
                append(message.text)
            }
            else -> {
                append("[Assistant]\n")
                append(message.text)
            }
        }
        append('\n')
    }
}

/**
 * Replace consecutive finished overflow turns with one USER row whose body is
 * `turn_integrations` JSON. Locked turns, cache misses, and the retain window stay originals.
 * Compact capsules and other non-turn rows keep their original positions.
 */
internal fun applyIntegrationProjection(
    messages: List<ChatMessage>,
    retainTurns: Int,
): List<ChatMessage> {
    val turns = CompactTurnIndexer.indexDialogueTurns(messages)
    if (turns.isEmpty()) return messages

    val finishedTurns = if (turns.last().isLiveTail()) turns.dropLast(1) else turns
    if (finishedTurns.isEmpty()) return messages

    val retain = retainTurns.coerceAtLeast(0)
    val retainFrom = (finishedTurns.size - retain).coerceAtLeast(0)
    val replacements = linkedMapOf<String, ChatMessage>()
    val hiddenIds = mutableSetOf<String>()
    val pending = mutableListOf<TurnIntegrationItem>()
    var pendingFirstMessageId: String? = null
    var pendingTimestamp = 0L
    val pendingMessageIds = mutableListOf<String>()

    fun flushPending() {
        if (pending.isEmpty()) return
        val firstId = pendingFirstMessageId ?: return
        replacements[firstId] = ChatMessage(
            id = "${TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX}${pending.first().turn_id}",
            text = encodeTurnIntegrationsJson(pending.toList()),
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = pendingTimestamp,
            turnId = pending.first().turn_id,
        )
        hiddenIds += pendingMessageIds.filter { it != firstId }
        pending.clear()
        pendingFirstMessageId = null
        pendingMessageIds.clear()
    }

    finishedTurns.forEachIndexed { index, turn ->
        val inRetain = index >= retainFrom
        val body = turn.integrateText?.trim().orEmpty()
        if (inRetain || turn.locked || body.isEmpty()) {
            flushPending()
            return@forEachIndexed
        }
        val first = turn.messages.firstOrNull(CompactTurnIndexer::isDialogueTurnOpening)
            ?: turn.messages.firstOrNull()
            ?: return@forEachIndexed
        if (pending.isEmpty()) {
            pendingFirstMessageId = first.id
            pendingTimestamp = first.timestamp
        }
        pending += TurnIntegrationItem(turn_id = turn.turnId, integration = body)
        pendingMessageIds += turn.messages.map { it.id }
    }
    flushPending()
    if (replacements.isEmpty()) return messages

    return messages.mapNotNull { message ->
        when {
            replacements.containsKey(message.id) -> replacements[message.id]
            message.id in hiddenIds -> null
            else -> message
        }
    }
}
