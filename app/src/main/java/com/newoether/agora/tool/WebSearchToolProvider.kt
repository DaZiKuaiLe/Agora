package com.newoether.agora.tool

import androidx.core.text.HtmlCompat
import com.newoether.agora.api.DuckDuckGoScraper
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.Constants
import com.newoether.agora.data.normalizeWebSearchProvider
import com.newoether.agora.data.WebSearchPrompts
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit

internal fun searxngSearchUrl(configuredBaseUrl: String, query: String): String {
    val baseUrl = configuredBaseUrl.ifBlank { "https://searx.be" }.trimEnd('/')
    val encodedQuery = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
    return "$baseUrl/search?q=$encodedQuery&format=json"
}

/** 一次 web_search 调用里，控制百度搜索的过滤条件（由模型运行时传入）。 */
internal data class BaiduSearchFilter(
    val recencyFilter: String? = null,
    val enableVideo: Boolean? = null,
    val enableImage: Boolean? = null,
    val siteFilter: String? = null,
)

/** 智谱允许的引擎枚举值。模型可传任意值，落库前 clamp 到这些白名单值上。 */
internal val ZHIPU_SEARCH_ENGINES = setOf(
    "search_std", "search_pro", "search_pro_sogou", "search_pro_quark",
)

/** 智谱默认引擎：优先长尾/小众召回（搜狗覆盖腾讯生态与知乎内容）。 */
internal const val DEFAULT_ZHIPU_SEARCH_ENGINE = "search_pro_sogou"

/** 一次 web_search 调用里，控制智谱搜索的过滤条件（由模型运行时传入）。 */
internal data class ZhipuSearchFilter(
    val engine: String? = null,
    val recencyFilter: String? = null,
    val domainFilter: String? = null,
    val contentSize: String? = null,
)

internal fun zhipuSearchRequestBody(
    query: String,
    numResults: Int,
    filter: ZhipuSearchFilter?,
): String {
    val engine = filter?.engine?.takeIf(ZHIPU_SEARCH_ENGINES::contains) ?: DEFAULT_ZHIPU_SEARCH_ENGINE
    return buildJsonObject {
        put("search_engine", engine)
        put("search_query", query)
        put("count", numResults.coerceIn(1, 50))
        // 固定跳过搜索意图识别：应用作为大模型的"数据搬运工"，意图判断交由大模型自己完成。
        put("search_intent", false)
        filter?.recencyFilter?.takeIf { it in setOf("oneDay", "oneWeek", "oneMonth", "oneYear", "noLimit") }?.let { recency ->
            put("search_recency_filter", recency)
        }
        filter?.domainFilter?.takeIf { it.isNotBlank() }?.let { domain ->
            put("search_domain_filter", domain)
        }
        filter?.contentSize?.takeIf { it in setOf("medium", "high") }?.let { size ->
            put("content_size", size)
        }
    }.toString()
}

internal fun normalizeZhipuSearchResponse(
    responseBody: String,
    query: String,
    numResults: Int,
): String {
    val root = Json.parseToJsonElement(responseBody) as? JsonObject
    val searchResults = root?.get("search_result")?.jsonArray
        ?: return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()

    val normalizedResults = buildJsonArray {
        var added = 0
        for (element in searchResults) {
            if (added >= numResults.coerceIn(1, 50)) break
            val result = element as? JsonObject ?: continue
            val url = (result["link"] as? JsonPrimitive)?.content.orEmpty()
            if (url.isBlank()) continue
            add(
                buildJsonObject {
                    put("title", (result["title"] as? JsonPrimitive)?.content.orEmpty())
                    put("url", url)
                    put("description", (result["content"] as? JsonPrimitive)?.content.orEmpty())
                    val media = (result["media"] as? JsonPrimitive)?.content.orEmpty()
                    if (media.isNotBlank()) put("source", media)
                    val publishDate = (result["publish_date"] as? JsonPrimitive)?.content.orEmpty()
                    if (publishDate.isNotBlank()) put("date", publishDate)
                }
            )
            added++
        }
    }
    if (normalizedResults.isEmpty()) {
        return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()
    }
    return buildJsonObject {
        put("type", "web_search")
        put("query", query)
        put("results", normalizedResults)
    }.toString()
}

