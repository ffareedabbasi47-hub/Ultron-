package com.ultron.ai.platform.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ultron.ai.data.db.MemoryDao
import com.ultron.ai.data.db.MemoryEntity
import com.ultron.ai.domain.ActionResult
import com.ultron.ai.domain.ActionRunner
import com.ultron.ai.domain.SettingsTarget
import com.ultron.ai.domain.SwitchResult
import com.ultron.ai.domain.ToggleHints
import com.ultron.ai.domain.ToggleTarget
import com.ultron.ai.domain.YouTubeResults
import kotlinx.coroutines.delay
import com.ultron.ai.domain.UltronAction
import com.ultron.ai.platform.reminders.ReminderScheduler
import com.ultron.ai.platform.voice.TtsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableApp(val label: String, val packageName: String)

/**
 * Executes validated actions through public Android intents only. Reports what actually
 * happened: "intent dispatched" is not the same as "it is playing", and we say so.
 */
class DeviceActionRunner(
    private val context: Context,
    private val memory: MemoryDao,
    private val reminders: ReminderScheduler,
    private val tts: TtsController,
    private val screen: com.ultron.ai.domain.ScreenControl,
    private val broker: ConfirmationBroker,
) : ActionRunner {

    /** Set by the container once the AI helper exists (avoids a construction cycle). */
    var agent: ScreenAgent? = null
    var builder: com.ultron.ai.platform.builder.BuilderAgent? = null

    override suspend fun run(action: UltronAction): ActionResult = when (action) {
        is UltronAction.OpenApp -> openApp(action.appName)
        is UltronAction.YouTubeSearch -> youTubePlay(action.query)
        is UltronAction.OpenSystemSettings -> openSettings(action.target)
        is UltronAction.SetToggle -> setToggle(action.target, action.on)
        is UltronAction.AdjustVolume -> adjustVolume(action.up)
        is UltronAction.SetBrightness -> setBrightness(action.percent, action.delta)
        is UltronAction.SetDnd -> setDnd(action.on)
        is UltronAction.MediaControl -> mediaControl(action.cmd)
        is UltronAction.SetReminder -> setReminder(action)
        is UltronAction.Remember -> remember(action.text)
        UltronAction.StopSpeaking -> {
            tts.stop()
            ActionResult(true, "Theek hai, chup.")
        }
        is UltronAction.BuildProject -> {
            val b = builder
            if (b == null) ActionResult(false, "Builder agent ready nahi hai.")
            else if (b.submit(action.brief) != null) ActionResult(true, "Builder agent ko kaam de diya. Ban jaye to bata dunga; Builder screen mein progress dikhta hai.")
            else ActionResult(false, "Brief samajh nahi aaya.")
        }
        is UltronAction.Click -> needScreen { if (screenClick(action.label)) ActionResult(true, "\"${action.label}\" dabaya.") else ActionResult(false, "\"${action.label}\" screen par nahi mila ya dab nahi paya.") }
        is UltronAction.TypeText -> needScreen { if (screen.typeText(action.text)) ActionResult(true, "Likh diya.") else ActionResult(false, "Koi input field nahi mila (ya wo password field hai).") }
        is UltronAction.Scroll -> needScreen { if (screen.scroll(action.down)) ActionResult(true, "Scroll kiya.") else ActionResult(false, "Is screen par scroll karne layak kuch nahi mila.") }
        is UltronAction.Navigate -> needScreen { if (screen.navigate(action.kind)) ActionResult(true, "Ho gaya.") else ActionResult(false, "Ye kar nahi paya.") }
        UltronAction.ReadScreen -> needScreen { readScreen() }
        is UltronAction.ScreenTask -> agent?.run(action.goal) ?: ActionResult(false, "Screen agent ready nahi hai.")
    }

    private suspend fun needScreen(block: suspend () -> ActionResult): ActionResult =
        if (!screen.isEnabled()) ActionResult(false, "Screen control off hai. Menu → Assistant & permissions mein ULTRON ka Accessibility service on karo.") else block()

    private suspend fun screenClick(label: String): Boolean {
        if (com.ultron.ai.domain.RiskPolicy.needsConfirmation(label)) {
            val yes = broker.ask("\"$label\" dabane wala hoon. Karun? Haan ya nahi bolo.", { t -> val k = tts.beginSpeech(0L); tts.enqueue(k, t); tts.endSpeech(k) })
            if (!yes) return false
        }
        return screen.clickLabel(label)
    }

    private fun readScreen(): ActionResult {
        val snap = screen.snapshot() ?: return ActionResult(false, "Screen padh nahi paya (secure screen ho sakti hai).")
        if (com.ultron.ai.domain.RiskPolicy.packageBlocked(snap.packageName)) return ActionResult(false, "Is screen ko main nahi padhta.")
        val texts = snap.nodes.map { it.label }.filter { it.isNotBlank() && it != "(password field)" }.distinct().take(12)
        return if (texts.isEmpty()) ActionResult(true, "Screen par padhne layak text nahi mila.") else ActionResult(true, "Screen par: " + texts.joinToString(", "))
    }

    // ---- apps ----------------------------------------------------------------------------

    suspend fun launchableApps(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        infos.map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
    }

    private suspend fun openApp(name: String): ActionResult {
        val match = AppMatcher.best(name, launchableApps())
            ?: return ActionResult(
                false,
                "\"$name\" naam ka launchable app nahi mila. Naam thoda alag bolke try karo.",
            )
        val launch = context.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return ActionResult(false, "${match.label} ka launch intent available nahi hai.")
        return try {
            BackgroundLaunch.launch(context, launch, match.label, "${match.label} kholne ka intent bhej diya.")
        } catch (e: ActivityNotFoundException) {
            ActionResult(false, "${match.label} khul nahi paya.")
        } catch (e: SecurityException) {
            ActionResult(false, "Android ne ${match.label} kholne ki permission nahi di.")
        }
    }

    // ---- YouTube -------------------------------------------------------------------------

    /** Finds the first real video for [query] and opens it directly (YouTube autoplays a watch link). Falls back to the results page. */
    private suspend fun youTubePlay(query: String): ActionResult {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val wasPlaying = audio?.isMusicActive == true
        val id = withContext(Dispatchers.IO) { firstVideoId(query) }
        val uri = Uri.parse(if (id != null) YouTubeResults.watchUrl(id) else YouTubeResults.resultsUrl(Uri.encode(query)))
        return try {
            val direct = BackgroundLaunch.canStartDirectly(context)
            val r = BackgroundLaunch.launch(context, Intent(Intent.ACTION_VIEW, uri), "YouTube: $query",
                if (id != null) "YouTube par \"$query\" ka pehla video khol diya." else "Video seedha nahi dhoondh paya, isliye \"$query\" ke search results khol diye. Video aap chun lo.")
            if (!r.success || !direct || id == null) r
            else {
                // Best available evidence: Android reports whether any media stream is active. It cannot say WHICH app is playing.
                delay(5000)
                val now = audio?.isMusicActive == true
                when {
                    now && !wasPlaying -> ActionResult(true, "Video khol diya aur audio chalna shuru ho gaya (Android ne media active detect kiya).")
                    now && wasPlaying -> ActionResult(true, "Video khol diya. Pehle se koi audio chal raha tha, isliye main pakka nahi bata sakta ki ye naya video chal raha hai.")
                    else -> ActionResult(true, "Video khol diya, par audio chalta detect nahi hua. Shayad autoplay band ho ya video load ho raha ho; screen par dekh lo. Main ye nahi keh sakta ki video chal raha hai.")
                }
            }
        } catch (e: ActivityNotFoundException) {
            ActionResult(false, "YouTube kholne ke liye koi app/browser nahi mila.")
        }
    }

    private fun firstVideoId(query: String): String? = try {
        val c = java.net.URL(YouTubeResults.resultsUrl(Uri.encode(query))).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Cookie", "CONSENT=YES+1")
        try { if (c.responseCode == 200) YouTubeResults.firstVideoId(c.inputStream.bufferedReader().use { it.readText().take(1_500_000) }) else null } finally { c.disconnect() }
    } catch (_: Exception) { null }

    // ---- phone switches --------------------------------------------------------------------

    // ---- volume / brightness / DND / media ---------------------------------------------------

    private fun adjustVolume(up: Boolean): ActionResult {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return ActionResult(false, "Audio service nahi mili.")
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val before = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val steps = maxOf(1, max / 5)
        repeat(steps) {
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, 0)
        }
        val after = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        return when {
            after == before -> ActionResult(false, if (up) "Media volume pehle se maximum hai." else "Media volume pehle se minimum hai.")
            else -> ActionResult(true, "Media volume $before se $after kar diya (max $max). Ringtone ka volume alag hota hai, wo nahi badla.")
        }
    }

    private fun openGrant(intent: Intent, what: String): ActionResult = try {
        BackgroundLaunch.launch(context, intent, what, "")
        ActionResult(false, "$what ke liye permission chahiye. Wo screen khol di hai: ULTRON ko allow kar do, phir dobara bolo. Ye permission sirf tum de sakte ho.")
    } catch (e: Exception) { ActionResult(false, "$what ki permission screen khul nahi payi.") }

    private fun setBrightness(percent: Int?, delta: Int): ActionResult {
        if (!Settings.System.canWrite(context)) {
            return openGrant(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)), "Brightness badalne")
        }
        return try {
            val cr = context.contentResolver
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val target = (percent?.let { it * 255 / 100 } ?: (cur + delta * 255 / 100)).coerceIn(1, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
            val now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (now == target) ActionResult(true, "Brightness ${now * 100 / 255} percent kar di (auto-brightness band ho gayi).")
            else ActionResult(false, "Brightness badalne ki koshish ki par phone ne value accept nahi ki.")
        } catch (e: SecurityException) { ActionResult(false, "Android ne brightness badalne nahi di.") }
    }

    private suspend fun setDnd(on: Boolean): ActionResult {
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return ActionResult(false, "Notification service nahi mili.")
        if (!nm.isNotificationPolicyAccessGranted) return openGrant(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "Do Not Disturb badalne")
        val want = if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        return try {
            nm.setInterruptionFilter(want)
            delay(300)
            if (nm.currentInterruptionFilter == want) ActionResult(true, if (on) "Do Not Disturb on ho gaya (sirf priority wale alerts aayenge)." else "Do Not Disturb band ho gaya.")
            else ActionResult(false, "Do Not Disturb badalne ki koshish ki par phone ne state nahi badli.")
        } catch (e: SecurityException) { ActionResult(false, "Android ne Do Not Disturb badalne nahi diya.") }
    }

    private fun sendMediaKey(code: Int) {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
        val t = android.os.SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0))
    }

    private suspend fun mediaControl(cmd: com.ultron.ai.domain.MediaCmd): ActionResult {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return ActionResult(false, "Audio service nahi mili.")
        val active = am.isMusicActive
        return when (cmd) {
            com.ultron.ai.domain.MediaCmd.PAUSE, com.ultron.ai.domain.MediaCmd.CLOSE_YOUTUBE -> {
                if (!active) return ActionResult(false, "Abhi koi media chalta nahi mila.")
                sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); delay(900)
                if (am.isMusicActive) ActionResult(false, "Pause ka signal bheja par media ab bhi chalta dikh raha hai. App ne shayad sunna nahi.")
                else if (cmd == com.ultron.ai.domain.MediaCmd.CLOSE_YOUTUBE) {
                    // An app cannot force-close another app; stopping playback and going Home is the supported way.
                    val home = screen.isEnabled() && screen.navigate(com.ultron.ai.domain.NavKind.HOME)
                    ActionResult(true, if (home) "Playback roka aur home par aa gaya. YouTube app ko main band (kill) nahi kar sakta." else "Playback rok diya. YouTube app ko main band (kill) nahi kar sakta; use Recents se hata do.")
                } else ActionResult(true, "Pause ho gaya.")
            }
            com.ultron.ai.domain.MediaCmd.PLAY -> {
                if (active) return ActionResult(true, "Media pehle se chal raha hai.")
                sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY); delay(1200)
                if (am.isMusicActive) ActionResult(true, "Resume ho gaya.") else ActionResult(false, "Resume ka signal bheja par kuch chalta detect nahi hua (koi paused media session nahi ho sakta).")
            }
            com.ultron.ai.domain.MediaCmd.NEXT, com.ultron.ai.domain.MediaCmd.PREVIOUS -> {
                if (!active) return ActionResult(false, "Abhi koi media chalta nahi mila.")
                sendMediaKey(if (cmd == com.ultron.ai.domain.MediaCmd.NEXT) android.view.KeyEvent.KEYCODE_MEDIA_NEXT else android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                ActionResult(true, "${if (cmd == com.ultron.ai.domain.MediaCmd.NEXT) "Next" else "Previous"} ka signal bhej diya. Track sach me badla ya nahi, Android ye nahi batata; app support na kare to kuch nahi hoga.")
            }
        }
    }

    private fun panel(action: String) = if (Build.VERSION.SDK_INT >= 29) Intent(action) else null

    private fun toggleIntents(t: ToggleTarget): List<Intent> = listOfNotNull(
        *when (t) {
            ToggleTarget.WIFI -> arrayOf(panel(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
            ToggleTarget.HOTSPOT -> arrayOf(
                Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS),
            )
            ToggleTarget.BLUETOOTH -> arrayOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            ToggleTarget.AIRPLANE -> arrayOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            ToggleTarget.MOBILE_DATA -> arrayOf(panel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            ToggleTarget.LOCATION -> arrayOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            ToggleTarget.NFC -> arrayOf(Intent(Settings.ACTION_NFC_SETTINGS))
            ToggleTarget.TORCH -> emptyArray()
        },
    )

    private fun label(t: ToggleTarget) = when (t) {
        ToggleTarget.WIFI -> "Wi-Fi"; ToggleTarget.HOTSPOT -> "Hotspot"; ToggleTarget.BLUETOOTH -> "Bluetooth"
        ToggleTarget.AIRPLANE -> "Airplane mode"; ToggleTarget.MOBILE_DATA -> "Mobile data"
        ToggleTarget.LOCATION -> "Location"; ToggleTarget.NFC -> "NFC"; ToggleTarget.TORCH -> "Torch"
    }

    private suspend fun setToggle(t: ToggleTarget, on: Boolean): ActionResult {
        val name = label(t); val word = if (on) "on" else "off"
        if (t == ToggleTarget.TORCH) return torch(on)
        // Android 10+ gives apps no API to flip these. The honest route: open the real Settings screen, then press the switch
        // through the Accessibility service the user turned on. Without it, only the screen is opened.
        if (t == ToggleTarget.AIRPLANE && on) {
            val yes = broker.ask("Airplane mode on karne se internet bhi band ho jayega, aur main bhi online kaam nahi kar paunga. Karun? Haan ya nahi bolo.", { x -> val k = tts.beginSpeech(0L); tts.enqueue(k, x); tts.endSpeech(k) })
            if (!yes) return ActionResult(false, "Theek hai, airplane mode on nahi kiya.")
        }
        val hints = ToggleHints.hints(t)
        val allowSingle = t != ToggleTarget.HOTSPOT && t != ToggleTarget.MOBILE_DATA
        val direct = BackgroundLaunch.canStartDirectly(context)
        var opened = false
        for (intent in toggleIntents(t)) {
            try { BackgroundLaunch.launch(context, intent, "$name settings", "") } catch (_: Exception) { continue }
            opened = true
            if (!direct || !screen.isEnabled()) break
            delay(1400)
            var r = SwitchResult.NOT_FOUND
            for (attempt in 0 until 3) {
                r = withContext(Dispatchers.Default) { screen.setSwitch(hints, on, allowSingle) }
                if (r != SwitchResult.NOT_FOUND) break
                delay(700)
            }
            when (r) {
                SwitchResult.ALREADY -> { screen.navigate(com.ultron.ai.domain.NavKind.BACK); return ActionResult(true, "$name pehle se $word hai.") }
                SwitchResult.CHANGED -> { screen.navigate(com.ultron.ai.domain.NavKind.BACK); return ActionResult(true, "$name $word kar diya.") }
                SwitchResult.FAILED -> return ActionResult(false, "$name ka switch mila par badal nahi paya. Screen khuli hai, aap dabao.")
                SwitchResult.NOT_FOUND -> { /* try the next screen */ }
            }
        }
        if (!opened) return ActionResult(false, "$name ki settings screen is phone par nahi khuli.")
        return if (!direct) ActionResult(true, "Background se Android screen seedha nahi kholne deta; notification aayi hai, use tap karo. ($name ko $word karna hai.)")
        else if (!screen.isEnabled()) ActionResult(true, "$name ki screen khol di hai. Android 10+ par app khud switch nahi dabane deta; ULTRON ka Accessibility service on kar do (Menu → Assistant & permissions) to main $word bhi kar dunga.")
        else ActionResult(false, "$name ki screen khuli hai par switch pehchan nahi paya (phone ka layout alag hai). Aap $word kar lo.")
    }

    private fun torch(on: Boolean): ActionResult = try {
        val cm = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
        if (id == null) ActionResult(false, "Is phone mein torch nahi mili.")
        else { cm.setTorchMode(id, on); ActionResult(true, if (on) "Torch on kar di." else "Torch band kar di.") }
    } catch (e: Exception) { ActionResult(false, "Torch ${if (on) "on" else "off"} nahi ho payi (camera abhi kisi aur app ke paas ho sakta hai).") }

    // ---- system settings -----------------------------------------------------------------

    private fun openSettings(target: SettingsTarget): ActionResult {
        val intent = when (target) {
            SettingsTarget.MAIN -> Intent(Settings.ACTION_SETTINGS)
            SettingsTarget.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            SettingsTarget.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingsTarget.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            SettingsTarget.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
            SettingsTarget.HOTSPOT -> Intent("android.settings.TETHER_SETTINGS")
            SettingsTarget.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            SettingsTarget.MOBILE_DATA -> Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            SettingsTarget.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SettingsTarget.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            SettingsTarget.APPS -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            SettingsTarget.STORAGE -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            SettingsTarget.NFC -> Intent(Settings.ACTION_NFC_SETTINGS)
            SettingsTarget.DATE -> Intent(Settings.ACTION_DATE_SETTINGS)
            SettingsTarget.LANGUAGE -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            SettingsTarget.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            SettingsTarget.SECURITY -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            SettingsTarget.NETWORK -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            SettingsTarget.DND -> Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
            SettingsTarget.APP_NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(true, "Settings screen kholne ka intent bhej diya. Toggle aap khud karoge; main protected settings change nahi karta.")
        } catch (e: ActivityNotFoundException) {
            ActionResult(false, "Ye settings screen is device par available nahi hai.")
        }
    }

    // ---- reminders / memory --------------------------------------------------------------

    private suspend fun setReminder(a: UltronAction.SetReminder): ActionResult {
        val r = reminders.schedule(a.text, a.delayMinutes)
        return if (r.notificationsEnabled) {
            ActionResult(
                true,
                "Reminder save ho gaya: \"${a.text}\", lagbhag ${a.delayMinutes} minute baad. Timing approximate hai; battery saver se thodi der ho sakti hai.",
            )
        } else {
            ActionResult(
                false,
                "Reminder save kiya, par notifications blocked hain, isliye alert nahi dikhega. Menu → Automation se notification permission do.",
            )
        }
    }

    private suspend fun remember(text: String): ActionResult {
        val t = System.currentTimeMillis()
        memory.insert(MemoryEntity(text = text, source = "command", createdAt = t, updatedAt = t))
        return ActionResult(true, "Yaadein mein save kar liya: \"$text\". Aap use kabhi bhi edit ya delete kar sakte ho.")
    }
}

/** Fuzzy label matching for "open <app>". Pure logic. */
object AppMatcher {
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun best(query: String, apps: List<LaunchableApp>): LaunchableApp? {
        val q = norm(query)
        if (q.isEmpty()) return null
        return apps
            .map { app ->
                val l = norm(app.label)
                val score = when {
                    l == q -> 3
                    l.startsWith(q) -> 2
                    l.contains(q) -> 1
                    else -> 0
                }
                Triple(app, score, l.length)
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<LaunchableApp, Int, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()?.first
    }
}
