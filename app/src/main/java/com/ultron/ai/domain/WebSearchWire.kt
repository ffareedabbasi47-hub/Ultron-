package com.ultron.ai.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Web search through Gemini's Google Search grounding (uses the user's own key; nothing is hardcoded in the APK). */
object WebSearchWire {
    data class Source(val title: String, val url: String)
    data class Result(val summary: String, val sources: List<Source>)

    private val json = Json { ignoreUnknownKeys = true }

    fun body(query: String): String = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                putJsonArray("parts") { add(buildJsonObject { put("text", "Search the web and answer concisely with current facts: $query") }) }
            })
        }
        putJsonArray("tools") { add(buildJsonObject { putJsonObject("google_search") { } }) }
    }.toString()

    /** Null when the response has no usable answer. A result with NO sources means the model did not actually search the web. */
    fun parse(text: String): Result? {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        val cand = ((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: return null
        val parts = ((cand["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val summary = parts.mapNotNull { ((it as? JsonObject)?.get("text"))?.jsonPrimitive?.contentOrNull }.joinToString("").trim()
        if (summary.isEmpty()) return null
        val chunks = ((cand["groundingMetadata"] as? JsonObject)?.get("groundingChunks") as? JsonArray).orEmpty()
        val sources = chunks.mapNotNull { c ->
            val w = (c as? JsonObject)?.get("web") as? JsonObject ?: return@mapNotNull null
            val uri = w["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!uri.startsWith("https://") && !uri.startsWith("http://")) return@mapNotNull null
            Source(w["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: uri, uri)
        }.distinctBy { it.url }.take(6)
        return Result(summary, sources)
    }
}
