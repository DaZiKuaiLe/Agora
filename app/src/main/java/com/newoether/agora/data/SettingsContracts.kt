package com.newoether.agora.data

import androidx.datastore.preferences.core.Preferences
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.SecretCrypto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val key: String,
    val provider: String = Constants.PROVIDER_GOOGLE
)

@Serializable
data class ShellDeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "conch",          // "conch" | "ssh"
    // Conch fields (type=conch)
    val serverUrl: String = "",
    val apiKey: String = "",
    val conchPublicKey: String = "",
    // SSH fields (type=ssh)
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    // Pinned SSH host key (base64 of the server public-key blob). Blank = not yet
    // pinned (trust-on-first-use); once set, connections must match or are rejected.
    val sshHostKey: String = ""
)

@Serializable
enum class McpTransportType {
    @SerialName("streamable_http")
    STREAMABLE_HTTP,

    @SerialName("sse")
    SSE,
}

@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val url: String = "",
    val transport: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val headers: Map<String, String> = emptyMap(),
    /** Raw MCP tool names disabled for this server. New tools stay enabled by default. */
    val disabledTools: Set<String> = emptySet(),
)

@Serializable
data class SystemPromptEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String = "",
    val systemItems: List<PromptTemplateItem> = emptyList(),
    val userPrependItems: List<PromptTemplateItem> = emptyList(),
    val userPostpendItems: List<PromptTemplateItem> = emptyList()
) {
    val resolvedSystemItems: List<PromptTemplateItem>
        get() = if (systemItems.isNotEmpty()) systemItems
        else if (content.isNotBlank()) listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = content))
        else emptyList()
}

internal val WEB_SEARCH_PROVIDERS = setOf(
    "baidu", "zhipu", "duckduckgo", "brave", "kagi", "serper", "tavily", "searxng",
)

internal fun normalizeWebSearchProvider(provider: String?): String =
    provider?.trim()?.lowercase()?.takeIf(WEB_SEARCH_PROVIDERS::contains) ?: "zhipu"

internal fun decodeWebSearchApiKeys(preferences: Preferences, json: Json): Map<String, String> {
    val raw = SecretCrypto.decrypt(preferences[WEB_SEARCH_API_KEYS_JSON] ?: "{}")
    return try {
        json.decodeFromString<Map<String, String>>(raw)
    } catch (error: Exception) {
        DebugLog.e("SettingsManager", "Failed to decode webSearchApiKeys", error)
        emptyMap()
    }
}



internal fun decodeConversationToolState(
    preferences: Preferences,
    json: Json,
): Map<String, ConversationToolState> = try {
    json.decodeFromString(preferences[CONVERSATION_SETTINGS_JSON] ?: "{}")
} catch (_: Exception) {
    emptyMap()
}

internal fun decodeEncryptedShellDevices(preferences: Preferences, json: Json): List<ShellDeviceConfig> {
    val raw = SecretCrypto.decrypt(preferences[SHELL_DEVICES_JSON] ?: "[]")
    return runCatching { json.decodeFromString<List<ShellDeviceConfig>>(raw) }.getOrDefault(emptyList())
}

/**
 * Session-scoped tool switches only. These are "is this tool enabled for the current
 * conversation" flags (global gate → per-conversation toggle), not sampling or thinking
 * parameters. Sampling/thinking/context parameters live in [ModelGenerationSettings] and
 * follow the model, never a conversation.
 */
@Serializable
data class ConversationToolState(
    val codeExecutionEnabled: Boolean? = null,
    val googleSearchEnabled: Boolean? = null,
    val openAiWebSearchEnabled: Boolean? = null,
    val webSearchEnabled: Boolean? = null,
    val shellEnabled: Boolean? = null
) {
    fun isAllNull() = codeExecutionEnabled == null && googleSearchEnabled == null
        && openAiWebSearchEnabled == null && webSearchEnabled == null && shellEnabled == null
}

/**
 * Per-model generation settings, keyed by `canonicalModelId(modelId)` in a
 * `Map<String, ModelGenerationSettings>` stored alongside `modelAliases`.
 *
 * These are sampling parameters and the model's context bound. They follow the
 * model, not a conversation or a single global default.
 */
@Serializable
data class ModelGenerationSettings(
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val thinkingEnabled: Boolean = true,
    /** Empty = provider's default effort; otherwise the provider's real tier. */
    val thinkingLevel: String = "",
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    /** Real model context limit (user-supplied). Null = unset. */
    val contextLimit: Int? = null,
    /** Absolute token count that triggers compaction. Null = unset. */
    val compactThreshold: Int? = null,
)

