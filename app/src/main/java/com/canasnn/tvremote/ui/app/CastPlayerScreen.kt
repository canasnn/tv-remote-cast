package com.canasnn.tvremote.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastPlayerScreen(
    mediaItem: MediaItemUi?,
    deviceName: String?,
    castState: CastPlaybackUiState,
    volumeFraction: Float = 0.5f,
    isMuted: Boolean = false,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onStopCasting: () -> Unit,
    onVolumeChanged: (Float) -> Unit = {},
    onToggleMute: () -> Unit = {},
    albumItems: List<MediaItemUi> = emptyList(),
    onCastOther: ((MediaItemUi) -> Unit)? = null,
    receiverControls: com.canasnn.tvremote.cast.ui.ReceiverControls? = null
) {
    var progress by rememberSaveable { mutableFloatStateOf(castState.progressFraction) }
    LaunchedEffect(castState.progressFraction) { progress = castState.progressFraction }

    var isStopping by remember { mutableStateOf(false) }

    LaunchedEffect(isStopping, castState.isCasting) {
        if (isStopping && !castState.isCasting) {
            kotlinx.coroutines.delay(350)
            isStopping = false
            onBack()
        }
    }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Cast,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = deviceName ?: "Cast Player",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            if (mediaItem == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            "No media selected",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@Scaffold
            }

            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
            // Short screens (small phones, landscape, large fonts) scroll with a fixed-height cover.
            val roomy = maxHeight >= 600.dp
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .then(if (roomy) Modifier.fillMaxHeight() else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Media artwork / cover ─────────────────────────────────────
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (roomy) Modifier.weight(1f) else Modifier.height(200.dp)),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (mediaItem.kind) {
                            MediaKind.Photo -> AsyncImage(
                                model = mediaItem.uri,
                                contentDescription = mediaItem.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            MediaKind.Video, MediaKind.Audio -> {
                                // Gradient background
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.radialGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                                    MaterialTheme.colorScheme.tertiaryContainer
                                                )
                                            )
                                        )
                                )
                                // Try real thumbnail
                                if (mediaItem.thumbnailUri != null) {
                                    MediaArtwork(
                                        model = mediaItem.thumbnailUri,
                                        contentDescription = mediaItem.title,
                                        contentScale = ContentScale.Crop,
                                        isVideo = mediaItem.kind == MediaKind.Video,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Tint overlay
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.25f))
                                    )
                                }
                                // Center icon
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(96.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = mediaItem.kind.placeholderIcon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(52.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // ── Wavy Progress Bar overlay until cast starts successfully or stopping ─────
                        val showProgress = !castState.isCasting || castState.isBusy || isStopping
                        if (showProgress) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    CircularWavyProgressBar(
                                        modifier = Modifier.size(72.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 6.dp,
                                        waveCount = 8,
                                        waveAmplitude = 3.dp
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                    ) {
                                        Text(
                                            text = when {
                                                isStopping -> "Stopping cast..."
                                                castState.isBusy -> "Connecting to TV..."
                                                else -> "Preparing stream..."
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Casting status badge
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            shape = RoundedCornerShape(50),
                            color = if (castState.isCasting)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Cast,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (castState.isCasting) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = when {
                                        castState.isCasting && castState.isPlaying -> "Live"
                                        castState.isCasting -> "Paused"
                                        else -> "Stopped"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (castState.isCasting) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ── Controls card ─────────────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Title + subtitle
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = mediaItem.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (mediaItem.subtitle.isNotBlank()) {
                                Text(
                                    text = mediaItem.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Seek slider (audio + video when duration is known)
                        if (mediaItem.kind != MediaKind.Photo && castState.durationMs > 0) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Slider(
                                    value = progress,
                                    onValueChange = { progress = it },
                                    onValueChangeFinished = { onSeekTo(progress) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        formatCastDuration(castState.positionMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        formatCastDuration(castState.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (mediaItem.kind != MediaKind.Photo && castState.durationMs <= 0L && castState.isCasting) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                strokeCap = StrokeCap.Round,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }

                        // Live TV Volume Slider for Audio & Video
                        if (mediaItem.kind != MediaKind.Photo) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = onToggleMute,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = if (isMuted) "Unmute" else "Mute",
                                        tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }
                                var currentVolFraction by remember(volumeFraction) { mutableFloatStateOf(volumeFraction) }
                                Slider(
                                    value = currentVolFraction,
                                    onValueChange = { currentVolFraction = it },
                                    onValueChangeFinished = { onVolumeChanged(currentVolFraction) },
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${(currentVolFraction * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Playback controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Stop
                            FilledTonalIconButton(
                                onClick = {
                                    isStopping = true
                                    onStopCasting()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = "Stop",
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Skip back (receiver)
                            if (receiverControls != null && mediaItem.kind != MediaKind.Photo) {
                                Spacer(Modifier.width(16.dp))
                                FilledTonalIconButton(
                                    onClick = receiverControls.onSkipBack,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Replay10,
                                        contentDescription = "Back ${receiverControls.skipBackSeconds} seconds",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            // Play/Pause (non-photo)
                            if (mediaItem.kind != MediaKind.Photo) {
                                Spacer(Modifier.width(16.dp))
                                FilledIconButton(
                                    onClick = onTogglePlayback,
                                    modifier = Modifier.size(58.dp)
                                ) {
                                    Icon(
                                        imageVector = if (castState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }

                            // Skip forward (receiver)
                            if (receiverControls != null && mediaItem.kind != MediaKind.Photo) {
                                Spacer(Modifier.width(16.dp))
                                FilledTonalIconButton(
                                    onClick = receiverControls.onSkipForward,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Forward10,
                                        contentDescription = "Forward ${receiverControls.skipForwardSeconds} seconds",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (receiverControls != null && mediaItem.kind != MediaKind.Photo) {
                    com.canasnn.tvremote.cast.ui.ReceiverPlaybackExtras(receiverControls)
                }

                // ── Photo album horizontal strip ───────────────────────────────
                if (mediaItem.kind == MediaKind.Photo && albumItems.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "From this album",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(end = 8.dp)
                        ) {
                            items(albumItems, key = { it.id }) { photo ->
                                val isActive = photo.id == mediaItem.id
                                AsyncImage(
                                    model = photo.thumbnailUri ?: photo.uri,
                                    contentDescription = photo.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .then(
                                            if (isActive)
                                                Modifier.border(
                                                    width = 2.5.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                            else Modifier
                                        )
                                        .then(
                                            if (onCastOther != null && !isActive)
                                                Modifier.clickable { onCastOther(photo) }
                                            else Modifier
                                        )
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
            }
        }
    }
}

private fun formatCastDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun CircularWavyProgressBar(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 6.dp,
    waveCount: Int = 8,
    waveAmplitude: Dp = 3.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wavyProgress")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavyRotation"
    )
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavyPhase"
    )

    Canvas(modifier = modifier.graphicsLayer { rotationZ = rotation }) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = (diameter - strokeWidth.toPx() - (waveAmplitude.toPx() * 2f)) / 2f
        val strokePx = strokeWidth.toPx()
        val ampPx = waveAmplitude.toPx()

        val path = Path()
        val steps = 360
        for (i in 0..steps) {
            val angleRad = Math.toRadians(i.toDouble()).toFloat()
            val r = baseRadius + (sin((angleRad * waveCount) + wavePhase) * ampPx)
            val x = center.x + (r * cos(angleRad))
            val y = center.y + (r * sin(angleRad))
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokePx,
                cap = StrokeCap.Round
            )
        )
    }
}
