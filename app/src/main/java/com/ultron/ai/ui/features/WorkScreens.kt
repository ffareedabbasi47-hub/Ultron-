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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
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
    POST("post", "Content", "Koi content draft nahi", "Post/ad/caption ke draft likho aur planned date set karo. Posting khud karni hoti hai — ULTRON kisi social account se connected nahi hai.", "Campaign / post ka naam", "Content (caption, copy, hashtags)", "AI se content", "Mere business ke liye marketing content likho. Details: "),
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
    var importNote by remember { mutableStateOf<String?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = readSmallText(context, uri)
            if (text == null) importNote = "File padh nahi paya (sirf text file, 1 MB tak)."
            else {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty().ifBlank { "Import" }
                val now = System.currentTimeMillis()
                dao.insert(WorkDocEntity(kind = kind.wire, title = name, body = text, createdAt = now, updatedAt = now))
                importNote = "\"$name\" import ho gaya."
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader(kind.title, onBack) {
            IconButton(onClick = {
                c.pendingPrompt.value = PendingPrompt(kind.aiPrompt, autoSend = false)
                onNavigate(Routes.chat())
            }) { Icon(Icons.Filled.AutoAwesome, kind.aiLabel) }
            if (kind != WorkKind.POST) IconButton(onClick = { importer.launch(arrayOf("text/*", "application/json", "application/xml", "application/javascript")) }) { Icon(Icons.Filled.FileOpen, "File import karo") }
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Naya") }
        }
        importNote?.let { Text(it, color = UltronColors.Gold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp)) }
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


/** Reads a user-picked text file (max 1 MB). Returns null when unreadable/too large/binary. */
private suspend fun readSmallText(context: android.content.Context, uri: android.net.Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() <= 1_000_000) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            val bytes = out.toByteArray()
            if (bytes.size > 1_000_000 || bytes.any { it == 0.toByte() }) null else String(bytes, Charsets.UTF_8)
        }
    } catch (e: Exception) { null }
}

// ---------------------------------------------------------------- Marketing (Business / Content / Calendar)

@Composable
fun MarketingScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val dao = c.db.workDocs()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val profiles by dao.observe("biz").collectAsState(initial = emptyList())
    val posts by dao.observe("post").collectAsState(initial = emptyList())
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val dayFmt = remember { DateFormat.getDateInstance(DateFormat.FULL) }
    var editing by remember { mutableStateOf<WorkDocEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<WorkDocEntity?>(null) }
    var editBiz by remember { mutableStateOf(false) }
    val profile = profiles.firstOrNull()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Marketing", onBack) {
            IconButton(onClick = {
                c.pendingPrompt.value = PendingPrompt(
                    "Mere business ke liye marketing content likho.\n" + (profile?.body?.let { "Business details:\n$it\n" } ?: "") + "Kaam: ",
                    autoSend = false,
                )
                onNavigate(Routes.chat())
            }) { Icon(Icons.Filled.AutoAwesome, "AI se content") }
            if (tab != 0) IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Naya") }
        }
        androidx.compose.material3.TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
            listOf("Business", "Content", "Calendar").forEachIndexed { i, t ->
                androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (profile == null) {
                    EmptyState("Business profile nahi hai", "Apne business ka naam, kya bechte ho, customer kaun hai aur tone kaisi ho — ye likho. AI content isi se banega. Koi social account connected nahi hai.")
                    androidx.compose.material3.Button(onClick = { editBiz = true }) { Text("Business profile banao") }
                } else GlassCard(Modifier.fillMaxWidth(), onClick = { editBiz = true }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(profile.title.ifBlank { "Mera business" }, style = MaterialTheme.typography.titleMedium)
                        Text(profile.body, color = UltronColors.TextLo, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                StatusPill("Publishing connectors available nahi — posting tum khud karte ho", UltronColors.Gold)
            }
            1 -> {
                if (posts.isEmpty()) EmptyState("Koi content draft nahi", "Plus dabao: platform, caption/copy aur status set karo, ya AI se draft banwao.")
                else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(posts, key = { it.id }) { e -> PostCard(e, fmt, onClick = { editing = e }, onShare = { shareText(context, e.title, e.body) }, onDelete = { deleteOne = e }) }
                }
            }
            else -> {
                val planned = posts.filter { it.plannedAt > 0 }.sortedBy { it.plannedAt }
                if (planned.isEmpty()) EmptyState("Calendar khaali hai", "Content draft mein date/time set karo to yahan agenda dikhega. Timezone: ${java.util.TimeZone.getDefault().id}.")
                else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val groups = planned.groupBy { dayFmt.format(Date(it.plannedAt)) }
                    groups.forEach { (day, list) ->
                        item(key = "d-$day") { Text(day, style = MaterialTheme.typography.labelMedium, color = UltronColors.Cyan, modifier = Modifier.padding(top = 6.dp)) }
                        items(list, key = { it.id }) { e -> PostCard(e, fmt, onClick = { editing = e }, onShare = { shareText(context, e.title, e.body) }, onDelete = { deleteOne = e }) }
                    }
                }
            }
        }
    }

    if (editBiz) {
        var name by remember(profile?.id) { mutableStateOf(profile?.title ?: "") }
        var details by remember(profile?.id) { mutableStateOf(profile?.body ?: "") }
        AlertDialog(
            onDismissRequest = { editBiz = false },
            title = { Text("Business profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(name, { name = it }, "Business ka naam")
                    LabeledField(details, { details = it.take(1000) }, "Kya bechte ho, customer kaun, tone", singleLine = false, minLines = 5)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val now = System.currentTimeMillis()
                    scope.launch {
                        if (profile == null) dao.insert(WorkDocEntity(kind = "biz", title = name.trim(), body = details, createdAt = now, updatedAt = now))
                        else dao.update(profile.copy(title = name.trim(), body = details, updatedAt = now))
                    }
                    editBiz = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editBiz = false }) { Text("Cancel") } },
        )
    }
    if (creating || editing != null) {
        val base = editing
        var title by remember(base?.id) { mutableStateOf(base?.title ?: "") }
        var body by remember(base?.id) { mutableStateOf(base?.body ?: "") }
        var platform by remember(base?.id) { mutableStateOf(base?.tag ?: "") }
        var status by remember(base?.id) { mutableStateOf(base?.status?.ifBlank { "draft" } ?: "draft") }
        var planned by remember(base?.id) { mutableStateOf(base?.plannedAt ?: 0L) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (base == null) "Naya content" else "Content edit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(title, { title = it }, "Post / campaign ka naam")
                    LabeledField(platform, { platform = it.take(30) }, "Platform (Instagram, WhatsApp…)")
                    LabeledField(body, { body = it }, "Caption / copy", singleLine = false, minLines = 5)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("draft" to "Draft", "ready" to "Ready", "posted" to "Posted").forEach { (k, l) ->
                            androidx.compose.material3.FilterChip(selected = status == k, onClick = { status = k }, label = { Text(l) })
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (planned > 0) "Planned: ${fmt.format(Date(planned))}" else "Date set nahi", Modifier.weight(1f), color = UltronColors.TextLo)
                        TextButton(onClick = { pickDateTime(context, planned) { planned = it } }) { Text("Date/time") }
                        if (planned > 0) TextButton(onClick = { planned = 0 }) { Text("Hatao") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank() || body.isNotBlank(), onClick = {
                    val now = System.currentTimeMillis()
                    scope.launch {
                        if (base == null) dao.insert(WorkDocEntity(kind = "post", title = title.trim(), body = body, createdAt = now, updatedAt = now, plannedAt = planned, tag = platform.trim(), status = status))
                        else dao.update(base.copy(title = title.trim(), body = body, updatedAt = now, plannedAt = planned, tag = platform.trim(), status = status))
                    }
                    creating = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } },
        )
    }
    deleteOne?.let { e -> ConfirmDialog("Ye content delete karein?", "\"${e.title.ifBlank { "(bina naam)" }}\" hat jayega.", "Delete", { scope.launch { dao.delete(e) }; deleteOne = null }, { deleteOne = null }) }
}

@Composable
private fun PostCard(e: WorkDocEntity, fmt: DateFormat, onClick: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(e.title.ifBlank { "(bina naam)" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(e.tag, e.status.ifBlank { "draft" }).filter { it.isNotBlank() }.joinToString(" · ") + " · " + fmt.format(Date(e.updatedAt)), style = MaterialTheme.typography.labelSmall, color = UltronColors.Cyan)
                if (e.plannedAt > 0) Text("Planned: ${fmt.format(Date(e.plannedAt))}", style = MaterialTheme.typography.labelSmall, color = UltronColors.Gold)
                Text(e.body, maxLines = 3, overflow = TextOverflow.Ellipsis, color = UltronColors.TextLo, modifier = Modifier.padding(top = 4.dp))
            }
            Column {
                IconButton(onClick = onShare) { Icon(Icons.Filled.IosShare, "Share") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
    }
}

// ---------------------------------------------------------------- Markets (Watchlist / Portfolio / Analysis)

@Composable
fun MarketsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val c = LocalContainer.current
    val dao = c.db.markets()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val rows by dao.observeAll().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<MarketEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<MarketEntity?>(null) }
    var watchInput by remember { mutableStateOf("") }
    var exchange by remember { mutableStateOf("NSE") }
    var watchError by remember { mutableStateOf<String?>(null) }

    val watch = rows.filter { it.type == "watch" }
    val positions = rows.filter { it.type == "position" }
    val invested = positions.sumOf { it.quantity * it.buyPrice }
    val current = positions.sumOf { it.quantity * it.lastPrice }
    val pl = current - invested

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Markets", onBack) {
            IconButton(onClick = {
                if (rows.isNotEmpty()) shareText(context, "Watchlist/Portfolio", rows.joinToString("\n") { "${it.type} ${it.exchange}:${it.symbol} ${it.quantity} @ ${it.buyPrice} (last ${it.lastPrice})" })
            }) { Icon(Icons.Filled.IosShare, "Export") }
            if (tab == 1) IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Position add karo") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Live price/index feed available nahi — source: tumhare daale hue prices", UltronColors.Gold)
            androidx.compose.material3.TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                listOf("Watchlist", "Portfolio", "Analysis").forEachIndexed { i, t -> androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
        }
        when (tab) {
            0 -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NSE", "BSE").forEach { ex -> androidx.compose.material3.FilterChip(selected = exchange == ex, onClick = { exchange = ex }, label = { Text(ex) }) }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        watchInput, { watchInput = it.take(20); watchError = null }, label = { Text("Company / ticker (jaise TCS)") },
                        singleLine = true, isError = watchError != null, supportingText = { watchError?.let { Text(it) } }, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val sym = watchInput.trim().uppercase(Locale.ROOT)
                        when {
                            sym.isEmpty() -> watchError = "Symbol khaali hai."
                            !Regex("[A-Z0-9&.\\-]{1,20}").matches(sym) -> watchError = "Sirf letters/numbers (& . -) allowed hain."
                            watch.any { it.symbol == sym && it.exchange == exchange } -> watchError = "Ye pehle se list mein hai."
                            else -> { scope.launch { dao.insert(MarketEntity(symbol = sym, name = "", quantity = 0.0, buyPrice = 0.0, lastPrice = 0.0, note = "", updatedAt = System.currentTimeMillis(), exchange = exchange, type = "watch")) }; watchInput = "" }
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Symbol add karo") }
                }
                if (watch.isEmpty()) EmptyState("Watchlist khaali hai", "Upar symbol daalo aur arrow dabao. Price tab dikhega jab koi asli market feed connect hogi — abhi nahi hai.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(watch, key = { it.id }) { m ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.symbol, style = MaterialTheme.typography.titleMedium)
                                    Text("${m.exchange} · price: data nahi", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo)
                                }
                                IconButton(onClick = { deleteOne = m }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (positions.isNotEmpty()) GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text("Invested", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo); Text(money(invested), style = MaterialTheme.typography.titleMedium) }
                        Column(Modifier.weight(1f)) { Text("Current (estimate)", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo); Text(money(current), style = MaterialTheme.typography.titleMedium) }
                        Column(Modifier.weight(1f)) { Text("P/L", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo); Text(money(pl), style = MaterialTheme.typography.titleMedium, color = if (pl >= 0) UltronColors.Green else UltronColors.Red) }
                    }
                }
                if (positions.isEmpty()) EmptyState("Portfolio khaali hai", "Plus dabao: symbol, quantity, buy price aur last price daalo. Values sirf tumhare daale hue numbers se nikalti hain.")
                else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(positions, key = { it.id }) { m ->
                        val mpl = (m.lastPrice - m.buyPrice) * m.quantity
                        GlassCard(Modifier.fillMaxWidth(), onClick = { editing = m }) {
                            Row(Modifier.padding(14.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("${m.exchange}: ${m.symbol}", style = MaterialTheme.typography.titleMedium)
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
            else -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (positions.isEmpty()) EmptyState("Analysis ke liye positions chahiye", "Portfolio tab mein kam se kam ek position daalo. Analysis sirf tumhare daale hue numbers par hoti hai.")
                else {
                    val best = positions.maxByOrNull { (it.lastPrice - it.buyPrice) * it.quantity }
                    val worst = positions.minByOrNull { (it.lastPrice - it.buyPrice) * it.quantity }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Hisaab (tumhare numbers se)", style = MaterialTheme.typography.titleMedium)
                            Text("Positions: ${positions.size}")
                            Text("Return: ${if (invested > 0) String.format(Locale.US, "%.2f%%", pl / invested * 100) else "—"}")
                            best?.let { Text("Sabse accha: ${it.symbol} (${money((it.lastPrice - it.buyPrice) * it.quantity)})") }
                            worst?.let { Text("Sabse kamzor: ${it.symbol} (${money((it.lastPrice - it.buyPrice) * it.quantity)})") }
                        }
                    }
                    Text("Allocation", style = MaterialTheme.typography.labelMedium, color = UltronColors.Cyan)
                    positions.sortedByDescending { it.quantity * it.lastPrice }.forEach { m ->
                        val share = if (current > 0) m.quantity * m.lastPrice / current * 100 else 0.0
                        Text("${m.symbol}: ${String.format(Locale.US, "%.1f%%", share)}")
                    }
                }
                Text("Ye sirf calculator hai, financial advice nahi. Market feed connect nahi hai, isliye koi prediction ya live commentary nahi.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedButton(onClick = {
                    c.pendingPrompt.value = PendingPrompt("Meri portfolio ke ye numbers hain, sirf hisaab samjhao (advice nahi):\n" + positions.joinToString("\n") { "${it.symbol}: ${trim(it.quantity)} @ ${it.buyPrice}, last ${it.lastPrice}" }, autoSend = false)
                    onNavigate(Routes.chat())
                }, enabled = positions.isNotEmpty()) { Text("AI se samjho (chat mein)") }
            }
        }
    }

    if (creating || editing != null) {
        val base = editing
        var symbol by remember(base?.id) { mutableStateOf(base?.symbol ?: "") }
        var name by remember(base?.id) { mutableStateOf(base?.name ?: "") }
        var ex by remember(base?.id) { mutableStateOf(base?.exchange ?: "NSE") }
        var qty by remember(base?.id) { mutableStateOf(base?.quantity?.let(::trim) ?: "") }
        var buy by remember(base?.id) { mutableStateOf(base?.buyPrice?.let(::trim) ?: "") }
        var last by remember(base?.id) { mutableStateOf(base?.lastPrice?.let(::trim) ?: "") }
        var note by remember(base?.id) { mutableStateOf(base?.note ?: "") }
        val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (base == null) "Nayi position" else "Edit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("NSE", "BSE").forEach { e -> androidx.compose.material3.FilterChip(selected = ex == e, onClick = { ex = e }, label = { Text(e) }) } }
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
                TextButton(enabled = symbol.isNotBlank() && q != null && b != null && l != null && q > 0 && b >= 0 && l >= 0, onClick = {
                    scope.launch {
                        val e = MarketEntity(base?.id ?: 0, symbol.trim().uppercase(Locale.ROOT), name.trim(), q!!, b!!, l!!, note.trim(), System.currentTimeMillis(), ex, "position")
                        if (base == null) dao.insert(e) else dao.update(e)
                    }
                    creating = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } },
        )
    }
    deleteOne?.let { m -> ConfirmDialog("Delete karein?", "${m.symbol} (${m.exchange}) hat jayega.", "Delete", { scope.launch { dao.delete(m) }; deleteOne = null }, { deleteOne = null }) }
}

private fun money(v: Double) = String.format(Locale.US, "%,.2f", v)
private fun trim(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
