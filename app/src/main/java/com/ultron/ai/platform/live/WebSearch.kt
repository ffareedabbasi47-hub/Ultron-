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
    enum class Kind { NOT_CONFIGURED, QUOTA, TRANSIENT, OTHER }

    sealed interface Outcome {
        data class Ok(val result: WebSearchWire.Result) : Outcome
        data class Failed(val message: String, val kind: Kind = Kind.OTHER) : Outcome
    }

    suspend fun search(query: String): Outcome = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        if (!s.cloudConsent) return@withContext Outcome.Failed("Web search ke liye Cloud AI allow chahiye. Search nahi hua.", Kind.NOT_CONFIGURED)
        val key = settings.geminiKey() ?: return@withContext Outcome.Failed("Web search configured nahi hai: Gemini API key chahiye. Search nahi hua.", Kind.NOT_CONFIGURED)
        val model = s.geminiModel.takeIf(GeminiWire::isSafeModelId) ?: GeminiWire.DEFAULT_MODEL
        val client = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        try {
            val req = Request.Builder().url(GeminiWire.generateUrl(model)).header("x-goog-api-key", key)
                .post(WebSearchWire.body(query.take(300)).toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    diagnostics.log("search", "http ${r.code}")
                    val kind = when { r.code == 429 -> Kind.QUOTA; r.code >= 500 -> Kind.TRANSIENT; else -> Kind.OTHER }
                    return@withContext Outcome.Failed(when (r.code) {
                        401, 403 -> "Web search: key ko is feature ki permission nahi. Search nahi hua."
                        429 -> "Web search ki limit poori. Search nahi hua."
                        else -> "Web search service ne error diya (HTTP ${r.code}). Search nahi hua."
                    }, kind)
                }
                val res = WebSearchWire.parse(txt) ?: return@withContext Outcome.Failed("Web search se koi jawab nahi aaya.")
                Outcome.Ok(res)
            }
        } catch (e: IOException) {
            diagnostics.log("search", "io ${e.javaClass.simpleName}")
            Outcome.Failed("Internet nahi hai, web search nahi hua.", Kind.TRANSIENT)
        }
    }

    companion object {
        /** Text for the chat bubble and for the model: current web info is clearly separated from ULTRON's own knowledge. */
        fun format(r: WebSearchWire.Result, provider: String = ""): String = buildString {
            append("🌐 Web se (abhi ki jaankari${if (provider.isNotEmpty()) " · $provider" else ""}):\n").append(r.summary)
            if (r.sources.isEmpty()) append("\n\n⚠ Koi source link nahi mila, isliye ise web-verified na maane.")
            else { append("\n\nSources:"); r.sources.forEachIndexed { i, s -> append("\n${i + 1}. ${s.title} — ${s.url}") } }
        }
    }
}

/** Primary provider: Gemini + Google Search grounding (user's own key). */
class GeminiGroundingProvider(private val search: WebSearch) : com.ultron.ai.domain.research.SearchProvider {
    override val name = "Google Search (Gemini)"
    override suspend fun search(query: String): com.ultron.ai.domain.research.ProviderOutcome = when (val o = search.search(query)) {
        is WebSearch.Outcome.Ok -> com.ultron.ai.domain.research.ProviderOutcome.Ok(o.result)
        is WebSearch.Outcome.Failed -> when (o.kind) {
            WebSearch.Kind.NOT_CONFIGURED -> com.ultron.ai.domain.research.ProviderOutcome.NotConfigured(o.message)
            WebSearch.Kind.QUOTA -> com.ultron.ai.domain.research.ProviderOutcome.Quota(o.message)
            WebSearch.Kind.TRANSIENT -> com.ultron.ai.domain.research.ProviderOutcome.Transient(o.message)
            WebSearch.Kind.OTHER -> com.ultron.ai.domain.research.ProviderOutcome.Failed(o.message)
        }
    }
}

/** Secondary provider: Wikipedia public API (keyless, independent of Gemini quota; encyclopedic topics only). */
class WikipediaProvider(private val http: OkHttpClient) : com.ultron.ai.domain.research.SearchProvider {
    override val name = "Wikipedia"
    override suspend fun search(query: String): com.ultron.ai.domain.research.ProviderOutcome = withContext(Dispatchers.IO) {
        val q = query.take(200)
        try {
            val req = Request.Builder().url(com.ultron.ai.domain.research.WikipediaWire.url(q, com.ultron.ai.domain.research.WikipediaWire.langFor(q)))
                .header("User-Agent", "ULTRON-Android/1.0 (student assistant app)").build()
            http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                when {
                    r.code == 429 -> com.ultron.ai.domain.research.ProviderOutcome.Quota("Wikipedia rate limit")
                    r.code >= 500 -> com.ultron.ai.domain.research.ProviderOutcome.Transient("Wikipedia HTTP ${r.code}")
                    !r.isSuccessful -> com.ultron.ai.domain.research.ProviderOutcome.Failed("Wikipedia HTTP ${r.code}")
                    else -> com.ultron.ai.domain.research.WikipediaWire.parse(txt)?.let { com.ultron.ai.domain.research.ProviderOutcome.Ok(it) }
                        ?: com.ultron.ai.domain.research.ProviderOutcome.Failed("Wikipedia par is topic ka result nahi mila")
                }
            }
        } catch (e: IOException) { com.ultron.ai.domain.research.ProviderOutcome.Transient("internet nahi") }
    }
}
