package com.ultron.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ultron.ai.ui.avatar.AvatarStudioScreen
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.ui.chat.ChatScreen
import com.ultron.ai.ui.features.GaaneScreen
import com.ultron.ai.ui.features.JournalScreen
import com.ultron.ai.ui.features.MemoryScreen
import com.ultron.ai.ui.features.MoodScreen
import com.ultron.ai.ui.features.ScanScreen
import com.ultron.ai.ui.features.TodayScreen
import com.ultron.ai.ui.features.WeatherScreen
import com.ultron.ai.ui.home.HomeScreen
import com.ultron.ai.ui.live.LiveScreen
import com.ultron.ai.ui.settings.AboutScreen
import com.ultron.ai.ui.settings.AiScreen
import com.ultron.ai.ui.settings.AutomationScreen
import com.ultron.ai.ui.settings.ConnectScreen
import com.ultron.ai.ui.settings.DiagnosticsScreen
import com.ultron.ai.ui.settings.GeneralScreen
import com.ultron.ai.ui.settings.InfoScreen
import com.ultron.ai.ui.settings.PrivacyScreen
import com.ultron.ai.ui.settings.VoiceStudioScreen
import com.ultron.ai.ui.theme.UltronColors
import kotlinx.coroutines.launch

private data class MenuItem(val label: String, val route: String, val tag: String? = null)
private data class MenuGroup(val title: String, val items: List<MenuItem>)

private val menu = listOf(
    MenuGroup("ULTRON", listOf(
        MenuItem("Ghar", Routes.HOME), MenuItem("Chat aur History", Routes.chat()),
        MenuItem("Yaadein / Memory", Routes.MEMORY), MenuItem("Live Voice", Routes.LIVE),
    )),
    MenuGroup("Roz ke Kaam", listOf(
        MenuItem("Gaane / YouTube", Routes.GAANE), MenuItem("Padhai", Routes.chat(study = true)),
        MenuItem("Journal / Notes", Routes.JOURNAL), MenuItem("Mausam", Routes.WEATHER),
        MenuItem("Aaj / Reminders", Routes.TODAY), MenuItem("Mood", Routes.MOOD),
        MenuItem("Scan / Camera", Routes.SCAN), MenuItem("Documents / Files", Routes.info("files"), "Partial"),
    )),
    MenuGroup("AI Studio", listOf(
        MenuItem("AI Models / Provider", Routes.AI), MenuItem("Awaaz Studio", Routes.VOICE),
        MenuItem("Avatar Studio / 3D Models", Routes.AVATAR, "Beta"),
        MenuItem("Download Center", Routes.info("downloads"), "Available nahi"),
        MenuItem("Web Search", Routes.info("websearch"), "Setup chahiye"),
    )),
    MenuGroup("Phone Control", listOf(
        MenuItem("Apps kholna", Routes.AUTOMATION), MenuItem("Screen Samjho", Routes.info("screen"), "Available nahi"),
        MenuItem("Accessibility", Routes.info("accessibility"), "Available nahi"), MenuItem("PC ↔ Phone", Routes.info("pc"), "Setup chahiye"),
    )),
    MenuGroup("Settings aur Safety", listOf(
        MenuItem("ULTRON ke Rules / Persona", Routes.GENERAL), MenuItem("Language aur Reply Style", Routes.AI),
        MenuItem("Voice / Wake Word", Routes.VOICE), MenuItem("Permissions Dashboard", Routes.PRIVACY),
        MenuItem("Privacy aur Data", Routes.PRIVACY), MenuItem("Memory Controls", Routes.MEMORY),
        MenuItem("Network / Model Usage", Routes.CONNECT), MenuItem("Diagnostics", Routes.DIAG), MenuItem("About", Routes.ABOUT),
    )),
)

private val bottomRoutes = setOf(Routes.HOME, Routes.SCAN, Routes.MEMORY)

