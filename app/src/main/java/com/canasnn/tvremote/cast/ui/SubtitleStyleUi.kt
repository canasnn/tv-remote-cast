package com.canasnn.tvremote.cast.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.subtitles.OpenSubtitlesAccount
import com.ruterika.castcore.settings.SubtitleBackground
import com.ruterika.castcore.settings.SubtitleEdge
import com.ruterika.castcore.settings.SubtitleLook
import com.ruterika.castcore.subtitles.OpenSubtitlesException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The 14 subtitle colours. */
internal val subtitlePalette = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF607D8B, 0xFF795548, 0xFFE51C23, 0xFFFF9800, 0xFFFFEB3B,
    0xFF259B24, 0xFF009688, 0xFF00BCD4, 0xFF03A9F4, 0xFF3F51B5, 0xFF9C27B0, 0xFFE91E63
)

/** Font choices: label → Android font family (null = player default). */
internal val subtitleFonts: List<Pair<String, String?>> = listOf(
    "Default" to null,
    "Sans Serif" to "sans-serif",
    "Serif" to "serif",
    "Monospaced Sans Serif" to "monospace",
    "Monospaced Serif" to "serif-monospace",
    "Casual" to "casual",
    "Cursive" to "cursive",
    "Small caps" to "sans-serif-smallcaps",
    "Condensed" to "sans-serif-condensed",
    "Medium" to "sans-serif-medium"
)

private val sizeSteps = listOf(60, 80, 100, 125, 150, 200)

/**
 * The subtitle look used on the TV and in the phone player, with a live
 * preview. Shared by the settings screen and the subtitle screen.
 */
@Composable
fun SubtitleStyleEditor(look: SubtitleLook, onChange: (SubtitleLook) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubtitlePreview(look)

        LabeledSwitch(
            "Use styles from the file",
            "ASS/VTT files keep their own colours and positions; the options below apply to the rest",
            look.useFileStyles
        ) { onChange(look.copy(useFileStyles = it)) }

        StyleLabel("Vertical position: ${look.verticalPosition}")
        var position by remember(look.verticalPosition) { mutableFloatStateOf(look.verticalPosition.toFloat()) }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onChange(look.copy(verticalPosition = position.roundToInt())) },
            valueRange = 0f..100f
        )

        StyleLabel("Text size")
        ChoiceChips(sizeSteps, look.sizePercent, { "$it%" }) { onChange(look.copy(sizePercent = it)) }

        StyleLabel("Text color")
        Palette(look.textColor) { onChange(look.copy(textColor = it)) }

        StyleLabel("Font")
        ChoiceChips(subtitleFonts, subtitleFonts.firstOrNull { it.second == look.fontFamily } ?: subtitleFonts.first(), { it.first }) {
            onChange(look.copy(fontFamily = it.second))
        }
        LabeledSwitch("Bold", null, look.bold) { onChange(look.copy(bold = it)) }

        StyleLabel("Edge")
        ChoiceChips(SubtitleEdge.entries, look.edge, { it.label }) { onChange(look.copy(edge = it)) }
        if (look.edge != SubtitleEdge.NONE) {
            StyleLabel("Edge color")
            Palette(look.edgeColor) { onChange(look.copy(edgeColor = it)) }
        }

        StyleLabel("Background style")
        ChoiceChips(SubtitleBackground.entries, look.backgroundStyle, { it.label }) { onChange(look.copy(backgroundStyle = it)) }
        if (look.backgroundStyle != SubtitleBackground.NONE) {
            val alpha = (look.backgroundColor ushr 24) and 0xFF
            StyleLabel("Background color")
            Palette(look.backgroundColor or 0xFF000000) { rgb ->
                // A fully transparent background becomes visible when a colour is picked.
                val a = if (alpha == 0L) 0x80L else alpha
                onChange(look.copy(backgroundColor = (a shl 24) or (rgb and 0xFFFFFF)))
            }
            var opacity by remember(look.backgroundColor) { mutableFloatStateOf(alpha * 100f / 255f) }
            StyleLabel("Background opacity: ${opacity.roundToInt()}%")
            Slider(
                value = opacity,
                onValueChange = { opacity = it },
                onValueChangeFinished = {
                    val a = (opacity / 100f * 255f).roundToInt().toLong().coerceIn(0, 255)
                    onChange(look.copy(backgroundColor = (a shl 24) or (look.backgroundColor and 0xFFFFFF)))
                },
                valueRange = 0f..100f
            )
        }
        TextButton(onClick = { onChange(SubtitleLook()) }) { Text("Reset style") }
    }
}

