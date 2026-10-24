package com.ultron.ai.platform.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ultron.ai.MainActivity
import com.ultron.ai.UltronApp
import com.ultron.ai.domain.live.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type "microphone") that keeps the process alive while a live session is on, and shows the
 * honest, always-visible notification with Stop / Pause / Wake. The service owns no audio itself: the
 * [LiveSessionEngine] in the app container does, so the lifetime of the mic is never tied to an Activity.
 */
class LiveSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var engine: LiveSessionEngine
    private lateinit var overlay: OverlayLight
    private var overlayWanted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = (application as UltronApp).container.live
        ensureChannel()
        overlay = OverlayLight(applicationContext)
        scope.launch { (application as UltronApp).container.settings.settings.collect { overlayWanted = it.overlayLight } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> { engine.stop(); shutdown(); return START_NOT_STICKY }
            ACTION_PAUSE -> engine.pause()
            ACTION_WAKE, ACTION_RESUME -> engine.start()
            else -> {
                if (!engine.mic.hasPermission()) { stopSelf(); return START_NOT_STICKY }
                promote(engine.state.value)
                engine.start()
            }
        }
        if (observer == null) observer = scope.launch {
            engine.state.collect { st ->
                if (st == LiveState.STOPPED) { shutdown(); return@collect }
                updateWakeLock(st)
                updateOverlay(st)
                notify(st)
            }
        }
        return START_NOT_STICKY // never silently restart and re-open the mic after the OS killed us
    }

    private fun promote(st: LiveState) {
        val n = build(st)
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android refused (e.g. started from the background): do not pretend to be listening.
            stopSelf()
        }
    }

    private fun notify(st: LiveState) { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(st)) }

    private fun build(st: LiveState): Notification {
        fun action(label: String, act: String, req: Int) = NotificationCompat.Action.Builder(
            0, label, PendingIntent.getService(this, req, Intent(this, LiveSessionService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        val text = when (st) {
            LiveState.LISTENING, LiveState.USER_SPEECH -> "Microphone active: sun raha hoon"
            LiveState.THINKING -> "Soch raha hoon…"
            LiveState.SPEAKING -> "ULTRON bol raha hai"
            LiveState.SLEEP -> if (engine.mic.isRunning) "Sleep: 'Hey Ultron' ka intezar (mic on)" else "Sleep: mic band hai. Wake dabao."
            LiveState.PAUSED -> "Paused: mic band hai"
            LiveState.STARTING -> "Start ho raha hai…"
            LiveState.PERMISSION_REQUIRED -> "Microphone permission chahiye"
            LiveState.AUDIO_ERROR -> "Audio error: " + (engine.message.value ?: "mic nahi chala")
            LiveState.STOPPED -> "Band"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("ULTRON Live — " + st.label).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(action("Stop", ACTION_STOP, 1))
        when (st) {
            LiveState.PAUSED, LiveState.SLEEP, LiveState.AUDIO_ERROR, LiveState.PERMISSION_REQUIRED -> b.addAction(action(if (st == LiveState.SLEEP) "Wake" else "Resume", ACTION_RESUME, 2))
            else -> b.addAction(action("Pause", ACTION_PAUSE, 3))
        }
        return b.build()
    }

    private fun updateWakeLock(st: LiveState) {
        val need = st == LiveState.LISTENING || st == LiveState.USER_SPEECH || st == LiveState.THINKING || st == LiveState.SPEAKING || (st == LiveState.SLEEP && engine.mic.isRunning)
        if (need && wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultron:live").apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        } else if (!need) { wake?.let { if (it.isHeld) it.release() }; wake = null }
    }

    private fun updateOverlay(st: LiveState) {
        val live = st == LiveState.LISTENING || st == LiveState.USER_SPEECH || st == LiveState.THINKING || st == LiveState.SPEAKING
        if (live && overlayWanted) overlay.show(intense = st == LiveState.SPEAKING || st == LiveState.USER_SPEECH) else overlay.hide()
    }

    private fun shutdown() {
        overlay.hide()
        observer?.cancel(); observer = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { /* the session keeps running; Stop is in the notification */ }

    override fun onDestroy() {
        overlay.hide()
        engine.stop()
        wake?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "ULTRON Live", NotificationManager.IMPORTANCE_LOW).apply { description = "Shows while the microphone is active" })
    }

    companion object {
        const val CHANNEL = "ultron_live"
        const val NOTIF_ID = 4107
        const val ACTION_START = "com.ultron.ai.live.START"
        const val ACTION_STOP = "com.ultron.ai.live.STOP"
        const val ACTION_PAUSE = "com.ultron.ai.live.PAUSE"
        const val ACTION_RESUME = "com.ultron.ai.live.RESUME"
        const val ACTION_WAKE = "com.ultron.ai.live.WAKE"

        /** Must be called from a user-visible interaction (button, assistant gesture). */
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, LiveSessionService::class.java).setAction(ACTION_START)) }
        fun stop(context: Context) { context.startService(Intent(context, LiveSessionService::class.java).setAction(ACTION_STOP)) }
    }
}
