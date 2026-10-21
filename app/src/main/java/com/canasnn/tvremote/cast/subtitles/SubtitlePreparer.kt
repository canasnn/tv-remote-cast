package com.canasnn.tvremote.cast.subtitles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.canasnn.tvremote.App
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.library.SavedSubtitle
import com.ruterika.castcore.subtitles.SubtitleArchive
import com.ruterika.castcore.subtitles.SubtitleConverter
import com.ruterika.castcore.subtitles.SubtitleNaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Makes any subtitle playable on the TV: downloads it (with the video's
 * headers), unzips it, fixes the character encoding, converts exotic formats
 * and serves the UTF-8 result from the phone's local server. Timing is shifted
 * live by the player (see SetSubtitleOffset), so [prepare] is normally called with 0.
 */
object SubtitlePreparer {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Raw bytes per original subtitle URL, so offsets can be re-applied without re-downloading. */
    private val rawCache = object : LinkedHashMap<String, Pair<ByteArray, String?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ByteArray, String?>>?) = size > 32
    }

    /** Forgets downloaded subtitle bytes kept in memory (privacy wipe). */
    fun clearCache() = synchronized(rawCache) { rawCache.clear() }

    suspend fun prepare(
        context: Context,
        subtitle: SavedSubtitle,
        headers: Map<String, String>,
        offsetMs: Long = 0L
    ): SavedSubtitle = withContext(Dispatchers.IO) {
        if (subtitle.url.contains("/text/") && subtitle.url.startsWith("http://") && isOurServer(subtitle.url)) return@withContext subtitle
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                val (bytes, name) = load(context, subtitle.url, headers)
                val preferred = CastHub.settings.value.subtitleCharset.charset?.let { runCatching { Charset.forName(it) }.getOrNull() }
                val normalized = SubtitleConverter.normalize(bytes, name, offsetMs, preferred)
                val fileName = "subtitle.${normalized.extension}"
                val url = App.app?.buildTextUrl(normalized.text, normalized.mimeType, fileName) ?: return@runCatching null
                subtitle.copy(
                    url = url,
                    mimeType = normalized.mimeType,
                    language = subtitle.language ?: name?.let(SubtitleNaming::guessLanguage),
                    label = subtitle.label ?: subtitle.language?.let(SubtitleNaming::displayName) ?: name
                )
            }.onFailure { Timber.w(it, "[Subtitles] Could not prepare ${subtitle.url}") }.getOrNull()
        }
        result ?: subtitle
    }

    /** Bytes of a subtitle that is a local file (content://) or a web URL. */
    fun load(context: Context, url: String, headers: Map<String, String> = emptyMap()): Pair<ByteArray, String?> {
        synchronized(rawCache) { rawCache[url] }?.let { return it }
        val uri = Uri.parse(url)
        val loaded = when (uri.scheme) {
            "content", "file" -> {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                bytes to displayName(context, uri)
            }
            else -> {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> if (!k.equals("Range", true)) header(k, v) }
                }.build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body.bytes()
                    val disposition = response.header("Content-Disposition")
                        ?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                    val segment = uri.lastPathSegment
                    body to (if (disposition != null && (segment == null || !segment.contains('.'))) disposition else segment ?: disposition)
                }
            }
        }
        require(loaded.first.size <= MAX_BYTES) { "Subtitle too large" }
        // A .zip (subtitle sites, picked archives): use the first subtitle inside.
        val unwrapped = SubtitleArchive.unwrap(loaded.first, loaded.second)
        synchronized(rawCache) { rawCache[url] = unwrapped }
        return unwrapped
    }

    /** Keeps downloaded bytes of e.g. an OpenSubtitles file under a stable key. */
    fun remember(key: String, bytes: ByteArray, fileName: String?) {
        synchronized(rawCache) { rawCache[key] = bytes to fileName }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun isOurServer(url: String): Boolean = url.contains(":${App.app?.webServerPort}/text/")

    private const val TIMEOUT_MS = 15_000L
    private const val MAX_BYTES = 10 * 1024 * 1024
}