/** A TV-shaped preview of the current look. */
@Composable
private fun SubtitlePreview(look: SubtitleLook) {
    val family = when (look.fontFamily) {
        "serif", "serif-monospace" -> if (look.fontFamily == "serif") FontFamily.Serif else FontFamily.Monospace
        "monospace" -> FontFamily.Monospace
        "cursive", "casual" -> FontFamily.Cursive
        null -> FontFamily.Default
        else -> FontFamily.SansSerif
    }
    val textColor = Color(look.textColor.toInt())
    val edgeColor = Color(look.edgeColor.toInt())
    val shadow = when (look.edge) {
        SubtitleEdge.NONE -> null
        SubtitleEdge.OUTLINE -> Shadow(edgeColor, blurRadius = 4f)
        SubtitleEdge.DROP_SHADOW -> Shadow(edgeColor, offset = androidx.compose.ui.geometry.Offset(3f, 3f), blurRadius = 3f)
        SubtitleEdge.RAISED -> Shadow(edgeColor, offset = androidx.compose.ui.geometry.Offset(-2f, -2f))
        SubtitleEdge.DEPRESSED -> Shadow(edgeColor, offset = androidx.compose.ui.geometry.Offset(2f, 2f))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A6073), Color(0xFF16222A))))
    ) {
        val fromTop = (100 - look.verticalPosition.coerceIn(0, 100)) / 100f
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = (fromTop * 140).dp.coerceAtMost(140.dp), top = 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                Modifier
                    .background(Color(look.windowBackground.toInt()), RoundedCornerShape(4.dp))
                    .padding(6.dp)
            ) {
                Text(
                    "Subtitle preview",
                    modifier = Modifier.background(Color(look.lineBackground.toInt())).padding(horizontal = 4.dp),
                    style = TextStyle(
                        color = textColor,
                        fontSize = (16 * look.sizePercent / 100f).sp,
                        fontFamily = family,
                        fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Normal,
                        shadow = shadow
                    )
                )
            }
        }
    }
}

@Composable
private fun StyleLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabeledSwitch(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun Palette(selected: Long, onSelect: (Long) -> Unit) {
    val selectedRgb = selected and 0xFFFFFF
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        subtitlePalette.forEach { value ->
            val isSelected = (value and 0xFFFFFF) == selectedRgb
            Box(
                Modifier
                    .size(34.dp)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .padding(4.dp)
                    .background(Color(value.toInt()), CircleShape)
                    .clickable { onSelect(value) }
            )
        }
    }
}

/**
 * OpenSubtitles.com account: login is optional (the API key works anonymously);
 * a login raises the daily download quota.
 */
@Composable
fun OpenSubtitlesAccountDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = CastHub.settings.value
    var user by remember { mutableStateOf(settings.openSubtitlesUser) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loggedIn by remember { mutableStateOf(OpenSubtitlesAccount.isLoggedIn) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenSubtitles.com") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loggedIn) {
                    Text("Logged in as: ${CastHub.settings.value.openSubtitlesUser}")
                    Text("Logging out clears the stored credentials.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "Login is optional, but it raises your daily download limit.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        password, { password = it }, singleLine = true, label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OpenSubtitlesAccount.REGISTER_URL))) }
                    }) { Text("Register (free)") }
                }
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (loggedIn) {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        OpenSubtitlesAccount.logout()
                        busy = false
                        loggedIn = false
                    }
                }) { Text("Logout") }
            } else {
                TextButton(enabled = !busy && user.isNotBlank() && password.isNotEmpty(), onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            OpenSubtitlesAccount.login(user, password)
                            loggedIn = true
                            onDismiss()
                        } catch (e: OpenSubtitlesException.Unauthorized) {
                            error = if (CastHub.settings.value.openSubtitlesApiKey.isBlank()) e.message else "Username or password is invalid"
                        } catch (e: OpenSubtitlesException.RateLimited) {
                            error = "OpenSubtitles asks you to wait a moment before trying again."
                        } catch (e: Exception) {
                            error = "Login failed, please try again later. (${e.message})"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Login") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (loggedIn) "Close" else "Anonymous") } }
    )
}