internal fun kagiSearchRequestBody(query: String, numResults: Int): String =
    Json.encodeToString(
        buildJsonObject {
            put("query", query)
            put("workflow", "search")
            put("limit", numResults.coerceIn(1, 10))
        }
    )

internal fun normalizeKagiSearchResponse(
    responseBody: String,
    query: String,
    numResults: Int,
): String {
    val root = Json.parseToJsonElement(responseBody) as? JsonObject
    val data = root?.get("data") as? JsonObject
    val searchResults = data?.get("search") as? JsonArray
        ?: return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()

    val normalizedResults = buildJsonArray {
        var added = 0
        for (element in searchResults) {
            if (added >= numResults.coerceIn(1, 10)) break
            val result = element as? JsonObject ?: continue
            val url = (result["url"] as? JsonPrimitive)?.content.orEmpty()
            if (url.isBlank()) continue
            add(
                buildJsonObject {
                    put("title", (result["title"] as? JsonPrimitive)?.content.orEmpty())
                    put("url", url)
                    put("description", (result["snippet"] as? JsonPrimitive)?.content.orEmpty())
                }
            )
            added++
        }
    }
    if (normalizedResults.isEmpty()) {
        return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()
    }

    return buildJsonObject {
        put("type", "web_search")
        put("query", query)
        put("results", normalizedResults)
    }.toString()
}

internal fun baiduSearchRequestBody(
    query: String,
    numResults: Int,
    filter: BaiduSearchFilter?,
): String {
    val resourceTypeFilter = buildJsonArray {
        add(buildJsonObject {
            put("type", "web")
            put("top_k", numResults.coerceIn(1, 50))
        })
        if (filter?.enableVideo == true) {
            add(buildJsonObject { put("type", "video"); put("top_k", numResults.coerceIn(1, 10)) })
        }
        if (filter?.enableImage == true) {
            add(buildJsonObject { put("type", "image"); put("top_k", numResults.coerceIn(1, 30)) })
        }
    }
    val siteSnippet = filter?.siteFilter?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
    return buildJsonObject {
        put("messages", buildJsonArray { add(buildJsonObject { put("content", query); put("role", "user") }) })
        put("search_source", "baidu_search_v2")
        put("resource_type_filter", resourceTypeFilter)
        filter?.recencyFilter?.takeIf { it in setOf("week", "month", "semiyear", "year") }?.let { recency ->
            put("search_recency_filter", recency)
        }
        if (!siteSnippet.isNullOrEmpty()) {
            put(
                "search_filter",
                buildJsonObject {
                    put(
                        "match",
                        buildJsonObject { put("site", buildJsonArray { siteSnippet.forEach { add(JsonPrimitive(it)) } }) },
                    )
                },
            )
        }
    }.toString()
}

internal fun normalizeBaiduSearchResponse(
    responseBody: String,
    query: String,
    numResults: Int,
): String {
    val root = Json.parseToJsonElement(responseBody) as? JsonObject
    val references = root?.get("references")?.jsonArray
        ?: // Some deployments return a nested `result` object instead.
        (root?.get("result")?.jsonObject?.get("references")?.jsonArray)
        ?: return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()

    val normalizedResults = buildJsonArray {
        var added = 0
        for (element in references) {
            if (added >= numResults.coerceIn(1, 50)) break
            val result = element as? JsonObject ?: continue
            val url = (result["url"] as? JsonPrimitive)?.content.orEmpty()
            if (url.isBlank()) continue
            add(
                buildJsonObject {
                    put("title", (result["title"] as? JsonPrimitive)?.content.orEmpty())
                    put("url", url)
                    put(
                        "description",
                        (result["snippet"] as? JsonPrimitive)?.content
                            ?: (result["content"] as? JsonPrimitive)?.content
                            ?: "",
                    )
                    val date = (result["date"] as? JsonPrimitive)?.content.orEmpty()
                    if (date.isNotBlank()) put("date", date)
                }
            )
            added++
        }
    }
    if (normalizedResults.isEmpty()) {
        return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()
    }
    return buildJsonObject {
        put("type", "web_search")
        put("query", query)
        put("results", normalizedResults)
    }.toString()
}

