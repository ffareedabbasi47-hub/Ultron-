package com.ultron.ai.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import com.ultron.ai.data.ai.ChatMode
import com.ultron.ai.data.ai.ErrorKind
import com.ultron.ai.platform.voice.SttState
import kotlinx.coroutines.flow.emptyFlow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultron.ai.AssistantState
import com.ultron.ai.PendingPrompt
import com.ultron.ai.R
import com.ultron.ai.data.avatar.AvatarIds
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.ui.avatar.AvatarHero
import com.ultron.ai.data.weather.WeatherState
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.Routes
import com.ultron.ai.ui.components.ComposerBar
import com.ultron.ai.ui.components.Equalizer
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.glass
import com.ultron.ai.ui.components.UltronCore
import com.ultron.ai.ui.components.coreStateLabel
import com.ultron.ai.ui.theme.UltronColors
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    settings: UltronSettings,
    onMenu: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val appState by c.assistantState.collectAsState()
    val sttLevel by c.stt.level.collectAsState()
    val weather by c.weather.state.collectAsState()
    val mood by c.db.mood().observeLatest().collectAsState(initial = null)
    val nextReminder by c.db.reminders().observeNext().collectAsState(initial = null)
    var draft by remember { mutableStateOf("") }
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrNote by remember { mutableStateOf<String?>(null) }
    var avatarFailed by remember(settings.avatarId) { mutableStateOf(false) }
    val use3d = settings.avatarId != AvatarIds.CORE && !settings.lowPerformance && !avatarFailed

    val context = LocalContext.current
    val stt by c.stt.state.collectAsState()
    val turn by c.turns.state.collectAsState()
    var micGranted by remember { mutableStateOf(hasMic(context)) }
    var micAsked by remember { mutableStateOf(false) }

    LaunchedEffect(settings.backendUrl, settings.weatherCity) { c.weather.refresh() }

    val liveState by c.live.state.collectAsState()
    val liveLevel by c.live.level.collectAsState()
    val liveMsg by c.live.message.collectAsState()
    val liveOn = liveState != com.ultron.ai.domain.live.LiveState.STOPPED
    val state = if (!liveOn) appState else when (liveState) {
        com.ultron.ai.domain.live.LiveState.LISTENING, com.ultron.ai.domain.live.LiveState.USER_SPEECH, com.ultron.ai.domain.live.LiveState.SLEEP -> AssistantState.LISTENING
        com.ultron.ai.domain.live.LiveState.THINKING -> AssistantState.THINKING
        com.ultron.ai.domain.live.LiveState.SPEAKING -> AssistantState.SPEAKING
        com.ultron.ai.domain.live.LiveState.AUDIO_ERROR, com.ultron.ai.domain.live.LiveState.PERMISSION_REQUIRED -> AssistantState.ERROR
        else -> AssistantState.IDLE
    }
    val level = if (liveOn) liveLevel else sttLevel

    fun startLive() {
        c.turns.dismissError()
        com.ultron.ai.platform.live.LiveSessionService.start(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        micGranted = res[Manifest.permission.RECORD_AUDIO] == true || hasMic(context)
        micAsked = true
        if (micGranted) startLive()
    }
    fun onMicTap() {
        when {
            liveOn && liveState != com.ultron.ai.domain.live.LiveState.SLEEP && liveState != com.ultron.ai.domain.live.LiveState.PAUSED &&
                liveState != com.ultron.ai.domain.live.LiveState.AUDIO_ERROR && liveState != com.ultron.ai.domain.live.LiveState.PERMISSION_REQUIRED ->
                com.ultron.ai.platform.live.LiveSessionService.stop(context)
            liveOn -> startLive() // resume / wake
            !micGranted -> {
                val perms = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(perms.toTypedArray())
            }
            else -> startLive()
        }
    }
    val micReq by c.micRequest.collectAsState()
    LaunchedEffect(micReq) { if (micReq) { c.micRequest.value = false; onMicTap() } }
    val liveReq by c.liveStartRequest.collectAsState()
    LaunchedEffect(liveReq) {
        if (liveReq) {
            c.liveStartRequest.value = false
            if (c.live.state.value == com.ultron.ai.domain.live.LiveState.STOPPED || c.live.state.value == com.ultron.ai.domain.live.LiveState.SLEEP || c.live.state.value == com.ultron.ai.domain.live.LiveState.PAUSED) onMicTap()
        }
    }
    // NOTE: leaving Home does NOT touch the microphone: the live session is owned by LiveSessionEngine + its service.

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        ocrBusy = true
        ocrNote = null
        scope.launch {
            try {
                val text = c.ocr.recognize(uri, devanagari = false).trim()
                if (text.isEmpty()) ocrNote = "Image mein text nahi mila."
                else draft = (draft + "\n" + text).trim()
            } catch (e: Exception) {
                ocrNote = "Image padh nahi paya."
            } finally {
                ocrBusy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        TopBar(onMenu = onMenu, onBell = { onNavigate(Routes.NOTIFS) }, onAvatar = { onNavigate(Routes.GENERAL) })

        // Greeting + real reminder card (same slot as the reference's status card)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(greeting() + ",", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp), color = UltronColors.TextLo, fontWeight = FontWeight.Medium)
                Text(
                    settings.userName.ifBlank { "ULTRON" },
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 36.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            GlassCard(Modifier.width(128.dp), onClick = { onNavigate(Routes.TODAY) }, shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.AlarmOn, null, tint = UltronColors.Gold)
                    Column {
                        Text(
                            nextReminder?.let { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it.triggerAt)) } ?: "—",
                            style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        )
                        Text("Agla reminder", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo, maxLines = 1)
                    }
                }
            }
        }

        // Core / avatar fills whatever height is left, so the whole screen fits in one frame.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight)
                if (use3d) {
                    AvatarHero(
                        avatarId = settings.avatarId, state = state, level = level,
                        lowPerformance = settings.lowPerformance, reducedMotion = settings.reducedMotion,
                        onFailed = { avatarFailed = true }, onClick = ::onMicTap,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else Box(
                    Modifier.size(side).clickable(onClick = ::onMicTap)
                        .semantics { contentDescription = "ULTRON core. Bolne ke liye tap karo" },
                    contentAlignment = Alignment.Center,
                ) {
                    UltronCore(state, level, Modifier.size(side), compact = true)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(0.62f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f).height(1.dp).background(UltronColors.Blue.copy(alpha = 0.6f)))
                            Text("AI ASSISTANT", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextHi)
                            Box(Modifier.weight(1f).height(1.dp).background(UltronColors.Blue.copy(alpha = 0.6f)))
                        }
                        Text(
                            "U.L.T.R.O.N",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                brush = Brush.horizontalGradient(listOf(UltronColors.Cyan, UltronColors.TextHi, UltronColors.Violet)),
                            ),
                            textAlign = TextAlign.Center, maxLines = 1,
                        )
                        Equalizer(state, level)
                        Text(
                            if (liveOn) "LIVE · " + liveState.label else if (state == AssistantState.IDLE) "BOLO, KAISE HELP KARUN?" else coreStateLabel(state).uppercase(),
                            style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // Problems only (permission / consent / error). The conversation itself lives in Chat.
        Notice(
            micGranted = micGranted, permanentlyDenied = micAsked && !hasMic(context), stt = liveMsg?.let { SttState.Error(it) } ?: stt, turn = turn,
            onSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) },
            onRetry = { c.turns.dismissError(); c.turns.retry(ChatMode.CHAT, true) },
            onConsent = { scope.launch { c.turns.grantConsentAndRetry(ChatMode.CHAT, true) } },
            onAiBrain = { c.turns.dismissError(); onNavigate(Routes.AI) },
            onDismiss = { c.turns.dismissError(); c.stt.clearError() },
        )

        val confirmQ by c.broker.pending.collectAsState()
        confirmQ?.let { q ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(q, color = UltronColors.Gold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 3)
                    TextButton(onClick = { c.broker.answer(true) }) { Text("Haan") }
                    TextButton(onClick = { c.broker.answer(false) }) { Text("Nahi") }
                }
            }
        }

        // Quick actions
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("Gaane", Icons.Filled.MusicNote, Modifier.weight(1f)) { onNavigate(Routes.GAANE) }
            QuickAction("Padhai", Icons.Filled.MenuBook, Modifier.weight(1f)) { onNavigate(Routes.chat(study = true)) }
            QuickAction("Journal", Icons.Filled.Edit, Modifier.weight(1f)) { onNavigate(Routes.JOURNAL) }
        }
        Spacer(Modifier.height(10.dp))

        // Info cards (all real data)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (wTitle, wSub) = when (val w = weather) {
                is WeatherState.Available -> "${w.weather.tempC.toInt()}°" to w.weather.description
                WeatherState.Loading -> "…" to "Load ho raha hai"
                is WeatherState.Unavailable -> "—" to "Data nahi"
                WeatherState.Idle -> "—" to "Data nahi"
            }
            InfoCard("Mausam", Icons.Filled.Cloud, wTitle, wSub, Modifier.weight(1f)) { onNavigate(Routes.WEATHER) }
            val now = remember { Date() }
            InfoCard(
                "Aaj", Icons.Filled.CalendarToday,
                SimpleDateFormat("d", Locale.getDefault()).format(now),
                SimpleDateFormat("EEE, MMM", Locale.getDefault()).format(now),
                Modifier.weight(1f),
            ) { onNavigate(Routes.TODAY) }
            InfoCard(
                "Mood", Icons.Filled.Favorite,
                mood?.let { moodLabel(it.level) } ?: "—",
                mood?.note?.takeIf { it.isNotBlank() } ?: if (mood == null) "Entry nahi" else "Aaj ka mood",
                Modifier.weight(1f),
            ) { onNavigate(Routes.MOOD) }
        }
        Spacer(Modifier.height(10.dp))

        ocrNote?.let { Text(it, color = UltronColors.Gold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp)) }
        ComposerBar(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "ULTRON se kuch bhi poochho...",
            busy = turn.busy,
            onStop = { c.stt.cancel(); c.turns.cancel() },
            onSend = {
                val text = draft.trim(); draft = ""
                if (text.isNotEmpty()) {
                    c.pendingPrompt.value = PendingPrompt(text, autoSend = true)
                    onNavigate(Routes.chat())
                }
            },
            onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            attachBusy = ocrBusy,
        )
        Spacer(Modifier.height(8.dp))
    }
}

