package com.ultron.ai.ui.home

import android.net.Uri
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
    val state by c.assistantState.collectAsState()
    val level by c.stt.level.collectAsState()
    val weather by c.weather.state.collectAsState()
    val mood by c.db.mood().observeLatest().collectAsState(initial = null)
    val nextReminder by c.db.reminders().observeNext().collectAsState(initial = null)
    var draft by remember { mutableStateOf("") }
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrNote by remember { mutableStateOf<String?>(null) }
    var avatarFailed by remember(settings.avatarId) { mutableStateOf(false) }
    val use3d = settings.avatarId != AvatarIds.CORE && !settings.lowPerformance && !avatarFailed

    LaunchedEffect(settings.backendUrl, settings.weatherCity) { c.weather.refresh() }

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

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        TopBar(onMenu = onMenu, onBell = { onNavigate(Routes.TODAY) }, onAvatar = { onNavigate(Routes.GENERAL) })
        Spacer(Modifier.height(8.dp))

        // Greeting + real status card
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(greeting() + ",", style = MaterialTheme.typography.headlineMedium, color = UltronColors.TextLo, fontWeight = FontWeight.Medium)
                Text(
                    settings.userName.ifBlank { "ULTRON" },
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            GlassCard(Modifier.width(132.dp), onClick = { onNavigate(Routes.TODAY) }, shape = RoundedCornerShape(20.dp), tint = UltronColors.Violet.copy(alpha = 0.10f)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.AlarmOn, null, tint = UltronColors.Gold)
                    Column {
                        Text(
                            nextReminder?.let { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it.triggerAt)) } ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("Agla reminder", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.NightsStay, null, tint = UltronColors.TextLo)
            Text(stateLine(state), style = MaterialTheme.typography.bodyLarge, color = UltronColors.TextLo)
        }

        // Core (or 3D avatar) with wordmark
        if (use3d) {
            AvatarHero(
                avatarId = settings.avatarId, state = state, level = level,
                lowPerformance = settings.lowPerformance, reducedMotion = settings.reducedMotion,
                onFailed = { avatarFailed = true }, onClick = { onNavigate(Routes.LIVE) },
            )
            Spacer(Modifier.height(12.dp))
        } else Box(
            Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onNavigate(Routes.LIVE) }
                .semantics { contentDescription = "ULTRON core. Live voice kholne ke liye tap karo" },
            contentAlignment = Alignment.Center,
        ) {
            UltronCore(state, level, Modifier.fillMaxWidth(0.94f), compact = true)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(0.58f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(1.dp).background(UltronColors.Violet.copy(alpha = 0.6f)))
                    Text("AI ASSISTANT", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextHi)
                    Box(Modifier.weight(1f).height(1.dp).background(UltronColors.Violet.copy(alpha = 0.6f)))
                }
                Text(
                    "ULTRON",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                        brush = Brush.horizontalGradient(listOf(UltronColors.Cyan, Color2, UltronColors.Magenta)),
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Equalizer(state, level)
            }
            Text(
                if (state == AssistantState.IDLE) "BOLO, KAISE HELP KARUN?" else coreStateLabel(state).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = UltronColors.TextLo,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }

        // Quick actions
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("Gaane", Icons.Filled.MusicNote, Modifier.weight(1f)) { onNavigate(Routes.GAANE) }
            QuickAction("Padhai", Icons.Filled.MenuBook, Modifier.weight(1f)) { onNavigate(Routes.chat(study = true)) }
            QuickAction("Journal", Icons.Filled.Edit, Modifier.weight(1f)) { onNavigate(Routes.JOURNAL) }
        }
        Spacer(Modifier.height(12.dp))

        // Info cards (all real data)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (wTitle, wSub) = when (val w = weather) {
                is WeatherState.Available -> "${w.weather.tempC.toInt()}°C" to w.weather.description
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
        Spacer(Modifier.height(12.dp))

        ocrNote?.let { Text(it, color = UltronColors.Gold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp)) }
        ComposerBar(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "ULTRON se kuch bhi poochho...",
            onSend = {
                c.pendingPrompt.value = PendingPrompt(draft.trim(), autoSend = true)
                draft = ""
                onNavigate(Routes.chat())
            },
            onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            attachBusy = ocrBusy,
        )
        Spacer(Modifier.height(16.dp))
    }
}

private val Color2 = UltronColors.TextHi

@Composable
private fun TopBar(onMenu: () -> Unit, onBell: () -> Unit, onAvatar: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu kholo", modifier = Modifier.size(30.dp))
        }
        Text(
            "ULTRON", style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onBell, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Notifications, contentDescription = "Reminders", modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(UltronColors.Bg)
                .border(1.5.dp, UltronColors.Cyan.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                .clickable(onClick = onAvatar)
                .semantics { contentDescription = "ULTRON profile aur settings" },
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(52.dp))
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.height(56.dp), onClick = onClick, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = UltronColors.TextHi, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
private fun InfoCard(title: String, icon: ImageVector, value: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.height(92.dp), onClick = onClick, shape = RoundedCornerShape(22.dp)) {
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
    AssistantState.OFFLINE -> "Offline — backend tak pahunch nahi paya"
    AssistantState.ERROR -> "Error aaya — details Chat mein hain"
}

fun moodLabel(level: Int) = when (level) {
    1 -> "Bahut low"
    2 -> "Low"
    3 -> "Theek"
    4 -> "Accha"
    else -> "Mast"
}
