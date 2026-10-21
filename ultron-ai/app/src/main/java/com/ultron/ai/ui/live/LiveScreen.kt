package com.ultron.ai.ui.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ultron.ai.AssistantState
import com.ultron.ai.data.ai.ChatMode
import com.ultron.ai.data.ai.ErrorKind
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.platform.voice.SttState
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.Routes
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.UltronCore
import com.ultron.ai.ui.components.coreStateLabel
import com.ultron.ai.ui.theme.UltronColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Live voice: mic -> speech recognition -> same chat/action pipeline -> TTS with the saved voice.
 * One tap on the big button always does the right thing for the real state:
 * speaking/thinking -> stop & listen again (barge-in), listening -> finish, idle -> listen.
 */
@Composable
fun LiveScreen(settings: UltronSettings, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by c.assistantState.collectAsState()
    val stt by c.stt.state.collectAsState()
    val level by c.stt.level.collectAsState()
    val turn by c.turns.state.collectAsState()
    var micGranted by remember { mutableStateOf(hasMic(context)) }
    var asked by remember { mutableStateOf(false) }
    var lastHeard by remember { mutableStateOf("") }

    fun listen() {
        // Barge-in + feedback-loop prevention: silence the assistant BEFORE opening the mic.
        c.turns.cancel()
        c.turns.dismissError()
        c.stt.clearError()
        c.stt.start(settings.language.speechTag) { text ->
            lastHeard = text
            scope.launch {
                val id = c.engine.latestOrNew(ChatMode.CHAT)
                c.turns.submit(id, text, ChatMode.CHAT, speak = true)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        asked = true
        if (granted) listen()
    }

    DisposableEffect(Unit) {
        onDispose {
            c.stt.cancel() // never leave the mic open when the screen goes away
        }
    }

    fun onMicTap() {
        if (!micGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        when {
            stt is SttState.Listening -> c.stt.stopListening()
            else -> listen()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenHeader("Live voice", onBack)

        UltronCore(state, level, Modifier.fillMaxWidth(0.82f).padding(top = 8.dp))
        Text(coreStateLabel(state).uppercase(), style = MaterialTheme.typography.labelMedium, color = UltronColors.Cyan, modifier = Modifier.padding(top = 4.dp))

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val partial = (stt as? SttState.Listening)?.partial.orEmpty()
            when {
                !micGranted -> PermissionCard(permanentlyDenied = asked && !hasMic(context), onGrant = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }, onSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                })
                stt is SttState.Error -> Message((stt as SttState.Error).message, UltronColors.Gold)
                turn.error != null -> {
                    Message(turn.error!!.message, UltronColors.Red)
                    Row {
                        if (turn.error!!.kind == ErrorKind.NOT_CONFIGURED || turn.error!!.kind == ErrorKind.UNAUTHORIZED) {
                            TextButton(onClick = { onNavigate(Routes.CONNECT) }) { Text("Connectivity") }
                        } else {
                            TextButton(onClick = { c.turns.dismissError(); c.turns.retry(ChatMode.CHAT, true) }) { Text("Dobara try") }
                        }
                    }
                }
                turn.needsConsent -> {
                    Message("Jawab ke liye cloud AI ki ijazat chahiye (aapka text backend ko jayega).", UltronColors.Gold)
                    Button(onClick = { scope.launch { c.turns.grantConsentAndRetry(ChatMode.CHAT, true) } }) { Text("Allow karo") }
                }
                partial.isNotEmpty() -> Message(partial, UltronColors.TextHi)
                turn.streaming.isNotEmpty() -> Message(turn.streaming, UltronColors.TextHi)
                lastHeard.isNotEmpty() && turn.busy -> Message("“$lastHeard”", UltronColors.TextLo)
                else -> Message(
                    if (c.stt.isAvailable()) "Mic dabao aur bolo. Bolte waqt phir dabao to main ruk jaunga." else "Is device par speech recognition service nahi mili.",
                    UltronColors.TextLo,
                )
            }
        }

        Row(Modifier.padding(bottom = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(Modifier.size(56.dp))
            val listening = stt is SttState.Listening
            IconButton(
                onClick = ::onMicTap,
                modifier = Modifier.size(84.dp).clip(CircleShape).background(if (listening) UltronColors.Cyan else UltronColors.Violet),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = if (listening) "Sunna band karo" else "Bolna shuru karo", tint = Color.White, modifier = Modifier.size(38.dp))
            }
            val active = listening || turn.busy || state == AssistantState.SPEAKING
            IconButton(
                onClick = { c.stt.cancel(); c.turns.cancel() },
                enabled = active,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(if (active) UltronColors.Red.copy(alpha = 0.8f) else UltronColors.Red.copy(alpha = 0.15f)),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "Sab kuch rokho", tint = Color.White)
            }
        }
    }
}

private fun hasMic(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
private fun Message(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun PermissionCard(permanentlyDenied: Boolean, onGrant: () -> Unit, onSettings: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Microphone ki permission chahiye", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Sirf tab sunta hoon jab aap mic button dabate ho. Koi background listening nahi hai, aur recording save nahi hoti.",
                color = UltronColors.TextLo, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            if (permanentlyDenied) {
                Text("Android ne permission block kar rakhi hai. Settings mein allow karo.", color = UltronColors.Gold, textAlign = TextAlign.Center)
                Button(onClick = onSettings) { Text("App settings kholo") }
            } else {
                Button(onClick = onGrant) { Text("Mic allow karo") }
            }
        }
    }
}
