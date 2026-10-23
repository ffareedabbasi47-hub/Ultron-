package com.ultron.ai.ui.avatar

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ultron.ai.data.avatar.AvatarIds
import com.ultron.ai.data.avatar.AvatarOpResult
import com.ultron.ai.data.avatar.CustomAvatar
import com.ultron.ai.data.avatar.GlbFiles
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.platform.wallpaper.UltronWallpaperService
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.LabeledField
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.components.StatusPill
import com.ultron.ai.ui.components.UltronCore
import com.ultron.ai.ui.theme.UltronColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AvatarStudioScreen(settings: UltronSettings, onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val customs by c.avatars.items.collectAsState()
    val assistant by c.assistantState.collectAsState()
    var status by remember { mutableStateOf<AvatarStatus>(AvatarStatus.Loading) }
    var expr by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var toDelete by remember { mutableStateOf<CustomAvatar?>(null) }
    val selected = settings.avatarId

    fun select(id: String) { scope.launch { c.settings.update { it.copy(avatarId = id) } } }

    fun handle(r: AvatarOpResult, what: String) {
        when (r) {
            is AvatarOpResult.Ok -> { select(r.avatar.id); message = "$what ho gaya: ${r.avatar.displayName} (${GlbFiles.formatSize(r.avatar.sizeBytes)})" }
            is AvatarOpResult.Failed -> message = r.reason
            AvatarOpResult.Cancelled -> message = "Download cancel kiya"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { message = null; handle(c.avatars.importFrom(uri), "Import") }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenHeader("Avatar Studio", onBack)

        // ---- live preview
        Box(Modifier.fillMaxWidth().height(340.dp), contentAlignment = Alignment.Center) {
            if (selected == AvatarIds.CORE) UltronCore(assistant, 0f, Modifier.fillMaxWidth(0.8f))
            else AvatarView(selected, assistant, settings.lowPerformance, settings.reducedMotion, Modifier.fillMaxSize(), expression = expr, onStatus = { status = it })
        }
        if (selected == AvatarIds.ULTRON3D) {
            Text("Expression (built-in model)", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "Auto", "calm" to "Calm", "menacing" to "Khatarnak", "smirk" to "Smirk", "curious" to "Curious", "alert" to "Alert", "surprised" to "Surprised", "calculating" to "Calculating", "cold" to "Cold").forEach { (k, label) ->
                    androidx.compose.material3.FilterChip(selected = expr == k, onClick = { expr = k }, label = { Text(label) })
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                selected == AvatarIds.CORE -> StatusPill("2D core (built-in)", UltronColors.Cyan)
                status is AvatarStatus.Ready -> StatusPill("3D ready", UltronColors.Green)
                status is AvatarStatus.Error -> StatusPill("Load nahi hua", UltronColors.Red)
                else -> StatusPill("Load ho raha hai", UltronColors.Gold)
            }
            Text("Model hamesha saamne dekhta hai; bolte waqt muh hilta hai.", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo)
        }
        (status as? AvatarStatus.Error)?.takeIf { selected != AvatarIds.CORE }?.let {
            Text(it.message + "\nHome screen par tab tak 2D core dikhega.", color = UltronColors.Gold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        if (settings.lowPerformance) Text("Low performance mode on hai: Home par 2D core hi dikhega. Menu → General se band karo.", color = UltronColors.Gold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))

        // ---- choose
        SectionLabel("Avatar chuno")
        AvatarRow("2D animated core", "Built-in, sabse halka", selected == AvatarIds.CORE, onPick = { select(AvatarIds.CORE) })
        AvatarRow("ULTRON 3D (original)", "Built-in, is app ke liye banaya gaya", selected == AvatarIds.ULTRON3D, onPick = { select(AvatarIds.ULTRON3D) })
        customs.forEach { a ->
            AvatarRow(a.displayName, "Aapka model · ${GlbFiles.formatSize(a.sizeBytes)}", selected == a.id, onPick = { select(a.id) }, onDelete = { toDelete = a })
        }

        // ---- add own model
        SectionLabel("Apna 3D model jodo")
        Text(
            "Format: .glb (glTF 2.0, embedded textures), 50 MB tak. Draco/meshopt compression, skeleton animation aur normal-maps abhi supported nahi: model static pose mein dikhta hai.",
            style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { message = null; picker.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*")) }, enabled = job == null) { Text("GLB file chuno") }
        }
        LabeledField(url, { url = it.take(500) }, "ya https:// link se download (.glb)", Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = job == null && url.isNotBlank(), onClick = {
                if (!GlbFiles.isHttpsUrl(url)) { message = "Sirf https:// link allowed hai"; return@Button }
                message = null; progress = 0L to -1L
                job = scope.launch {
                    val r = c.avatars.download(url) { d, t -> progress = d to t }
                    progress = null; job = null
                    handle(r, "Download")
                    if (r is AvatarOpResult.Ok) url = ""
                }
            }) { Text("Download") }
            if (job != null) OutlinedButton(onClick = { job?.cancel() }) { Text("Cancel") }
        }
        progress?.let { (done, total) ->
            if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text("${GlbFiles.formatSize(done)}" + if (total > 0) " / ${GlbFiles.formatSize(total)}" else "", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo)
        }
        message?.let { Text(it, color = UltronColors.TextHi, modifier = Modifier.padding(top = 8.dp)) }
        GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Licence aapki zimmedari hai", style = MaterialTheme.typography.titleMedium)
                Text(
                    "ULTRON kisi model ki licence verify nahi karta aur koi \"verified catalog\" nahi hai. Sirf wahi model use karo jiska licence (CC0, CC-BY, ya khud ka) aapko allow karta ho; CC-BY mein credit dena hota hai. Movie/game se nikale (ripped) ya copyrighted character models mat use karo.",
                    style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo,
                )
            }
        }

        // ---- phone home screen
        SectionLabel("Phone ki home screen (live wallpaper)")
        Text(
            "Built-in ULTRON 3D avatar ko phone ke wallpaper ki tarah set kar sakte ho. Ye native OpenGL se alag chalta hai, sirf built-in model dikhata hai (custom GLB nahi), sirf tab render hota hai jab wallpaper dikh raha ho, ~30 fps par. Touch se sir ghoomta hai; app chal rahi ho to assistant ki state (listening/thinking/speaking) bhi dikhta hai.",
            style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo,
        )
        Button(modifier = Modifier.padding(top = 8.dp), onClick = {
            val comp = ComponentName(context, UltronWallpaperService::class.java)
            try {
                context.startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp))
            } catch (_: ActivityNotFoundException) {
                try { context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
                catch (_: ActivityNotFoundException) { message = "Is phone par live wallpaper chooser nahi mila. Home screen long-press → Wallpapers se \"ULTRON Avatar\" chuno." }
            }
        }) { Text("Wallpaper set karo") }
        Text(
            "Dhyan: wallpaper GPU/battery use karta hai aur mere test environment mein asli phone par chalaya nahi gaya. Agar dikhne mein dikkat ho to wapas normal wallpaper chun lo.",
            style = MaterialTheme.typography.labelSmall, color = UltronColors.Gold, modifier = Modifier.padding(top = 6.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.padding(20.dp))
    }

    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("${a.displayName} delete karein?") },
            text = { Text("Ye model is phone se hata diya jayega.") },
            confirmButton = {
                TextButton(onClick = {
                    c.avatars.delete(a.fileName)
                    if (selected == a.id) select(AvatarIds.ULTRON3D)
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Rehne do") } },
        )
    }
}

@Composable
private fun AvatarRow(title: String, subtitle: String, selected: Boolean, onPick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onPick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo)
        }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "$title delete karo", tint = UltronColors.TextLo) }
    }
}
