package com.canasnn.tvremote.cast.intake

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.canasnn.tvremote.ui.app.CastTab
import com.ruterika.castcore.intake.IncomingLinks
import com.ruterika.castcore.library.SavedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/** Something another app (or a launcher shortcut) asked us to open. */
sealed interface IncomingRequest {
    /** A web page: the in-app browser (or another browser, see the "Open links in the in-app browser" setting). */
    data class Page(val url: String) : IncomingRequest

    /**
     * Playable media (links, streams, files). [iptv] is set for m3u/m3u8 files
     * that may be an IPTV list; [likelyIptv] when their content looks like one.
     */
    data class Media(
        val items: List<SavedMedia>,
        val iptv: IptvCandidate? = null,
        val likelyIptv: Boolean = false
    ) : IncomingRequest

    /** Launcher shortcut: one of [Intake.SECTION_BOOKMARKS] … */
    data class Section(val section: String) : IncomingRequest
}

data class IptvCandidate(val name: String, val location: String)

/**
 * Incoming VIEW / SEND intents. MainActivity parses them with [parse] and
 * posts the result; the navigation graph consumes [request] once the home
 * screen is up (like the "open now playing" request).
 */
object Intake {

    const val ACTION_OPEN_SECTION = "com.canasnn.tvremote.OPEN_SECTION"
    const val EXTRA_SECTION = "section"
    const val SECTION_BOOKMARKS = "bookmarks"
    const val SECTION_MOST_VISITED = "most_visited"
    const val SECTION_LOCAL_MEDIA = "local_media"
    const val SECTION_IPTV = "iptv"

    private val _request = MutableStateFlow<IncomingRequest?>(null)
    val request: StateFlow<IncomingRequest?> = _request.asStateFlow()
    fun post(request: IncomingRequest) { _request.value = request }
    fun consume() { _request.value = null }

