package com.canasnn.tvremote.cast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.player.PhonePlayer
import com.canasnn.tvremote.ui.app.AppBackdrop
import com.ruterika.castcore.library.LibraryStore
import com.ruterika.castcore.library.Playlist
import com.ruterika.castcore.library.PlaylistEntry
import com.ruterika.castcore.library.PlaylistSort
import com.ruterika.castcore.library.SavedMedia
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

enum class LibrarySection(val route: String, val title: String, val icon: ImageVector) {
    Recent("recent", "Recent media", Icons.Filled.History),
    Playlists("playlists", "Playlists", Icons.AutoMirrored.Filled.PlaylistPlay),
    Bookmarks("bookmarks", "Bookmarks", Icons.Filled.Bookmark),
    History("history", "Browsing history", Icons.Filled.Public),
    MostVisited("most_visited", "Most visited", Icons.Filled.TrendingUp),
    Downloads("downloads", "Downloads", Icons.Filled.Download);

    companion object {
        fun of(route: String?) = entries.firstOrNull { it.route == route } ?: Recent
    }
}

/** Library tab of the Cast hub: entry points to the library screens. */
fun LazyListScope.librarySection(onOpen: (LibrarySection) -> Unit) {
    item {
        val data by CastHub.library.data.collectAsStateWithLifecycle()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibrarySection.entries.forEach { section ->
                val count = when (section) {
                    LibrarySection.Recent -> data.recentMedia.size
                    LibrarySection.Playlists -> data.playlists.size
                    LibrarySection.Bookmarks -> data.bookmarks.size
                    LibrarySection.History -> data.history.size
                    LibrarySection.MostVisited -> data.sites.size
                    LibrarySection.Downloads -> data.downloads.size
                }
                ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable { onOpen(section) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(section.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Text(section.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    section: LibrarySection,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCast: (List<SavedMedia>, Int) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    /** "Open in new tab" in the history menu; the item is left out when null. */
    onOpenUrlInNewTab: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val data by CastHub.library.data.collectAsStateWithLifecycle()
    val addToPlaylist = rememberPlaylistPicker()
    val download = rememberDownloadStarter()
    val snackbar = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    val onDownloadRemoved: (com.ruterika.castcore.library.DownloadItem, Boolean) -> Unit = { item, fileDeleted ->
        snackScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = if (fileDeleted) "File deleted" else "Removed from downloads",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) com.canasnn.tvremote.cast.download.Downloads.undoRemove(item)
        }
    }
    // "Delete history older than N days" also runs when the history opens.
    LaunchedEffect(section) {
        if (section == LibrarySection.History) CastHub.library.deleteHistoryOlderThan(CastHub.settings.value.historyAutoDeleteDays)
    }

    // ── Search and multi-select (history, bookmarks) ───────────────────────
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    fun matches(vararg texts: String?) = q.isEmpty() || texts.any { it?.contains(q, ignoreCase = true) == true }
    val recent = data.recentMedia.filter { matches(it.title, it.url, it.pageUrl) }
    val history = data.history.withIndex().filter { matches(it.value.title, it.value.url) }
    val bookmarks = data.bookmarks.filter { matches(it.title, it.url) }
    val allPages = remember(data.sites) { CastHub.library.mostVisitedPages() }
    val pages = allPages.filter { matches(it.title, it.url) }

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    fun endSelection() { selecting = false; selected = emptySet() }
    fun toggle(key: String) { selected = if (key in selected) selected - key else selected + key }
    BackHandler(enabled = selecting) { endSelection() }
    val visibleKeys = when (section) {
        LibrarySection.History -> history.map { historyKey(it.value) }
        LibrarySection.Bookmarks -> bookmarks.map { it.url }
        else -> emptyList()
    }

    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear && section == LibrarySection.History) {
        ClearHistoryDialog(onDismiss = { confirmClear = false })
    } else if (confirmClear) {
        ConfirmDialog("Clear ${section.title.lowercase()}?", "This can't be undone.", onDismiss = { confirmClear = false }) {
            when (section) {
                LibrarySection.Recent -> CastHub.library.clearRecent()
                LibrarySection.History -> CastHub.library.clearHistory()
                LibrarySection.MostVisited -> CastHub.library.clearMostVisited()
                else -> Unit
            }
            confirmClear = false
        }
    }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    if (confirmDeleteSelected) {
        val what = if (section == LibrarySection.Bookmarks) "bookmarks" else "history entries"
        ConfirmDialog("Delete selected items?", "${selected.size} $what will be deleted.", onDismiss = { confirmDeleteSelected = false }) {
            when (section) {
                LibrarySection.History -> CastHub.library.removeHistoryEntries(data.history.filter { historyKey(it) in selected })
                LibrarySection.Bookmarks -> CastHub.library.removeBookmarks(selected)
                else -> Unit
            }
            confirmDeleteSelected = false
            endSelection()
        }
    }
    var deleteId by remember { mutableStateOf<String?>(null) }
    deleteId?.let { id ->
        ConfirmDialog("Delete playlist?", data.playlists.firstOrNull { it.id == id }?.name.orEmpty(), onDismiss = { deleteId = null }) {
            CastHub.library.deletePlaylist(id)
            deleteId = null
        }
    }
    var renamePlaylist by remember { mutableStateOf<Playlist?>(null) }
    renamePlaylist?.let { playlist ->
        TextInputDialog("Rename playlist", playlist.name, "Name", password = false, onDismiss = { renamePlaylist = null }) {
            if (it.isNotBlank()) CastHub.library.renamePlaylist(playlist.id, it.trim())
            renamePlaylist = null
        }
    }
    var renameRecent by remember { mutableStateOf<SavedMedia?>(null) }
    renameRecent?.let { media ->
        TextInputDialog("Rename", media.title, "Title", password = false, onDismiss = { renameRecent = null }) {
            if (it.isNotBlank()) CastHub.library.renameRecent(media.url, it.trim())
            renameRecent = null
        }
    }
    var bookmarkEdit by remember { mutableStateOf<com.ruterika.castcore.library.Bookmark?>(null) }
    var bookmarkNew by remember { mutableStateOf(false) }
    if (bookmarkNew || bookmarkEdit != null) {
        val editing = bookmarkEdit
        BookmarkDialog(
            initialTitle = editing?.title.orEmpty(),
            initialUrl = editing?.url.orEmpty(),
            onDismiss = { bookmarkNew = false; bookmarkEdit = null }
        ) { title, url ->
            if (editing != null && editing.url != url) CastHub.library.removeBookmark(editing.url)
            if (!CastHub.library.isBookmarked(url)) CastHub.library.toggleBookmark(url, title)
            CastHub.library.renameBookmark(url, title.ifBlank { url })
            bookmarkNew = false; bookmarkEdit = null
        }
    }
    var bookmarkDelete by remember { mutableStateOf<com.ruterika.castcore.library.Bookmark?>(null) }
    bookmarkDelete?.let { b ->
        ConfirmDialog("Delete bookmark?", b.title, onDismiss = { bookmarkDelete = null }) {
            CastHub.library.removeBookmark(b.url)
            bookmarkDelete = null
        }
    }
    var newPlaylist by remember { mutableStateOf(false) }
    if (newPlaylist) {
        TextInputDialog("New playlist", "", "Name", password = false, onDismiss = { newPlaylist = false }) {
            CastHub.library.createPlaylist(it.trim())
            newPlaylist = false
        }
    }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (selecting) {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        navigationIcon = { IconButton(onClick = ::endSelection) { Icon(Icons.Filled.Close, "Cancel selection") } },
                        title = { Text("${selected.size} selected") },
                        actions = {
                            val all = visibleKeys.isNotEmpty() && selected.containsAll(visibleKeys)
                            IconButton(onClick = { selected = if (all) emptySet() else visibleKeys.toSet() }) {
                                Icon(if (all) Icons.Filled.DoneAll else Icons.Filled.SelectAll, if (all) "Deselect all" else "Select all")
                            }
                            IconButton(onClick = { confirmDeleteSelected = true }, enabled = selected.isNotEmpty()) {
                                Icon(Icons.Filled.Delete, "Delete selected items")
                            }
                        }
                    )
                } else {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                        title = { Text(section.title) },
                        actions = {
                            if (visibleKeys.isNotEmpty()) {
                                IconButton(onClick = { selecting = true }) { Icon(Icons.Filled.Checklist, "Select items") }
                            }
                            when (section) {
                                LibrarySection.Playlists -> IconButton(onClick = { newPlaylist = true }) { Icon(Icons.Filled.Add, "New playlist") }
                                LibrarySection.Bookmarks -> IconButton(onClick = { bookmarkNew = true }) { Icon(Icons.Filled.Add, "Add bookmark") }
                                LibrarySection.Recent, LibrarySection.History, LibrarySection.MostVisited ->
                                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.DeleteSweep, "Clear") }
                                else -> Unit
                            }
                        }
                    )
                }
            },
            bottomBar = { MiniController() },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val searchable = when (section) {
                    LibrarySection.Recent -> data.recentMedia.isNotEmpty()
                    LibrarySection.History -> data.history.isNotEmpty()
                    LibrarySection.Bookmarks -> data.bookmarks.isNotEmpty()
                    LibrarySection.MostVisited -> allPages.isNotEmpty()
                    else -> false
                }
                if (searchable || q.isNotEmpty()) {
                    item(key = "search") { LibrarySearchField(query, "Search ${section.title.lowercase()}") { query = it } }
                }
                when (section) {
                    LibrarySection.Recent -> {
                        if (data.recentMedia.isEmpty()) item { EmptyText("Videos you cast show up here.") }
                        else if (recent.isEmpty()) noResults(q)
                        itemsIndexed(recent, key = { _, m -> m.url }) { _, media ->
                            MediaRow(
                                media,
                                onClick = { PhonePlayer.playOrCast(context, listOf(media), 0, onCast) },
                                trailing = {
                                    IconButton(onClick = { PhonePlayer.play(context, listOf(media)) }) {
                                        Icon(Icons.Filled.Smartphone, "Play on phone")
                                    }
                                    RecentMediaMenu(
                                        media = media,
                                        onCast = { onCast(listOf(media), 0) },
                                        onPlayOnPhone = { PhonePlayer.play(context, listOf(media)) },
                                        onPlayAsLive = { onCast(listOf(media.copy(isLive = true, durationMs = 0L)), 0) },
                                        onAddToPlaylist = { addToPlaylist(listOf(media)) },
                                        onDownload = { download(media) }.takeIf { isRemote(media) && !media.isLive },
                                        onOpenPage = media.pageUrl?.takeIf { it.startsWith("http") }?.let { page -> { onOpenUrl(page) } },
                                        onOpenWith = { openInOtherApp(context, media) },
                                        onRename = { renameRecent = media },
                                        onCopyUrl = { copyUrl(context, media.url) },
                                        onRemove = { CastHub.library.removeRecent(media.url) }
                                    )
                                }
                            )
                        }
                    }
                    LibrarySection.Playlists -> {
                        if (data.playlists.isEmpty()) item { EmptyText("Use \"Add to playlist\" on any video, or create one with +.") }
                        itemsIndexed(data.playlists, key = { _, p -> p.id }) { _, playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                onClick = { onOpenPlaylist(playlist.id) },
                                onPlayAll = {
                                    PhonePlayer.playPlaylistOrCast(context, playlist, 0) { items, index ->
                                        CastHub.markPlaylistCast(items, playlist)
                                        onCast(items, index)
                                    }
                                },
                                onPlayOnPhone = { PhonePlayer.playPlaylist(context, playlist, 0) },
                                onRename = { renamePlaylist = playlist },
                                onDelete = { deleteId = playlist.id }
                            )
                        }
                    }
                    LibrarySection.Bookmarks -> {
                        if (data.bookmarks.isEmpty()) item { EmptyText("Bookmark pages from the browser menu.") }
                        else if (bookmarks.isEmpty()) noResults(q)
                        itemsIndexed(bookmarks, key = { _, b -> b.url }) { _, b ->
                            LinkRow(
                                b.title, b.url, Icons.Filled.Bookmark,
                                iconUrl = b.iconUrl ?: LibraryStore.faviconUrl(b.url),
                                onClick = { if (selecting) toggle(b.url) else onOpenUrl(b.url) },
                                onLongClick = { if (!selecting) { selecting = true; selected = setOf(b.url) } },
                                selecting = selecting,
                                selected = b.url in selected,
                                onRemove = { bookmarkDelete = b },
                                onEdit = { bookmarkEdit = b }
                            )
                        }
                    }
                    LibrarySection.History -> {
                        if (data.history.isEmpty()) item { EmptyText("No browsing history.") }
                        else if (history.isEmpty()) noResults(q)
                        // Newest first, so grouping keeps the days in order.
                        history.groupBy { dayStart(it.value.visitedAt) }.forEach { (day, entries) ->
                            item(key = "day:$day") { DayHeader(dayLabel(day)) }
                            itemsIndexed(entries, key = { _, (i, h) -> "h:$i:${h.visitedAt}" }) { _, (_, h) ->
                                val key = historyKey(h)
                                LinkRow(
                                    h.title, "${timeText(h.visitedAt)} · ${h.url}", Icons.Filled.Public,
                                    onClick = { if (selecting) toggle(key) else onOpenUrl(h.url) },
                                    onLongClick = { if (!selecting) { selecting = true; selected = setOf(key) } },
                                    selecting = selecting,
                                    selected = key in selected,
                                    menu = { dismiss ->
                                        DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(Icons.Filled.Language, null) }, onClick = { dismiss(); onOpenUrl(h.url) })
                                        if (onOpenUrlInNewTab != null) {
                                            DropdownMenuItem(text = { Text("Open in new tab") }, leadingIcon = { Icon(Icons.Filled.Tab, null) }, onClick = { dismiss(); onOpenUrlInNewTab(h.url) })
                                        }
                                        DropdownMenuItem(text = { Text("Copy URL") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { dismiss(); copyUrl(context, h.url) })
                                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { dismiss(); CastHub.library.removeHistoryEntry(h) })
                                    }
                                )
                            }
                        }
                    }
                    LibrarySection.Downloads -> downloadsSection(data.downloads, onCast, onDownloadRemoved)
                    LibrarySection.MostVisited -> {
                        if (allPages.isEmpty()) item { EmptyText("Sites you visit often show up here.") }
                        else if (pages.isEmpty()) noResults(q)
                        itemsIndexed(pages, key = { i, s -> "$i:${s.url}" }) { _, site ->
                            val visits = if (site.count == 1) "1 visit" else "${site.count} visits"
                            LinkRow(
                                site.title, "$visits · ${site.url.substringAfter("://")}", Icons.Filled.TrendingUp,
                                onClick = { onOpenUrl(site.url) },
                                menu = { dismiss ->
                                    DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(Icons.Filled.Language, null) }, onClick = { dismiss(); onOpenUrl(site.url) })
                                    DropdownMenuItem(text = { Text("Copy URL") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { dismiss(); copyUrl(context, site.url) })
                                    DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { dismiss(); CastHub.library.removeSitePage(site.url) })
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Recent media item menu. */
@Composable
private fun RecentMediaMenu(
    media: SavedMedia,
    onCast: () -> Unit,
    onPlayOnPhone: () -> Unit,
    onPlayAsLive: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: (() -> Unit)?,
    onOpenPage: (() -> Unit)?,
    onOpenWith: () -> Unit,
    onRename: () -> Unit,
    onCopyUrl: () -> Unit,
    onRemove: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Cast") }, leadingIcon = { Icon(Icons.Filled.Cast, null) }, onClick = { menu = false; onCast() })
            DropdownMenuItem(text = { Text("Play on phone") }, leadingIcon = { Icon(Icons.Filled.Smartphone, null) }, onClick = { menu = false; onPlayOnPhone() })
            if (!media.isLive) {
                DropdownMenuItem(text = { Text("Play as live") }, leadingIcon = { Icon(Icons.Filled.LiveTv, null) }, onClick = { menu = false; onPlayAsLive() })
            }
            DropdownMenuItem(text = { Text("Add to playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }, onClick = { menu = false; onAddToPlaylist() })
            if (onDownload != null) {
                DropdownMenuItem(text = { Text("Download") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { menu = false; onDownload() })
            }
            if (onOpenPage != null) {
                DropdownMenuItem(text = { Text("Open web page") }, leadingIcon = { Icon(Icons.Filled.Language, null) }, onClick = { menu = false; onOpenPage() })
            }
            DropdownMenuItem(text = { Text("Open in another app") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = { menu = false; onOpenWith() })
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Copy URL") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; onCopyUrl() })
            DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onRemove() })
        }
    }
}

@Composable
private fun LibrarySearchField(query: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear search") } }) else null,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DayHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

private fun LazyListScope.noResults(query: String) {
    item(key = "no_results") { EmptyText("Nothing matches \"$query\".") }
}

private fun historyKey(h: com.ruterika.castcore.library.HistoryEntry) = "${h.visitedAt}|${h.url}"

/** Midnight (local time) of the day [ms] falls in. */
private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** History day header: Today / Yesterday / the date. */
private fun dayLabel(day: Long): String {
    val today = dayStart(System.currentTimeMillis())
    val yesterday = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
    return when (day) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(day))
    }
}

private fun timeText(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

private fun copyUrl(context: Context, url: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", url))
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
}

/** A playlist in the list: cover, name, item count and its menu. */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayOnPhone: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistCover(playlist, 56.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    playlistSummary(playlist),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (playlist.entries.isNotEmpty()) IconButton(onClick = onPlayAll) { Icon(Icons.Filled.PlayArrow, "Play all on TV") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (playlist.entries.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Play all") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = { menu = false; onPlayAll() })
                        DropdownMenuItem(text = { Text("Play all on phone") }, leadingIcon = { Icon(Icons.Filled.Smartphone, null) }, onClick = { menu = false; onPlayOnPhone() })
                    }
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private fun playlistSummary(playlist: Playlist): String = buildList {
    add(itemCountText(playlist.entries.size))
    if (playlist.hasIntroOutro) add("skips intro/outro")
    if (playlist.autoRemovePlayed) add("removes played items")
}.joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistId: String,
    onBack: () -> Unit,
    onCast: (List<SavedMedia>, Int) -> Unit,
    onOpenUrl: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val data by CastHub.library.data.collectAsStateWithLifecycle()
    val playlist = data.playlists.firstOrNull { it.id == playlistId }
    val entries = playlist?.entries.orEmpty()
    val recentByUrl = remember(data.recentMedia) { data.recentMedia.associateBy { it.url } }
    val download = rememberDownloadStarter()

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    fun endSelection() { selecting = false; selected = emptySet() }
    BackHandler(enabled = selecting) { endSelection() }

    fun removeWithUndo(ids: Set<String>) {
        val removed = CastHub.library.removeEntries(playlistId, ids)
        if (removed.isEmpty()) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                if (removed.size == 1) "Playlist item was removed" else "${removed.size} items were removed",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) CastHub.library.restoreEntries(playlistId, removed)
        }
    }

    /** Plays the playlist from [index]: on the TV, or on the phone when "Always play in app" is on. */
    fun castAll(index: Int) {
        val p = playlist ?: return
        PhonePlayer.playPlaylistOrCast(context, p, index) { items, i ->
            CastHub.markPlaylistCast(items, p)
            onCast(items, i)
        }
    }

    /** The playlist on the phone from [index], with its intro/outro skipping. */
    fun playOnPhone(index: Int) {
        playlist?.let { PhonePlayer.playPlaylist(context, it, index) }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────
    var rename by remember { mutableStateOf(false) }
    if (rename && playlist != null) {
        TextInputDialog("Rename playlist", playlist.name, "Name", password = false, onDismiss = { rename = false }) {
            if (it.isNotBlank()) CastHub.library.renamePlaylist(playlist.id, it.trim())
            rename = false
        }
    }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear && playlist != null) {
        ConfirmDialog("Remove all items?", "The playlist will be cleared.", onDismiss = { confirmClear = false }) {
            CastHub.library.clearPlaylist(playlist.id)
            confirmClear = false
        }
    }
    var confirmRemoveSelected by remember { mutableStateOf(false) }
    if (confirmRemoveSelected) {
        ConfirmDialog("Remove selected items?", "The selected items will be removed from this playlist.", onDismiss = { confirmRemoveSelected = false }) {
            confirmRemoveSelected = false
            removeWithUndo(selected)
            endSelection()
        }
    }
    var sortDialog by remember { mutableStateOf(false) }
    if (sortDialog && playlist != null) {
        SortDialog(playlist, onDismiss = { sortDialog = false }) { sort, descending ->
            CastHub.library.sortPlaylist(playlist.id, sort, descending)
            sortDialog = false
        }
    }
    var introDialog by remember { mutableStateOf(false) }
    if (introDialog && playlist != null) {
        IntroOutroDialog(playlist, onDismiss = { introDialog = false }) { intro, after, outro ->
            CastHub.library.setIntroOutro(playlist.id, intro, after, outro)
            introDialog = false
        }
    }
    var renameEntry by remember { mutableStateOf<PlaylistEntry?>(null) }
    renameEntry?.let { entry ->
        TextInputDialog("Rename", entry.media.title, "Title", password = false, onDismiss = { renameEntry = null }) {
            if (it.isNotBlank()) CastHub.library.renameEntry(playlistId, entry.id, it.trim())
            renameEntry = null
        }
    }
    /** Entries waiting for a target playlist; second = move instead of copy. */
    var transfer by remember { mutableStateOf<Pair<Set<String>, Boolean>?>(null) }
    transfer?.let { (ids, move) ->
        PlaylistChooserDialog(
            title = if (move) "Move to playlist" else "Copy to playlist",
            excludeId = playlistId,
            onDismiss = { transfer = null }
        ) { targetId, _ ->
            transfer = null
            val count = if (move) CastHub.library.moveEntries(playlistId, ids, targetId) else CastHub.library.copyEntries(playlistId, ids, targetId)
            if (count > 0) Toast.makeText(context, if (move) "Moved to playlist" else "Copied to playlist", Toast.LENGTH_SHORT).show()
            endSelection()
        }
    }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (selecting) {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        navigationIcon = { IconButton(onClick = ::endSelection) { Icon(Icons.Filled.Close, "Cancel selection") } },
                        title = { Text("${selected.size} selected") },
                        actions = {
                            val all = entries.isNotEmpty() && selected.size == entries.size
                            IconButton(onClick = { selected = if (all) emptySet() else entries.map { it.id }.toSet() }) {
                                Icon(if (all) Icons.Filled.DoneAll else Icons.Filled.SelectAll, if (all) "Deselect all items" else "Select all items")
                            }
                            IconButton(onClick = { confirmRemoveSelected = true }, enabled = selected.isNotEmpty()) {
                                Icon(Icons.Filled.Delete, "Remove selected items")
                            }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = selected.isNotEmpty()) { Icon(Icons.Filled.MoreVert, "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Copy selected items to playlist") },
                                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                        onClick = { menu = false; transfer = selected to false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Move selected items to playlist") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                        onClick = { menu = false; transfer = selected to true }
                                    )
                                }
                            }
                        }
                    )
                } else {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                        title = { Text(playlist?.name ?: "Playlist", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        actions = {
                            if (playlist != null && entries.isNotEmpty()) {
                                IconButton(onClick = { castAll(0) }) { Icon(Icons.Filled.PlayArrow, "Play all on TV") }
                            }
                            if (playlist != null) PlaylistMenu(
                                playlist = playlist,
                                onRename = { rename = true },
                                onSort = { sortDialog = true },
                                onIntroOutro = { introDialog = true },
                                onPlayOnPhone = { playOnPhone(0) },
                                onSelect = { selecting = true },
                                onClear = { confirmClear = true }
                            )
                        }
                    )
                }
            },
            bottomBar = { MiniController() }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (playlist != null && (playlist.hasIntroOutro || playlist.autoRemovePlayed)) {
                    item(key = "info") { PlaylistInfo(playlist, onClick = { introDialog = true }) }
                }
                if (entries.isEmpty()) item(key = "empty") { EmptyText("This playlist is empty. Use \"Add to playlist\" on any video.") }
                itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
                    val recent = recentByUrl[entry.media.url]
                    // Resume progress comes from Recent media (positions are tracked by URL).
                    val media = entry.media.copy(
                        positionMs = recent?.positionMs ?: 0L,
                        durationMs = recent?.durationMs?.takeIf { it > 0 } ?: entry.media.durationMs
                    )
                    PlaylistEntryRow(
                        modifier = Modifier.animateItem(),
                        media = media,
                        selecting = selecting,
                        selected = entry.id in selected,
                        canMoveUp = index > 0,
                        canMoveDown = index < entries.lastIndex,
                        onClick = {
                            if (selecting) selected = if (entry.id in selected) selected - entry.id else selected + entry.id
                            else castAll(index)
                        },
                        onLongClick = {
                            if (!selecting) { selecting = true; selected = setOf(entry.id) }
                        },
                        onMove = { delta -> CastHub.library.moveEntry(playlistId, entry.id, delta) },
                        onCastOnly = { onCast(listOf(entry.media), 0) },
                        onPlayOnPhone = { playOnPhone(index) },
                        onDownload = { download(entry.media) }.takeIf { isRemote(entry.media) && !entry.media.isLive },
                        onOpenPage = entry.media.pageUrl?.takeIf { it.startsWith("http") }?.let { page -> { onOpenUrl(page) } },
                        onOpenWith = { openInOtherApp(context, entry.media) },
                        onRename = { renameEntry = entry },
                        onCopy = { transfer = setOf(entry.id) to false },
                        onMoveTo = { transfer = setOf(entry.id) to true },
                        onRemove = { removeWithUndo(setOf(entry.id)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistMenu(
    playlist: Playlist,
    onRename: () -> Unit,
    onSort: () -> Unit,
    onIntroOutro: () -> Unit,
    onPlayOnPhone: () -> Unit,
    onSelect: () -> Unit,
    onClear: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val hasItems = playlist.entries.isNotEmpty()
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (hasItems) {
                DropdownMenuItem(text = { Text("Play all on phone") }, leadingIcon = { Icon(Icons.Filled.Smartphone, null) }, onClick = { menu = false; onPlayOnPhone() })
            }
            DropdownMenuItem(text = { Text("Rename playlist") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onRename() })
            if (hasItems) {
                DropdownMenuItem(text = { Text("Sort by…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) }, onClick = { menu = false; onSort() })
            }
            DropdownMenuItem(text = { Text("Skip intro / outro…") }, leadingIcon = { Icon(Icons.Filled.SkipNext, null) }, onClick = { menu = false; onIntroOutro() })
            DropdownMenuItem(
                text = { Text("Remove played items") },
                leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                trailingIcon = { Checkbox(checked = playlist.autoRemovePlayed, onCheckedChange = null) },
                onClick = { CastHub.library.setAutoRemovePlayed(playlist.id, !playlist.autoRemovePlayed) }
            )
            if (hasItems) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Select items") }, leadingIcon = { Icon(Icons.Filled.Checklist, null) }, onClick = { menu = false; onSelect() })
                DropdownMenuItem(text = { Text("Remove all items") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onClear() })
            }
        }
    }
}

@Composable
private fun PlaylistInfo(playlist: Playlist, onClick: () -> Unit) {
    val parts = buildList {
        if (playlist.skipIntroMs > 0) {
            add(
                "Skips ${formatTime(playlist.skipIntroMs)} intro" +
                    if (playlist.skipIntroAfterMs > 0) " after ${formatTime(playlist.skipIntroAfterMs)}" else ""
            )
        }
        if (playlist.skipOutroMs > 0) add("skips the last ${formatTime(playlist.skipOutroMs)}")
        if (playlist.autoRemovePlayed) add("played items are removed")
    }
    Text(
        parts.joinToString(" · ").replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

/** One playlist item: drag handle to reorder, long press to select, menu with the item actions. */
@Composable
private fun PlaylistEntryRow(
    modifier: Modifier,
    media: SavedMedia,
    selecting: Boolean,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: (Int) -> Boolean,
    onCastOnly: () -> Unit,
    onPlayOnPhone: () -> Unit,
    onDownload: (() -> Unit)?,
    onOpenPage: (() -> Unit)?,
    onOpenWith: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMoveTo: () -> Unit,
    onRemove: () -> Unit
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var rowHeight by remember { mutableIntStateOf(0) }
    val spacingPx = with(LocalDensity.current) { 8.dp.toPx() }
    var menu by remember { mutableStateOf(false) }
    Box(
        (if (dragging) Modifier else modifier)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = dragOffset }
            .onSizeChanged { rowHeight = it.height }
    ) {
        MediaRow(
            media = media,
            onClick = onClick,
            onLongClick = onLongClick,
            selected = selected || dragging,
            leading = if (selecting) ({ Checkbox(checked = selected, onCheckedChange = { onClick() }) }) else null,
            trailing = {
                if (!selecting) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Cast without starting playlist") }, leadingIcon = { Icon(Icons.Filled.Cast, null) }, onClick = { menu = false; onCastOnly() })
                            DropdownMenuItem(text = { Text("Play on phone") }, leadingIcon = { Icon(Icons.Filled.Smartphone, null) }, onClick = { menu = false; onPlayOnPhone() })
                            if (onDownload != null) {
                                DropdownMenuItem(text = { Text("Download") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { menu = false; onDownload() })
                            }
                            if (onOpenPage != null) {
                                DropdownMenuItem(text = { Text("Open web page") }, leadingIcon = { Icon(Icons.Filled.Language, null) }, onClick = { menu = false; onOpenPage() })
                            }
                            DropdownMenuItem(text = { Text("Open in another app") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = { menu = false; onOpenWith() })
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Copy to playlist") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; onCopy() })
                            DropdownMenuItem(text = { Text("Move to playlist") }, leadingIcon = { Icon(Icons.Filled.ExitToApp, null) }, onClick = { menu = false; onMoveTo() })
                            if (canMoveUp) {
                                DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) }, onClick = { menu = false; onMove(-1) })
                            }
                            if (canMoveDown) {
                                DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) }, onClick = { menu = false; onMove(1) })
                            }
                            DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onRemove() })
                        }
                    }
                    Icon(
                        Icons.Filled.DragHandle,
                        "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(36.dp)
                            .padding(6.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { dragging = true },
                                    onDragEnd = { dragging = false; dragOffset = 0f },
                                    onDragCancel = { dragging = false; dragOffset = 0f }
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val step = rowHeight + spacingPx
                                    if (step > 0f) {
                                        if (dragOffset > step / 2 && onMove(1)) dragOffset -= step
                                        else if (dragOffset < -step / 2 && onMove(-1)) dragOffset += step
                                    }
                                }
                            }
                    )
                }
            }
        )
    }
}

