package com.canasnn.tvremote.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.protocol.PlayerState

/** Opens the now-playing screen; provided by the nav graph. */
val LocalOpenNowPlaying = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** Small "playing on TV" bar for browser, IPTV, library and the Cast hub. */
@Composable
fun MiniController(modifier: Modifier = Modifier, onOpen: () -> Unit = LocalOpenNowPlaying.current) {
    val session by CastHub.session.collectAsStateWithLifecycle()
    val status by CastHub.status.collectAsStateWithLifecycle()
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val batteryOptimized = rememberBatteryOptimized()
    val s = session ?: return
    if (s.ended || !settings.showMiniController) return
    val current = s.current(status)
    val playing = status.state == PlayerState.PLAYING || status.state == PlayerState.BUFFERING
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    val art = current?.posterUrl ?: current?.url?.takeIf { s.isPhotos }
                    if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Filled.Cast, null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(status.title ?: current?.title ?: "Playing on TV", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (status.state) {
                            PlayerState.BUFFERING, PlayerState.LOADING -> "Buffering…"
                            PlayerState.PAUSED -> "Paused on TV"
                            PlayerState.ERROR -> "Playback error"
                            else -> if (status.isLive) "Live on TV" else "Playing on TV"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Doze warning: pulses while battery optimisation can stop playback.
                if (batteryOptimized && !settings.batteryHintDismissed && !s.isPhotos) BatteryWarningButton()
                if (!s.isPhotos) {
                    IconButton(onClick = CastHub::togglePlay) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause")
                    }
                }
                IconButton(onClick = CastHub::stop) { Icon(Icons.Filled.Stop, "Stop") }
                Icon(Icons.Filled.KeyboardArrowUp, "Open controller", modifier = Modifier.padding(end = 6.dp))
            }
            if (status.durationMs > 0 && !status.isLive) {
                LinearProgressIndicator(
                    progress = { (status.positionMs.toFloat() / status.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp)
                )
            }
        }
    }
}
