package com.ultron.ai.ui.settings

import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Row
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.Routes
import com.ultron.ai.ui.components.EmptyState
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.IconTextRow
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.components.StatusPill
import com.ultron.ai.ui.components.shareText
import com.ultron.ai.ui.theme.UltronColors
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
private fun HubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenHeader(title, onBack)
        content()
        androidx.compose.foundation.layout.Spacer(Modifier.padding(20.dp))
    }
}

private data class HubRow(val group: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val sub: String, val route: String)

@Composable
fun SettingsHubScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val rows = remember {
        listOf(
            HubRow("Personal", Icons.Filled.Person, "Personal", "Naam, language, creator: Fardeen", Routes.GENERAL),
            HubRow("Assistant", Icons.Filled.AutoAwesome, "ULTRON / AI Brain", "Gemini key, model, speed, context", Routes.AI),
            HubRow("Assistant", Icons.Filled.Rule, "ULTRON ke rules", "Apne standing instructions", Routes.RULES),
            HubRow("Assistant", Icons.Filled.RecordVoiceOver, "Voice engine", "Mode aur speech recognition ka real status", Routes.VOICE_ENGINE),
            HubRow("Assistant", Icons.Filled.RecordVoiceOver, "Awaaz Studio (TTS)", "Voice, speed, pitch, preview", Routes.VOICE),
            HubRow("Backup", Icons.Filled.IosShare, "Backup", "Export, last backup, restore status", Routes.BACKUP),
            HubRow("Advanced", Icons.Filled.PrivacyTip, "Privacy aur permissions", "Mic, camera, cloud, delete everything", Routes.PRIVACY),
            HubRow("Advanced", Icons.Filled.BugReport, "Diagnostics", "Redacted logs, connectivity", Routes.DIAG),
            HubRow("Battery saver", Icons.Filled.BatteryChargingFull, "Battery aur background", "Battery optimization, autostart guidance", Routes.ACCESS),
            HubRow("Battery saver", Icons.Filled.BatteryChargingFull, "Low-performance mode", "Animation aur 3D halka karo", Routes.GENERAL),
            HubRow("Appearance", Icons.Filled.Palette, "Appearance", "Theme, motion", Routes.GENERAL),
            HubRow("Appearance", Icons.Filled.Face, "Avatar Studio", "3D avatar, models, live wallpaper", Routes.AVATAR),
            HubRow("Activity", Icons.Filled.History, "Messages / Activity", "Purani chats", Routes.ACTIVITY),
            HubRow("Activity", Icons.Filled.Description, "Privacy Policy", "ULTRON data kaise use karta hai", Routes.POLICY),
        )
    }
    val q = query.trim().lowercase()
    val shown = rows.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.sub.lowercase().contains(q) || it.group.lowercase().contains(q) }
    HubPage("Settings", onBack) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            placeholder = { Text("Settings / connectors dhoondo") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
        )
        if (shown.isEmpty()) Text("Kuch nahi mila.", color = UltronColors.TextLo, modifier = Modifier.padding(top = 16.dp))
        shown.groupBy { it.group }.forEach { (group, items) ->
            SectionLabel(group)
            GlassCard(Modifier.fillMaxWidth()) {
                Column { items.forEach { r -> IconTextRow(r.icon, r.title, r.sub, { onNavigate(r.route) }) } }
            }
        }
    }
}

