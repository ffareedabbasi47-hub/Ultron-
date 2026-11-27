package com.ultron.ai.domain.research

import com.ultron.ai.domain.WebSearchWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Wikipedia's public API: a second, keyless, independent source (encyclopedic only, not news). */
object WikipediaWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun langFor(query: String) = if (query.any { it in '\u0900'..'\u097F' }) "hi" else "en"

    fun url(query: String, lang: String): String =
        "https://$lang.wikipedia.org/w/api.php?action=query&format=json&utf8=1&generator=search&gsrlimit=3" +
            "&prop=extracts%7Cinfo&exintro=1&explaintext=1&exsentences=3&inprop=url&gsrsearch=" + java.net.URLEncoder.encode(query, "UTF-8")

    fun parse(text: String): WebSearchWire.Result? {
        val pages = try { ((json.parseToJsonElement(text) as? JsonObject)?.get("query") as? JsonObject)?.get("pages") as? JsonObject } catch (_: Exception) { null } ?: return null
        val items = pages.values.mapNotNull { it as? JsonObject }
            .sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: 99 }
            .mapNotNull { pg ->
                val title = pg["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val extract = pg["extract"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val url = pg["fullurl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (extract.isEmpty()) null else Triple(title, extract, url)
            }
        if (items.isEmpty()) return null
        return WebSearchWire.Result(items.joinToString("\n\n") { "${it.first}: ${it.second}" }, items.map { WebSearchWire.Source(it.first, it.third) })
    }
}
