package com.ultron.ai.data.ai

import com.ultron.ai.BuildConfig
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.Persona
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Talks to Google's Gemini API directly from the phone with the USER'S OWN API key.
 *
 * The key is typed by the user, stored encrypted (Android Keystore AES-GCM) and only ever sent in the
 * `x-goog-api-key` header over HTTPS to generativelanguage.googleapis.com. It is never put in the APK,
 * the repository, logs or diagnostics. A backend proxy remains the safer option for shared builds
 * (a key on a rooted phone can be extracted); this path exists for fast, personal single-user use.
 */
class GeminiDirectProvider(
    private val settings: SettingsRepository,
    private val baseClient: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
) : AIProvider {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    /** Models for which the API rejected our thinking field once; we stop sending it for them. */
    private val noThinking = ConcurrentHashMap.newKeySet<String>()

    private fun failed(msg: String, reachable: Boolean = false, authOk: Boolean? = null) =
        ProviderStatus(reachable, false, authOk, "Gemini (direct)", null, emptyList(), msg)

    override suspend fun status(): ProviderStatus = withContext(Dispatchers.IO) {
        val key = settings.geminiKey() ?: return@withContext failed("Gemini API key set nahi hai.")
        try {
            val client = baseClient.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(GeminiWire.modelsUrl()).header("x-goog-api-key", key).get().build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.code == 400 || r.code == 401 || r.code == 403 ->
                        failed("Gemini ne key accept nahi ki (HTTP ${r.code}). Key sahi hai aur Gemini API enabled hai?", reachable = true, authOk = false)
                    r.code == 429 -> failed("Gemini quota/rate limit (HTTP 429). Thodi der baad try karo.", reachable = true, authOk = true)
                    !r.isSuccessful -> failed("Gemini ne HTTP ${r.code} diya.", reachable = true)
                    else -> ProviderStatus(true, true, true, "Gemini (direct)", null, GeminiWire.chatModels(body), null)
                }
            }
        } catch (e: IOException) {
            diagnostics.log("net", "gemini status failed: ${e.javaClass.simpleName}")
            failed(if (e is UnknownHostException || e is ConnectException) "Internet nahi hai ya Gemini tak pahunch nahi paya." else "Connection error.")
        }
    }

    /** Opens DNS+TLS+HTTP/2 to Google ahead of the first question so the first reply starts faster. */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        if (settings.geminiKey() == null) return@withContext
        try {
            baseClient.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("${GeminiWire.HOST}/").head().build()).execute().close()
        } catch (_: Exception) { /* best effort */ }
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val worker = launch(Dispatchers.IO) {
            var call: okhttp3.Call? = null
            try {
                val key = settings.geminiKey()
                    ?: throw ProviderException(ErrorKind.NOT_CONFIGURED, "Gemini API key set nahi hai. Menu → AI Brain mein key daalo.")
                val s = settings.settings.first()
                val model = s.geminiModel.takeIf(GeminiWire::isSafeModelId) ?: GeminiWire.DEFAULT_MODEL
                val system = Persona.systemPrompt(BuildConfig.CREATOR_NAME, request.style.wire, request.language.wire, request.mode.wire, request.rules)
                val maxTokens = GeminiWire.maxOutputTokens(request.style.wire)
                val client = baseClient.newBuilder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(request.timeoutSec.toLong(), TimeUnit.SECONDS)
                    .build()

                var thinking = if (model in noThinking) ThinkingMode.NONE else GeminiWire.thinkingFor(model)
                var sawDelta = false
                var sawDone = false
                var attempt = 0
                var usageSeen: Usage? = null
                while (true) {
                    attempt++
                    val body = GeminiWire.requestBody(system, request.turns, maxTokens, thinking)
                    val httpReq = Request.Builder().url(GeminiWire.streamUrl(model))
                        .header("x-goog-api-key", key)
                        .header("Accept", "text/event-stream")
                        .post(body.toRequestBody(jsonMedia)).build()
                    val c = client.newCall(httpReq).also { call = it }
                    var retryWithoutThinking = false
                    c.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val errBody = resp.body?.string().orEmpty()
                            if (resp.code == 400 && thinking != ThinkingMode.NONE && attempt == 1 && GeminiWire.looksLikeThinkingRejection(errBody)) {
                                noThinking.add(model); thinking = ThinkingMode.NONE; retryWithoutThinking = true
                            } else throw httpError(resp.code, model)
                        } else {
                            val source = resp.body?.source() ?: throw ProviderException(ErrorKind.BAD_RESPONSE, "Khaali response.")
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                for (ev in GeminiWire.parseLine(line)) when (ev) {
                                    is GeminiEvent.Delta -> { sawDelta = true; trySend(ChatEvent.Delta(ev.text)) }
                                    is GeminiEvent.Usage -> usageSeen = Usage(ev.prompt, ev.output, ev.total)
                                    is GeminiEvent.Blocked -> throw ProviderException(ErrorKind.BAD_RESPONSE, "Gemini ne is request ko block kar diya.")
                                }
                            }
                            sawDone = true
                        }
                    }
                    if (!retryWithoutThinking) break
                }
                if (!sawDone || !sawDelta) {
                    throw ProviderException(ErrorKind.BAD_RESPONSE, if (sawDelta) "Reply beech mein ruk gayi." else "Gemini se koi reply nahi mila.")
                }
                trySend(ChatEvent.Done(usageSeen, model))
                close()
            } catch (e: ProviderException) {
                diagnostics.log("ai", "gemini error ${e.kind}")
                close(e)
            } catch (e: IOException) {
                if (call?.isCanceled() == true) close()
                else {
                    val kind = if (e is SocketTimeoutException) ErrorKind.TIMEOUT else ErrorKind.OFFLINE
                    diagnostics.log("ai", "gemini io ${e.javaClass.simpleName}")
                    close(ProviderException(kind, if (kind == ErrorKind.TIMEOUT) "Gemini ne time par jawab nahi diya." else "Internet se connection nahi ban paya."))
                }
            }
        }
        awaitClose { worker.cancel() }
    }.buffer(capacity = 256)

    private fun httpError(code: Int, model: String): ProviderException = when (code) {
        400 -> ProviderException(ErrorKind.BAD_RESPONSE, "Gemini ne request reject ki (HTTP 400). Model \"$model\" sahi hai?")
        401, 403 -> ProviderException(ErrorKind.UNAUTHORIZED, "Gemini API key galat hai ya is model ki permission nahi (Menu → AI Brain).")
        404 -> ProviderException(ErrorKind.NOT_CONFIGURED, "Model \"$model\" Gemini par nahi mila. AI Brain mein doosra model chuno.")
        429 -> ProviderException(ErrorKind.RATE_LIMITED, "Gemini ki free/paid limit poori ho gayi. Thodi der baad try karo.")
        in 500..599 -> ProviderException(ErrorKind.SERVER, "Gemini server error (HTTP $code).")
        else -> ProviderException(ErrorKind.SERVER, "Gemini ne HTTP $code diya.")
    }
}

/** Picks the Gemini-on-this-phone route when the user saved a key, otherwise the ULTRON backend proxy. */
class RoutingProvider(
    private val settings: SettingsRepository,
    private val proxy: AIProvider,
    private val direct: AIProvider,
) : AIProvider {
    override suspend fun status(): ProviderStatus = if (settings.geminiKey() != null) direct.status() else proxy.status()
    override fun stream(request: ChatRequest): Flow<ChatEvent> = kotlinx.coroutines.flow.flow {
        val route = if (settings.geminiKey() != null) direct else proxy
        route.stream(request).collect { emit(it) }
    }
}
