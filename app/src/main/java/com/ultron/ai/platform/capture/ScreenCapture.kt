package com.ultron.ai.platform.capture

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import java.io.File

/**
 * User-triggered screenshot of ULTRON's OWN window (PixelCopy). No MediaProjection / consent needed for that.
 * Other apps' protected (FLAG_SECURE / DRM) content is never captured: Android blanks it and we do not try to bypass that.
 * Saved to Pictures/ULTRON (Android 10+) or the app's own Pictures folder (older, no storage permission needed).
 */
object ScreenshotSaver {
    fun capture(activity: Activity, onDone: (ok: Boolean, message: String) -> Unit) {
        try {
            val w = activity.window; val v = w.decorView
            if (v.width <= 0 || v.height <= 0) { onDone(false, "Screen abhi ready nahi hai."); return }
            if (w.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                { onDone(false, "Screenshot block hai: Menu → Privacy me \"Screenshot/recents block\" band karo."); return }
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(w, bmp, { code ->
                if (code != PixelCopy.SUCCESS) { bmp.recycle(); onDone(false, "Screenshot nahi ban paya (code $code).") }
                else Thread {
                    val r = try { save(activity.applicationContext, bmp) } catch (e: Exception) { null }
                    bmp.recycle()
                    Handler(Looper.getMainLooper()).post { if (r != null) onDone(true, "Screenshot save ho gaya: $r") else onDone(false, "Screenshot save nahi ho paya (storage bhara ya permission).") }
                }.start()
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { onDone(false, "Screenshot nahi ban paya.") }
    }

    private fun save(ctx: Context, bmp: Bitmap): String? {
        val name = "ULTRON_" + System.currentTimeMillis() + ".png"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ULTRON"); put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            val ok = ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
            if (!ok) { ctx.contentResolver.delete(uri, null, null); return null }
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0); ctx.contentResolver.update(uri, cv, null, null)
            return "Pictures/ULTRON/$name"
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ULTRON").apply { mkdirs() }
        val f = File(dir, name)
        f.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) return null }
        return f.absolutePath
    }
}
