package com.ruterika.castcore.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryRestoreTest {

    private fun media(url: String) = SavedMedia(url = url, title = url)

    private val current = LibraryData(
        bookmarks = listOf(Bookmark("https://a", "A", 1)),
        recentMedia = listOf(media("https://v/1.mp4")),
        playlists = listOf(Playlist(id = "p", name = "Mine", entries = listOf(PlaylistEntry("e1", media("https://v/1.mp4"))))),
        iptvSources = listOf(IptvSource("s1", "Here", "https://tv/list.m3u")),
        sites = listOf(SiteVisits("a", "https://a", "A", count = 3, lastVisitedAt = 10))
    )
    private val backup = LibraryData(
        bookmarks = listOf(Bookmark("https://a", "A old", 0), Bookmark("https://b", "B", 2)),
        recentMedia = listOf(media("https://v/2.mp4")),
        playlists = listOf(
            Playlist(id = "p", name = "Theirs", entries = listOf(PlaylistEntry("x1", media("https://v/1.mp4")), PlaylistEntry("x2", media("https://v/3.mp4")))),
            Playlist(id = "q", name = "Other")
        ),
        iptvSources = listOf(IptvSource("s2", "Same list", "https://tv/list.m3u"), IptvSource("s3", "New", "https://tv/2.m3u")),
        sites = listOf(SiteVisits("a", "https://a", "A", count = 7, lastVisitedAt = 5))
    )

    @Test
    fun mergeAddsOnlyWhatIsMissing() {
        val merged = LibraryRestore.restore(current, backup, LibraryPart.entries.toSet(), replace = false)
        assertEquals(listOf("https://a", "https://b"), merged.bookmarks.map { it.url })
        assertEquals("A", merged.bookmarks.first().title)
        assertEquals(listOf("https://v/1.mp4", "https://v/2.mp4"), merged.recentMedia.map { it.url })
        assertEquals(listOf("p", "q"), merged.playlists.map { it.id })
        assertEquals(listOf("e1", "x2"), merged.playlists.first().entries.map { it.id })
        assertEquals(listOf("s1", "s3"), merged.iptvSources.map { it.id })
        assertEquals(7, merged.sites.single().count)
        assertEquals(10L, merged.sites.single().lastVisitedAt)
    }

    @Test
    fun replaceSwapsOnlyTheChosenLists() {
        val replaced = LibraryRestore.restore(current, backup, setOf(LibraryPart.BOOKMARKS, LibraryPart.PLAYLISTS), replace = true)
        assertEquals(backup.bookmarks, replaced.bookmarks)
        assertEquals(backup.playlists, replaced.playlists)
        assertEquals(current.recentMedia, replaced.recentMedia)
        assertEquals(current.iptvSources, replaced.iptvSources)
    }
}
