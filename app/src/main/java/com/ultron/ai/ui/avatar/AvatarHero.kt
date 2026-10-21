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
import com.ultron.ai.ui.components.Equalizer
import com.ultron.ai.ui.components.coreStateLabel
import com.ultron.ai.ui.theme.UltronColors

/** Home-screen replacement for the 2D core when a 3D avatar is selected. Tap opens Live voice. */
@Composable
fun AvatarHero(
    avatarId: String,
    state: AssistantState,
    level: Float,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
    onFailed: () -> Unit,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(380.dp)
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, UltronColors.Violet.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
                .semantics { contentDescription = "ULTRON 3D avatar. Live voice kholne ke liye tap karo" },
        ) {
            AvatarView(
                avatarId = avatarId, state = state, lowPerformance = lowPerformance, reducedMotion = reducedMotion,
                modifier = Modifier.fillMaxSize(),
                onStatus = { if (it is AvatarStatus.Error) onFailed() },
            )
            // The WebView would swallow touches; this transparent layer makes the whole card a button.
            Box(Modifier.fillMaxSize().clickable(onClick = onClick))
            Text(
                if (state == AssistantState.IDLE) "BOLO, KAISE HELP KARUN?" else coreStateLabel(state).uppercase(),
                style = MaterialTheme.typography.labelSmall, color = UltronColors.TextLo,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth(0.6f).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                "ULTRON", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp),
                textAlign = TextAlign.Center, maxLines = 1,
            )
        }
        Equalizer(state, level)
    }
}
