package com.ultron.ai.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ultron.ai.BuildConfig
import com.ultron.ai.data.ai.ProviderStatus
import com.ultron.ai.data.settings.AppLanguage
import com.ultron.ai.data.settings.ResponseStyle
import com.ultron.ai.data.settings.ThemeVariant
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.components.ConfirmDialog
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.LabeledField
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.components.StatusPill
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
fun GeneralScreen(settings: UltronSettings, onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by remember(settings.userName) { mutableStateOf(settings.userName) }
    fun update(b: (UltronSettings) -> UltronSettings) = scope.launch { c.settings.update(b) }

    Page("ULTRON ke Rules aur General", onBack) {
        SectionLabel("Aap")
        LabeledField(name, { name = it.take(30) }, "Greeting mein naam")
        TextButton(enabled = name.trim() != settings.userName, onClick = { update { it.copy(userName = name.trim()) } }) { Text("Naam save karo") }

        SectionLabel("Language")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { l -> FilterChip(selected = settings.language == l, onClick = { update { it.copy(language = l) } }, label = { Text(l.label) }) }
        }
        Text("Ye ULTRON ke jawab ki language aur speech recognition ka locale tay karta hai.", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)

        SectionLabel("Theme aur motion")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeVariant.entries.forEach { t -> FilterChip(selected = settings.theme == t, onClick = { update { it.copy(theme = t) } }, label = { Text(t.label) }) }
        }
        SwitchRow("Reduced motion", "Core aur equalizer ki animation band (static).", settings.reducedMotion) { v -> update { it.copy(reducedMotion = v) } }
        SwitchRow("Low performance mode", "Kam layers/particles — purane phone ke liye.", settings.lowPerformance) { v -> update { it.copy(lowPerformance = v) } }

        SectionLabel("Persona aur creator")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Configured creator record: ${BuildConfig.CREATOR_NAME}", style = MaterialTheme.typography.titleMedium)
                Text("Ye app ka configured attribution hai, kisi ka independent proof nahi. Ye record sirf developer build configuration se badalta hai — chat mein kuch bolke ya settings se nahi.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
                Text("Persona: calm, analytical, composed; chhote seedhe jawab; zaroorat par detail. Server-side system prompt mein tay hai, isliye app client use badal nahi sakta.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun AiScreen(settings: UltronSettings, onBack: () -> Unit, onConnect: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ProviderStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var keyVersion by remember { mutableStateOf(0) }
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val savedKey = remember(keyVersion) { c.settings.geminiKey() }
    fun update(b: (UltronSettings) -> UltronSettings) = scope.launch { c.settings.update(b) }
    LaunchedEffect(settings.backendUrl, keyVersion) { loading = true; status = c.provider.status(); loading = false }

    Page("AI Brain", onBack) {
        SectionLabel("Gemini API key")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (savedKey != null) {
                    StatusPill("Key saved · …${savedKey.takeLast(4)}", UltronColors.Green)
                    Text("Ab chat seedha Gemini se hota hai (backend ke bina) — sabse tez route.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Apni Gemini API key daalo (Google AI Studio se). Key is phone par encrypted rehti hai, APK ya repo mein nahi.", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = keyInput, onValueChange = { keyInput = it.trim() }, singleLine = true,
                    label = { Text(if (savedKey != null) "Nayi key (badalne ke liye)" else "Gemini API key") },
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = keyInput.length >= 20, onClick = {
                        c.settings.setGeminiKey(keyInput); keyInput = ""; keyVersion++
                        update { it.copy(cloudConsent = true) }
                    }) { Text("Save") }
                    OutlinedButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Chhupao" else "Dikhao") }
                    if (savedKey != null) TextButton(onClick = { c.settings.setGeminiKey(null); keyVersion++ }) { Text("Hatao") }
                }
                Text("Save karne par tumhara chat text Google Gemini ko bheja jayega (Cloud AI allow on ho jata hai). Rooted phone par key nikal sakti hai — zyada safety chahiye to backend proxy use karo.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionLabel("Provider")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val s = status
                when {
                    loading -> StatusPill("Check ho raha hai…", UltronColors.Gold)
                    s == null || !s.reachable -> StatusPill("Backend connect nahi", UltronColors.Red)
                    s.error != null -> StatusPill("Setup chahiye", UltronColors.Gold)
                    else -> StatusPill("Connected · ${s.provider ?: "provider"}", UltronColors.Green)
                }
                s?.error?.let { Text(it, color = UltronColors.Gold, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = onConnect) { Text("Connectivity kholo") }
            }
        }

        SectionLabel("Model")
        val models = status?.models.orEmpty()
        if (savedKey != null) {
            models.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.geminiModel == m, onClick = { update { it.copy(geminiModel = m) } })
                    Text(m)
                }
            }
            if (models.isEmpty() && !loading) Text("Key se model list nahi mili. Default: ${settings.geminiModel}", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
            Text("Tez jawab ke liye flash / flash-lite model chuno.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.selectedModel == null, onClick = { update { it.copy(selectedModel = null) } })
            Text("Backend ka default model")
        }
        if (savedKey == null) models.forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.selectedModel == m, onClick = { update { it.copy(selectedModel = m) } })
                Text(m)
            }
        }
        if (savedKey == null && models.isEmpty() && !loading) Text("Backend se koi model list nahi mili. Sirf wahi models dikhte hain jo backend sach mein configured karta hai.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
        Text("Auto / Fast / Balanced / Advanced routing: Setup chahiye — abhi backend ek hi configured model list deta hai, isliye smart routing is build mein nahi hai.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))

        SectionLabel("Jawab ka style")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResponseStyle.entries.forEach { r -> FilterChip(selected = settings.responseStyle == r, onClick = { update { it.copy(responseStyle = r) } }, label = { Text(r.label) }) }
        }
        SliderRow("Context: pichhle kitne messages yaad rakhun", settings.historyTurns.toFloat(), 4f..30f) { v -> update { it.copy(historyTurns = v.toInt()) } }

        SectionLabel("Performance")
        SwitchRow("Streaming", "Jawab aate hi dikhao (aur sentence-by-sentence bolo).", settings.streaming) { v -> update { it.copy(streaming = v) } }
        Text("Timeout (sec)", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60).forEach { t -> FilterChip(selected = settings.timeoutSec == t, onClick = { update { it.copy(timeoutSec = t) } }, label = { Text("$t") }) }
        }
        Text("Retries (sirf network/server error par, jawab shuru hone se pehle)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 1, 2, 3).forEach { t -> FilterChip(selected = settings.maxRetries == t, onClick = { update { it.copy(maxRetries = t) } }, label = { Text("$t") }) }
        }

        SectionLabel("ULTRON ke rules")
        RulesEditor(settings)

        SectionLabel("Cloud")
        SwitchRow("Cloud AI allow", "Chat text backend ko bheja jata hai. Band rakhoge to device commands (app kholna, reminder, YouTube) phir bhi chalenge.", settings.cloudConsent) { v -> update { it.copy(cloudConsent = v) } }
    }
}

