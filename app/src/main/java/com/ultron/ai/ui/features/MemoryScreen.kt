package com.ultron.ai.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.ultron.ai.data.db.MemoryEntity
import com.ultron.ai.data.db.escapeLike
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.components.ConfirmDialog
import com.ultron.ai.ui.components.EmptyState
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.LabeledField
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.shareText
import com.ultron.ai.ui.theme.UltronColors
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Yaadein: user-approved long-term memory. Nothing is saved silently; everything is editable. */
@Composable
fun MemoryScreen(onBack: (() -> Unit)?) {
    val c = LocalContainer.current
    val dao = c.db.memory()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val items by remember(query) { if (query.isBlank()) dao.observeAll() else dao.search(escapeLike(query.trim())) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteAll by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Yaadein", onBack) {
            IconButton(onClick = {
                scope.launch { val all = dao.all(); if (all.isNotEmpty()) shareText(context, "Yaadein export", all.joinToString("\n") { "• ${it.text}" }) }
            }) { Icon(Icons.Filled.IosShare, "Export") }
            IconButton(onClick = { deleteAll = true }, enabled = items.isNotEmpty()) { Icon(Icons.Filled.DeleteSweep, "Sab delete karo") }
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Nayi yaad") }
        }
        Text(
            "Yahan sirf wahi hai jo aapne khud save karwaya (\"yaad rakh: …\" bolke ya plus se). Chat history alag hai. Passwords/OTP yahan mat rakho.",
            color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LabeledField(query, { query = it }, "Search", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (items.isEmpty()) {
            EmptyState("Abhi kuch save nahi hai", "Bolo: \"yaad rakh: meri exam 5 March ko hai\".")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { m ->
                    GlassCard(Modifier.fillMaxWidth(), onClick = { editing = m }) {
                        Row(Modifier.padding(14.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                                Text("${fmt.format(Date(m.updatedAt))} · ${m.source}", style = MaterialTheme.typography.labelSmall, color = UltronColors.Cyan)
                            }
                            IconButton(onClick = { scope.launch { dao.delete(m) } }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val base = editing
        var text by remember(base?.id) { mutableStateOf(base?.text ?: "") }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (base == null) "Nayi yaad" else "Yaad edit karo") },
            text = { LabeledField(text, { text = it.take(500) }, "Kya yaad rakhna hai?", singleLine = false, minLines = 3) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val now = System.currentTimeMillis()
                    scope.launch {
                        if (base == null) dao.insert(MemoryEntity(text = text.trim(), source = "manual", createdAt = now, updatedAt = now))
                        else dao.update(base.copy(text = text.trim(), updatedAt = now))
                    }
                    creating = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } },
        )
    }
    if (deleteAll) ConfirmDialog("Saari yaadein delete karein?", "Ye wapas nahi aayengi.", "Sab delete karo", { scope.launch { dao.deleteAll() }; deleteAll = false }, { deleteAll = false })
}