    /** A cast hub tab to show (launcher shortcuts "Local media" / "IPTV"); the cast screen consumes it. */
    private val _castTab = MutableStateFlow<CastTab?>(null)
    val castTabRequest: StateFlow<CastTab?> = _castTab.asStateFlow()
    fun requestCastTab(tab: CastTab) { _castTab.value = tab }
    fun consumeCastTab() { _castTab.value = null }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** True for intents this object understands (so MainActivity knows to parse them off the main thread). */
    fun handles(intent: Intent): Boolean = intent.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, ACTION_OPEN_SECTION)

    /** Turns an intent into a request; null when there is nothing we can open. Call off the main thread. */
    suspend fun parse(context: Context, intent: Intent): IncomingRequest? = withContext(Dispatchers.IO) {
        runCatching {
            when (intent.action) {
                ACTION_OPEN_SECTION -> intent.getStringExtra(EXTRA_SECTION)?.let(IncomingRequest::Section)
                Intent.ACTION_VIEW -> intent.data?.let { fromUri(context, it, intent.type, intent.getStringExtra(Intent.EXTRA_TITLE)) }
                Intent.ACTION_SEND -> {
                    val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    if (stream != null) fromStreams(context, listOf(stream), intent.type)
                    else {
                        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        IncomingLinks.firstUrl(text)?.let { url ->
                            fromUrl(url, null, intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() && !it.contains("://") })
                        }
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    if (streams.isEmpty()) null else fromStreams(context, streams, intent.type)
                }
                else -> null
            }
        }.onFailure { Timber.w(it, "[Intake] Could not read the intent") }.getOrNull()
    }

    private suspend fun fromUri(context: Context, uri: Uri, type: String?, title: String?): IncomingRequest? =
        when (uri.scheme?.lowercase()) {
            "http", "https" -> fromUrl(uri.toString(), type, title)
            "content", "file" -> fromStreams(context, listOf(uri), type)
            in IncomingLinks.STREAM_SCHEMES -> IncomingRequest.Media(listOf(SavedMedia(url = uri.toString(), title = title ?: IncomingLinks.titleFor(uri.toString()), isLive = true)))
            else -> null
        }

    private fun fromUrl(url: String, type: String?, title: String?): IncomingRequest {
        if (!IncomingLinks.isMedia(url, type)) return IncomingRequest.Page(url)
        val name = title ?: IncomingLinks.titleFor(url)
        val media = SavedMedia(
            url = url,
            title = name,
            mimeType = IncomingLinks.mediaMimeType(url, type),
            isLive = IncomingLinks.scheme(url) in IncomingLinks.STREAM_SCHEMES,
            addedAt = System.currentTimeMillis()
        )
        if (!IncomingLinks.isPlaylistFile(url, type)) return IncomingRequest.Media(listOf(media))
        val head = runCatching {
            http.newCall(Request.Builder().url(url).header("Range", "bytes=0-65535").build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body.byteStream().use { readHead(it) }
            }
        }.getOrNull()
        return IncomingRequest.Media(
            items = listOf(media),
            iptv = IptvCandidate(name, url),
            likelyIptv = head != null && IncomingLinks.looksLikeIptvList(head)
        )
    }

    /** Files shared or opened from file managers, galleries, … */
    private fun fromStreams(context: Context, uris: List<Uri>, type: String?): IncomingRequest? {
        val resolver = context.contentResolver
        val items = uris.mapNotNull { uri ->
            val name = displayName(context, uri)
            val mime = (if (uri.scheme == "content") resolver.getType(uri) else null) ?: type?.takeUnless { it.endsWith("/*") && uris.size > 1 }
            if (!IncomingLinks.isMedia(uri.toString(), mime, name)) return@mapNotNull null
            val readable = keepReadable(context, uri, name)
            SavedMedia(
                url = readable.toString(),
                title = IncomingLinks.titleFor(uri.toString(), name),
                mimeType = IncomingLinks.mediaMimeType(uri.toString(), mime, name),
                addedAt = System.currentTimeMillis()
            ) to (name to mime)
        }
        if (items.isEmpty()) return null
        val (first, info) = items.first()
        val (name, mime) = info
        val playlist = items.size == 1 && IncomingLinks.isPlaylistFile(uris.first().toString(), mime, name)
        if (!playlist) return IncomingRequest.Media(items.map { it.first })
        // An m3u file: keep a private copy so the IPTV list still works after the grant ends.
        val head = runCatching { resolver.openInputStream(uris.first())?.use { readHead(it) } }.getOrNull()
        val listFile = runCatching { copyTo(context, uris.first(), File(context.filesDir, "incoming"), name) }.getOrNull()
        return IncomingRequest.Media(
            items = listOf(first),
            iptv = listFile?.let { IptvCandidate(first.title, Uri.fromFile(it).toString()) },
            likelyIptv = head != null && IncomingLinks.looksLikeIptvList(head)
        )
    }

    /**
     * A content:// grant from another app usually ends with our task. Keep a
     * persistable permission when offered; otherwise copy small files to the cache.
     */
    private fun keepReadable(context: Context, uri: Uri, name: String?): Uri {
        if (uri.scheme != "content") return uri
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (persisted) return uri
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull() ?: return uri
        if (size > MAX_COPY_BYTES) return uri
        val dir = File(context.cacheDir, "incoming")
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > COPY_MAX_AGE_MS }?.forEach { it.delete() }
        return runCatching { Uri.fromFile(copyTo(context, uri, dir, name)) }.getOrDefault(uri)
    }

    private fun copyTo(context: Context, uri: Uri, dir: File, name: String?): File {
        dir.mkdirs()
        val safe = (name ?: uri.lastPathSegment ?: "file").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        val target = File(dir, "${System.currentTimeMillis()}-$safe")
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun displayName(context: Context, uri: Uri): String? =
        if (uri.scheme == "file") uri.lastPathSegment
        else runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()

    private fun readHead(input: java.io.InputStream): String {
        val buffer = ByteArray(HEAD_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private const val HEAD_BYTES = 64 * 1024
    private const val MAX_COPY_BYTES = 64L * 1024 * 1024
    private const val COPY_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
}
