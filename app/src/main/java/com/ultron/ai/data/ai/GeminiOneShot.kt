package com.ultron.ai.data.ai

import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.ChatTurn
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One non-streaming Gemini call (used by the screen agent). Same key, consent and model rules as chat. */
class GeminiOneShot(private val settings: SettingsRepository, private val baseClient: OkHttpClient, private val diagnostics: DiagnosticsLog) {
    private val media = "application/json; charset=utf-8".toMediaType()

    /** @return reply text, or null with [lastError] set. */
    @Volatile var lastError: String? = null

    suspend fun generate(system: String, user: String, maxTokens: Int = 300): String? = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        if (!s.cloudConsent) { lastError = "Cloud AI ki permission chahiye."; return@withContext null }
        val key = settings.geminiKey() ?: run { lastError = "Gemini API key chahiye (Menu → AI Brain)."; return@withContext null }
        val model = s.geminiModel.takeIf(GeminiWire::isSafeModelId) ?: GeminiWire.DEFAULT_MODEL
        val client = baseClient.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
        var thinking = GeminiWire.thinkingFor(model)
        try {
            repeat(2) {
                val body = GeminiWire.requestBody(system, listOf(ChatTurn(ChatTurn.USER, user)), maxTokens, thinking)
                val req = Request.Builder().url(GeminiWire.generateUrl(model)).header("x-goog-api-key", key).post(body.toRequestBody(media)).build()
                client.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (r.isSuccessful) { lastError = null; return@withContext GeminiWire.parseText(txt) }
                    if (r.code == 400 && thinking != ThinkingMode.NONE && GeminiWire.looksLikeThinkingRejection(txt)) { thinking = ThinkingMode.NONE; return@use }
                    lastError = "Gemini error (HTTP ${r.code})."
                    return@withContext null
                }
            }
            null
        } catch (e: IOException) {
            diagnostics.log("ai", "oneshot io ${e.javaClass.simpleName}"); lastError = "Internet nahi hai."; null
        }
    }
}
