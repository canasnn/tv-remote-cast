package com.ruterika.castcore.iptv

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** List formats [PlaylistFiles] can read. */
enum class PlaylistFormat { M3U, W3U, RSS }

/** A list copied to disk by [PlaylistFiles.store] or [PlaylistLoader.download]. */
data class StoredPlaylist(
    /** SHA-1 (hex) of the bytes as fetched. */
    val sha1: String,
    val bytes: Long,
    val contentType: String? = null,
    val charset: String? = null,
)

/**
 * Format detection, decoding (gzip, charset, BOM) and hashing for IPTV list files.
 * Everything works on streams so lists of any size can be handled.
 */
object PlaylistFiles {

    const val SNIFF_CHARS = 16 * 1024
    private const val READ_BUFFER_CHARS = 64 * 1024
    private const val COPY_BUFFER_BYTES = 32 * 1024

    /** File extensions IPTV lists usually have (anything else gets a "load anyway?" question). */
    val KNOWN_EXTENSIONS = setOf("m3u", "m3u8", "iptv", "w3u", "json", "xml", "rss", "txt", "gz")

    private val HLS_MARKERS = listOf("#EXT-X-TARGETDURATION", "#EXT-X-STREAM-INF", "#EXT-X-MEDIA-SEQUENCE")

    /** Lower-cased extension of a file name, path or URL, or null when there is none. */
    fun extensionOf(name: String?): String? {
        val base = name?.substringBefore('|')?.substringBefore('#')?.substringBefore('?')?.trimEnd('/')
            ?.substringAfterLast('/') ?: return null
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) return null
        return base.substring(dot + 1).lowercase().takeIf { ext -> ext.length <= 5 && ext.all { it.isLetterOrDigit() } }
    }

    /**
     * Decides the format of a list from its first characters ([head]), the file name or URL
     * ([nameHint]) and the HTTP content type.
     *
     * @param lenient read anything that is not obviously W3U as M3U ("load anyway").
     * @throws EmptyPlaylistException when there is no content.
     * @throws NotAnM3uPlaylistException when the content is not a channel list.
     */
    fun detectFormat(head: String, nameHint: String? = null, contentType: String? = null, lenient: Boolean = false): PlaylistFormat {
        val text = head.trimStart('﻿', ' ', '\t', '\r', '\n')
        if (text.isEmpty()) throw EmptyPlaylistException()
        val ext = extensionOf(nameHint)
        val type = contentType?.lowercase().orEmpty()
        if (text[0] == '{' || text[0] == '[') {
            if (ext == "w3u" || "w3u" in type || looksLikeW3u(text)) return PlaylistFormat.W3U
        }
        // RSS/Atom feeds are recognised by their root element, whatever the name or type says.
        if (text[0] == '<' && RssParser.looksLikeFeed(text)) return PlaylistFormat.RSS
        if (lenient) return PlaylistFormat.M3U
        if (HLS_MARKERS.any { text.contains(it, ignoreCase = true) }) {
            throw NotAnM3uPlaylistException("Content is an HLS stream manifest, not an IPTV playlist", isHlsStream = true)
        }
        if (text.startsWith("#EXTM3U", ignoreCase = true) || text.contains("#EXTINF", ignoreCase = true)) return PlaylistFormat.M3U
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        if (firstLine != null && "://" in firstLine && firstLine[0] != '<' && firstLine[0] != '{') return PlaylistFormat.M3U
        val kind = when (text[0]) {
            '<' -> "HTML/XML (an error page or an EPG file?)"
            '{', '[' -> "JSON"
            else -> "unrecognised text"
        }
        throw NotAnM3uPlaylistException("Content is not an M3U playlist: looks like $kind")
    }

    private fun looksLikeW3u(text: String): Boolean = "\"stations\"" in text || "\"groups\"" in text

    /**
     * Opens [input] as text: gzip payloads are detected by their magic bytes, [charset]
     * defaults to UTF-8. The caller closes the returned reader.
     */
    fun openReader(input: InputStream, charset: Charset? = null): BufferedReader {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, COPY_BUFFER_BYTES)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val stream: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered, COPY_BUFFER_BYTES) else buffered
        return BufferedReader(InputStreamReader(stream, charset ?: Charsets.UTF_8), READ_BUFFER_CHARS)
    }

    /** Reads up to [SNIFF_CHARS] characters without consuming them. */
    fun peekHead(reader: BufferedReader): String {
        reader.mark(SNIFF_CHARS)
        val head = CharArray(SNIFF_CHARS)
        var read = 0
        while (read < SNIFF_CHARS) {
            val n = reader.read(head, read, SNIFF_CHARS - read)
            if (n < 0) break
            read += n
        }
        reader.reset()
        return String(head, 0, read)
    }

    /**
     * Detects the format of [reader] and streams its entries to [onEntry].
     *
     * @return the list's header attributes.
     */
    fun parse(
        reader: BufferedReader,
        nameHint: String? = null,
        contentType: String? = null,
        lenient: Boolean = false,
        onEntry: (M3uEntry) -> Unit,
    ): Map<String, String> =
        when (detectFormat(peekHead(reader), nameHint, contentType, lenient)) {
            PlaylistFormat.M3U -> M3uParser.forEachEntry(reader, onEntry)
            PlaylistFormat.W3U -> W3uParser.forEachEntry(reader, onEntry)
            PlaylistFormat.RSS -> RssParser.forEachEntry(reader, onEntry)
        }

    /** Copies [input] into [target] (closing [input]) and returns its SHA-1. */
    fun store(input: InputStream, target: File, contentType: String? = null, charset: String? = null): StoredPlaylist {
        val digest = MessageDigest.getInstance("SHA-1")
        var total = 0L
        target.parentFile?.mkdirs()
        input.use { src ->
            target.outputStream().use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    total += n
                }
            }
        }
        return StoredPlaylist(hex(digest.digest()), total, contentType, charset)
    }

    /** SHA-1 (hex) of [text]. */
    fun sha1(text: String): String = hex(MessageDigest.getInstance("SHA-1").digest(text.toByteArray()))

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
