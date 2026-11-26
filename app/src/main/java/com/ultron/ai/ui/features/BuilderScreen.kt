package com.ultron.ai.ui.features

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultron.ai.platform.builder.BuildJob
import com.ultron.ai.platform.builder.BuildStage
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.LabeledField
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.components.StatusPill
import com.ultron.ai.ui.components.shareText
import com.ultron.ai.ui.theme.UltronColors
import kotlinx.coroutines.launch

/**
 * Builder agent screen. UX rules applied (ui-ux-pro-max): 48dp touch targets, visible labels, status shown as TEXT plus
 * colour (never colour alone), progress feedback for long work, helper text for the one decision that matters (where files go),
 * disabled state when the brief is too short, 8dp spacing rhythm.
 */
@Composable
fun BuilderScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by c.builder.jobs.collectAsState()
    val settings by c.settings.settings.collectAsState(initial = com.ultron.ai.data.settings.UltronSettings())
    var brief by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.isSuccess
            if (ok) scope.launch { c.settings.update { it.copy(builderFolderUri = uri.toString()) } }
            else note = "Ye folder permanent permission nahi de raha. Koi aur folder chuno."
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Builder agent", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Voice agent aapki baat sunta hai; coding ka kaam yeh Builder background mein karta hai: Stage A architecture plan, Stage B files. Bolo: \"builder se meri dukaan ki website banwao\".", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)
                        Text("Cloud AI allow aur Gemini key chahiye. Aapka brief Google Gemini ko jata hai.", style = MaterialTheme.typography.bodySmall, color = UltronColors.TextLo)
                    }
                }
            }
            item {
                SectionLabel("Files kahan save hongi")
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chosen = settings.builderFolderUri.isNotBlank()
                        StatusPill(if (chosen) "Aapka folder set hai" else "Folder set nahi (app ke andar save hoga)", if (chosen) UltronColors.Green else UltronColors.Gold)
                        Text("Har project ek naye subfolder mein jayega, purani files overwrite nahi hongi.", style = MaterialTheme.typography.bodySmall, color = UltronColors.TextLo)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { picker.launch(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (chosen) "Folder badlo" else "Folder chuno") }
                            if (chosen) OutlinedButton(onClick = { scope.launch { c.settings.update { it.copy(builderFolderUri = "") } } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Hatao") }
                        }
                        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = UltronColors.Gold) }
                    }
                }
            }
            item {
                SectionLabel("Naya kaam")
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledField(brief, { brief = it.take(600) }, "Kya banana hai?", singleLine = false, minLines = 3)
                        Text("Jaise: restaurant ki website, menu, timing aur call button ke saath. (${brief.trim().length}/600)", style = MaterialTheme.typography.bodySmall, color = UltronColors.TextLo)
                        val ready = brief.trim().length >= 6
                        Button(
                            onClick = { if (c.builder.submit(brief) != null) { brief = ""; note = null } else note = "Thoda detail mein batao (kam se kam 6 akshar)." },
                            enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Builder ko do") }
                    }
                }
            }
            item { SectionLabel(if (jobs.isEmpty()) "Abhi koi kaam nahi" else "Kaam") }
            items(jobs, key = { it.id }) { j -> JobCard(j, onCancel = { c.builder.cancel(j.id) }, onShare = { shareText(context, "Builder plan", j.plan) }) }
            if (jobs.any { it.finished }) item { TextButton(onClick = { c.builder.clearFinished() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Poore hue kaam hatao") } }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun JobCard(j: BuildJob, onCancel: () -> Unit, onShare: () -> Unit) {
    var showPlan by remember { mutableStateOf(false) }
    val color = when (j.stage) { BuildStage.DONE -> UltronColors.Green; BuildStage.FAILED -> UltronColors.Red; BuildStage.CANCELLED -> UltronColors.Gold; else -> UltronColors.Cyan }
    GlassCard(Modifier.fillMaxWidth().semantics { contentDescription = "Builder kaam: ${j.stage.label}. ${j.brief}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(j.stage.label, color)
            Text(j.brief, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
            if (!j.finished) LinearProgressIndicator(Modifier.fillMaxWidth(), color = UltronColors.Cyan)
            if (j.message.isNotBlank()) Text(j.message, style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)
            if (j.files.isNotEmpty()) Text("Files: " + j.files.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = UltronColors.TextLo)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!j.finished) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                if (j.plan.isNotBlank()) {
                    TextButton(onClick = { showPlan = !showPlan }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showPlan) "Plan chhupao" else "Plan dekho") }
                    if (showPlan) TextButton(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp)) { Text("Plan share") }
                }
            }
            if (showPlan) Text(j.plan, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
