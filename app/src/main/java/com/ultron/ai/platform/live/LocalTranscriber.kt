package com.ultron.ai.platform.live

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local command recognition with NO Gemini key: the utterance ULTRON already captured (same single mic pipeline) is fed to
 * Android's own SpeechRecognizer through RecognizerIntent.EXTRA_AUDIO_SOURCE (Android 13+). The mic is not opened a second time.
 * Honest limits: needs Android 13+ and a recognizer on the phone that supports EXTRA_AUDIO_SOURCE (Google's usually does);
 * the Google recognizer may use the internet unless an offline language pack is installed; Roman (Hinglish) text works best with en-IN.
 */
class LocalTranscriber(private val context: Context) {
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun transcribe(pcm: ShortArray, sampleRate: Int, languageTag: String = "en-IN"): Transcript {
        if (!isAvailable()) return Transcript.Failed("Bina Gemini key ke command samajhne ke liye Android 13+ ka voice recognition chahiye. Is phone par nahi mila.", false)
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(15_000) { recognize(pcm, sampleRate, languageTag) } ?: Transcript.Failed("Voice recognition ne time par jawab nahi diya.", true)
        }
    }

    private suspend fun recognize(pcm: ShortArray, rate: Int, lang: String): Transcript = suspendCancellableCoroutine { cont ->
        val rec = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        var done = false
        fun finish(t: Transcript) {
            if (done) return; done = true
            try { rec.destroy() } catch (_: Exception) {}
            try { pipe[0].close() } catch (_: Exception) {}
            try { pipe[1].close() } catch (_: Exception) {}
            if (cont.isActive) cont.resumeWith(Result.success(t))
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                finish(if (best.isEmpty()) Transcript.NoSpeech else Transcript.Text(best))
            }
            override fun onError(error: Int) {
                finish(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Transcript.NoSpeech
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Transcript.Failed("Mic permission chahiye.", false)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Transcript.Failed("Voice recognition ko internet ya offline language pack chahiye.", true)
                    else -> Transcript.Failed("Voice recognition error ($error).", true)
                })
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        if (Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        cont.invokeOnCancellation { try { rec.destroy() } catch (_: Exception) {}; try { pipe[0].close() } catch (_: Exception) {}; try { pipe[1].close() } catch (_: Exception) {} }
        rec.startListening(intent)
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    val bb = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (v in pcm) bb.putShort(v)
                    out.write(bb.array())
                }
            } catch (_: Exception) { /* recognizer closed early */ }
        }.start()
    }
}
