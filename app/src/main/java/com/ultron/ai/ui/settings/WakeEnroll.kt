package com.ultron.ai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.domain.wake.WakePhrase
import com.ultron.ai.platform.live.PersonalWake
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.components.StatusPill
import com.ultron.ai.ui.theme.UltronColors
import kotlinx.coroutines.launch

/** Record each wake phrase a few times so ULTRON can recognise it offline (free, private, speaker-dependent). */
@Composable
fun WakeEnrollSection(settings: UltronSettings, update: ((UltronSettings) -> UltronSettings) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val pw = c.personalWake
    val sets by pw.phrases.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val liveOn = c.live.isActive

    SectionLabel("Wake words — Hey / Hello / Wake up ULTRON (offline, free)")
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Har phrase ${pw.needed} baar bolkar record karo (kam se kam 3). Matching phone par hoti hai: koi network ya quota nahi lagta, sirf awaaz ke numbers save hote hain (audio nahi). " +
                    "Hey/Hello ULTRON par jawab 'Yes Boss?', Wake up ULTRON par 'I'm awake, Boss.' " +
                    "Ye sirf AAPKI awaaz/bolne ke tareeke ko pehchanta hai — asli neural wake-word engine nahi hai, isliye kabhi miss ya galat trigger ho sakta hai; sensitivity se adjust karo.",
                color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium,
            )
            Text(if (liveOn) "Record karne ke liye pehle Live band karo." else "Shant jagah mein, mic se ~30 cm door, normal awaaz mein.", color = UltronColors.TextLo)
        }
    }
    for (phrase in WakePhrase.entries) {
        val n = sets[phrase]?.size ?: 0
        val ok = n >= 3
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text("'${phrase.spoken}'", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (ok) StatusPill("$n recording · ready", UltronColors.Green) else StatusPill("$n/${pw.needed}", UltronColors.Gold)
                }
                Button(
                    enabled = !busy && !liveOn,
                    onClick = {
                        scope.launch {
                            busy = true; msg = "Ab bolo: '${phrase.spoken}'…"
                            when (val r = pw.record()) {
                                is PersonalWake.Rec.Failed -> msg = r.message
                                is PersonalWake.Rec.Ok -> msg = pw.add(r.pcm, phrase) ?: "Record ho gaya (${pw.count(phrase)})."
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Sun raha hoon…" else "Record karo (${n + 1})") }
                OutlinedButton(enabled = !busy && n > 0, onClick = { pw.clear(phrase); msg = "'${phrase.spoken}' ki recordings delete ho gayi." }, modifier = Modifier.fillMaxWidth()) { Text("Ye recordings delete karo") }
            }
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && !liveOn && pw.ready,
                onClick = {
                    scope.launch {
                        busy = true; msg = "Test: teeno mein se koi bhi phrase bolo…"
                        when (val r = pw.record()) {
                            is PersonalWake.Rec.Failed -> msg = r.message
                            is PersonalWake.Rec.Ok -> {
                                val d = pw.decide(r.pcm, settings.wakeSensitivity)
                                msg = if (d == null) "Test nahi ho paya." else (d.phrase?.let { "MATCH ✓ '${it.spoken}' → ${it.greeting}" } ?: "Match nahi (${d.note})") +
                                    (if (d.score < Float.MAX_VALUE && d.threshold > 0f) "  (score %.2f, limit %.2f)".format(d.score, d.threshold) else "")
                            }
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Test karo (kaun sa phrase pehchana)") }
            OutlinedButton(enabled = !busy && sets.isNotEmpty(), onClick = { pw.clear(); msg = "Saari recordings delete ho gayi." }, modifier = Modifier.fillMaxWidth()) { Text("Saari recordings delete karo") }
            msg?.let { Text(it, color = UltronColors.Cyan) }
        }
    }
    SwitchRow("Offline wake word on", "Sleep mein mic khula rehta hai (battery thodi lagti hai) aur wake phrase par ULTRON jaag jaata hai. Recording ke bina kaam nahi karta. Cloud key na ho tab bhi sirf wake chalta hai (kuch upload nahi hota).", settings.wakePersonal) { v -> update { it.copy(wakePersonal = v) } }
    SwitchRow("Wake-first (armed start)", "On: mic dabate hi ULTRON sirf wake phrase ka intezar karta hai; jab tak 'Hey/Hello/Wake up ULTRON' na bolo kuch bhi upload nahi hota. Off: purana tareeka (seedha sunna shuru).", settings.wakeFirst, enabled = settings.wakePersonal) { v -> update { it.copy(wakeFirst = v) } }
    SliderRow("Sensitivity (zyada = jaldi trigger, zyada galat trigger)", settings.wakeSensitivity, 0.6f..1.8f) { v -> update { it.copy(wakeSensitivity = v) } }
}
