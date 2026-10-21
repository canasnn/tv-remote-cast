package com.canasnn.tvremote.cast.iptv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.iptv.EmptyPlaylistException
import com.ruterika.castcore.iptv.IptvIndex
import com.ruterika.castcore.iptv.IptvIndexOptions
import com.ruterika.castcore.iptv.M3uEntry
import com.ruterika.castcore.iptv.M3uParser
import com.ruterika.castcore.iptv.NotAnM3uPlaylistException
import com.ruterika.castcore.iptv.PlaylistFiles
import com.ruterika.castcore.iptv.PlaylistHttpException
import com.ruterika.castcore.iptv.PlaylistLoadException
import com.ruterika.castcore.iptv.PlaylistLoader
import com.ruterika.castcore.iptv.PlaylistTimeoutException
import com.ruterika.castcore.iptv.StoredPlaylist
import com.ruterika.castcore.library.IptvSource
import com.ruterika.castcore.library.SavedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Loads IPTV lists (URL or picked file). The downloaded file is kept on disk with its SHA-1 so
 * an update can tell "no changes", and the parsed channels are kept in an on-disk [IptvIndex]
 * that the UI pages through, so lists with 100k+ channels stay smooth.
 *
 * Files in `cacheDir/iptv` per list id: `<id>.raw` (as fetched), `<id>.meta` (hash, time,
 * content type) and `<id>.<hash>.<options>.idx/.dat` (the index). Sub-lists use the id
 * `<parent id>~<hash of url>`, so removing a list removes its sub-lists too.
 */
object IptvRepository {

    /** How current the shown list is (drives the status bar). */
    enum class Freshness { UPDATED, UNCHANGED, CACHED, LOCAL, UPDATE_FAILED }

    class Loaded(val index: IptvIndex, val freshness: Freshness, val updatedAt: Long, val error: Throwable? = null)

