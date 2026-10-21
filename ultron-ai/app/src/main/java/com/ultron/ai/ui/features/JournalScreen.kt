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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ultron.ai.data.db.JournalEntity
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun JournalScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val dao = c.db.journal()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val entries by remember(query) { if (query.isBlank()) dao.observeAll() else dao.search(escapeLike(query.trim())) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<JournalEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<JournalEntity?>(null) }
    var deleteAll by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Journal", onBack) {
            IconButton(onClick = {
                scope.launch {
                    val all = dao.all()
                    if (all.isNotEmpty()) shareText(context, "Journal export", all.joinToString("\n\n") { "${fmt.format(Date(it.createdAt))}\n${it.title}\n${it.body}" })
                }
            }) { Icon(Icons.Filled.IosShare, "Export") }
            IconButton(onClick = { deleteAll = true }, enabled = entries.isNotEmpty()) { Icon(Icons.Filled.DeleteSweep, "Saari entries delete karo") }
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Nayi entry") }
        }
        LabeledField(query, { query = it }, "Search", Modifier.padding(horizontal = 16.dp))
        if (entries.isEmpty()) {
            EmptyState(if (query.isBlank()) "Journal khaali hai" else "Kuch nahi mila", if (query.isBlank()) "Plus dabao aur pehli entry likho. Ye sirf aapke phone par encrypted rehti hai." else "Dusra shabd try karo.")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries, key = { it.id }) { e ->
                    GlassCard(Modifier.fillMaxWidth(), onClick = { editing = e }) {
                        Row(Modifier.padding(14.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title.ifBlank { "(bina title)" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fmt.format(Date(e.updatedAt)), style = MaterialTheme.typography.labelSmall, color = UltronColors.Cyan)
                                Text(e.body, maxLines = 3, overflow = TextOverflow.Ellipsis, color = UltronColors.TextLo, modifier = Modifier.padding(top = 4.dp))
                            }
                            IconButton(onClick = { deleteOne = e }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val base = editing
        var title by remember(base?.id) { mutableStateOf(base?.title ?: "") }
        var body by remember(base?.id) { mutableStateOf(base?.body ?: "") }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (base == null) "Nayi entry" else "Entry edit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(title, { title = it }, "Title")
                    LabeledField(body, { body = it }, "Aaj kya hua?", singleLine = false, minLines = 5)
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank() || body.isNotBlank(), onClick = {
                    val now = System.currentTimeMillis()
                    scope.launch {
                        if (base == null) dao.insert(JournalEntity(title = title.trim(), body = body.trim(), createdAt = now, updatedAt = now))
                        else dao.update(base.copy(title = title.trim(), body = body.trim(), updatedAt = now))
                    }
                    creating = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } },
        )
    }
    deleteOne?.let { e ->
        ConfirmDialog("Entry delete karein?", "Ye entry hamesha ke liye hat jayegi.", "Delete", { scope.launch { dao.delete(e) }; deleteOne = null }, { deleteOne = null })
    }
    if (deleteAll) {
        ConfirmDialog("Saari journal entries delete karein?", "Ye wapas nahi aayengi. Pehle export karna chaho to cancel karke export dabao.", "Sab delete karo", { scope.launch { dao.deleteAll() }; deleteAll = false }, { deleteAll = false })
    }
}
