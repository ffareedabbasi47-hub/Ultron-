package com.ultron.ai.platform.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.ultron.ai.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * In-app screen recording, only ever started by the user's tap + Android's own MediaProjection consent dialog.
 * A foreground notification (with Stop) is shown for the whole recording. Everything is released on stop/interrupt.
 * Audio: optional MICROPHONE only. Device (internal) audio is NOT recorded in this build.
 * Android blanks other apps' protected/DRM content in the video; that is not bypassed.
 */
class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: android.net.Uri? = null
    private var file: File? = null
    private var label = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finishRecording(); stopSelf() }
            ACTION_START -> if (_recording.value) { /* already recording: ignore a second start */ } else startRecording(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun fail(msg: String) { _error.value = msg; cleanup(deleteOutput = true); stopSelf() }

    private fun startRecording(i: Intent) {
        _error.value = null
        val code = i.getIntExtra(EXTRA_CODE, 0); val data: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(EXTRA_DATA)
        val mic = i.getBooleanExtra(EXTRA_MIC, false)
        // Must be in the foreground BEFORE the projection is used (Android 14+).
        try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { fail("Recording service start nahi hui."); return }
        if (data == null) { fail("Screen capture ki permission nahi mili."); return }
        val free = try { StatFs((getExternalFilesDir(null) ?: filesDir).absolutePath).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (free < 200L * 1024 * 1024) { fail("Storage kam hai (200 MB se kam). Jagah banao aur dobara try karo."); return }
        try {
            val dm = resources.displayMetrics
            var w = dm.widthPixels; var h = dm.heightPixels
            val scale = if (w > 1280) 1280f / w else 1f
            w = ((w * scale).toInt() / 2) * 2; h = ((h * scale).toInt() / 2) * 2
            val rec = MediaRecorder().also { recorder = it }
            if (mic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            openOutput(rec)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (mic) { rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); rec.setAudioEncodingBitRate(96_000); rec.setAudioSamplingRate(44_100) }
            rec.setVideoSize(w, h); rec.setVideoFrameRate(30); rec.setVideoEncodingBitRate(6_000_000)
            rec.prepare()
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data).also { projection = it }
            // Android 14+ requires a callback BEFORE the virtual display; also tells us if the user/system ends the projection.
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { finishRecording(); stopSelf() } }, null)
            display = p.createVirtualDisplay("ULTRON-rec", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null)
            rec.start()
            _recording.value = true
        } catch (e: Exception) { fail("Recording shuru nahi ho payi: " + (e.message ?: e.javaClass.simpleName).take(80)) }
    }

    private fun openOutput(rec: MediaRecorder) {
        val name = "ULTRON_" + System.currentTimeMillis() + ".mp4"
        label = if (Build.VERSION.SDK_INT >= 29) "Movies/ULTRON/$name" else name
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ULTRON"); put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("MediaStore insert failed")
            uri = u
            pfd = contentResolver.openFileDescriptor(u, "w") ?: throw IllegalStateException("cannot open output")
            rec.setOutputFile(pfd!!.fileDescriptor)
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ULTRON").apply { mkdirs() }
            file = File(dir, name); label = file!!.absolutePath
            rec.setOutputFile(file!!.absolutePath)
        }
    }

    /** Stop normally: finalise and keep the video. Safe to call more than once. */
    private fun finishRecording() {
        if (!_recording.value && recorder == null) return
        var ok = _recording.value
        try { if (_recording.value) recorder?.stop() } catch (e: RuntimeException) { ok = false; _error.value = "Recording bahut chhoti thi ya beech me ruk gayi; video save nahi hua." }
        _recording.value = false
        if (ok) { _lastSaved.value = label }
        cleanup(deleteOutput = !ok)
    }

    private fun cleanup(deleteOutput: Boolean) {
        try { display?.release() } catch (_: Exception) {}
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        val u = uri
        if (u != null) {
            if (deleteOutput) try { contentResolver.delete(u, null, null) } catch (_: Exception) {}
            else try { contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) } catch (_: Exception) {}
        } else if (deleteOutput) try { file?.delete() } catch (_: Exception) {}
        display = null; recorder = null; projection = null; pfd = null; uri = null; file = null
        _recording.value = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    override fun onDestroy() { finishRecording(); super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { finishRecording(); stopSelf(); super.onTaskRemoved(rootIntent) }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "ULTRON screen recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 7, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ULTRON screen record kar raha hai").setContentText("Rokne ke liye Stop dabao")
            .setOngoing(true).addAction(0, "Stop", stop).build()
    }

    companion object {
        const val ACTION_START = "com.ultron.ai.REC_START"; const val ACTION_STOP = "com.ultron.ai.REC_STOP"
        const val EXTRA_CODE = "code"; const val EXTRA_DATA = "data"; const val EXTRA_MIC = "mic"
        private const val CHANNEL = "ultron_record"; private const val NOTIF_ID = 4107
        private val _recording = MutableStateFlow(false); val recording: StateFlow<Boolean> = _recording
        private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
        private val _lastSaved = MutableStateFlow<String?>(null); val lastSaved: StateFlow<String?> = _lastSaved

        fun start(ctx: Context, code: Int, data: Intent, mic: Boolean) {
            val i = Intent(ctx, ScreenRecordService::class.java).setAction(ACTION_START).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data).putExtra(EXTRA_MIC, mic)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, ScreenRecordService::class.java).setAction(ACTION_STOP)) }
    }
}
