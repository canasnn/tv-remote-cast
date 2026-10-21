package com.canasnn.tvremote.cast.backup

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.browser.BrowserManager
import com.canasnn.tvremote.cast.download.DownloadService
import com.canasnn.tvremote.cast.iptv.IptvRepository
import com.canasnn.tvremote.cast.player.PhoneCache
import com.canasnn.tvremote.cast.subtitles.SubtitleLibrary
import com.canasnn.tvremote.cast.subtitles.SubtitlePreparer
import com.ruterika.castcore.library.BackupFile
import com.ruterika.castcore.library.LibraryData
import com.ruterika.castcore.library.LibraryPart
import com.ruterika.castcore.library.LibraryRestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a backup contains. */
enum class BackupModule(val label: String, val part: LibraryPart?) {
    BOOKMARKS("Bookmarks", LibraryPart.BOOKMARKS),
    HISTORY("Browsing history & most visited", LibraryPart.HISTORY),
    TABS("Open tabs", null),
    RECENT("Recent media", LibraryPart.RECENT),
    PLAYLISTS("Playlists", LibraryPart.PLAYLISTS),
    IPTV("IPTV lists & favorites", LibraryPart.IPTV),
    DOWNLOADS("Downloads list", LibraryPart.DOWNLOADS),
    SETTINGS("Settings", null)
}

/** What "Erase data" can remove. */
enum class PrivacyItem(val label: String) {
    HISTORY("Browsing history"),
    MOST_VISITED("Most visited sites"),
    RECENT("Recent media"),
    BOOKMARKS("Bookmarks"),
    PLAYLISTS("Playlists"),
    IPTV_LISTS("IPTV lists & favorite channels"),
    DOWNLOADS("Downloads list (files stay on the phone)"),
    COOKIES("Cookies & logins"),
    SITE_DATA("Site data (storage)"),
    CACHE("Browser cache"),
    TABS("Open tabs"),
    POPUP_SITES("Allowed pop-up sites"),
    SUBTITLES("Downloaded subtitles & subtitle searches"),
    IPTV_CACHE("IPTV list cache"),
    PLAYER_CACHE("Phone player cache");

    companion object {
        val DEFAULT = setOf(HISTORY, CACHE)

        fun fromNames(names: Collection<String>): Set<PrivacyItem> = names.mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet()
    }
}

object Backup {

    private val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun export(context: Context, target: Uri, modules: Set<BackupModule>) = withContext(Dispatchers.IO) {
        val d = CastHub.library.data.value
        fun has(m: BackupModule) = m in modules
        // Local files (content:// / file://) only work on this phone.
        fun local(url: String) = url.startsWith("content://") || url.startsWith("file://")
        val library = LibraryData(
            bookmarks = if (has(BackupModule.BOOKMARKS)) d.bookmarks else emptyList(),
            history = if (has(BackupModule.HISTORY)) d.history else emptyList(),
            sites = if (has(BackupModule.HISTORY)) d.sites else emptyList(),
            recentMedia = if (has(BackupModule.RECENT)) d.recentMedia.filterNot { local(it.url) } else emptyList(),
            playlists = if (has(BackupModule.PLAYLISTS)) d.playlists else emptyList(),
            iptvSources = if (has(BackupModule.IPTV)) d.iptvSources.filterNot { local(it.location) || it.location.startsWith("/") } else emptyList(),
            favoriteChannels = if (has(BackupModule.IPTV)) d.favoriteChannels else emptyList(),
            downloads = if (has(BackupModule.DOWNLOADS)) d.downloads else emptyList()
        )
        val file = BackupFile(
            createdAt = System.currentTimeMillis(),
            library = library,
            settings = if (has(BackupModule.SETTINGS)) CastHub.settings.value.copy(
                // Never put account secrets into a file the user may share.
                openSubtitlesPassword = "",
                openSubtitlesToken = ""
            ) else null,
            tabs = if (has(BackupModule.TABS)) openTabUrls(context) else emptyList()
        )
        context.contentResolver.openOutputStream(target, "wt")!!.use {
            it.write(BackupFile.encode(file).toByteArray())
        }
    }

