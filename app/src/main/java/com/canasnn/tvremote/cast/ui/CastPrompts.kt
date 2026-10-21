package com.canasnn.tvremote.cast.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.canasnn.tvremote.cast.player.PhonePlayer
import com.canasnn.tvremote.ui.app.TvRemoteViewModel
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.canasnn.tvremote.cast.CastHub
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ruterika.castcore.library.SavedMedia
import com.ruterika.castcore.library.SavedSubtitle
import com.ruterika.castcore.settings.ResumeMode

/** A cast the user asked for, waiting on a prompt. */
data class PendingCast(val items: List<SavedMedia>, val index: Int, val step: Step, val positionMs: Long = -1L) {
    enum class Step { NOT_CONNECTED, ALREADY_PLAYING, RESUME, SUBTITLES }
}

/**
 * Entry point for every "cast this" action: asks "already playing → play now
 * or add to queue?" and "resume from X?", then calls
 * [play] (items, index, start position; -1 = settings decide) or [enqueue].
 */
class CastLauncher(
    private val state: MutableState<PendingCast?>,
    private val play: (List<SavedMedia>, Int, Long) -> Unit,
    private val enqueue: (List<SavedMedia>) -> Unit,
    private val addToPlaylist: (List<SavedMedia>) -> Unit = {},
    /** True when a TV is connected (the remote's connection guard). */
    private val isConnected: () -> Boolean = { true },
    /** "Connect to TV": runs the connection guard, which opens TV discovery. */
    private val connect: () -> Unit = {},
    private val download: (SavedMedia) -> Unit = {},
    private val playOnPhone: (List<SavedMedia>, Int) -> Unit = { _, _ -> }
) {
    fun cast(items: List<SavedMedia>, index: Int = 0) {
        if (items.isEmpty()) return
        val settings = CastHub.settings.value
        when {
            // "not connected" sheet: connect, or do something else with the video.
            !isConnected() -> state.value = PendingCast(items, index, PendingCast.Step.NOT_CONNECTED)
            settings.showAlreadyPlayingDialog && CastHub.isPlaying -> state.value = PendingCast(items, index, PendingCast.Step.ALREADY_PLAYING)
            needsResumePrompt(items[index]) -> state.value = PendingCast(items, index, PendingCast.Step.RESUME)
            else -> proceed(items, index, -1L)
        }
    }

    internal fun continueAfterAlreadyPlaying(p: PendingCast) {
        state.value = if (needsResumePrompt(p.items[p.index])) p.copy(step = PendingCast.Step.RESUME) else null
        if (state.value == null) proceed(p.items, p.index, -1L)
    }

    /** "Always use subtitles": the subtitle chooser comes last, right before playing. */
    private fun proceed(items: List<SavedMedia>, index: Int, positionMs: Long) {
        val media = items[index]
        val video = media.mimeType?.let { it.startsWith("audio") || it.startsWith("image") } != true
        if (CastHub.settings.value.promptSubtitles && video && !subtitlesChosen.remove(media.url)) {
            state.value = PendingCast(items, index, PendingCast.Step.SUBTITLES, positionMs)
        } else {
            state.value = null
            play(items, index, positionMs)
        }
    }

    /** Casts [p] with [subtitle] first in its subtitle list (null = as it is). */
    internal fun castWithSubtitle(p: PendingCast, subtitle: SavedSubtitle?) {
        state.value = null
        val items = if (subtitle == null) p.items else p.items.toMutableList().also { list ->
            val media = list[p.index]
            list[p.index] = media.copy(subtitles = listOf(subtitle) + media.subtitles.filterNot { it.url == subtitle.url })
        }
        play(items, p.index, p.positionMs)
    }

    internal fun enqueue(p: PendingCast) {
        state.value = null
        enqueue(p.items.drop(p.index))
    }

    /** "Add to playlist" instead of interrupting what is playing. */
    internal fun addToPlaylist(p: PendingCast) {
        state.value = null
        addToPlaylist(listOf(p.items[p.index]))
    }

    internal fun start(p: PendingCast, positionMs: Long) {
        state.value = null
        webPositions.remove(p.items[p.index].url)
        proceed(p.items, p.index, positionMs)
    }

    internal fun dismiss() {
        state.value = null
    }

    internal fun connect() {
        state.value = null
        connect.invoke()
    }

    internal fun download(p: PendingCast) {
        state.value = null
        download(p.items[p.index])
    }

    internal fun playOnPhone(p: PendingCast) {
        state.value = null
        playOnPhone(p.items, p.index)
    }

    private fun needsResumePrompt(media: SavedMedia): Boolean {
        val s = CastHub.settings.value
        return s.resumePlayback && s.resumeMode == ResumeMode.ASK && !media.isLive &&
            (CastHub.savedPosition(media) > MIN_RESUME_MS || webPosition(media) > MIN_WEB_POSITION_MS)
    }

    companion object {
        internal const val MIN_RESUME_MS = 30_000L
        internal const val MIN_WEB_POSITION_MS = 15_000L

        /** Where the web page's own player was, per media URL (browser casts; "web page position"). */
        private val webPositions = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** The browser is about to cast [url], whose page player was at [positionMs]. */
        fun noteWebPosition(url: String, positionMs: Long?) {
            if (positionMs != null && positionMs > MIN_WEB_POSITION_MS) webPositions[url] = positionMs else webPositions.remove(url)
        }

        internal fun webPosition(media: SavedMedia): Long = webPositions[media.url] ?: 0L

        /** Videos whose subtitles were just picked elsewhere (browser "Subtitles…"): no second prompt. */
        private val subtitlesChosen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun markSubtitlesChosen(url: String) {
            if (CastHub.settings.value.promptSubtitles) subtitlesChosen += url
        }
    }
}