@Composable
private fun SortDialog(playlist: Playlist, onDismiss: () -> Unit, onApply: (PlaylistSort, Boolean) -> Unit) {
    var sort by remember { mutableStateOf(playlist.sort ?: PlaylistSort.DATE_ADDED) }
    var descending by remember { mutableStateOf(playlist.sortDescending) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort by") },
        text = {
            Column {
                listOf(PlaylistSort.NAME to "Name", PlaylistSort.DATE_ADDED to "Date added", PlaylistSort.SHUFFLE to "Shuffle").forEach { (value, label) ->
                    RadioRow(label, selected = sort == value) { sort = value }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val enabled = sort != PlaylistSort.SHUFFLE
                RadioRow("Ascending", selected = !descending, enabled = enabled) { descending = false }
                RadioRow("Descending", selected = descending, enabled = enabled) { descending = true }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(sort, descending) }) { Text("Sort") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Seconds per playlist; empty = off. */
@Composable
private fun IntroOutroDialog(playlist: Playlist, onDismiss: () -> Unit, onSave: (Long, Long, Long) -> Unit) {
    fun secondsText(ms: Long) = if (ms > 0) (ms / 1000).toString() else ""
    var intro by remember { mutableStateOf(secondsText(playlist.skipIntroMs)) }
    var after by remember { mutableStateOf(secondsText(playlist.skipIntroAfterMs)) }
    var outro by remember { mutableStateOf(secondsText(playlist.skipOutroMs)) }
    fun ms(text: String) = (text.trim().toLongOrNull() ?: 0L).coerceAtLeast(0L) * 1000L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skip intro / outro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Applies to every item of this playlist when it plays on the TV. Leave a field empty to turn it off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SecondsField("Skip intro (seconds)", intro) { intro = it }
                SecondsField("Intro starts after (seconds)", after) { after = it }
                SecondsField("Skip outro (seconds)", outro) { outro = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ms(intro), if (ms(intro) > 0) ms(after) else 0L, ms(outro)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SecondsField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun isRemote(media: SavedMedia) = media.url.startsWith("http://") || media.url.startsWith("https://")

/** "Open in another app": the system chooser for the media URL. */
private fun openInOtherApp(context: Context, media: SavedMedia) {
    val mime = media.mimeType ?: if (media.url.contains(".m3u8")) "application/x-mpegURL" else "video/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(media.url), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
        .onFailure { Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaRow(
    media: SavedMedia,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        colors = if (selected) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.elevatedCardColors(),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                val thumb = media.posterUrl ?: localThumbnail(media.url)
                if (thumb != null) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(media.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        media.url.startsWith("content://") || media.url.startsWith("file://") -> "Phone file"
                        else -> android.net.Uri.parse(media.pageUrl ?: media.url).host ?: media.url
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (media.durationMs > 0 && media.positionMs > 0) {
                    LinearProgressIndicator(
                        progress = { (media.positionMs.toFloat() / media.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
            trailing?.invoke()
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove") }
        }
    }
}

/** Video thumbnail of a phone file (MediaStore), loaded off the main thread. */
@Composable
internal fun localThumbnail(url: String): android.graphics.Bitmap? {
    if (!url.startsWith("content://")) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, url) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(android.net.Uri.parse(url), android.util.Size(256, 256), null)
                } else null
            }.getOrNull()
        }
    }
    return bitmap
}

/**
 * A web page row (bookmark, history, most visited). [iconUrl] is a favicon shown
 * in place of [icon] once it loads. While [selecting], a checkbox replaces the
 * actions; [menu] adds a "More" menu (its items call the given dismiss first).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    iconUrl: String? = null,
    onLongClick: (() -> Unit)? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null
) {
    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        colors = if (selected) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.elevatedCardColors(),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onClick() }, modifier = Modifier.padding(end = 4.dp))
            LinkIcon(icon, iconUrl)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!selecting) {
                if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit") }
                if (menu != null) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
                    }
                } else if (onRemove != null) {
                    IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove") }
                }
            }
        }
    }
}

/** The page favicon, or [icon] until it loads (and when it can't). */
@Composable
private fun LinkIcon(icon: ImageVector, iconUrl: String?) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        var loaded by remember(iconUrl) { mutableStateOf(false) }
        if (!loaded) Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun BookmarkDialog(initialTitle: String, initialUrl: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(initialTitle) }
    var url by remember { mutableStateOf(initialUrl) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialUrl.isEmpty()) "Add bookmark" else "Edit bookmark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(url, { url = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = url.isNotBlank(), onClick = {
                onSave(title.trim(), CastHub.settings.value.resolveInput(url))
            }) { Text("Save") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** "clear browsing data" ranges. */
@Composable
private fun ClearHistoryDialog(onDismiss: () -> Unit) {
    val hour = 60 * 60 * 1000L
    val ranges = listOf(
        "Last hour" to hour,
        "Last 24 hours" to 24 * hour,
        "Last 7 days" to 7 * 24 * hour,
        "Last 4 weeks" to 28 * 24 * hour,
        "All time" to 0L
    )
    var selected by remember { mutableStateOf(ranges.last()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear history") },
        text = {
            Column {
                ranges.forEach { range ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = range }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(selected = selected == range, onClick = { selected = range })
                        Text(range.first)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val window = selected.second
                CastHub.library.clearHistory(if (window == 0L) 0L else System.currentTimeMillis() - window)
                onDismiss()
            }) { Text("Clear") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(24.dp)
    )
}
