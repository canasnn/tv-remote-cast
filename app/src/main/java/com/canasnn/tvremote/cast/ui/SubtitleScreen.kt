package com.canasnn.tvremote.cast.ui

import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.browser.BrowserManager
import com.canasnn.tvremote.cast.subtitles.OpenSubtitlesAccount
import com.canasnn.tvremote.cast.subtitles.SubtitleLibrary
import com.canasnn.tvremote.ui.app.AppBackdrop
import com.ruterika.castcore.library.SavedMedia
import com.ruterika.castcore.library.SavedSubtitle
import com.ruterika.castcore.subtitles.OpenSubtitlesResult
import com.ruterika.castcore.subtitles.SubtitleArchive
import com.ruterika.castcore.subtitles.SubtitleNaming
import com.ruterika.castcore.subtitles.SubtitleParseException
import com.ruterika.protocol.TrackType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Subtitles for what is playing (or, with [onPicked], for a video about to be
 * cast): remove, embedded tracks, web page tracks, OpenSubtitles, local file
 * or URL (also `.zip`), previously downloaded files, timing and style.
 *
 * @param target the video to choose subtitles for before casting (pre-cast mode).
 * @param onPicked pre-cast mode: called with the chosen subtitle, or null to cast without one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleScreen(
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    target: SavedMedia? = null,
    onPicked: ((SavedSubtitle?) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val session by CastHub.session.collectAsStateWithLifecycle()
    val status by CastHub.status.collectAsStateWithLifecycle()
    val preCast = onPicked != null
    val media = if (preCast) target else session?.current(status)
    val store = remember { SubtitleLibrary.store(context) }
    val storeData by store.data.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OpenSubtitlesResult>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmLoad by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }

    fun attach(subtitle: SavedSubtitle) {
        if (onPicked != null) { onPicked(subtitle); return }
        scope.launch {
            busy = true
            val ok = CastHub.attachSubtitle(subtitle)
            busy = false
            Toast.makeText(context, if (ok) "Subtitles sent to TV" else "Could not reload on TV", Toast.LENGTH_SHORT).show()
            if (ok) onBack()
        }
    }

    /** Reads, unzips, checks and keeps a subtitle, then attaches it. */
    fun obtainAndAttach(url: String, headers: Map<String, String>, language: String?) {
        scope.launch {
            busy = true
            try {
                attach(SubtitleLibrary.obtain(context, url, headers, language, mediaTitle = media?.title))
            } catch (e: SubtitleParseException) {
                problem = "The subtitle file does not appear to contain subtitles."
            } catch (e: Exception) {
                problem = "Error getting subtitle: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { displayName(context, uri) }
            val load = { obtainAndAttach(uri.toString(), emptyMap(), null) }
            if (SubtitleArchive.isSuspiciousName(name)) confirmLoad = (name ?: "This file") to load else load()
        }
    }

    fun download(result: OpenSubtitlesResult) {
        scope.launch {
            busy = true
            try {
                val file = OpenSubtitlesAccount.call { c -> c.downloadFile(result.fileId) }
                val subtitle = withContext(Dispatchers.IO) {
                    val (bytes, name) = SubtitleArchive.unwrap(file.bytes, file.link.fileName ?: result.fileName)
                    SubtitleLibrary.keep(
                        context, bytes, name, result.language, "OpenSubtitles.com", media?.title,
                        label = "${SubtitleNaming.displayName(result.language)} · ${result.release.ifBlank { name.orEmpty() }}".take(60)
                    )
                }
                file.link.remaining?.let { left ->
                    Toast.makeText(context, "$left OpenSubtitles downloads left today", Toast.LENGTH_SHORT).show()
                }
                attach(subtitle)
            } catch (e: SubtitleParseException) {
                problem = "The subtitle file does not appear to contain subtitles."
            } catch (e: Exception) {
                problem = openSubtitlesMessage(e, searching = false)
            } finally {
                busy = false
            }
        }
    }

    if (showAccount) OpenSubtitlesAccountDialog(onDismiss = { showAccount = false })
    problem?.let { text ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("Subtitles") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } }
        )
    }
    confirmLoad?.let { (name, load) ->
        AlertDialog(
            onDismissRequest = { confirmLoad = null },
            title = { Text("Not a subtitle file?") },
            text = { Text("\"$name\" doesn't appear to be a subtitle file. Do you want to attempt to load it?") },
            confirmButton = { TextButton(onClick = { confirmLoad = null; load() }) { Text("Load") } },
            dismissButton = { TextButton(onClick = { confirmLoad = null }) { Text("Cancel") } }
        )
    }
    if (confirmDeleteAll) {
        ConfirmDialog("Delete all downloaded subtitles?", "${storeData.downloads.size} files will be removed from this phone.", onDismiss = { confirmDeleteAll = false }) {
            confirmDeleteAll = false
            store.deleteAll()
        }
    }
    if (showStyle) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showStyle = false }) {
            LazyColumn(Modifier.padding(horizontal = 20.dp)) {
                item {
                    Text("Subtitle style", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                    SubtitleStyleEditor(settings.subtitleLook, { v -> CastHub.settingsStore.update { it.copy(subtitleLook = v) } })
                }
            }
        }
    }

    val textTracks = if (preCast) emptyList() else status.tracks.filter { it.type == TrackType.TEXT }
    val pageSubs = remember(media) { pageSubtitles(media) }
    val attachedUrl = media?.subtitles?.firstOrNull()?.url
    val defaultLanguage = settings.subtitleLanguages.firstOrNull()
    // The page track in the default subtitle language is the one picked.
    val selectedPageUrl = attachedUrl?.takeIf { url -> pageSubs.any { it.url == url } }
        ?: pageSubs.firstOrNull { SubtitleNaming.sameLanguage(it.language ?: it.label, defaultLanguage) }?.url

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = { Text("Subtitles") },
                    actions = {
                        IconButton(onClick = { showStyle = true }) { Icon(Icons.Filled.Palette, "Subtitle style") }
                        IconButton(onClick = { showAccount = true }) { Icon(Icons.Filled.AccountCircle, "OpenSubtitles account") }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        media?.title ?: "Nothing is playing",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onPicked != null) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Choose subtitles for this video, or cast it without.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onPicked(null) }) { Text("Cast without") }
                    }
                }
                if (busy) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }

                // Remove / embedded (tracks the TV reports)
                if (!preCast && media != null) item {
                    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            ChoiceRow("Remove subtitles", null, selected = textTracks.isNotEmpty() && textTracks.none { it.selected }, icon = true) {
                                CastHub.selectTrack(TrackType.TEXT, null)
                            }
                            if (textTracks.isNotEmpty()) {
                                SectionLabel("Subtitles in the video")
                                textTracks.forEach { track ->
                                    ChoiceRow(track.label, track.language?.let(SubtitleNaming::displayName), track.selected) {
                                        CastHub.selectTrack(TrackType.TEXT, track.id)
                                    }
                                }
                            }
                        }
                    }
                }

                // Web page subtitles
                if (pageSubs.isNotEmpty()) item {
                    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            SectionLabel("Subtitles from web page")
                            pageSubs.forEach { sub ->
                                val lang = (sub.language ?: sub.label)?.let { SubtitleNaming.normalizeLanguage(it) ?: it }
                                ChoiceRow(
                                    sub.label ?: lang?.let(SubtitleNaming::displayName) ?: "Subtitle",
                                    listOfNotNull(
                                        lang?.let(SubtitleNaming::displayName)?.takeIf { it != sub.label },
                                        Uri.parse(sub.url).lastPathSegment,
                                        Uri.parse(sub.url).host
                                    ).joinToString(" · "),
                                    selected = sub.url == selectedPageUrl,
                                    enabled = !busy && media != null
                                ) { attach(sub) }
                            }
                        }
                    }
                }

                // Local file / URL
                item {
                    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = media != null && !busy) {
                                Icon(Icons.Filled.FolderOpen, null); Text("  Local file (srt, vtt, ass, sub, zip)")
                            }
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Filled.Link, null) },
                                placeholder = { Text("Subtitles from URL") },
                                trailingIcon = {
                                    if (urlInput.startsWith("http")) TextButton(enabled = media != null && !busy, onClick = {
                                        obtainAndAttach(urlInput.trim(), media?.headers.orEmpty(), SubtitleNaming.guessLanguage(urlInput))
                                    }) { Text("Load") }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (urlInput.startsWith("http") && media != null) {
                                        obtainAndAttach(urlInput.trim(), media.headers, SubtitleNaming.guessLanguage(urlInput))
                                    }
                                })
                            )
                        }
                    }
                }

                // Timing (live on the TV)
                if (!preCast && session != null) item {
                    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                        SubtitleTimingControl(
                            offsetMs = session?.subtitleOffsetMs ?: 0L,
                            onChange = { CastHub.setSubtitleOffset(it) },
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                // OpenSubtitles
                item {
                    OpenSubtitlesSearchCard(
                        mediaTitle = media?.title,
                        busy = busy,
                        onBusy = { busy = it },
                        onResults = { results = it },
                        onOpenSettings = onOpenSettings
                    )
                }
                results?.let { list ->
                    if (list.isEmpty()) item {
                        Text(
                            "No subtitles found. Try a shorter name or turn off Exact.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    items(list, key = { it.fileId }) { result ->
                        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = !busy && media != null) { download(result) }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    result.featureTitle?.let {
                                        Text(listOfNotNull(it, result.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(result.release.ifBlank { result.fileName ?: "Subtitle" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildList {
                                            add(SubtitleNaming.displayName(result.language))
                                            add("${result.downloadCount} downloads")
                                            if (result.movieHashMatch) add("exact match")
                                            if (result.hearingImpaired) add("SDH")
                                            if (result.aiTranslated || result.machineTranslated) add("machine translated")
                                        }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Filled.Download, "Download", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                // Previously downloaded subtitles
                if (storeData.downloads.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Downloaded subtitles", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { confirmDeleteAll = true }) { Text("Delete all") }
                        }
                    }
                    items(storeData.downloads, key = { "dl-" + it.id }) { entry ->
                        val url = remember(entry) { SubtitleLibrary.toSaved(context, entry).url }
                        ElevatedCard(
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy && media != null) { attach(SubtitleLibrary.toSaved(context, entry)) }
                        ) {
                            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = attachedUrl == url, onClick = null, modifier = Modifier.padding(8.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(entry.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(
                                            entry.language?.let(SubtitleNaming::displayName),
                                            DateUtils.getRelativeTimeSpanString(entry.savedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                            entry.mediaTitle ?: entry.source?.let { s -> Uri.parse(s).host ?: s }
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { store.delete(entry.id) }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        }
                    }
                }
                item { Box(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    icon: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon) Icon(Icons.Filled.SubtitlesOff, null, modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Subtitle tracks the browser found for [media] (same video URL, else same
 * page) plus web subtitles already attached to it.
 */
private fun pageSubtitles(media: SavedMedia?): List<SavedSubtitle> {
    media ?: return emptyList()
    val detected = BrowserManager.tabs.value.flatMap { it.collector.items.value }
    val sameVideo = detected.filter { it.url == media.url }
    val sources = sameVideo.ifEmpty { if (media.pageUrl != null) detected.filter { it.pageUrl == media.pageUrl } else emptyList() }
    val fromPage = sources.flatMap { it.subtitles }.map { SavedSubtitle(it.url, it.language, it.label, it.mime) }
    val attached = media.subtitles.filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        .filterNot { it.url.contains("/text/") }
    return (fromPage + attached).distinctBy { it.url }
}

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment
