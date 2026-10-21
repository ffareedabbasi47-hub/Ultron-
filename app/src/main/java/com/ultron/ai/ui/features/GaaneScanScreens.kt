package com.ultron.ai.ui.features

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ultron.ai.PendingPrompt
import com.ultron.ai.data.db.MemoryEntity
import com.ultron.ai.domain.ActionValidator
import com.ultron.ai.domain.UltronAction
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.Routes
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.LabeledField
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.theme.UltronColors
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun GaaneScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun run(action: UltronAction) {
        val v = ActionValidator.validate(action)
        if (!v.ok) { result = false to (v.reason ?: "Invalid"); return }
        scope.launch { val r = c.actions.run(action); result = r.success to r.message }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenHeader("Gaane", onBack)
        LabeledField(query, { query = it.take(120) }, "Gaana ya artist ka naam")
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = query.isNotBlank(), onClick = { run(UltronAction.YouTubeSearch(query.trim())) }) { Text("YouTube par dhoondo") }
            OutlinedButton(onClick = { run(UltronAction.OpenApp("YouTube Music")) }) { Text("YouTube Music kholo") }
        }
        result?.let { (ok, msg) ->
            Text(msg, color = if (ok) UltronColors.TextHi else UltronColors.Gold, modifier = Modifier.padding(top = 14.dp))
        }
        Text(
            "Ye official YouTube search/app kholta hai. Gaana sach mein chal raha hai ya nahi, ye ULTRON verify nahi kar sakta, isliye \"playing\" kabhi nahi bolta. Voice se bhi bol sakte ho: \"YouTube pe arijit singh songs chalao\".",
            color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 18.dp),
        )
    }
}

@Composable
fun ScanScreen(onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devanagari by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    fun recognize(uri: Uri) {
        busy = true; status = null
        scope.launch {
            try {
                text = c.ocr.recognize(uri, devanagari).trim()
                if (text.isEmpty()) status = "Image mein koi text nahi mila."
            } catch (e: Exception) {
                status = "Image padh nahi paya. Dusri/saaf photo try karo."
            } finally { busy = false }
        }
    }

    // System camera app: ULTRON itself never holds the CAMERA permission.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingUri
        if (ok && uri != null) recognize(uri) else if (!ok) status = "Photo cancel ho gayi."
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) recognize(uri) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenHeader("Scan", null)
        Text("Photo se text padho (OCR, device par — image cloud nahi jati).", color = UltronColors.TextLo)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !devanagari, onClick = { devanagari = false }, label = { Text("English / Roman") })
            FilterChip(selected = devanagari, onClick = { devanagari = true }, label = { Text("हिन्दी (Devanagari)") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                try {
                    val dir = File(context.cacheDir, "scan").apply { mkdirs() }
                    val file = File.createTempFile("scan_", ".jpg", dir)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    pendingUri = uri
                    camera.launch(uri)
                } catch (e: Exception) { status = "Camera app nahi khul paya." }
            }) { Text("Camera se") }
            OutlinedButton(enabled = !busy, onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Gallery se") }
        }
        if (busy) Text("Padh raha hoon…", color = UltronColors.Cyan, modifier = Modifier.padding(top = 12.dp))
        status?.let { Text(it, color = UltronColors.Gold, modifier = Modifier.padding(top = 12.dp)) }
        if (text.isNotEmpty()) {
            SectionLabel("Mila hua text")
            GlassCard(Modifier.fillMaxWidth()) { SelectionContainer { Text(text, Modifier.padding(14.dp)) } }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    c.pendingPrompt.value = PendingPrompt("Is text ko samjhao:\n\n$text", autoSend = false)
                    onNavigate(Routes.chat())
                }) { Text("Chat mein poochho") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val t = System.currentTimeMillis()
                        c.db.memory().insert(MemoryEntity(text = text.take(500), source = "scan", createdAt = t, updatedAt = t))
                        status = "Yaadein mein save ho gaya."
                    }
                }) { Text("Yaadein mein rakho") }
            }
            Text("\"Chat mein poochho\" sirf text ko composer mein daalta hai; bhejne se pehle aap dekh/edit kar sakte ho.", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo, modifier = Modifier.padding(top = 8.dp))
        }
        SectionLabel("Abhi available nahi")
        Text("QR/barcode live scanning is build mein nahi hai (iske liye in-app camera + CAMERA permission chahiye).", color = UltronColors.TextLo)
    }
}
