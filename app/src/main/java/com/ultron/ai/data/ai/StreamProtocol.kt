package com.ultron.ai.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One server-sent event from the ULTRON backend proxy: `data: {json}`. */
@Serializable
data class StreamEvent(
    val delta: String? = null,
    val done: Boolean? = null,
    val error: String? = null,
    val code: String? = null,
    val usage: Usage? = null,
    val model: String? = null,
)

@Serializable
data class Usage(
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

object StreamProtocol {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Parses one SSE line. Returns null for blank lines, comments and non-data fields. */
    fun parseLine(line: String): StreamEvent? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        return try {
            json.decodeFromString(StreamEvent.serializer(), payload)
        } catch (_: Exception) {
            null
        }
    }
}
