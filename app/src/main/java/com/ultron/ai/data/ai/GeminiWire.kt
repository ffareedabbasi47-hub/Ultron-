package com.ultron.ai.data.ai

import com.ultron.ai.domain.ChatTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How to ask a Gemini model to think as little as possible (latency). */
enum class ThinkingMode { NONE, LEVEL_MINIMAL, BUDGET_ZERO }

sealed interface GeminiEvent {
    data class Delta(val text: String) : GeminiEvent
    data class Usage(val prompt: Int?, val output: Int?, val total: Int?) : GeminiEvent
    data class Blocked(val reason: String) : GeminiEvent
}

/**
 * Pure request/response mapping for the Gemini REST API (streamGenerateContent?alt=sse).
 * No Android or network code here, so it is unit-tested on the JVM.
 */
object GeminiWire {
    const val HOST = "https://generativelanguage.googleapis.com"
    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    private val json = Json { ignoreUnknownKeys = true }

    fun streamUrl(model: String) = "$HOST/v1beta/models/$model:streamGenerateContent?alt=sse"
    fun modelsUrl() = "$HOST/v1beta/models?pageSize=200"

    /** Model ids are put into a URL path, so only plain ids are accepted. */
    fun isSafeModelId(id: String) = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$").matches(id)

    fun thinkingFor(model: String): ThinkingMode {
        val m = model.lowercase()
        return when {
            "image" in m || "tts" in m || "embedding" in m -> ThinkingMode.NONE
            m.startsWith("gemini-3") && ("flash" in m) -> ThinkingMode.LEVEL_MINIMAL
            m.startsWith("gemini-2.5-flash") -> ThinkingMode.BUDGET_ZERO
            else -> ThinkingMode.NONE
        }
    }

    fun maxOutputTokens(styleWire: String) = if (styleWire == "detailed") 2048 else 768

    fun requestBody(system: String, turns: List<ChatTurn>, maxTokens: Int, thinking: ThinkingMode): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            put("contents", buildJsonArray {
                for (t in turns) add(buildJsonObject {
                    put("role", if (t.role == ChatTurn.ASSISTANT) "model" else "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", t.text) }) }
                })
            })
            putJsonObject("generationConfig") {
                put("temperature", 0.7)
                put("maxOutputTokens", maxTokens)
                when (thinking) {
                    ThinkingMode.LEVEL_MINIMAL -> putJsonObject("thinkingConfig") { put("thinkingLevel", "minimal") }
                    ThinkingMode.BUDGET_ZERO -> putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
                    ThinkingMode.NONE -> Unit
                }
            }
        }
        return body.toString()
    }

    /** Parses one SSE line into zero or more events (text parts marked as "thought" are skipped). */
    fun parseLine(line: String): List<GeminiEvent> {
        if (!line.startsWith("data:")) return emptyList()
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return emptyList()
        val obj = try { json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { return emptyList() }
        val out = ArrayList<GeminiEvent>()
        (obj["promptFeedback"] as? JsonObject)?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let { out += GeminiEvent.Blocked(it) }
        val parts = ((obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("content")?.let { it as? JsonObject }?.get("parts") as? JsonArray
        if (parts != null) {
            val text = buildString {
                for (p in parts) {
                    val po = p as? JsonObject ?: continue
                    if ((po["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue
                    (po["text"] as? JsonPrimitive)?.contentOrNull?.let { append(it) }
                }
            }
            if (text.isNotEmpty()) out += GeminiEvent.Delta(text)
        }
        (obj["usageMetadata"] as? JsonObject)?.let { u ->
            out += GeminiEvent.Usage(
                (u["promptTokenCount"] as? JsonPrimitive)?.intOrNull,
                (u["candidatesTokenCount"] as? JsonPrimitive)?.intOrNull,
                (u["totalTokenCount"] as? JsonPrimitive)?.intOrNull,
            )
        }
        return out
    }

    /** Chat-capable Gemini text models from a models.list response ("models/gemini-…" -> "gemini-…"). */
    fun chatModels(body: String): List<String> {
        val arr = try { json.parseToJsonElement(body).jsonObject["models"]?.jsonArray } catch (_: Exception) { null } ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") ?: return@mapNotNull null
            val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val lower = name.lowercase()
            if (!lower.startsWith("gemini") || "generateContent" !in methods) return@mapNotNull null
            if (listOf("image", "tts", "embedding", "live", "audio", "robotics", "computer-use", "customtools").any { it in lower }) return@mapNotNull null
            name.takeIf(::isSafeModelId)
        }.distinct().sortedDescending()
    }

    /** True if an HTTP 400 may be caused by our thinking field (so the caller retries without it). */
    fun looksLikeThinkingRejection(errorBody: String) = errorBody.contains("thinking", ignoreCase = true)
}
