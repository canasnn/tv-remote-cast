package com.canasnn.tvremote.ui.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.canasnn.tvremote.cast.ui.librarySection
import com.canasnn.tvremote.cast.ui.iptvSection
import com.canasnn.tvremote.cast.ui.webHomeSection
import kotlinx.coroutines.launch

/**
 * Unified enum for bucket-level navigation state.
 * Each tab starts showing "buckets" (albums/folders) and drills into items.
 */
private sealed interface BucketSelection {
    object None : BucketSelection
    data class Photo(val id: String, val name: String) : BucketSelection
    data class Video(val id: String, val name: String) : BucketSelection
    data class Audio(val id: String, val name: String) : BucketSelection
}

/** Where the Videos / Photos / Audio tabs read from. */
private enum class LocalSource { Gallery, Folders }

private suspend fun loadBucket(context: Context, bucket: BucketSelection): List<MediaItemUi> = when (bucket) {
    is BucketSelection.Photo -> queryPhotosInAlbum(context, bucket.id)
    is BucketSelection.Video -> queryVideosInFolder(context, bucket.id)
    is BucketSelection.Audio -> queryAudiosInAlbum(context, bucket.id)
    BucketSelection.None -> emptyList()
}

/** Callbacks and selection state shared by every local media item (grid cells and rows). */
private class LocalItemActions(
    val isConnected: Boolean,
    val selection: Set<String>,
    val menuFor: String?,
    val onClick: (MediaItemUi) -> Unit,
    val onLongClick: (MediaItemUi) -> Unit,
    val onMenu: (MediaItemUi?) -> Unit,
    val onCast: (MediaItemUi) -> Unit,
    val onAddToPlaylist: (MediaItemUi) -> Unit
) {
    val selecting: Boolean get() = selection.isNotEmpty()
    fun isSelected(item: MediaItemUi) = item.uri in selection
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CastScreen(
    modifier: Modifier = Modifier,
    isConnected: Boolean,
    deviceName: String?,
    castState: CastPlaybackUiState,
    onOpenCastPlayer: (MediaItemUi) -> Unit,
    onOpenBrowser: (String?) -> Unit = {},
    onOpenIptvSource: (String) -> Unit = {},
    onOpenLibrary: (com.canasnn.tvremote.cast.ui.LibrarySection) -> Unit = {},
    onCastSaved: (List<com.ruterika.castcore.library.SavedMedia>, Int) -> Unit = { _, _ -> },
    onOpenCastSettings: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(CastTab.Web) }
    // Launcher shortcuts "Local media" / "IPTV" pick the tab.
    val requestedTab by com.canasnn.tvremote.cast.intake.Intake.castTabRequest.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(requestedTab) {
        requestedTab?.let { tab ->
            selectedTab = tab
            com.canasnn.tvremote.cast.intake.Intake.consumeCastTab()
        }
    }
    var showAddIptv by remember { mutableStateOf(false) }
    if (showAddIptv) {
        com.canasnn.tvremote.cast.ui.AddIptvDialog(
            onDismiss = { showAddIptv = false },
            onAdded = { id -> showAddIptv = false; onOpenIptvSource(id) }
        )
    }
    var bucket by remember { mutableStateOf<BucketSelection>(BucketSelection.None) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissions = rememberMediaPermissions()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissions.refresh(context)
    }
    // Access can change outside the app (settings, Android 14 "Select more").
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissions.refresh(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isLocalTab = selectedTab == CastTab.Videos || selectedTab == CastTab.Photos || selectedTab == CastTab.Audio
    var source by rememberSaveable { mutableStateOf(LocalSource.Gallery) }
    var query by rememberSaveable(selectedTab) { mutableStateOf("") }
    val searching = query.isNotBlank()

    // ── Sort (persisted per tab; the folder browser has its own) ─────────────
    val sortKey = if (source == LocalSource.Folders) "folders" else selectedTab.name
    var sort by remember(sortKey) {
        mutableStateOf(
            LocalMediaPrefs.sort(
                context, sortKey,
                default = if (source == LocalSource.Folders) LocalSort(LocalSortField.NAME, ascending = true) else LocalSort()
            )
        )
    }
    val onSort: (LocalSort) -> Unit = { sort = it; LocalMediaPrefs.setSort(context, sortKey, it) }
    val sortFields = when {
        source == LocalSource.Folders || selectedTab == CastTab.Photos -> listOf(LocalSortField.NAME, LocalSortField.DATE, LocalSortField.SIZE)
        else -> LocalSortField.entries
    }

    // ── Photo albums ──────────────────────────────────────────────────────────
    val photoAlbums by produceState(emptyList<PhotoAlbum>(), permissions.revision, selectedTab) {
        value = if (permissions.canRead(CastTab.Photos) && selectedTab == CastTab.Photos) queryAlbums(context)
        else emptyList()
    }
    // ── Video folders ─────────────────────────────────────────────────────────
    val videoFolders by produceState(emptyList<VideoFolder>(), permissions.revision, selectedTab) {
        value = if (permissions.canRead(CastTab.Videos) && selectedTab == CastTab.Videos) queryVideoFolders(context)
        else emptyList()
    }
    // ── Audio albums ──────────────────────────────────────────────────────────
    val audioAlbums by produceState(emptyList<AudioAlbum>(), permissions.revision, selectedTab) {
        value = if (permissions.canRead(CastTab.Audio) && selectedTab == CastTab.Audio) queryAudioAlbums(context)
        else emptyList()
    }

    // ── Items inside selected bucket ──────────────────────────────────────────
    val bucketItems by produceState(emptyList<MediaItemUi>(), bucket, permissions.revision) {
        value = loadBucket(context, bucket)
    }
    // ── Whole tab, for searching across all albums ────────────────────────────
    val searchAll = isLocalTab && searching && source == LocalSource.Gallery && bucket == BucketSelection.None
    val allTabItems by produceState(emptyList<MediaItemUi>(), searchAll, selectedTab, permissions.revision) {
        value = if (searchAll && permissions.canRead(selectedTab)) queryMedia(context, selectedTab) else emptyList()
    }

    val bucketName: String? = when (val b = bucket) {
        is BucketSelection.Photo -> b.name
        is BucketSelection.Video -> b.name
        is BucketSelection.Audio -> b.name
        BucketSelection.None -> null
    }

    // ── Folder browser (Storage Access Framework) ─────────────────────────────
    var safRoots by remember { mutableStateOf(LocalMediaPrefs.safRoots(context)) }
    var safRoot by remember {
        mutableStateOf(safRoots.firstOrNull { it.uri == LocalMediaPrefs.lastRoot(context) } ?: safRoots.firstOrNull())
    }
    var safPath by remember(safRoot) { mutableStateOf(listOfNotNull(safRoot?.let(::safRootDir))) }
    var safReload by remember { mutableIntStateOf(0) }
    val browsingFolders = isLocalTab && source == LocalSource.Folders
    val safListing by produceState<SafListing>(SafListing.Loading, safRoot, safPath, safReload, browsingFolders) {
        val root = safRoot
        val dir = safPath.lastOrNull()
        if (!browsingFolders || root == null || dir == null) {
            value = SafListing.Ok(emptyList(), emptyList())
        } else {
            value = SafListing.Loading
            value = listSafFolder(context, root, dir)
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val root = LocalMediaPrefs.addSafRoot(context, uri)
        if (root == null) {
            Toast.makeText(context, "Couldn't open this folder", Toast.LENGTH_SHORT).show()
        } else {
            safRoots = LocalMediaPrefs.safRoots(context)
            safRoot = root
        }
    }

    // ── Casting helpers ───────────────────────────────────────────────────────
    /** Photos and audio play as a queue of their list (photos = slideshow); a video plays alone. */
    fun castFrom(list: List<MediaItemUi>, item: MediaItemUi) {
        // "Always play in app": videos and audio open in the phone player.
        if (item.kind != MediaKind.Photo && com.canasnn.tvremote.cast.CastHub.settings.value.phonePlayer.alwaysPlayInApp) {
            val queue = if (item.kind == MediaKind.Video) listOf(item) else list.filter { it.kind == item.kind }.ifEmpty { listOf(item) }
            playLocalOnPhone(context, queue, queue.indexOf(item).coerceAtLeast(0))
            return
        }
        if (item.kind == MediaKind.Video) {
            onCastSaved(listOf(item.toSavedMedia()), 0)
        } else {
            val queue = list.filter { it.kind == item.kind }.ifEmpty { listOf(item) }
            onCastSaved(queue.map { it.toSavedMedia() }, queue.indexOf(item).coerceAtLeast(0))
        }
    }
    fun castAll(list: List<MediaItemUi>) {
        if (list.isNotEmpty()) onCastSaved(list.map { it.toSavedMedia() }, 0)
    }
    val pickPlaylist = com.canasnn.tvremote.cast.ui.rememberPlaylistPicker()
    fun addAll(list: List<MediaItemUi>) { if (list.isNotEmpty()) pickPlaylist(list.map { it.toSavedMedia() }) }
    fun notConnected() = Toast.makeText(context, "Connect to a TV first", Toast.LENGTH_SHORT).show()

    // ── System file picker ────────────────────────────────────────────────────
    var picked by remember { mutableStateOf<List<MediaItemUi>>(emptyList()) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val items = pickedMediaItems(context, uris)
            when {
                items.isEmpty() -> Toast.makeText(context, "Only video, audio and image files can be cast", Toast.LENGTH_SHORT).show()
                items.size == 1 && isConnected -> castFrom(items, items.first())
                else -> picked = items
            }
        }
    }
    if (picked.isNotEmpty()) {
        PickedFilesDialog(
            items = picked,
            isConnected = isConnected,
            onCastAll = { castAll(picked); picked = emptyList() },
            onAddToPlaylist = { addAll(picked); picked = emptyList() },
            onDismiss = { picked = emptyList() }
        )
    }

    // ── What the list shows (search + sort applied) ───────────────────────────
    val safFiles = (safListing as? SafListing.Ok)?.files.orEmpty()
    val visibleItems = remember(isLocalTab, source, bucket, bucketItems, allTabItems, safFiles, query, sort) {
        when {
            !isLocalTab -> emptyList()
            source == LocalSource.Folders -> safFiles.matching(query).sortedLocal(sort)
            bucket != BucketSelection.None -> bucketItems.matching(query).sortedLocal(sort)
            query.isNotBlank() -> allTabItems.matching(query).sortedLocal(sort)
            else -> emptyList()
        }
    }

    // ── Multi-select ──────────────────────────────────────────────────────────
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedTab, bucket, source, safRoot, safPath) {
        selection = emptySet()
        menuFor = null
    }
    val selectedItems = visibleItems.filter { it.uri in selection }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(enabled = selection.isEmpty() && isLocalTab && source == LocalSource.Gallery && bucket != BucketSelection.None) {
        bucket = BucketSelection.None
    }
    BackHandler(enabled = selection.isEmpty() && browsingFolders && safPath.size > 1) { safPath = safPath.dropLast(1) }

    fun toggle(item: MediaItemUi) {
        selection = if (item.uri in selection) selection - item.uri else selection + item.uri
    }
    val actions = LocalItemActions(
        onAddToPlaylist = { item -> addAll(listOf(item)) },
        isConnected = isConnected,
        selection = selection,
        menuFor = menuFor,
        onClick = { item ->
            when {
                selection.isNotEmpty() -> toggle(item)
                isConnected -> castFrom(visibleItems, item)
                else -> notConnected()
            }
        },
        onLongClick = { item -> toggle(item) },
        onMenu = { item -> menuFor = item?.uri },
        onCast = { item -> if (isConnected) castFrom(visibleItems, item) else notConnected() }
    )

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Tab bar ───────────────────────────────────────────────────────────
        item {
            com.canasnn.tvremote.cast.ui.CastHubTabs(
                selected = selectedTab,
                onSelect = { tab ->
                    selectedTab = tab
                    bucket = BucketSelection.None
                },
                onOpenSettings = onOpenCastSettings
            )
        }

        // ── Status banner (only when no TV is connected) ──────────────────────
        if (!isConnected) item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (isConnected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = if (isConnected) {
                        if (castState.isCasting && castState.activeMedia != null)
                            "Casting: ${castState.activeMedia.title}"
                        else "Pick media to cast to ${deviceName ?: "your TV"}"
                    } else "Connect to a TV first",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { com.canasnn.tvremote.cast.ui.MiniController() }

        // ── Web, IPTV and library tabs ────────────────────────────────────────
        when (selectedTab) {
            CastTab.Web -> {
                webHomeSection(onOpenBrowser, onOpenLibrary, onCastSaved)
                return@LazyColumn
            }
            CastTab.Iptv -> {
                iptvSection(onOpenIptvSource, onCastSaved, onAddList = { showAddIptv = true })
                return@LazyColumn
            }
            CastTab.Library -> {
                librarySection(onOpenLibrary)
                return@LazyColumn
            }
            else -> Unit
        }

        // ── Play a link (video URL, m3u8 stream, …) ──────────────────────────
        if (selectedTab == CastTab.Link) {
            item {
                LinkCastCard(
                    enabled = isConnected,
                    onPlay = { item -> onCastSaved(listOf(item.toSavedMedia()), 0) }
                )
            }
            return@LazyColumn
        }

        // ── Gallery / Folders switch, file picker, search and sort ────────────
        item {
            LocalSourceRow(
                source = source,
                onSource = { source = it },
                onPickFiles = {
                    runCatching { filePicker.launch(arrayOf("video/*", "audio/*", "image/*")) }
                }
            )
        }
        if (source == LocalSource.Gallery && permissions.isPartialAccess && selectedTab != CastTab.Audio) {
            item {
                PartialAccessBanner(
                    onSelectMore = { launcher.launch(visualMediaPermissions()) },
                    onAllowAll = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                        }
                    }
                )
            }
        }
        item {
            SearchSortRow(
                query = query,
                onQuery = { query = it },
                sort = sort,
                fields = sortFields,
                onSort = onSort
            )
        }
        if (selection.isNotEmpty()) {
            stickyHeader(key = "selection") {
                SelectionBar(
                    count = selectedItems.size,
                    isConnected = isConnected,
                    onClose = { selection = emptySet() },
                    onSelectAll = { selection = visibleItems.map { it.uri }.toSet() },
                    onCast = { castAll(selectedItems); selection = emptySet() },
                    onAddToPlaylist = { addAll(selectedItems); selection = emptySet() },
                    onShare = { shareLocal(context, selectedItems) }
                )
            }
        }

        // ── Folder browser ────────────────────────────────────────────────────
        if (source == LocalSource.Folders) {
            safFolderSection(
                roots = safRoots,
                root = safRoot,
                path = safPath,
                listing = safListing,
                files = visibleItems,
                query = query,
                sort = sort,
                actions = actions,
                onSelectRoot = { root ->
                    safRoot = root
                    LocalMediaPrefs.setLastRoot(context, root.uri)
                },
                onRemoveRoot = { root ->
                    LocalMediaPrefs.removeSafRoot(context, root)
                    safRoots = LocalMediaPrefs.safRoots(context)
                    if (safRoot?.uri == root.uri) safRoot = safRoots.firstOrNull()
                },
                onAddRoot = { runCatching { folderPicker.launch(null) } },
                onOpenFolder = { folder -> safPath = safPath + SafDir(folder.name, folder.documentId) },
                onUp = { safPath = safPath.dropLast(1) },
                onRetry = { safReload++ },
                onCastAll = { if (isConnected) castAll(visibleItems) else notConnected() },
                onAddAll = { addAll(visibleItems) },
                onPlayAllOnPhone = { playLocalOnPhone(context, visibleItems.filter { it.kind != MediaKind.Photo }) },
                onSelectAll = { selection = visibleItems.map { it.uri }.toSet() }
            )
            return@LazyColumn
        }

        // ── Permission gate ───────────────────────────────────────────────────
        if (!permissions.canRead(selectedTab)) {
            item {
                ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Media access needed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Or use Folders / Pick files to cast without it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { launcher.launch(permissions.requiredPermissions) }) { Text("Grant Permission") }
                    }
                }
            }
            return@LazyColumn
        }

        // ── Back row when inside a bucket ─────────────────────────────────────
        if (bucketName != null) {
            item {
                FolderHeader(
                    title = bucketName,
                    showBack = true,
                    onBack = { bucket = BucketSelection.None },
                    castIcon = if (selectedTab == CastTab.Photos) Icons.Filled.Slideshow else Icons.Filled.PlayArrow,
                    castLabel = if (selectedTab == CastTab.Photos) "Start slideshow" else "Cast all",
                    castEnabled = isConnected && visibleItems.isNotEmpty(),
                    onCastAll = { castAll(visibleItems) },
                    onAddAll = { addAll(visibleItems) },
                    onPlayAllOnPhone = if (selectedTab == CastTab.Photos) null else { { playLocalOnPhone(context, visibleItems) } },
                    onSelectAll = { selection = visibleItems.map { it.uri }.toSet() }
                )
            }

            // Items inside the bucket
            if (visibleItems.isEmpty()) {
                item {
                    Text(
                        if (searching && bucketItems.isNotEmpty()) "No matches" else "No items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                localMediaGrid(selectedTab, visibleItems, actions)
            }
            return@LazyColumn
        }

        // ── Search across every album of the tab ──────────────────────────────
        if (searching) {
            item {
                Text(
                    "Results",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (visibleItems.isEmpty()) {
                item { Text("No matches", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                localMediaGrid(selectedTab, visibleItems, actions)
            }
            return@LazyColumn
        }

        // ── Bucket grid (top-level) ───────────────────────────────────────────
        val castBucket: (BucketSelection) -> Unit = { b ->
            if (!isConnected) notConnected()
            else scope.launch { castAll(loadBucket(context, b).sortedLocal(sort)) }
        }
        val addBucket: (BucketSelection) -> Unit = { b ->
            scope.launch { addAll(loadBucket(context, b).sortedLocal(sort)) }
        }
        when (selectedTab) {
            CastTab.Photos -> {
                item {
                    Text(
                        "Albums",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (photoAlbums.isEmpty()) {
                    item { Text("No albums found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(photoAlbums.sortedBuckets(sort, { it.name }, { it.count }).chunked(2)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { album ->
                                val b = BucketSelection.Photo(album.id, album.name)
                                PhotoAlbumCard(
                                    modifier = Modifier.weight(1f),
                                    album = album,
                                    castEnabled = isConnected,
                                    onClick = { bucket = b },
                                    onCastAll = { castBucket(b) },
                                    onAddAll = { addBucket(b) }
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            CastTab.Videos -> {
                item {
                    Text(
                        "Folders",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (videoFolders.isEmpty()) {
                    item { Text("No video folders found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(videoFolders.sortedBuckets(sort, { it.name }, { it.count }).chunked(2)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { folder ->
                                val b = BucketSelection.Video(folder.id, folder.name)
                                VideoFolderCard(
                                    modifier = Modifier.weight(1f),
                                    folder = folder,
                                    castEnabled = isConnected,
                                    onClick = { bucket = b },
                                    onCastAll = { castBucket(b) },
                                    onAddAll = { addBucket(b) }
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            CastTab.Audio -> {
                item {
                    Text(
                        "Albums",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (audioAlbums.isEmpty()) {
                    item { Text("No audio albums found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(audioAlbums.sortedBuckets(sort, { it.name }, { it.count }).chunked(2)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { album ->
                                val b = BucketSelection.Audio(album.id, album.name)
                                AudioAlbumCard(
                                    modifier = Modifier.weight(1f),
                                    album = album,
                                    castEnabled = isConnected,
                                    onClick = { bucket = b },
                                    onCastAll = { castBucket(b) },
                                    onAddAll = { addBucket(b) }
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            else -> {}
        }
    }
}

/** Albums / folders: by name, by item count (size, duration) or recency (query order). */
private fun <T> List<T>.sortedBuckets(sort: LocalSort, name: (T) -> String, count: (T) -> Int): List<T> = when (sort.field) {
    LocalSortField.NAME -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, name)).let { if (sort.ascending) it else it.reversed() }
    LocalSortField.SIZE, LocalSortField.DURATION -> sortedBy(count).let { if (sort.ascending) it else it.reversed() }
    LocalSortField.DATE -> if (sort.ascending) reversed() else this
}

/** Photos as a 3-column grid, videos and audio as 2-column cards. */
private fun LazyListScope.localMediaGrid(tab: CastTab, list: List<MediaItemUi>, actions: LocalItemActions) {
    if (tab == CastTab.Photos) {
        items(list.chunked(3)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { item ->
                    PhotoThumb(modifier = Modifier.weight(1f), item = item, actions = actions)
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else {
        items(list.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    MediaCard(modifier = Modifier.weight(1f), mediaItem = item, actions = actions)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ─── Folder browser (SAF) ─────────────────────────────────────────────────────

private fun LazyListScope.safFolderSection(
    roots: List<SafRoot>,
    root: SafRoot?,
    path: List<SafDir>,
    listing: SafListing,
    files: List<MediaItemUi>,
    query: String,
    sort: LocalSort,
    actions: LocalItemActions,
    onSelectRoot: (SafRoot) -> Unit,
    onRemoveRoot: (SafRoot) -> Unit,
    onAddRoot: () -> Unit,
    onOpenFolder: (SafFolder) -> Unit,
    onUp: () -> Unit,
    onRetry: () -> Unit,
    onCastAll: () -> Unit,
    onAddAll: () -> Unit,
    onPlayAllOnPhone: () -> Unit,
    onSelectAll: () -> Unit
) {
    if (roots.isEmpty() || root == null) {
        item {
            ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Browse a folder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Pick a folder (Downloads, SD card, USB drive…) to browse it and cast its videos, music and photos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onAddRoot) { Text("Choose folder") }
                }
            }
        }
        return
    }

    // Saved folders as chips; the selected one can be removed.
    item {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            roots.forEach { r ->
                val selected = r.uri == root.uri
                FilterChip(
                    selected = selected,
                    onClick = { onSelectRoot(r) },
                    label = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove folder",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .clickable { onRemoveRoot(r) }
                            )
                        }
                    } else null
                )
            }
            AssistChip(
                onClick = onAddRoot,
                label = { Text("Add folder") },
                leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)) }
            )
        }
    }

    item {
        FolderHeader(
            title = path.joinToString(" / ") { it.name }.ifBlank { root.title },
            showBack = path.size > 1,
            onBack = onUp,
            castIcon = Icons.Filled.PlayArrow,
            castLabel = "Cast all",
            castEnabled = actions.isConnected && files.isNotEmpty(),
            onCastAll = onCastAll,
            onAddAll = onAddAll,
            onPlayAllOnPhone = if (files.any { it.kind != MediaKind.Photo }) onPlayAllOnPhone else null,
            onSelectAll = onSelectAll
        )
    }

    when (listing) {
        SafListing.Loading -> item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        is SafListing.Error -> item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listing.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        is SafListing.Ok -> {
            val folders = listing.folders
                .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                .let { list ->
                    if (sort.field == LocalSortField.DATE) list.sortedBy { it.modifiedMs }.let { if (sort.ascending) it else it.reversed() }
                    else list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                }
            if (folders.isEmpty() && files.isEmpty()) {
                item {
                    Text(
                        if (query.isNotBlank()) "No matches" else "This folder is empty",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(folders, key = { "dir:" + it.documentId }) { folder ->
                LocalRow(
                    title = folder.name,
                    subtitle = "Folder",
                    selected = false,
                    onClick = { onOpenFolder(folder) },
                    onLongClick = null,
                    leading = {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.Center).size(26.dp)
                        )
                    },
                    trailing = {
                        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            }
            items(files, key = { "file:" + it.uri }) { item -> LocalFileRow(item, actions) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    leading: @Composable BoxScope.() -> Unit,
    trailing: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                content = leading
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
        }
    }
}

@Composable
private fun LocalFileRow(item: MediaItemUi, actions: LocalItemActions) {
    val context = LocalContext.current
    val selected = actions.isSelected(item)
    LocalRow(
        title = item.title,
        subtitle = formatFileInfo(context, item),
        selected = selected,
        onClick = { actions.onClick(item) },
        onLongClick = { actions.onLongClick(item) },
        leading = {
            val icon = when (item.kind) {
                MediaKind.Video -> Icons.Filled.PlayArrow
                MediaKind.Audio -> Icons.Filled.MusicNote
                MediaKind.Photo -> Icons.Filled.PhotoLibrary
            }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(22.dp)
            )
            if (item.thumbnailUri != null) {
                MediaArtwork(
                    model = item.thumbnailUri,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    isVideo = item.kind == MediaKind.Video,
                    modifier = Modifier.matchParentSize()
                )
            }
        },
        trailing = {
            if (actions.selecting) {
                SelectionMark(selected, Modifier.padding(horizontal = 12.dp))
            } else {
                Box {
                    IconButton(onClick = { actions.onMenu(item) }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LocalItemMenu(item, actions)
                }
            }
        }
    )
}

// ─── Toolbars ─────────────────────────────────────────────────────────────────

@Composable
private fun LocalSourceRow(source: LocalSource, onSource: (LocalSource) -> Unit, onPickFiles: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = source == LocalSource.Gallery,
            onClick = { onSource(LocalSource.Gallery) },
            label = { Text("Gallery") },
            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null, modifier = Modifier.size(18.dp)) }
        )
        FilterChip(
            selected = source == LocalSource.Folders,
            onClick = { onSource(LocalSource.Folders) },
            label = { Text("Folders") },
            leadingIcon = { Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = onPickFiles,
            label = { Text("Pick files") },
            leadingIcon = { Icon(Icons.Filled.FileOpen, null, modifier = Modifier.size(18.dp)) }
        )
    }
}

@Composable
private fun SearchSortRow(
    query: String,
    onQuery: (String) -> Unit,
    sort: LocalSort,
    fields: List<LocalSortField>,
    onSort: (LocalSort) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Search by name") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
            } else null,
            shape = RoundedCornerShape(14.dp)
        )
        Box {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                fields.forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field.label) },
                        trailingIcon = { if (field == sort.field) Icon(Icons.Filled.Check, null) },
                        onClick = { onSort(sort.copy(field = field)); menu = false }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Ascending") },
                    leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                    trailingIcon = { if (sort.ascending) Icon(Icons.Filled.Check, null) },
                    onClick = { onSort(sort.copy(ascending = true)); menu = false }
                )
                DropdownMenuItem(
                    text = { Text("Descending") },
                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                    trailingIcon = { if (!sort.ascending) Icon(Icons.Filled.Check, null) },
                    onClick = { onSort(sort.copy(ascending = false)); menu = false }
                )
            }
        }
    }
}

@Composable
private fun PartialAccessBanner(onSelectMore: () -> Unit, onAllowAll: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 2.dp)) {
            Text(
                "You've allowed access to some photos and videos only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onSelectMore) { Text("Select more") }
                TextButton(onClick = onAllowAll) { Text("Allow all") }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    isConnected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCast: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
            Text(
                "$count selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, contentDescription = "Select all") }
            IconButton(onClick = onCast, enabled = isConnected && count > 0) { Icon(Icons.Filled.Cast, contentDescription = "Cast selected") }
            IconButton(onClick = onAddToPlaylist, enabled = count > 0) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add selected to playlist")
            }
            IconButton(onClick = onShare, enabled = count > 0) { Icon(Icons.Filled.Share, contentDescription = "Share") }
        }
    }
}

/** Back row of an album / folder with "cast all" and its overflow menu. */
@Composable
private fun FolderHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    castIcon: ImageVector,
    castLabel: String,
    castEnabled: Boolean,
    onCastAll: () -> Unit,
    onAddAll: () -> Unit,
    onPlayAllOnPhone: (() -> Unit)?,
    onSelectAll: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(15.dp))
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCastAll, enabled = castEnabled) {
                Icon(castIcon, contentDescription = castLabel)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(castLabel) },
                        leadingIcon = { Icon(castIcon, null) },
                        enabled = castEnabled,
                        onClick = { menu = false; onCastAll() }
                    )
                    DropdownMenuItem(
                        text = { Text("Add all to playlist") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                        onClick = { menu = false; onAddAll() }
                    )
                    if (onPlayAllOnPhone != null) {
                        DropdownMenuItem(
                            text = { Text("Play all on phone") },
                            leadingIcon = { Icon(Icons.Filled.Smartphone, null) },
                            onClick = { menu = false; onPlayAllOnPhone() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Select") },
                        leadingIcon = { Icon(Icons.Filled.CheckCircle, null) },
                        onClick = { menu = false; onSelectAll() }
                    )
                }
            }
        }
    }
}

/** Long-press / ⋮ menu of a single file. */
@Composable
private fun LocalItemMenu(item: MediaItemUi, actions: LocalItemActions) {
    val context = LocalContext.current
    val close = { actions.onMenu(null) }
    DropdownMenu(expanded = actions.menuFor == item.uri, onDismissRequest = close) {
        DropdownMenuItem(
            text = { Text("Cast to TV") },
            leadingIcon = { Icon(Icons.Filled.Cast, null) },
            enabled = actions.isConnected,
            onClick = { close(); actions.onCast(item) }
        )
        DropdownMenuItem(
            text = { Text("Add to playlist") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
            onClick = { close(); actions.onAddToPlaylist(item) }
        )
        if (item.kind != MediaKind.Photo) {
            DropdownMenuItem(
                text = { Text("Play on phone") },
                leadingIcon = { Icon(Icons.Filled.Smartphone, null) },
                onClick = { close(); playLocalOnPhone(context, listOf(item)) }
            )
        }
        DropdownMenuItem(
            text = { Text("Open in another app") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
            onClick = { close(); openLocalWith(context, item) }
        )
        DropdownMenuItem(
            text = { Text("Share") },
            leadingIcon = { Icon(Icons.Filled.Share, null) },
            onClick = { close(); shareLocal(context, listOf(item)) }
        )
        DropdownMenuItem(
            text = { Text("Select") },
            leadingIcon = { Icon(Icons.Filled.CheckCircle, null) },
            onClick = { close(); actions.onLongClick(item) }
        )
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
        contentDescription = if (selected) "Selected" else "Not selected",
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(22.dp)
    )
}

@Composable
private fun PickedFilesDialog(
    items: List<MediaItemUi>,
    isConnected: Boolean,
    onCastAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.size == 1) "1 file picked" else "${items.size} files picked") },
        text = {
            val names = items.take(5).joinToString("\n") { it.title }
            Text(
                if (items.size > 5) "$names\n+${items.size - 5} more" else names,
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = onCastAll, enabled = isConnected) { Text(if (items.size == 1) "Cast" else "Cast all") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onAddToPlaylist) { Text("Add to playlist") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ─── Photo Album card ─────────────────────────────────────────────────────────

@Composable
private fun PhotoAlbumCard(
    modifier: Modifier,
    album: PhotoAlbum,
    castEnabled: Boolean,
    onClick: () -> Unit,
    onCastAll: () -> Unit,
    onAddAll: () -> Unit
) {
    BucketCard(
        modifier = modifier,
        coverUri = album.coverUri,
        name = album.name,
        count = album.count,
        icon = Icons.Filled.Folder,
        castIcon = Icons.Filled.Slideshow,
        castLabel = "Start slideshow",
        castEnabled = castEnabled,
        onClick = onClick,
        onCastAll = onCastAll,
        onAddAll = onAddAll
    )
}

// ─── Video Folder card ────────────────────────────────────────────────────────

@Composable
private fun VideoFolderCard(
    modifier: Modifier,
    folder: VideoFolder,
    castEnabled: Boolean,
    onClick: () -> Unit,
    onCastAll: () -> Unit,
    onAddAll: () -> Unit
) {
    BucketCard(
        modifier = modifier,
        coverUri = folder.coverUri,
        name = folder.name,
        count = folder.count,
        icon = Icons.Filled.PlayArrow,
        castIcon = Icons.Filled.PlayArrow,
        castLabel = "Cast folder",
        castEnabled = castEnabled,
        onClick = onClick,
        onCastAll = onCastAll,
        onAddAll = onAddAll
    )
}

// ─── Audio Album card ─────────────────────────────────────────────────────────

@Composable
private fun AudioAlbumCard(
    modifier: Modifier,
    album: AudioAlbum,
    castEnabled: Boolean,
    onClick: () -> Unit,
    onCastAll: () -> Unit,
    onAddAll: () -> Unit
) {
    BucketCard(
        modifier = modifier,
        coverUri = album.artUri,
        name = album.name,
        count = album.count,
        icon = Icons.Filled.Album,
        castIcon = Icons.Filled.PlayArrow,
        castLabel = "Cast album",
        castEnabled = castEnabled,
        onClick = onClick,
        onCastAll = onCastAll,
        onAddAll = onAddAll
    )
}

// ─── Generic bucket card ──────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BucketCard(
    modifier: Modifier,
    coverUri: String?,
    name: String,
    count: Int,
    icon: ImageVector,
    castIcon: ImageVector,
    castLabel: String,
    castEnabled: Boolean,
    onClick: () -> Unit,
    onCastAll: () -> Unit,
    onAddAll: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier = modifier

            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (coverUri != null) {
                MediaArtwork(
                    model = coverUri,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    isVideo = icon == Icons.Filled.PlayArrow,
                    modifier = Modifier.matchParentSize()
                )
                // Bottom scrim
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))
                            )
                        )
                )
            } else {
                // Fallback icon
                Box(
                    modifier = Modifier.matchParentSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            // Cast the whole album / folder as a queue (photos: slideshow).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = if (castEnabled) 0.45f else 0.25f))
                    .clickable(onClick = onCastAll),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = castIcon,
                    contentDescription = castLabel,
                    tint = Color.White.copy(alpha = if (castEnabled) 1f else 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(castLabel) },
                    leadingIcon = { Icon(castIcon, null) },
                    enabled = castEnabled,
                    onClick = { menu = false; onCastAll() }
                )
                DropdownMenuItem(
                    text = { Text("Add all to playlist") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    onClick = { menu = false; onAddAll() }
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.
            width(4.dp))

            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Photo thumb (3-column) ───────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoThumb(
    modifier: Modifier = Modifier,
    item: MediaItemUi,
    actions: LocalItemActions
) {
    val selected = actions.isSelected(item)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = { actions.onClick(item) }, onLongClick = { actions.onLongClick(item) })
    ) {
        AsyncImage(
            model = item.thumbnailUri ?: item.uri,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            )
        }
        if (actions.selecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.3f))
            ) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { actions.onMenu(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White, modifier = Modifier.size(16.dp))
                }
                LocalItemMenu(item, actions)
            }
        }
    }
}

