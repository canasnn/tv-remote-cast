package com.canasnn.tvremote.cast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Title
import com.canasnn.tvremote.cast.BatteryOptimization
import com.canasnn.tvremote.cast.CallPauseMonitor
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.ui.app.AppBackdrop
import com.canasnn.tvremote.ui.app.SettingItemRow
import com.canasnn.tvremote.ui.app.SettingSubtitle
import com.canasnn.tvremote.ui.app.SettingsTopBar
import com.canasnn.tvremote.ui.app.LocalSettingsQuery
import com.canasnn.tvremote.ui.app.settingMatches
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.canasnn.tvremote.cast.player.PhoneCache
import com.ruterika.castcore.settings.CastSettings
import com.ruterika.castcore.settings.PhonePlayerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ruterika.castcore.settings.SubtitleCharsetPref
import com.ruterika.castcore.settings.SubtitleEdge
import com.ruterika.castcore.settings.TabRestore
import com.ruterika.castcore.settings.VideoTapAction

/** All options, grouped like the rest of the app's settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSettingsScreen(onBack: () -> Unit, onOpenHelp: () -> Unit = {}) {
    val s by CastHub.settings.collectAsStateWithLifecycle()
    val update: ((CastSettings) -> CastSettings) -> Unit = CastHub.settingsStore::update
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var query by rememberSaveable { mutableStateOf("") }

    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    dialog?.invoke()
    val dismiss = { dialog = null }

    fun textDialog(title: String, value: String, hint: String = "", password: Boolean = false, onSave: (String) -> Unit) {
        dialog = { TextInputDialog(title, value, hint, password, onDismiss = dismiss, onSave = { onSave(it); dismiss() }) }
    }

    fun <T> choiceDialog(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
        dialog = { ChoiceDialog(title, options, selected, label, onDismiss = dismiss, onSelect = { onSelect(it); dismiss() }) }
    }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = { SettingsTopBar(title = "Casting & Browser", onBack = onBack, scrollBehavior = scrollBehavior) },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        ) { innerPadding ->
            CompositionLocalProvider(LocalSettingsQuery provides query) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                item { SettingsSearchField(query, onChange = { query = it }) }
                item { SettingItemRow(title = "Casting help & FAQ", desc = "The TV receiver, installing it, fixing common problems", icon = Icons.AutoMirrored.Filled.HelpOutline, onClick = onOpenHelp) }

                // ── Browser ─────────────────────────────────────────────────
                item { SettingSubtitle(text = "Browser") }
                item {
                    SwitchRow("Open links in the in-app browser", "Web pages you open with this app from other apps (pick it as a browser)", Icons.Filled.Language, s.openLinksInApp) { v ->
                        update { it.copy(openLinksInApp = v) }
                    }
                }
                searchEngineSetting(s, showDialog = { dialog = it }, dismiss = dismiss)
                item {
                    SettingItemRow(
                        title = "Homepage",
                        desc = s.homepage.ifBlank { "Start page with bookmarks and most visited" },
                        icon = Icons.Filled.Home,
                        onClick = { textDialog("Homepage", s.homepage, "https://") { v -> update { it.copy(homepage = v.trim()) } } }
                    )
                }
                item { SwitchRow("Block pop-ups", "New windows opened by pages are blocked", Icons.Filled.Block, s.blockPopups) { v -> update { it.copy(blockPopups = v) } } }
                item { SwitchRow("Ad blocking", "Block requests to known ad and tracker hosts", Icons.Filled.FilterAlt, s.adBlock) { v -> update { it.copy(adBlock = v) } } }
                adBlockMoreSettings(s, update, showDialog = { dialog = it }, dismiss = dismiss)
                item { SwitchRow("Block ad redirects", "Stop pages from jumping to ad sites", Icons.Filled.AdsClick, s.blockAdRedirects) { v -> update { it.copy(blockAdRedirects = v) } } }
                item { SwitchRow("Block opening other apps", "Pages can't launch Play Store or other apps", Icons.Filled.OpenInNew, s.blockExternalApps) { v -> update { it.copy(blockExternalApps = v) } } }
                item { SwitchRow("Third-party cookies", "Needed by some video players and logins", Icons.Filled.Cookie, s.thirdPartyCookies) { v -> update { it.copy(thirdPartyCookies = v) } } }
                browserMoreSettings(s, update, showDialog = { dialog = it }, dismiss = dismiss)
                item {
                    SettingItemRow(
                        title = "Restore tabs on start",
                        desc = s.restoreTabs.label,
                        icon = Icons.Filled.Restore,
                        onClick = { choiceDialog("Restore tabs", TabRestore.entries, s.restoreTabs, { it.label }) { v -> update { it.copy(restoreTabs = v) } } }
                    )
                }
                item {
                    SettingItemRow(
                        title = "Clear browsing history",
                        desc = "${CastHub.library.data.value.history.size} entries",
                        icon = Icons.Filled.History,
                        onClick = {
                            dialog = {
                                ConfirmDialog("Clear history?", "Browsing history and most visited sites will be deleted.", onDismiss = dismiss) {
                                    CastHub.library.clearHistory(); dismiss()
                                }
                            }
                        }
                    )
                }
                item {
                    fun daysLabel(days: Int) = if (days <= 0) "Off" else "Older than $days days"
                    SettingItemRow(
                        title = "Delete old history",
                        desc = daysLabel(s.historyAutoDeleteDays),
                        icon = Icons.Filled.History,
                        onClick = {
                            choiceDialog("Delete history older than", listOf(0, 7, 30, 90), s.historyAutoDeleteDays, ::daysLabel) { v ->
                                update { it.copy(historyAutoDeleteDays = v) }
                                CastHub.library.deleteHistoryOlderThan(v)
                            }
                        }
                    )
                }

                // ── Video detection ─────────────────────────────────────────
                item { SettingSubtitle(text = "Video detection") }
                item { SwitchRow("Advanced detection", "Read players and video tags inside pages", Icons.Filled.VideoLibrary, s.advancedDetection) { v -> update { it.copy(advancedDetection = v) } } }
                item { SwitchRow("Show video list automatically", "Open the list as soon as a video is found", Icons.Filled.Visibility, s.autoShowVideoList) { v -> update { it.copy(autoShowVideoList = v) } } }
                item { SwitchRow("Clear list on new page", "Forget videos when you navigate away", Icons.Filled.Refresh, s.clearListOnNavigation) { v -> update { it.copy(clearListOnNavigation = v) } } }
                item { SwitchRow("Hide short clips", "Treat videos under 30 s as ads (ad hosts are always hidden)", Icons.Filled.FilterAlt, s.hideShortVideos) { v -> update { it.copy(hideShortVideos = v) } } }
                item { SwitchRow("Cast when a page video starts", "Playing a video on a page sends it to the TV", Icons.Filled.PlayCircle, s.castOnPagePlay) { v -> update { it.copy(castOnPagePlay = v) } } }
                item { SwitchRow("Pause the page when casting", "Stop the web player so audio doesn't play twice", Icons.Filled.PlayCircle, s.pausePageVideoOnCast) { v -> update { it.copy(pausePageVideoOnCast = v) } } }
                videoDetectionMoreSettings(s, update, showDialog = { dialog = it }, dismiss = dismiss)
                item {
                    SettingItemRow(
                        title = "Tapping a video",
                        desc = s.videoTapAction.label,
                        icon = Icons.Filled.TouchApp,
                        onClick = { choiceDialog("Tapping a video", VideoTapAction.entries, s.videoTapAction, { it.label }) { v -> update { it.copy(videoTapAction = v) } } }
                    )
                }

                // ── Player ──────────────────────────────────────────────────
                item { SettingSubtitle(text = "TV player") }
                item { SettingItemRow(title = "Skip back", desc = "${s.skipBackSeconds} seconds", icon = Icons.Filled.FastForward) }
                item { ChipRow("Skip back", listOf(5, 10, 15, 30, 60, 120), s.skipBackSeconds, { "${it}s" }) { v -> update { it.copy(skipBackSeconds = v) } } }
                item { SettingItemRow(title = "Skip forward", desc = "${s.skipForwardSeconds} seconds", icon = Icons.Filled.FastForward) }
                item { ChipRow("Skip forward", listOf(5, 10, 15, 30, 60, 120), s.skipForwardSeconds, { "${it}s" }) { v -> update { it.copy(skipForwardSeconds = v) } } }
                item {
                    SettingItemRow(
                        title = "Resume videos",
                        desc = if (!s.resumePlayback) "Off" else s.resumeMode.label,
                        icon = Icons.Filled.Timer,
                        onClick = {
                            choiceDialog("Continue where you left off", com.ruterika.castcore.settings.ResumeMode.entries, s.resumeMode, { it.label }) { v ->
                                update { it.copy(resumeMode = v, resumePlayback = v != com.ruterika.castcore.settings.ResumeMode.NEVER) }
                            }
                        }
                    )
                }
                item { SwitchRow("Open controller when playback starts", null, Icons.Filled.PlayCircle, s.openControllerOnPlay) { v -> update { it.copy(openControllerOnPlay = v) } } }
                item { SwitchRow("Ask when something is already playing", "Play now or add to queue", Icons.Filled.SkipNext, s.showAlreadyPlayingDialog) { v -> update { it.copy(showAlreadyPlayingDialog = v) } } }
                item { SwitchRow("Artwork on lock screen", null, Icons.Filled.Palette, s.lockscreenArtwork) { v -> update { it.copy(lockscreenArtwork = v) } } }
                item { ControllerSettingsRows(s, update) }
                item { SettingItemRow(title = "Photo slideshow", desc = if (s.slideshowSeconds == 0) "Manual" else "Every ${s.slideshowSeconds} s", icon = Icons.Filled.Timer) }
                item { ChipRow("Photo slideshow", listOf(0, 3, 5, 10, 20, 60), s.slideshowSeconds, { if (it == 0) "Manual" else "${it}s" }) { v -> update { it.copy(slideshowSeconds = v) } } }
                item { SwitchRow("Swipe between photos", "Swipe the photo on the now-playing screen to go back or forward", Icons.Filled.Swipe, s.photoSwipe) { v -> update { it.copy(photoSwipe = v) } } }
                item { SettingItemRow(title = "Default speed", desc = if (s.defaultSpeed == 1f) "Normal" else "${s.defaultSpeed}x", icon = Icons.Filled.Speed) }
                item { ChipRow("Default speed", listOf(0.75f, 1f, 1.25f, 1.5f, 2f), s.defaultSpeed, { if (it == 1f) "Normal" else "${it}x" }) { v -> update { it.copy(defaultSpeed = v) } } }
                item { SwitchRow("Play next automatically", "Continue with the next playlist item", Icons.Filled.SkipNext, s.autoplayNext) { v -> update { it.copy(autoplayNext = v) } } }
                item {
                    SettingItemRow(
                        title = "Preferred audio language",
                        desc = s.preferredAudioLanguage.ifBlank { "Stream default" },
                        icon = Icons.Filled.Language,
                        onClick = { textDialog("Audio language code", s.preferredAudioLanguage, "tr, en, de…") { v -> update { it.copy(preferredAudioLanguage = v.trim().lowercase()) } } }
                    )
                }

                // ── Phone player ────────────────────────────────────────────
                item { SettingSubtitle(text = "Phone player") }
                item { SwitchRow("Background play", "Keep playing when you leave the player", Icons.Filled.PlayCircle, s.phoneBackgroundPlay) { v -> update { it.copy(phoneBackgroundPlay = v) } } }
                item { SwitchRow("Gestures", "Double tap to skip, swipe edges for brightness and volume", Icons.Filled.TouchApp, s.phoneGestures) { v -> update { it.copy(phoneGestures = v) } } }
                item { PhonePlayerRows(s, update, setDialog = { dialog = it }, dismiss = dismiss) }

                // ── Subtitles ───────────────────────────────────────────────
                item { SettingSubtitle(text = "Subtitles") }
                item { SwitchRow("Load subtitles found on pages", "Attach .vtt/.srt files the page uses", Icons.Filled.ClosedCaption, s.autoLoadDetectedSubtitles) { v -> update { it.copy(autoLoadDetectedSubtitles = v) } } }
                item { SwitchRow("Always use subtitles", "Show the subtitle chooser every time a video is cast", Icons.Filled.Subtitles, s.promptSubtitles) { v -> update { it.copy(promptSubtitles = v) } } }
                item {
                    SettingItemRow(
                        title = "Subtitle languages",
                        desc = s.subtitleLanguages.joinToString(", ").ifBlank { "Any" },
                        icon = Icons.Filled.Language,
                        onClick = {
                            textDialog("Languages (comma separated)", s.subtitleLanguages.joinToString(", "), "tr, en") { v ->
                                update { it.copy(subtitleLanguages = v.split(',', ' ').map(String::trim).filter(String::isNotEmpty).map(String::lowercase)) }
                            }
                        }
                    )
                }
                item {
                    SettingItemRow(
                        title = "Character encoding",
                        desc = s.subtitleCharset.label,
                        icon = Icons.Filled.TextFields,
                        onClick = { choiceDialog("Subtitle encoding", SubtitleCharsetPref.entries, s.subtitleCharset, { it.label }) { v -> update { it.copy(subtitleCharset = v) } } }
                    )
                }
                item {
                    if (settingMatches(LocalSettingsQuery.current, SUBTITLE_STYLE_KEYWORDS)) SubtitleStyleEditor(
                        look = s.subtitleLook,
                        onChange = { v -> update { it.copy(subtitleLook = v) } },
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
                item { SwitchRow("Search OpenSubtitles automatically", "Search for the playing video when the subtitle screen opens", Icons.Filled.Search, s.autoSearchOpenSubtitles) { v -> update { it.copy(autoSearchOpenSubtitles = v) } } }
                item { SwitchRow("Always do exact searches", "Use the entered name literally. Helps when results are irrelevant or empty", Icons.Filled.Search, s.subtitleExactAlways) { v -> update { it.copy(subtitleExactAlways = v) } } }
                item { SwitchRow("Remember subtitle searches", "Suggest earlier names, languages, seasons and episodes", Icons.Filled.Search, s.rememberSubtitleSearches) { v -> update { it.copy(rememberSubtitleSearches = v) } } }
                item {
                    SettingItemRow(
                        title = "OpenSubtitles API key",
                        desc = if (s.openSubtitlesApiKey.isBlank()) "Needed for subtitle search (free at opensubtitles.com)" else "Set",
                        icon = Icons.Filled.Key,
                        onClick = { textDialog("OpenSubtitles API key", s.openSubtitlesApiKey, "Consumer API key") { v -> update { it.copy(openSubtitlesApiKey = v.trim()) } } }
                    )
                }
                item {
                    SettingItemRow(
                        title = "OpenSubtitles account",
                        desc = if (s.openSubtitlesToken.isNotBlank()) "Logged in as ${s.openSubtitlesUser}" else "Anonymous · log in for a higher daily download limit",
                        icon = Icons.Filled.Person,
                        onClick = { dialog = { OpenSubtitlesAccountDialog(onDismiss = dismiss) } }
                    )
                }

                // ── IPTV ────────────────────────────────────────────────────
                item { SettingSubtitle(text = "IPTV") }
                item {
                    SettingItemRow(
                        title = "List update mode",
                        desc = "${s.iptvUpdateMode.label} · ${s.iptvUpdateMode.description}",
                        icon = Icons.Filled.LiveTv,
                        onClick = {
                            choiceDialog("Update mode", com.ruterika.castcore.iptv.IptvUpdateMode.entries, s.iptvUpdateMode, { it.label }) { v ->
                                update { it.copy(iptvUpdateMode = v) }
                            }
                        }
                    )
                }
                item {
                    SwitchRow("Skip single groups", "Open a level directly when it only holds one group", Icons.Filled.LiveTv, s.iptvSkipSingleGroups) { v ->
                        update { it.copy(iptvSkipSingleGroups = v) }
                    }
                }
                item {
                    SwitchRow("Use subgroups", "Show pltv-subgroup as folders inside groups", Icons.Filled.LiveTv, s.iptvConsiderSubgroups) { v ->
                        update { it.copy(iptvConsiderSubgroups = v) }
                    }
                }
                item {
                    SwitchRow("Rename channels with tvg-name", "Use the tvg-name attribute as the channel name", Icons.Filled.LiveTv, s.iptvRenameWithTvgName) { v ->
                        update { it.copy(iptvRenameWithTvgName = v) }
                    }
                }
                item {
                    SwitchRow("Keep the feed's own channel item", "RSS/XML lists: show the feed's root channel as a folder holding its items", Icons.Filled.LiveTv, s.iptvKeepRssRootChannel) { v ->
                        update { it.copy(iptvKeepRssRootChannel = v) }
                    }
                }
                item {
                    SettingItemRow(
                        title = "Default IPTV user agent",
                        desc = s.iptvDefaultUserAgent.ifBlank { "None" },
                        icon = Icons.Filled.Person,
                        onClick = { textDialog("IPTV user agent", s.iptvDefaultUserAgent) { v -> update { it.copy(iptvDefaultUserAgent = v.trim()) } } }
                    )
                }

                // ── Downloads ───────────────────────────────────────────────
                item { SettingSubtitle(text = "Downloads") }
                item { SwitchRow("Download on Wi-Fi only", null, Icons.Filled.Wifi, s.downloadWifiOnly) { v -> update { it.copy(downloadWifiOnly = v) } } }
                item {
                    SwitchRow("Save HLS streams as .ts", "Join the stream's parts instead of converting to MP4 (faster, not every player seeks in it)", Icons.Filled.Download, s.downloadHlsAsTs) { v ->
                        update { it.copy(downloadHlsAsTs = v) }
                    }
                }
                item {
                    SwitchRow("Download subtitles too", "Save the video's subtitle next to the downloaded file", Icons.Filled.Subtitles, s.downloadSubtitles) { v ->
                        update { it.copy(downloadSubtitles = v) }
                    }
                }
                item { SettingSubtitle(text = "Backup & privacy") }
                item { BackupAndPrivacyRows() }
                item {
                    SettingItemRow(
                        title = "Reset cast settings",
                        icon = Icons.Filled.Download,
                        onClick = {
                            dialog = {
                                ConfirmDialog("Reset settings?", "All casting and browser options go back to defaults.", onDismiss = dismiss) {
                                    CastHub.settingsStore.reset(); dismiss()
                                }
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            }
        }
    }
}

/**
 * Controller options: title, phone calls (asks for READ_PHONE_STATE only
 * when turned on), mini player, help tips, "did it play?", battery optimisation.
 */