@Composable
fun AppRoot(settings: UltronSettings) {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val chatRoute = route?.startsWith("chat") == true
    val showBottom = route in bottomRoutes || chatRoute

    fun go(r: String) {
        scope.launch { drawer.close() }
        navigateTop(nav, r)
    }
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    val push: (String) -> Unit = { r -> nav.navigate(r) { launchSingleTop = true } }
    val back: () -> Unit = { if (!nav.popBackStack()) navigateTop(nav, Routes.HOME) }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = route == Routes.HOME || drawer.isOpen,
        drawerContent = { DrawerContent(onPick = ::go) },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
                    composable(Routes.HOME) { HomeScreen(settings, openMenu, push) }
                    composable(
                        Routes.CHAT,
                        arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "chat" }),
                    ) { e -> ChatScreen(study = e.arguments?.getString("mode") == "study", onBack = back, onNavigate = push) }
                    composable(Routes.SCAN) { ScanScreen(push) }
                    composable(Routes.MEMORY) { MemoryScreen(onBack = null) }
                    composable(Routes.LIVE) { LiveScreen(settings, back, push) }
                    composable(Routes.JOURNAL) { JournalScreen(back) }
                    composable(Routes.MOOD) { MoodScreen(back) }
                    composable(Routes.TODAY) { TodayScreen(back) }
                    composable(Routes.WEATHER) { WeatherScreen(settings, back, push) }
                    composable(Routes.GAANE) { GaaneScreen(back) }
                    composable(Routes.VOICE) { VoiceStudioScreen(settings, back) }
                    composable(Routes.AI) { AiScreen(settings, back) { push(Routes.CONNECT) } }
                    composable(Routes.GENERAL) { GeneralScreen(settings, back) }
                    composable(Routes.PRIVACY) { PrivacyScreen(settings, back) }
                    composable(Routes.CONNECT) { ConnectScreen(settings, back) }
                    composable(Routes.DIAG) { DiagnosticsScreen(back) }
                    composable(Routes.ABOUT) { AboutScreen(back) }
                    composable(Routes.AUTOMATION) { AutomationScreen(back) }
                    composable(Routes.AVATAR) { AvatarStudioScreen(settings, back) }
                    composable(Routes.INFO, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        InfoScreen(e.arguments?.getString("id").orEmpty(), back)
                    }
                }
            }
            if (showBottom) {
                BottomBar(
                    route = route,
                    chatSelected = chatRoute,
                    onHome = { navigateTop(nav, Routes.HOME) },
                    onScan = { navigateTop(nav, Routes.SCAN) },
                    onLive = { push(Routes.LIVE) },
                    onMemory = { navigateTop(nav, Routes.MEMORY) },
                    onChat = { navigateTop(nav, Routes.chat()) },
                )
            }
        }
    }
}

private fun navigateTop(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = false }
        launchSingleTop = true
    }
}

@Composable
private fun DrawerContent(onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalDrawerSheet(drawerContainerColor = UltronColors.Graphite) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
            Text("ULTRON", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Menu mein dhoondo") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            )
            val q = query.trim().lowercase()
            val groups = menu.map { g -> g.copy(items = g.items.filter { q.isEmpty() || it.label.lowercase().contains(q) }) }.filter { it.items.isNotEmpty() }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (groups.isEmpty()) item { Text("Kuch nahi mila.", color = UltronColors.TextLo, modifier = Modifier.padding(16.dp)) }
                groups.forEach { g ->
                    item(key = "h-${g.title}") {
                        Text(g.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = UltronColors.Cyan, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
                    }
                    g.items.forEach { m ->
                        item(key = "i-${g.title}-${m.label}") {
                            Row(
                                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                                    .clickable { onPick(m.route) }.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(m.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                m.tag?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = UltronColors.Gold) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    route: String?, chatSelected: Boolean,
    onHome: () -> Unit, onScan: () -> Unit, onLive: () -> Unit, onMemory: () -> Unit, onChat: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().background(Color(0xE6120E1E)).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(UltronColors.GlassBorder))
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            NavItem("Home", Icons.Filled.Home, route == Routes.HOME, Modifier.weight(1f), onHome)
            NavItem("Scan", Icons.Filled.CenterFocusStrong, route == Routes.SCAN, Modifier.weight(1f), onScan)
            Spacer(Modifier.weight(1f))
            NavItem("Yaadein", Icons.Filled.Psychology, route == Routes.MEMORY, Modifier.weight(1f), onMemory)
            NavItem("Chat", Icons.Filled.Forum, chatSelected, Modifier.weight(1f), onChat)
        }
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-26).dp).size(76.dp).clip(CircleShape)
                .background(UltronColors.Bg).padding(5.dp).clip(CircleShape).background(UltronColors.Violet)
                .clickable(onClick = onLive)
                .semantics { contentDescription = "Live voice shuru karo" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) UltronColors.Violet else UltronColors.TextLo
    Column(
        modifier.height(72.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Default), color = color, maxLines = 1)
    }
}
