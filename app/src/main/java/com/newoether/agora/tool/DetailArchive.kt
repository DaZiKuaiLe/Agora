package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.StreamingToolArgumentHintResolver
import com.newoether.agora.ui.chat.message.ToolKind
import com.newoether.agora.ui.chat.message.ToolPresentation
import com.newoether.agora.ui.chat.message.ToolPresentationResolver
import com.newoether.agora.ui.chat.message.ToolPresentationState
import com.newoether.agora.ui.chat.message.toolSummaryText
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool 去细节化：发送前把历史长工具结果换成 detail_ref（原文进细节库）。
 * stub 的 summary 与会话折叠卡片同一句；命令行失败也归档，不把 stdout 留在上下文。
 * query_detail_store 成功返回不入细节库：live 之外改为一句省略说明。
 * 与总结式按批压缩不同，但不调用总结模型。失败结果（顶层 error）不去细节化。
 */
object DetailArchive {
    const val REF_TYPE = "detail_ref"
    const val QUERY_TOOL = "query_detail_store"
    const val QUERY_OMITTED_TYPE = "detail_store_omitted"

    private val ARCHIVEABLE = setOf(
        "web_search",
        "web_fetch",
        "openai_search",
        "google_search",
        "execute_local_command",
        "execute_remote_command",
        ProcessTools.START_LOCAL,
        ProcessTools.START_REMOTE,
        ProcessTools.GET,
        ProcessTools.WAIT,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun isArchiveableTool(name: String?): Boolean =
        name != null && name in ARCHIVEABLE

    fun isArchiveableShell(name: String?): Boolean =
        name == "execute_local_command" ||
            name == "execute_remote_command" ||
            name in ProcessTools.START_NAMES

    fun isDetailRef(payload: String?): Boolean {
        if (payload.isNullOrBlank()) return false
        return payloadType(payload) == REF_TYPE
    }

    /** Prior query_detail_store success already replaced by the one-line omit notice. */
    fun isQueryLookupOmitted(payload: String?): Boolean {
        if (payload.isNullOrBlank()) return false
        return payloadType(payload) == QUERY_OMITTED_TYPE
    }

    /** Top-level tool-framework failure — keep short payload in context. */
    fun isFailedToolPayload(payload: String?): Boolean {
        if (payload.isNullOrBlank()) return false
        return runCatching {
            json.parseToJsonElement(payload).jsonObject.containsKey("error")
        }.getOrDefault(false)
    }

    fun detailIdFromRef(payload: String?): String? {
        if (payload.isNullOrBlank() || !isDetailRef(payload)) return null
        return runCatching {
            val raw = json.parseToJsonElement(payload).jsonObject["detail_id"]
            (raw as? JsonPrimitive)?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Collect tool-level detail ids already present as detail_ref in [messages]. */
    fun collectToolDetailIds(messages: List<ChatMessage>): Set<String> {
        val ids = linkedSetOf<String>()
        for (message in messages) {
            message.segments?.forEach { segment ->
                if (segment.type == "tool") {
                    detailIdFromRef(segment.toolResult)?.let(ids::add)
                }
            }
            message.toolCall?.let { call ->
                detailIdFromRef(call.result)?.let(ids::add)
            }
            if (message.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                detailIdFromRef(message.text)?.let(ids::add)
            }
        }
        return ids
    }

    /**
     * After a summary batch is stored under [batchId], drop tool-level detail copies for
     * [toolDetailIds] so the store does not keep duplicate payloads.
     */
    fun deleteToolDetailsAfterBatchArchive(
        store: DetailStore,
        toolDetailIds: Collection<String>,
        batchId: String,
    ) {
        val batchKey = DetailStore.sanitizeId(batchId)
        for (raw in toolDetailIds) {
            val id = DetailStore.sanitizeId(raw) ?: continue
            if (id == batchKey) continue
            store.delete(id)
        }
    }

    fun applyStubs(
        messages: List<ChatMessage>,
        store: DetailStore,
        context: Context,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val liveFrom = messages.indexOfLast { it.isDetailLiveAnchor() }.coerceAtLeast(0)
        return messages.mapIndexed { index, message ->
            stubMessage(
                message = message,
                store = store,
                context = context,
                storedLiveOnly = index >= liveFrom,
            )
        }
    }

    /**
     * Write archiveable successful tool payloads into [store] without changing Room/UI rows.
     * Includes the current live tail so a later [applyStubs] can stub without waiting for send.
     * @return unique toolCallIds that were stored.
     */
    fun persistEligibleTools(
        messages: List<ChatMessage>,
        store: DetailStore,
        context: Context,
    ): Set<String> {
        val ids = linkedSetOf<String>()
        for (message in messages) {
            visitArchiveablePayloads(message) { toolName, arguments, payload, preferredId ->
                val rewritten = rewritePayload(
                    toolName = toolName,
                    arguments = arguments,
                    payload = payload,
                    preferredId = preferredId,
                    store = store,
                    context = context,
                    storedLiveOnly = false,
                )
                if (rewritten != null) {
                    DetailStore.sanitizeId(preferredId)?.let(ids::add)
                }
            }
        }
        return ids
    }

    private fun ChatMessage.isDetailLiveAnchor(): Boolean {
        if (participant != Participant.USER) return false
        if (id.startsWith(Constants.TOOL_MSG_PREFIX)) return false
        if (id.startsWith(Constants.RESULT_MSG_PREFIX)) return false
        if (id.startsWith(Constants.COMPACT_MSG_PREFIX)) return false
        if (id.startsWith("context_summary_")) return false
        return true
    }

    private fun stubMessage(
        message: ChatMessage,
        store: DetailStore,
        context: Context,
        storedLiveOnly: Boolean,
    ): ChatMessage {
        val segments = message.segments
        val stubbedSegments = segments?.map { segment ->
            if (segment.type != "tool") return@map segment
            val payload = segment.toolResult ?: return@map segment
            val rewritten = rewritePayload(
                toolName = segment.toolName,
                arguments = segment.toolArgs,
                payload = payload,
                preferredId = segment.toolCallId,
                store = store,
                context = context,
                storedLiveOnly = storedLiveOnly,
            ) ?: return@map segment
            segment.copy(toolResult = rewritten, toolResultText = rewritten)
        }
        val toolCall = message.toolCall?.let { call ->
            val rewritten = rewritePayload(
                toolName = call.toolName,
                arguments = call.arguments,
                payload = call.result,
                preferredId = call.toolCallId,
                store = store,
                context = context,
                storedLiveOnly = storedLiveOnly,
            ) ?: return@let call
            call.copy(result = rewritten, resultText = rewritten)
        }
        val nextText = if (message.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
            rewritePayload(
                toolName = toolCall?.toolName
                    ?: stubbedSegments?.lastOrNull { it.type == "tool" }?.toolName,
                arguments = toolCall?.arguments
                    ?: stubbedSegments?.lastOrNull { it.type == "tool" }?.toolArgs,
                payload = message.text,
                preferredId = toolCall?.toolCallId
                    ?: stubbedSegments?.lastOrNull { it.type == "tool" }?.toolCallId,
                store = store,
                context = context,
                storedLiveOnly = storedLiveOnly,
            ) ?: message.text
        } else {
            message.text
        }
        if (stubbedSegments === segments && toolCall === message.toolCall && nextText === message.text) {
            return message
        }
        return message.copy(
            text = nextText,
            segments = stubbedSegments,
            toolCall = toolCall,
        )
    }

    private fun visitArchiveablePayloads(
        message: ChatMessage,
        visit: (toolName: String?, arguments: String?, payload: String?, preferredId: String?) -> Unit,
    ) {
        message.segments?.forEach { segment ->
            if (segment.type == "tool") {
                visit(segment.toolName, segment.toolArgs, segment.toolResult, segment.toolCallId)
            }
        }
        message.toolCall?.let { call ->
            visit(call.toolName, call.arguments, call.result, call.toolCallId)
        }
        if (message.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
            val tool = message.toolCall
            val segment = message.segments?.lastOrNull { it.type == "tool" }
            visit(
                tool?.toolName ?: segment?.toolName,
                tool?.arguments ?: segment?.toolArgs,
                message.text,
                tool?.toolCallId ?: segment?.toolCallId,
            )
        }
    }

    /**
     * @return rewritten payload, or null to keep the original.
     */
    private fun rewritePayload(
        toolName: String?,
        arguments: String?,
        payload: String?,
        preferredId: String?,
        store: DetailStore,
        context: Context,
        storedLiveOnly: Boolean,
    ): String? {
        if (payload.isNullOrBlank()) return null
        if (toolName == QUERY_TOOL) {
            if (storedLiveOnly) return null
            if (isFailedToolPayload(payload) || isQueryLookupOmitted(payload)) return null
            return omitQueryLookupPayload(payload, arguments, context)
        }
        if (!isArchiveableTool(toolName)) return null
        if (isDetailRef(payload)) return null
        if (isFailedToolPayload(payload) && !isArchiveableShell(toolName)) return null
        if (storedLiveOnly) {
            val id = DetailStore.sanitizeId(preferredId) ?: return null
            if (store.get(id) == null) return null
        }
        return archiveAndStub(
            toolName = toolName.orEmpty(),
            arguments = arguments,
            payload = payload,
            preferredId = preferredId,
            store = store,
            context = context,
        )
    }

    private fun omitQueryLookupPayload(
        payload: String,
        arguments: String?,
        context: Context,
    ): String {
        val id = detailIdFromJsonField(payload, "detail_id")
            ?: detailIdFromJsonField(arguments, "detail_id")
            ?: ""
        val note = if (id.isNotEmpty()) {
            context.getString(R.string.detail_store_query_omitted, id)
        } else {
            context.getString(R.string.detail_store_query_omitted_no_id)
        }
        return buildJsonObject {
            put("type", QUERY_OMITTED_TYPE)
            if (id.isNotEmpty()) put("detail_id", id)
            put("note", note)
        }.toString()
    }

    private fun payloadType(payload: String): String? = runCatching {
        json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun detailIdFromJsonField(raw: String?, key: String): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(raw).jsonObject[key]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * @return stub JSON, or null to keep the original payload (e.g. missing stable toolCallId).
     */
    private fun archiveAndStub(
        toolName: String,
        arguments: String?,
        payload: String,
        preferredId: String?,
        store: DetailStore,
        context: Context,
    ): String? {
        // Prefer a stable toolCallId so query_detail_store matches the stub; skip if absent
        // rather than minting a random id the model will not discover.
        val id = DetailStore.sanitizeId(preferredId) ?: return null
        val summary = DetailSummarizer.summarize(
            context = context,
            toolName = toolName,
            argumentsJson = arguments,
            resultJson = payload,
        )
        val record = store.put(
            toolName = toolName,
            summary = summary,
            payload = payload,
            preferredId = id,
        )
        return buildJsonObject {
            put("type", REF_TYPE)
            put("detail_id", record.detailId)
            put("tool", toolName)
            put("summary", summary)
            put(
                "hint",
                "Details are stored in the detail store. Call $QUERY_TOOL with detail_id if needed (usually unnecessary).",
            )
        }.toString()
    }
}

/** Builds the same collapsed-card one-liner the chat UI shows. */
internal object DetailSummarizer {
    fun summarize(
        context: Context,
        toolName: String,
        argumentsJson: String?,
        resultJson: String?,
    ): String {
        val kind = ToolPresentationResolver.kindForToolName(toolName)
        val hints = StreamingToolArgumentHintResolver.resolve(kind, argumentsJson)
        val failedPrefix = resultJson?.takeIf { it.startsWith("Error", ignoreCase = true) }
        return toolSummaryText(
            context,
            ToolPresentation(
                toolName = toolName,
                kind = kind,
                state = if (failedPrefix != null) {
                    ToolPresentationState.FAILED
                } else {
                    ToolPresentationState.COMPLETED
                },
                arguments = null,
                result = null,
                rawArguments = null,
                rawResult = null,
                rawTextResult = null,
                rawStructuredResult = null,
                liveOutput = null,
                subject = hints.subject,
                device = hints.server,
                count = null,
                errorMessage = failedPrefix?.take(160),
                exitCode = null,
                jobId = null,
                outputLength = null,
            ),
        )
    }
}
