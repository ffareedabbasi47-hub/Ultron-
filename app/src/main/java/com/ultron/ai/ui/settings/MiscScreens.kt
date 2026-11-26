package com.ultron.ai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ultron.ai.BuildConfig
import com.ultron.ai.domain.ActionValidator
import com.ultron.ai.domain.UltronAction
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.LabeledField
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.components.StatusPill
import com.ultron.ai.ui.components.shareText
import com.ultron.ai.ui.features.notificationsAllowed
import com.ultron.ai.ui.theme.UltronColors
import kotlinx.coroutines.launch

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenHeader(title, onBack)
        content()
        androidx.compose.foundation.layout.Spacer(Modifier.padding(20.dp))
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val logs by c.diagnostics.entries.collectAsState()
    val tts by c.tts.state.collectAsState()
    Page("Diagnostics", onBack) {
        SectionLabel("Status")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ULTRON ${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug" else "release"})")
                Text("TTS: ${if (tts.ready) "ready (${tts.engine})" else "ready nahi"}  ·  voices: ${tts.voices.size}", color = UltronColors.TextLo)
                Text("Speech recognition: ${if (c.stt.isAvailable()) "available" else "available nahi"}", color = UltronColors.TextLo)
                Text("Assistant state: ${c.assistantState.collectAsState().value}", color = UltronColors.TextLo)
                if (c.databaseWasReset) Text("Dhyan: Keystore key lost hone se purana database padh nahi paye. Wo delete nahi hua: file ultron.db.unreadable-… naam se device par rakhi hai; abhi naya khaali database chal raha hai.", color = UltronColors.Gold)
            }
        }
        SectionLabel("Voice diagnostics (live, real values)")
        val mic by c.mic.info.collectAsState()
        val ld by c.live.diag.collectAsState()
        val cloudErr by c.cloudVoice.lastError.collectAsState()
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                fun yn(b: Boolean) = if (b) "haan" else "nahi"
                Text("Session: ${ld.state.label}", style = MaterialTheme.typography.titleMedium)
                Text("Recorder: ${if (mic.recording) "recording" else "band"} · ${mic.sampleRate} Hz · source ${mic.source}", color = UltronColors.TextLo)
                Text("Route: ${mic.route}", color = UltronColors.TextLo)
                Text("Recorder opens: ${mic.recorderOpens} · recoveries: ${mic.recoveries} (ek session mein 1 hona chahiye)", color = UltronColors.TextLo)
                Text("AEC: available ${yn(mic.aecAvailable)} · enabled ${yn(mic.aecEnabled)}", color = UltronColors.TextLo)
                Text("Noise suppressor: available ${yn(mic.nsAvailable)} · enabled ${yn(mic.nsEnabled)} · AGC: off", color = UltronColors.TextLo)
                Text("Echo mode: ${if (ld.commMode) "full-duplex (barge-in)" else "safe gating"} · TTS active: ${yn(ld.ttsActive)}", color = UltronColors.TextLo)
                Text("VAD speech: ${yn(ld.vadSpeech)} · level ${"%.0f".format(ld.levelDb)} dB · noise floor ${"%.0f".format(ld.noiseFloorDb)} dB", color = UltronColors.TextLo)
                Text("Utterances: ${ld.utterances} · echo discard: ${ld.discardedAsEcho} · too short: ${ld.rejectedShort}", color = UltronColors.TextLo)
                Text("Last transcribe: ${ld.lastTranscribeMs?.let { "$it ms" } ?: "—"} · first reply audio: ${ld.lastReplyStartMs?.let { "$it ms" } ?: "—"}", color = UltronColors.TextLo)
                ld.sleepInMs?.let { Text("Sleep in: ${it / 1000}s", color = UltronColors.TextLo) }
                if (mic.silencedBySystem) Text("Android ne mic ko silence kiya hua hai.", color = UltronColors.Gold)
                (mic.lastError ?: ld.lastError)?.let { Text("Last error: $it", color = UltronColors.Gold) }
                cloudErr?.let { Text("Online voice: $it", color = UltronColors.Gold) }
                ld.note?.let { Text(it, color = UltronColors.TextLo) }
            }
        }
        Text("Ye sab asli measurements hain; koi hard-coded 'ON' nahi. Raw audio ya baatein yahan save nahi hoti.", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)
        SectionLabel("Measured")
        StatsBlock()
        SectionLabel("Log (redacted, sirf memory mein)")
        if (logs.isEmpty()) Text("Abhi koi entry nahi.", color = UltronColors.TextLo)
        logs.takeLast(80).reversed().forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo) }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { c.diagnostics.clear() }) { Text("Log clear") }
            OutlinedButton(enabled = logs.isNotEmpty(), onClick = { shareText(context, "ULTRON diagnostics", logs.joinToString("\n")) }) { Text("Share log") }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Page("About", onBack) {
        Text("ULTRON AI", style = MaterialTheme.typography.headlineMedium)
        Text("Version ${BuildConfig.VERSION_NAME}", color = UltronColors.Cyan)
        Text("Configured creator: ${BuildConfig.CREATOR_NAME} (app configuration; independent authorship ka proof nahi).", color = UltronColors.TextLo, modifier = Modifier.padding(top = 6.dp))

        SectionLabel("Changelog")
        Text("0.3.0 — ULTRON ka custom backend hata diya: AI seedha Gemini se, mausam seedha Open-Meteo se; offline personal wake word; kam boxes. 0.2.0 — Live persistent mic (sleep timer, echo handling), Gemini online awaaz, themes/fonts, screen control, default assistant. 0.1.0 — pehla build: 3D avatar (built-in + apna GLB, live wallpaper), Home/Chat/Live voice, Awaaz Studio, Journal, Mood, Yaadein, Aaj reminders, Mausam, Scan OCR, app kholna, YouTube search, encrypted local storage.", color = UltronColors.TextLo)

        SectionLabel("Is build mein nahi hai")
        Text("Asli offline wake-word engine, screen-capture analysis (MediaProjection), avatar model catalog, home-screen widget, QR scanning, PC↔Phone link, web search. Live mic, screen control (Accessibility) aur default-assistant ab hain, par device par abhi test nahi hue.", color = UltronColors.TextLo)

        SectionLabel("Open-source licences")
        Text("AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines/serialization, OkHttp: Apache-2.0.\nSQLCipher for Android: BSD-style (Zetetic).\nGoogle ML Kit Text Recognition: ML Kit Terms of Service.\nWeather data: Open-Meteo (CC BY 4.0).", color = UltronColors.TextLo)
    }
}

