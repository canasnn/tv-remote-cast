package com.canasnn.tvremote.cast.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

/** "+1.5 s", "-0.1 s", "0.0 s". */
internal fun formatSubtitleOffset(offsetMs: Long): String =
    (if (offsetMs > 0) "+" else "") + String.format(Locale.US, "%.1f s", offsetMs / 1000.0)

/**
 * Pick a step (0.1 / 0.5 / 1.0 / 5.0 s), then tap − or +
 * as often as needed. Every tap is applied immediately through [onChange].
 */
@Composable
fun SubtitleTimingControl(offsetMs: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    var step by rememberSaveable { mutableLongStateOf(500L) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Timer, null, tint = MaterialTheme.colorScheme.primary)
            Text("Timing", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (offsetMs != 0L) TextButton(onClick = { onChange(0L) }) { Text("Reset") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100L, 500L, 1000L, 5000L).forEach { value ->
                FilterChip(
                    selected = step == value,
                    onClick = { step = value },
                    label = { Text(String.format(Locale.US, "%.1f s", value / 1000.0)) }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onChange(offsetMs - step) }) { Icon(Icons.Filled.Remove, "Subtract time") }
            Text(
                formatSubtitleOffset(offsetMs),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            FilledTonalIconButton(onClick = { onChange(offsetMs + step) }) { Icon(Icons.Filled.Add, "Add time") }
        }
        Text(
            "If you see subtitles before you hear dialog: Add time.\nIf you hear dialog before you see subtitles: Subtract time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The timing control in a dialog (phone player, now-playing screen). */
@Composable
fun SubtitleTimingDialog(offsetMs: Long, onChange: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust timing") },
        text = { SubtitleTimingControl(offsetMs, onChange) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
