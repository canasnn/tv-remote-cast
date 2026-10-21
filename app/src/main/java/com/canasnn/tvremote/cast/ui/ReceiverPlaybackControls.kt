package com.canasnn.tvremote.cast.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ruterika.protocol.PlaybackStatus
import com.ruterika.protocol.TrackType

/** Extra controls available when casting to our own TV receiver. */
data class ReceiverControls(
    val status: PlaybackStatus,
    val skipBackSeconds: Int,
    val skipForwardSeconds: Int,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSpeed: (Float) -> Unit,
    val onSelectTrack: (TrackType, String?) -> Unit,
    val onAddSubtitle: () -> Unit
)

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun ReceiverPlaybackExtras(controls: ReceiverControls, modifier: Modifier = Modifier) {
    val status = controls.status
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (status.itemCount > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Item ${status.itemIndex + 1} of ${status.itemCount}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(onClick = controls.onPrevious, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }
                    FilledTonalIconButton(onClick = controls.onNext, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                }
            }

            SectionLabel(Icons.Filled.Speed, "Speed")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                speeds.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(status.speed - speed) < 0.01f,
                        onClick = { controls.onSpeed(speed) },
                        label = { Text(if (speed == 1f) "Normal" else "${speed}x") }
                    )
                }
            }

            val audio = status.tracks.filter { it.type == TrackType.AUDIO }
            if (audio.size > 1) {
                SectionLabel(Icons.Filled.Audiotrack, "Audio")
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    audio.forEach { track ->
                        FilterChip(
                            selected = track.selected,
                            onClick = { controls.onSelectTrack(TrackType.AUDIO, track.id) },
                            label = { Text(track.label) }
                        )
                    }
                }
            }

            SectionLabel(Icons.Filled.ClosedCaption, "Subtitles")
            val text = status.tracks.filter { it.type == TrackType.TEXT }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = text.none { it.selected },
                    onClick = { controls.onSelectTrack(TrackType.TEXT, null) },
                    leadingIcon = { Icon(Icons.Filled.SubtitlesOff, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text("Off") }
                )
                text.forEach { track ->
                    FilterChip(
                        selected = track.selected,
                        onClick = { controls.onSelectTrack(TrackType.TEXT, track.id) },
                        label = { Text(track.label) }
                    )
                }
                AssistChip(onClick = controls.onAddSubtitle, label = { Text("Add subtitles…") })
            }

            status.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SectionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}
