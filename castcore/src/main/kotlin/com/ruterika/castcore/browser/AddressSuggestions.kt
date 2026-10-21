package com.ruterika.castcore.browser

import com.ruterika.castcore.library.LibraryData
import com.ruterika.castcore.settings.CastSettings
import com.ruterika.castcore.settings.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** Address bar suggestions: bookmarks and history first, then the search engine's. */
object AddressSuggestions {

    enum class Kind { BOOKMARK, HISTORY, SEARCH }

    /** [url] is null for a search suggestion (the text is searched). */
    data class Suggestion(val text: String, val url: String?, val kind: Kind)

    /** Bookmarks and history whose title or URL matches [query]; prefix matches first. */
    fun local(query: String, data: LibraryData, limit: Int = 5): List<Suggestion> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        fun score(url: String, title: String): Int? {
            val bare = bareUrl(url).lowercase()
            val t = title.lowercase()
            return when {
                bare.startsWith(q) -> 0
                t.startsWith(q) || t.split(' ').any { it.startsWith(q) } -> 1
                q in bare || q in t -> 2
                else -> null
            }
        }
        val bookmarks = data.bookmarks.mapNotNull { b ->
            score(b.url, b.title)?.let { Triple(it, 0, Suggestion(b.title.ifBlank { bareUrl(b.url) }, b.url, Kind.BOOKMARK)) }
        }
        val history = data.history.distinctBy { it.url }.mapNotNull { h ->
            score(h.url, h.title)?.let { Triple(it, 1, Suggestion(h.title.ifBlank { bareUrl(h.url) }, h.url, Kind.HISTORY)) }
        }
        return (bookmarks + history)
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
            .distinctBy { it.url }
            .take(limit)
    }

    /** OpenSearch suggestion endpoint for the selected engine (Google for the rest). */
    fun remoteUrl(s: CastSettings, query: String): String {
        val q = URLEncoder.encode(query, "UTF-8")
        return when (s.searchEngine) {
            SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/ac/?q=$q&type=list"
            SearchEngine.BING -> "https://api.bing.com/osjson.aspx?query=$q"
            SearchEngine.YANDEX -> "https://suggest.yandex.com/suggest-ff.cgi?part=$q"
            SearchEngine.BRAVE -> "https://search.brave.com/api/suggest?q=$q"
            SearchEngine.ECOSIA -> "https://ac.ecosia.org/autocomplete?q=$q&type=list"
            else -> "https://suggestqueries.google.com/complete/search?client=chrome&q=$q"
        }
    }

    /** Parses `["query", ["s1", "s2", …], …]` (OpenSearch suggestions). */
    fun parseOpenSearch(body: String): List<String> = runCatching {
        val root = Json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        val list = root.getOrNull(1) as? JsonArray ?: return emptyList()
        list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    /** Fetches search suggestions (no cookies are sent). Empty on any error. */
    suspend fun fetchRemote(http: OkHttpClient, s: CastSettings, query: String, limit: Int = 5): List<Suggestion> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            runCatching {
                http.newCall(Request.Builder().url(remoteUrl(s, query)).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    parseOpenSearch(response.body.string()).distinct()
                        .filterNot { it.equals(query, ignoreCase = true) }
                        .take(limit)
                        .map { Suggestion(it, null, Kind.SEARCH) }
                }
            }.getOrDefault(emptyList())
        }

    fun bareUrl(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
}