@Composable
private fun ControllerSettingsRows(s: CastSettings, update: ((CastSettings) -> CastSettings) -> Unit) {
    val context = LocalContext.current
    val phonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) update { it.copy(pauseOnCall = true) }
        else android.widget.Toast.makeText(context, "Phone permission is needed to notice calls", android.widget.Toast.LENGTH_SHORT).show()
    }
    val batteryOptimized = rememberBatteryOptimized()
    Column {
        SwitchRow("Send title to TV", "Show the video's name on the TV", Icons.Filled.Title, s.sendTitleToTv) { v -> update { it.copy(sendTitleToTv = v) } }
        SwitchRow("Pause when a call comes in", "Pause the TV while the phone rings or a call is active", Icons.Filled.Call, s.pauseOnCall) { v ->
            when {
                !v -> update { it.copy(pauseOnCall = false) }
                CallPauseMonitor.hasPermission(context) -> update { it.copy(pauseOnCall = true) }
                else -> phonePermission.launch(Manifest.permission.READ_PHONE_STATE)
            }
        }
        if (s.pauseOnCall) {
            SwitchRow("Resume after the call", "Continue playing when the call ends", Icons.Filled.PlayCircle, s.resumeAfterCall) { v -> update { it.copy(resumeAfterCall = v) } }
        }
        SwitchRow("Show mini player", "Small player bar on the cast screens", Icons.Filled.PictureInPicture, s.showMiniController) { v -> update { it.copy(showMiniController = v) } }
        SwitchRow("Help tips", "Tips banner on the controller", Icons.Filled.Lightbulb, s.showHelpTips) { v -> update { it.copy(showHelpTips = v) } }
        SwitchRow("Ask if the video played", "A few seconds after playback starts", Icons.Filled.ThumbUp, s.askVideoPlayed) { v -> update { it.copy(askVideoPlayed = v) } }
        SettingItemRow(
            title = "Battery optimisation",
            desc = if (batteryOptimized) "On · Android may stop casting when the screen is off. Tap to allow background use"
            else "Off · casting keeps running with the screen off",
            icon = Icons.Filled.BatteryAlert,
            onClick = {
                update { it.copy(batteryHintDismissed = false) }
                if (batteryOptimized) BatteryOptimization.requestExemption(context)
                else runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        )
    }
}