/**
 * Default (and per-provider overridable) wording for the [web_search] tool that is shown to the
 * model. These are the hardcoded strings that historically lived directly in
 * [com.newoether.agora.tool.WebSearchToolProvider.definitions]; extracting them here lets the
 * user edit them per provider and lets the tool layer switch the whole parameter/description set
 * based on the selected provider.
 *
 * The tool-level description (shared) and the per-provider conventions are concatenated when
 * building `ToolFunction.description`. The per-provider params carry both the *set* of exposed
 * parameter keys and the description of each, so switching provider changes which keys/props the
 * model sees.
 */
internal object WebSearchPrompts {
    /** Shared, tool-level description (provider-agnostic). */
    const val DEFAULT_TOOL_DESCRIPTION: String =
        "联网搜索当前信息。当你需要查找不在训练数据里的时事、资料、人物背景、数据时使用。\n\n" +
            "使用约定：\n" +
            "1. query 要具体（带限定词），比宽泛关键词更省 token 也更准。\n" +
            "2. num_results 由你控制（1-100，上限受搜索商内部限制）。通常一次 10-20 条足够；若一次没找到目标，可增大此值重新搜索（如 30-50），避免反复小批量搜造成浪费。\n" +
            "3. 时效(recency_filter)按业务场景选：查新闻/热点用 week 或 month；查人物/背景/历史用 year 或不传。\n" +
            "4. 信息可能在视频/图片里（尤其中文小众人物），可开启 include_video / include_image。\n" +
            "5. 已知权威来源时用 site_filter 缩小范围（如 baike.baidu.com），既精准又省 token。\n" +
            "6. 需要更全面时，用不同角度发起多次搜索（如\"张三\"、\"张三 获奖\"、\"张三 代表作\"），比单次多要几条更能覆盖；AI 搜索没有稳定的翻页，不要指望翻页。"

    /** Per-provider usage conventions appended to the tool description. */
    val DEFAULT_PROVIDER_CONVENTIONS: Map<String, String> = mapOf(
        "baidu" to
            "百度：域名限定用 site_filter（白名单域名逗号分隔，如 xiaohongshu.com）。同时传 site_filter 与 recency_filter 无冲突，可叠加使用。",
        "zhipu" to
            "智谱：search_engine 要按需决策：不传 = 用默认 search_pro_sogou（搜狗，覆盖腾讯生态/知乎，长尾强）——绝大多数查人名/背景/冷门事件都应不传；search_pro(高阶,多引擎召回更全) 用在需更全面或多次依旧空结果时；search_std(基础,省成本) 用在纯常识/宽泛查询；search_pro_quark(夸克) 用在垂直小众内容。注意 search_domain_filter 仅支持 search_std/search_pro（sogou/quark 不支持域名白名单），且同时传 search_domain_filter+search_recency_filter 时 count 会失效，请避免三者都传。",
    )

    /** Per-provider parameter key set + descriptions. The keys are the *only* params the model sees. */
    val DEFAULT_PROVIDER_PARAMS: Map<String, Map<String, String>> = mapOf(
        "baidu" to mapOf(
            "query" to "要搜索的关键词，尽量具体，可带限定词。",
            "num_results" to "返回结果数，由你控制(1-100)。没传则用 100 兜底（再受搜索商内部上限约束）。",
            "recency_filter" to "发布时间过滤：week=最近7天, month=最近30天, semiyear=最近180天, year=最近365天。不传或非这些值=不限时间。",
            "include_video" to "是否包含视频结果。当信息可能藏在视频里时为 true。",
            "include_image" to "是否包含图片结果。当信息可能藏在图片里时为 true。",
            "site_filter" to "仅从指定站点搜索，逗号分隔域名，如  baike.baidu.com, tieba.baidu.com 。不传则不限制站点。",
        ),
        "zhipu" to mapOf(
            "query" to "要搜索的关键词，尽量具体，可带限定词。",
            "num_results" to "返回结果数，由你控制(1-100)。没传则用 100 兜底（再受搜索商内部上限约束）。",
            "search_engine" to "搜索引擎。search_std(基础,省成本)/search_pro(高阶,召回全)/search_pro_sogou(搜狗,长尾强,默认)/search_pro_quark(夸克)。不传即用默认 sogou，通常这样最合适；只有明确要更全更准或更省时才改传。",
            "search_recency_filter" to "时间过滤。oneDay/oneWeek/oneMonth/oneYear/noLimit(默认,不限)。",
            "search_domain_filter" to "域名白名单，仅返回指定域名内容，如 xiaohongshu.com。仅 search_std/search_pro 支持，sogou/quark 不支持；若同时传 search_domain_filter+search_recency_filter，count 会失效。不传则不限制。",
            "content_size" to "摘要长度。medium(默认)/high(更详细)。",
        ),
    )
}
