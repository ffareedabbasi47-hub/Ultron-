package com.ultron.ai.data.ai

import com.ultron.ai.BuildConfig
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.settings.SettingsRepository
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable private data class MsgDto(val role: String, val text: String)
@Serializable private data class ChatBody(
    val messages: List<MsgDto>,
    val style: String,
    val language: String,
    val mode: String,
    val model: String? = null,
    val rules: String? = null,
)
@Serializable private data class HealthDto(
    val ok: Boolean = false,
    val version: String? = null,
    val provider: String? = null,
    val configured: Boolean = false,
    val auth: Boolean = false,
)
@Serializable private data class ModelsDto(val models: List<String> = emptyList())
@Serializable private data class ErrDto(val error: String? = null)

/**
 * Talks ONLY to the ULTRON backend proxy. No provider API key exists in the app; the optional
 * access token is the user's own credential for their own backend and is stored encrypted.
 */
class ProxyProvider(
    private val settings: SettingsRepository,
    private val baseClient: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
) : AIProvider {

    private val json = StreamProtocol.json
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private suspend fun baseUrl(): HttpUrl {
        val raw = settings.settings.first().backendUrl.trim()
        if (raw.isEmpty()) {
            throw ProviderException(
                ErrorKind.NOT_CONFIGURED,
                "Backend URL set nahi hai. Menu → Connectivity mein apna ULTRON backend URL daalo.",
            )
        }
        val url = raw.toHttpUrlOrNull()
            ?: throw ProviderException(ErrorKind.NOT_CONFIGURED, "Backend URL valid nahi hai.")
        if (!url.isHttps && !BuildConfig.DEBUG) {
            throw ProviderException(ErrorKind.NOT_CONFIGURED, "Sirf https backend URL allowed hai.")
        }
        return url
    }

    private fun Request.Builder.withAuth(): Request.Builder {
        settings.accessToken()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        return this
    }

    override suspend fun status(): ProviderStatus = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl()
            val client = baseClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
            val health = client.newCall(Request.Builder().url(base.resolve("health")!!).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext failed("Backend ne ${r.code} diya.")
                json.decodeFromString(HealthDto.serializer(), r.body?.string().orEmpty())
            }
            var authOk: Boolean? = null
            var models = emptyList<String>()
            client.newCall(Request.Builder().url(base.resolve("v1/models")!!).get().withAuth().build()).execute().use { r ->
                authOk = r.code != 401
                if (r.isSuccessful) {
                    models = json.decodeFromString(ModelsDto.serializer(), r.body?.string().orEmpty()).models
                }
            }
            ProviderStatus(
                reachable = true,
                configured = health.configured,
                authOk = authOk,
                provider = health.provider,
                version = health.version,
                models = models,
                error = when {
                    authOk == false -> "Access token galat ya missing hai."
                    !health.configured -> "Backend chal raha hai, par AI provider configured nahi hai."
                    else -> null
                },
            )
        } catch (e: ProviderException) {
            failed(e.message ?: "Not configured")
        } catch (e: IOException) {
            diagnostics.log("net", "status check failed: ${e.javaClass.simpleName}")
            failed(if (e is UnknownHostException || e is ConnectException) "Backend tak pahunch nahi paya (offline ya galat URL)." else "Connection error.")
        } catch (e: Exception) {
            failed("Backend ka response samajh nahi aaya.")
        }
    }

    private fun failed(msg: String) =
        ProviderStatus(false, false, null, null, null, emptyList(), msg)

    override fun stream(request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val base = try {
            baseUrl()
        } catch (e: ProviderException) {
            close(e); return@callbackFlow
        }
        val body = ChatBody(
            messages = request.turns.map { MsgDto(it.role, it.text) },
            style = request.style.wire,
            language = request.language.wire,
            mode = request.mode.wire,
            model = request.model,
            rules = request.rules.ifBlank { null },
        )
        val httpRequest = Request.Builder()
            .url(base.resolve("v1/chat/stream")!!)
            .post(json.encodeToString(ChatBody.serializer(), body).toRequestBody(jsonMedia))
            .header("Accept", "text/event-stream")
            .withAuth()
            .build()
        val client = baseClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(request.timeoutSec.toLong(), TimeUnit.SECONDS) // idle timeout between chunks
            .build()
        val call = client.newCall(httpRequest)

        val worker = launch(Dispatchers.IO) {
            var sawDelta = false
            var sawDone = false
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw httpError(resp)
                    val source = resp.body?.source() ?: throw ProviderException(ErrorKind.BAD_RESPONSE, "Khaali response.")
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val ev = StreamProtocol.parseLine(line) ?: continue
                        when {
                            ev.error != null -> throw errorFromCode(ev.error, null)
                            ev.delta != null -> { sawDelta = true; trySend(ChatEvent.Delta(ev.delta)) }
                            ev.done == true -> { sawDone = true; trySend(ChatEvent.Done(ev.usage, ev.model)); break }
                        }
                    }
                }
                if (!sawDone) {
                    throw ProviderException(
                        ErrorKind.BAD_RESPONSE,
                        if (sawDelta) "Reply beech mein ruk gayi." else "Backend se koi reply nahi mila.",
                    )
                }
                close()
            } catch (e: ProviderException) {
                diagnostics.log("ai", "provider error ${e.kind}")
                close(e)
            } catch (e: IOException) {
                if (call.isCanceled()) close()
                else {
                    val kind = when (e) {
                        is SocketTimeoutException -> ErrorKind.TIMEOUT
                        is UnknownHostException, is ConnectException -> ErrorKind.OFFLINE
                        else -> ErrorKind.OFFLINE
                    }
                    diagnostics.log("ai", "io error ${e.javaClass.simpleName}")
                    close(ProviderException(kind, if (kind == ErrorKind.TIMEOUT) "Backend ne time par jawab nahi diya." else "Internet/backend se connection nahi ban paya."))
                }
            }
        }
        awaitClose {
            call.cancel() // user pressed stop / screen closed: stop the HTTP stream immediately
            worker.cancel()
        }
    }.buffer(capacity = 256)

    private fun httpError(resp: Response): ProviderException {
        val code = try {
            json.decodeFromString(ErrDto.serializer(), resp.body?.string().orEmpty()).error
        } catch (_: Exception) {
            null
        }
        return errorFromCode(code, resp.code)
    }

    private fun errorFromCode(code: String?, http: Int?): ProviderException = when {
        http == 401 || code == "unauthorized" ->
            ProviderException(ErrorKind.UNAUTHORIZED, "Access token galat ya missing hai (Menu → Connectivity).")
        http == 429 || code == "rate_limited" ->
            ProviderException(ErrorKind.RATE_LIMITED, "Bahut zyada requests. Thodi der baad try karo.")
        code == "not_configured" || http == 503 ->
            ProviderException(ErrorKind.NOT_CONFIGURED, "Backend par AI provider/model configured nahi hai.")
        code == "timeout" || http == 504 ->
            ProviderException(ErrorKind.TIMEOUT, "AI provider ne time par jawab nahi diya.")
        code == "blocked" ->
            ProviderException(ErrorKind.BAD_RESPONSE, "Provider ne is request ko block kar diya.")
        code == "invalid_request" || http == 400 || http == 413 || http == 415 ->
            ProviderException(ErrorKind.BAD_RESPONSE, "Request backend ne reject kar di.")
        else -> ProviderException(ErrorKind.SERVER, "Backend/provider mein error aaya.")
    }
}