private const val SUBTITLE_STYLE_KEYWORDS = "Subtitle style text size color colour font bold edge outline shadow background position preview"

/** Rows that don't match the typed words are hidden. */
@Composable
private fun SettingsSearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Search settings") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear search") }
        },
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** The rest of the "Phone player" section. */
@Composable
private fun PhonePlayerRows(
    s: CastSettings,
    update: ((CastSettings) -> CastSettings) -> Unit,
    setDialog: ((@Composable () -> Unit)?) -> Unit,
    dismiss: () -> Unit
) {
    val p = s.phonePlayer
    val phone: ((PhonePlayerSettings) -> PhonePlayerSettings) -> Unit = { f -> update { it.copy(phonePlayer = f(it.phonePlayer)) } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { PhoneCache.sizeBytes(context) } }

    Column {
        if (s.phoneGestures) {
            Column(Modifier.padding(start = 24.dp)) {
                SwitchRow("Double tap to skip / pause", null, Icons.Filled.TouchApp, p.gestureDoubleTap) { v -> phone { it.copy(gestureDoubleTap = v) } }
                SwitchRow("Swipe sideways to skip", null, Icons.Filled.SwapHoriz, p.gestureSwipeSeek) { v -> phone { it.copy(gestureSwipeSeek = v) } }
                SwitchRow("Swipe left edge for brightness", null, Icons.Filled.BrightnessMedium, p.gestureSwipeBrightness) { v -> phone { it.copy(gestureSwipeBrightness = v) } }
                SwitchRow("Swipe right edge for volume", null, Icons.AutoMirrored.Filled.VolumeUp, p.gestureSwipeVolume) { v -> phone { it.copy(gestureSwipeVolume = v) } }
            }
        }
        SwitchRow("Always play in app", "Videos you open from the library play on the phone instead of the TV", Icons.Filled.Smartphone, p.alwaysPlayInApp) { v -> phone { it.copy(alwaysPlayInApp = v) } }
        SwitchRow("Remember volume", "Start the next video at the last level", Icons.AutoMirrored.Filled.VolumeUp, p.rememberVolume) { v -> phone { it.copy(rememberVolume = v) } }
        SwitchRow("Lower volume before playing", "Every video starts at 25 %", Icons.AutoMirrored.Filled.VolumeDown, p.lowerVolumeBeforePlay) { v -> phone { it.copy(lowerVolumeBeforePlay = v) } }
        SwitchRow("Always start muted", "Videos start muted (audio files don't)", Icons.AutoMirrored.Filled.VolumeOff, p.startMuted) { v -> phone { it.copy(startMuted = v) } }
        SwitchRow("Volume boost", "Allow up to 200 % volume", Icons.Filled.GraphicEq, p.volumeBoost) { v -> phone { it.copy(volumeBoost = v, volumeLevel = it.volumeLevel.coerceAtMost(if (v) 2f else 1f)) } }
        if (p.volumeBoost) {
            SettingItemRow(title = "Maximum boost", desc = "+${p.boostMaxDb} dB at 200 %", icon = Icons.Filled.GraphicEq)
            ChipRow("Maximum boost volume", PhonePlayerSettings.BOOST_CHOICES_DB, p.boostMaxDb, { "+$it dB" }) { v -> phone { it.copy(boostMaxDb = v) } }
        }
        SwitchRow("Remember brightness", "Swipe brightness is kept for the next video", Icons.Filled.BrightnessMedium, p.rememberBrightness) { v ->
            phone { it.copy(rememberBrightness = v, brightnessLevel = if (v) it.brightnessLevel else -1f) }
        }
        SwitchRow("Remember screen orientation", "Rotate stays the way you left it", Icons.Filled.ScreenRotation, p.rememberOrientation) { v ->
            phone { it.copy(rememberOrientation = v, orientation = if (v) it.orientation else -1) }
        }
        SettingItemRow(title = "Buffer size", desc = "${p.bufferSeconds} s ahead · applies next time the player opens", icon = Icons.Filled.HourglassBottom)
        ChipRow("Buffer size", PhonePlayerSettings.BUFFER_CHOICES, p.bufferSeconds, { "${it}s" }) { v -> phone { it.copy(bufferSeconds = v) } }
        SettingItemRow(
            title = "Disk cache",
            desc = (if (p.cacheMb == 0) "Off" else "Up to ${sizeLabel(p.cacheMb * 1024L * 1024L)}") + " · for MP4/MKV files, applies next time the player opens",
            icon = Icons.Filled.Storage
        )
        ChipRow("Disk cache", PhonePlayerSettings.CACHE_CHOICES_MB, p.cacheMb, { if (it == 0) "Off" else sizeLabel(it * 1024L * 1024L) }) { v -> phone { it.copy(cacheMb = v) } }
        SettingItemRow(
            title = "Clear cache",
            desc = cacheBytes?.let { "${sizeLabel(it)} used" } ?: "…",
            icon = Icons.Filled.DeleteSweep,
            onClick = {
                setDialog {
                    ConfirmDialog("Clear cache?", "Cached parts of videos played on the phone will be deleted.", onDismiss = dismiss) {
                        dismiss()
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) { PhoneCache.clear(context) }
                            cacheBytes = withContext(Dispatchers.IO) { PhoneCache.sizeBytes(context) }
                            android.widget.Toast.makeText(context, "Freed ${sizeLabel(freed)}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
        SwitchRow("Show all tracks", "Offer qualities the phone may not decode smoothly · applies next time", Icons.Filled.HighQuality, p.allowAllTracks) { v -> phone { it.copy(allowAllTracks = v) } }
        SwitchRow("Own subtitle style", "Style phone subtitles separately from the TV", Icons.Filled.Subtitles, p.subtitleLook != null) { v ->
            phone { it.copy(subtitleLook = if (v) it.subtitleLook ?: s.subtitleLook else null) }
        }
        p.subtitleLook?.takeIf { settingMatches(LocalSettingsQuery.current, "Phone $SUBTITLE_STYLE_KEYWORDS") }?.let { look ->
            SubtitleStyleEditor(
                look = look,
                onChange = { v -> phone { it.copy(subtitleLook = v) } },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024)).replace(".0 ", " ")
    bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / 1024} KB"
}

@Composable
internal fun SwitchRow(title: String, desc: String?, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingItemRow(
        title = title,
        desc = desc,
        icon = icon,
        onClick = { onChange(!checked) },
        action = { Switch(checked = checked, onCheckedChange = onChange) }
    )
}

@Composable
private fun <T> ChipRow(filterKey: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    if (!settingMatches(LocalSettingsQuery.current, filterKey)) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
internal fun TextInputDialog(
    title: String,
    initial: String,
    hint: String,
    password: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { if (hint.isNotEmpty()) Text(hint) },
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, onClick = { onSelect(option) })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option) })
                        Text(label(option), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
internal fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
