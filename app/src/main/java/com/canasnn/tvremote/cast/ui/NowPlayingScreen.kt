package com.canasnn.tvremote.cast.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.ui.app.AppBackdrop
import com.ruterika.protocol.PlayerEventType
import com.ruterika.protocol.PlayerState
import com.ruterika.protocol.RepeatMode
import com.ruterika.protocol.ResizeMode
import com.ruterika.protocol.TrackInfo
import com.ruterika.protocol.TrackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val speeds = listOf(0.5f, 0.75f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.75f, 2f)

/** Controller for what plays on our TV receiver. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    deviceName: String?,
    onBack: () -> Unit,
    onAddSubtitles: () -> Unit,
    /** The TV's volume as the remote sees it (0..1) and its mute state. */
    tvVolume: Float = 0.5f,
    tvMuted: Boolean = false,
    tvVolumeKnown: Boolean = true,
    onVolumeDown: () -> Unit = {},
    onVolumeUp: () -> Unit = {},
    onMute: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by CastHub.status.collectAsStateWithLifecycle()
    val session by CastHub.session.collectAsStateWithLifecycle()
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val pendingSkip by CastHub.pendingSkipMs.collectAsStateWithLifecycle()
    val helpTipsHidden by CastHub.helpTipsHidden.collectAsStateWithLifecycle()
    val batteryOptimized = rememberBatteryOptimized()
    val snackbar = remember { SnackbarHostState() }

    val current = session?.current(status)
    val photos = session?.isPhotos == true
    val title = status.title ?: current?.title ?: "Video on ${deviceName ?: "TV"}"
    val playing = status.state == PlayerState.PLAYING || status.state == PlayerState.BUFFERING
    val ended = session?.ended == true || (status.state == PlayerState.ENDED && status.repeatMode == RepeatMode.OFF)

    LaunchedEffect(Unit) {
        CastHub.events.collect { event ->
            val text = when (event.event) {
                PlayerEventType.INTRO_SKIPPED -> "Intro was skipped"
                PlayerEventType.OUTRO_SKIPPED -> "Outro was skipped"
                PlayerEventType.RETRYING -> "Connection problem, retrying (${event.detail})…"
                else -> null
            }
            if (text != null) snackbar.showSnackbar(text)
        }
    }

    // ── Position with smooth interpolation between 1 s status frames ─────────
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var interpolated by remember { mutableLongStateOf(status.positionMs) }
    LaunchedEffect(status.positionMs, status.state, status.speed) {
        interpolated = status.positionMs
        if (status.state == PlayerState.PLAYING) {
            val start = System.currentTimeMillis()
            while (true) {
                delay(250)
                interpolated = (status.positionMs + ((System.currentTimeMillis() - start) * status.speed).toLong())
                    .coerceAtMost(status.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
            }
        }
    }
    val duration = status.durationMs
    val shownPosition = if (dragging) (dragFraction * duration).toLong() else interpolated
    var showRemaining by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var audioInfo by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Help sheet: null = closed, otherwise the topic opened first (HelpRequest.topic may be null).
    var help by remember { mutableStateOf<HelpRequest?>(null) }
    help?.let { request -> CastHelpSheet(initial = request.topic, onDismiss = { help = null }) }

    // "Did the video play?" a few seconds into playback, once per video.
    val currentUrl = current?.url
    var askedPlayedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentUrl, status.state == PlayerState.PLAYING) {
        if (currentUrl != null && !photos && status.state == PlayerState.PLAYING && CastHub.shouldAskPlayed(currentUrl)) {
            delay(PLAYED_PROMPT_DELAY_MS)
            CastHub.markPlayedAsked(currentUrl)
            askedPlayedFor = currentUrl
        }
    }

    if (jumpDialog) JumpToMinuteDialog(duration, onDismiss = { jumpDialog = false }) { CastHub.seekTo(it); jumpDialog = false }
    if (audioInfo) {
        AlertDialog(
            onDismissRequest = { audioInfo = false },
            title = { Text("Audio tracks") },
            text = { Text("This video has a single audio track. Streams with several languages show them here once the TV has loaded them.") },
            confirmButton = { TextButton(onClick = { audioInfo = false }) { Text("OK") } }
        )
    }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Now playing", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("on ${deviceName ?: "TV"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        if (!photos) IconButton(onClick = onAddSubtitles) { Icon(Icons.Filled.Subtitles, "Subtitles") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Help & FAQ") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, null) },
                                    onClick = { menuOpen = false; help = HelpRequest(null) }
                                )
                                if (!photos && (!settings.showHelpTips || helpTipsHidden)) {
                                    DropdownMenuItem(
                                        text = { Text("Show help tips") },
                                        leadingIcon = { Icon(Icons.Filled.Lightbulb, null) },
                                        onClick = {
                                            menuOpen = false
                                            CastHub.settingsStore.update { it.copy(showHelpTips = true) }
                                            CastHub.showHelpTipsAgain()
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // Tablets: a phone-like column, centred, so the controls stay in view.
                    .wrapContentWidth()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── Artwork + state ─────────────────────────────────────────
                ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(
                            // Photos: swipe left / right for the next / previous one.
                            if (photos && settings.photoSwipe) Modifier.pointerInput(Unit) {
                                var dragX = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragX = 0f },
                                    onDragEnd = {
                                        val threshold = 48.dp.toPx()
                                        if (dragX < -threshold) CastHub.next() else if (dragX > threshold) CastHub.previous()
                                    }
                                ) { _, delta -> dragX += delta }
                            } else Modifier
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxSize()) {}
                        val art = current?.posterUrl ?: current?.url?.takeIf { photos }
                        if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Filled.Movie, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        if (status.state == PlayerState.BUFFERING || status.state == PlayerState.LOADING || (session != null && !status.playerVisible && !ended)) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                        StateChip(status.state, status.isLive, Modifier.align(Alignment.TopStart).padding(12.dp))
                        if (pendingSkip != 0L) Box(Modifier.align(Alignment.Center)) {
                            Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.7f)) {
                                Text(
                                    (if (pendingSkip > 0) "+" else "-") + formatTime(kotlin.math.abs(pendingSkip)),
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (status.itemCount > 1) {
                    Text("${status.itemIndex + 1} of ${status.itemCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // ── Hints: battery optimisation, "did it play?" ─────────────
                if (batteryOptimized && !settings.batteryHintDismissed && !photos) BatteryHintCard()
                if (askedPlayedFor != null && askedPlayedFor == currentUrl && status.state != PlayerState.ERROR) {
                    VideoPlayedCard(
                        onYes = { askedPlayedFor = null },
                        onNo = { askedPlayedFor = null; help = HelpRequest(CastHelpTopic.VIDEO_NOT_PLAYING) },
                        onNeverAsk = { askedPlayedFor = null; CastHub.settingsStore.update { it.copy(askVideoPlayed = false) } }
                    )
                }

                // ── Error / ended banners ───────────────────────────────────
                if (status.state == PlayerState.ERROR) {
                    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                                Text("Video failed to play", style = MaterialTheme.typography.titleSmall)
                            }
                            Text(status.errorMessage ?: status.errorCode ?: "Unknown error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = CastHub::retry) { Icon(Icons.Filled.Refresh, null); Text(" Retry") }
                                current?.let { media ->
                                    TextButton(onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(media.url), media.mimeType ?: "video/*"))
                                        }
                                    }) { Icon(Icons.Filled.Smartphone, null); Text(" Play on phone") }
                                }
                            }
                        }
                    }
                }
                if (ended) {
                    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Playback finished", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val s = session ?: return@TextButton
                                scope.launch { CastHub.play(s.items, s.startIndex, 0L) }
                            }) { Icon(Icons.Filled.Replay, null); Text(" Replay") }
                            TextButton(onClick = { CastHub.clearEnded(); onBack() }) { Text("Close") }
                        }
                    }
                }

                // ── Seek bar ────────────────────────────────────────────────
                if (!photos && duration > 0 && status.seekable) {
                    Column {
                        Box {
                            Slider(
                                value = if (dragging) dragFraction else (shownPosition.toFloat() / duration).coerceIn(0f, 1f),
                                onValueChange = { dragging = true; dragFraction = it },
                                onValueChangeFinished = {
                                    CastHub.seekTo((dragFraction * duration).toLong())
                                    interpolated = (dragFraction * duration).toLong()
                                    dragging = false
                                }
                            )
                            // Scrub bubble: the time under the finger, above the thumb.
                            if (dragging) {
                                SeekBubble(dragFraction, formatTime(shownPosition), Modifier.align(Alignment.TopStart).offset(y = (-34).dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                formatTime(shownPosition),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.clickable(enabled = duration >= 60_000) { jumpDialog = true }
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (showRemaining) "-" + formatTime(duration - shownPosition) else formatTime(duration),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clickable { showRemaining = !showRemaining }
                            )
                        }
                    }
                } else if (status.isLive) {
                    Text("Live stream", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }

                // ── Transport ───────────────────────────────────────────────
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    if (status.itemCount > 1) {
                        IconButton(onClick = CastHub::previous) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(30.dp)) }
                    }
                    if (!photos && status.seekable) {
                        FilledTonalIconButton(onClick = CastHub::skipBack, modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.FastRewind, "Back ${settings.skipBackSeconds} seconds")
                                Text("${settings.skipBackSeconds}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (!photos || status.itemCount > 1) {
                        FilledIconButton(onClick = CastHub::togglePlay, modifier = Modifier.size(68.dp)) {
                            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause", Modifier.size(36.dp))
                        }
                    }
                    if (!photos && status.seekable) {
                        FilledTonalIconButton(onClick = CastHub::skipForward, modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.FastForward, "Forward ${settings.skipForwardSeconds} seconds")
                                Text("${settings.skipForwardSeconds}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (status.itemCount > 1) {
                        IconButton(onClick = CastHub::next) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(30.dp)) }
                    }
                }

                // ── Stop / continue on phone / repeat (centred) ───────────────────────────────────────────────
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
                    FilledTonalIconButton(onClick = { CastHub.stop(); onBack() }) { Icon(Icons.Filled.Stop, "Stop") }
                    if (!photos) {
                        FilledTonalIconButton(onClick = {
                            val s = session ?: return@FilledTonalIconButton
                            com.canasnn.tvremote.cast.player.PhonePlayer.play(
                                context, s.items, status.itemIndex.coerceIn(0, s.items.lastIndex), if (status.isLive) 0L else status.positionMs
                            )
                            CastHub.stop()
                            onBack()
                        }) { Icon(Icons.Filled.Smartphone, "Continue on phone") }
                    }
                    if (!photos) {
                        FilledTonalIconButton(onClick = CastHub::cycleRepeat) {
                            Icon(
                                if (status.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                "Repeat",
                                tint = if (status.repeatMode == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // TV volume: the remote's keys and level, so both screens stay in sync.
                com.canasnn.tvremote.ui.app.TvVolumeBar(
                    level = tvVolume,
                    muted = tvMuted,
                    known = tvVolumeKnown,
                    onVolumeDown = onVolumeDown,
                    onVolumeUp = onVolumeUp,
                    onMute = onMute,
                    modifier = Modifier.fillMaxWidth()
                )

                if (photos) {
                    ControlCard(Icons.Filled.Slideshow, "Slideshow") {
                        ChipRowNp(listOf(0, 3, 5, 10, 20, 60), settings.slideshowSeconds, { if (it == 0) "Manual" else "${it}s" }) { CastHub.setSlideshow(it) }
                    }
                    PictureCard(status.rotationDeg, status.zoom, status.resizeMode, showResize = false)
                    return@Column
                }

                // ── Speed ───────────────────────────────────────────────────
                ControlCard(Icons.Filled.Speed, "Speed") {
                    ChipRowNp(speeds, speeds.minByOrNull { kotlin.math.abs(it - status.speed) } ?: 1f, { if (it == 1f) "Normal" else "${it}x" }) { CastHub.setSpeed(it) }
                }

                // ── Tracks ──────────────────────────────────────────────────
                val audio = status.tracks.filter { it.type == TrackType.AUDIO }
                val text = status.tracks.filter { it.type == TrackType.TEXT }
                val video = status.tracks.filter { it.type == TrackType.VIDEO }
                ControlCard(Icons.Filled.Audiotrack, "Audio" + if (audio.size > 1) " (${audio.size})" else "") {
                    if (audio.size > 1) TrackChips(audio, TrackType.AUDIO, offLabel = null)
                    else AssistChip(onClick = { audioInfo = true }, label = { Text(audio.firstOrNull()?.label ?: "Default track") })
                }
                ControlCard(Icons.Filled.ClosedCaption, "Subtitles") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = text.none { it.selected },
                            onClick = { CastHub.selectTrack(TrackType.TEXT, null) },
                            leadingIcon = { Icon(Icons.Filled.SubtitlesOff, null, Modifier.size(18.dp)) },
                            label = { Text("Off") }
                        )
                        text.forEach { t ->
                            FilterChip(selected = t.selected, onClick = { CastHub.selectTrack(TrackType.TEXT, t.id) }, label = { Text(t.label) })
                        }
                        AssistChip(onClick = onAddSubtitles, label = { Text("Add / timing…") })
                    }
                }
                if (video.size > 1) {
                    ControlCard(Icons.Filled.HighQuality, "Quality") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = false, onClick = { CastHub.selectTrack(TrackType.VIDEO, null) }, label = { Text("Auto") })
                            video.sortedByDescending { it.label.substringBefore('p').toIntOrNull() ?: 0 }.forEach { t ->
                                FilterChip(selected = t.selected, onClick = { CastHub.selectTrack(TrackType.VIDEO, t.id) }, label = { Text(t.label) })
                            }
                        }
                    }
                }
                PictureCard(status.rotationDeg, status.zoom, status.resizeMode, showResize = true)
                if (settings.showHelpTips && !helpTipsHidden) {
                    HelpTipsBanner(
                        buffering = status.state == PlayerState.BUFFERING,
                        batteryOptimized = batteryOptimized,
                        onHelp = { help = HelpRequest(it) }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Open request for the help sheet; [topic] starts expanded (null = the list). */
private class HelpRequest(val topic: CastHelpTopic?)

/** Time label that follows the slider thumb, kept inside the row. */
@Composable
private fun SeekBubble(fraction: Float, text: String, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 4.dp
            ) {
                Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    ) { measurables, constraints ->
        val bubble = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        // The thumb travels between half a thumb width from each edge.
        val inset = 10.dp.roundToPx()
        val center = inset + (width - 2 * inset) * fraction.coerceIn(0f, 1f)
        val x = (center - bubble.width / 2f).toInt().coerceIn(0, (width - bubble.width).coerceAtLeast(0))
        layout(width, bubble.height) { bubble.place(x, 0) }
    }
}

@Composable
private fun TrackChips(tracks: List<TrackInfo>, type: TrackType, offLabel: String?) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offLabel != null) FilterChip(selected = tracks.none { it.selected }, onClick = { CastHub.selectTrack(type, null) }, label = { Text(offLabel) })
        tracks.forEach { t -> FilterChip(selected = t.selected, onClick = { CastHub.selectTrack(type, t.id) }, label = { Text(t.label) }) }
    }
}