@Composable
fun AutomationScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var app by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var count by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { count = c.actions.launchableApps().size }

    Page("Apps kholna aur Automation", onBack) {
        SectionLabel("App kholo")
        LabeledField(app, { app = it.take(40) }, "App ka naam (jaise WhatsApp)")
        Button(enabled = app.isNotBlank(), modifier = Modifier.padding(top = 8.dp), onClick = {
            val a = UltronAction.OpenApp(app.trim())
            val v = ActionValidator.validate(a)
            if (!v.ok) result = false to (v.reason ?: "Invalid") else scope.launch { val r = c.actions.run(a); result = r.success to r.message }
        }) { Text("Kholo") }
        result?.let { (ok, m) -> Text(m, color = if (ok) UltronColors.TextHi else UltronColors.Gold, modifier = Modifier.padding(top = 8.dp)) }
        Text("Launchable apps jo ULTRON dekh sakta hai: ${count ?: "…"}", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo, modifier = Modifier.padding(top = 8.dp))

        SectionLabel("Voice/chat commands")
        Text("• \"WhatsApp kholo\" / \"open camera\"\n• \"YouTube pe arijit singh songs chalao\"\n• \"wifi settings\" / \"bluetooth settings\"\n• \"10 minute mein chai banana yaad dilana\"\n• \"yaad rakh: …\"\n• \"chup\" (bolna band)", color = UltronColors.TextLo)
        Text("Ye sab device par validate hokar chalte hain. Calls, messages, purchases, delete jaise consequential actions is build mein hain hi nahi. Settings sirf khulti hain; toggle aap khud karte ho.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))

        SectionLabel("Reminders")
        val n = notificationsAllowed(context)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("Notifications"); if (n) StatusPill("Allowed", UltronColors.Green) else StatusPill("Blocked", UltronColors.Gold) }
        SectionLabel("Accessibility")
        Text("Optional screen control: Menu → Live aur Assistant se on karo. Commands: \"click <naam>\", \"scroll down\", \"back jao\", \"type <text>\", \"agent: <kaam>\".", color = UltronColors.TextLo)
    }
}
