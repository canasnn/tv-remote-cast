package com.canasnn.tvremote.cast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import com.ruterika.castcore.browser.UserAgents
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.Public
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.browser.BrowserManager
import com.canasnn.tvremote.cast.browser.BrowserTab
import com.ruterika.castcore.library.SavedMedia
import com.ruterika.castcore.library.SavedSubtitle
import com.ruterika.castcore.settings.TabRestore
import com.ruterika.castcore.settings.VideoAdMode
import com.ruterika.castcore.settings.VideoTapAction
import com.ruterika.castcore.web.DetectedMedia
import com.ruterika.castcore.web.HlsVariant
import com.ruterika.castcore.web.MediaClass

/**
 * In-app browser: any video the page plays is
 * detected and can be sent to the TV with the headers it needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String?,
    onBack: () -> Unit,
    onCast: (List<SavedMedia>, Int) -> Unit,
    onOpenLibrary: (LibrarySection) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    // "Dark pages: Automatic" follows the app theme.
    BrowserManager.setAppDarkTheme(com.canasnn.tvremote.preference.LocalDarkTheme.current.isDarkTheme())

    // Restore tabs from last time if wanted (only when the browser is empty).
    var askRestore by remember { mutableStateOf(false) }
    var restoreList by remember { mutableStateOf<List<String>>(emptyList()) }
    val tab = remember {
        BrowserManager.init(context)
        com.canasnn.tvremote.cast.browser.BrowserData.refreshIfStale()
        val saved = BrowserManager.savedTabUrls()
        if (BrowserManager.tabs.value.isEmpty() && initialUrl == null && saved.isNotEmpty()) {
            when (settings.restoreTabs) {
                TabRestore.ALWAYS -> {
                    saved.drop(1).forEach { BrowserManager.newTab(it, select = false, lazy = settings.lazyLoadRestoredTabs) }
                    BrowserManager.newTab(saved.first())
                }
                // Keep the list: opening the empty tab below overwrites the saved tabs.
                TabRestore.ASK -> { restoreList = saved; askRestore = true; BrowserManager.open(null) }
                TabRestore.NEVER -> BrowserManager.open(null)
            }
        } else BrowserManager.open(initialUrl)
    }
    val current by BrowserManager.current.collectAsStateWithLifecycle()
    val activeTab = current ?: tab

    if (askRestore && restoreList.isNotEmpty()) {
        val saved = restoreList
        ConfirmDialog(
            "Reopen previous tabs?",
            if (saved.size == 1) "1 site was open last time." else "${saved.size} sites were open last time.",
            onDismiss = { askRestore = false }
        ) {
            activeTab.load(saved.first())
            saved.drop(1).forEach { BrowserManager.newTab(it, select = false, lazy = settings.lazyLoadRestoredTabs) }
            askRestore = false
        }
    }

    BrowserContent(
        tab = activeTab,
        onBack = onBack,
        onCast = onCast,
        onOpenLibrary = onOpenLibrary,
        onOpenSettings = onOpenSettings
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserContent(
    tab: BrowserTab,
    onBack: () -> Unit,
    onCast: (List<SavedMedia>, Int) -> Unit,
    onOpenLibrary: (LibrarySection) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val url by tab.url.collectAsStateWithLifecycle()
    val title by tab.title.collectAsStateWithLifecycle()
    val progress by tab.progress.collectAsStateWithLifecycle()
    val canGoBack by tab.canGoBack.collectAsStateWithLifecycle()
    val canGoForward by tab.canGoForward.collectAsStateWithLifecycle()
    val media by tab.collector.items.collectAsState()
    val adMedia by tab.collector.adItems.collectAsState()
    val blockedPopups by tab.blockedPopups.collectAsStateWithLifecycle()
    val tabs by BrowserManager.tabs.collectAsStateWithLifecycle()
    val library by CastHub.library.data.collectAsStateWithLifecycle()

    var address by remember(url) { mutableStateOf(url) }
    var editing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<DetectedMedia?>(null) }
    var qualityFor by remember { mutableStateOf<DetectedMedia?>(null) }
    var subtitlesFor by remember { mutableStateOf<DetectedMedia?>(null) }
    var autoShownFor by remember { mutableStateOf<String?>(null) }
    val startDownload = rememberDownloadStarter()
    val addToPlaylist = rememberPlaylistPicker()

    val fullscreen by tab.fullscreen.collectAsStateWithLifecycle()
    val blockedPopupUrl by tab.blockedPopupUrl.collectAsStateWithLifecycle()
    val contextTarget by tab.contextTarget.collectAsStateWithLifecycle()
    var finding by remember(tab) { mutableStateOf(false) }
    var pickUserAgent by remember { mutableStateOf(false) }
    var pickAdMode by remember { mutableStateOf(false) }
    /** Cast waiting on the "Video ad detected" question (item, quality, play as live). */
    var adQuestion by remember { mutableStateOf<Triple<DetectedMedia, HlsVariant?, Boolean>?>(null) }
    val adsBlocked by tab.adsBlocked.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }

    BackHandler {
        when {
            fullscreen != null -> tab.exitFullscreen()
            finding -> finding = false
            editing -> { editing = false; focus.clearFocus() }
            canGoBack -> tab.webView.goBack()
            else -> onBack()
        }
    }

    // Certificate errors, JS dialogs, sign-in, permissions, file upload.
    BrowserPromptDialogs(tab)
    TabNotices(tab, snackbar)

    contextTarget?.let { target ->
        LinkContextSheet(tab, target, pageUrl = url, pageTitle = title, onCast = onCast, onDismiss = tab::dismissContextMenu)
    }

    if (pickAdMode) {
        ChoiceDialog(
            title = "Video ad mode",
            options = VideoAdMode.entries,
            selected = settings.videoAdMode,
            label = { it.label + " · " + it.description },
            onDismiss = { pickAdMode = false },
            onSelect = { mode -> pickAdMode = false; CastHub.settingsStore.update { it.copy(videoAdMode = mode) } }
        )
    }

    if (pickUserAgent) {
        UserAgentDialog(
            selectedKey = tab.effectiveUserAgentKey,
            showMakeDefault = true,
            onDismiss = { pickUserAgent = false },
            onSelect = { key, makeDefault ->
                pickUserAgent = false
                if (makeDefault) saveDefaultUserAgent(key)
                tab.setUserAgent(if (makeDefault) null else key)
            }
        )
    }

    // Open the list on the first video of a page.
    LaunchedEffect(media.isNotEmpty(), url) {
        if (media.isNotEmpty() && settings.autoShowVideoList && autoShownFor != url) {
            autoShownFor = url
            showList = true
        }
    }

    fun cast(item: DetectedMedia, variant: HlsVariant? = null, asLive: Boolean = false, adConfirmed: Boolean = false) {
        // Ask before sending something that looks like an ad.
        if (item.isLikelyAd && settings.askBeforeCastingAds && !adConfirmed) {
            adQuestion = Triple(item, variant, asLive)
            return
        }
        tab.onCastStarted(item.isLikelyAd)
        val saved = item.toSaved(title, settings.autoLoadDetectedSubtitles)
        val chosen = when {
            variant == null -> saved
            // Audio in its own rendition, or a DASH quality: keep the manifest and hold the player to this quality.
            variant.audioGroup != null || item.mediaClass == MediaClass.DASH || variant.url == item.url -> saved.copy(
                videoHeight = variant.height,
                videoBitrate = variant.bandwidth.takeIf { it > 0 }
            )
            else -> saved.copy(url = variant.url)
        }.let { m ->
            // Cookies are read again for the URL that is sent (they may have changed since detection).
            val fresh = m.copy(headers = tab.withFreshCookie(m.url, m.headers))
            if (asLive) fresh.copy(isLive = true, durationMs = 0L) else fresh
        }
        CastLauncher.noteWebPosition(chosen.url, if (asLive) null else item.webPositionMs)
        showList = false
        onCast(listOf(chosen), 0)
    }

    adQuestion?.let { (item, variant, asLive) ->
        VideoAdDialog(
            item = item,
            onPlay = { dontAsk ->
                adQuestion = null
                if (dontAsk) CastHub.settingsStore.update { it.copy(askBeforeCastingAds = false) }
                cast(item, variant, asLive, adConfirmed = true)
            },
            onDismiss = { adQuestion = null }
        )
    }

    /** The detected item as saved media, with fresh cookies (play on phone, playlist, download). */
    fun savedOf(item: DetectedMedia): SavedMedia =
        item.toSaved(title, settings.autoLoadDetectedSubtitles).let { it.copy(headers = tab.withFreshCookie(it.url, it.headers)) }

    // The page started a video -> cast it (one source) or show the list.
    var lastAutoCast by remember { mutableStateOf(0L) }
    LaunchedEffect(tab) {
        tab.pagePlay.collect { playedUrl ->
            if (!settings.castOnPagePlay) return@collect
            val now = System.currentTimeMillis()
            if (now - lastAutoCast < AUTO_CAST_DEBOUNCE_MS) return@collect
            kotlinx.coroutines.delay(800) // let the request that started playback be detected
            val list = tab.collector.items.value
            val match = list.firstOrNull { playedUrl.isNotEmpty() && it.url == playedUrl }
            when {
                match != null -> { lastAutoCast = now; cast(match) }
                list.size == 1 -> { lastAutoCast = now; cast(list.first()) }
                list.size > 1 -> showList = true
            }
        }
    }

    fun onVideoTap(item: DetectedMedia) {
        when (settings.videoTapAction) {
            VideoTapAction.CAST -> cast(item)
            VideoTapAction.PLAY_ON_PHONE -> com.canasnn.tvremote.cast.player.PhonePlayer.play(context, listOf(savedOf(item)))
            VideoTapAction.ASK -> actionsFor = item
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                if (finding) FindInPageBar(tab, onClose = { finding = false }) else Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close browser") }
                    TextField(
                        value = if (editing) address else (if (url.isBlank()) "" else displayUrl(url)),
                        onValueChange = { address = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                if (tab.incognito) Icons.Filled.VisibilityOff else if (url.startsWith("https")) Icons.Filled.Lock else Icons.Filled.Search,
                                null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        placeholder = { Text("Search or type URL") },
                        trailingIcon = if (!editing && adsBlocked > 0 && settings.adBlock) {
                            {
                                IconButton(onClick = {
                                    Toast.makeText(context, if (adsBlocked == 1) "1 ad blocked on this page" else "$adsBlocked ads blocked on this page", Toast.LENGTH_SHORT).show()
                                }) {
                                    BadgedBox(badge = { Badge { Text(if (adsBlocked > 99) "99+" else "$adsBlocked") } }) {
                                        Icon(Icons.Filled.Shield, "$adsBlocked ads blocked", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        } else null,
                        shape = RoundedCornerShape(26.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val target = settings.resolveInput(address)
                            if (target.isNotBlank()) tab.load(target)
                            editing = false
                            focus.clearFocus()
                        }),
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            .also { source ->
                                LaunchedEffect(source) {
                                    source.interactions.collect {
                                        if (it is androidx.compose.foundation.interaction.FocusInteraction.Focus) {
                                            editing = true
                                            address = url
                                        }
                                        if (it is androidx.compose.foundation.interaction.FocusInteraction.Unfocus) editing = false
                                    }
                                }
                            }
                    )
                    IconButton(onClick = { if (progress in 1..99) tab.webView.stopLoading() else tab.webView.reload() }) {
                        Icon(if (progress in 1..99) Icons.Filled.Stop else Icons.Filled.Refresh, "Reload")
                    }
                    IconButton(onClick = { tab.captureThumbnail(); showTabs = true }) {
                        BadgedBox(badge = { Badge { Text("${tabs.size}") } }) { Icon(Icons.Filled.Tab, "Tabs") }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        BrowserMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            tab = tab,
                            url = url,
                            title = title,
                            canGoForward = canGoForward,
                            bookmarked = library.bookmarks.any { it.url == url },
                            onOpenLibrary = onOpenLibrary,
                            onOpenSettings = onOpenSettings,
                            onShowList = { showList = true },
                            onFindInPage = { finding = true },
                            onPickUserAgent = { pickUserAgent = true },
                            onPickAdMode = { pickAdMode = true }
                        )
                    }
                }
                if (editing && !finding && address.isNotBlank() && address != url) {
                    AddressSuggestionsList(
                        query = address,
                        incognito = tab.incognito,
                        onOpen = { target ->
                            tab.load(target)
                            editing = false
                            focus.clearFocus()
                        },
                        onFill = { address = it }
                    )
                }
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                AnimatedVisibility(blockedPopups > 0 && settings.blockPopups) {
                    PopupBlockedBar(
                        count = blockedPopups,
                        host = Uri.parse(url).host?.removePrefix("www."),
                        canOpen = blockedPopupUrl != null,
                        onOpen = tab::openBlockedPopup,
                        onAllow = { host ->
                            CastHub.settingsStore.update { it.copy(allowedPopupHosts = it.allowedPopupHosts + host) }
                            tab.openBlockedPopup()
                        },
                        onDismiss = tab::dismissBlockedPopups
                    )
                }
            }
        },
        bottomBar = { MiniController(Modifier.navigationBarsPadding()) },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        floatingActionButton = {
            if (media.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showList = true },
                    icon = { Icon(Icons.Filled.Cast, null) },
                    text = { Text(if (media.size == 1) "1 video" else "${media.size} videos") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            WebViewHost(tab)
            fullscreen?.let { (view, _) ->
                FullscreenWindowEffect()
                AndroidView(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            (view.parent as? android.view.ViewGroup)?.removeView(view)
                            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        }
                    }
                )
            }
            if (url.isBlank()) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LazyColumn {
                        webHomeSection(
                            onOpenBrowser = { target -> if (target != null) tab.load(target) },
                            onOpenLibrary = onOpenLibrary,
                            onCast = onCast
                        )
                    }
                }
            }
        }
    }

    if (showList) {
        ModalBottomSheet(onDismissRequest = { showList = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
            VideoListSheet(
                items = media,
                ads = adMedia,
                pageTitle = title,
                progress = { item -> playedLine(item, library.recentMedia) },
                onTap = ::onVideoTap,
                onMore = { actionsFor = it },
                onRescan = { tab.rescan() }
            )
        }
    }

    actionsFor?.let { item ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            VideoActionsSheet(
                item = item,
                onCast = { actionsFor = null; cast(item) },
                onPlayAsLive = { actionsFor = null; cast(item, asLive = true) },
                onChooseQuality = { actionsFor = null; qualityFor = item },
                onSubtitles = { actionsFor = null; subtitlesFor = item },
                onPlayOnPhone = {
                    actionsFor = null
                    if (settings.pausePageVideoOnCast) tab.pausePageMedia()
                    com.canasnn.tvremote.cast.player.PhonePlayer.play(context, listOf(savedOf(item)))
                },
                onOpenWith = { actionsFor = null; playOnPhone(context, item) },
                onAddToPlaylist = {
                    actionsFor = null
                    addToPlaylist(listOf(item.toSaved(title, settings.autoLoadDetectedSubtitles)))
                },
                onDownload = {
                    actionsFor = null
                    startDownload(savedOf(item))
                },
                onCopy = {
                    actionsFor = null
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("video", item.url))
                    Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                },
                onShare = {
                    actionsFor = null
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.url), null))
                }
            )
        }
    }

    // "Subtitles…": choose subtitles for this video, then cast it with them.
    subtitlesFor?.let { item ->
        val saved = remember(item) { savedOf(item) }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { subtitlesFor = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            SubtitleScreen(
                onBack = { subtitlesFor = null },
                onOpenSettings = null,
                target = saved,
                onPicked = { sub ->
                    subtitlesFor = null
                    tab.onCastStarted(item.isLikelyAd)
                    CastLauncher.markSubtitlesChosen(saved.url)
                    CastLauncher.noteWebPosition(saved.url, item.webPositionMs)
                    onCast(listOf(if (sub == null) saved else saved.copy(subtitles = listOf(sub) + saved.subtitles.filterNot { it.url == sub.url })), 0)
                }
            )
        }
    }

    qualityFor?.let { item ->
        ModalBottomSheet(onDismissRequest = { qualityFor = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text("Choose quality", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                if (item.mediaClass == MediaClass.DASH) {
                    Text(
                        "DASH: the TV plays this manifest and keeps to the chosen quality.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                ListItem(
                    headlineContent = { Text("Automatic") },
                    supportingContent = { Text("The TV adapts to your network") },
                    leadingContent = { Icon(Icons.Filled.HighQuality, null) },
                    modifier = Modifier.clickable { qualityFor = null; cast(item) }
                )
                item.variants.sortedByDescending { it.height ?: 0 }.forEach { variant ->
                    ListItem(
                        headlineContent = { Text(variant.label) },
                        supportingContent = { if (variant.bandwidth > 0) Text("${variant.bandwidth / 1000} kbps") },
                        leadingContent = { Icon(Icons.Filled.Movie, null) },
                        modifier = Modifier.clickable { qualityFor = null; cast(item, variant) }
                    )
                }
            }
        }
    }

    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            TabsSheet(
                tabs = tabs,
                current = tab,
                onSelect = { BrowserManager.select(it); showTabs = false },
                onClose = { BrowserManager.close(it); if (BrowserManager.tabs.value.isEmpty()) { showTabs = false; onBack() } },
                onNew = { incognito -> BrowserManager.newTab(null, incognito); showTabs = false },
                onCloseAll = { BrowserManager.closeAll(); showTabs = false; onBack() },
                onReopenClosed = { BrowserManager.reopenClosedTab(); showTabs = false }
            )
        }
    }
}