internal fun webSearchProviderDisplayName(provider: String): String = when (provider) {
    "baidu" -> "Baidu Search"
    "zhipu" -> "智谱 AI 搜索"
    "kagi" -> "Kagi"
    "serper" -> "Serper"
    "tavily" -> "Tavily"
    "searxng" -> "SearXNG"
    "duckduckgo" -> "DuckDuckGo"
    else -> "Brave Search"
}

/** Resolve the per-provider usage convention from the built-in default. */
internal fun effectiveProviderConvention(provider: String): String =
    WebSearchPrompts.DEFAULT_PROVIDER_CONVENTIONS[provider] ?: ""

/** The per-provider parameter key set + descriptions, from the built-in default. */
internal fun effectiveProviderParams(provider: String): Map<String, String> =
    WebSearchPrompts.DEFAULT_PROVIDER_PARAMS[provider] ?: emptyMap()

/** Infer the JSON-schema type for a web_search parameter by its name. */
internal fun webSearchParamType(key: String): String = when (key) {
    "num_results" -> "integer"
    "include_video", "include_image" -> "boolean"
    else -> "string"
}

class WebSearchToolProvider : ToolProvider {
    private val webClient = HttpClient.client.newBuilder()
        .callTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.webSearchEnabled) return emptyList()
        val provider = normalizeWebSearchProvider(ctx.webSearchProvider)
        val description = WebSearchPrompts.DEFAULT_TOOL_DESCRIPTION +
            "\n\n" + effectiveProviderConvention(provider)
        val properties = effectiveProviderParams(provider)
            .map { (key, desc) -> key to ToolProperty(webSearchParamType(key), desc) }
            .toMap()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "web_search",
                description = description,
                parameters = ToolParameters(
                    properties = properties,
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "web_fetch",
                description = "抓取并阅读一个网页的完整文本内容。在 web_search 之后、需要某个具体页面的更详细内容时使用。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty("string", "要抓取的页面 URL。"),
                        "maxChars" to ToolProperty("integer", "返回文本的最大字符数(默认8000，最大100000)。若结果带 \"truncated\": true，可增大此值再次调用以获取更多。")
                    ),
                    required = listOf("url")
                )
            ))
        )
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        when (name) {
            "web_search" -> executeWebSearch(arguments, ctx)
            "web_fetch" -> executeWebFetch(arguments, ctx)
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in setOf("web_search", "web_fetch")

    private fun executeWebSearch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_search"); put("error", "no_query") }.toString()
        val numResults = ((args["num_results"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 100).coerceIn(1, 100)
        val provider = normalizeWebSearchProvider(ctx.webSearchProvider)

        // 由模型按业务场景传入的搜索过滤条件；未传入则用 null。只解析当前 provider 的那一套参数，
        // 避免把另一套搜索商的参数也解析出来（与 definitions 里只暴露当前 provider 参数保持一致）。
        val baiduFilter: BaiduSearchFilter? = if (provider == "baidu") {
            BaiduSearchFilter(
                recencyFilter = (args["recency_filter"] as? JsonPrimitive)?.content,
                enableVideo = (args["include_video"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
                enableImage = (args["include_image"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
                siteFilter = (args["site_filter"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
            )
        } else null

        // 由模型按业务场景传入的智谱过滤条件；未传入则用 null（默认引擎/不限时效/不限站点/默认摘要长度）。
        val zhipuFilter: ZhipuSearchFilter? = if (provider == "zhipu") {
            ZhipuSearchFilter(
                engine = (args["search_engine"] as? JsonPrimitive)?.content,
                recencyFilter = (args["search_recency_filter"] as? JsonPrimitive)?.content,
                domainFilter = (args["search_domain_filter"] as? JsonPrimitive)?.content,
                contentSize = (args["content_size"] as? JsonPrimitive)?.content,
            )
        } else null

        return try {
            // DuckDuckGo is a scraper, not an API — handle it separately.
            if (provider == "duckduckgo") {
                val scraper = DuckDuckGoScraper(webClient)
                return when (val r = scraper.search(query, numResults)) {
                    is DuckDuckGoScraper.SearchResponse.Success -> {
                        val rawResults = buildJsonArray {
                            r.results.forEach { result ->
                                add(buildJsonObject {
                                    put("title", result.title)
                                    put("url", result.url)
                                    put("description", result.snippet)
                                })
                            }
                        }
                        buildJsonObject {
                            put("type", "web_search")
                            put("query", query)
                            put("results", rawResults)
                        }.toString()
                    }
                    is DuckDuckGoScraper.SearchResponse.Error -> {
                        buildJsonObject {
                            put("type", "web_search")
                            put("query", query)
                            put("error", r.type.name.lowercase())
                            put("message", r.message)
                        }.toString()
                    }
                }
            }

            val apiKey = ctx.webSearchApiKeys[provider].orEmpty()
            if (provider != "searxng" && apiKey.isBlank()) {
                return buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    put("error", "no_api_key")
                    put("provider", webSearchProviderDisplayName(provider))
                }.toString()
            }
            val body = when (provider) {
                "kagi" -> HttpClient.post(
                    "https://kagi.com/api/v1/search",
                    kagiSearchRequestBody(query, numResults),
                    mapOf(
                        "Accept" to "application/json",
                        "Authorization" to "Bearer $apiKey",
                    ),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "serper" -> HttpClient.post(
                    "https://google.serper.dev/search",
                    Json.encodeToString(buildJsonObject { put("q", query); put("num", numResults) }),
                    mapOf("X-API-KEY" to apiKey),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "tavily" -> HttpClient.post(
                    "https://api.tavily.com/search",
                    Json.encodeToString(buildJsonObject {
                        put("api_key", apiKey)
                        put("query", query)
                        put("max_results", numResults)
                        put("search_depth", "advanced")
                        put("include_answer", true)
                    }),
                    emptyMap(),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "searxng" -> {
                    // Don't pin engines=google,brave: many public/self-hosted SearXNG instances
                    // disable those engines (rate-limited/require config), and pinning them yields
                    // an empty result set. Letting the instance use its own default-enabled engines
                    // matches how other SearXNG clients behave. Send a browser-like User-Agent so
                    // bot-filtering instances don't 403 us (same reason web_fetch sets one).
                    HttpClient.fetchModels(
                        searxngSearchUrl(ctx.webSearchBaseUrl, query),
                        mapOf("User-Agent" to Constants.WEB_FETCH_USER_AGENT),
                        callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                    )
                }
                "baidu" -> HttpClient.post(
                    "https://qianfan.baidubce.com/v2/ai_search/web_search",
                    baiduSearchRequestBody(query, numResults, baiduFilter),
                    mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        "Authorization" to "Bearer $apiKey",
                    ),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "zhipu" -> HttpClient.post(
                    "https://open.bigmodel.cn/api/paas/v4/web_search",
                    zhipuSearchRequestBody(query, numResults, zhipuFilter),
                    mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        "Authorization" to "Bearer $apiKey",
                    ),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                else -> HttpClient.fetchModels(
                    "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=$numResults",
                    mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_response") }.toString()

            if (provider == "kagi") {
                return normalizeKagiSearchResponse(body, query, numResults)
            }
            if (provider == "baidu") {
                return normalizeBaiduSearchResponse(body, query, numResults)
            }
            if (provider == "zhipu") {
                return normalizeZhipuSearchResponse(body, query, numResults)
            }

            val json: Map<String, kotlinx.serialization.json.JsonElement> = Json.decodeFromString(body)

            if (provider == "tavily") {
                val resultsArray = json["results"]?.jsonArray
                    ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                if (resultsArray.isEmpty())
                    return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                val answer = (json["answer"] as? JsonPrimitive)?.content
                val rawResults = buildJsonArray {
                    for (element in resultsArray) {
                        val obj = element.jsonObject
                        add(buildJsonObject {
                            put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                            put("url", (obj["url"] as? JsonPrimitive)?.content ?: "")
                            put("content", (obj["content"] as? JsonPrimitive)?.content ?: "")
                            val score = (obj["score"] as? JsonPrimitive)?.content?.toFloatOrNull()
                            if (score != null) put("score", score)
                        })
                    }
                }
                return buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    if (!answer.isNullOrBlank()) put("answer", answer)
                    put("results", rawResults)
                }.toString()
            }

            val resultsArray = when {
                json.containsKey("organic") -> json["organic"]?.jsonArray
                json.containsKey("web") -> {
                    val web = json["web"]?.jsonObject
                    web?.get("results")?.jsonArray
                }
                json.containsKey("results") -> json["results"]?.jsonArray
                else -> null
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            if (resultsArray.isEmpty())
                return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            val rawResults = buildJsonArray {
                for (element in resultsArray) {
                    val obj = element.jsonObject
                    add(buildJsonObject {
                        put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                        put("url", (obj["link"] as? JsonPrimitive)?.content ?: (obj["url"] as? JsonPrimitive)?.content ?: "")
                        put("description", (obj["snippet"] as? JsonPrimitive)?.content ?: (obj["content"] as? JsonPrimitive)?.content ?: (obj["description"] as? JsonPrimitive)?.content ?: "")
                    })
                }
            }
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("results", rawResults)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("error", "search_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun executeWebFetch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val url = (args["url"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_fetch"); put("error", "no_url") }.toString()
        val maxChars = (try {
            (args["maxChars"] as? JsonPrimitive)?.content?.toIntOrNull()
        } catch (_: Exception) { null } ?: 8000).coerceIn(1, 100_000)

        return try {
            val html = HttpClient.fetchModels(url, mapOf(
                "User-Agent" to Constants.WEB_FETCH_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,*/*"
            ), callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS)
                ?: return buildJsonObject { put("type", "web_fetch"); put("url", url); put("error", "no_response") }.toString()
            val fullText = htmlToReadableText(html)
            val text = fullText.take(maxChars)
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("text", text)
                put("truncated", fullText.length > text.length)
                put("totalChars", fullText.length)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("error", "fetch_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    /**
     * Extracts readable text from an HTML page.
     *
     * Strips non-content blocks (comments, script/style/noscript/svg/head), then lets
     * [HtmlCompat] decode entities and flatten the remaining markup while keeping block
     * elements as line breaks. Extraction runs over the whole (capped) HTML and the caller
     * truncates the resulting *text* — so article content past the page's boilerplate is no
     * longer cut off, and entities (—, ’, accents, numeric refs) are decoded correctly
     * instead of being dropped to spaces.
     */
    private fun htmlToReadableText(rawHtml: String): String {
        val stripped = rawHtml
            .take(Constants.MAX_WEB_FETCH_HTML_LENGTH)
            .replace(Regex("<!--[\\s\\S]*?-->"), " ")
            .replace(
                Regex("<(script|style|noscript|svg|head)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
            // Drop common page chrome so navigation/menus/footers don't eat the text budget.
            .replace(
                Regex("<(nav|header|footer|aside)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
        val text = HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        return text
            .replace(Regex("[ \\t\\x0B\\u000C\\r]+"), " ") // collapse intra-line whitespace
            .replace(Regex(" *\\n *"), "\n")               // trim around line breaks
            .replace(Regex("\\n{3,}"), "\n\n")             // collapse blank-line runs
            .trim()
    }
}