@Composable
private fun PictureCard(rotation: Int, zoom: Float, resize: ResizeMode, showResize: Boolean) {
    ControlCard(Icons.Filled.AspectRatio, "Picture") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showResize) {
                ChipRowNp(ResizeMode.entries, resize, {
                    when (it) {
                        ResizeMode.FIT -> "Fit"
                        ResizeMode.FILL -> "Stretch"
                        ResizeMode.ZOOM -> "Crop"
                        ResizeMode.FIXED_WIDTH -> "Fit width"
                        ResizeMode.FIXED_HEIGHT -> "Fit height"
                    }
                }) { CastHub.setResizeMode(it) }
            }
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { CastHub.setVideoTransform(rotation - 90, zoom) }) { Icon(Icons.Filled.RotateLeft, "Rotate left") }
                IconButton(onClick = { CastHub.setVideoTransform(rotation + 90, zoom) }) { Icon(Icons.Filled.RotateRight, "Rotate right") }
                IconButton(onClick = { CastHub.setVideoTransform(rotation, (zoom - 0.1f).coerceAtLeast(0.5f)) }) { Icon(Icons.Filled.ZoomOut, "Zoom out") }
                IconButton(onClick = { CastHub.setVideoTransform(rotation, (zoom + 0.1f).coerceAtMost(3f)) }) { Icon(Icons.Filled.ZoomIn, "Zoom in") }
                Text("${rotation}° · ${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                TextButton(onClick = { CastHub.setVideoTransform(0, 1f) }) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun ControlCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            }
            content()
        }
    }
}

