package com.canasnn.tvremote.cast.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.canasnn.tvremote.cast.BatteryOptimization
import com.canasnn.tvremote.cast.CastHub
import kotlinx.coroutines.delay

// ── Help / FAQ ──────────────────────────────────────────────────────────────

/** Short in-app answers to the usual casting problems. */
enum class CastHelpTopic(val title: String, val icon: ImageVector, val points: List<String>) {
    TV_NOT_FOUND(
        "TV not found", Icons.Filled.Tv, listOf(
            "Phone and TV must be on the same Wi-Fi network. Guest networks and routers with \"AP isolation\" keep devices apart.",
            "Turn off VPN, and mobile data if the phone keeps leaving Wi-Fi.",
            "Turn the TV on. A TV in deep standby may need its own remote once.",
            "On the Remote tab, pick the TV and pair it with the code shown on the TV.",
            "Install the receiver app on the TV. The first cast opens it through the remote."
        )
    ),
    VIDEO_NOT_PLAYING(
        "Video doesn't play", Icons.Filled.VideocamOff, listOf(
            "Start the video on the web page first, then cast it from the video list. Some links expire within minutes.",
            "Try another entry in the video list, or another quality.",
            "Videos that need DRM or a paid app can't be cast.",
            "Use \"Play on phone\" to check that the link works at all.",
            "Phone files stop when Android suspends the app. Allow the app to ignore battery optimisation."
        )
    ),
    NO_SOUND(
        "No sound", Icons.AutoMirrored.Filled.VolumeOff, listOf(
            "Check the TV volume and mute in the controller, or use the remote.",
            "Pick another audio track under Audio in the controller.",
            "Some TVs can't decode every audio format (for example DTS). Try another source or quality."
        )
    ),
    SUBTITLES(
        "Subtitles", Icons.Filled.Subtitles, listOf(
            "Tap Subtitles to add a file or a link, or to search OpenSubtitles.",
            "Strange characters: change Character encoding in Settings → Subtitles.",
            "Out of sync: use the timing controls on the subtitle screen.",
            "Size, colour and position are under subtitle style in the settings."
        )
    ),
    BUFFERING(
        "Video stops to buffer", Icons.Filled.HourglassBottom, listOf(
            "Choose a lower quality under Quality, if the stream offers one.",
            "Move the phone or the TV closer to the router, or use 5 GHz Wi-Fi.",
            "Phone files are served by the phone: keep it on Wi-Fi and awake."
        )
    ),
    SKIPPING(
        "Can't skip or seek", Icons.Filled.FastForward, listOf(
            "Live streams can't be seeked, so the seek bar and skip buttons are hidden.",
            "Some servers don't allow jumping inside the file. Try another source."
        )
    ),
    BATTERY(
        "Playback stops when the screen is off", Icons.Filled.BatteryAlert, listOf(
            "Battery optimisation can suspend the app while the screen is off.",
            "Allow the app to ignore battery optimisation so phone files, subtitles and the controls keep working."
        )
    )
}

/** Help sheet with every topic; [initial] starts expanded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastHelpSheet(initial: CastHelpTopic?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
                Text("Help with casting", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
            CastHelpTopic.entries.forEach { topic ->
                val open = expanded == topic
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = if (open) null else topic }.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(topic.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(topic.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 16.dp))
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
                if (open) {
                    Column(Modifier.padding(start = 64.dp, end = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        topic.points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (topic == CastHelpTopic.BATTERY || topic == CastHelpTopic.VIDEO_NOT_PLAYING) {
                            if (BatteryOptimization.isOptimized(context)) {
                                TextButton(onClick = { BatteryOptimization.requestExemption(context) }) { Text("Allow background use") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Battery optimisation ────────────────────────────────────────────────────

/** True while the app is battery-optimised; re-checked whenever the screen resumes. */
@Composable
fun rememberBatteryOptimized(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var optimized by remember { mutableStateOf(BatteryOptimization.isOptimized(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) optimized = BatteryOptimization.isOptimized(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return optimized
}

/** Controller card: battery optimisation is on, with "Allow" and a close button. */
@Composable
fun BatteryHintCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BatteryAlert, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Battery optimisation is on", style = MaterialTheme.typography.titleSmall)
                Text("Android may stop casting when the screen turns off.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { BatteryOptimization.requestExemption(context) }) { Text("Allow", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold) }
            IconButton(onClick = { CastHub.settingsStore.update { it.copy(batteryHintDismissed = true) } }) { Icon(Icons.Filled.Close, "Hide") }
        }
    }
}

/** Mini controller icon: pulses between the accent and red warning. */
@Composable
fun BatteryWarningButton() {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf(false) }
    val transition = rememberInfiniteTransition(label = "battery")
    val tint by transition.animateColor(
        initialValue = MaterialTheme.colorScheme.primary,
        targetValue = MaterialTheme.colorScheme.error,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "batteryTint"
    )
    IconButton(onClick = { dialog = true }) { Icon(Icons.Filled.BatteryAlert, "Battery optimisation is on", tint = tint) }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            icon = { Icon(Icons.Filled.BatteryAlert, null) },
            title = { Text("Battery optimisation") },
            text = { Text("Battery optimisations are on for this app. Android may stop casting, phone files and subtitles when the screen turns off.") },
            confirmButton = { TextButton(onClick = { dialog = false; BatteryOptimization.requestExemption(context) }) { Text("Allow") } },
            dismissButton = {
                TextButton(onClick = { dialog = false; CastHub.settingsStore.update { it.copy(batteryHintDismissed = true) } }) { Text("Don't show again") }
            }
        )
    }
}

