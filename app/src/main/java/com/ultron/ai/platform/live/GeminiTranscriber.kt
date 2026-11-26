package com.ultron.ai.platform.live

import android.util.Base64
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.ai.GeminiWire
import com.ultron.ai.data.ai.ThinkingMode
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.live.Wav
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface Transcript {
    data class Text(val text: String) : Transcript
    data object NoSpeech : Transcript
    data class Failed(val message: String, val retryable: Boolean) : Transcript
}

/**
 * Turns ONE finished utterance (never continuous audio) into text using the user's own Gemini key.
 * Only the utterance's PCM is uploaded, and only after the user has allowed cloud AI. Nothing is stored.
 */
class GeminiTranscriber(
    private val settings: SettingsRepository,
    private val baseClient: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
) {
    private val media = "application/json; charset=utf-8".toMediaType()

    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): Transcript = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        if (!s.cloudConsent) return@withContext Transcript.Failed("Live voice ke liye cloud AI ki permission chahiye (Privacy mein allow karo).", false)
        val key = settings.geminiKey() ?: return@withContext Transcript.Failed("Live voice ke liye Gemini API key chahiye (Menu → AI Brain).", false)
        val model = s.geminiModel.takeIf(GeminiWire::isSafeModelId) ?: GeminiWire.DEFAULT_MODEL
        val wav = Base64.encodeToString(Wav.wrap(pcm, sampleRate), Base64.NO_WRAP)
        val client = baseClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        try {
            var thinking = GeminiWire.thinkingFor(model)
            repeat(2) {
                val req = Request.Builder().url(GeminiWire.generateUrl(model)).header("x-goog-api-key", key)
                    .post(GeminiWire.transcribeBody(wav, thinking).toRequestBody(media)).build()
                client.newCall(req).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    when {
                        r.isSuccessful -> {
                            val t = GeminiWire.cleanTranscript(GeminiWire.parseText(body))
                            return@withContext if (t == null) Transcript.NoSpeech else Transcript.Text(t)
                        }
                        r.code == 400 && thinking != ThinkingMode.NONE && GeminiWire.looksLikeThinkingRejection(body) -> thinking = ThinkingMode.NONE
                        r.code == 401 || r.code == 403 -> return@withContext Transcript.Failed("Gemini key galat hai ya permission nahi.", false)
                        r.code == 429 -> return@withContext Transcript.Failed("Gemini limit poori ho gayi. Thodi der baad.", true)
                        else -> return@withContext Transcript.Failed("Samajhne mein error (HTTP ${r.code}).", r.code >= 500)
                    }
                    Unit
                }
            }
            Transcript.Failed("Samajhne mein error.", true)
        } catch (e: IOException) {
            diagnostics.log("stt", "transcribe io ${e.javaClass.simpleName}")
            Transcript.Failed("Internet nahi hai ya slow hai.", true)
        }
    }
}
