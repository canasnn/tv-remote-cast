package com.ruterika.castcore.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.random.Random

/**
 * Bookmarks, history, most visited sites, recent media, playlists and IPTV
 * sources, persisted as one JSON file. Small enough to keep in memory; writes
 * are debounced and atomic (temp file + rename).
 */
class LibraryStore(
    private val file: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val saveDelayMs: Long = 500L
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    /** Set by [load] when the file had old-format playlists. */
    private var loadedLegacy = false
    private val _data = MutableStateFlow(load())
    val data: StateFlow<LibraryData> = _data.asStateFlow()
    private var pendingSave: Job? = null

    init {
        // Old playlists just got entry ids: save them so the ids stay stable.
        if (loadedLegacy) scheduleSave()
    }

    // ── Bookmarks ───────────────────────────────────────────────────────────

    fun isBookmarked(url: String): Boolean = _data.value.bookmarks.any { it.url == url }

    /** Adds or removes a bookmark. A new one gets [iconUrl], or the site's /favicon.ico. */
    fun toggleBookmark(url: String, title: String, iconUrl: String? = null): Boolean {
        var added = false
        edit { d ->
            if (d.bookmarks.any { it.url == url }) d.copy(bookmarks = d.bookmarks.filterNot { it.url == url })
            else {
                added = true
                val icon = iconUrl?.takeIf { it.isNotBlank() } ?: faviconUrl(url)
                d.copy(bookmarks = listOf(Bookmark(url, title.ifBlank { url }, clock(), icon)) + d.bookmarks)
            }
        }
        return added
    }

    fun removeBookmark(url: String) = edit { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.url == url }) }

    fun removeBookmarks(urls: Set<String>) {
        if (urls.isNotEmpty()) edit { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.url in urls }) }
    }

    fun renameBookmark(url: String, title: String) = edit { d ->
        d.copy(bookmarks = d.bookmarks.map { if (it.url == url) it.copy(title = title) else it })
    }

    // ── History / most visited ──────────────────────────────────────────────

    /** Records a finished page load. Consecutive duplicates only update the title. */
    fun recordVisit(url: String, title: String, incognito: Boolean = false) {
        if (incognito || !url.startsWith("http")) return
        val now = clock()
        edit { d ->
            val history = if (d.history.firstOrNull()?.url == url) {
                listOf(d.history.first().copy(title = title.ifBlank { d.history.first().title }, visitedAt = now)) + d.history.drop(1)
            } else {
                (listOf(HistoryEntry(url, title.ifBlank { url }, now)) + d.history).take(MAX_HISTORY)
            }
            val host = hostOf(url) ?: return@edit d.copy(history = history)
            // Most visited counts per page (normalised full URL).
            val key = pageKey(url)
            val existing = d.sites.firstOrNull { pageKey(it.url) == key }
            val site = existing?.copy(
                count = existing.count + if (d.history.firstOrNull()?.url == url) 0 else 1,
                lastVisitedAt = now,
                title = title.ifBlank { existing.title }
            ) ?: SiteVisits(host, url, title.ifBlank { host }, 1, now)
            d.copy(
                history = history,
                sites = (listOf(site) + d.sites.filterNot { pageKey(it.url) == key }).take(MAX_SITES)
            )
        }
    }

    /** Most visited sites, one per host (visits of its pages added up), for the start page grid. */
    fun mostVisited(limit: Int = 12): List<SiteVisits> =
        _data.value.sites.groupBy { it.host }.map { (host, pages) ->
            val top = pages.maxWith(siteOrder)
            SiteVisits(host, "https://$host", top.title, pages.sumOf { it.count }, pages.maxOf { it.lastVisitedAt })
        }.sortedWith(siteOrder.reversed()).take(limit)

    /** Most visited pages (full URLs), most visits first. */
    fun mostVisitedPages(limit: Int = Int.MAX_VALUE): List<SiteVisits> =
        _data.value.sites.sortedWith(siteOrder.reversed()).take(limit)

    /** Removes every page of [host]. */
    fun removeSite(host: String) = edit { it.copy(sites = it.sites.filterNot { s -> s.host == host }) }

    /** Removes one page from most visited. */
    fun removeSitePage(url: String) {
        val key = pageKey(url)
        edit { it.copy(sites = it.sites.filterNot { s -> pageKey(s.url) == key }) }
    }

    fun removeHistoryEntry(entry: HistoryEntry) = edit { it.copy(history = it.history - entry) }

    fun removeHistoryEntries(entries: Collection<HistoryEntry>) {
        val set = entries.toSet()
        if (set.isNotEmpty()) edit { it.copy(history = it.history.filterNot { h -> h in set }) }
    }

    /** Drops visits older than [days] days (0 = off). */
    fun deleteHistoryOlderThan(days: Int) {
        if (days <= 0) return
        val cutoff = clock() - days * DAY_MS
        if (_data.value.history.none { it.visitedAt < cutoff }) return
        edit { d -> d.copy(history = d.history.filter { it.visitedAt >= cutoff }) }
    }

    /** Deletes history newer than [sinceMs] (0 = everything). Most visited is kept. */
    fun clearHistory(sinceMs: Long = 0L) = edit { d ->
        d.copy(history = if (sinceMs == 0L) emptyList() else d.history.filter { it.visitedAt < sinceMs })
    }

    fun clearMostVisited() = edit { it.copy(sites = emptyList()) }

    // ── Recent media ────────────────────────────────────────────────────────

    fun addRecent(media: SavedMedia) = edit { d ->
        val item = media.copy(addedAt = clock())
        d.copy(recentMedia = (listOf(item) + d.recentMedia.filterNot { it.url == media.url }).take(MAX_RECENT))
    }

    fun updateRecentPosition(url: String, positionMs: Long, durationMs: Long) = edit { d ->
        d.copy(recentMedia = d.recentMedia.map {
            if (it.url == url) it.copy(positionMs = positionMs, durationMs = durationMs.takeIf { v -> v > 0 } ?: it.durationMs) else it
        })
    }

    fun renameRecent(url: String, title: String) = edit { d ->
        d.copy(recentMedia = d.recentMedia.map { if (it.url == url) it.copy(title = title) else it })
    }

    fun removeRecent(url: String) = edit { it.copy(recentMedia = it.recentMedia.filterNot { m -> m.url == url }) }
    fun clearRecent() = edit { it.copy(recentMedia = emptyList()) }

    // ── Playlists ───────────────────────────────────────────────────────────

    fun playlist(id: String): Playlist? = _data.value.playlists.firstOrNull { it.id == id }

    fun createPlaylist(name: String): Playlist {
        val playlist = Playlist(UUID.randomUUID().toString(), name.ifBlank { "Playlist" }, createdAt = clock(), updatedAt = clock())
        edit { it.copy(playlists = it.playlists + playlist) }
        return playlist
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }
    fun deletePlaylist(id: String) = edit { it.copy(playlists = it.playlists.filterNot { p -> p.id == id }) }

    /**
     * Appends [media] to [playlistId], or to the default "Queue" playlist
     * (created on demand). Duplicates are allowed.
     */
    fun addToPlaylist(media: SavedMedia, playlistId: String? = null): Playlist {
        val target = playlistId?.let { id -> playlist(id) }
            ?: playlist(DEFAULT_QUEUE_ID)
            ?: Playlist(DEFAULT_QUEUE_ID, "Queue", createdAt = clock()).also { q -> edit { it.copy(playlists = listOf(q) + it.playlists) } }
        addToPlaylist(listOf(media), target.id)
        return playlist(target.id) ?: target
    }

    /** Appends [media] to playlist [playlistId]; false when it doesn't exist. */
    fun addToPlaylist(media: List<SavedMedia>, playlistId: String): Boolean {
        if (playlist(playlistId) == null) return false
        val now = clock()
        val entries = media.map { PlaylistEntry(UUID.randomUUID().toString(), it.copy(addedAt = now), now) }
        editPlaylist(playlistId) { p -> p.copy(entries = p.entries + entries) }
        return true
    }

    fun removeFromPlaylist(id: String, index: Int) = editPlaylist(id) { p ->
        if (index !in p.entries.indices) p else p.copy(entries = p.entries.toMutableList().apply { removeAt(index) })
    }

    /** Removes [entryIds]; returns what was removed with its old index, for undo. */
    fun removeEntries(playlistId: String, entryIds: Set<String>): List<IndexedValue<PlaylistEntry>> {
        val removed = playlist(playlistId)?.entries?.withIndex()?.filter { it.value.id in entryIds }.orEmpty()
        if (removed.isNotEmpty()) editPlaylist(playlistId) { p -> p.copy(entries = p.entries.filterNot { it.id in entryIds }) }
        return removed
    }

    /** Puts entries back where [removeEntries] took them from (undo). */
    fun restoreEntries(playlistId: String, removed: List<IndexedValue<PlaylistEntry>>) = editPlaylist(playlistId) { p ->
        val ids = removed.map { it.value.id }.toSet()
        val list = p.entries.filterNot { it.id in ids }.toMutableList()
        removed.sortedBy { it.index }.forEach { (index, entry) -> list.add(index.coerceIn(0, list.size), entry) }
        p.copy(entries = list)
    }

    fun movePlaylistItem(id: String, from: Int, to: Int) = editPlaylist(id) { p ->
        if (from !in p.entries.indices || to !in p.entries.indices) p
        else p.copy(entries = p.entries.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Moves an entry [delta] places (drag and drop); false when it is already at the edge. */
    fun moveEntry(playlistId: String, entryId: String, delta: Int): Boolean {
        val entries = playlist(playlistId)?.entries ?: return false
        val from = entries.indexOfFirst { it.id == entryId }
        val to = from + delta
        if (from < 0 || delta == 0 || to !in entries.indices) return false
        movePlaylistItem(playlistId, from, to)
        return true
    }

    fun renameEntry(playlistId: String, entryId: String, title: String) = editPlaylist(playlistId) { p ->
        p.copy(entries = p.entries.map { if (it.id == entryId) it.copy(media = it.media.copy(title = title)) else it })
    }

    /** Copies entries to another playlist (new ids, appended at the end). Returns how many. */
    fun copyEntries(fromId: String, entryIds: Set<String>, toId: String): Int {
        val source = playlist(fromId)?.entries?.filter { it.id in entryIds }.orEmpty()
        if (source.isEmpty() || playlist(toId) == null) return 0
        val now = clock()
        val copies = source.map { it.copy(id = UUID.randomUUID().toString(), addedAt = now) }
        editPlaylist(toId) { p -> p.copy(entries = p.entries + copies) }
        return copies.size
    }

    /** Moves entries to another playlist (they keep their ids). Returns how many. */
    fun moveEntries(fromId: String, entryIds: Set<String>, toId: String): Int {
        if (fromId == toId || playlist(toId) == null) return 0
        val moved = removeEntries(fromId, entryIds).map { it.value }
        if (moved.isNotEmpty()) editPlaylist(toId) { p -> p.copy(entries = p.entries + moved) }
        return moved.size
    }

    fun clearPlaylist(id: String) = editPlaylist(id) { it.copy(entries = emptyList()) }

    fun setAutoRemovePlayed(id: String, enabled: Boolean) = editPlaylist(id) { it.copy(autoRemovePlayed = enabled) }

    fun setIntroOutro(id: String, skipIntroMs: Long, skipIntroAfterMs: Long, skipOutroMs: Long) = editPlaylist(id) {
        it.copy(
            skipIntroMs = skipIntroMs.coerceAtLeast(0L),
            skipIntroAfterMs = skipIntroAfterMs.coerceAtLeast(0L),
            skipOutroMs = skipOutroMs.coerceAtLeast(0L)
        )
    }

    /**
     * Rewrites the item order (so it sticks) and remembers the
     * choice. Shuffle is a one-time permanent shuffle.
     */
    fun sortPlaylist(id: String, sort: PlaylistSort, descending: Boolean, random: Random = Random.Default) = editPlaylist(id) { p ->
        val sorted = when (sort) {
            PlaylistSort.NAME -> p.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.media.title })
            PlaylistSort.DATE_ADDED -> p.entries.sortedBy { it.addedAt }
            PlaylistSort.SHUFFLE -> p.entries.shuffled(random)
        }
        p.copy(entries = if (descending && sort != PlaylistSort.SHUFFLE) sorted.reversed() else sorted, sort = sort, sortDescending = descending)
    }

    private fun editPlaylist(id: String, transform: (Playlist) -> Playlist) = edit { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) transform(it).copy(updatedAt = clock()) else it })
    }

    // ── IPTV ────────────────────────────────────────────────────────────────

    fun addIptvSource(name: String, location: String, userAgent: String? = null, skipContentCheck: Boolean = false): IptvSource {
        val source = IptvSource(
            UUID.randomUUID().toString(), name.ifBlank { hostOf(location) ?: "IPTV" }, location, userAgent, clock(),
            skipContentCheck = skipContentCheck
        )
        edit { it.copy(iptvSources = it.iptvSources + source) }
        return source
    }

    fun updateIptvSource(source: IptvSource) = edit { d ->
        d.copy(iptvSources = d.iptvSources.map { if (it.id == source.id) source else it })
    }

    fun removeIptvSource(id: String) = edit { d ->
        d.copy(
            iptvSources = d.iptvSources.filterNot { it.id == id },
            favoriteChannels = d.favoriteChannels.filterNot { it.sourceId == id }
        )
    }

    fun toggleFavoriteChannel(channel: FavoriteChannel): Boolean {
        var added = false
        edit { d ->
            val exists = d.favoriteChannels.any { it.url == channel.url && it.sourceId == channel.sourceId }
            added = !exists
            d.copy(
                favoriteChannels = if (exists) d.favoriteChannels.filterNot { it.url == channel.url && it.sourceId == channel.sourceId }
                else d.favoriteChannels + channel
            )
        }
        return added
    }

    // ── Downloads ───────────────────────────────────────────────────────────

    fun addDownload(item: DownloadItem) = edit { d ->
        d.copy(downloads = listOf(item.copy(addedAt = clock(), updatedAt = clock())) + d.downloads.filterNot { it.id == item.id })
    }

    fun updateDownload(id: String, transform: (DownloadItem) -> DownloadItem) = edit { d ->
        d.copy(downloads = d.downloads.map { if (it.id == id) transform(it).copy(updatedAt = clock()) else it })
    }

    fun removeDownload(id: String) = edit { it.copy(downloads = it.downloads.filterNot { d -> d.id == id }) }

    fun download(id: String): DownloadItem? = _data.value.downloads.firstOrNull { it.id == id }

    /** Oldest queued download, next in line. */
    fun nextQueuedDownload(): DownloadItem? =
        _data.value.downloads.filter { it.status == DownloadStatus.QUEUED }.minByOrNull { it.addedAt }

    // ── Backup ──────────────────────────────────────────────────────────────

    fun exportJson(): String = json.encodeToString(LibraryData.serializer(), _data.value)

    /** Replaces everything with a backup; returns false when the text is not a backup. */
    fun importJson(text: String): Boolean {
        val imported = runCatching { json.decodeFromString(LibraryData.serializer(), text) }.getOrNull() ?: return false
        edit { imported.migrated() }
        return true
    }

    /** Replaces everything (restore). */
    fun replace(data: LibraryData) = edit { data.migrated() }

    /** Atomic change of several lists at once (restore, erase). */
    fun modify(transform: (LibraryData) -> LibraryData) = edit { transform(it).migrated() }

    /** Writes pending changes now (e.g. before the process may die). */
    fun flush() {
        pendingSave?.cancel()
        write(_data.value)
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun edit(transform: (LibraryData) -> LibraryData) {
        _data.update(transform)
        scheduleSave()
    }

    @Synchronized
    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(saveDelayMs)
            write(_data.value)
        }
    }

    @Synchronized
    private fun write(data: LibraryData) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(LibraryData.serializer(), data))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): LibraryData =
        runCatching { json.decodeFromString(LibraryData.serializer(), file.readText()) }.getOrNull()
            ?.also { d -> loadedLegacy = d.playlists.any { it.legacyItems.isNotEmpty() } }
            ?.migrated() ?: LibraryData()

    /** Old playlists stored plain media without ids; give every item an entry id. */
    private fun LibraryData.migrated(): LibraryData {
        if (playlists.none { it.legacyItems.isNotEmpty() }) return this
        return copy(playlists = playlists.map { p ->
            if (p.legacyItems.isEmpty()) p
            else p.copy(
                entries = p.entries + p.legacyItems.map { PlaylistEntry(UUID.randomUUID().toString(), it, it.addedAt) },
                legacyItems = emptyList()
            )
        })
    }

    companion object {
        const val DEFAULT_QUEUE_ID = "queue"
        private const val MAX_HISTORY = 2000
        private const val MAX_SITES = 500
        private const val MAX_RECENT = 200
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Most visited first, then the latest visit. */
        private val siteOrder: Comparator<SiteVisits> = compareBy<SiteVisits> { it.count }.thenBy { it.lastVisitedAt }

        fun hostOf(url: String): String? =
            runCatching { URI(url.trim()).host?.removePrefix("www.")?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

        /**
         * Most visited key of a page: lower-case, trimmed, without one
         * trailing "/". "www." is dropped too, so an old per-host entry
         * ("https://host") and the site's root page share one key.
         */
        fun pageKey(url: String): String =
            url.trim().lowercase().removeSuffix("/").replaceFirst("://www.", "://")

        /** "https://host/favicon.ico" of a web page, or null for other URLs. */
        fun faviconUrl(pageUrl: String): String? = runCatching {
            val uri = URI(pageUrl.trim())
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) null
            else "$scheme://${uri.rawAuthority}/favicon.ico"
        }.getOrNull()
    }
}