    /** Reads a backup file; null when it is not one of ours. */
    suspend fun read(context: Context, source: Uri): BackupFile? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(source)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.let(BackupFile::decode)
    }

    /**
     * Restores the chosen modules. With [replace] each chosen list (and the
     * open tabs) is swapped for the backup's; otherwise lists are merged by
     * URL/id with what is already here. Settings are always replaced.
     */
    suspend fun import(context: Context, file: BackupFile, modules: Set<BackupModule>, replace: Boolean) {
        val parts = modules.mapNotNull { it.part }.toSet()
        if (parts.isNotEmpty()) {
            if (replace && LibraryPart.IPTV in parts) {
                CastHub.library.data.value.iptvSources.forEach { IptvRepository.forget(context, it.id) }
            }
            CastHub.library.modify { current -> LibraryRestore.restore(current, file.library, parts, replace) }
        }
        if (BackupModule.SETTINGS in modules) file.settings?.let { restored ->
            CastHub.settingsStore.update { now ->
                restored.copy(
                    openSubtitlesPassword = now.openSubtitlesPassword,
                    openSubtitlesToken = restored.openSubtitlesToken.ifBlank { now.openSubtitlesToken }
                )
            }
        }
        if (BackupModule.TABS in modules) withContext(Dispatchers.Main) {
            BrowserManager.init(context)
            if (replace) BrowserManager.closeAll()
            val open = BrowserManager.tabs.value.map { it.url.value }.toSet()
            file.tabs.filter { it.startsWith("http") && it !in open }.forEach { url ->
                BrowserManager.newTab(url, select = BrowserManager.current.value == null, lazy = true)
            }
            BrowserManager.persist()
        }
    }

    /** URLs of the open normal tabs (the live list when the browser was used, otherwise the saved one). */
    private suspend fun openTabUrls(context: Context): List<String> = withContext(Dispatchers.Main) {
        val live = BrowserManager.tabs.value.filterNot { it.incognito }.map { it.url.value }.filter { it.startsWith("http") }
        live.ifEmpty {
            context.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("open_tabs", null).orEmpty()
                .split('\n').filter { it.startsWith("http") }
        }
    }

    /** Removes the chosen items. */
    suspend fun erase(context: Context, items: Set<PrivacyItem>) {
        withContext(Dispatchers.Main) {
            if (PrivacyItem.TABS in items) {
                BrowserManager.init(context)
                BrowserManager.closeAll()
            }
            if (PrivacyItem.COOKIES in items) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
            if (PrivacyItem.SITE_DATA in items) WebStorage.getInstance().deleteAllData()
            if (PrivacyItem.CACHE in items) runCatching { WebView(context).apply { clearCache(true); destroy() } }
        }
        val lib = CastHub.library
        if (PrivacyItem.HISTORY in items) lib.clearHistory()
        if (PrivacyItem.MOST_VISITED in items) lib.clearMostVisited()
        if (PrivacyItem.RECENT in items) lib.clearRecent()
        if (PrivacyItem.BOOKMARKS in items) lib.modify { it.copy(bookmarks = emptyList()) }
        if (PrivacyItem.PLAYLISTS in items) lib.modify { it.copy(playlists = emptyList()) }
        if (PrivacyItem.DOWNLOADS in items) {
            lib.data.value.downloads.forEach { DownloadService.cancelIfRunning(it.id) }
            lib.modify { it.copy(downloads = emptyList()) }
        }
        if (PrivacyItem.POPUP_SITES in items) CastHub.settingsStore.update { it.copy(allowedPopupHosts = emptySet()) }
        withContext(Dispatchers.IO) {
            if (PrivacyItem.IPTV_LISTS in items || PrivacyItem.IPTV_CACHE in items) {
                lib.data.value.iptvSources.forEach { IptvRepository.forget(context, it.id) }
                File(context.cacheDir, "iptv").deleteRecursively()
            }
            if (PrivacyItem.IPTV_LISTS in items) {
                lib.modify { it.copy(iptvSources = emptyList(), favoriteChannels = emptyList()) }
                File(context.filesDir, "incoming").deleteRecursively()
            }
            if (PrivacyItem.SUBTITLES in items) {
                SubtitleLibrary.store(context).run { deleteAll(); clearSearches() }
                SubtitlePreparer.clearCache()
            }
            if (PrivacyItem.PLAYER_CACHE in items) runCatching { PhoneCache.clear(context) }
            // Files other apps shared with us.
            if (PrivacyItem.RECENT in items) File(context.cacheDir, "incoming").deleteRecursively()
            lib.flush()
        }
    }

    /** "Always erase when leaving the app": called when the main screen is closed for good. */
    fun eraseOnExitIfEnabled(context: Context) {
        val s = CastHub.settings.value
        if (!s.eraseOnExit) return
        val items = PrivacyItem.fromNames(s.eraseOnExitItems)
        if (items.isEmpty()) return
        val app = context.applicationContext
        exitScope.launch { runCatching { erase(app, items) } }
    }
}