// ── Help tips banner ────────────────────────────────────────────────────────

private data class HelpTip(val title: String, val text: String, val topic: CastHelpTopic?)

private val generalTips = listOf(
    HelpTip("Having issues?", "Quick fixes for the most common casting problems.", null),
    HelpTip("Video failed to start?", "Some links expire or only play on the page.", CastHelpTopic.VIDEO_NOT_PLAYING),
    HelpTip("Can't hear anything?", "Check the TV volume or pick another audio track.", CastHelpTopic.NO_SOUND),
    HelpTip("Subtitles not right?", "Fix encoding, timing and style.", CastHelpTopic.SUBTITLES),
    HelpTip("Unable to skip?", "Live streams and some servers don't allow seeking.", CastHelpTopic.SKIPPING)
)

/**
 * Help tips: one card at a time (buffering gets its own), a help button,
 * next tip, and a close button asking "hide this time / never show".
 */
@Composable
fun HelpTipsBanner(buffering: Boolean, batteryOptimized: Boolean, onHelp: (CastHelpTopic?) -> Unit) {
    val tips = remember(buffering, batteryOptimized) {
        when {
            buffering -> listOf(HelpTip("The TV is buffering", "Slow Wi-Fi or a high quality makes the video stop and wait.", CastHelpTopic.BUFFERING))
            batteryOptimized -> generalTips + HelpTip("Video ending abruptly?", "Battery optimisation can stop casting.", CastHelpTopic.BATTERY)
            else -> generalTips
        }
    }
    var index by remember(tips) { mutableIntStateOf(0) }
    var askClose by remember { mutableStateOf(false) }
    LaunchedEffect(index, tips) {
        if (tips.size > 1) {
            delay(TIP_ROTATE_MS)
            index = (index + 1) % tips.size
        }
    }
    val tip = tips[index % tips.size]
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lightbulb, null, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(tip.title, style = MaterialTheme.typography.titleSmall)
                Text(tip.text, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onHelp(tip.topic) }) { Text("Help", color = MaterialTheme.colorScheme.onSecondaryContainer) }
            if (tips.size > 1) IconButton(onClick = { index = (index + 1) % tips.size }) { Icon(Icons.Filled.ChevronRight, "Next tip") }
            IconButton(onClick = { askClose = true }) { Icon(Icons.Filled.Close, "Close tips") }
        }
    }
    if (askClose) {
        AlertDialog(
            onDismissRequest = { askClose = false },
            title = { Text("Don't show help tips?") },
            text = { Text("You can turn them back on in Settings → Casting, or from the controller menu.") },
            confirmButton = {
                TextButton(onClick = { askClose = false; CastHub.settingsStore.update { it.copy(showHelpTips = false) } }) { Text("Never show") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askClose = false }) { Text("Cancel") }
                    TextButton(onClick = { askClose = false; CastHub.hideHelpTipsForNow() }) { Text("Hide this time") }
                }
            }
        )
    }
}

// ── "Did the video play?" ───────────────────────────────────────────────────

/** "Video playback — Did the video play correctly?" as an inline card. */
@Composable
fun VideoPlayedCard(onYes: () -> Unit, onNo: () -> Unit, onNeverAsk: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Did the video play correctly?", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNeverAsk) { Text("Don't ask again", color = MaterialTheme.colorScheme.onTertiaryContainer) }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = onNo) { Text("No", color = MaterialTheme.colorScheme.onTertiaryContainer) }
                FilledTonalButton(onClick = onYes) { Text("Yes") }
            }
        }
    }
}

private const val TIP_ROTATE_MS = 10_000L
