package com.ultron.ai.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ultron.ai.data.ai.ChatMode
import com.ultron.ai.data.ai.ErrorKind
import com.ultron.ai.data.db.MessageEntity
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.Routes
import com.ultron.ai.ui.components.ComposerBar
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.glass
import com.ultron.ai.ui.containerViewModel
import com.ultron.ai.ui.theme.UltronColors
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(study: Boolean, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val mode = if (study) ChatMode.STUDY else ChatMode.CHAT
    val vm = containerViewModel(key = "chat-${mode.wire}") { ChatViewModel(it, mode) }
    val c = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val messages by vm.messages.collectAsState()
    val turn by vm.turn.collectAsState()
    val convs by vm.conversations.collectAsState()
    val currentId by vm.conversationId.collectAsState()
    val ttsState by c.tts.state.collectAsState()
    val pending by c.pendingPrompt.collectAsState()
    var draft by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    // Prompt handed over from Home / Scan.
    LaunchedEffect(pending, currentId) {
        val p = pending ?: return@LaunchedEffect
        if (currentId == null || p.study != study) return@LaunchedEffect
        c.pendingPrompt.value = null
        if (p.autoSend) vm.send(p.text) else draft = p.text
    }
    LaunchedEffect(messages.size, turn.streaming) {
        val n = messages.size + (if (turn.streaming.isNotEmpty() || turn.busy) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        ScreenHeader(if (study) "Padhai" else "Chat", onBack) {
            if (ttsState.speaking) {
                IconButton(onClick = { vm.stopSpeaking() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.VolumeOff, contentDescription = "Bolna band karo", tint = UltronColors.Red)
                }
            }
            IconButton(onClick = { currentId?.let { confirmDelete = it } }, enabled = messages.isNotEmpty(), modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.DeleteSweep, contentDescription = "Ye chat saaf karo") }
            IconButton(onClick = { vm.newChat() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Add, contentDescription = "Nayi chat") }
            IconButton(onClick = { showHistory = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.History, contentDescription = "History") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && !turn.busy) {
                EmptyChat(study)
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(messages, key = { it.id }) { m -> Bubble(m.role == "user", m.text, cancelled = m.status == MessageEntity.STATUS_CANCELLED) }
                    if (turn.busy || turn.streaming.isNotEmpty()) {
                        item(key = "stream") {
                            Bubble(false, turn.streaming.ifEmpty { "Soch raha hoon…" }, placeholder = turn.streaming.isEmpty())
                        }
                    }
                }
            }
        }

        // Errors / consent
        turn.error?.let { err ->
            ErrorBanner(
                message = err.message,
                primary = if (err.kind?.retryable == true || err.kind == ErrorKind.RATE_LIMITED || err.kind == null) "Dobara try" to { vm.dismiss(); vm.retry() } else null,
                secondary = if (err.kind == ErrorKind.NOT_CONFIGURED || err.kind == ErrorKind.UNAUTHORIZED) "AI Brain" to { vm.dismiss(); onNavigate(Routes.AI) } else null,
                onDismiss = { vm.dismiss() },
            )
        }

        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ComposerBar(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = if (study) "Kya padhna hai?" else "ULTRON se kuch bhi poochho...",
                    onSend = { vm.send(draft); draft = "" },
                    busy = turn.busy,
                    onStop = { vm.cancel() },
                )
            }
            IconButton(onClick = { c.micRequest.value = true; onNavigate(Routes.HOME) }, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(UltronColors.Blue.copy(alpha = 0.25f))) {
                Icon(Icons.Filled.Mic, contentDescription = "Bolo (Home par)")
            }
        }
    }

    if (turn.needsConsent) {
        AlertDialog(
            onDismissRequest = { vm.dismiss() },
            title = { Text("Cloud AI ki ijazat") },
            text = {
                Text(
                    "Jawab ke liye aapka text (aur chat ka recent context) aapke ULTRON backend ko bheja jayega, jo use AI provider ko forward karta hai. " +
                        "Mic ki audio ya screen kabhi nahi bheji jati. Ye ijazat aap Menu → Privacy mein kabhi bhi wapas le sakte ho.",
                )
            },
            confirmButton = { Button(onClick = { vm.grantConsent() }) { Text("Allow karo") } },
            dismissButton = { TextButton(onClick = { vm.dismiss() }) { Text("Abhi nahi") } },
        )
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Text("Chat history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val list = convs.filter { it.mode == mode.wire }
            if (list.isEmpty()) Text("Abhi koi chat nahi.", color = UltronColors.TextLo, modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(list, key = { it.id }) { conv ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.open(conv.id); showHistory = false }.padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            conv.title + if (conv.id == currentId) "  •" else "",
                            Modifier.weight(1f), maxLines = 1,
                            color = if (conv.id == currentId) UltronColors.Cyan else UltronColors.TextHi,
                        )
                        IconButton(onClick = {
                            scope.launch {
                                val text = vm.exportText(conv.id)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Chat share karo"))
                            }
                        }) { Icon(Icons.Filled.IosShare, contentDescription = "Export") }
                        IconButton(onClick = { renameTarget = conv.id to conv.title }) { Icon(Icons.Filled.Edit, contentDescription = "Naam badlo") }
                        IconButton(onClick = { confirmDelete = conv.id }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                }
            }
        }
    }

    renameTarget?.let { (id, title) ->
        var text by remember(id) { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Chat ka naam") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(id, text); renameTarget = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Ye chat delete karein?") },
            text = { Text("Is chat ke saare messages hamesha ke liye hat jayenge.") },
            confirmButton = { TextButton(onClick = { vm.delete(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyChat(study: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (study) "Padhai mode" else "Systems ready.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            if (study) "Koi bhi topic likho. Main step-by-step samjhaunga, example dunga aur practice questions poochunga."
            else "Batao, kya solve karna hai? App kholna, YouTube search, reminder — ye commands seedhe device par chalte hain; baaki sawaal AI se.",
            color = UltronColors.TextLo,
        )
    }
}

@Composable
private fun Bubble(mine: Boolean, text: String, placeholder: Boolean = false, cancelled: Boolean = false) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (mine && !placeholder) IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy karo", tint = UltronColors.TextLo, modifier = Modifier.size(18.dp))
        }
        Box(
            Modifier.widthIn(max = 320.dp)
                .glass(RoundedCornerShape(20.dp), tint = if (mine) UltronColors.Blue.copy(alpha = 0.30f) else TransparentTint)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(
                    text + if (cancelled) "  (beech mein roka gaya)" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (placeholder) UltronColors.TextLo else UltronColors.TextHi,
                )
            }
        }
        if (!mine && !placeholder) IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy karo", tint = UltronColors.TextLo, modifier = Modifier.size(18.dp))
        }
    }
}

private val TransparentTint = androidx.compose.ui.graphics.Color.Transparent

@Composable
private fun ErrorBanner(message: String, primary: Pair<String, () -> Unit>?, secondary: Pair<String, () -> Unit>?, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).glass(RoundedCornerShape(16.dp), tint = UltronColors.Red.copy(alpha = 0.14f)).padding(12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) { Text("Band karo") }
            secondary?.let { (label, act) -> TextButton(onClick = act) { Text(label) } }
            primary?.let { (label, act) -> TextButton(onClick = act) { Text(label) } }
        }
    }
}
