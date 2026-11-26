package com.ultron.ai.platform.vision

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device OCR (ML Kit bundled models). The image never leaves the phone from here;
 * sending recognised text to the AI is a separate, consent-gated user action.
 */
class OcrEngine(private val context: Context) {

    suspend fun recognize(uri: Uri, devanagari: Boolean): String = suspendCancellableCoroutine { cont ->
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            cont.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        val client = if (devanagari) {
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        } else {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        client.process(image)
            .addOnSuccessListener { result ->
                client.close()
                if (cont.isActive) cont.resume(result.text)
            }
            .addOnFailureListener { e ->
                client.close()
                if (cont.isActive) cont.resumeWithException(e)
            }
        cont.invokeOnCancellation { client.close() }
    }
}