@Composable
fun ConnectorsScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = listOf("Notion" to "Notes aur docs sync", "Todoist" to "Tasks sync", "Linear" to "Issues sync")
    val shown = all.filter { query.isBlank() || it.first.contains(query.trim(), true) }
    HubPage("Connectors", onBack) {
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Connector dhoondo") })
        Text("Koi connector is build mein asli login ke saath bana nahi hai, isliye sab \"Available nahi\" hain. Connected ka fake badge nahi dikhaunga.", color = UltronColors.TextLo, modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        shown.forEach { (name, desc) ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(desc, color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
                    }
                    StatusPill("Available nahi", UltronColors.Gold)
                }
            }
        }
        if (shown.isEmpty()) Text("Kuch nahi mila.", color = UltronColors.TextLo)
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("ultron_backup", android.content.Context.MODE_PRIVATE) }
    var last by remember { mutableStateOf(prefs.getLong("last", 0L)) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    HubPage("Backup", onBack) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Kya export hota hai", style = MaterialTheme.typography.titleMedium)
                Text("Journal, Yaadein, Documents/Code/Content drafts aur Watchlist/Portfolio — plain text mein. Chat history, API key aur settings export nahi hote.", color = UltronColors.TextLo)
                Text("Destination: tum share sheet se khud chunte ho (Drive, WhatsApp, Files…). ULTRON khud kahin upload nahi karta.", color = UltronColors.TextLo)
                Text(if (last == 0L) "Last backup: abhi tak nahi" else "Last backup: ${fmt.format(Date(last))}")
            }
        }
        androidx.compose.material3.Button(modifier = Modifier.padding(top = 12.dp), onClick = {
            scope.launch {
                val sb = StringBuilder("ULTRON backup\n\n")
                sb.append("== Journal ==\n"); c.db.journal().all().forEach { sb.append("${it.title}\n${it.body}\n\n") }
                sb.append("== Memory ==\n"); c.db.memory().all().forEach { sb.append("- ${it.text}\n") }
                sb.append("\n== Documents / code / content ==\n"); c.db.workDocs().all().forEach { sb.append("[${it.kind}] ${it.title}\n${it.body}\n\n") }
                sb.append("== Watchlist / Portfolio ==\n"); c.db.markets().all().forEach { sb.append("${it.type} ${it.exchange}:${it.symbol} ${it.quantity} @ ${it.buyPrice} (last ${it.lastPrice})\n") }
                shareText(context, "ULTRON backup", sb.toString())
                last = System.currentTimeMillis(); prefs.edit().putLong("last", last).apply()
            }
        }) { Text("Backup export karo") }
        SectionLabel("Restore")
        StatusPill("Restore available nahi", UltronColors.Gold)
        Text("Export sirf padhne/sambhalne ke liye text hai. Automatic restore is build mein nahi hai, isliye fake \"restore\" button nahi diya.", color = UltronColors.TextLo, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun VoiceEngineScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val sttOk = remember { c.stt.isAvailable() }
    HubPage("Voice engine", onBack) {
        SectionLabel("Mode")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text("Normal", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); StatusPill("Active", UltronColors.Green) }
                Text("Mic dabao → phone ka recogniser sunta hai → AI/commands → tumhari chuni awaaz mein jawab.", color = UltronColors.TextLo)
            }
        }
        GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text("Advanced Agentic", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); StatusPill("Available nahi", UltronColors.Gold) }
                Text("Is mode ke liye alag voice agent chahiye jo is build mein nahi hai.", color = UltronColors.TextLo)
            }
        }
        SectionLabel("Speech recognition (STT)")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text("Phone ka recogniser", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); if (sttOk) StatusPill("Selected", UltronColors.Green) else StatusPill("Device par nahi", UltronColors.Red) }
                Text(if (sttOk) "Android ka system recogniser mila. Language General settings se badalti hai." else "Is phone par speech recognition service nahi mili. Google/system voice service install karo.", color = UltronColors.TextLo)
            }
        }
        GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text("Cloud STT providers", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); StatusPill("Available nahi", UltronColors.Gold) }
                Text("Koi cloud speech-to-text integrate nahi hai.", color = UltronColors.TextLo)
            }
        }
        SectionLabel("Awaaz (TTS)")
        androidx.compose.material3.OutlinedButton(onClick = { onNavigate(Routes.VOICE) }, Modifier.fillMaxWidth()) { Text("Awaaz Studio kholo") }
        Text("Wake word / hamesha-sunna abhi implement nahi hai; mic sirf tab chalta hai jab tum dabate ho.", color = UltronColors.TextLo, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PolicyScreen(onBack: () -> Unit) = HubPage("Privacy Policy", onBack) {
    val sections = listOf(
        "Ye app kya store karta hai" to "Chat history, Journal, Mood, Yaadein, Documents/Code/Content drafts, Watchlist/Portfolio, reminders aur settings tumhare phone par rehte hain. Database encrypted hai (key Android Keystore se bandhi hai).",
        "Cloud par kya jata hai" to "Sirf tab jab tum Cloud AI allow karte ho: tumhara chat text aur chat ka recent context AI provider ko jata hai — seedha Gemini ko (agar tumne apni key daali) ya tumhare ULTRON backend ke through. Mic ki audio, screen, camera photo aur tumhari files AI ko nahi bheji jatin.",
        "Gemini API key" to "Key phone par Keystore se encrypted rehti hai. Wo APK, repo ya logs mein nahi hoti. Rooted/compromised phone par key nikal sakti hai.",
        "Mic aur speech" to "Mic sirf tab chalta hai jab tum mic dabate ho. Speech recognition Android ka system recogniser karta hai; uska data us service ki apni policy ke hisaab se handle hota hai. ULTRON recording save nahi karta.",
        "Camera / Scan" to "ULTRON khud CAMERA permission nahi leta; system camera app ya image picker se tumhari chuni image par OCR chalta hai, phone par.",
        "Weather / location" to "Weather ke liye sirf tumhara city naam provider ko jata hai. Continuous location collect nahi hoti.",
        "Controls" to "Privacy aur Data screen se tum cloud permission wapas le sakte ho aur saara data delete kar sakte ho. Journal/Yaadein/Backup export ke liye alag buttons hain.",
        "Analytics" to "Koi analytics ya ad SDK nahi hai. Diagnostics log local rehta hai aur keys/private text redact hote hain.",
    )
    sections.forEach { (h, b) ->
        Text(h, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
        Text(b, color = UltronColors.TextLo, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Custom standing rules appended to the system prompt (never above identity/honesty/safety rules). */
@Composable
fun RulesEditor(settings: UltronSettings) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var rules by remember(settings.customRules) { mutableStateOf(settings.customRules) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = rules, onValueChange = { rules = it.take(600) }, label = { Text("Apne rules (max 600 akshar)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { scope.launch { c.settings.update { it.copy(customRules = rules.trim()) } } }, enabled = rules.trim() != settings.customRules) { Text("Rules save") }
        Text("Ye rules har jawab ke saath jate hain, par identity/honesty/safety rules se upar nahi.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun RulesScreen(settings: UltronSettings, onBack: () -> Unit) = HubPage("ULTRON ke rules", onBack) {
    Text("Jaise: \"Hamesha 2 line mein jawab do\" ya \"Padhai mein pehle example do\".", color = UltronColors.TextLo, modifier = Modifier.padding(bottom = 12.dp))
    RulesEditor(settings)
}

@Composable
fun AccessibilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(i: Intent) = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    var unrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName) }
        val owner = context as? androidx.lifecycle.LifecycleOwner
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    HubPage("Accessibility aur Battery", onBack) {
        Text(
            "Ye screen sirf samjhane aur Android settings tak pahunchane ke liye hai. ULTRON kuch bhi apne aap on nahi karta; har step tum khud karte ho.",
            style = MaterialTheme.typography.bodyLarge,
        )
        SectionLabel("Accessibility")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Text("Accessibility service", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); StatusPill("Is build mein nahi", UltronColors.Gold) }
                Text("ULTRON dusre apps ke buttons nahi dabata. App kholna, settings kholna aur YouTube normal Android intents se hota hai. Agar ye service aayegi to sirf tumhari permission se, aur security/payment screens par kabhi nahi.", color = UltronColors.TextLo)
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("Android Accessibility settings") }
            }
        }
        SectionLabel("Battery")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Text("Battery optimization", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); if (unrestricted) StatusPill("Unrestricted", UltronColors.Green) else StatusPill("Optimized", UltronColors.Gold) }
                Text("ULTRON abhi background mein nahi sunta, isliye iski zaroorat nahi. Reminders aur live wallpaper ke liye kabhi-kabhi Android optimize kar deta hai; chaho to yahan se badal sakte ho. Background chalna phone/OS par depend karta hai — guarantee nahi.", color = UltronColors.TextLo)
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("Battery optimization settings") }
            }
        }
        SectionLabel("Autostart")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Text("Autostart", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); StatusPill("Status pata nahi", UltronColors.Gold) }
                Text("Autostart ka koi standard Android API nahi hai (Xiaomi/Oppo/Vivo jaisi companies apni alag setting deti hain), isliye main iski sach-much status nahi bata sakta. App info mein jaakar dekh lo.", color = UltronColors.TextLo)
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }, Modifier.fillMaxWidth()) { Text("ULTRON app info / permissions") }
            }
        }
    }
}

