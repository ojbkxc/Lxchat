package com.lxseek.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class KagiSearchResponseTest {
    @Test
    fun requestUsesCurrentV1FieldsAndClampsLimit() {
        val request = Json.parseToJsonElement(
            kagiSearchRequestBody(query = "compose markdown", numResults = 42)
        ).jsonObject

        assertEquals("compose markdown", request.getValue("query").jsonPrimitive.content)
        assertEquals("search", request.getValue("workflow").jsonPrimitive.content)
        assertEquals(10, request.getValue("limit").jsonPrimitive.content.toInt())
    }

    @Test
    fun responseMapsDataSearchAndHonorsRequestedLimit() {
        val response = """
            {
              "meta": {"trace": "trace-id"},
              "data": {
                "search": [
                  {"url": "https://example.com/one", "title": "One", "snippet": "First"},
                  {"url": "https://example.com/two", "title": "Two", "snippet": "Second"}
                ],
                "related_search": [{"props": {"query": "ignored"}}]
              }
            }
        """.trimIndent()

        val normalized = Json.parseToJsonElement(
            normalizeKagiSearchResponse(response, query = "example", numResults = 1)
        ).jsonObject
        val results = normalized.getValue("results").jsonArray

        assertEquals("web_search", normalized.getValue("type").jsonPrimitive.content)
        assertEquals("example", normalized.getValue("query").jsonPrimitive.content)
        assertEquals(1, results.size)
        assertEquals("One", results[0].jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(
            "https://example.com/one",
            results[0].jsonObject.getValue("url").jsonPrimitive.content,
        )
        assertEquals(
            "First",
            results[0].jsonObject.getValue("description").jsonPrimitive.content,
        )
    }

    @Test
    fun responseWithoutUsableSearchResultsReturnsNoResults() {
        val response = """{"meta":{"trace":"trace-id"},"data":{"search":[]}}"""

        val normalized = Json.parseToJsonElement(
            normalizeKagiSearchResponse(response, query = "missing", numResults = 5)
        ).jsonObject

        assertEquals("no_results", normalized.getValue("error").jsonPrimitive.content)
    }
}
