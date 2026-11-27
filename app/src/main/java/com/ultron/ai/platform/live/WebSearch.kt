package com.ultron.ai.platform.live

import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.ai.GeminiWire
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.WebSearchWire
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Real web search (Gemini + Google Search grounding). Never pretends: failures and "no sources" are reported as such. */
class WebSearch(private val settings: SettingsRepository, private val http: OkHttpClient, private val diagnostics: DiagnosticsLog) {
    sealed interface Outcome {
        data class Ok(val result: WebSearchWire.Result) : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun search(query: String): Outcome = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        if (!s.cloudConsent) return@withContext Outcome.Failed("Web search ke liye Cloud AI allow chahiye. Search nahi hua.")
        val key = settings.geminiKey() ?: return@withContext Outcome.Failed("Web search configured nahi hai: Gemini API key chahiye. Search nahi hua.")
        val model = s.geminiModel.takeIf(GeminiWire::isSafeModelId) ?: GeminiWire.DEFAULT_MODEL
        val client = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        try {
            val req = Request.Builder().url(GeminiWire.generateUrl(model)).header("x-goog-api-key", key)
                .post(WebSearchWire.body(query.take(300)).toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    diagnostics.log("search", "http ${r.code}")
                    return@withContext Outcome.Failed(when (r.code) {
                        401, 403 -> "Web search: key ko is feature ki permission nahi. Search nahi hua."
                        429 -> "Web search ki limit poori. Search nahi hua."
                        else -> "Web search service ne error diya (HTTP ${r.code}). Search nahi hua."
                    })
                }
                val res = WebSearchWire.parse(txt) ?: return@withContext Outcome.Failed("Web search se koi jawab nahi aaya.")
                Outcome.Ok(res)
            }
        } catch (e: IOException) {
            diagnostics.log("search", "io ${e.javaClass.simpleName}")
            Outcome.Failed("Internet nahi hai, web search nahi hua.")
        }
    }

    companion object {
        /** Text for the chat bubble and for the model: current web info is clearly separated from ULTRON's own knowledge. */
        fun format(r: WebSearchWire.Result): String = buildString {
            append("🌐 Web se (abhi ki jaankari):\n").append(r.summary)
            if (r.sources.isEmpty()) append("\n\n⚠ Koi source link nahi mila, isliye ise web-verified na maane.")
            else { append("\n\nSources:"); r.sources.forEachIndexed { i, s -> append("\n${i + 1}. ${s.title} — ${s.url}") } }
        }
    }
}