private val Color2 = UltronColors.TextHi

@Composable
private fun TopBar(onMenu: () -> Unit, onBell: () -> Unit, onAvatar: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu, modifier = Modifier.size(48.dp).glass(CircleShape)) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu kholo", modifier = Modifier.size(28.dp))
        }
        Text(
            "ULTRON", style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f).padding(start = 14.dp).clickable(onClick = onAvatar)
                .semantics { contentDescription = "ULTRON profile aur settings" },
        )
        IconButton(onClick = onBell, modifier = Modifier.size(48.dp).glass(CircleShape)) {
            Icon(Icons.Filled.Notifications, contentDescription = "Notifications", modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.height(52.dp), onClick = onClick, shape = RoundedCornerShape(26.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = UltronColors.TextHi, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
private fun InfoCard(title: String, icon: ImageVector, value: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.height(104.dp), onClick = onClick, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, tint = UltronColors.TextLo, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo, maxLines = 1)
            }
            Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun greeting(): String {
    val h = LocalTime.now().hour
    return when (h) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else -> "Good night"
    }
}

private fun stateLine(s: AssistantState) = when (s) {
    AssistantState.IDLE -> "ULTRON ready hai — mic dabao ya likho"
    AssistantState.LISTENING -> "Sun raha hoon…"
    AssistantState.THINKING -> "Soch raha hoon…"
    AssistantState.SPEAKING -> "Bol raha hoon…"
    AssistantState.OFFLINE -> "Offline — AI tak pahunch nahi paya"
    AssistantState.ERROR -> "Error aaya — details Chat mein hain"
}

fun moodLabel(level: Int) = when (level) {
    1 -> "Bahut low"
    2 -> "Low"
    3 -> "Theek"
    4 -> "Accha"
    else -> "Mast"
}

private fun hasMic(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Shows only things the user must act on. Conversation text is never shown on Home. */
@Composable
private fun Notice(
    micGranted: Boolean, permanentlyDenied: Boolean, stt: SttState, turn: com.ultron.ai.data.ai.TurnState,
    onSettings: () -> Unit, onRetry: () -> Unit, onConsent: () -> Unit, onAiBrain: () -> Unit, onDismiss: () -> Unit,
) {
    val sttError = (stt as? SttState.Error)?.message
    val err = turn.error
    val show = (permanentlyDenied && !micGranted) || sttError != null || err != null || turn.needsConsent
    if (!show) return
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val (msg, color) = when {
                permanentlyDenied && !micGranted -> "Mic permission block hai." to UltronColors.Gold
                sttError != null -> sttError to UltronColors.Gold
                turn.needsConsent -> "Jawab ke liye cloud AI allow karo." to UltronColors.Gold
                else -> (err?.message ?: "Error") to UltronColors.Red
            }
            Text(msg, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                permanentlyDenied && !micGranted -> TextButton(onClick = onSettings) { Text("Settings") }
                turn.needsConsent -> TextButton(onClick = onConsent) { Text("Allow") }
                err != null && (err.kind == ErrorKind.NOT_CONFIGURED || err.kind == ErrorKind.UNAUTHORIZED) -> TextButton(onClick = onAiBrain) { Text("AI Brain") }
                err != null -> TextButton(onClick = onRetry) { Text("Retry") }
                else -> TextButton(onClick = onDismiss) { Text("Theek") }
            }
        }
    }
}