@Composable
private fun <T> ChipRowNp(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun StateChip(state: PlayerState, live: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    // Container/content pairs from the theme, so the label reads in light, dark and AMOLED.
    val (text, colors) = when {
        state == PlayerState.ERROR -> "Error" to (cs.errorContainer to cs.onErrorContainer)
        state == PlayerState.BUFFERING || state == PlayerState.LOADING -> "Buffering" to (cs.tertiaryContainer to cs.onTertiaryContainer)
        live && state == PlayerState.PLAYING -> "LIVE" to (Color(0xFFD32F2F) to Color.White)
        state == PlayerState.PAUSED -> "Paused" to (cs.secondaryContainer to cs.onSecondaryContainer)
        state == PlayerState.ENDED -> "Finished" to (cs.secondaryContainer to cs.onSecondaryContainer)
        state == PlayerState.PLAYING -> "Playing" to (cs.primaryContainer to cs.onPrimaryContainer)
        else -> return
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = colors.first, contentColor = colors.second) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun JumpToMinuteDialog(durationMs: Long, onDismiss: () -> Unit, onJump: (Long) -> Unit) {
    val maxMinutes = durationMs / 60_000
    var input by remember { mutableStateOf("") }
    val minutes = input.toLongOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to minute") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter(Char::isDigit).take(4) },
                label = { Text("0 – $maxMinutes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = {
            TextButton(enabled = minutes != null && minutes <= maxMinutes, onClick = { onJump((minutes ?: 0) * 60_000) }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private const val PLAYED_PROMPT_DELAY_MS = 8_000L

internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
