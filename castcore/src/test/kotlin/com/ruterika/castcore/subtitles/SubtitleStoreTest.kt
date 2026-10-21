package com.ruterika.castcore.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubtitleStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun store() = SubtitleStore(tmp.root, clock = { now })

    @Test
    fun savesNewestFirstAndKeepsFiles() {
        val store = store()
        val a = store.save("1\n00:00:01,000 --> 00:00:02,000\nA\n".toByteArray(), "a.srt", "en", "https://x/a.srt", "Movie")
        now = 2_000L
        val b = store.save("WEBVTT\n\n00:01.000 --> 00:02.000\nB\n".toByteArray(), "b.vtt", "tr", "OpenSubtitles.com", null)
        assertEquals(listOf(b.id, a.id), store.data.value.downloads.map { it.id })
        assertTrue(store.file(a).isFile)
        assertTrue(store.file(b).name.endsWith(".vtt"))
        assertEquals("a.srt", a.fileName)

        // Reloaded from disk.
        val again = store()
        assertEquals(listOf(b.id, a.id), again.data.value.downloads.map { it.id })
    }

    @Test
    fun sameContentMovesToTop() {
        val store = store()
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nA\n".toByteArray()
        val first = store.save(bytes, "a.srt", null, null, null)
        now = 2_000L
        store.save("other".toByteArray(), "o.srt", null, null, null)
        now = 3_000L
        val second = store.save(bytes, "renamed.srt", "en", null, null)
        assertEquals(first.id, second.id)
        assertEquals(2, store.data.value.downloads.size)
        assertEquals(first.id, store.data.value.downloads.first().id)
        assertEquals(3_000L, store.data.value.downloads.first().savedAt)
        assertEquals("en", store.data.value.downloads.first().language)
    }

    @Test
    fun deleteAllRemovesFiles() {
        val store = store()
        val a = store.save("x".toByteArray(), "a.srt", null, null, null)
        store.addSearch(SubtitleSearch("Dune"))
        store.deleteAll()
        assertTrue(store.data.value.downloads.isEmpty())
        assertFalse(store.file(a).exists())
        // Search history is separate.
        assertEquals(1, store.data.value.searches.size)
    }

    @Test
    fun searchesAreDedupedCappedAndSuggested() {
        val store = store()
        store.addSearch(SubtitleSearch("Breaking Bad", listOf("en"), 1, 2, episodes = true))
        store.addSearch(SubtitleSearch("Dune", listOf("tr")))
        store.addSearch(SubtitleSearch("breaking bad", listOf("en"), 1, 2, episodes = true))
        store.addSearch(SubtitleSearch("  "))
        assertEquals(listOf("breaking bad", "Dune"), store.data.value.searches.map { it.query })
        assertEquals(listOf("breaking bad"), store.suggestions("BREAK").map { it.query })
        assertEquals(2, store.suggestions("").size)

        repeat(SubtitleStore.MAX_SEARCHES + 5) { store.addSearch(SubtitleSearch("q$it")) }
        assertEquals(SubtitleStore.MAX_SEARCHES, store.data.value.searches.size)
        store.clearSearches()
        assertTrue(store.data.value.searches.isEmpty())
    }

    @Test
    fun languageCacheExpires() {
        val store = store()
        assertTrue(store.languagesStale())
        store.setLanguages(listOf(OpenSubtitlesLanguage("en", "English")))
        assertFalse(store.languagesStale())
        now += SubtitleStore.LANGUAGES_MAX_AGE_MS + 1
        assertTrue(store.languagesStale())
    }
}
