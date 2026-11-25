package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Retrieves archived tool payloads (细节库) by detail_id. */
class DetailStoreToolProvider(
    private val store: DetailStore,
) : ToolProvider {
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = DetailArchive.QUERY_TOOL,
                description =
                    "查询细节库。当上下文里的工具结果已被替换为含 detail_id 与摘要的 detail_ref 时，" +
                        "可用此工具按 id 取回完整原文。先前成功的查询返回可能已从后续上下文省略（全文仍在细节库）。" +
                        "通常不需要调用：有用信息应已写进助手正文。" +
                        " Query the detail store by detail_id when a prior tool result was archived as a detail_ref.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "detail_id" to ToolProperty(
                            "string",
                            "细节 id（来自 detail_ref 的 detail_id 字段）。",
                        ),
                    ),
                    required = listOf("detail_id"),
                ),
            ),
        ),
    )

    override fun handles(name: String): Boolean = name == DetailArchive.QUERY_TOOL

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        }.getOrNull()
        val detailId = (args?.get("detail_id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (detailId.isEmpty()) {
            return buildJsonObject {
                put("type", "detail_store")
                put("error", "missing_detail_id")
            }.toString()
        }
        val record = store.get(detailId)
            ?: return buildJsonObject {
                put("type", "detail_store")
                put("detail_id", detailId)
                put("error", "not_found")
            }.toString()
        return buildJsonObject {
            put("type", "detail_store")
            put("detail_id", record.detailId)
            put("tool", record.toolName)
            put("summary", record.summary)
            put("payload", record.payload)
        }.toString()
    }
}
