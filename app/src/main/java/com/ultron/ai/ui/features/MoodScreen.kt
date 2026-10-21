package com.ultron.ai.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ultron.ai.data.db.MoodEntity
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.components.ConfirmDialog
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.LabeledField
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.components.shareText
import com.ultron.ai.ui.home.moodLabel
import com.ultron.ai.ui.theme.UltronColors
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private val faces = listOf("😞", "🙁", "😐", "🙂", "😄")

@Composable
fun MoodScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val dao = c.db.mood()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val entries by dao.observeAll().collectAsState(initial = emptyList())
    var level by remember { mutableIntStateOf(3) }
    var note by remember { mutableStateOf("") }
    var confirmAll by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Mood", onBack) {
            IconButton(onClick = {
                if (entries.isNotEmpty()) shareText(context, "Mood export", entries.reversed().joinToString("\n") { "${fmt.format(Date(it.createdAt))} — ${moodLabel(it.level)} ${it.note}" })
            }) { Icon(Icons.Filled.IosShare, "Export") }
            IconButton(onClick = { confirmAll = true }, enabled = entries.isNotEmpty()) { Icon(Icons.Filled.DeleteSweep, "Sab delete karo") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Abhi kaisa lag raha hai?", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            faces.forEachIndexed { i, f ->
                                val sel = level == i + 1
                                Box(
                                    Modifier.height(52.dp).weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(16.dp))
                                        .background(if (sel) UltronColors.Violet.copy(alpha = 0.5f) else UltronColors.GlassBottom),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    IconButton(onClick = { level = i + 1 }) { Text(f, style = MaterialTheme.typography.titleMedium) }
                                }
                            }
                        }
                        Text(moodLabel(level), color = UltronColors.Cyan)
                        LabeledField(note, { note = it }, "Note (optional)", singleLine = false)
                        Button(onClick = {
                            scope.launch { dao.insert(MoodEntity(level = level, note = note.trim(), createdAt = System.currentTimeMillis())) }
                            note = ""
                        }) { Text("Save karo") }
                        Text("Ye sirf aapki self-reported entry hai. ULTRON mood ya mental health ka diagnosis nahi karta.", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)
                    }
                }
            }
            if (entries.size >= 2) {
                item {
                    SectionLabel("Pichhli entries ka trend")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().height(90.dp).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                            entries.take(14).reversed().forEach { e ->
                                Box(Modifier.weight(1f).height((12 + e.level * 12).dp).clip(RoundedCornerShape(4.dp)).background(UltronColors.Cyan.copy(alpha = 0.4f + e.level * 0.12f)))
                            }
                        }
                    }
                }
            }
            item { SectionLabel("History") }
            if (entries.isEmpty()) item { Text("Abhi koi entry nahi.", color = UltronColors.TextLo, modifier = Modifier.padding(8.dp)) }
            items(entries, key = { it.id }) { e ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(faces[(e.level - 1).coerceIn(0, 4)], style = MaterialTheme.typography.headlineMedium)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(moodLabel(e.level), style = MaterialTheme.typography.titleMedium)
                            Text(fmt.format(Date(e.createdAt)), style = MaterialTheme.typography.labelSmall, color = UltronColors.Cyan)
                            if (e.note.isNotBlank()) Text(e.note, color = UltronColors.TextLo)
                        }
                        IconButton(onClick = { scope.launch { dao.delete(e) } }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
        }
    }
    if (confirmAll) ConfirmDialog("Saari mood entries delete karein?", "Ye wapas nahi aayengi.", "Sab delete karo", { scope.launch { dao.deleteAll() }; confirmAll = false }, { confirmAll = false })
}