// ─── Video / Audio media card (2-column) ──────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(
    modifier: Modifier = Modifier,
    mediaItem: MediaItemUi,
    actions: LocalItemActions
) {
    val selected = actions.isSelected(mediaItem)
    ElevatedCard(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
            .combinedClickable(onClick = { actions.onClick(mediaItem) }, onLongClick = { actions.onLongClick(mediaItem) }),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiaryContainer
                                )
                            )
                        )
                )
                if (mediaItem.thumbnailUri != null) {
                    MediaArtwork(
                        model = mediaItem.thumbnailUri,
                        contentDescription = mediaItem.title,
                        contentScale = ContentScale.Crop,
                        isVideo = mediaItem.kind == MediaKind.Video,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.25f))
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (mediaItem.kind == MediaKind.Video) Icons.Filled.PlayArrow else Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (actions.selecting) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(if (selected) Color.White else Color.Black.copy(alpha = 0.3f))
                    ) {
                        Icon(
                            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = if (selected) "Selected" else "Not selected",
                            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(mediaItem.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (mediaItem.subtitle.isNotBlank()) {
                        Text(mediaItem.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !actions.selecting) { actions.onMenu(mediaItem) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    LocalItemMenu(mediaItem, actions)
                }
            }
        }
    }
}

@Composable
private fun LinkCastCard(
    enabled: Boolean,
    onPlay: (MediaItemUi) -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
    // "example.com/video.m3u8" works too: a missing scheme means https.
    val trimmed = url.trim().let { if (it.isNotEmpty() && !Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(it)) "https://$it" else it }
    val isValid = Regex("""^(https?|rtsps?)://\S+$""", RegexOption.IGNORE_CASE).matches(trimmed) && trimmed.substringAfter("://").substringBefore('/').contains('.')
    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Play a link on TV", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Paste a video or stream address (mp4, m3u8, mpd…). It opens straight in the TV receiver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://") },
                shape = RoundedCornerShape(14.dp)
            )
            Button(
                enabled = enabled && isValid,
                onClick = { onPlay(linkMediaItem(trimmed)) }
            ) { Text("Play on TV") }
        }
    }
}

private fun linkMediaItem(url: String): MediaItemUi {
    val path = android.net.Uri.parse(url).lastPathSegment.orEmpty()
    val lower = path.lowercase()
    val mime = when {
        lower.endsWith(".m3u8") || lower.endsWith(".m3u") -> "application/x-mpegURL"
        lower.endsWith(".mpd") -> "application/dash+xml"
        lower.endsWith(".mp3") || lower.endsWith(".aac") || lower.endsWith(".m4a") -> "audio/*"
        else -> "video/*"
    }
    return MediaItemUi(
        id = url.hashCode().toLong(),
        title = path.ifBlank { android.net.Uri.parse(url).host ?: url },
        subtitle = url,
        uri = url,
        mimeType = mime,
        kind = if (mime.startsWith("audio")) MediaKind.Audio else MediaKind.Video
    )
}
