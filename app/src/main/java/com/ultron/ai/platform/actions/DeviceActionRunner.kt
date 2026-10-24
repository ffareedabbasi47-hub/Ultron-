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

    override suspend fun run(action: UltronAction): ActionResult = when (action) {
        is UltronAction.OpenApp -> openApp(action.appName)
        is UltronAction.YouTubeSearch -> youTube(action.query)
        is UltronAction.OpenSystemSettings -> openSettings(action.target)
        is UltronAction.SetReminder -> setReminder(action)
        is UltronAction.Remember -> remember(action.text)
        UltronAction.StopSpeaking -> {
            tts.stop()
            ActionResult(true, "Theek hai, chup.")
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
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(true, "${match.label} kholne ka intent bhej diya.")
        } catch (e: ActivityNotFoundException) {
            ActionResult(false, "${match.label} khul nahi paya.")
        } catch (e: SecurityException) {
            ActionResult(false, "Android ne ${match.label} kholne ki permission nahi di.")
        }
    }

    // ---- YouTube -------------------------------------------------------------------------

    private fun youTube(query: String): ActionResult {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(
                true,
                "YouTube par \"$query\" ka search kholne ka intent bhej diya. Playback shuru hua ya nahi, ye main verify nahi kar sakta.",
            )
        } catch (e: ActivityNotFoundException) {
            ActionResult(false, "YouTube kholne ke liye koi app/browser nahi mila.")
        }
    }

    // ---- system settings -----------------------------------------------------------------

    private fun openSettings(target: SettingsTarget): ActionResult {
        val intent = when (target) {
            SettingsTarget.MAIN -> Intent(Settings.ACTION_SETTINGS)
            SettingsTarget.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            SettingsTarget.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingsTarget.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            SettingsTarget.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
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
