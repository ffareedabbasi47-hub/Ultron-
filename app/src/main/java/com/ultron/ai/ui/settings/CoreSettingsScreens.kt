package com.ultron.ai.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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

        SectionLabel("Theme")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeVariant.entries.forEach { t ->
                val p = com.ultron.ai.ui.theme.Palettes.of(t)
                FilterChip(
                    selected = settings.theme == t, onClick = { update { it.copy(theme = t) } }, label = { Text(t.label) },
                    leadingIcon = { androidx.compose.foundation.layout.Box(Modifier.size(14.dp).background(p.primary, androidx.compose.foundation.shape.CircleShape)) },
                )
            }
        }
        SectionLabel("Font aur text size")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            com.ultron.ai.data.settings.AppFont.entries.forEach { f -> FilterChip(selected = settings.font == f, onClick = { update { it.copy(font = f) } }, label = { Text(f.label) }) }
        }
        SliderRow("Text size", settings.textScale, 0.85f..1.35f) { v -> update { it.copy(textScale = v) } }
        SectionLabel("Motion")
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
    // No automatic network call to Google until the user allowed cloud AI; the explicit "Test" button in Connectivity still works.
    LaunchedEffect(keyVersion, settings.cloudConsent) {
        if (settings.cloudConsent) { loading = true; status = c.provider.status(); loading = false } else { status = null; loading = false }
    }

    Page("AI Brain", onBack) {
        SectionLabel("Gemini API key")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (savedKey != null) {
                    StatusPill("Key saved · …${savedKey.takeLast(4)}", UltronColors.Green)
                    Text("Chat seedha Google Gemini se hota hai (koi beech ka server nahi).", style = MaterialTheme.typography.bodyMedium)
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
                        // Saving a key is NOT consent to send data: cloud permission stays a separate, explicit switch.
                        c.settings.setGeminiKey(keyInput); keyInput = ""; keyVersion++
                    }) { Text("Save") }
                    OutlinedButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Chhupao" else "Dikhao") }
                    if (savedKey != null) TextButton(onClick = { c.settings.setGeminiKey(null); keyVersion++ }) { Text("Hatao") }
                }
                Text("Save karne par tumhara chat text Google Gemini ko bheja jayega (Cloud AI allow on ho jata hai). Phone encrypted storage rooted/modified phone par bhi nikali ja sakti hai, isliye Google AI Studio mein key par restrictions lagao aur key kisi ke saath share mat karo.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionLabel("Provider")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val s = status
                when {
                    loading -> StatusPill("Check ho raha hai…", UltronColors.Gold)
                    !settings.cloudConsent -> StatusPill(if (savedKey == null) "Key chahiye" else "Cloud AI off — check nahi kiya", UltronColors.Gold)
                    s == null || !s.reachable -> StatusPill(if (savedKey == null) "Key chahiye" else "Gemini tak pahunch nahi paya", UltronColors.Red)
                    s.error != null -> StatusPill("Setup chahiye", UltronColors.Gold)
                    else -> StatusPill("Key accept hui · ${s.provider ?: "provider"}", UltronColors.Green)
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
            if (models.isNotEmpty() && settings.geminiModel !in models && settings.geminiModel.isNotBlank()) Text("Chuna hua model (${settings.geminiModel}) aapki key ki list mein nahi hai — chat fail ho sakti hai. Upar se list wala model chuno.", color = UltronColors.Gold, style = MaterialTheme.typography.bodyMedium)
            if (models.isEmpty() && !loading) Text("Key se model list nahi mili. Default: ${settings.geminiModel}", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
            Text("Tez jawab ke liye flash / flash-lite model chuno.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
        } else Text("Model chunne ke liye pehle upar Gemini API key save karo.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
        Text("Auto / Fast / Balanced routing is build mein nahi hai: aap khud ek Gemini model chunte ho.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))

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
        SwitchRow("Cloud AI allow", "Chat text Google Gemini ko bheja jata hai. Band rakhoge to device commands (app kholna, reminder, YouTube) phir bhi chalenge.", settings.cloudConsent) { v -> update { it.copy(cloudConsent = v) } }
    }
}

@Composable
fun ConnectScreen(settings: UltronSettings, onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ProviderStatus?>(null) }
    var testing by remember { mutableStateOf(false) }

    Page("Connectivity", onBack) {
        SectionLabel("Google Gemini")
        Text("ULTRON ka koi apna server nahi hai. AI jawab, live voice transcription aur online awaaz seedha aapki Gemini API key se Google ko jaate hain (Menu → AI Brain mein key).", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)
        Button(enabled = !testing, onClick = { testing = true; scope.launch { result = c.provider.status(); testing = false } }) { Text(if (testing) "Test chal raha hai…" else "Gemini connection test karo") }
        result?.let { r ->
            GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        r.error == null -> StatusPill("Gemini connected", UltronColors.Green)
                        r.reachable -> StatusPill("Gemini mila, par problem hai", UltronColors.Gold)
                        else -> StatusPill("Gemini tak nahi pahunch paya", UltronColors.Red)
                    }
                    if (r.error == null) Text("Chat models jo aapki key dikhati hai: ${r.models.joinToString().ifBlank { "—" }}")
                    r.error?.let { Text(it, color = UltronColors.Gold) }
                }
            }
        }
        SectionLabel("Mausam")
        Text("Mausam seedha Open-Meteo se aata hai (koi key nahi, location permission nahi). Shehar Mausam screen mein set hota hai${if (settings.weatherCity.isBlank()) " — abhi set nahi" else ": ${settings.weatherCity}"}.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
        SectionLabel("Offline")
        Text("Internet ke bina ye chalta hai: Journal, Mood, Yaadein, reminders, app kholna, YouTube search handoff, OCR, phone ki awaaz (Voice Studio). AI chat, live voice aur online awaaz ke liye internet aur Gemini key chahiye.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
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
    var resetNote by remember { mutableStateOf<String?>(null) }
    val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Page("Privacy aur Permissions", onBack) {
        SectionLabel("Permissions dashboard")
        PermRow("Microphone", if (mic) "Allowed" else "Mic dabane par poochhi jayegi", if (mic) UltronColors.Green else UltronColors.Gold, "Live voice mein mic ek session ke dauran khula rehta hai (notification mein Stop). Har baat ka audio transcription ke liye Google Gemini ko jata hai (Cloud AI allow ho tab). Audio disk par save nahi hota.")
        val n = notificationsAllowed(context)
        PermRow("Notifications", if (n) "Allowed" else "Blocked", if (n) UltronColors.Green else UltronColors.Gold, "Sirf aapke set kiye reminders ke liye.")
        PermRow("Camera", "App ke paas nahi", UltronColors.TextLo, "Scan system camera app use karta hai; ULTRON camera access nahi rakhta.")
        PermRow("Location", "Use nahi hoti", UltronColors.TextLo, "Mausam ke liye shehar aap khud likhte ho.")
        PermRow("Screenshot / screen recording", "Sirf aapke tap par", UltronColors.TextLo, "Neeche ke buttons se hi shuru hota hai. Recording se pehle Android ka consent dialog aata hai aur notification dikhti hai. Kuch bhi upload nahi hota, file sirf aapke phone me save hoti hai.")
        val a11y = c.screenControl.isEnabled()
        PermRow("Accessibility (screen control)", if (a11y) "On" else "Off", if (a11y) UltronColors.Green else UltronColors.TextLo, "Aap Android Settings mein khud on karte ho. On hone par ULTRON aapke command par click/scroll/type kar sakta hai aur screen ke text ko padh sakta hai (password fields nahi). Agent mode mein screen ka text Gemini ko ja sakta hai.")
        val overlay = android.provider.Settings.canDrawOverlays(context)
        PermRow("Dusre apps ke upar dikhna", if (overlay) "Allowed" else "Off", if (overlay) UltronColors.Green else UltronColors.TextLo, "Sirf live ke dauran edge light dikhane ke liye.")
        val live = c.live.isActive
        PermRow("Live mic service", if (live) "Chal rahi hai" else "Band", if (live) UltronColors.Green else UltronColors.TextLo, "Foreground service, hamesha notification ke saath. Offline wake word aapki apni recording se banta hai (Live aur Assistant screen).")

        SectionLabel("ULTRON ke rules")
        var rules by remember(settings.customRules) { mutableStateOf(settings.customRules) }
        OutlinedTextField(value = rules, onValueChange = { rules = it.take(600) }, label = { Text("Apne rules (max 600 akshar)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(onClick = { scope.launch { c.settings.update { it.copy(customRules = rules.trim()) } } }, enabled = rules.trim() != settings.customRules) { Text("Rules save") }
        Text("Ye rules har jawab ke saath jate hain, par identity/honesty/safety rules se upar nahi.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodySmall)

        SectionLabel("Cloud")
        SwitchRow("Cloud AI allow", "Chat text (aur recent context) seedha Google Gemini ko jata hai. Live voice mein har baat ki audio bhi transcription ke liye Gemini ko jati hai. Camera/screenshot kabhi nahi (screen ka text sirf neeche ke alag switch se).", settings.cloudConsent) { v -> scope.launch { c.settings.update { it.copy(cloudConsent = v, screenCloudConsent = if (v) it.screenCloudConsent else false) } } }
        SwitchRow("Screen text cloud ko bhejo", if (!settings.cloudConsent) "Pehle upar Cloud AI allow karo." else "Screen task (\"screen par ye karo\") mein screen ke buttons/text (password fields nahi) Gemini ko jate hain. Band ho to screen task chalega hi nahi.", settings.screenCloudConsent && settings.cloudConsent, enabled = settings.cloudConsent) { v -> scope.launch { c.settings.update { it.copy(screenCloudConsent = v) } } }

        SwitchRow("Screenshot/recents block", "Off (default): screenshot aur recording chalte hain. On: ULTRON ki screen ka screenshot/recording nahi banta aur recent apps me preview chhupta hai.", settings.secureScreen) { v -> scope.launch { c.settings.update { it.copy(secureScreen = v) } } }

        SectionLabel("Calling")
        val callPerms = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.SEND_SMS)
        var callGranted by remember { mutableStateOf(callPerms.map { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
        val callLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
            callGranted = callPerms.map { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        }
        PermRow("Calling permissions", if (callGranted.all { it }) "Sab allowed" else "Kuch baaki", if (callGranted.all { it }) UltronColors.Green else UltronColors.Gold, "Contacts (naam dhoondhne), Call (seedha call), Phone state (call lagi ya nahi + call ke dauran ULTRON chup), call kaatna aur SMS bhejna. Har call se pehle ULTRON aapse awaaz me haan/nahi poochta. Kuch deny ho to dialer khulta hai aur ULTRON bolta hai ki call abhi shuru nahi hui.")
        if (!callGranted.all { it }) Button(onClick = { callLauncher.launch(callPerms) }) { Text("Calling permissions do") }

        SectionLabel("Screenshot aur screen recording")
        val rec by com.ultron.ai.platform.capture.ScreenRecordService.recording.collectAsState()
        val recErr by com.ultron.ai.platform.capture.ScreenRecordService.error.collectAsState()
        val recSaved by com.ultron.ai.platform.capture.ScreenRecordService.lastSaved.collectAsState()
        var shotNote by remember { mutableStateOf<String?>(null) }
        var recMic by remember { mutableStateOf(false) }
        val projLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data
            if (r.resultCode == android.app.Activity.RESULT_OK && d != null) com.ultron.ai.platform.capture.ScreenRecordService.start(context, r.resultCode, d, recMic)
            else shotNote = "Recording ki permission nahi mili."
        }
        fun beginRecording() {
            val mpm = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
            projLauncher.launch(mpm.createScreenCaptureIntent())
        }
        val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) beginRecording() else { recMic = false; shotNote = "Mic permission nahi mili, mic ke bina record kar sakte ho." }
        }
        SwitchRow("Recording me mic ki awaaz", "Sirf mic record hota hai. Phone ki andar ki awaaz (device audio) is build me record nahi hoti. Live mic chal raha ho to mic busy ho sakta hai.", recMic) { v -> recMic = v }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = {
                var a: android.content.Context = context
                while (a is android.content.ContextWrapper && a !is android.app.Activity) a = a.baseContext
                (a as? android.app.Activity)?.let { act -> com.ultron.ai.platform.capture.ScreenshotSaver.capture(act) { _, m -> shotNote = m } } ?: run { shotNote = "Screenshot yahan se nahi ban sakta." }
            }) { Text("Screenshot lo") }
            if (!rec) Button(onClick = {
                if (recMic && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micLauncher.launch(Manifest.permission.RECORD_AUDIO) else beginRecording()
            }) { Text("Recording shuru") }
            else Button(onClick = { com.ultron.ai.platform.capture.ScreenRecordService.stop(context) }) { Text("Recording roko") }
        }
        if (rec) Text("● Recording chal rahi hai", color = UltronColors.Gold, modifier = Modifier.padding(top = 4.dp))
        (recErr ?: shotNote)?.let { Text(it, color = UltronColors.Gold, modifier = Modifier.padding(top = 4.dp)) }
        recSaved?.let { Text("Last video: $it", color = UltronColors.TextLo, style = MaterialTheme.typography.bodySmall) }

        SectionLabel("Aapka data")
        Text("Chats, journal, mood, yaadein aur reminders sirf is phone par, encrypted database (SQLCipher, Keystore key) mein hain. Android cloud backup band hai. Diagnostics log memory mein rehta hai, redacted, disk par nahi jata.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { confirmChats = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Saari chat history delete karo") }
        OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Sab kuch reset karo") }
        resetNote?.let { Text(it, color = UltronColors.Gold, modifier = Modifier.padding(top = 6.dp)) }
    }
    if (confirmChats) ConfirmDialog("Saari chats delete karein?", "Sab conversations hamesha ke liye hat jayengi.", "Delete", { scope.launch { c.turns.cancel(); c.db.conversations().deleteAll() }; confirmChats = false }, { confirmChats = false })
    if (confirmAll) ConfirmDialog(
        "Sab kuch reset karein?", "Chats, journal, mood, yaadein, reminders, settings, saved Gemini key, wake-word voice data, import kiye avatar models aur cache — sab hamesha ke liye hat jayega. Ye undo nahi hota.", "Sab reset karo",
        {
            scope.launch {
                // Stop everything that could still be using the data, then wipe each store and report real outcomes.
                c.turns.cancel(); c.tts.stop()
                com.ultron.ai.platform.live.LiveSessionService.stop(context)
                val R = com.ultron.ai.data.ResetEverything
                val results = R.run(listOf(
                    com.ultron.ai.data.ResetEverything.Step("reminders") { c.reminders.cancelAll(); true },
                    com.ultron.ai.data.ResetEverything.Step("database") {
                        c.db.conversations().deleteAll(); c.db.journal().deleteAll(); c.db.mood().deleteAll(); c.db.memory().deleteAll(); c.db.workDocs().deleteAll(); c.db.markets().deleteAll(); true
                    },
                    com.ultron.ai.data.ResetEverything.Step("settings + API key") { c.settings.resetAll(); c.settings.geminiKey() == null },
                    com.ultron.ai.data.ResetEverything.Step("wake-word voice data") { c.personalWake.clear(); !java.io.File(context.filesDir, "wake_templates.bin").exists() && !java.io.File(context.filesDir, "wake_phrases.bin").exists() },
                    com.ultron.ai.data.ResetEverything.Step("avatar models") { c.avatars.wipeAll() },
                    com.ultron.ai.data.ResetEverything.Step("cache") { com.ultron.ai.data.ResetEverything.deleteRecursively(context.cacheDir) },
                    com.ultron.ai.data.ResetEverything.Step("export record") { context.getSharedPreferences("ultron_backup", android.content.Context.MODE_PRIVATE).edit().clear().commit() },
                    com.ultron.ai.data.ResetEverything.Step("unreadable old database copies") {
                        c.secure.deleteUnreadablePassphrases()
                        com.ultron.ai.data.db.UltronDatabase.unreadableFiles(context).all { it.delete() }
                    },
                ))
                resetNote = com.ultron.ai.data.ResetEverything.summary(results)
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
