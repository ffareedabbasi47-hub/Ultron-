package com.ultron.ai.platform.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import androidx.core.content.ContextCompat
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Real, measured capture facts for the Voice Diagnostics screen (nothing here is hard-coded "ON"). */
data class MicInfo(
    val recording: Boolean = false,
    val sampleRate: Int = 0,
    val source: String = "-",
    val route: String = "-",
    val aecAvailable: Boolean = false,
    val aecEnabled: Boolean = false,
    val nsAvailable: Boolean = false,
    val nsEnabled: Boolean = false,
    val agcEnabled: Boolean = false,
    val recorderOpens: Int = 0,
    val recoveries: Int = 0,
    val silencedBySystem: Boolean = false,
    val lastError: String? = null,
)

/**
 * THE one microphone owner. One AudioRecord lives from [start] until [stop]; speech boundaries,
 * STT, AI and TTS never touch it. A read failure triggers a bounded, logged recovery (max 3 per minute),
 * after which [onFatal] is called instead of looping forever.
 */
class MicCapture(private val context: Context, private val diagnostics: DiagnosticsLog) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    }

    private val _info = MutableStateFlow(MicInfo())
    val info: StateFlow<MicInfo> = _info

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val recoveryTimes = ArrayDeque<Long>()
    private var opens = 0
    private var recoveries = 0
    private var recordingCallback: Any? = null

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val isRunning: Boolean get() = running

    /** @return false if it could not start (no permission / recorder failed); [onFatal] explains. */
    @Synchronized
    fun start(onFrame: (ShortArray) -> Unit, onFatal: (String) -> Unit, onSilenced: (Boolean) -> Unit): Boolean {
        if (running) return true
        if (!hasPermission()) { onFatal("PERMISSION"); return false }
        if (!open()) { onFatal("Recorder start nahi hua (mic shayad kisi aur app ke paas hai)."); return false }
        running = true
        registerSilenceCallback(onSilenced)
        val t = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(FRAME_SAMPLES)
            while (running) {
                val rec = record
                val n = try { rec?.read(buf, 0, buf.size) ?: -1 } catch (e: Exception) { -1 }
                if (!running) break
                if (n == buf.size) {
                    onFrame(buf.copyOf())
                } else if (n < 0) {
                    if (!recover(onFatal)) break
                }
            }
        }, "ultron-mic")
        thread = t
        t.start()
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        val t = thread
        thread = null
        unregisterSilenceCallback()
        try { record?.stop() } catch (_: Exception) {}
        if (t != null && t !== Thread.currentThread()) try { t.join(800) } catch (_: InterruptedException) {}
        closeRecorder()
        _info.value = _info.value.copy(recording = false)
    }

    private fun open(): Boolean {
        closeRecorder()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { fail("getMinBufferSize=$min"); return false }
        val size = maxOf(min, FRAME_SAMPLES * 2 * 8)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (e: SecurityException) { fail("security"); return false } catch (e: Exception) { fail(e.javaClass.simpleName); return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); fail("not initialized"); return false }
        // Platform audio effects are attached to THIS recorder's session. Availability and the real enabled state are measured.
        val aecAvail = AcousticEchoCanceler.isAvailable()
        val nsAvail = NoiseSuppressor.isAvailable()
        try { if (aecAvail) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true } } catch (e: Exception) { diagnostics.log("mic", "aec create failed ${e.javaClass.simpleName}") }
        try { if (nsAvail) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true } } catch (e: Exception) { diagnostics.log("mic", "ns create failed ${e.javaClass.simpleName}") }
        // AGC is intentionally NOT enabled: it can amplify residual echo/noise.
        try { r.startRecording() } catch (e: Exception) { r.release(); fail("startRecording ${e.javaClass.simpleName}"); return false }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); fail("not recording (mic busy/blocked)"); return false }
        record = r
        opens++
        val route = try { r.routedDevice?.let { "${it.productName} (type ${it.type})" } ?: "default" } catch (_: Exception) { "unknown" }
        _info.value = MicInfo(
            recording = true, sampleRate = SAMPLE_RATE, source = "VOICE_COMMUNICATION", route = route,
            aecAvailable = aecAvail, aecEnabled = aec?.enabled == true,
            nsAvailable = nsAvail, nsEnabled = ns?.enabled == true, agcEnabled = false,
            recorderOpens = opens, recoveries = recoveries, silencedBySystem = false, lastError = null,
        )
        diagnostics.log("mic", "recorder open #$opens aec=${aec?.enabled} ns=${ns?.enabled}")
        return true
    }

    private fun recover(onFatal: (String) -> Unit): Boolean {
        val now = System.nanoTime() / 1_000_000
        while (recoveryTimes.isNotEmpty() && now - recoveryTimes.first() > 60_000) recoveryTimes.removeFirst()
        if (recoveryTimes.size >= 3) {
            running = false
            closeRecorder()
            onFatal("Mic baar-baar fail ho raha hai. Dusri app mic use kar rahi ho sakti hai.")
            return false
        }
        recoveryTimes.addLast(now)
        recoveries++
        diagnostics.log("mic", "read failed: reopening recorder (recovery #$recoveries)")
        try { Thread.sleep(250) } catch (_: InterruptedException) { return false }
        val ok = open()
        if (!ok) { running = false; onFatal("Mic dobara start nahi hua."); return false }
        return true
    }

    private fun closeRecorder() {
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        aec = null; ns = null; agc = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
    }

    private fun fail(msg: String) {
        diagnostics.log("mic", "open failed: $msg")
        _info.value = _info.value.copy(recording = false, lastError = msg)
    }

    private fun registerSilenceCallback(onSilenced: (Boolean) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                val sid = record?.audioSessionId ?: return
                val mine = configs?.firstOrNull { it.clientAudioSessionId == sid } ?: return
                val silenced = if (android.os.Build.VERSION.SDK_INT >= 29) mine.isClientSilenced else false
                _info.value = _info.value.copy(silencedBySystem = silenced)
                onSilenced(silenced)
            }
        }
        recordingCallback = cb
        am.registerAudioRecordingCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    private fun unregisterSilenceCallback() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val cb = recordingCallback as? AudioManager.AudioRecordingCallback ?: return
        context.getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(cb)
        recordingCallback = null
    }
}
