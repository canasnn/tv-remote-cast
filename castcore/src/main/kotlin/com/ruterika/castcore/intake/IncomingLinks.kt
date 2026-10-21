package com.ruterika.castcore.intake

import java.net.URLDecoder

/**
 * Decides what to do with links and files other apps hand to us (VIEW / SEND
 * intents): a web page for the browser, something playable, or an IPTV list.
 */
object IncomingLinks {

    /** Streaming schemes that are always media (played by the TV/phone player). */
    val STREAM_SCHEMES = setOf("rtsp", "rtsps")

    private val MEDIA_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mts", "flv", "wmv", "3gp", "mpg", "mpeg", "ogv",
        "m3u8", "m3u", "mpd", "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka"
    )
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
    private val PLAYLIST_MIMES = setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-mpegurl", "audio/mpegurl")
    private val MEDIA_MIMES = PLAYLIST_MIMES + setOf("application/dash+xml", "application/vnd.mpeg.dash.mpd", "application/x-matroska")

    private val URL_REGEX = Regex("""(?i)\b(?:https?|rtsps?)://[^\s<>"'`]+""")

    /** The first link in shared text ("Look at this: https://…"), trailing punctuation removed. */
    fun firstUrl(text: String?): String? {
        val match = text?.let { URL_REGEX.find(it) }?.value ?: return null
        var url = match.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"', '*')
        // A closing bracket belongs to the text unless the URL opened one itself.
        while ((url.endsWith(')') && url.count { it == '(' } < url.count { it == ')' }) ||
            (url.endsWith(']') && url.count { it == '[' } < url.count { it == ']' })
        ) url = url.dropLast(1).trimEnd('.', ',', ';', ':', '!', '?')
        return url
    }

    fun scheme(url: String): String = url.substringBefore("://", "").lowercase()

    /** Lower-case extension of the URL path or file name ("" when there is none). */
    fun extension(urlOrName: String): String {
        val path = urlOrName.substringBefore('#').substringBefore('?')
        val last = path.substringAfterLast('/')
        return if ('.' in last) last.substringAfterLast('.').lowercase().take(6) else ""
    }

    /** True for streams, media MIME types and links to media files. */
    fun isMedia(url: String, mimeType: String? = null, fileName: String? = null): Boolean {
        if (scheme(url) in STREAM_SCHEMES) return true
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/") || mime in MEDIA_MIMES)) return true
        return extension(fileName ?: url) in MEDIA_EXTENSIONS || (fileName != null && extension(url) in MEDIA_EXTENSIONS)
    }

    /** `.m3u`/`.m3u8` files and mpegurl MIME types: maybe an IPTV list, maybe an HLS stream. */
    fun isPlaylistFile(url: String, mimeType: String? = null, fileName: String? = null): Boolean {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        return mime in PLAYLIST_MIMES || extension(fileName ?: url) in PLAYLIST_EXTENSIONS || extension(url) in PLAYLIST_EXTENSIONS
    }

    /**
     * Judges the first bytes of an m3u file: an IPTV channel list has `#EXTINF`
     * entries (often with `tvg-`/`group-title`) but none of the HLS stream tags.
     */
    fun looksLikeIptvList(head: String): Boolean {
        val hls = listOf("#EXT-X-TARGETDURATION", "#EXT-X-STREAM-INF", "#EXT-X-MEDIA-SEQUENCE", "#EXT-X-ENDLIST", "#EXT-X-MAP", "#EXT-X-KEY")
            .any { head.contains(it, ignoreCase = true) }
        if (hls) return false
        return head.contains("#EXTINF", ignoreCase = true) || head.contains("tvg-", ignoreCase = true) || head.contains("group-title", ignoreCase = true)
    }

    /** A MIME type players understand for [url] (null = let the player sniff). */
    fun mediaMimeType(url: String, mimeType: String? = null, fileName: String? = null): String? {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "*/*" }
        if (mime != null && !mime.endsWith("/*")) return mime
        return when (extension(fileName ?: url)) {
            "m3u8", "m3u" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> mime
        }
    }

    /** A readable title: the file name without extension, or the host. */
    fun titleFor(url: String, fileName: String? = null): String {
        fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.let { return it }
        val afterScheme = url.substringAfter("://", url)
        val host = afterScheme.substringBefore('/').substringBefore('?').substringAfter('@')
        val last = afterScheme.substringAfter('/', "").substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        return decoded.substringBeforeLast('.').ifBlank { host.ifBlank { url } }
    }
}
