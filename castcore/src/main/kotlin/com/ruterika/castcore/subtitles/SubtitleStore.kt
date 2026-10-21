package com.ruterika.castcore.subtitles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A subtitle file kept on the phone. */
@Serializable
data class DownloadedSubtitle(
    /** Content hash; also the file name on disk (without extension). */
    val id: String,
    /** Name of the file on disk inside the store directory. */
    val storedName: String,
    /** Original file name (shown in the list). */
    val fileName: String,
    val language: String? = null,
    /** Where it came from: a URL, "OpenSubtitles.com" or a picked file's name. */
    val source: String? = null,
    /** What was playing when it was downloaded. */
    val mediaTitle: String? = null,
    /** Epoch millis of the last download. */
    val savedAt: Long = 0L,
)

/** One OpenSubtitles search, for "Remember searches" and the name autocomplete. */
@Serializable
data class SubtitleSearch(
    val query: String,
    val languages: List<String> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String = "",
    /** "Episodes of a show" instead of "Movie". */
    val episodes: Boolean = false,
    val savedAt: Long = 0L,
) {
    /** Two searches are the same entry when everything but the time matches. */
    internal fun sameAs(other: SubtitleSearch) =
        query.equals(other.query, ignoreCase = true) && languages.sorted() == other.languages.sorted() &&
            season == other.season && episode == other.episode && imdbId == other.imdbId && episodes == other.episodes
}

@Serializable
data class SubtitleStoreData(
    /** Newest first. */
    val downloads: List<DownloadedSubtitle> = emptyList(),
    /** Newest first. */
    val searches: List<SubtitleSearch> = emptyList(),
    /** Cached `GET /infos/languages`. */
    val languages: List<OpenSubtitlesLanguage> = emptyList(),
    val languagesFetchedAt: Long = 0L,
)

/**
 * Downloaded subtitles on disk plus the OpenSubtitles search history and language list.
 * Files live in [dir]; the index is `index.json` next to them.
 */
class SubtitleStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    private val indexFile = File(dir, "index.json")
    private val _data = MutableStateFlow(load())
    val data: StateFlow<SubtitleStoreData> = _data.asStateFlow()

    /** The file of [entry] (may be missing if it was deleted outside the store). */
    fun file(entry: DownloadedSubtitle): File = File(dir, entry.storedName)

    /**
     * Keeps [bytes] (already unzipped) and returns its entry. Saving the same content again only
     * moves it to the top of the list.
     */
    @Synchronized
    fun save(bytes: ByteArray, fileName: String?, language: String?, source: String?, mediaTitle: String?): DownloadedSubtitle {
        val id = sha1(bytes).take(20)
        val now = clock()
        val current = _data.value
        val existing = current.downloads.firstOrNull { it.id == id }
        val entry = if (existing != null && file(existing).isFile) {
            existing.copy(
                savedAt = now,
                language = language ?: existing.language,
                mediaTitle = mediaTitle ?: existing.mediaTitle
            )
        } else {
            val ext = fileName?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "srt"
            val stored = "$id.$ext"
            dir.mkdirs()
            File(dir, stored).writeBytes(bytes)
            DownloadedSubtitle(
                id = id,
                storedName = stored,
                fileName = fileName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: stored,
                language = language,
                source = source,
                mediaTitle = mediaTitle,
                savedAt = now
            )
        }
        var downloads = listOf(entry) + current.downloads.filterNot { it.id == id }
        if (downloads.size > MAX_DOWNLOADS) {
            downloads.drop(MAX_DOWNLOADS).forEach { file(it).delete() }
            downloads = downloads.take(MAX_DOWNLOADS)
        }
        update(current.copy(downloads = downloads))
        return entry
    }

    @Synchronized
    fun delete(id: String) {
        val current = _data.value
        current.downloads.filter { it.id == id }.forEach { file(it).delete() }
        update(current.copy(downloads = current.downloads.filterNot { it.id == id }))
    }

    /** "Delete all": removes every stored file. */
    @Synchronized
    fun deleteAll() {
        dir.listFiles()?.filter { it.isFile && it != indexFile }?.forEach { it.delete() }
        update(_data.value.copy(downloads = emptyList()))
    }

    /** Remembers [search] (newest first, duplicates merged). Blank queries are ignored. */
    @Synchronized
    fun addSearch(search: SubtitleSearch) {
        if (search.query.isBlank() && search.imdbId.isBlank()) return
        val entry = search.copy(query = search.query.trim(), imdbId = search.imdbId.trim(), savedAt = clock())
        val current = _data.value
        update(current.copy(searches = (listOf(entry) + current.searches.filterNot { it.sameAs(entry) }).take(MAX_SEARCHES)))
    }

    @Synchronized
    fun clearSearches() = update(_data.value.copy(searches = emptyList()))

    /** Saved searches whose name contains [text] (all of them for blank text), newest first. */
    fun suggestions(text: String, limit: Int = 6): List<SubtitleSearch> {
        val needle = text.trim()
        return _data.value.searches
            .filter { needle.isEmpty() || it.query.contains(needle, ignoreCase = true) }
            .take(limit)
    }

    @Synchronized
    fun setLanguages(languages: List<OpenSubtitlesLanguage>) {
        if (languages.isEmpty()) return
        update(_data.value.copy(languages = languages, languagesFetchedAt = clock()))
    }

    /** True when the cached language list is missing or older than [maxAgeMs]. */
    fun languagesStale(maxAgeMs: Long = LANGUAGES_MAX_AGE_MS): Boolean =
        _data.value.languages.isEmpty() || clock() - _data.value.languagesFetchedAt > maxAgeMs

    private fun update(data: SubtitleStoreData) {
        _data.value = data
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(json.encodeToString(SubtitleStoreData.serializer(), data))
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
        }
    }

    private fun load(): SubtitleStoreData {
        val loaded = runCatching { json.decodeFromString(SubtitleStoreData.serializer(), indexFile.readText()) }.getOrNull()
            ?: return SubtitleStoreData()
        // Drop entries whose file is gone.
        return loaded.copy(downloads = loaded.downloads.filter { File(dir, it.storedName).isFile })
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_DOWNLOADS = 100
        const val MAX_SEARCHES = 30
        const val LANGUAGES_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