@Composable
fun ActivityScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val chats by c.db.conversations().observeAll().collectAsState(initial = emptyList())
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    HubPage("Activity", onBack) {
        if (chats.isEmpty()) EmptyState("Abhi koi activity nahi", "Chat ya voice se baat karoge to yahan history dikhegi.")
        else chats.forEach { ch ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = { onNavigate(Routes.chat(study = ch.mode == "study")) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(ch.title.ifBlank { "Chat" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text("${if (ch.mode == "study") "Padhai" else "Chat"} · ${fmt.format(Date(ch.updatedAt))}", style = MaterialTheme.typography.labelSmall, color = UltronColors.Cyan)
                }
            }
        }
    }
}

/** Bell panel: real reminders + real setup alerts. Nothing invented. */
@Composable
fun NotificationsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val reminders by c.db.reminders().observeAll().collectAsState(initial = emptyList())
    val hasKey = remember { c.settings.geminiKey() != null }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    HubPage("Notifications", onBack) {
        SectionLabel("Alerts")
        if (!hasKey) GlassCard(Modifier.fillMaxWidth(), onClick = { onNavigate(Routes.AI) }) {
            Column(Modifier.padding(14.dp)) {
                Text("Gemini key set nahi hai", style = MaterialTheme.typography.titleMedium)
                Text("AI jawab ke liye key daalo ya backend connect karo.", color = UltronColors.TextLo)
                TextButton(onClick = { onNavigate(Routes.AI) }) { Text("AI Brain kholo") }
            }
        } else Text("Koi naya alert nahi.", color = UltronColors.TextLo)
        SectionLabel("Reminders")
        val upcoming = reminders.filter { !it.fired }
        if (upcoming.isEmpty()) Text("Koi pending reminder nahi.", color = UltronColors.TextLo)
        upcoming.forEach { r ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { onNavigate(Routes.TODAY) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.text, style = MaterialTheme.typography.titleMedium)
                    Text(fmt.format(Date(r.triggerAt)), style = MaterialTheme.typography.labelSmall, color = UltronColors.Cyan)
                }
            }
        }
    }
}
