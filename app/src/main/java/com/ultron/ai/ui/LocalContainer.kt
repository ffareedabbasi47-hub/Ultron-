package com.ultron.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ultron.ai.AppContainer

val LocalContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

object Routes {
    const val HOME = "home"
    const val CHAT = "chat?mode={mode}"
    const val SCAN = "scan"
    const val MEMORY = "memory"
    const val MARKETS = "markets"
    const val MARKETING = "marketing"
    const val DOCS = "docs"
    const val CODING = "coding"
    const val ACCESS = "access"
    const val SETTINGS = "settings"
    const val RULES = "rules"
    const val PC = "pc"
    const val ACTIVITY = "activity"
    const val NOTIFS = "notifs"
    const val BACKUP = "backup"
    const val POLICY = "policy"
    const val VOICE_ENGINE = "voiceengine"
    const val CONNECTORS = "connectors"
    const val JOURNAL = "journal"
    const val MOOD = "mood"
    const val TODAY = "today"
    const val WEATHER = "weather"
    const val GAANE = "gaane"
    const val VOICE = "voice"
    const val AI = "ai"
    const val GENERAL = "general"
    const val PRIVACY = "privacy"
    const val CONNECT = "connect"
    const val DIAG = "diag"
    const val ABOUT = "about"
    const val AUTOMATION = "automation"
    const val AVATAR = "avatar"
    const val INFO = "info/{id}"

    fun chat(study: Boolean = false) = "chat?mode=${if (study) "study" else "chat"}"
    fun info(id: String) = "info/$id"
}
