package com.canasnn.tvremote.cast.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.ui.SubtitleTimingDialog
import com.canasnn.tvremote.cast.ui.formatTime
import com.ruterika.castcore.settings.SubtitleEdge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Full-screen phone player: gestures, PiP, background
 * play through [PhonePlayerService], and a one-tap hand-off to the TV.
 */
@OptIn(UnstableApi::class)
class PhonePlayerActivity : ComponentActivity() {

    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val phone = CastHub.settings.value.phonePlayer
        if (phone.rememberBrightness && phone.brightnessLevel >= 0f) {
            window.attributes = window.attributes.apply { screenBrightness = phone.brightnessLevel.coerceIn(0.01f, 1f) }
        }
        if (phone.rememberOrientation && phone.orientation != -1) requestedOrientation = phone.orientation
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val player by PhonePlayer.player.collectAsStateWithLifecycle()
                val ask by PhonePlayer.resumeAsk.collectAsStateWithLifecycle()
                player?.let { PlayerScreen(it) }
                    ?: Box(Modifier.fillMaxSize().background(Color.Black))
                ask?.let { pending ->
                    ResumeAskDialog(pending) { position ->
                        PhonePlayer.answerResume(this@PhonePlayerActivity, position)
                        // Cancelled with nothing loaded: there is nothing to show.
                        if (position == null && (PhonePlayer.player.value?.mediaItemCount ?: 0) == 0) {
                            PhonePlayer.stop(this@PhonePlayerActivity)
                            finish()
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && PhonePlayer.player.value?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // Closing the PiP window stops playback; otherwise background play continues.
        if (inPip || !CastHub.settings.value.phoneBackgroundPlay) {
            PhonePlayer.stop(this)
            finish()
        }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val size = PhonePlayer.player.value?.videoSize
        val ratio = size?.takeIf { it.width > 0 && it.height > 0 }?.let { Rational(it.width, it.height) }
            ?.takeIf { it.toFloat() in 0.42f..2.39f } ?: Rational(16, 9)
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    private fun rotate() {
        val target = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        requestedOrientation = target
        if (CastHub.settings.value.phonePlayer.rememberOrientation) {
            CastHub.settingsStore.update { it.copy(phonePlayer = it.phonePlayer.copy(orientation = target)) }
        }
    }

    private enum class Menu { MAIN, SPEED, ASPECT }

    @Composable
    private fun PlayerScreen(player: ExoPlayer) {
        val settings by CastHub.settings.collectAsStateWithLifecycle()
        val current by PhonePlayer.current.collectAsStateWithLifecycle()
        val volume by PhonePlayer.volume.collectAsStateWithLifecycle()
        val muted by PhonePlayer.muted.collectAsStateWithLifecycle()
        val speed by PhonePlayer.speed.collectAsStateWithLifecycle()
        val skipNotice by PhonePlayer.skipNotice.collectAsStateWithLifecycle()
        val phone = settings.phonePlayer
        val scope = rememberCoroutineScope()
        var locked by remember { mutableStateOf(false) }
        var controlsVisible by remember { mutableStateOf(true) }
        var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
        var menu by remember { mutableStateOf<Menu?>(null) }
        var tracksDialog by remember { mutableStateOf(false) }
        var volumeDialog by remember { mutableStateOf(false) }
        var playerView by remember { mutableStateOf<PlayerView?>(null) }
        var skipTotal by remember { mutableIntStateOf(0) }
        var skipJob by remember { mutableStateOf<Job?>(null) }
        var level by remember { mutableStateOf<Pair<String, Float>?>(null) }
        var brightness by remember { mutableFloatStateOf(window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f) }

        BackHandler {
            PhonePlayer.stop(this@PhonePlayerActivity)
            finish()
        }

        if (tracksDialog) TracksDialog(player) { tracksDialog = false }
        if (volumeDialog) VolumeDialog { volumeDialog = false }

        // Boost switched off elsewhere (settings) clamps the level back to 100 %.
        LaunchedEffect(phone.volumeBoost, phone.boostMaxDb) { PhonePlayer.applyVolume() }

        var showTiming by remember { mutableStateOf(false) }
        val subtitleOffset by PhonePlayer.subtitleOffsetMs.collectAsStateWithLifecycle()
        if (showTiming) {
            SubtitleTimingDialog(subtitleOffset, PhonePlayer::setSubtitleOffset) { showTiming = false }
        }

        fun skip(steps: Int) {
            if (!player.isCurrentMediaItemSeekable) return
            skipTotal += steps
            skipJob?.cancel()
            skipJob = scope.launch {
                delay(500)
                val total = skipTotal
                skipTotal = 0
                val delta = if (total > 0) total * settings.skipForwardSeconds * 1000L else total * settings.skipBackSeconds * 1000L
                val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + delta).coerceIn(0, duration))
            }
        }

        fun castToTv() {
            val snap = PhonePlayer.snapshot() ?: return
            scope.launch {
                val outcome = CastHub.play(snap.first, snap.second, snap.third)
                if (outcome == com.canasnn.tvremote.receiver.ReceiverManager.PlayOutcome.Started) {
                    PhonePlayer.stop(this@PhonePlayerActivity)
                    finish()
                } else {
                    Toast.makeText(this@PhonePlayerActivity, "Connect to your TV first", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // The phone can have its own subtitle style (settings), otherwise the TV one is used.
        val look = phone.subtitleLook ?: settings.subtitleLook
        LaunchedEffect(playerView, look) {
            playerView?.subtitleView?.let { view ->
                view.setApplyEmbeddedStyles(look.useFileStyles)
                view.setStyle(
                    CaptionStyleCompat(
                        look.textColor.toInt(), look.lineBackground.toInt(), look.windowBackground.toInt(),
                        when (look.edge) {
                            SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
                            SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                            SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                            SubtitleEdge.RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
                            SubtitleEdge.DEPRESSED -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
                        },
                        look.edgeColor.toInt(),
                        look.fontFamily?.let { android.graphics.Typeface.create(it, if (look.bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
                            ?: if (look.bold) android.graphics.Typeface.DEFAULT_BOLD else null
                    )
                )
                view.setFractionalTextSize(0.0533f * look.sizePercent / 100f)
            }
        }

        val density = LocalDensity.current
        val gestures = settings.phoneGestures
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        setShowNextButton(true)
                        setShowPreviousButton(true)
                        setShowSubtitleButton(true)
                        controllerShowTimeoutMs = 3500
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == android.view.View.VISIBLE })
                        playerView = this
                    }
                },
                update = { view ->
                    view.player = player
                    view.resizeMode = resizeMode
                    view.useController = !locked && !inPip
                }
            )

            // Gestures: double tap left/center/right, horizontal fling, edge swipes.
            if (!inPip) Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(locked, gestures, phone.gestureDoubleTap) {
                        detectTapGestures(
                            onTap = {
                                val view = playerView ?: return@detectTapGestures
                                if (locked) { controlsVisible = !controlsVisible; return@detectTapGestures }
                                if (view.isControllerFullyVisible) view.hideController() else view.showController()
                            },
                            onDoubleTap = { offset ->
                                if (locked || !gestures || !phone.gestureDoubleTap) return@detectTapGestures
                                val x = offset.x / size.width
                                when {
                                    x < 0.3f -> skip(-1)
                                    x > 0.7f -> skip(1)
                                    x in 0.4f..0.6f -> if (player.isPlaying) player.pause() else player.play()
                                }
                            }
                        )
                    }
                    .pointerInput(locked, gestures, phone.gestureSwipeSeek, phone.gestureSwipeBrightness, phone.gestureSwipeVolume, phone.maxVolume) {
                        if (locked || !gestures) return@pointerInput
                        var startX = 0f
                        var totalX = 0f
                        var totalY = 0f
                        var adjusted: String? = null
                        detectDragGestures(
                            onDragStart = { start -> startX = start.x / size.width; totalX = 0f; totalY = 0f; adjusted = null },
                            onDragEnd = {
                                level = null
                                when (adjusted) {
                                    "Volume" -> PhonePlayer.rememberVolume()
                                    "Brightness" -> if (CastHub.settings.value.phonePlayer.rememberBrightness) {
                                        val b = brightness
                                        CastHub.settingsStore.update { it.copy(phonePlayer = it.phonePlayer.copy(brightnessLevel = b)) }
                                    }
                                    else -> {
                                        val minFling = with(density) { 160.dp.toPx() }
                                        val maxVertical = with(density) { 100.dp.toPx() }
                                        if (phone.gestureSwipeSeek && startX in 0.15f..0.85f && abs(totalX) >= minFling && abs(totalY) <= maxVertical) {
                                            skip(if (totalX > 0) 1 else -1)
                                        }
                                    }
                                }
                            },
                            onDragCancel = { level = null }
                        ) { change, drag ->
                            totalX += drag.x
                            totalY += drag.y
                            val step = -drag.y / size.height * 1.2f
                            val vertical = abs(totalY) > abs(totalX)
                            when {
                                phone.gestureSwipeBrightness && startX < 0.15f && vertical -> {
                                    brightness = (brightness + step).coerceIn(0.01f, 1f)
                                    window.attributes = window.attributes.apply { screenBrightness = brightness }
                                    level = "Brightness" to brightness
                                    adjusted = "Brightness"
                                    change.consume()
                                }
                                phone.gestureSwipeVolume && startX > 0.85f && vertical -> {
                                    val max = phone.maxVolume
                                    PhonePlayer.setVolume(PhonePlayer.volume.value + step * max)
                                    level = "Volume" to PhonePlayer.volume.value
                                    adjusted = "Volume"
                                    change.consume()
                                }
                            }
                        }
                    }
            )

            // Skip accumulator + level indicator
            if (skipTotal != 0) {
                val seconds = if (skipTotal > 0) skipTotal * settings.skipForwardSeconds else skipTotal * settings.skipBackSeconds
                Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center)) {
                    Text((if (seconds > 0) "+" else "-") + formatTime(abs(seconds) * 1000L), color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                }
            }
            level?.let { (name, value) ->
                val max = if (name == "Volume") phone.maxVolume else 1f
                Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (name == "Volume") Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.BrightnessMedium, null, tint = Color.White)
                        Text("$name ${(value * 100).toInt()}%", color = Color.White)
                        LinearProgressIndicator(progress = { value / max }, modifier = Modifier.size(width = 140.dp, height = 4.dp).padding(top = 6.dp))
                    }
                }
            }

            // Intro/outro skip announcement with "Cancel".
            skipNotice?.let { notice ->
                if (!inPip) Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 16.dp, bottom = 96.dp)
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Skipping ${if (notice.outro) "outro" else "intro"} in ${notice.secondsLeft} s",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = PhonePlayer::cancelSkip) { Text("Cancel") }
                    }
                }
            }

            // Top bar (follows the controller), lock button always reachable.
            if (!inPip && (controlsVisible || locked)) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).safeDrawingPadding().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!locked) {
                        IconButton(onClick = { PhonePlayer.stop(this@PhonePlayerActivity); finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) }
                        Text(current?.title.orEmpty(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = ::castToTv) { Icon(Icons.Filled.Cast, "Play on TV", tint = Color.White) }
                        IconButton(onClick = { showTiming = true }) { Icon(Icons.Filled.Timer, "Subtitle timing", tint = Color.White) }
                        IconButton(onClick = { tracksDialog = true }) { Icon(Icons.Filled.Tune, "Tracks", tint = Color.White) }
                        IconButton(onClick = { volumeDialog = true }) {
                            Icon(
                                if (muted || volume == 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                "Volume",
                                tint = if (volume > 1f && !muted) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            IconButton(onClick = ::enterPip) { Icon(Icons.Filled.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                        }
                    } else {
                        Text("Locked", color = Color.White, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    }
                    IconButton(onClick = { locked = !locked; if (locked) playerView?.hideController() }) {
                        Icon(if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock, if (locked) "Unlock" else "Lock", tint = Color.White)
                    }
                    if (!locked) Box {
                        IconButton(onClick = { menu = Menu.MAIN }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                        DropdownMenu(expanded = menu != null, onDismissRequest = { menu = null }) {
                            when (menu) {
                                Menu.MAIN, null -> {
                                    DropdownMenuItem(
                                        text = { Text("Speed · ${speedLabel(speed)}") },
                                        leadingIcon = { Icon(Icons.Filled.Speed, null) },
                                        onClick = { menu = Menu.SPEED }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Aspect ratio") },
                                        leadingIcon = { Icon(Icons.Filled.AspectRatio, null) },
                                        onClick = { menu = Menu.ASPECT }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rotate screen") },
                                        leadingIcon = { Icon(Icons.Filled.ScreenRotation, null) },
                                        onClick = { menu = null; rotate() }
                                    )
                                }
                                Menu.SPEED -> PHONE_SPEEDS.forEach { value ->
                                    DropdownMenuItem(
                                        text = { Text(speedLabel(value)) },
                                        trailingIcon = { if (value == speed) Icon(Icons.Filled.Check, null) },
                                        onClick = { PhonePlayer.setSpeed(value); menu = null }
                                    )
                                }
                                Menu.ASPECT -> listOf(
                                    "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                    "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
                                    "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                                    "Fixed width" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
                                    "Fixed height" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                                ).forEach { (label, mode) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = { if (mode == resizeMode) Icon(Icons.Filled.Check, null) },
                                        onClick = { resizeMode = mode; menu = null }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
