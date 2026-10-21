package com.ruterika.castcore.library

/** The library lists a backup can restore. */
enum class LibraryPart { BOOKMARKS, HISTORY, RECENT, PLAYLISTS, IPTV, DOWNLOADS }

/**
 * Puts the lists of a backup into the current library. "Replace" swaps each chosen
 * list for the backup's; "Merge" keeps what is here and adds what is missing
 * (matched by URL or id), so restoring twice never creates duplicates.
 */
object LibraryRestore {

    fun restore(current: LibraryData, backup: LibraryData, parts: Set<LibraryPart>, replace: Boolean): LibraryData {
        fun <T> pick(part: LibraryPart, mine: List<T>, theirs: List<T>, merge: (List<T>, List<T>) -> List<T>): List<T> = when {
            part !in parts -> mine
            replace -> theirs
            else -> merge(mine, theirs)
        }
        return current.copy(
            bookmarks = pick(LibraryPart.BOOKMARKS, current.bookmarks, backup.bookmarks) { a, b -> (a + b).distinctBy { it.url } },
            history = pick(LibraryPart.HISTORY, current.history, backup.history) { a, b ->
                (a + b).distinctBy { it.url to it.visitedAt }.sortedByDescending { it.visitedAt }
            },
            sites = pick(LibraryPart.HISTORY, current.sites, backup.sites, ::mergeSites),
            recentMedia = pick(LibraryPart.RECENT, current.recentMedia, backup.recentMedia) { a, b -> (a + b).distinctBy { it.url } },
            playlists = pick(LibraryPart.PLAYLISTS, current.playlists, backup.playlists, ::mergePlaylists),
            iptvSources = pick(LibraryPart.IPTV, current.iptvSources, backup.iptvSources) { a, b ->
                a + b.filter { s -> a.none { it.id == s.id || it.location == s.location } }
            },
            favoriteChannels = pick(LibraryPart.IPTV, current.favoriteChannels, backup.favoriteChannels) { a, b ->
                (a + b).distinctBy { it.sourceId to it.url }
            },
            downloads = pick(LibraryPart.DOWNLOADS, current.downloads, backup.downloads) { a, b -> (a + b).distinctBy { it.id } }
        )
    }

    /** Same page in both: the higher visit count and the latest visit win. */
    private fun mergeSites(mine: List<SiteVisits>, theirs: List<SiteVisits>): List<SiteVisits> {
        val byPage = LinkedHashMap<String, SiteVisits>()
        (mine + theirs).forEach { site ->
            val key = LibraryStore.pageKey(site.url)
            val known = byPage[key]
            byPage[key] = if (known == null) site else known.copy(
                count = maxOf(known.count, site.count),
                lastVisitedAt = maxOf(known.lastVisitedAt, site.lastVisitedAt)
            )
        }
        return byPage.values.toList()
    }

    /** Same playlist id in both: entries missing here (by media URL) are appended. */
    private fun mergePlaylists(mine: List<Playlist>, theirs: List<Playlist>): List<Playlist> {
        val merged = mine.map { playlist ->
            val other = theirs.firstOrNull { it.id == playlist.id } ?: return@map playlist
            val urls = playlist.entries.map { it.media.url }.toSet()
            val ids = playlist.entries.map { it.id }.toSet()
            playlist.copy(entries = playlist.entries + other.entries.filter { it.media.url !in urls && it.id !in ids })
        }
        return merged + theirs.filter { p -> mine.none { it.id == p.id } }
    }
}
