package com.canasnn.tvremote.cast.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.ui.formatTime
import java.util.Locale

/** The playback rate menu. */
internal val PHONE_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.75f, 2f, 2.5f, 3f)

internal fun speedLabel(speed: Float): String =
    if (speed == 1f) "Normal" else "${speed.toString().removeSuffix(".0")}x"

/** "Continue watching?" before the phone player starts (resume mode Ask). */
@Composable
internal fun ResumeAskDialog(ask: ResumeAsk, onAnswer: (Long?) -> Unit) {
    val media = ask.media
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text("Continue watching?") },
        text = {
            Column {
                Text(media.title)
                Text(
                    if (media.durationMs > 0) "Played ${formatTime(ask.positionMs)} of ${formatTime(media.durationMs)}"
                    else "Last position ${formatTime(ask.positionMs)}"
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(ask.positionMs) }) { Text("Resume at ${formatTime(ask.positionMs)}") } },
        dismissButton = { TextButton(onClick = { onAnswer(0L) }) { Text("Start over") } }
    )
}

/** Volume slider (up to 200 % with boost), mute and the boost switch. */
@Composable
internal fun VolumeDialog(onDismiss: () -> Unit) {
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val volume by PhonePlayer.volume.collectAsStateWithLifecycle()
    val muted by PhonePlayer.muted.collectAsStateWithLifecycle()
    val phone = settings.phonePlayer
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Volume") },
        text = {
            Column {
                Text(
                    if (muted) "Muted" else "${(volume * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (volume > 1f && !muted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = volume.coerceAtMost(phone.maxVolume),
                    onValueChange = { PhonePlayer.setVolume(it) },
                    onValueChangeFinished = PhonePlayer::rememberVolume,
                    valueRange = 0f..phone.maxVolume
                )
                SwitchLine("Mute", muted) { PhonePlayer.setMuted(it) }
                SwitchLine("Volume boost (up to +${phone.boostMaxDb} dB)", phone.volumeBoost) { on ->
                    CastHub.settingsStore.update { it.copy(phonePlayer = it.phonePlayer.copy(volumeBoost = on)) }
                    PhonePlayer.applyVolume()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = checked, onClick = { onChange(!checked) }).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Every video quality, audio and text track of the stream. */
@OptIn(UnstableApi::class)
@Composable
internal fun TracksDialog(player: ExoPlayer, onDismiss: () -> Unit) {
    val tracks by PhonePlayer.tracks.collectAsStateWithLifecycle()
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val lenient = settings.phonePlayer.allowAllTracks
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tracks") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TrackSection("Video quality", C.TRACK_TYPE_VIDEO, tracks, player, lenient)
                TrackSection("Audio", C.TRACK_TYPE_AUDIO, tracks, player, lenient)
                TrackSection("Subtitles", C.TRACK_TYPE_TEXT, tracks, player, lenient)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun TrackSection(title: String, type: Int, tracks: Tracks, player: ExoPlayer, lenient: Boolean) {
    val groups = tracks.groups.filter { it.type == type }
    if (groups.isEmpty() && type != C.TRACK_TYPE_TEXT) return
    val params = player.trackSelectionParameters
    val disabled = type in params.disabledTrackTypes
    val overridden = params.overrides.keys.any { it.type == type }
    val entries = groups.flatMap { group ->
        (0 until group.length)
            .filter { group.isTrackSupported(it, lenient) }
            .map { i -> Triple(group, i, group.getTrackFormat(i)) }
    }.let { list -> if (type == C.TRACK_TYPE_VIDEO) list.sortedByDescending { it.third.height * 1_000_000L + it.third.bitrate.coerceAtLeast(0) } else list }
    val playing = entries.firstOrNull { (g, i, _) -> g.isTrackSelected(i) }?.let { trackLabel(type, it.third, 0) }

    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    TrackLine(
        label = if (playing != null && !disabled) "Auto ($playing)" else "Auto",
        selected = !overridden && !disabled
    ) {
        player.trackSelectionParameters = params.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, false).build()
    }
    if (type == C.TRACK_TYPE_TEXT) {
        TrackLine("Off", disabled) {
            player.trackSelectionParameters = params.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, true).build()
        }
    }
    entries.forEachIndexed { n, (group, i, format) ->
        TrackLine(trackLabel(type, format, n + 1), overridden && !disabled && group.isTrackSelected(i)) {
            player.trackSelectionParameters = params.buildUpon()
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                .build()
        }
    }
    if (entries.isEmpty() && type == C.TRACK_TYPE_TEXT) {
        Text("No subtitles in this video", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun trackLabel(type: Int, f: Format, number: Int): String {
    val language = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
        ?.let { Locale.forLanguageTag(it).displayLanguage.takeIf(String::isNotBlank) ?: it }
    val bitrate = f.bitrate.takeIf { it > 0 }?.let {
        if (it >= 1_000_000) String.format(Locale.US, "%.1f Mbps", it / 1_000_000f) else "${it / 1000} kbps"
    }
    val parts = when (type) {
        C.TRACK_TYPE_VIDEO -> listOf(f.height.takeIf { it > 0 }?.let { "${it}p" }, f.label, bitrate)
        C.TRACK_TYPE_AUDIO -> listOf(
            f.label ?: language,
            if (f.label != null) language else null,
            when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null },
            bitrate
        )
        else -> listOf(f.label ?: language, if (f.label != null) language else null)
    }.filterNotNull().distinct()
    return parts.joinToString(" · ").ifBlank { "Track $number" }
}