/** While a page video is fullscreen: hide the system bars and keep the screen on. */
@Composable
private fun FullscreenWindowEffect() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

/** Hosts the tab's long-lived WebView inside Compose. */
@Composable
private fun WebViewHost(tab: BrowserTab) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                tab.attach(ctx)
                addView(tab.webView)
            }
        },
        update = { container ->
            if (tab.webView.parent !== container) {
                container.removeAllViews()
                tab.attach(container.context)
                container.addView(tab.webView)
            }
        }
    )
    DisposableEffect(tab) {
        tab.onShown()
        onDispose {
            tab.captureThumbnail()
            tab.onHidden()
            tab.detach(context.applicationContext)
            BrowserManager.persist()
        }
    }
}

@Composable
private fun BrowserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    tab: BrowserTab,
    url: String,
    title: String,
    canGoForward: Boolean,
    bookmarked: Boolean,
    onOpenLibrary: (LibrarySection) -> Unit,
    onOpenSettings: () -> Unit,
    onShowList: () -> Unit,
    onFindInPage: () -> Unit,
    onPickUserAgent: () -> Unit,
    onPickAdMode: () -> Unit
) {
    val context = LocalContext.current
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val host = Uri.parse(url).host?.removePrefix("www.")
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (canGoForward) DropdownMenuItem(text = { Text("Forward") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }, onClick = { onDismiss(); tab.webView.goForward() })
        DropdownMenuItem(text = { Text("Find in page") }, leadingIcon = { Icon(Icons.Filled.FindInPage, null) }, enabled = url.startsWith("http"), onClick = { onDismiss(); onFindInPage() })
        DropdownMenuItem(text = { Text("Scan page for videos") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, onClick = { onDismiss(); tab.rescan(); onShowList() })
        DropdownMenuItem(
            text = {
                Column {
                    Text("Video ad mode…")
                    Text(settings.videoAdMode.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            leadingIcon = { Icon(Icons.Filled.SkipNext, null) },
            onClick = { onDismiss(); onPickAdMode() }
        )
        DropdownMenuItem(
            text = { Text(if (bookmarked) "Remove bookmark" else "Bookmark this page") },
            leadingIcon = { Icon(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null) },
            enabled = url.startsWith("http"),
            onClick = { onDismiss(); CastHub.library.toggleBookmark(url, title) }
        )
        DropdownMenuItem(text = { Text("Bookmarks") }, leadingIcon = { Icon(Icons.Filled.Bookmark, null) }, onClick = { onDismiss(); onOpenLibrary(LibrarySection.Bookmarks) })
        DropdownMenuItem(text = { Text("History") }, leadingIcon = { Icon(Icons.Filled.History, null) }, onClick = { onDismiss(); onOpenLibrary(LibrarySection.History) })
        val desktop = UserAgents.isDesktop(tab.effectiveUserAgentKey, settings)
        DropdownMenuItem(
            text = { Text("Desktop site") },
            leadingIcon = { Icon(Icons.Filled.Computer, null) },
            trailingIcon = { androidx.compose.material3.Checkbox(checked = desktop, onCheckedChange = null) },
            onClick = {
                onDismiss()
                // This tab only; "User agent…" can make it the default.
                tab.setUserAgent(if (desktop) UserAgents.NONE else UserAgents.DESKTOP_KEY)
            }
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text("User agent…")
                    Text(UserAgents.label(tab.effectiveUserAgentKey, settings), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            leadingIcon = { Icon(Icons.Filled.Person, null) },
            onClick = { onDismiss(); onPickUserAgent() }
        )
        if (host != null) {
            val adBlockOff = settings.adBlockAllowHosts.contains(host)
            DropdownMenuItem(
                text = { Text(if (adBlockOff) "Turn ad blocking on for $host" else "Turn ad blocking off for $host") },
                leadingIcon = { Icon(Icons.Filled.FilterAlt, null) },
                onClick = {
                    onDismiss()
                    CastHub.settingsStore.update {
                        it.copy(adBlockAllowHosts = if (adBlockOff) it.adBlockAllowHosts - host else it.adBlockAllowHosts + host)
                    }
                    tab.webView.reload()
                }
            )
            val popupsAllowed = settings.allowedPopupHosts.contains(host)
            DropdownMenuItem(
                text = { Text(if (popupsAllowed) "Block pop-ups on $host" else "Allow pop-ups on $host") },
                leadingIcon = { Icon(Icons.Filled.OpenInNew, null) },
                onClick = {
                    onDismiss()
                    CastHub.settingsStore.update {
                        it.copy(allowedPopupHosts = if (popupsAllowed) it.allowedPopupHosts - host else it.allowedPopupHosts + host)
                    }
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Set as homepage") },
            leadingIcon = { Icon(Icons.Filled.Home, null) },
            enabled = url.startsWith("http"),
            onClick = { onDismiss(); CastHub.settingsStore.update { it.copy(homepage = url) } }
        )
        if (settings.homepage.isNotBlank()) {
            DropdownMenuItem(
                text = { Text("Remove homepage") },
                leadingIcon = { Icon(Icons.Filled.Home, null) },
                onClick = { onDismiss(); CastHub.settingsStore.update { it.copy(homepage = "") } }
            )
        }
        DropdownMenuItem(
            text = { Text("Add to home screen") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.AddToHomeScreen, null) },
            enabled = url.startsWith("http"),
            onClick = { onDismiss(); addToHomeScreen(context, url, title, tab.favicon.value) }
        )
        DropdownMenuItem(
            text = { Text("Share") },
            leadingIcon = { Icon(Icons.Filled.Share, null) },
            enabled = url.startsWith("http"),
            onClick = {
                onDismiss()
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
            }
        )
        DropdownMenuItem(
            text = { Text("Open in another browser") },
            leadingIcon = { Icon(Icons.Filled.Web, null) },
            enabled = url.startsWith("http"),
            onClick = { onDismiss(); runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        )
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) }, onClick = { onDismiss(); onOpenSettings() })
    }
}

@Composable
private fun VideoListSheet(
    items: List<DetectedMedia>,
    ads: List<DetectedMedia>,
    pageTitle: String,
    progress: (DetectedMedia) -> String?,
    onTap: (DetectedMedia) -> Unit,
    onMore: (DetectedMedia) -> Unit,
    onRescan: () -> Unit
) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Videos on this page", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRescan) { Text("Scan again") }
        }
        if (items.isEmpty()) {
            EmptyText("Play the video on the page; it will show up here.")
        }
        var showAds by remember { mutableStateOf(false) }
        LazyColumn(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item -> VideoRow(item, pageTitle, progress(item), onTap, onMore) }
            if (ads.isNotEmpty()) {
                item(key = "ads-header") {
                    TextButton(onClick = { showAds = !showAds }) {
                        Text("Possible ads (${ads.size})" + if (showAds) " ▲" else " ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (showAds) items(ads, key = { "ad-" + it.id }) { item ->
                    Box(Modifier.alpha(0.6f)) { VideoRow(item, pageTitle, progress(item), onTap, onMore) }
                }
            }
        }
    }
}

@Composable
private fun VideoRow(item: DetectedMedia, pageTitle: String, progress: String?, onTap: (DetectedMedia) -> Unit, onMore: (DetectedMedia) -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable { onTap(item) }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxSize()) {}
                if (item.posterUrl != null) AsyncImage(item.posterUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title ?: pageTitle.ifBlank { fileName(item.url) }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    fileName(item.url) + " · " + Uri.parse(item.url).host.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    describe(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.previouslyFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                if (progress != null) {
                    Text(progress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            IconButton(onClick = { onMore(item) }) { Icon(Icons.Filled.MoreVert, "More") }
        }
    }
}

@Composable
private fun VideoActionsSheet(
    item: DetectedMedia,
    onCast: () -> Unit,
    onPlayAsLive: () -> Unit,
    onChooseQuality: () -> Unit,
    onSubtitles: () -> Unit,
    onPlayOnPhone: () -> Unit,
    onOpenWith: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(item.title ?: fileName(item.url), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Text(describe(item), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                item.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }
        ListItem(headlineContent = { Text("Cast to TV") }, leadingContent = { Icon(Icons.Filled.Cast, null) }, modifier = Modifier.clickable(onClick = onCast))
        if (item.variants.size > 1) {
            ListItem(
                headlineContent = { Text("Choose quality") },
                supportingContent = { Text("${item.variants.size} qualities" + if (item.mediaClass == MediaClass.DASH) " (DASH)" else "") },
                leadingContent = { Icon(Icons.Filled.HighQuality, null) },
                modifier = Modifier.clickable(onClick = onChooseQuality)
            )
        }
        if (!item.isLive) {
            ListItem(
                headlineContent = { Text("Play as live") },
                supportingContent = { Text("For streams that stop or loop: no seeking or duration") },
                leadingContent = { Icon(Icons.Filled.Sensors, null) },
                modifier = Modifier.clickable(onClick = onPlayAsLive)
            )
        }
        ListItem(
            headlineContent = { Text("Subtitles…") },
            supportingContent = {
                Text(if (item.subtitles.isNotEmpty()) "Found on the page: " + item.subtitles.joinToString { it.label ?: it.language ?: "track" } else "Online, local file or URL")
            },
            leadingContent = { Icon(Icons.Filled.Subtitles, null) },
            modifier = Modifier.clickable(onClick = onSubtitles)
        )
        ListItem(headlineContent = { Text("Add to playlist") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }, modifier = Modifier.clickable(onClick = onAddToPlaylist))
        if (!item.isLive) {
            ListItem(
                headlineContent = { Text("Download") },
                supportingContent = { if (item.mediaClass == MediaClass.HLS || item.mediaClass == MediaClass.DASH) Text("Saved as MP4") },
                leadingContent = { Icon(Icons.Filled.Download, null) },
                modifier = Modifier.clickable(onClick = onDownload)
            )
        }
        ListItem(headlineContent = { Text("Play on phone") }, leadingContent = { Icon(Icons.Filled.Smartphone, null) }, modifier = Modifier.clickable(onClick = onPlayOnPhone))
        ListItem(headlineContent = { Text("Open in another app") }, leadingContent = { Icon(Icons.Filled.OpenInNew, null) }, modifier = Modifier.clickable(onClick = onOpenWith))
        ListItem(headlineContent = { Text("Copy link") }, leadingContent = { Icon(Icons.Filled.ContentCopy, null) }, modifier = Modifier.clickable(onClick = onCopy))
        ListItem(headlineContent = { Text("Share") }, leadingContent = { Icon(Icons.Filled.Share, null) }, modifier = Modifier.clickable(onClick = onShare))
    }
}

@Composable
private fun TabsSheet(
    tabs: List<BrowserTab>,
    current: BrowserTab,
    onSelect: (BrowserTab) -> Unit,
    onClose: (BrowserTab) -> Unit,
    onNew: (incognito: Boolean) -> Unit,
    onCloseAll: () -> Unit,
    onReopenClosed: () -> Unit
) {
    var confirmCloseAll by remember { mutableStateOf(false) }
    if (confirmCloseAll) {
        ConfirmDialog("Close all tabs?", "${tabs.size} tabs will be closed.", onDismiss = { confirmCloseAll = false }) {
            confirmCloseAll = false
            onCloseAll()
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tabs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onNew(true) }) { Icon(Icons.Filled.VisibilityOff, null); Text(" Private") }
            TextButton(onClick = { onNew(false) }) { Icon(Icons.Filled.Add, null); Text(" New") }
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            if (tabs.size > 1) {
                TextButton(onClick = { confirmCloseAll = true }) { Icon(Icons.Filled.Close, null); Text(" Close all tabs") }
            }
            // Re-read when the tab list changes (closing a tab changes it).
            if (remember(tabs) { BrowserManager.hasClosedTab }) {
                TextButton(onClick = onReopenClosed) { Icon(Icons.Filled.Restore, null); Text(" Reopen closed tab") }
            }
        }
        tabs.forEach { t ->
            val tabTitle by t.title.collectAsStateWithLifecycle()
            val tabUrl by t.url.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text(tabTitle.ifBlank { if (tabUrl.isBlank()) "New tab" else displayUrl(tabUrl) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text((if (t.incognito) "Private · " else "") + displayUrl(tabUrl), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { TabPreview(t, tabUrl, selected = t === current) },
                trailingContent = { IconButton(onClick = { onClose(t) }) { Icon(Icons.Filled.Close, "Close tab") } },
                modifier = Modifier.clickable { onSelect(t) }
            )
        }
    }
}

/** The tab's page picture (when one was taken) with its icon; the current tab is outlined. */
@Composable
private fun TabPreview(tab: BrowserTab, url: String, selected: Boolean) {
    val favicon by tab.favicon.collectAsStateWithLifecycle()
    val thumbnail by tab.thumbnail.collectAsStateWithLifecycle()
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.size(width = 48.dp, height = 64.dp)
    ) {
        Box {
            val picture = thumbnail
            if (picture != null) {
                Image(
                    bitmap = remember(picture) { picture.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                ) {
                    TabIcon(tab.incognito, favicon, url, tint, Modifier.padding(2.dp).size(16.dp))
                }
            } else {
                TabIcon(tab.incognito, favicon, url, tint, Modifier.align(Alignment.Center).size(24.dp))
            }
        }
    }
}

/**
 * The page icon: the WebView's (or the last one seen for the site), else the site's
 * `/favicon.ico` for normal tabs, else a globe (incognito icon for private tabs).
 */
@Composable
private fun TabIcon(incognito: Boolean, favicon: android.graphics.Bitmap?, url: String, tint: Color, modifier: Modifier) {
    val fallback = if (incognito) Icons.Filled.VisibilityOff else Icons.Filled.Public
    val host = remember(url) { Uri.parse(url).host?.takeIf { url.startsWith("http") } }
    when {
        favicon != null -> Image(
            bitmap = remember(favicon) { favicon.asImageBitmap() },
            contentDescription = null,
            modifier = modifier
        )
        // Private tabs make no extra requests for icons.
        host != null && !incognito -> coil.compose.SubcomposeAsyncImage(
            model = "https://$host/favicon.ico",
            contentDescription = null,
            loading = { Icon(fallback, null, tint = tint) },
            error = { Icon(fallback, null, tint = tint) },
            modifier = modifier
        )
        else -> Icon(fallback, null, tint = tint, modifier = modifier)
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

internal fun DetectedMedia.toSaved(pageTitle: String, withSubtitles: Boolean): SavedMedia = SavedMedia(
    url = url,
    title = title ?: pageTitle.ifBlank { fileName(url) },
    mimeType = mimeType ?: when (mediaClass) {
        MediaClass.HLS -> "application/x-mpegURL"
        MediaClass.DASH -> "application/dash+xml"
        MediaClass.AUDIO -> "audio/*"
        else -> null
    },
    headers = headers,
    pageUrl = pageUrl,
    posterUrl = posterUrl,
    // The track in the default subtitle language goes first (the TV selects the first one).
    subtitles = if (withSubtitles) subtitles.map { SavedSubtitle(it.url, it.language, it.label, it.mime) }
        .sortedByDescending { com.ruterika.castcore.subtitles.SubtitleNaming.sameLanguage(it.language ?: it.label, CastHub.settings.value.subtitleLanguages.firstOrNull()) }
        else emptyList(),
    isLive = isLive,
    durationMs = durationMs ?: 0L
)

private fun describe(item: DetectedMedia): String = buildList {
    add(
        when (item.mediaClass) {
            MediaClass.HLS -> if (item.isLive) "Live HLS" else "HLS"
            MediaClass.DASH -> "DASH"
            MediaClass.AUDIO -> "Audio"
            else -> item.mimeType?.substringAfter('/')?.uppercase() ?: "Video"
        }
    )
    val height = item.height ?: item.variants.maxOfOrNull { it.height ?: 0 }?.takeIf { it > 0 }
    if (height != null) add("${height}p")
    if (item.variants.size > 1) add("${item.variants.size} qualities")
    item.durationMs?.takeIf { it > 0 }?.let { add(formatDuration(it)) }
    item.contentLength?.takeIf { it > 0 }?.let { add(formatSize(it)) }
    if (item.subtitles.isNotEmpty()) add("CC")
    if (item.isDrmProtected) add("DRM")
    if (item.previouslyFailed) add("Failed before")
}.joinToString(" · ")

/**
 * "Played X / Y" from the recent media history; otherwise where the page's own player is
 * (shows the web position when it is past 15 s).
 */
private fun playedLine(item: DetectedMedia, recent: List<SavedMedia>): String? {
    val played = recent.firstOrNull { it.url == item.url }
    if (played != null && played.positionMs > 0) {
        val total = played.durationMs.takeIf { it > 0 } ?: item.durationMs
        return "Played ${formatDuration(played.positionMs)}" + (total?.let { " / ${formatDuration(it)}" } ?: "")
    }
    val web = item.webPositionMs?.takeIf { it > 15_000L } ?: return null
    return "Played ${formatDuration(web)}" + (item.durationMs?.takeIf { it > 0 }?.let { " / ${formatDuration(it)}" } ?: "") + " on the page"
}

/** "Video ad detected" question before casting something that looks like an ad. */
@Composable
private fun VideoAdDialog(item: DetectedMedia, onPlay: (dontAskAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontAsk by remember { mutableStateOf(false) }
    val short = item.durationMs?.let { it in 1 until 30_000L } == true
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video ad detected") },
        text = {
            Column {
                Text(
                    (if (short) "This is a short clip" else "This video comes from an ad server") +
                        " (${Uri.parse(item.url).host.orEmpty()}). Play it on the TV anyway?"
                )
                Row(
                    Modifier.fillMaxWidth().clickable { dontAsk = !dontAsk }.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                    Text("Don't ask again")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPlay(dontAsk) }) { Text("Play on TV") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Don't play") } }
    )
}

private const val AUTO_CAST_DEBOUNCE_MS = 10_000L

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

private fun fileName(url: String): String = Uri.parse(url).lastPathSegment ?: url

private fun displayUrl(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

private fun playOnPhone(context: Context, item: DetectedMedia) {
    val mime = item.mimeType ?: when (item.mediaClass) {
        MediaClass.HLS -> "application/x-mpegURL"
        MediaClass.DASH -> "application/dash+xml"
        MediaClass.AUDIO -> "audio/*"
        else -> "video/*"
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.url), mime)
    runCatching { context.startActivity(Intent.createChooser(intent, "Play with")) }
}
