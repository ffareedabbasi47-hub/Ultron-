package com.ultron.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Real audio focus + output-route tracking. Focus is requested only while a voice session is active and always
 * abandoned in [release]; the route follows headset / Bluetooth plug events through an [AudioDeviceCallback]
 * that is registered only between [acquire] and [release] (no leaked listeners).
 */
class AndroidAudioSessionManager(context: Context) : AudioSessionManager {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _route = MutableStateFlow(AudioSessionManager.Route.UNKNOWN)
    override val route: StateFlow<AudioSessionManager.Route> = _route
    private var request: AudioFocusRequest? = null
    private var callback: AudioDeviceCallback? = null

    @Synchronized
    override fun acquire(onFocus: (AudioSessionManager.FocusEvent) -> Unit): Boolean {
        release()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setOnAudioFocusChangeListener({ change -> onFocus(map(change)) }, main)
            .build()
        val ok = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!ok) return false
        request = req
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { _route.value = currentRoute() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { _route.value = currentRoute() }
        }
        callback = cb
        audio.registerAudioDeviceCallback(cb, main)
        _route.value = currentRoute()
        return true
    }

    @Synchronized
    override fun release() {
        callback?.let { runCatching { audio.unregisterAudioDeviceCallback(it) } }
        callback = null
        request?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        request = null
    }

    private fun currentRoute(): AudioSessionManager.Route =
        routeOf(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })

    companion object {
        fun map(change: Int): AudioSessionManager.FocusEvent = when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> AudioSessionManager.FocusEvent.LOST_PERMANENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioSessionManager.FocusEvent.LOST_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioSessionManager.FocusEvent.LOST_CAN_DUCK
            else -> AudioSessionManager.FocusEvent.REGAINED
        }

        /** Priority: Bluetooth > wired > USB > built-in speaker. Takes raw AudioDeviceInfo types (pure, testable). */
        fun routeOf(types: List<Int>): AudioSessionManager.Route = when {
            types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == 26 /* BLE headset */ } -> AudioSessionManager.Route.BLUETOOTH
            types.any { it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } -> AudioSessionManager.Route.WIRED
            types.any { it == AudioDeviceInfo.TYPE_USB_HEADSET || it == AudioDeviceInfo.TYPE_USB_DEVICE } -> AudioSessionManager.Route.USB
            types.any { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } -> AudioSessionManager.Route.SPEAKER
            types.any { it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } -> AudioSessionManager.Route.EARPIECE
            else -> AudioSessionManager.Route.UNKNOWN
        }
    }
}
