package com.canasnn.tvremote.utils

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class WebServer(
    private val context: Context,
    host: String?,
    port: Int
) : NanoHTTPD(host, port) {

    private class MediaEntry(
        val uri: Uri,
        val mimeType: String,
        val displayName: String,
        @Volatile var updatedAt: Long
    )

    /** Size + modification time of a served file, for ETag / Last-Modified. */
    private data class Validator(val size: Long, val lastModified: Long?, val etag: String)

    /**
     * token → phone file. Persisted (48 h) so a TV retry or resume after the
     * app process was killed still finds its file; an identical uri keeps its token.
     */
    private val mediaRegistry = ConcurrentHashMap<String, MediaEntry>()
    private val tokenByUri = ConcurrentHashMap<String, String>()
    private val validators = ConcurrentHashMap<String, Validator>()
    private val registryFile = registryFile(context)

    private data class TextEntry(val bytes: ByteArray, val mimeType: String)

    // Small generated files (normalized subtitles) served from memory.
    private val textRegistry = ConcurrentHashMap<String, TextEntry>()

    init {
        loadRegistry()
    }

    fun registerText(text: String, mimeType: String, fileName: String): String {
        val token = UUID.randomUUID().toString()
        if (textRegistry.size > MAX_TEXT_ENTRIES) textRegistry.clear()
        textRegistry[token] = TextEntry(text.toByteArray(Charsets.UTF_8), mimeType)
        return "/text/$token/${Uri.encode(fileName)}"
    }

    fun registerMedia(uri: Uri, mimeType: String, displayName: String): String {
        val name = displayName.ifBlank { "media" }
        val key = uri.toString()
        val token = synchronized(mediaRegistry) {
            val existing = tokenByUri[key]?.takeIf { mediaRegistry.containsKey(it) }
            val token = existing ?: UUID.randomUUID().toString()
            mediaRegistry[token] = MediaEntry(uri, mimeType, name, System.currentTimeMillis())
            tokenByUri[key] = token
            token
        }
        persistAsync()
        return "/media/$token/${Uri.encode(name)}"
    }

    fun clearRegisteredMedia() {
        synchronized(mediaRegistry) {
            mediaRegistry.clear()
            tokenByUri.clear()
            validators.clear()
        }
        textRegistry.clear()
        persistAsync()
    }

    // ── Token registry persistence ──────────────────────────────────────────

    private fun loadRegistry() {
        val now = System.currentTimeMillis()
        val entries = readRegistry(registryFile, now)
        synchronized(mediaRegistry) {
            entries.forEach { (token, entry) ->
                mediaRegistry[token] = entry
                tokenByUri[entry.uri.toString()] = token
            }
        }
    }

    private fun persistAsync() {
        val now = System.currentTimeMillis()
        val snapshot = synchronized(mediaRegistry) {
            pruneLocked(now)
            mediaRegistry.entries.map { it.key to it.value }
        }
        ioExecutor.execute {
            runCatching {
                val array = JSONArray()
                snapshot.forEach { (token, e) ->
                    array.put(
                        JSONObject()
                            .put("token", token)
                            .put("uri", e.uri.toString())
                            .put("mime", e.mimeType)
                            .put("name", e.displayName)
                            .put("updatedAt", e.updatedAt)
                    )
                }
                registryFile.parentFile?.mkdirs()
                val tmp = File(registryFile.parentFile, registryFile.name + ".tmp")
                tmp.writeText(JSONObject().put("version", 1).put("entries", array).toString())
                if (!tmp.renameTo(registryFile)) {
                    registryFile.delete()
                    tmp.renameTo(registryFile)
                }
            }.onFailure { android.util.Log.w("WebServer", "Saving media tokens failed", it) }
        }
    }

    /** Drops expired entries and keeps the newest [MAX_MEDIA_ENTRIES]. */
    private fun pruneLocked(now: Long) {
        val expired = mediaRegistry.filterValues { now - it.updatedAt > TOKEN_TTL_MS }.keys
        val overflow = (mediaRegistry.size - expired.size - MAX_MEDIA_ENTRIES).takeIf { it > 0 }?.let { extra ->
            mediaRegistry.entries.filterNot { it.key in expired }.sortedBy { it.value.updatedAt }.take(extra).map { it.key }
        }.orEmpty()
        (expired + overflow).forEach { token ->
            mediaRegistry.remove(token)?.let { tokenByUri.remove(it.uri.toString(), token) }
            validators.remove(token)
        }
    }

    /** Keeps an entry alive while the TV keeps using it (written at most hourly). */
    private fun touch(entry: MediaEntry) {
        val now = System.currentTimeMillis()
        if (now - entry.updatedAt > TOUCH_INTERVAL_MS) {
            entry.updatedAt = now
            persistAsync()
        }
    }

    companion object {
        private const val MAX_TEXT_ENTRIES = 64
        private const val MAX_MEDIA_ENTRIES = 2000
        private const val TOKEN_TTL_MS = 48L * 60 * 60 * 1000
        private const val TOUCH_INTERVAL_MS = 60L * 60 * 1000
        private const val MEDIA_MAX_AGE_SECONDS = 2_592_000L // 30 days

        private val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "media-token-store").apply { isDaemon = true }
        }

        private fun registryFile(context: Context) = File(File(context.filesDir, "cast"), "media_tokens.json")

        private fun readRegistry(file: File, now: Long): List<Pair<String, MediaEntry>> = runCatching {
            if (!file.exists()) return emptyList()
            val array = JSONObject(file.readText()).optJSONArray("entries") ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val token = o.optString("token").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val updatedAt = o.optLong("updatedAt")
                if (now - updatedAt > TOKEN_TTL_MS) return@mapNotNull null
                token to MediaEntry(Uri.parse(uri), o.optString("mime", "video/*"), o.optString("name", "media"), updatedAt)
            }
        }.getOrDefault(emptyList())

        /** True when files cast before the app was killed may still be requested by the TV. */
        fun hasLiveMedia(context: Context): Boolean =
            readRegistry(registryFile(context), System.currentTimeMillis()).isNotEmpty()
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Headers", "origin,accept,content-type,range,if-none-match")
                addHeader("Access-Control-Allow-Methods", "GET,HEAD,OPTIONS")
            }
        }

        if (session.uri == "/health") {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }

        val segments = session.uri.trim('/').split('/')
        if (segments.size >= 2 && segments[0] == "text") {
            val text = textRegistry[segments[1]]
                ?: return notFound("Not found")
            return newFixedLengthResponse(
                Response.Status.OK,
                "${text.mimeType}; charset=utf-8",
                java.io.ByteArrayInputStream(text.bytes),
                text.bytes.size.toLong()
            ).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Cache-Control", "no-cache")
            }
        }
        if (segments.size < 2 || segments.firstOrNull() != "media") {
            return notFound("Not found")
        }

        val token = segments[1]
        val entry = mediaRegistry[token] ?: return notFound("Media not found")
        touch(entry)

        return serveMedia(token, entry, session.headers["range"], session.headers["if-none-match"])
    }

    private fun notFound(message: String): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, message).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }

    private fun serveMedia(token: String, entry: MediaEntry, rangeHeader: String?, ifNoneMatch: String?): Response {
        val parcelFileDescriptor = runCatching { context.contentResolver.openFileDescriptor(entry.uri, "r") }.getOrNull()
            ?: return notFound("Unable to open media")

        val fileSize = parcelFileDescriptor.statSize
        val validator = validatorFor(token, entry, fileSize)

        // ── Conditional request: the client already has this exact file ──────
        if (ifNoneMatch != null && etagMatches(ifNoneMatch, validator.etag)) {
            parcelFileDescriptor.close()
            return newFixedLengthResponse(Response.Status.NOT_MODIFIED, entry.mimeType, "").apply {
                addCommonMediaHeaders(entry, validator)
            }
        }

        val range = parseRange(rangeHeader, fileSize)
        if (range is RangeSpec.Unsatisfiable) {
            parcelFileDescriptor.close()
            return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                MIME_PLAINTEXT,
                "Requested range not satisfiable"
            ).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes */${fileSize.coerceAtLeast(0L)}")
            }
        }
        val partial = range as? RangeSpec.Partial

        val start = partial?.start ?: 0L
        val end = partial?.end ?: (fileSize - 1L).coerceAtLeast(0L)
        val contentLength = if (fileSize >= 0L) (end - start + 1L).coerceAtLeast(0L) else -1L

        android.util.Log.d(
            "WebServer",
            "Serving ${entry.displayName} [${entry.mimeType}] " +
            "range=${rangeHeader ?: "none"} start=$start end=$end size=$fileSize"
        )

        val fileInputStream = ParcelFileDescriptor.AutoCloseInputStream(parcelFileDescriptor).apply {
            if (start > 0L) {
                channel.position(start)
            }
        }
        val inputStream = ClosableRangeInputStream(
            input = fileInputStream,
            bytesToRead = contentLength,
            onClose = {
                fileInputStream.close()
            }
        )

        // ── HEVC/H.265 codec hint ──────────────────────────────────────────────
        // Cast receivers may not auto-detect HEVC from a bare "video/mp4" Content-Type.
        // Appending a codec profile hint allows the receiver to pick the right
        // hardware decoder. Profile hev1.1.6.L93.B0 covers most common 4K HEVC streams.
        // ──────────────────────────────────────────────────────────────────────────
        val effectiveMimeType = if (isHevcMime(entry.mimeType)) {
            "${entry.mimeType}; codecs=\"hev1.1.6.L93.B0\""
        } else {
            entry.mimeType
        }

        val response = if (contentLength >= 0L) {
            newFixedLengthResponse(
                if (partial != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                effectiveMimeType,
                inputStream,
                contentLength
            )
        } else {
            newChunkedResponse(Response.Status.OK, effectiveMimeType, inputStream)
        }

        response.addCommonMediaHeaders(entry, validator)
        response.addHeader("Content-Disposition", "inline; filename=\"${entry.displayName.replace('"', '\'')}\"")
        if (partial != null && fileSize >= 0L) {
            response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
        }
        return response
    }

    private fun Response.addCommonMediaHeaders(entry: MediaEntry, validator: Validator) {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Accept-Ranges", "bytes")
        addHeader("ETag", validator.etag)
        validator.lastModified?.let { addHeader("Last-Modified", httpDate(it)) }
        addHeader("Cache-Control", "public, max-age=$MEDIA_MAX_AGE_SECONDS")
    }

    /** Stable validator per token; recomputed when the file size changes. */
    private fun validatorFor(token: String, entry: MediaEntry, size: Long): Validator {
        validators[token]?.takeIf { it.size == size }?.let { return it }
        val lastModified = lastModifiedOf(entry.uri)
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${entry.uri}|$size|${lastModified ?: 0L}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return Validator(size, lastModified, "\"$digest\"").also { validators[token] = it }
    }

    private fun lastModifiedOf(uri: Uri): Long? = runCatching {
        if (uri.scheme == "file") return uri.path?.let { File(it).lastModified() }?.takeIf { it > 0L }
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val documentIdx = c.getColumnIndex("last_modified") // DocumentsContract, ms
            if (documentIdx >= 0 && !c.isNull(documentIdx)) return c.getLong(documentIdx).takeIf { it > 0L }
            val mediaIdx = c.getColumnIndex("date_modified") // MediaStore, seconds
            if (mediaIdx >= 0 && !c.isNull(mediaIdx)) return (c.getLong(mediaIdx) * 1000L).takeIf { it > 0L }
            null
        }
    }.getOrNull()

    private fun etagMatches(header: String, etag: String): Boolean =
        header.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag }

    private fun httpDate(millis: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(millis))

    /**
     * Returns true if the MIME type indicates HEVC/H.265 content.
     * Covers the common variants: video/hevc, video/x-hevc, and video/mp4 files
     * that have been registered with an HEVC-specific MIME.
     */
    private fun isHevcMime(mimeType: String): Boolean {
        val lower = mimeType.lowercase()
        return lower.contains("hevc") ||
            lower.contains("hev1") ||
            lower.contains("hvc1") ||
            lower.contains("h265") ||
            lower.contains("h.265")
    }

    private sealed interface RangeSpec {
        /** No usable Range header: send the whole file. */
        object Full : RangeSpec
        data class Partial(val start: Long, val end: Long) : RangeSpec
        object Unsatisfiable : RangeSpec
    }

    /**
     * Single byte range (`bytes=s-e`, `bytes=s-`, `bytes=-n`; only the first of
     * several ranges is honoured). Malformed headers are ignored (RFC 7233).
     */
    private fun parseRange(rangeHeader: String?, fileSize: Long): RangeSpec {
        if (rangeHeader.isNullOrBlank() || fileSize < 0L) return RangeSpec.Full
        val header = rangeHeader.trim()
        if (!header.startsWith("bytes=", ignoreCase = true)) return RangeSpec.Full
        val first = header.substring(6).split(',').first().trim()
        val dash = first.indexOf('-')
        if (dash < 0) return RangeSpec.Full
        val startText = first.substring(0, dash).trim()
        val endText = first.substring(dash + 1).trim()

        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return RangeSpec.Full
            if (suffix <= 0L || fileSize == 0L) return RangeSpec.Unsatisfiable
            return RangeSpec.Partial((fileSize - suffix).coerceAtLeast(0L), fileSize - 1L)
        }
        val start = startText.toLongOrNull()?.takeIf { it >= 0L } ?: return RangeSpec.Full
        val end = if (endText.isEmpty()) null else endText.toLongOrNull() ?: return RangeSpec.Full
        if (end != null && end < start) return RangeSpec.Full
        if (start >= fileSize) return RangeSpec.Unsatisfiable
        return RangeSpec.Partial(start, (end ?: fileSize - 1L).coerceAtMost(fileSize - 1L))
    }

    private class ClosableRangeInputStream(
        input: InputStream,
        private val bytesToRead: Long,
        private val onClose: () -> Unit
    ) : FilterInputStream(input) {

        private var remaining = bytesToRead

        override fun read(): Int {
            if (remaining == 0L) {
                return -1
            }
            val value = super.read()
            if (value != -1 && remaining > 0L) {
                remaining--
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) {
                return -1
            }
            val allowedLength = if (remaining < 0L) {
                length
            } else {
                minOf(length.toLong(), remaining).toInt()
            }
            val count = super.read(buffer, offset, allowedLength)
            if (count > 0 && remaining > 0L) {
                remaining -= count.toLong()
            }
            return count
        }

        override fun close() {
            try {
                super.close()
            } finally {
                onClose()
            }
        }
    }
}
