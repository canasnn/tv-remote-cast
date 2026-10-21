package com.ruterika.castcore.download

import java.net.URL

/** What a TS download needs to know about an HLS media playlist. */
data class HlsMediaPlaylist(
    /** Absolute segment URLs in order. */
    val segments: List<String>,
    /** No `#EXT-X-ENDLIST`: a live stream that never ends. */
    val live: Boolean,
    /** `#EXT-X-KEY` with a method other than NONE. */
    val encrypted: Boolean,
    /** fMP4 segments (`#EXT-X-MAP`): joining them does not give a .ts file. */
    val fragmentedMp4: Boolean
) {
    /** Plain MPEG-TS segments that can be joined into one playable file. */
    val joinableAsTs: Boolean get() = !live && !encrypted && !fragmentedMp4 && segments.isNotEmpty()
}

/**
 * Minimal HLS reading for "Download as TS": picks the best
 * variant of a master playlist and lists the segments of a media playlist.
 */
object HlsSegments {

    /** URL of the highest-bandwidth variant, or null when [text] is not a master playlist. */
    fun bestVariantUrl(text: String, baseUrl: String): String? {
        val lines = text.lineSequence().map { it.trim() }.toList()
        var best: Pair<Long, String>? = null
        lines.forEachIndexed { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) return@forEachIndexed
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
            val bandwidth = Regex("""(?i)(?:^|[:,])BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            if (best == null || bandwidth > best!!.first) best = bandwidth to resolve(baseUrl, uri)
        }
        return best?.second
    }

    fun parseMedia(text: String, baseUrl: String): HlsMediaPlaylist {
        val segments = ArrayList<String>()
        var encrypted = false
        var fmp4 = false
        var ended = false
        var expectSegment = false
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> expectSegment = true
                line.startsWith("#EXT-X-KEY", ignoreCase = true) ->
                    if (!Regex("""(?i)METHOD=NONE""").containsMatchIn(line)) encrypted = true
                line.startsWith("#EXT-X-MAP", ignoreCase = true) -> fmp4 = true
                line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> ended = true
                line.startsWith("#") -> Unit
                expectSegment -> {
                    segments += resolve(baseUrl, line)
                    expectSegment = false
                }
            }
        }
        return HlsMediaPlaylist(segments = segments, live = !ended, encrypted = encrypted, fragmentedMp4 = fmp4)
    }

    /** Resolves a playlist reference against the playlist's own URL (lenient about odd characters). */
    fun resolve(baseUrl: String, reference: String): String =
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(reference)) reference
        else runCatching { URL(URL(baseUrl), reference).toString() }.getOrElse { baseUrl.substringBeforeLast('/') + "/" + reference }
}
