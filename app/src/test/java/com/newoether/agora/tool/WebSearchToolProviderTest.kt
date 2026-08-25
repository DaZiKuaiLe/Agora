package com.newoether.agora.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearxngSearchUrlTest {
    @Test
    fun searxngUsesInstanceDefaultsAndCanonicalizesTrailingSlash() {
        val url = searxngSearchUrl(
            configuredBaseUrl = "https://search.example.test/",
            query = "hello world/中文",
        )

        assertEquals(
            "https://search.example.test/search?q=hello+world%2F%E4%B8%AD%E6%96%87&format=json",
            url,
        )
        assertFalse(url.contains("engines="))
        assertFalse(url.contains(".test//search"))
    }
}

class BaiduSearchRequestBodyTest {
    @Test
    fun buildsV2PayloadWithDefaultWebMode() {
        val body = Json.parseToJsonElement(
            baiduSearchRequestBody("北京旅游", 5, null),
        ).jsonObject

        assertEquals("baidu_search_v2", body["search_source"]?.jsonPrimitive?.content)
        val resources = body["resource_type_filter"]!!.jsonArray
        assertEquals(1, resources.size)
        assertEquals("web", resources[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("5", resources[0].jsonObject["top_k"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("search_recency_filter"))
        assertFalse(body.containsKey("search_filter"))
    }

    @Test
    fun includesRecencyVideoImageAndSiteFilterWhenConfigured() {
        val body = Json.parseToJsonElement(
            baiduSearchRequestBody(
                "deepseek",
                8,
                BaiduSearchFilter(
                    recencyFilter = "week",
                    enableVideo = true,
                    enableImage = true,
                    siteFilter = "tieba.baidu.com,baike.baidu.com",
                ),
            ),
        ).jsonObject

        assertEquals("week", body["search_recency_filter"]?.jsonPrimitive?.content)
        val resources = body["resource_type_filter"]!!.jsonArray
        assertEquals(3, resources.size)
        assertEquals("video", resources[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image", resources[2].jsonObject["type"]?.jsonPrimitive?.content)
        val site = body["search_filter"]!!.jsonObject["match"]!!.jsonObject["site"]!!.jsonArray
        assertEquals(2, site.size)
        assertEquals("tieba.baidu.com", site[0].jsonPrimitive.content)
    }
}

class NormalizeBaiduSearchResponseTest {
    @Test
    fun extractsReferencesIntoExpectedShape() {
        val response = """
            {"references":[
                {"id":1,"url":"https://baike.baidu.com/item/x","title":"标题","content":"简介内容","snippet":"简介","date":"2025-10-12","type":"web"},
                {"id":2,"url":"https://example.com/y","title":"二","content":"","snippet":"","type":"web"}
            ]}
        """.trimIndent()

        val normalized = Json.parseToJsonElement(normalizeBaiduSearchResponse(response, "query", 5)).jsonObject
        val results = normalized["results"]!!.jsonArray
        assertEquals("web_search", normalized["type"]?.jsonPrimitive?.content)
        assertEquals(2, results.size)
        assertEquals("https://baike.baidu.com/item/x", results[0].jsonObject["url"]?.jsonPrimitive?.content)
        assertEquals("简介", results[0].jsonObject["description"]?.jsonPrimitive?.content)
        assertEquals("2025-10-12", results[0].jsonObject["date"]?.jsonPrimitive?.content)
    }

    @Test
    fun fallsBackToNoResultsWhenEmpty() {
        val normalized = Json.parseToJsonElement(
            normalizeBaiduSearchResponse("""{"references":[]}""", "query", 5),
        ).jsonObject
        assertEquals("no_results", normalized["error"]?.jsonPrimitive?.content)
    }
}

class ZhipuSearchRequestBodyTest {
    @Test
    fun buildsDefaultPayloadWithSogouEngineAndFixedIntent() {
        val body = Json.parseToJsonElement(
            zhipuSearchRequestBody("知乎 小众人物", 5, null),
        ).jsonObject

        assertEquals("search_pro_sogou", body["search_engine"]?.jsonPrimitive?.content)
        assertEquals("知乎 小众人物", body["search_query"]?.jsonPrimitive?.content)
        assertEquals("5", body["count"]?.jsonPrimitive?.content)
        assertEquals("false", body["search_intent"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("search_recency_filter"))
        assertFalse(body.containsKey("search_domain_filter"))
        assertFalse(body.containsKey("content_size"))
    }

    @Test
    fun includesFilterOptionsWhenConfiguredAndKeepsValidEnums() {
        val body = Json.parseToJsonElement(
            zhipuSearchRequestBody(
                "明星八卦",
                20,
                ZhipuSearchFilter(
                    engine = "search_pro_quark",
                    recencyFilter = "oneWeek",
                    domainFilter = "weibo.com",
                    contentSize = "high",
                ),
            ),
        ).jsonObject

        assertEquals("search_pro_quark", body["search_engine"]?.jsonPrimitive?.content)
        assertEquals("oneWeek", body["search_recency_filter"]?.jsonPrimitive?.content)
        assertEquals("weibo.com", body["search_domain_filter"]?.jsonPrimitive?.content)
        assertEquals("high", body["content_size"]?.jsonPrimitive?.content)
    }

    @Test
    fun skipsUnknownEngineAndKeepsDefaultSogou() {
        val body = Json.parseToJsonElement(
            zhipuSearchRequestBody(
                "query",
                10,
                ZhipuSearchFilter(engine = "search_unknown"),
            ),
        ).jsonObject

        assertEquals("search_pro_sogou", body["search_engine"]?.jsonPrimitive?.content)
    }
}

class NormalizeZhipuSearchResponseTest {
    @Test
    fun extractsSearchResultIntoExpectedShape() {
        val response = """
            {"created":1748261757,"id":"x","search_result":[
                {"title":"标题","link":"https://weibo.com/123","content":"简介内容","media":"微博","publish_date":"2025-05-23"},
                {"title":"二","link":"https://example.com/y","content":"","media":"","publish_date":""}
            ]}
        """.trimIndent()

        val normalized = Json.parseToJsonElement(normalizeZhipuSearchResponse(response, "query", 5)).jsonObject
        val results = normalized["results"]!!.jsonArray
        assertEquals("web_search", normalized["type"]?.jsonPrimitive?.content)
        assertEquals(2, results.size)
        assertEquals("https://weibo.com/123", results[0].jsonObject["url"]?.jsonPrimitive?.content)
        assertEquals("简介内容", results[0].jsonObject["description"]?.jsonPrimitive?.content)
        assertEquals("微博", results[0].jsonObject["source"]?.jsonPrimitive?.content)
        assertEquals("2025-05-23", results[0].jsonObject["date"]?.jsonPrimitive?.content)
    }

    @Test
    fun fallsBackToNoResultsWhenEmpty() {
        val normalized = Json.parseToJsonElement(
            normalizeZhipuSearchResponse("""{"search_result":[]}""", "query", 5),
        ).jsonObject
        assertEquals("no_results", normalized["error"]?.jsonPrimitive?.content)
    }
}
