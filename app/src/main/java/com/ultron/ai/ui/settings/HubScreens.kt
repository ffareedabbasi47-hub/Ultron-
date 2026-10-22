package com.ultron.ai.ui.settings

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

@Composable
fun SettingsHubScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    HubPage("Settings", onBack) {
        SectionLabel("Personal")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                IconTextRow(Icons.Filled.Person, "Personal / General", "Naam, language, creator, reply style", { onNavigate(Routes.GENERAL) })
                IconTextRow(Icons.Filled.Palette, "Appearance", "Theme, motion, low-performance mode", { onNavigate(Routes.GENERAL) })
                IconTextRow(Icons.Filled.BatteryChargingFull, "Battery saver", "Low-performance mode General mein hai", { onNavigate(Routes.GENERAL) })
            }
        }
        SectionLabel("Assistant")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                IconTextRow(Icons.Filled.AutoAwesome, "AI Brain / Gemini key", "Key, model, context, speed", { onNavigate(Routes.AI) })
                IconTextRow(Icons.Filled.Rule, "ULTRON ke rules", "Apne standing instructions", { onNavigate(Routes.RULES) })
                IconTextRow(Icons.Filled.RecordVoiceOver, "Awaaz / Speech", "TTS voice, speed, speech recognition", { onNavigate(Routes.VOICE) })
                IconTextRow(Icons.Filled.Face, "Avatar Studio", "3D avatar aur live wallpaper", { onNavigate(Routes.AVATAR) })
                IconTextRow(Icons.Filled.Cable, "Connectors", "Notion/Todoist/Linear: abhi available nahi", { onNavigate(Routes.info("connectors")) })
            }
        }
        SectionLabel("Data")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                IconTextRow(Icons.Filled.IosShare, "Backup export", "Journal, memory, docs, watchlist ka text export (share sheet)", {
                    scope.launch {
                        val sb = StringBuilder("ULTRON backup\n\n")
                        sb.append("== Journal ==\n"); c.db.journal().all().forEach { sb.append("${it.title}\n${it.body}\n\n") }
                        sb.append("== Memory ==\n"); c.db.memory().all().forEach { sb.append("- ${it.text}\n") }
                        sb.append("\n== Documents / code / posts ==\n"); c.db.workDocs().all().forEach { sb.append("[${it.kind}] ${it.title}\n${it.body}\n\n") }
                        sb.append("== Watchlist ==\n"); c.db.markets().all().forEach { sb.append("${it.symbol}: ${it.quantity} @ ${it.buyPrice} (last ${it.lastPrice})\n") }
                        shareText(context, "ULTRON backup", sb.toString())
                    }
                })
                IconTextRow(Icons.Filled.History, "Activity", "Purani chats", { onNavigate(Routes.ACTIVITY) })
                IconTextRow(Icons.Filled.PrivacyTip, "Privacy aur Data", "Permissions, delete everything", { onNavigate(Routes.PRIVACY) })
                IconTextRow(Icons.Filled.BugReport, "Advanced / Diagnostics", "Redacted logs, connectivity", { onNavigate(Routes.DIAG) })
            }
        }
        Text("Restore-from-backup abhi available nahi hai — export sirf text hai.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
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
    HubPage("Accessibility aur Battery", onBack) {
        StatusPill("ULTRON AccessibilityService use nahi karta", UltronColors.Gold)
        Text(
            "Is build mein dusre apps ke buttons dabane wali service nahi hai. App kholna, settings kholna aur YouTube normal Android intents se hota hai. Agar baad mein ye service aayegi to wo sirf tumhari explicit permission se on hogi.",
            modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge,
        )
        SectionLabel("Useful shortcuts")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("Android Accessibility settings") }
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("Battery optimization settings") }
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }, Modifier.fillMaxWidth()) { Text("ULTRON app permissions") }
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
