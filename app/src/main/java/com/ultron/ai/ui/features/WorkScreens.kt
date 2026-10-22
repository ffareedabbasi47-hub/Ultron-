package com.ultron.ai.ui.features

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ultron.ai.PendingPrompt
import com.ultron.ai.data.db.MarketEntity
import com.ultron.ai.data.db.WorkDocEntity
import com.ultron.ai.data.db.escapeLike
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.Routes
import com.ultron.ai.ui.components.ConfirmDialog
import com.ultron.ai.ui.components.EmptyState
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.LabeledField
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.StatusPill
import com.ultron.ai.ui.components.shareText
import com.ultron.ai.ui.theme.UltronColors
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

enum class WorkKind(
    val wire: String, val title: String, val emptyTitle: String, val emptyBody: String,
    val titleLabel: String, val bodyLabel: String, val aiLabel: String, val aiPrompt: String,
) {
    DOC("doc", "Documents", "Koi document nahi", "Plus dabao aur likho, ya AI se draft banwao. Sab kuch sirf tumhare phone par rehta hai.", "Title", "Document ka text", "AI se draft", "Mere liye ek document draft karo. Topic: "),
    CODE("code", "Website / Coding", "Koi snippet nahi", "HTML/CSS/JS snippet save karo. HTML ka preview locked-down browser mein dikhta hai (internet band).", "Naam", "Code (HTML ho to Preview dabao)", "AI se code", "Mere liye code likho (agar website ho to ek hi self-contained HTML file). Kaam: "),
    POST("post", "Marketing", "Koi content draft nahi", "Post/ad/caption ke draft likho aur planned date set karo. Posting khud karni hoti hai — ULTRON kisi social account se connected nahi hai.", "Campaign / post ka naam", "Content (caption, copy, hashtags)", "AI se content", "Mere business ke liye marketing content likho. Details: "),
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun WorkScreen(kind: WorkKind, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val dao = c.db.workDocs()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val items by remember(query, kind) { if (query.isBlank()) dao.observe(kind.wire) else dao.search(kind.wire, escapeLike(query.trim())) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<WorkDocEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<WorkDocEntity?>(null) }
    var preview by remember { mutableStateOf<WorkDocEntity?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader(kind.title, onBack) {
            IconButton(onClick = {
                c.pendingPrompt.value = PendingPrompt(kind.aiPrompt, autoSend = false)
                onNavigate(Routes.chat())
            }) { Icon(Icons.Filled.AutoAwesome, kind.aiLabel) }
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Naya") }
        }
        LabeledField(query, { query = it }, "Search", Modifier.padding(horizontal = 16.dp))
        if (items.isEmpty()) {
            EmptyState(if (query.isBlank()) kind.emptyTitle else "Kuch nahi mila", if (query.isBlank()) kind.emptyBody else "Dusra shabd try karo.")
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { e ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { editing = e }) {
                    Row(Modifier.padding(14.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title.ifBlank { "(bina naam)" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fmt.format(Date(e.updatedAt)), style = MaterialTheme.typography.labelSmall, color = UltronColors.Cyan)
                            if (kind == WorkKind.POST && e.plannedAt > 0) Text("Planned: ${fmt.format(Date(e.plannedAt))}", style = MaterialTheme.typography.labelSmall, color = UltronColors.Gold)
                            Text(e.body, maxLines = 3, overflow = TextOverflow.Ellipsis, color = UltronColors.TextLo, modifier = Modifier.padding(top = 4.dp))
                        }
                        Column {
                            if (kind == WorkKind.CODE && looksLikeHtml(e.body)) TextButton(onClick = { preview = e }) { Text("Preview") }
                            Row {
                                IconButton(onClick = { shareText(context, e.title.ifBlank { kind.title }, e.body) }) { Icon(Icons.Filled.IosShare, "Share") }
                                IconButton(onClick = { deleteOne = e }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
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
        var planned by remember(base?.id) { mutableStateOf(base?.plannedAt ?: 0L) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (base == null) "Naya" else "Edit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(title, { title = it }, kind.titleLabel)
                    LabeledField(body, { body = it }, kind.bodyLabel, singleLine = false, minLines = 6)
                    if (kind == WorkKind.POST) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(if (planned > 0) "Planned: ${fmt.format(Date(planned))}" else "Date set nahi", Modifier.weight(1f), color = UltronColors.TextLo)
                            TextButton(onClick = { pickDateTime(context, planned) { planned = it } }) { Text("Date/time") }
                            if (planned > 0) TextButton(onClick = { planned = 0 }) { Text("Hatao") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank() || body.isNotBlank(), onClick = {
                    val now = System.currentTimeMillis()
                    scope.launch {
                        if (base == null) dao.insert(WorkDocEntity(kind = kind.wire, title = title.trim(), body = body, createdAt = now, updatedAt = now, plannedAt = planned))
                        else dao.update(base.copy(title = title.trim(), body = body, updatedAt = now, plannedAt = planned))
                    }
                    creating = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } },
        )
    }
    deleteOne?.let { e -> ConfirmDialog("Delete karein?", "Ye hamesha ke liye hat jayega.", "Delete", { scope.launch { dao.delete(e) }; deleteOne = null }, { deleteOne = null }) }
    preview?.let { e ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Preview: ${e.title}") },
            text = { HtmlPreview(e.body) },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Band karo") } },
        )
    }
}

private fun looksLikeHtml(s: String) = s.contains("<html", true) || s.contains("<body", true) || s.contains("<div", true) || s.contains("<h1", true)

private fun pickDateTime(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { if (current > 0) timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            cal.set(y, m, d, h, min, 0); onPicked(cal.timeInMillis)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

/** Locked-down preview: no network, no file/content access, no JS bridges. Scripts run only inside the page itself. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreview(html: String) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(380.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.setGeolocationEnabled(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                        if (request?.url?.scheme == "data" || request?.url?.toString() == "about:blank") null
                        else WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

// ---------------------------------------------------------------- Markets

@Composable
fun MarketsScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val dao = c.db.markets()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val rows by dao.observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<MarketEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<MarketEntity?>(null) }

    val invested = rows.sumOf { it.quantity * it.buyPrice }
    val current = rows.sumOf { it.quantity * it.lastPrice }
    val pl = current - invested

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Markets", onBack) {
            IconButton(onClick = {
                if (rows.isNotEmpty()) shareText(context, "Watchlist", rows.joinToString("\n") { "${it.symbol}: ${it.quantity} @ ${it.buyPrice} (last ${it.lastPrice})" })
            }) { Icon(Icons.Filled.IosShare, "Export") }
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Naya") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Live prices available nahi — prices tum khud daalte ho", UltronColors.Gold)
            if (rows.isNotEmpty()) GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Invested", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo)
                        Text(money(invested), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Current (tumhare daale price se)", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo)
                        Text(money(current), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("P/L", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo)
                        Text(money(pl), style = MaterialTheme.typography.titleMedium, color = if (pl >= 0) UltronColors.Green else UltronColors.Red)
                    }
                }
            }
        }
        if (rows.isEmpty()) EmptyState("Watchlist khaali hai", "Plus dabao: symbol, quantity, buy price aur last price daalo. Ye sirf hisaab-kitaab hai, financial advice nahi.")
        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it.id }) { m ->
                val mpl = (m.lastPrice - m.buyPrice) * m.quantity
                GlassCard(Modifier.fillMaxWidth(), onClick = { editing = m }) {
                    Row(Modifier.padding(14.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(m.symbol, style = MaterialTheme.typography.titleMedium)
                            if (m.name.isNotBlank()) Text(m.name, color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium)
                            Text("${trim(m.quantity)} × ${money(m.buyPrice)} → ${money(m.lastPrice)}", style = MaterialTheme.typography.bodyMedium)
                            Text("P/L ${money(mpl)}", color = if (mpl >= 0) UltronColors.Green else UltronColors.Red, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { deleteOne = m }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val base = editing
        var symbol by remember(base?.id) { mutableStateOf(base?.symbol ?: "") }
        var name by remember(base?.id) { mutableStateOf(base?.name ?: "") }
        var qty by remember(base?.id) { mutableStateOf(base?.quantity?.let(::trim) ?: "") }
        var buy by remember(base?.id) { mutableStateOf(base?.buyPrice?.let(::trim) ?: "") }
        var last by remember(base?.id) { mutableStateOf(base?.lastPrice?.let(::trim) ?: "") }
        var note by remember(base?.id) { mutableStateOf(base?.note ?: "") }
        val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (base == null) "Naya entry" else "Edit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(symbol, { symbol = it.take(20) }, "Symbol (jaise TCS)")
                    LabeledField(name, { name = it }, "Naam (optional)")
                    androidx.compose.material3.OutlinedTextField(qty, { qty = it }, label = { Text("Quantity") }, singleLine = true, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
                    androidx.compose.material3.OutlinedTextField(buy, { buy = it }, label = { Text("Buy price") }, singleLine = true, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
                    androidx.compose.material3.OutlinedTextField(last, { last = it }, label = { Text("Last price (khud daalo)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
                    LabeledField(note, { note = it }, "Note")
                }
            },
            confirmButton = {
                val q = qty.toDoubleOrNull(); val b = buy.toDoubleOrNull(); val l = last.toDoubleOrNull()
                TextButton(enabled = symbol.isNotBlank() && q != null && b != null && l != null && q >= 0 && b >= 0 && l >= 0, onClick = {
                    scope.launch {
                        val e = MarketEntity(base?.id ?: 0, symbol.trim().uppercase(Locale.ROOT), name.trim(), q!!, b!!, l!!, note.trim(), System.currentTimeMillis())
                        if (base == null) dao.insert(e) else dao.update(e)
                    }
                    creating = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } },
        )
    }
    deleteOne?.let { m -> ConfirmDialog("Delete karein?", "${m.symbol} hat jayega.", "Delete", { scope.launch { dao.delete(m) }; deleteOne = null }, { deleteOne = null }) }
}

private fun money(v: Double) = String.format(Locale.US, "%,.2f", v)
private fun trim(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