    /** Result of checking an address before it is added. */
    class Analysis(val message: String, val canLoadAnyway: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val loader = PlaylistLoader(http)
    private val locks = ConcurrentHashMap<String, Mutex>()

    private class Open(val sha1: String, val index: IptvIndex)

    /** Recently opened indexes (only the arrays live in memory; entries stay on disk). */
    private val open = object : LinkedHashMap<String, Open>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Open>?): Boolean = size > MAX_OPEN
    }

    private data class Meta(
        val sha1: String,
        val loadedAt: Long,
        val contentType: String?,
        val charset: String?,
        val nameHint: String?,
        val location: String
    )

    fun options(): IptvIndexOptions = CastHub.settings.value.let { IptvIndexOptions(it.iptvConsiderSubgroups, it.iptvRenameWithTvgName, it.iptvKeepRssRootChannel) }

    fun isLocal(location: String): Boolean =
        location.startsWith("content://") || location.startsWith("file://") || location.startsWith("/")

    /** The index already in memory, if it matches the current options. */
    fun cached(sourceId: String): IptvIndex? =
        synchronized(open) { open[sourceId] }?.index?.takeIf { it.options == options() }

    /** User agent used to fetch a list and to play its channels. */
    fun userAgent(source: IptvSource): String? = source.userAgent ?: CastHub.settings.value.iptvDefaultUserAgent.ifBlank { null }

    /** The saved copy of [source], or null when it was never loaded. Never touches the network. */
    suspend fun openCached(context: Context, source: IptvSource): Loaded? = withContext(Dispatchers.IO) {
        lock(source.id).withLock {
            val meta = readMeta(context, source.id) ?: migrateLegacy(context, source) ?: return@withLock null
            val index = indexFor(context, source, meta) ?: return@withLock null
            Loaded(index, if (isLocal(source.location)) Freshness.LOCAL else Freshness.CACHED, meta.loadedAt)
        }
    }

    /**
     * Fetches [source] again. When the bytes are unchanged (same SHA-1) the existing index is
     * kept ([Freshness.UNCHANGED]). When the fetch fails but a copy exists, that copy comes back
     * as [Freshness.UPDATE_FAILED] with the error; otherwise the error is thrown.
     *
     * @param lenient read content that does not look like a list anyway.
     */
    suspend fun update(context: Context, source: IptvSource, lenient: Boolean): Loaded = withContext(Dispatchers.IO) {
        lock(source.id).withLock {
            val tmp = File(dir(context), "${source.id}.download")
            val old = readMeta(context, source.id) ?: migrateLegacy(context, source)
            try {
                val (stored, nameHint) = fetch(context, source, tmp)
                val now = System.currentTimeMillis()
                if (old != null && old.sha1 == stored.sha1) {
                    val existing = indexFor(context, source, old)
                    if (existing != null) {
                        tmp.delete()
                        writeMeta(context, source.id, old.copy(loadedAt = now))
                        markLoaded(source, existing, now)
                        return@withLock Loaded(existing, Freshness.UNCHANGED, now)
                    }
                }
                val meta = Meta(stored.sha1, now, stored.contentType, stored.charset, nameHint, source.location)
                val index = build(context, source.id, tmp, meta, lenient)
                if (index.size == 0) throw EmptyPlaylistException("The list has no channels")
                val raw = rawFile(context, source.id)
                raw.delete()
                if (!tmp.renameTo(raw)) {
                    tmp.copyTo(raw, overwrite = true)
                    tmp.delete()
                }
                writeMeta(context, source.id, meta)
                cleanup(context, source.id, meta)
                remember(source.id, meta.sha1, index)
                markLoaded(source, index, now)
                Loaded(index, Freshness.UPDATED, now)
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                val cached = old?.let { runCatching { indexFor(context, source, it) }.getOrNull() }
                if (cached != null) Loaded(cached, Freshness.UPDATE_FAILED, old.loadedAt, e) else throw e
            }
        }
    }

    /**
     * Checks an address before it is added: file type, reachability and whether the content
     * looks like an IPTV list. Returns null when everything looks fine.
     */
    suspend fun analyze(context: Context, location: String, displayName: String?, userAgent: String?): Analysis? = withContext(Dispatchers.IO) {
        try {
            when {
                isLocal(location) -> {
                    val hint = displayName ?: location
                    val ext = PlaylistFiles.extensionOf(hint)
                    val head = openLocal(context, location).let { PlaylistFiles.openReader(it).use(PlaylistFiles::peekHead) }
                    PlaylistFiles.detectFormat(head, hint)
                    when {
                        ext == null -> Analysis("The type of the file could not be determined.", canLoadAnyway = true)
                        ext !in PlaylistFiles.KNOWN_EXTENSIONS -> Analysis("This file (.$ext) doesn't appear to be an IPTV list.", canLoadAnyway = true)
                        else -> null
                    }
                }
                location.startsWith("http://", true) || location.startsWith("https://", true) -> {
                    val peek = loader.peek(location, userAgent)
                    PlaylistFiles.detectFormat(peek.head, location, peek.contentType)
                    null
                }
                else -> Analysis("The address appears to be invalid. It must start with http://, https:// or be a picked file.", canLoadAnyway = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotAnM3uPlaylistException) {
            Analysis(describe(e), canLoadAnyway = canLoadAnyway(e))
        } catch (e: Exception) {
            Analysis(describe(e), canLoadAnyway = false)
        }
    }

    /** Whether "load anyway" makes sense for this failure (content that is not a list). */
    fun canLoadAnyway(e: Throwable?): Boolean =
        e is NotAnM3uPlaylistException && e !is EmptyPlaylistException && !e.isHlsStream

    /** A user-facing explanation of a load failure. */
    fun describe(e: Throwable): String = when (e) {
        is PlaylistHttpException -> buildString {
            append("Could not fetch the IPTV list from the server.\n\nError code: HTTP ${e.code}")
            if (e.reason.isNotBlank()) append(" ").append(e.reason)
            e.details?.let { append("\nDetails: ").append(it) }
        }
        is PlaylistTimeoutException -> "Could not fetch the IPTV list: the server did not answer in time. It may be down or unresponsive."
        is EmptyPlaylistException -> "The IPTV list is empty: it has no channels."
        is NotAnM3uPlaylistException ->
            if (e.isHlsStream) "This address is a single video stream (HLS), not a channel list. Cast it from the Link tab instead."
            else "This doesn't look like an IPTV list.\n\n${e.message}"
        is SecurityException -> "Access to this file was denied. Please add the list again."
        is FileNotFoundException -> "The IPTV file can no longer be found."
        is UnknownHostException -> "Server not found. Check the address and your connection."
        is ConnectException -> "Could not connect to the server. It may be down or unresponsive."
        is SSLException -> "Secure connection to the server failed (${e.message})."
        is PlaylistLoadException -> e.cause?.takeIf { it !is PlaylistLoadException && it !is IllegalArgumentException }
            ?.let { cause -> describe(cause).takeIf { it != cause.message } } ?: (e.message ?: "An error occurred while loading the IPTV list")
        else -> e.message ?: "An error occurred while loading the IPTV list"
    }

    /** Deletes the cached files of a list and of all its sub-lists. */
    fun forget(context: Context, sourceId: String) {
        synchronized(open) {
            open.keys.filter { it.startsWith(sourceId) }.forEach { key -> open.remove(key)?.index?.close() }
        }
        dir(context).listFiles()?.filter { it.name.startsWith(sourceId) }?.forEach { it.delete() }
    }

    /** A sub-list (an entry that links to another list) as a source that inherits [parent]'s user agent. */
    fun subSource(parent: IptvSource, entry: M3uEntry): IptvSource = IptvSource(
        id = parent.id + "~" + PlaylistFiles.sha1(entry.url).take(12),
        name = entry.title,
        location = entry.url,
        userAgent = entry.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: parent.userAgent
    )

    fun M3uEntry.toSavedMedia(listUserAgent: String? = null, forceLive: Boolean = false): SavedMedia = SavedMedia(
        url = url,
        title = title,
        mimeType = if (url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }) "application/x-mpegURL" else null,
        headers = if (listUserAgent != null && headers.keys.none { it.equals("User-Agent", true) }) headers + ("User-Agent" to listUserAgent) else headers,
        posterUrl = logo,
        isLive = forceLive || (durationSeconds == null && season == null)
    )

    /**
     * At most [radius] channels on each side of position [position] of [channels] (entry
     * indexes), so the TV queue stays small and CH▲/▼ walks the current group or search.
     */
    fun window(
        index: IptvIndex,
        channels: IntArray,
        position: Int,
        userAgent: String?,
        forceLive: Boolean = false,
        radius: Int = 50
    ): Pair<List<SavedMedia>, Int> {
        val from = (position - radius).coerceAtLeast(0)
        val to = (position + radius + 1).coerceAtMost(channels.size)
        val items = (from until to).mapNotNull { i ->
            val entry = index.entry(channels[i])
            if (entry.url.isEmpty() || (i != position && entry.isNestedList)) null
            else entry.toSavedMedia(userAgent, forceLive && i == position)
        }
        val start = (from until position).count { i -> index.entry(channels[i]).let { it.url.isNotEmpty() && !it.isNestedList } }
        return items to start
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun lock(id: String): Mutex = locks.getOrPut(id) { Mutex() }

    private fun dir(context: Context) = File(context.cacheDir, "iptv").apply { mkdirs() }
    private fun rawFile(context: Context, id: String) = File(dir(context), "$id.raw")
    private fun metaFile(context: Context, id: String) = File(dir(context), "$id.meta")
    private fun indexFiles(context: Context, id: String, sha1: String, options: IptvIndexOptions): Pair<File, File> {
        val base = "$id.${sha1.take(12)}.${options.tag}"
        return File(dir(context), "$base.idx") to File(dir(context), "$base.dat")
    }

    /** Opens (or rebuilds from the raw file) the index for [meta]. */
    private suspend fun indexFor(context: Context, source: IptvSource, meta: Meta): IptvIndex? {
        val options = options()
        synchronized(open) { open[source.id] }?.let { if (it.sha1 == meta.sha1 && it.index.options == options) return it.index }
        val (idx, dat) = indexFiles(context, source.id, meta.sha1, options)
        val index = IptvIndex.open(idx, dat, options) ?: run {
            val raw = rawFile(context, source.id)
            if (!raw.isFile) return null
            try {
                // The raw copy was accepted once already, so it is read leniently.
                build(context, source.id, raw, meta, lenient = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
        }
        cleanup(context, source.id, meta)
        remember(source.id, meta.sha1, index)
        return index
    }

    private suspend fun build(context: Context, id: String, file: File, meta: Meta, lenient: Boolean): IptvIndex {
        val options = options()
        val (idx, dat) = indexFiles(context, id, meta.sha1, options)
        val charset = meta.charset?.let { runCatching { Charset.forName(it) }.getOrNull() }
        val baseUrl = meta.location.takeIf { it.startsWith("http", ignoreCase = true) }?.let { M3uParser.splitPipeHeaders(it).first }
        return PlaylistFiles.openReader(file.inputStream(), charset).use { reader ->
            IptvIndex.build(idx, dat, options, baseUrl) { sink ->
                PlaylistFiles.parse(reader, meta.nameHint, meta.contentType, lenient, sink)
            }
        }
    }

    private suspend fun fetch(context: Context, source: IptvSource, target: File): Pair<StoredPlaylist, String?> {
        val location = source.location.trim()
        return when {
            isLocal(location) -> {
                val name = if (location.startsWith("content://")) displayName(context, Uri.parse(location)) else File(location.removePrefix("file://")).name
                PlaylistFiles.store(openLocal(context, location), target) to (name ?: location)
            }
            location.startsWith("http://", true) || location.startsWith("https://", true) ->
                loader.download(location, userAgent(source), target) to location
            else -> throw PlaylistLoadException("The address appears to be invalid: $location")
        }
    }

    private fun openLocal(context: Context, location: String): InputStream = when {
        location.startsWith("content://") ->
            context.contentResolver.openInputStream(Uri.parse(location)) ?: throw FileNotFoundException(location)
        else -> File(Uri.parse(location).path ?: location.removePrefix("file://")).inputStream()
    }

    /** Display name of a picked file (used to check its type). */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun remember(id: String, sha1: String, index: IptvIndex) {
        synchronized(open) { open[id] = Open(sha1, index) }
    }

    private fun markLoaded(source: IptvSource, index: IptvIndex, now: Long) {
        val stored = CastHub.library.data.value.iptvSources.firstOrNull { it.id == source.id } ?: return
        CastHub.library.updateIptvSource(stored.copy(lastLoadedAt = now, channelCount = index.size))
    }

    /** Removes index files of older versions/options of list [id] (not its sub-lists). */
    private fun cleanup(context: Context, id: String, meta: Meta) {
        val (idx, dat) = indexFiles(context, id, meta.sha1, options())
        val keep = setOf(idx.name, dat.name, "$id.raw", "$id.meta", "$id.download")
        dir(context).listFiles()?.filter { it.name.startsWith("$id.") && it.name !in keep }?.forEach { it.delete() }
    }

    /** Lists cached by older versions of the app (`<id>.m3u`) become the raw copy. */
    private fun migrateLegacy(context: Context, source: IptvSource): Meta? {
        val legacy = File(dir(context), "${source.id}.m3u")
        if (!legacy.isFile) return null
        return runCatching {
            val stored = PlaylistFiles.store(legacy.inputStream(), rawFile(context, source.id))
            legacy.delete()
            Meta(stored.sha1, source.lastLoadedAt, null, null, "list.m3u", source.location).also { writeMeta(context, source.id, it) }
        }.getOrNull()
    }

    private fun readMeta(context: Context, id: String): Meta? {
        val file = metaFile(context, id)
        if (!file.isFile) return null
        return runCatching {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            Meta(
                sha1 = p.getProperty("sha1") ?: return null,
                loadedAt = p.getProperty("loadedAt")?.toLongOrNull() ?: 0L,
                contentType = p.getProperty("contentType"),
                charset = p.getProperty("charset"),
                nameHint = p.getProperty("nameHint"),
                location = p.getProperty("location").orEmpty()
            )
        }.getOrNull()
    }

    private fun writeMeta(context: Context, id: String, meta: Meta) {
        val p = Properties()
        p.setProperty("sha1", meta.sha1)
        p.setProperty("loadedAt", meta.loadedAt.toString())
        meta.contentType?.let { p.setProperty("contentType", it) }
        meta.charset?.let { p.setProperty("charset", it) }
        meta.nameHint?.let { p.setProperty("nameHint", it) }
        p.setProperty("location", meta.location)
        runCatching { metaFile(context, id).outputStream().use { p.store(it, null) } }
    }

    private const val MAX_OPEN = 3
}
