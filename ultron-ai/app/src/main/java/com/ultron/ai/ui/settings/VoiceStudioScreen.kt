package com.ultron.ai.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.platform.voice.VoiceInfo
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.components.StatusPill
import com.ultron.ai.ui.theme.UltronColors
import kotlinx.coroutines.launch

private const val SAMPLE = "Systems ready. Batao, kya solve karna hai?"

@Composable
fun VoiceStudioScreen(settings: UltronSettings, onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tts by c.tts.state.collectAsState()
    var filter by remember { mutableStateOf(0) } // 0 = Indian hi/en, 1 = all
    val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun update(block: (UltronSettings) -> UltronSettings) = scope.launch { c.settings.update(block) }
    fun preview(s: UltronSettings) {
        c.tts.applySettings(s) // use exactly the values on screen, then speak
        c.tts.preview(SAMPLE)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenHeader("Awaaz Studio", onBack)

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        tts.initializing -> StatusPill("Engine start ho raha hai", UltronColors.Gold)
                        tts.ready -> StatusPill("Engine ready", UltronColors.Green)
                        else -> StatusPill("Engine available nahi", UltronColors.Red)
                    }
                }
                Text("Engine: ${tts.engine ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Active voice: ${tts.activeVoice ?: "engine default"}", style = MaterialTheme.typography.bodyMedium)
                tts.warning?.let { Text(it, color = UltronColors.Gold, style = MaterialTheme.typography.bodyMedium) }
                tts.error?.let { Text(it, color = UltronColors.Red, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = tts.ready, onClick = { preview(settings) }) { Text("Test voice") }
                    OutlinedButton(onClick = { c.tts.stop() }) { Text("Stop") }
                }
            }
        }

        SectionLabel("Style")
        SliderRow("Speed", settings.ttsRate, 0.5f..1.5f) { v -> update { it.copy(ttsRate = v) } }
        SliderRow("Pitch (kam = gehri awaaz)", settings.ttsPitch, 0.5f..1.5f) { v -> update { it.copy(ttsPitch = v) } }
        SliderRow("Volume", settings.ttsVolume, 0.2f..1f) { v -> update { it.copy(ttsVolume = v) } }
        OutlinedButton(onClick = {
            val s = settings.copy(ttsRate = 0.9f, ttsPitch = 0.78f)
            update { it.copy(ttsRate = 0.9f, ttsPitch = 0.78f) }
            preview(s)
        }) { Text("Deep cinematic preset") }
        Text(
            "Preset sirf speed/pitch badalta hai. Awaaz kitni natural lagegi wo aapke device ke TTS engine/voice par depend karti hai; ye kisi film character ki awaaz nahi hai.",
            style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo, modifier = Modifier.padding(top = 6.dp),
        )
        SwitchRow("Jawab bolkar sunao", "Chat aur Live dono mein ULTRON ke jawab bole jayenge.", settings.speakReplies) { v -> update { it.copy(speakReplies = v) } }

        SectionLabel("TTS engine")
        if (tts.engines.isEmpty()) Text("Koi TTS engine detect nahi hua.", color = UltronColors.TextLo)
        tts.engines.forEach { e ->
            val selected = (settings.ttsEngine ?: tts.engine) == e.packageName
            Row(Modifier.fillMaxWidth().clickable { update { it.copy(ttsEngine = e.packageName, ttsVoice = null) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = { update { it.copy(ttsEngine = e.packageName, ttsVoice = null) } })
                Column { Text(e.label); Text(e.packageName, style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo) }
            }
        }
        TextButton(onClick = {
            try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { /* settings page not exposed on this device */ }
        }) { Text("Android TTS settings / voice data install") }

        SectionLabel("Voices (${tts.voices.size} mile)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("Hindi / English") })
            FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Sab") })
        }
        val shown = tts.voices.filter { filter == 1 || it.localeTag.startsWith("hi") || it.localeTag.startsWith("en") }
        if (shown.isEmpty()) Text("Is filter mein koi voice nahi. \"Sab\" try karo ya TTS settings mein voice data install karo.", color = UltronColors.TextLo, modifier = Modifier.padding(top = 8.dp))
        shown.forEach { v -> VoiceRow(v, selected = settings.ttsVoice == v.name, onPick = {
            update { it.copy(ttsVoice = v.name) }
            preview(settings.copy(ttsVoice = v.name))
        }) }
        if (settings.ttsVoice != null && tts.voices.isNotEmpty() && tts.voices.none { it.name == settings.ttsVoice }) {
            Text("Aapki saved voice \"${settings.ttsVoice}\" abhi device par nahi mil rahi. Selection hata nahi hai; voice pack wapas install karoge to wahi use hogi.", color = UltronColors.Gold, modifier = Modifier.padding(top = 8.dp))
        }

        SectionLabel("Mic aur wake word")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Microphone permission", Modifier.weight(1f))
            if (micOk) StatusPill("Allowed", UltronColors.Green) else StatusPill("Mic dabane par poochhi jayegi", UltronColors.Gold)
        }
        Text("Speech recognition: ${if (c.stt.isAvailable()) "device recognizer available (network ki zaroorat device par depend karti hai)" else "available nahi"}", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo, modifier = Modifier.padding(top = 6.dp))
        GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(14.dp)) {
                StatusPill("Wake word: available nahi", UltronColors.TextLo)
                Text(
                    "\"Hey Ultron\" jaisi hamesha-on detection ke liye ek asli low-power wake-word engine aur foreground service chahiye. Wo is build mein nahi hai, aur main normal speech recognition ko background mein baar-baar chalake use \"always-on\" nahi bolunga. Abhi push-to-talk (mic button) use hota hai.",
                    style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp))
    }
}

@Composable
private fun VoiceRow(v: VoiceInfo, selected: Boolean, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onPick)
        Column(Modifier.weight(1f)) {
            Text(v.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            val tags = buildList {
                add(v.localeTag)
                add("quality ${qualityLabel(v.quality)}")
                add(if (v.needsNetwork) "internet chahiye" else "offline")
                if (!v.installed) add("voice data install nahi")
            }
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = if (v.installed) UltronColors.TextLo else UltronColors.Gold)
        }
    }
}

private fun qualityLabel(q: Int) = when {
    q >= 500 -> "very high"
    q >= 400 -> "high"
    q >= 300 -> "normal"
    q >= 200 -> "low"
    else -> "very low"
}
