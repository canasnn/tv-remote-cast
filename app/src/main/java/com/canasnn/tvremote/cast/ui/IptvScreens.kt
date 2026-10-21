package com.canasnn.tvremote.cast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.iptv.IptvRepository
import com.canasnn.tvremote.cast.iptv.IptvRepository.toSavedMedia
import com.canasnn.tvremote.cast.player.PhonePlayer
import com.canasnn.tvremote.ui.app.AppBackdrop
import com.ruterika.castcore.iptv.IptvIndex
import com.ruterika.castcore.iptv.IptvIndexOptions
import com.ruterika.castcore.iptv.IptvListSort
import com.ruterika.castcore.iptv.IptvSort
import com.ruterika.castcore.iptv.IptvSortBy
import com.ruterika.castcore.iptv.IptvUpdateMode
import com.ruterika.castcore.iptv.M3uEntry
import com.ruterika.castcore.iptv.XtreamCodes
import com.ruterika.castcore.library.FavoriteChannel
import com.ruterika.castcore.library.IptvSource
import com.ruterika.castcore.library.SavedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// ── Channel actions ─────────────────────────────────────────────────────────

/** One entry of a channel's ⋮ / long-press menu. */
private class ChannelAction(val label: String, val icon: ImageVector, val run: () -> Unit)

/** Most channels "Add all to playlist" adds in one go. */
private const val ADD_ALL_LIMIT = 1000

private fun openInOtherApp(context: Context, media: SavedMedia) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(media.url), media.mimeType ?: "video/*")
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
        .onFailure { Toast.makeText(context, "No app can open this channel", Toast.LENGTH_SHORT).show() }
}

private fun copyUrl(context: Context, url: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("IPTV channel", url))
    Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
}

private fun channelActions(
    context: Context,
    scope: CoroutineScope,
    media: SavedMedia,
    favorite: Boolean,
    onCast: () -> Unit,
    onCastLive: () -> Unit,
    onToggleFavorite: () -> Unit,
    addToPlaylist: (List<SavedMedia>) -> Unit,
    download: ((SavedMedia) -> Unit)? = null
): List<ChannelAction> = listOfNotNull(
    ChannelAction("Cast to TV", Icons.Filled.Cast, onCast),
    ChannelAction("Play on phone", Icons.Filled.PhoneAndroid) { PhonePlayer.play(context, listOf(media)) },
    ChannelAction("Play as live stream", Icons.Filled.LiveTv, onCastLive),
    ChannelAction("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { addToPlaylist(listOf(media)) },
    // VOD entries (movies / episodes as files) can be downloaded; live channels can't.
    if (download != null && isVodFile(media.url)) ChannelAction("Download", Icons.Filled.Download) { download(media) } else null,
    ChannelAction("Open in another app", Icons.AutoMirrored.Filled.OpenInNew) { openInOtherApp(context, media) },
    ChannelAction("Copy URL", Icons.Filled.ContentCopy) { copyUrl(context, media.url) },
    ChannelAction(
        if (favorite) "Remove from favorites" else "Add to favorites",
        if (favorite) Icons.Filled.StarBorder else Icons.Filled.Star,
        onToggleFavorite
    )
)

private fun isVodFile(url: String): Boolean =
    url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "flv")