@Composable
fun ConnectScreen(settings: UltronSettings, onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var url by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(!c.settings.accessToken().isNullOrBlank()) }
    var result by remember { mutableStateOf<ProviderStatus?>(null) }
    var testing by remember { mutableStateOf(false) }

    Page("Connectivity", onBack) {
        SectionLabel("ULTRON backend")
        LabeledField(url, { url = it.trim() }, "Backend URL (https://…)")
        Text("Ye aapka apna deployed ULTRON proxy hai. API keys sirf us server par rehti hain, is app mein kabhi nahi.", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)
        TextButton(enabled = url != settings.backendUrl, onClick = { scope.launch { c.settings.update { it.copy(backendUrl = url) } } }) { Text("URL save karo") }

        OutlinedTextField(
            value = token, onValueChange = { token = it }, label = { Text(if (hasToken) "Access token (saved — naya daalke badlo)" else "Access token") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row {
            TextButton(enabled = token.isNotBlank(), onClick = { c.settings.setAccessToken(token); token = ""; hasToken = true }) { Text("Token save karo") }
            TextButton(enabled = hasToken, onClick = { c.settings.setAccessToken(null); hasToken = false }) { Text("Token hatao") }
        }
        Text("Token Android Keystore se encrypt hokar store hota hai.", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)

        SectionLabel("Test")
        Button(enabled = !testing, onClick = { testing = true; scope.launch { result = c.provider.status(); testing = false } }) { Text(if (testing) "Test chal raha hai…" else "Connection test karo") }
        result?.let { r ->
            GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(if (r.reachable) "Backend reachable" else "Backend unreachable", if (r.reachable) UltronColors.Green else UltronColors.Red)
                    if (r.reachable) {
                        Text("Provider: ${r.provider ?: "?"} · version ${r.version ?: "?"}")
                        Text("Provider configured: ${if (r.configured) "haan" else "nahi"}")
                        Text("Token: ${when (r.authOk) { true -> "theek"; false -> "galat/missing"; null -> "?" }}")
                        Text("Models: ${r.models.joinToString().ifBlank { "—" }}")
                    }
                    r.error?.let { Text(it, color = UltronColors.Gold) }
                }
            }
        }
        SectionLabel("Offline")
        Text("Backend na ho to bhi ye chalta hai: Journal, Mood, Yaadein, reminders, app kholna, YouTube search, OCR, Voice Studio.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
        SectionLabel("Is session ke measured numbers")
        StatsBlock()
    }
}

@Composable
fun StatsBlock() {
    val c = LocalContainer.current
    val s by c.latency.snapshot.collectAsState()
    fun ms(v: Long?) = v?.let { "$it ms" } ?: "abhi measure nahi hua"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("AI replies (samples: ${s.samples}, errors: ${s.errors})")
        Text("Pehla token (avg): ${ms(s.avgFirstTokenMs)}", color = UltronColors.TextLo)
        Text("Poora jawab (avg): ${ms(s.avgTotalMs)}", color = UltronColors.TextLo)
        Text("Pehli awaaz (avg): ${ms(s.avgFirstAudioMs)}", color = UltronColors.TextLo)
        Text("Last reply tokens: ${s.lastUsageTotalTokens?.toString() ?: "provider ne nahi bataya"}", color = UltronColors.TextLo)
        Text("Quota/limit: provider se nahi aata, isliye nahi dikhata.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PrivacyScreen(settings: UltronSettings, onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmChats by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Page("Privacy aur Permissions", onBack) {
        SectionLabel("Permissions dashboard")
        PermRow("Microphone", if (mic) "Allowed" else "Mic dabane par poochhi jayegi", if (mic) UltronColors.Green else UltronColors.Gold, "Sirf Live voice mein, tab tak jab tak aap sun-ne dete ho.")
        val n = notificationsAllowed(context)
        PermRow("Notifications", if (n) "Allowed" else "Blocked", if (n) UltronColors.Green else UltronColors.Gold, "Sirf aapke set kiye reminders ke liye.")
        PermRow("Camera", "App ke paas nahi", UltronColors.TextLo, "Scan system camera app use karta hai; ULTRON camera access nahi rakhta.")
        PermRow("Location", "Use nahi hoti", UltronColors.TextLo, "Mausam ke liye shehar aap khud likhte ho.")
        PermRow("Screen capture", "Is build mein nahi", UltronColors.TextLo, "Screen kabhi capture ya upload nahi hoti.")
        PermRow("Accessibility", "Use nahi hoti", UltronColors.TextLo, "Koi accessibility service enable nahi karni padti.")
        PermRow("Background mic", "Is build mein nahi", UltronColors.TextLo, "Koi foreground service ya wake word nahi.")

        SectionLabel("ULTRON ke rules")
        var rules by remember(settings.customRules) { mutableStateOf(settings.customRules) }
        OutlinedTextField(value = rules, onValueChange = { rules = it.take(600) }, label = { Text("Apne rules (max 600 akshar)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(onClick = { update { it.copy(customRules = rules.trim()) } }, enabled = rules.trim() != settings.customRules) { Text("Rules save") }
        Text("Ye rules har jawab ke saath jate hain, par identity/honesty/safety rules se upar nahi.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodySmall)

        SectionLabel("Cloud")
        SwitchRow("Cloud AI allow", "Chat text (aur recent context) backend → AI provider ko jata hai. Audio/screen/images kabhi nahi.", settings.cloudConsent) { v -> scope.launch { c.settings.update { it.copy(cloudConsent = v) } } }

        SectionLabel("Aapka data")
        Text("Chats, journal, mood, yaadein aur reminders sirf is phone par, encrypted database (SQLCipher, Keystore key) mein hain. Android cloud backup band hai. Diagnostics log memory mein rehta hai, redacted, disk par nahi jata.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { confirmChats = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Saari chat history delete karo") }
        OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Sab kuch reset karo") }
    }
    if (confirmChats) ConfirmDialog("Saari chats delete karein?", "Sab conversations hamesha ke liye hat jayengi.", "Delete", { scope.launch { c.turns.cancel(); c.db.conversations().deleteAll() }; confirmChats = false }, { confirmChats = false })
    if (confirmAll) ConfirmDialog(
        "Sab kuch reset karein?", "Chats, journal, mood, yaadein, reminders, settings aur saved token — sab hat jayega.", "Sab reset karo",
        {
            scope.launch {
                c.turns.cancel(); c.tts.stop()
                c.reminders.cancelAll()
                c.db.conversations().deleteAll(); c.db.journal().deleteAll(); c.db.mood().deleteAll(); c.db.memory().deleteAll(); c.db.workDocs().deleteAll(); c.db.markets().deleteAll()
                c.settings.resetAll()
            }
            confirmAll = false
        },
        { confirmAll = false },
    )
}

@Composable
private fun PermRow(name: String, state: String, color: androidx.compose.ui.graphics.Color, why: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            StatusPill(state, color)
        }
        Text(why, style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)
    }
}
