package com.ultron.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ultron.ai.ui.theme.UltronColors

/** Rounded glass composer: attachment, text, send (or stop while a reply is in flight). */
@Composable
fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onStop: () -> Unit = {},
    onAttach: (() -> Unit)? = null,
    attachBusy: Boolean = false,
) {
    val canSend = value.isNotBlank()
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp)).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onAttach != null) {
            IconButton(onClick = onAttach, enabled = !attachBusy, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Image se text padho (OCR)", tint = UltronColors.TextLo)
            }
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = UltronColors.TextLo.copy(alpha = 0.7f), maxLines = 1) },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend && !busy) onSend() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = UltronColors.Cyan,
            ),
        )
        val active = busy || canSend
        IconButton(
            onClick = { if (busy) onStop() else if (canSend) onSend() },
            enabled = active,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (active) UltronColors.Violet.copy(alpha = 0.85f) else UltronColors.Violet.copy(alpha = 0.18f)),
        ) {
            if (busy) Icon(Icons.Filled.Stop, contentDescription = "Rokho", tint = Color.White)
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Bhejo", tint = Color.White)
        }
    }
}
