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

    // ---------- Audio: speech-to-text (inline audio) and Gemini TTS ----------

    fun generateUrl(model: String) = "$HOST/v1beta/models/$model:generateContent"

    const val TRANSCRIBE_PROMPT =
        "Transcribe the speech in this audio exactly as spoken. The speaker uses Hindi, English or Hinglish. " +
            "Write Hindi words in Roman letters (Hinglish) unless the speaker clearly spoke only Hindi, then use Devanagari. " +
            "Output ONLY the transcript, no quotes, no labels, no commentary. If there is no intelligible human speech, output exactly: [none]"

    fun transcribeBody(wavBase64: String, thinking: ThinkingMode): String = buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", TRANSCRIBE_PROMPT) })
                    add(buildJsonObject { putJsonObject("inline_data") { put("mime_type", "audio/wav"); put("data", wavBase64) } })
                }
            })
        })
        putJsonObject("generationConfig") {
            put("temperature", 0.0)
            put("maxOutputTokens", 256)
            when (thinking) {
                ThinkingMode.LEVEL_MINIMAL -> putJsonObject("thinkingConfig") { put("thinkingLevel", "minimal") }
                ThinkingMode.BUDGET_ZERO -> putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
                ThinkingMode.NONE -> Unit
            }
        }
    }.toString()

    /** Concatenated non-thought text of a non-streaming generateContent response ("" if none). */
    fun parseText(body: String): String {
        val obj = try { json.parseToJsonElement(body).jsonObject } catch (_: Exception) { return "" }
        val parts = ((obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("content")?.let { it as? JsonObject }?.get("parts") as? JsonArray ?: return ""
        return buildString {
            for (p in parts) {
                val po = p as? JsonObject ?: continue
                if ((po["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue
                (po["text"] as? JsonPrimitive)?.contentOrNull?.let { append(it) }
            }
        }.trim()
    }

    /** Transcript text, or null when the model reported no speech. */
    fun cleanTranscript(raw: String): String? {
        val t = raw.trim().trim('"').trim()
        if (t.isEmpty() || t.equals("[none]", ignoreCase = true) || t.equals("none", ignoreCase = true)) return null
        return t
    }

    /** Style instruction goes in front of the text; the model reads it as direction, not as words to say. */
    const val VOICE_STYLE =
        "Say the following in a deep, slow, calm, controlled and slightly menacing voice, like a composed machine intelligence. " +
            "Clear pronunciation, deliberate pauses, never shouting: "

    fun ttsBody(text: String, voiceName: String): String = buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                putJsonArray("parts") { add(buildJsonObject { put("text", VOICE_STYLE + text) }) }
            })
        })
        putJsonObject("generationConfig") {
            putJsonArray("responseModalities") { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) }
            putJsonObject("speechConfig") {
                putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voiceName) } }
            }
        }
    }.toString()

    /** Base64 audio from a TTS response (raw 16-bit PCM, 24 kHz mono unless mimeType says otherwise). */
    data class TtsAudio(val base64: String, val sampleRate: Int)

    fun parseTtsAudio(body: String): TtsAudio? {
        val obj = try { json.parseToJsonElement(body).jsonObject } catch (_: Exception) { return null }
        val parts = ((obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("content")?.let { it as? JsonObject }?.get("parts") as? JsonArray ?: return null
        for (p in parts) {
            val inline = ((p as? JsonObject)?.get("inlineData") ?: (p as? JsonObject)?.get("inline_data")) as? JsonObject ?: continue
            val data = (inline["data"] as? JsonPrimitive)?.contentOrNull ?: continue
            val mime = ((inline["mimeType"] ?: inline["mime_type"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
            return TtsAudio(data, rate)
        }
        return null
    }

    /** Voices offered in the picker (names are Gemini prebuilt voices; descriptors are Google's). */
    val CLOUD_VOICES: List<Pair<String, String>> = listOf(
        "Algenib" to "Gravelly (deep, rough)",
        "Gacrux" to "Mature",
        "Alnilam" to "Firm",
        "Orus" to "Firm",
        "Charon" to "Informative",
        "Rasalgethi" to "Informative",
        "Schedar" to "Even",
        "Iapetus" to "Clear",
        "Umbriel" to "Easy-going",
        "Fenrir" to "Excitable",
    )
}
