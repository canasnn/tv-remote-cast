package com.canasnn.tvremote.ui.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The remote's "water" volume control laid out horizontally: − and + send the
 * TV's own volume keys (same as the remote), the level is the TV's reported
 * volume, the middle toggles mute.
 */
@Composable
fun TvVolumeBar(
    level: Float,
    muted: Boolean,
    /** False until the TV has reported its volume: no percentage is shown then. */
    known: Boolean = true,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .height(56.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), shape)
    ) {
        HorizontalFluid(level, muted)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(64.dp).fillMaxHeight().clickable(onClick = onVolumeDown), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Remove, "Volume down", tint = MaterialTheme.colorScheme.onSurface)
            }
            Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onMute), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (muted) "Unmute" else "Mute",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        if (muted) "  Muted" else if (!known) "" else "  ${(level.coerceIn(0f, 1f) * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Box(Modifier.width(64.dp).fillMaxHeight().clickable(onClick = onVolumeUp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, "Volume up", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Same water as the remote's volume rocker, filling from left to right. */
@Composable
private fun HorizontalFluid(fraction: Float, muted: Boolean) {
    val fill by animateFloatAsState(
        targetValue = if (muted) 0f else fraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 350f),
        label = "tvVolumeFill"
    )
    val phase by rememberInfiniteTransition(label = "tvVolumeWave").animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "tvVolumePhase"
    )
    val primary = MaterialTheme.colorScheme.primaryContainer
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val edge = w * fill
        val amp = 5.dp.toPx() * if (fill > 0.03f && fill < 0.97f) 1f else 0.3f

        fun wave(shift: Float, scale: Float) = Path().apply {
            moveTo(0f, 0f)
            lineTo(edge, 0f)
            var y = 0f
            while (y <= h) {
                lineTo(edge + sin((y / h) * 2f * Math.PI.toFloat() + phase + shift) * amp * scale, y)
                y += 3f
            }
            lineTo(0f, h)
            close()
        }

        drawPath(
            wave(1.2f, 0.7f),
            Brush.horizontalGradient(listOf(primary.copy(alpha = 0.55f), secondary.copy(alpha = 0.40f)), endX = (edge + amp).coerceAtLeast(1f))
        )
        drawPath(
            wave(0f, 1f),
            Brush.horizontalGradient(listOf(primary.copy(alpha = 0.90f), primary.copy(alpha = 0.65f)), endX = (edge + amp).coerceAtLeast(1f))
        )
    }
}
