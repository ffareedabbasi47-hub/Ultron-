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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import com.ultron.ai.BuildConfig
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
import androidx.compose.ui.unit.sp
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
import com.ultron.ai.ui.settings.AboutScreen
import com.ultron.ai.ui.settings.BackupScreen
import com.ultron.ai.ui.settings.ConnectorsScreen
import com.ultron.ai.ui.settings.PolicyScreen
import com.ultron.ai.ui.settings.VoiceEngineScreen
import com.ultron.ai.ui.settings.AccessibilityScreen
import com.ultron.ai.ui.settings.ActivityScreen
import com.ultron.ai.ui.settings.NotificationsScreen
import com.ultron.ai.ui.settings.RulesScreen
import com.ultron.ai.ui.settings.SettingsHubScreen
import com.ultron.ai.ui.features.MarketsScreen
import com.ultron.ai.ui.features.WorkKind
import com.ultron.ai.ui.features.WorkScreen
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

private data class MenuItem(val label: String, val route: String, val icon: ImageVector, val tag: String? = null)
private data class MenuGroup(val title: String, val items: List<MenuItem>)

private val menu = listOf(
    MenuGroup("", listOf(
        MenuItem("Home", Routes.HOME, Icons.Filled.Home),
        MenuItem("Yaadein", Routes.MEMORY, Icons.Filled.Psychology),
        MenuItem("Chat", Routes.chat(), Icons.Filled.Forum),
    )),
    MenuGroup("KAAM-KAAJ", listOf(
        MenuItem("Markets", Routes.MARKETS, Icons.Filled.ShowChart),
        MenuItem("Marketing", Routes.MARKETING, Icons.Filled.Storefront),
        MenuItem("Documents", Routes.DOCS, Icons.Filled.Description),
        MenuItem("Website / Coding", Routes.CODING, Icons.Filled.Code),
        MenuItem("Live aur Assistant", Routes.ACCESS, Icons.Filled.AccessibilityNew),
        MenuItem("Gaane / YouTube", Routes.GAANE, Icons.Filled.MusicNote),
        MenuItem("Padhai", Routes.chat(study = true), Icons.Filled.MenuBook),
        MenuItem("Journal / Notes", Routes.JOURNAL, Icons.Filled.Edit),
        MenuItem("Mausam", Routes.WEATHER, Icons.Filled.Cloud),
        MenuItem("Aaj / Reminders", Routes.TODAY, Icons.Filled.CalendarToday),
        MenuItem("Mood", Routes.MOOD, Icons.Filled.Favorite),
        MenuItem("Scan / Camera", Routes.SCAN, Icons.Filled.CenterFocusStrong),
        MenuItem("Apps kholna", Routes.AUTOMATION, Icons.Filled.Apps),
    )),
    MenuGroup("AI STUDIO", listOf(
        MenuItem("AI Brain / Gemini key", Routes.AI, Icons.Filled.AutoAwesome),
        MenuItem("Awaaz Studio", Routes.VOICE, Icons.Filled.RecordVoiceOver),
        MenuItem("Avatar Studio / Downloads", Routes.AVATAR, Icons.Filled.Face, "Beta"),
    )),
    MenuGroup("SYSTEM", listOf(
        MenuItem("Settings", Routes.SETTINGS, Icons.Filled.Settings),
        MenuItem("ULTRON ke rules", Routes.RULES, Icons.Filled.VerifiedUser),
        MenuItem("Privacy aur Data", Routes.PRIVACY, Icons.Filled.Lock),
        MenuItem("Activity", Routes.ACTIVITY, Icons.Filled.History),
        MenuItem("Diagnostics", Routes.DIAG, Icons.Filled.BugReport),
        MenuItem("About", Routes.ABOUT, Icons.Filled.Info),
    )),
)

private val bottomRoutes = setOf(Routes.HOME, Routes.SCAN, Routes.MEMORY)

@Composable
fun AppRoot(settings: UltronSettings) {
    val nav = rememberNavController()
    val c = LocalContainer.current
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

    val liveState by c.live.state.collectAsState()
    val liveOn = liveState == com.ultron.ai.domain.live.LiveState.LISTENING || liveState == com.ultron.ai.domain.live.LiveState.USER_SPEECH ||
        liveState == com.ultron.ai.domain.live.LiveState.THINKING || liveState == com.ultron.ai.domain.live.LiveState.SPEAKING
    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = route == Routes.HOME || drawer.isOpen,
        drawerContent = { DrawerContent(route = route, onPick = ::go) },
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
                    composable(Routes.MARKETS) { MarketsScreen(back, push) }
                    composable(Routes.MARKETING) { com.ultron.ai.ui.features.MarketingScreen(back, push) }
                    composable(Routes.DOCS) { WorkScreen(WorkKind.DOC, back, push) }
                    composable(Routes.CODING) { WorkScreen(WorkKind.CODE, back, push) }
                    composable(Routes.ACCESS) { AccessibilityScreen(settings, back) }
                    composable(Routes.SETTINGS) { SettingsHubScreen(back, push) }
                    composable(Routes.RULES) { RulesScreen(settings, back) }
                    composable(Routes.BACKUP) { BackupScreen(back) }
                    composable(Routes.POLICY) { PolicyScreen(back) }
                    composable(Routes.VOICE_ENGINE) { VoiceEngineScreen(back, push) }
                    composable(Routes.CONNECTORS) { ConnectorsScreen(back) }
                    composable(Routes.PC) { InfoScreen("pc", back) }
                    composable(Routes.ACTIVITY) { ActivityScreen(back, push) }
                    composable(Routes.NOTIFS) { NotificationsScreen(back, push) }
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
                    onHome = { if (route == Routes.HOME) c.homeReselect.value++ else navigateTop(nav, Routes.HOME) },
                    onScan = { navigateTop(nav, Routes.SCAN) },
                    onLive = { c.micRequest.value = true; if (route != Routes.HOME) navigateTop(nav, Routes.HOME) },
                    onMemory = { navigateTop(nav, Routes.MEMORY) },
                    onChat = { navigateTop(nav, Routes.chat()) },
                )
            }
        }
    }
    if (settings.edgeLight) com.ultron.ai.ui.components.LiveEdgeLight(
        active = liveOn,
        intense = liveState == com.ultron.ai.domain.live.LiveState.SPEAKING || liveState == com.ultron.ai.domain.live.LiveState.USER_SPEECH,
        reducedMotion = settings.reducedMotion,
    )
    }
}

private fun navigateTop(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = false }
        launchSingleTop = true
    }
}

@Composable
private fun DrawerContent(route: String?, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalDrawerSheet(drawerContainerColor = UltronColors.Bg, drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
            Row(Modifier.padding(top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(UltronColors.Blue, UltronColors.Violet))),
                    contentAlignment = Alignment.Center,
                ) { Text("U", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold) }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("ULTRON", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 2.sp), fontWeight = FontWeight.Bold)
                    Text("Personal AI Assistant", style = MaterialTheme.typography.bodyMedium, color = UltronColors.TextLo)
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                placeholder = { Text("Menu mein dhoondo") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            )
            val q = query.trim().lowercase()
            val groups = menu.map { g -> g.copy(items = g.items.filter { q.isEmpty() || it.label.lowercase().contains(q) }) }.filter { it.items.isNotEmpty() }
            LazyColumn(Modifier.weight(1f, fill = true), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (groups.isEmpty()) item { Text("Kuch nahi mila.", color = UltronColors.TextLo, modifier = Modifier.padding(16.dp)) }
                groups.forEach { g ->
                    if (g.title.isNotEmpty()) item(key = "h-${g.title}") {
                        Text(g.title, style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp))
                    }
                    g.items.forEach { m ->
                        item(key = "i-${g.title}-${m.label}") {
                            val active = route == m.route
                            val shape = RoundedCornerShape(24.dp)
                            Row(
                                Modifier.fillMaxWidth().height(58.dp).shadow(4.dp, shape, ambientColor = Color(0x66000000), spotColor = Color(0x334F9CFF)).clip(shape)
                                    .background(
                                        if (active) Brush.horizontalGradient(listOf(UltronColors.Blue.copy(alpha = 0.55f), UltronColors.Violet.copy(alpha = 0.45f)))
                                        else Brush.linearGradient(listOf(UltronColors.Raised, Color(0xFF1B2450))), shape,
                                    )
                                    .clickable { onPick(m.route) }.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(m.icon, null, tint = if (active) Color.White else UltronColors.Blue, modifier = Modifier.size(22.dp))
                                Text(m.label, Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                m.tag?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = UltronColors.Gold) }
                            }
                        }
                    }
                }
            }
            Text("ULTRON v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun BottomBar(
    route: String?, chatSelected: Boolean,
    onHome: () -> Unit, onScan: () -> Unit, onLive: () -> Unit, onMemory: () -> Unit, onChat: () -> Unit,
) {
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp)
                .shadow(8.dp, shape, ambientColor = Color(0x66000000), spotColor = Color(0x664F9CFF))
                .clip(shape).background(Brush.linearGradient(listOf(UltronColors.GlassTop, UltronColors.GlassBottom)), shape)
                .border(1.dp, UltronColors.GlassBorder, shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("Home", Icons.Filled.Home, route == Routes.HOME, Modifier.weight(1f), onHome)
            NavItem("Scan", Icons.Filled.CenterFocusStrong, route == Routes.SCAN, Modifier.weight(1f), onScan)
            Spacer(Modifier.weight(1f))
            NavItem("Yaadein", Icons.Filled.Psychology, route == Routes.MEMORY, Modifier.weight(1f), onMemory)
            NavItem("Chat", Icons.Filled.Forum, chatSelected, Modifier.weight(1f), onChat)
        }
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-26).dp).size(76.dp)
                .shadow(14.dp, CircleShape, ambientColor = UltronColors.Blue, spotColor = UltronColors.Blue)
                .clip(CircleShape).background(Brush.radialGradient(listOf(UltronColors.Cyan, UltronColors.Blue)))
                .padding(8.dp).clip(CircleShape).background(Color.White)
                .clickable(onClick = onLive)
                .semantics { contentDescription = "Bolna shuru karo" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = UltronColors.Bg, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) Color.White else UltronColors.TextLo
    Box(modifier.height(68.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(
            Modifier.size(width = 62.dp, height = 56.dp).clip(RoundedCornerShape(20.dp))
                .then(if (selected) Modifier.background(Brush.verticalGradient(listOf(UltronColors.Blue, Color(0xFF2B6FD6))), RoundedCornerShape(20.dp)) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Default, fontSize = 11.sp), color = color, maxLines = 1)
        }
    }
}