private fun iptvTime(millis: Long): String =
    if (millis <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private fun sortSources(sources: List<IptvSource>, sort: IptvListSort): List<IptvSource> = when (sort) {
    IptvListSort.DATE_ADDED_ASC -> sources.sortedBy { it.addedAt }
    IptvListSort.DATE_ADDED_DESC -> sources.sortedBy { it.addedAt }.reversed()
    IptvListSort.NAME_ASC -> sources.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    IptvListSort.NAME_DESC -> sources.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
}

// ── Cast hub section ────────────────────────────────────────────────────────

/** IPTV tab of the Cast hub: lists, favorites, add a list. */
fun LazyListScope.iptvSection(
    onOpenSource: (String) -> Unit,
    onCast: (List<SavedMedia>, Int) -> Unit,
    onAddList: () -> Unit
) {
    item {
        val data by CastHub.library.data.collectAsStateWithLifecycle()
        val settings by CastHub.settings.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val pickPlaylist = rememberPlaylistPicker()
        val sources = remember(data.iptvSources, settings.iptvListSort) { sortSources(data.iptvSources, settings.iptvListSort) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Filled.LiveTv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("IPTV lists", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Add an M3U/M3U8 or W3U link or file. Channels open straight on the TV; CH▲/▼ switches.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (sources.size > 1) {
                        Box {
                            var sortMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort lists") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                IptvListSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        trailingIcon = { if (option == settings.iptvListSort) Icon(Icons.Filled.Check, null) },
                                        onClick = {
                                            sortMenu = false
                                            CastHub.settingsStore.update { it.copy(iptvListSort = option) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    FilledTonalButton(onClick = onAddList) { Icon(Icons.Filled.Add, null); Text("Add") }
                }
            }
            sources.forEach { source ->
                key(source.id) {
                    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable { onOpenSource(source.id) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    buildString {
                                        append(if (source.channelCount > 0) (if (source.channelCount == 1) "1 channel" else "${source.channelCount} channels") else "Not loaded yet")
                                        if (source.lastLoadedAt > 0) append(" · Updated " + iptvTime(source.lastLoadedAt))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            var editing by remember { mutableStateOf(false) }
                            var confirmRemove by remember { mutableStateOf(false) }
                            if (editing) AddIptvDialog(onDismiss = { editing = false }, onAdded = { editing = false }, editing = source)
                            if (confirmRemove) {
                                ConfirmDialog("Remove list?", source.name, onDismiss = { confirmRemove = false }) {
                                    IptvRepository.forget(context, source.id)
                                    CastHub.library.removeIptvSource(source.id)
                                    confirmRemove = false
                                }
                            }
                            IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit list") }
                            IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Filled.Delete, contentDescription = "Remove list") }
                        }
                    }
                }
            }
            if (data.favoriteChannels.isNotEmpty()) {
                Text("Favorite channels", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                val favorites = data.favoriteChannels
                val favoriteMedia = favorites.map { SavedMedia(it.url, it.title, headers = it.headers, posterUrl = it.logo, isLive = true) }
                favorites.forEachIndexed { index, fav ->
                    key(fav.sourceId, fav.url) {
                        ChannelRow(
                            title = fav.title,
                            subtitle = fav.group,
                            logo = fav.logo,
                            favorite = true,
                            onToggleFavorite = { CastHub.library.toggleFavoriteChannel(fav) },
                            onClick = { PhonePlayer.playOrCast(context, favoriteMedia, index, onCast) },
                            actions = {
                                channelActions(
                                    context, scope, favoriteMedia[index], favorite = true,
                                    onCast = { onCast(favoriteMedia, index) },
                                    onCastLive = { onCast(favoriteMedia, index) },
                                    onToggleFavorite = { CastHub.library.toggleFavoriteChannel(fav) },
                                    addToPlaylist = pickPlaylist
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Add / edit dialog ───────────────────────────────────────────────────────

@Composable
fun AddIptvDialog(onDismiss: () -> Unit, onAdded: (String) -> Unit, editing: IptvSource? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var url by remember { mutableStateOf(editing?.location.orEmpty()) }
    var userAgent by remember { mutableStateOf(editing?.userAgent.orEmpty()) }
    var fileName by remember { mutableStateOf<String?>(null) }
    val xtream = remember { editing?.location?.let(XtreamCodes::parse) }
    var showXtream by remember { mutableStateOf(xtream != null) }
    var xServer by remember { mutableStateOf(xtream?.server.orEmpty()) }
    var xUser by remember { mutableStateOf(xtream?.username.orEmpty()) }
    var xPassword by remember { mutableStateOf(xtream?.password.orEmpty()) }
    var xOutput by remember { mutableStateOf(xtream?.output ?: XtreamCodes.Output.TS) }
    var analyzing by remember { mutableStateOf(false) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<Pair<String, IptvRepository.Analysis>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            url = uri.toString()
            addressError = null
            showXtream = false
            fileName = IptvRepository.displayName(context, uri)
            if (name.isBlank()) name = fileName?.substringBeforeLast('.').orEmpty()
        }
    }

    fun buildXtream() {
        if (xServer.isNotBlank() && xUser.isNotBlank() && xPassword.isNotBlank()) {
            url = XtreamCodes.buildUrl(xServer, xUser, xPassword, xOutput)
            addressError = null
        }
    }

    fun normalizedAddress(): String? {
        val text = url.trim()
        return when {
            text.isEmpty() -> null
            text.startsWith("http://", true) || text.startsWith("https://", true) ||
                text.startsWith("content://") || text.startsWith("file://") || text.startsWith("/") -> text
            !text.contains("://") && !text.contains(' ') && text.contains('.') -> "http://$text"
            else -> null
        }
    }

    fun save(location: String, skipContentCheck: Boolean) {
        val ua = userAgent.trim().ifBlank { null }
        if (editing != null) {
            val moved = editing.location != location
            if (moved || editing.userAgent != ua) IptvRepository.forget(context, editing.id)
            CastHub.library.updateIptvSource(
                editing.copy(
                    name = name.trim().ifBlank { editing.name },
                    location = location,
                    userAgent = ua,
                    skipContentCheck = if (moved) skipContentCheck else editing.skipContentCheck || skipContentCheck
                )
            )
            onAdded(editing.id)
        } else {
            val source = CastHub.library.addIptvSource(name.trim().ifBlank { fileName.orEmpty() }, location, ua, skipContentCheck)
            onAdded(source.id)
        }
    }

    problem?.let { (location, analysis) ->
        ConfirmDialog(
            title = if (analysis.canLoadAnyway) "Load anyway?" else "Add anyway?",
            text = analysis.message + if (analysis.canLoadAnyway) "\n\nLoad it as an IPTV list anyway?" else "\n\nAdd the list anyway?",
            onDismiss = { problem = null }
        ) {
            problem = null
            save(location, skipContentCheck = analysis.canLoadAnyway)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!analyzing) onDismiss() },
        title = { Text(if (editing != null) "Edit IPTV list" else "Add IPTV list") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; addressError = null; fileName = null },
                    label = { Text("M3U / W3U link") },
                    singleLine = true,
                    isError = addressError != null,
                    supportingText = addressError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FolderOpen, null); Text(" Pick a file")
                    }
                    TextButton(onClick = { showXtream = !showXtream }) {
                        Icon(Icons.Filled.VpnKey, null); Text(" Xtream Codes")
                    }
                }
                if (showXtream) {
                    Text(
                        "Fill in your provider's login; the M3U link is built for you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        xServer, { xServer = it; buildXtream() }, label = { Text("Server (http://host:port)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(xUser, { xUser = it; buildXtream() }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        xPassword, { xPassword = it; buildXtream() }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Stream format", style = MaterialTheme.typography.labelMedium)
                        XtreamCodes.Output.entries.forEach { output ->
                            FilterChip(selected = xOutput == output, onClick = { xOutput = output; buildXtream() }, label = { Text(output.label) })
                        }
                    }
                }
                OutlinedTextField(userAgent, { userAgent = it }, label = { Text("User agent (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (analyzing) {
                    Text("Analyzing IPTV list…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank() && !analyzing, onClick = {
                val location = normalizedAddress()
                if (location == null) {
                    addressError = "The address appears to be invalid"
                    return@TextButton
                }
                if (editing != null && editing.location == location) {
                    save(location, skipContentCheck = false)
                    return@TextButton
                }
                analyzing = true
                scope.launch {
                    val ua = userAgent.trim().ifBlank { null } ?: CastHub.settings.value.iptvDefaultUserAgent.ifBlank { null }
                    val hint = fileName ?: if (location.startsWith("content://")) IptvRepository.displayName(context, Uri.parse(location)) else null
                    val analysis = IptvRepository.analyze(context, location, hint, ua)
                    analyzing = false
                    if (analysis == null) save(location, skipContentCheck = false) else problem = location to analysis
                }
            }) { Text(if (editing != null) "Save" else "Add") }
        },
        dismissButton = { TextButton(enabled = !analyzing, onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── List screen ─────────────────────────────────────────────────────────────

/**
 * One list being browsed: the root list or a nested sub-list. [stack] holds the full group
 * paths the user opened (names from the root), so it survives re-indexing.
 */
private data class ListFrame(val source: IptvSource, val lenient: Boolean, val stack: List<List<String>>)

private data class LoadState(
    val loaded: IptvRepository.Loaded? = null,
    val loading: Boolean = false,
    val error: Throwable? = null
)

/** Rows shown for one level or search; only used together with the same [index]. */
private class Rows(val index: IptvIndex, val rows: IntArray) {
    val channels: IntArray by lazy { rows.filter { it >= 0 }.toIntArray() }
}

private const val SEP_FIELD = '\u001D'
private const val SEP_PATH = '\u001E'
private const val SEP_NAME = '\u001F'

private val FramesSaver: Saver<List<ListFrame>, Any> = listSaver(
    save = { frames ->
        frames.map { f ->
            listOf(
                f.source.id, f.source.name, f.source.location, f.source.userAgent.orEmpty(), f.lenient.toString(),
                f.stack.joinToString(SEP_PATH.toString()) { it.joinToString(SEP_NAME.toString()) }
            ).joinToString(SEP_FIELD.toString())
        }
    },
    restore = { saved ->
        saved.map { s ->
            val c = s.split(SEP_FIELD)
            ListFrame(
                IptvSource(c[0], c[1], c[2], c[3].ifEmpty { null }),
                c[4].toBoolean(),
                if (c[5].isEmpty()) emptyList() else c[5].split(SEP_PATH).map { it.split(SEP_NAME) }
            )
        }
    }
)

/** Channels of one IPTV list: folders, sub-lists, search, sorting, freshness. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvSourceScreen(sourceId: String, onBack: () -> Unit, onCast: (List<SavedMedia>, Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickPlaylist = rememberPlaylistPicker()
    val downloadVod = rememberDownloadStarter()
    val data by CastHub.library.data.collectAsStateWithLifecycle()
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val rootSource = data.iptvSources.firstOrNull { it.id == sourceId }
    if (rootSource == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var savedFrames by rememberSaveable(stateSaver = FramesSaver) {
        mutableStateOf(listOf(ListFrame(rootSource, rootSource.skipContentCheck, emptyList())))
    }
    // The root frame always reflects the stored source (edits, "load anyway").
    val frames = savedFrames.mapIndexed { i, f -> if (i == 0) f.copy(source = rootSource, lenient = rootSource.skipContentCheck) else f }
    val frame = frames.last()
    val isSubList = frames.size > 1

    var states by remember {
        mutableStateOf<Map<String, LoadState>>(
            IptvRepository.cached(sourceId)?.let { mapOf(sourceId to LoadState(IptvRepository.Loaded(it, IptvRepository.Freshness.CACHED, rootSource.lastLoadedAt))) }
                ?: emptyMap()
        )
    }
    val checked = remember { mutableSetOf<String>() }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var confirmAddAll by remember { mutableStateOf<IntArray?>(null) }

    fun setState(id: String, transform: (LoadState) -> LoadState) {
        states = states + (id to transform(states[id] ?: LoadState()))
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun load(target: ListFrame, sub: Boolean, manual: Boolean) {
        val id = target.source.id
        scope.launch {
            setState(id) { it.copy(loading = true, error = null) }
            if (!manual) {
                val cached = try {
                    IptvRepository.openCached(context, target.source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val mode = CastHub.settings.value.iptvUpdateMode
                val auto = mode == IptvUpdateMode.AUTOMATIC || (mode == IptvUpdateMode.SUBLISTS && sub)
                if (cached != null) {
                    setState(id) { LoadState(cached, loading = auto) }
                    if (!auto) return@launch
                }
            }
            try {
                val result = IptvRepository.update(context, target.source, target.lenient)
                setState(id) { LoadState(result) }
                if (manual) {
                    when (result.freshness) {
                        IptvRepository.Freshness.UNCHANGED -> toast("No updates found.")
                        IptvRepository.Freshness.UPDATED -> toast("The list was updated.")
                        IptvRepository.Freshness.UPDATE_FAILED -> toast("An error occurred while looking for updates.")
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                setState(id) { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                setState(id) { it.copy(loading = false, error = e) }
            }
        }
    }

    val options = IptvIndexOptions(settings.iptvConsiderSubgroups, settings.iptvRenameWithTvgName, settings.iptvKeepRssRootChannel)
    LaunchedEffect(frame.source.id, frame.lenient, options) {
        val current = states[frame.source.id]
        if (frame.source.id !in checked || (current?.loaded != null && current.loaded.index.options != options) ||
            (current?.loaded == null && current?.loading != true)
        ) {
            checked += frame.source.id
            load(frame, isSubList, manual = false)
        }
    }

    val state = states[frame.source.id] ?: LoadState(loading = true)
    val index = state.loaded?.index
    val skipSingles = settings.iptvSkipSingleGroups
    fun effectiveNode(idx: IptvIndex, path: List<String>?): Int {
        val base = path?.let { idx.findNode(it) } ?: 0
        return if (skipSingles) idx.descendSingleGroups(base) else base
    }
    val nodeId = index?.let { effectiveNode(it, frame.stack.lastOrNull()) } ?: 0
    val sort = IptvSort(settings.iptvChannelSort, settings.iptvChannelSortAscending, settings.iptvMixGroupsAndChannels)

    var rows by remember { mutableStateOf<Rows?>(null) }
    LaunchedEffect(index, nodeId, query, sort) {
        val idx = index ?: return@LaunchedEffect
        if (query.isNotBlank()) delay(150)
        val q = query
        rows = withContext(Dispatchers.Default) { Rows(idx, if (q.isBlank()) idx.level(nodeId, sort) else idx.search(nodeId, q, sort)) }
    }
    val shown = rows?.takeIf { it.index === index }
    val listState = rememberLazyListState()
    LaunchedEffect(frame.source.id, nodeId, query.isBlank()) { listState.scrollToItem(0) }

    fun replaceFrame(newFrame: ListFrame) {
        savedFrames = savedFrames.dropLast(1) + newFrame
    }

    fun openGroup(id: Int) {
        val idx = index ?: return
        replaceFrame(frame.copy(stack = frame.stack + listOf(idx.pathNames(id))))
        query = ""
        showSearch = false
    }

    fun openSubList(entry: M3uEntry) {
        savedFrames = savedFrames + ListFrame(IptvRepository.subSource(frame.source, entry), false, emptyList())
        query = ""
        showSearch = false
    }

    fun goUp() {
        when {
            frame.stack.isNotEmpty() -> replaceFrame(frame.copy(stack = frame.stack.dropLast(1)))
            savedFrames.size > 1 -> savedFrames = savedFrames.dropLast(1)
            else -> onBack()
        }
    }

    BackHandler(enabled = showSearch || frame.stack.isNotEmpty() || savedFrames.size > 1) {
        if (showSearch) {
            showSearch = false
            query = ""
        } else {
            goUp()
        }
    }

    val backLabel: String? = when {
        frame.stack.isNotEmpty() -> {
            val parentNode = index?.let { effectiveNode(it, frame.stack.dropLast(1).lastOrNull()) } ?: 0
            if (parentNode == 0 || index == null) frame.source.name else index.node(parentNode).name
        }
        isSubList -> frames[frames.size - 2].let { f -> f.stack.lastOrNull()?.lastOrNull() ?: f.source.name }
        else -> null
    }
    val title = if (index != null && nodeId != 0) index.node(nodeId).name else frame.source.name
    val userAgent = IptvRepository.userAgent(frame.source)
    val favoriteUrls = remember(data.favoriteChannels, sourceId) {
        data.favoriteChannels.filter { it.sourceId == sourceId }.map { it.url }.toSet()
    }

    /** Casts the channel at [entryIndex]; a plain tap ([tap]) plays it on the phone when "Always play in app" is on. */
    fun castAt(entryIndex: Int, forceLive: Boolean = false, tap: Boolean = false) {
        val r = shown ?: return
        val position = r.channels.indexOf(entryIndex).takeIf { it >= 0 } ?: return
        val (queue, start) = IptvRepository.window(r.index, r.channels, position, userAgent, forceLive)
        if (queue.isEmpty()) return
        if (tap) PhonePlayer.playOrCast(context, queue, start, onCast) else onCast(queue, start)
    }

    fun toggleFavorite(entry: M3uEntry) {
        CastHub.library.toggleFavoriteChannel(FavoriteChannel(sourceId, entry.url, entry.title, entry.logo, entry.group, entry.headers))
    }

    if (showSort) ChannelSortDialog(onDismiss = { showSort = false })
    confirmAddAll?.let { channels ->
        val count = minOf(channels.size, ADD_ALL_LIMIT)
        ConfirmDialog(
            title = "Add all?",
            text = "Add $count channels to the playlist?" +
                if (channels.size > ADD_ALL_LIMIT) "\n\nOnly the first $ADD_ALL_LIMIT of ${channels.size} channels are added." else "",
            onDismiss = { confirmAddAll = null }
        ) {
            confirmAddAll = null
            val idx = index ?: return@ConfirmDialog
            scope.launch {
                val items = withContext(Dispatchers.IO) {
                    channels.take(ADD_ALL_LIMIT).map { idx.entry(it) }
                        .filter { it.url.isNotEmpty() && !it.isNestedList }
                        .map { it.toSavedMedia(userAgent) }
                }
                if (items.isNotEmpty()) pickPlaylist(items)
            }
        }
    }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = { IconButton(onClick = { goUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = { Text(title.ifBlank { "IPTV" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) query = ""
                        }) { Icon(Icons.Filled.Search, "Search") }
                        IconButton(enabled = !state.loading, onClick = { load(frame, isSubList, manual = true) }) { Icon(Icons.Filled.Refresh, "Update") }
                        Box {
                            IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(
                                    text = { Text("Sort channels") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                                    onClick = { overflow = false; showSort = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add all to playlist") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                    enabled = index != null,
                                    onClick = {
                                        overflow = false
                                        val idx = index ?: return@DropdownMenuItem
                                        val channels = if (query.isBlank()) idx.channelsUnder(nodeId) else shown?.channels ?: IntArray(0)
                                        if (channels.isEmpty()) toast("No channels here") else confirmAddAll = channels
                                    }
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = { MiniController() }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showSearch) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(if (nodeId == 0) "Search channels" else "Search in $title") },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                if (index != null) {
                    FreshnessBar(state, isLocal = IptvRepository.isLocal(frame.source.location)) { load(frame, isSubList, manual = true) }
                }
                when {
                    index == null && state.error != null -> {
                        val error: Throwable = state.error
                        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            backLabel?.let { BackRow(it) { goUp() } }
                            Text(IptvRepository.describe(error), color = MaterialTheme.colorScheme.error)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { load(frame, isSubList, manual = true) }) { Text("Retry") }
                                if (IptvRepository.canLoadAnyway(error)) {
                                    OutlinedButton(onClick = {
                                        if (isSubList) replaceFrame(frame.copy(lenient = true))
                                        else CastHub.library.updateIptvSource(rootSource.copy(skipContentCheck = true))
                                    }) { Text("Load anyway") }
                                }
                            }
                        }
                    }
                    index == null -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Text("Loading IPTV list…", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        if (backLabel != null) item(key = "back") { BackRow(backLabel) { goUp() } }
                        if (query.isNotBlank()) {
                            item(key = "results") {
                                Text("Results for: $query", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        val r = shown
                        if (r != null && r.rows.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    "No channels found.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 24.dp)
                                )
                            }
                        }
                        if (r != null) {
                            items(count = r.rows.size, key = { r.rows[it] }) { i ->
                                val row = r.rows[i]
                                if (IptvIndex.isGroupRow(row)) {
                                    val node = r.index.node(IptvIndex.groupId(row))
                                    GroupRow(node.name, node.groupCount, node.totalChannels) { openGroup(node.id) }
                                } else {
                                    val entry = remember(r.index, row) { r.index.entry(row) }
                                    val nested = entry.isNestedList
                                    val favorite = entry.url in favoriteUrls
                                    ChannelRow(
                                        title = entry.title.ifBlank { "N/A" },
                                        subtitle = when {
                                            nested -> "List"
                                            query.isNotBlank() -> r.index.pathNames(r.index.nodeOf(row)).joinToString(" › ").ifEmpty { null }
                                            else -> null
                                        },
                                        logo = entry.logo,
                                        favorite = favorite,
                                        isList = nested,
                                        onToggleFavorite = { toggleFavorite(entry) },
                                        onClick = { if (nested) openSubList(entry) else castAt(row, tap = true) },
                                        actions = {
                                            if (nested) {
                                                listOf(
                                                    ChannelAction("Open list", Icons.AutoMirrored.Filled.PlaylistPlay) { openSubList(entry) },
                                                    ChannelAction("Copy URL", Icons.Filled.ContentCopy) { copyUrl(context, entry.url) }
                                                )
                                            } else {
                                                channelActions(
                                                    context, scope, entry.toSavedMedia(userAgent), favorite,
                                                    onCast = { castAt(row) },
                                                    onCastLive = { castAt(row, forceLive = true) },
                                                    onToggleFavorite = { toggleFavorite(entry) },
                                                    addToPlaylist = pickPlaylist,
                                                    download = downloadVod
                                                )
                                            }
                                        }
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

// ── Rows and bars ───────────────────────────────────────────────────────────

@Composable
private fun FreshnessBar(state: LoadState, isLocal: Boolean, onUpdate: () -> Unit) {
    val loaded = state.loaded ?: return
    val time = iptvTime(loaded.updatedAt)
    val failed = !state.loading && loaded.freshness == IptvRepository.Freshness.UPDATE_FAILED
    val text: String
    var action: String? = null
    when {
        state.loading -> text = "Looking for updates…"
        loaded.freshness == IptvRepository.Freshness.UPDATED ->
            text = "The list is up-to-date." + if (time.isNotEmpty()) " Updated $time" else ""
        loaded.freshness == IptvRepository.Freshness.UNCHANGED ->
            text = "No changes since the last update." + if (time.isNotEmpty()) " Checked $time" else ""
        loaded.freshness == IptvRepository.Freshness.UPDATE_FAILED -> {
            text = "Displaying cached list as update attempt failed: " +
                (loaded.error?.let { IptvRepository.describe(it).lineSequence().filter { line -> line.isNotBlank() }.joinToString(" ") } ?: "unknown error")
            action = "Retry"
        }
        isLocal || loaded.freshness == IptvRepository.Freshness.LOCAL -> {
            text = "Loaded from a file." + if (time.isNotEmpty()) " Read $time" else ""
            action = "Update"
        }
        else -> {
            text = "Displaying cached list." + if (time.isNotEmpty()) " Updated $time" else ""
            action = "Update"
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            )
            if (action != null && !state.loading) TextButton(onClick = onUpdate) { Text(action) }
        }
    }
}

@Composable
private fun BackRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Text(
                "Back to $label",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

@Composable
private fun GroupRow(name: String, groups: Int, channels: Int, onClick: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(name.ifBlank { "N/A" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (channels == 1) "1 channel" else "$channels channels") + if (groups == 1) " · 1 group" else if (groups > 1) " · $groups groups" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChannelRow(
    title: String,
    subtitle: String?,
    logo: String?,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    actions: () -> List<ChannelAction>,
    isList: Boolean = false
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    ElevatedCard(
        shape = shape,
        modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick, onLongClick = { menu = true })
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                when {
                    logo != null -> AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    isList -> Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary)
                    else -> Icon(Icons.Filled.LiveTv, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (!isList) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "Favorite",
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Channel options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (menu) {
                    DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                        actions().forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                leadingIcon = { Icon(action.icon, null) },
                                onClick = {
                                    menu = false
                                    action.run()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelSortDialog(onDismiss: () -> Unit) {
    val s by CastHub.settings.collectAsStateWithLifecycle()
    val update = CastHub.settingsStore::update
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort channels") },
        text = {
            Column {
                Text("Sort by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                IptvSortBy.entries.forEach { by ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = s.iptvChannelSort == by, onClick = { update { it.copy(iptvChannelSort = by) } })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = s.iptvChannelSort == by, onClick = { update { it.copy(iptvChannelSort = by) } })
                        Text(by.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text("Order", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.iptvChannelSortAscending, onClick = { update { it.copy(iptvChannelSortAscending = true) } }, label = { Text("Ascending") })
                    FilterChip(selected = !s.iptvChannelSortAscending, onClick = { update { it.copy(iptvChannelSortAscending = false) } }, label = { Text("Descending") })
                }
                Row(
                    Modifier.fillMaxWidth().clickable { update { it.copy(iptvMixGroupsAndChannels = !s.iptvMixGroupsAndChannels) } }.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = s.iptvMixGroupsAndChannels, onCheckedChange = { v -> update { it.copy(iptvMixGroupsAndChannels = v) } })
                    Text("Mix groups and channels", modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
