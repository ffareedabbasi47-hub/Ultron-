package com.ultron.ai.ui.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultron.ai.AssistantState
import com.ultron.ai.ui.theme.UltronColors

/**
 * Home-screen replacement for the 2D core when a 3D avatar is selected. The avatar floats on the app
 * background (transparent WebView, no frame/border). Tap toggles the mic on Home.
 */
@Composable
fun AvatarHero(
    avatarId: String,
    state: AssistantState,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
    onFailed: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.semantics { contentDescription = "ULTRON 3D avatar. Bolne ke liye tap karo" }) {
        AvatarView(
            avatarId = avatarId, state = state, lowPerformance = lowPerformance, reducedMotion = reducedMotion,
            modifier = Modifier.fillMaxSize(),
            onStatus = { if (it is AvatarStatus.Error) onFailed() },
        )
        // The WebView would swallow touches; this transparent layer makes the whole area a button.
        Box(Modifier.fillMaxSize().clickable(onClick = onClick))
    }
}
