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
                if (c.databaseWasReset) Text("Dhyan: Keystore key lost hone se local database naya banana pada; purana data unreadable tha.", color = UltronColors.Gold)
            }
        }
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
        Text("0.1.0 — pehla build: Home/Chat/Live voice, Awaaz Studio, Journal, Mood, Yaadein, Aaj reminders, Mausam (backend), Scan OCR, app kholna, YouTube search, encrypted local storage, secure backend proxy.", color = UltronColors.TextLo)

        SectionLabel("Is build mein nahi hai")
        Text("Wake word / background listening, screen understanding (MediaProjection), 3D avatar renderer aur model download, accessibility automation, QR scanning, PC↔Phone link, smart model routing, Dream Journal, web search.", color = UltronColors.TextLo)

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
        Text("Use nahi hoti — koi AccessibilityService register nahi hai.", color = UltronColors.TextLo)
    }
}

private data class InfoPage(val title: String, val status: String, val body: String, val alternative: String? = null)

private val infoPages = mapOf(
    "avatar" to InfoPage("Avatar Studio", "Is build mein nahi", "Abhi sirf built-in animated ULTRON core use hota hai. Real 3D (GLB/GLTF) renderer aur verified, licensed model catalog abhi implement nahi hai, isliye main koi nakli preview, progress bar ya download nahi dikhaunga.", "Core ki animation Menu → General mein reduced motion / low performance se control hoti hai."),
    "downloads" to InfoPage("Download Center", "Khaali — kuch download nahi hota", "Is build mein koi model/avatar/voice download flow nahi hai, isliye yahan dikhane ko koi asli download nahi. Voice packs Android ke TTS settings se install hote hain (Awaaz Studio mein link hai)."),
    "websearch" to InfoPage("Web Search", "Setup chahiye", "Web search ke liye backend par ek asli search provider configure hona chahiye. Abhi koi configured nahi, isliye ULTRON live web results ka dawa nahi karta."),
    "screen" to InfoPage("Screen Samjho", "Is build mein nahi", "Screen understanding ke liye Android MediaProjection (system consent), foreground service, visible indicator aur Pause/Stop controls chahiye. Ye abhi implement nahi hua — ULTRON kabhi bhi chhupke screen capture ya upload nahi karta.", "Abhi: Scan tab se kisi image/photo ka text (OCR) padh sakte ho."),
    "accessibility" to InfoPage("Accessibility automation", "Use nahi hoti", "Is build mein koi AccessibilityService nahi hai, isliye ULTRON dusre apps ke buttons dabata nahi. App kholna aur settings screens kholna normal Android intents se hota hai."),
    "pc" to InfoPage("PC ↔ Phone", "Setup chahiye", "Iske liye ek asli paired PC endpoint, authentication aur aapki consent chahiye. Koi pairing configured nahi hai, isliye ye feature off hai."),
    "files" to InfoPage("Documents / Files", "Partial", "ULTRON aapki files ko browse nahi karta. Image se text padhne ke liye Scan ya Home ka attachment button use karo — wo system picker se sirf wahi image leta hai jo aap chunte ho."),
)

@Composable
fun InfoScreen(id: String, onBack: () -> Unit) {
    val p = infoPages[id] ?: InfoPage("Available nahi", "—", "Ye screen maujood nahi hai.")
    Page(p.title, onBack) {
        StatusPill(p.status, UltronColors.Gold)
        Text(p.body, modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyLarge)
        p.alternative?.let { Text(it, modifier = Modifier.padding(top = 12.dp), color = UltronColors.TextLo) }
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Wapas") }
    }
}
