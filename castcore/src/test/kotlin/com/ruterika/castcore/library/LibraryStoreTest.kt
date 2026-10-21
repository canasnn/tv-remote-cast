package com.ruterika.castcore.library

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun store() = LibraryStore(tmp.root.resolve("library.json"), Dispatchers.Unconfined, { now }, saveDelayMs = 0)

    @Test
    fun bookmarksToggleAndPersist() {
        val file = tmp.root.resolve("library.json")
        val first = LibraryStore(file, Dispatchers.Unconfined, { now }, 0)
        assertTrue(first.toggleBookmark("https://a.com", "A"))
        first.flush()
        val second = LibraryStore(file, Dispatchers.Unconfined, { now }, 0)
        assertTrue(second.isBookmarked("https://a.com"))
        assertFalse(second.toggleBookmark("https://a.com", "A"))
        assertFalse(second.isBookmarked("https://a.com"))
    }

    @Test
    fun visitsBuildHistoryAndMostVisited() {
        val s = store()
        s.recordVisit("https://www.site.com/a", "A"); now++
        s.recordVisit("https://www.site.com/a", "A again"); now++ // reload: same entry
        s.recordVisit("https://site.com/b", "B"); now++
        s.recordVisit("https://other.org/", "O"); now++
        s.recordVisit("https://x.org/", "X", incognito = true)
        val d = s.data.value
        assertEquals(3, d.history.size)
        assertEquals("A again", d.history.last().title)
        val top = s.mostVisited()
        assertEquals("site.com", top.first().host)
        assertEquals(2, top.first().count)
        assertEquals(2, top.size)
    }

    @Test
    fun clearHistorySince() {
        val s = store()
        s.recordVisit("https://a.com", "A"); now = 5_000
        s.recordVisit("https://b.com", "B")
        s.clearHistory(sinceMs = 4_000)
        assertEquals(2, s.data.value.sites.size)
        assertEquals(listOf("https://a.com"), s.data.value.history.map { it.url })
    }

    @Test
    fun recentMediaDedupesAndTracksPosition() {
        val s = store()
        s.addRecent(SavedMedia("u1", "One"))
        s.addRecent(SavedMedia("u2", "Two"))
        s.addRecent(SavedMedia("u1", "One"))
        assertEquals(listOf("u1", "u2"), s.data.value.recentMedia.map { it.url })
        s.updateRecentPosition("u2", 5000, 60000)
        assertEquals(5000, s.data.value.recentMedia.first { it.url == "u2" }.positionMs)
    }

    @Test
    fun playlistsAddMoveRemove() {
        val s = store()
        val queue = s.addToPlaylist(SavedMedia("a", "A"))
        s.addToPlaylist(SavedMedia("b", "B"))
        s.addToPlaylist(SavedMedia("c", "C"))
        assertEquals(LibraryStore.DEFAULT_QUEUE_ID, queue.id)
        s.movePlaylistItem(queue.id, 2, 0)
        s.removeFromPlaylist(queue.id, 1)
        assertEquals(listOf("c", "b"), s.data.value.playlists.first().items.map { it.url })
        val custom = s.createPlaylist("Films")
        s.addToPlaylist(SavedMedia("x", "X"), custom.id)
        assertEquals(1, s.data.value.playlists.first { it.id == custom.id }.items.size)
    }

    @Test
    fun oldPlaylistFormatStillLoads() {
        val file = tmp.root.resolve("library.json")
        file.writeText(
            """{"playlists":[{"id":"queue","name":"Queue","items":[{"url":"a","title":"A","addedAt":5},{"url":"b","title":"B","addedAt":7}],"createdAt":1}],"bookmarks":[{"url":"https://x.com","title":"X","addedAt":2}]}"""
        )
        val s = LibraryStore(file, Dispatchers.Unconfined, { now }, 0)
        val p = s.data.value.playlists.single()
        assertEquals(listOf("a", "b"), p.items.map { it.url })
        assertEquals(listOf(5L, 7L), p.entries.map { it.addedAt })
        assertEquals(2, p.entries.map { it.id }.toSet().size)
        assertTrue(p.legacyItems.isEmpty())
        assertFalse(p.autoRemovePlayed)
        assertEquals(0L, p.skipIntroMs)
        assertTrue(s.isBookmarked("https://x.com"))
        // Saved in the new format right away, so the ids stay stable.
        val again = LibraryStore(file, Dispatchers.Unconfined, { now }, 0)
        assertEquals(p.entries.map { it.id }, again.data.value.playlists.single().entries.map { it.id })
    }

    @Test
    fun playlistKeepsDuplicatesAndRemovesWithUndo() {
        val s = store()
        val p = s.createPlaylist("Films")
        assertTrue(s.addToPlaylist(listOf(SavedMedia("a", "A"), SavedMedia("b", "B"), SavedMedia("a", "A")), p.id))
        val entries = s.playlist(p.id)!!.entries
        assertEquals(listOf("a", "b", "a"), entries.map { it.media.url })
        val removed = s.removeEntries(p.id, setOf(entries[0].id, entries[2].id))
        assertEquals(listOf("b"), s.playlist(p.id)!!.items.map { it.url })
        s.restoreEntries(p.id, removed)
        assertEquals(entries.map { it.id }, s.playlist(p.id)!!.entries.map { it.id })
        assertFalse(s.addToPlaylist(listOf(SavedMedia("z", "Z")), "missing"))
    }

    @Test
    fun copyMoveRenameAndDrag() {
        val s = store()
        val a = s.createPlaylist("A")
        val b = s.createPlaylist("B")
        s.addToPlaylist(listOf(SavedMedia("1", "One"), SavedMedia("2", "Two"), SavedMedia("3", "Three")), a.id)
        val ids = s.playlist(a.id)!!.entries.map { it.id }
        assertEquals(1, s.copyEntries(a.id, setOf(ids[0]), b.id))
        assertEquals(3, s.playlist(a.id)!!.entries.size)
        assertTrue(s.playlist(b.id)!!.entries.single().id != ids[0])
        assertEquals(1, s.moveEntries(a.id, setOf(ids[1]), b.id))
        assertEquals(listOf("1", "3"), s.playlist(a.id)!!.items.map { it.url })
        assertEquals(listOf("1", "2"), s.playlist(b.id)!!.items.map { it.url })
        s.renameEntry(a.id, ids[2], "Third")
        assertEquals("Third", s.playlist(a.id)!!.items.last().title)
        assertTrue(s.moveEntry(a.id, ids[2], -1))
        assertFalse(s.moveEntry(a.id, ids[2], -1))
        assertEquals(listOf("3", "1"), s.playlist(a.id)!!.items.map { it.url })
    }

    @Test
    fun sortIsPersistedAndIntroOutroStored() {
        val s = store()
        val p = s.createPlaylist("P")
        s.addToPlaylist(listOf(SavedMedia("b", "beta")), p.id); now++
        s.addToPlaylist(listOf(SavedMedia("a", "Alpha")), p.id); now++
        s.addToPlaylist(listOf(SavedMedia("c", "Gamma")), p.id)
        s.sortPlaylist(p.id, PlaylistSort.NAME, descending = false)
        assertEquals(listOf("Alpha", "beta", "Gamma"), s.playlist(p.id)!!.items.map { it.title })
        s.sortPlaylist(p.id, PlaylistSort.DATE_ADDED, descending = true)
        assertEquals(listOf("c", "a", "b"), s.playlist(p.id)!!.items.map { it.url })
        assertEquals(PlaylistSort.DATE_ADDED, s.playlist(p.id)!!.sort)
        assertTrue(s.playlist(p.id)!!.sortDescending)
        s.sortPlaylist(p.id, PlaylistSort.SHUFFLE, descending = false, random = kotlin.random.Random(1))
        assertEquals(setOf("a", "b", "c"), s.playlist(p.id)!!.items.map { it.url }.toSet())
        s.setIntroOutro(p.id, 90_000, 5_000, -1)
        s.setAutoRemovePlayed(p.id, true)
        val after = s.playlist(p.id)!!
        assertEquals(90_000L, after.skipIntroMs)
        assertEquals(5_000L, after.skipIntroAfterMs)
        assertEquals(0L, after.skipOutroMs)
        assertTrue(after.autoRemovePlayed)
        // Round trip through the file.
        s.flush()
        val reloaded = LibraryStore(tmp.root.resolve("library.json"), Dispatchers.Unconfined, { now }, 0).playlist(p.id)!!
        assertEquals(after, reloaded)
    }

    @Test
    fun iptvSourcesAndFavorites() {
        val s = store()
        val src = s.addIptvSource("", "https://example.com/list.m3u")
        assertEquals("example.com", src.name)
        val fav = FavoriteChannel(src.id, "http://ch/1", "TRT 1")
        assertTrue(s.toggleFavoriteChannel(fav))
        s.removeIptvSource(src.id)
        assertTrue(s.data.value.favoriteChannels.isEmpty())
    }

    @Test
    fun backupRoundTrip() {
        val s = store()
        s.toggleBookmark("https://a.com", "A")
        val backup = s.exportJson()
        val other = LibraryStore(tmp.root.resolve("other.json"), Dispatchers.Unconfined, { now }, 0)
        assertTrue(other.importJson(backup))
        assertTrue(other.isBookmarked("https://a.com"))
        assertFalse(other.importJson("not json"))
    }

    @Test
    fun mostVisitedCountsPagesAndKeepsOldHostEntries() {
        // Old file: one entry per host.
        val file = tmp.root.resolve("library.json")
        file.writeText("""{"sites":[{"host":"site.com","url":"https://site.com","title":"Site","count":5,"lastVisitedAt":1}]}""")
        val s = LibraryStore(file, Dispatchers.Unconfined, { now }, 0)
        s.recordVisit("https://www.site.com/", "Home"); now++
        s.recordVisit("https://site.com/page", "Page"); now++
        s.recordVisit("https://other.org/x", "X"); now++
        s.recordVisit("https://SITE.com/page/", "Page")
        val pages = s.mostVisitedPages()
        assertEquals(3, pages.size)
        assertEquals(6, pages.first().count)
        assertEquals("https://site.com", pages.first().url)
        assertEquals(2, pages.first { it.url == "https://site.com/page" }.count)
        // The start page grid still sums up per host.
        val hosts = s.mostVisited()
        assertEquals(listOf("site.com", "other.org"), hosts.map { it.host })
        assertEquals(8, hosts.first().count)
        assertEquals("https://site.com", hosts.first().url)
        s.removeSitePage("https://site.com/page/")
        assertEquals(listOf("https://site.com", "https://other.org/x"), s.mostVisitedPages().map { it.url })
        s.removeSite("site.com")
        assertEquals(listOf("other.org"), s.mostVisited().map { it.host })
    }

    @Test
    fun historyMultiDeleteAndAutoDelete() {
        val day = 24 * 60 * 60 * 1000L
        now = 100 * day
        val s = store()
        s.recordVisit("https://a.com", "A"); now += day
        s.recordVisit("https://b.com", "B"); now += 10 * day
        s.recordVisit("https://c.com", "C")
        s.deleteHistoryOlderThan(0)
        assertEquals(3, s.data.value.history.size)
        s.deleteHistoryOlderThan(10)
        assertEquals(listOf("https://c.com", "https://b.com"), s.data.value.history.map { it.url })
        s.removeHistoryEntries(s.data.value.history.filter { it.url == "https://b.com" })
        assertEquals(listOf("https://c.com"), s.data.value.history.map { it.url })
    }

    @Test
    fun bookmarksGetFaviconsAndMultiDelete() {
        val s = store()
        s.toggleBookmark("https://www.a.com/page?q=1", "A")
        s.toggleBookmark("http://b.org:8080/", "B", iconUrl = "https://cdn.b.org/icon.png")
        s.toggleBookmark("about:blank", "Blank")
        val byUrl = s.data.value.bookmarks.associateBy { it.url }
        assertEquals("https://www.a.com/favicon.ico", byUrl.getValue("https://www.a.com/page?q=1").iconUrl)
        assertEquals("https://cdn.b.org/icon.png", byUrl.getValue("http://b.org:8080/").iconUrl)
        assertEquals(null, byUrl.getValue("about:blank").iconUrl)
        assertEquals("http://b.org:8080/favicon.ico", LibraryStore.faviconUrl("http://b.org:8080/x"))
        s.removeBookmarks(setOf("https://www.a.com/page?q=1", "about:blank"))
        assertEquals(listOf("http://b.org:8080/"), s.data.value.bookmarks.map { it.url })
    }

    @Test
    fun recentMediaRename() {
        val s = store()
        s.addRecent(SavedMedia("u1", "One"))
        s.renameRecent("u1", "Renamed")
        assertEquals("Renamed", s.data.value.recentMedia.single().title)
    }
}
