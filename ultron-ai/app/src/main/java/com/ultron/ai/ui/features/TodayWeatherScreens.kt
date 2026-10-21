package com.ultron.ai.ui.features

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.data.weather.WeatherState
import com.ultron.ai.domain.ActionValidator
import com.ultron.ai.domain.UltronAction
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.Routes
import com.ultron.ai.ui.components.GlassCard
import com.ultron.ai.ui.components.LabeledField
import com.ultron.ai.ui.components.ScreenHeader
import com.ultron.ai.ui.components.SectionLabel
import com.ultron.ai.ui.theme.UltronColors
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TodayScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reminders by c.db.reminders().observeAll().collectAsState(initial = emptyList())
    var now by remember { mutableStateOf(Date()) }
    var text by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("10") }
    var message by remember { mutableStateOf<String?>(null) }
    var notifOk by remember { mutableStateOf(notificationsAllowed(context)) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it || notificationsAllowed(context) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Aaj", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(SimpleDateFormat("EEEE", Locale.getDefault()).format(now), style = MaterialTheme.typography.labelMedium, color = UltronColors.Cyan)
                        Text(SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(now), style = MaterialTheme.typography.headlineMedium)
                        Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(now), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 40.sp), color = UltronColors.TextHi)
                        Text("Device ka local time. Calendar events abhi connect nahi hain — sirf ULTRON ke reminders yahan dikhte hain.", color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item {
                SectionLabel("Naya reminder")
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledField(text, { text = it.take(200) }, "Kya yaad dilana hai?")
                        LabeledField(minutes, { minutes = it.filter(Char::isDigit).take(5) }, "Kitne minute baad?")
                        if (!notifOk) {
                            Text("Notifications blocked hain, isliye reminder ka alert nahi dikhega.", color = UltronColors.Gold)
                            if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Notification permission do") }
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings kholo") }
                        }
                        Button(enabled = text.isNotBlank(), onClick = {
                            val action = UltronAction.SetReminder(text.trim(), minutes.toIntOrNull() ?: 0)
                            val v = ActionValidator.validate(action)
                            if (!v.ok) message = v.reason
                            else scope.launch {
                                val r = c.actions.run(action)
                                message = r.message
                                if (r.success) text = ""
                                notifOk = notificationsAllowed(context)
                            }
                        }) { Text("Reminder set karo") }
                        message?.let { Text(it, color = UltronColors.TextLo, style = MaterialTheme.typography.bodyMedium) }
                        Text("Timing approximate hai (battery saver/Doze se kuch der ho sakti hai).", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)
                    }
                }
            }
            item { SectionLabel("Reminders") }
            if (reminders.isEmpty()) item { Text("Koi reminder nahi.", color = UltronColors.TextLo, modifier = Modifier.padding(8.dp)) }
            items(reminders, key = { it.id }) { r ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.text, style = MaterialTheme.typography.bodyLarge)
                            Text(fmt.format(Date(r.triggerAt)) + if (r.fired) " · ho chuka" else "", style = MaterialTheme.typography.labelSmall, color = if (r.fired) UltronColors.TextLo else UltronColors.Cyan)
                        }
                        IconButton(onClick = { scope.launch { c.reminders.cancel(r.id) } }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
        }
    }
}

fun notificationsAllowed(context: android.content.Context): Boolean {
    val enabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    return enabled && (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
}

@Composable
fun WeatherScreen(settings: UltronSettings, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state by c.weather.state.collectAsState()
    var city by remember(settings.weatherCity) { mutableStateOf(settings.weatherCity) }

    LaunchedEffect(Unit) { c.weather.refresh() }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenHeader("Mausam", onBack)
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (val s = state) {
                    WeatherState.Loading -> Text("Mausam load ho raha hai…")
                    is WeatherState.Available -> {
                        val w = s.weather
                        Text(listOfNotNull(w.city, w.country).joinToString(", "), style = MaterialTheme.typography.labelMedium, color = UltronColors.Cyan)
                        Text("${w.tempC.toInt()}°C", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 56.sp))
                        Text(w.description, style = MaterialTheme.typography.titleMedium)
                        w.feelsC?.let { Text("Feels like ${it.toInt()}°C", color = UltronColors.TextLo) }
                        w.humidity?.let { Text("Humidity $it%", color = UltronColors.TextLo) }
                        w.windKph?.let { Text("Hawa ${it.toInt()} km/h", color = UltronColors.TextLo) }
                        Text(
                            "Source: Open-Meteo, ULTRON backend ke through · ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(s.fetchedAtMs))} par liya",
                            style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo,
                        )
                    }
                    is WeatherState.Unavailable -> {
                        Text("Mausam ke liye setup ya internet chahiye", style = MaterialTheme.typography.titleMedium)
                        Text(s.reason, color = UltronColors.Gold)
                    }
                    WeatherState.Idle -> Text("—")
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { c.weather.refresh() } }) { Text("Retry / refresh") }
            if (settings.backendUrl.isBlank()) TextButton(onClick = { onNavigate(Routes.CONNECT) }) { Text("Backend set karo") }
        }
        SectionLabel("Shehar")
        LabeledField(city, { city = it }, "Shehar ka naam (jaise Mumbai)")
        Button(enabled = city.isNotBlank() && city.trim() != settings.weatherCity, onClick = {
            scope.launch { c.settings.update { it.copy(weatherCity = city.trim().take(80)) } }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Shehar save karo") }
        Text("Location permission nahi maangi jati; shehar aap khud likhte ho.", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo, modifier = Modifier.padding(top = 8.dp))
    }
}

@Suppress("unused")
private val keepUri: Uri? = null
