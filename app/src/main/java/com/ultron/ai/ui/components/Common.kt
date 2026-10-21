package com.ultron.ai.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ultron.ai.ui.theme.UltronColors

fun shareText(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = UltronColors.Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = UltronColors.TextLo, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun LabeledField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = modifier.fillMaxWidth(), singleLine = singleLine, minLines = minLines,
    )
}

@Suppress("unused")
private val keep = Modifier.size(0.dp)