@Composable
fun rememberCastLauncher(
    play: (List<SavedMedia>, Int, Long) -> Unit,
    enqueue: (List<SavedMedia>) -> Unit
): CastLauncher {
    val state = remember { mutableStateOf<PendingCast?>(null) }
    val context = LocalContext.current
    // Live here (not in the dialog) so the picker / download prompt stay open after the prompt closes.
    val pickPlaylist = rememberPlaylistPicker()
    val startDownload = rememberDownloadStarter()
    // The activity-wide view model the nav graph uses: its connection guard opens TV discovery.
    val activity = context.findComponentActivity()
    val remote: TvRemoteViewModel? = activity?.let {
        viewModel(viewModelStoreOwner = it, factory = ViewModelProvider.AndroidViewModelFactory.getInstance(it.application))
    }
    val launcher = remember(remote) {
        CastLauncher(
            state, play, enqueue, pickPlaylist,
            isConnected = { remote == null || remote.uiState.value.connectedDevice != null },
            connect = { remote?.checkConnectionGuard() },
            download = startDownload,
            playOnPhone = { items, index -> PhonePlayer.play(context, items, index) }
        )
    }
    state.value?.let { CastPromptDialog(it, launcher) }
    return launcher
}

@Composable
private fun CastPromptDialog(pending: PendingCast, launcher: CastLauncher) {
    val media = pending.items[pending.index]
    when (pending.step) {
        PendingCast.Step.NOT_CONNECTED -> NotConnectedSheet(pending, launcher)
        PendingCast.Step.ALREADY_PLAYING -> AlertDialog(
            onDismissRequest = launcher::dismiss,
            title = { Text("Something is already playing") },
            text = { Text("Play \"${media.title}\" now, add it to the queue on the TV, or save it to a playlist?") },
            confirmButton = { TextButton(onClick = { launcher.continueAfterAlreadyPlaying(pending) }) { Text("Play now") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { launcher.addToPlaylist(pending) }) { Text("Add to playlist") }
                    TextButton(onClick = { launcher.enqueue(pending) }) { Text("Add to queue") }
                }
            }
        )
        PendingCast.Step.RESUME -> {
            val saved = CastHub.savedPosition(media).takeIf { it > CastLauncher.MIN_RESUME_MS } ?: 0L
            val web = CastLauncher.webPosition(media).takeIf { it > CastLauncher.MIN_WEB_POSITION_MS } ?: 0L
            AlertDialog(
                onDismissRequest = launcher::dismiss,
                title = { Text("Continue watching?") },
                text = {
                    Column {
                        Text(media.title)
                        if (saved > 0) Text(
                            if (media.durationMs > 0) "Played ${formatTime(saved)} of ${formatTime(media.durationMs)}"
                            else "Last position ${formatTime(saved)}"
                        )
                        if (web > 0) Text("The web page was at ${formatTime(web)}")
                    }
                },
                confirmButton = {
                    if (saved > 0) TextButton(onClick = { launcher.start(pending, saved) }) { Text("Resume at ${formatTime(saved)}") }
                    else TextButton(onClick = { launcher.start(pending, web) }) { Text("Resume at page position ${formatTime(web)}") }
                },
                dismissButton = {
                    Row {
                        if (saved > 0 && web > 0) TextButton(onClick = { launcher.start(pending, web) }) { Text("Page position ${formatTime(web)}") }
                        TextButton(onClick = { launcher.start(pending, 0L) }) { Text("Start over") }
                    }
                }
            )
        }
        PendingCast.Step.SUBTITLES -> Dialog(
            onDismissRequest = launcher::dismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            SubtitleScreen(
                onBack = launcher::dismiss,
                onOpenSettings = null,
                target = media,
                onPicked = { launcher.castWithSubtitle(pending, it) }
            )
        }
    }
}

/** "not connected" page: connect to a TV, or download / save / play the video on the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotConnectedSheet(pending: PendingCast, launcher: CastLauncher) {
    val media = pending.items[pending.index]
    val image = media.mimeType?.startsWith("image") == true
    ModalBottomSheet(onDismissRequest = launcher::dismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Not connected to a TV", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                media.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            SheetAction(Icons.Filled.Cast, "Connect to TV", "Find your TV and pair it") { launcher.connect() }
            if (!media.isLive) SheetAction(Icons.Filled.Download, "Download", "Save it to the phone and cast it later") { launcher.download(pending) }
            SheetAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", null) { launcher.addToPlaylist(pending) }
            if (!image) SheetAction(Icons.Filled.Smartphone, "Play on phone", null) { launcher.playOnPhone(pending) }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
