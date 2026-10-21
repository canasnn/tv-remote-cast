package com.ruterika.castcore.web

import kotlinx.serialization.Serializable

/** What a URL most likely points to, from the TV player's point of view. */
@Serializable
enum class MediaClass {
    /** HLS playlist (master or media). */
    HLS,

    /** MPEG-DASH manifest. */
    DASH,

    /** A complete video file (mp4, webm, mkv, mov, standalone ts...). */
    PROGRESSIVE_VIDEO,

    /** A complete audio file (mp3, m4a, aac, ogg, flac...). */
    AUDIO,

    /** A subtitle file (vtt, srt, ass/ssa, ttml). */
    SUBTITLE,

    /** An image (posters, thumbnails, sprites). */
    IMAGE,

    /** A chunk of an adaptive stream (ts/m4s/aac segment, byte-range fragment). Never listed. */
    SEGMENT,

    /** Could be media but the URL alone does not tell; a HEAD probe should decide. */
    MAYBE,

    /** Not media (or blocked). */
    NONE;

    /** `true` for classes that can be offered to the user as a playable item. */
    val isPlayable: Boolean get() = this == HLS || this == DASH || this == PROGRESSIVE_VIDEO || this == AUDIO
}

/**
 * Classifies URLs (optionally with a known MIME type) seen by the in-app browser.
 *
 * The decision uses, in order: the ad blocker, the MIME type, the path extension (query and
 * fragment ignored), manifest hints hidden in the path or query (`?file=x.m3u8`,
 * `/manifest(format=m3u8-aapl)`, `mime=video/mp4`), segment naming patterns and finally keyword
 * heuristics that yield [MediaClass.MAYBE].
 */
object MediaUrlClassifier {

    private val HLS_MIMES = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl",
        "audio/mpegurl", "audio/x-mpegurl", "video/mpegurl", "video/x-mpegurl",
    )
    private val DASH_MIMES = setOf("application/dash+xml", "video/vnd.mpeg.dash.mpd")
    private val SUBTITLE_MIMES = setOf(
        "text/vtt", "application/x-subrip", "text/srt", "application/srt", "text/x-srt",
        "text/x-ssa", "text/x-ass", "application/x-ass", "application/x-ssa",
        "application/ttml+xml", "text/ttml",
    )
    private val UNKNOWN_BINARY_MIMES = setOf(
        "application/octet-stream", "binary/octet-stream", "application/binary",
        "application/x-binary", "application/force-download", "application/download",
    )
    private val SEGMENT_MIMES = setOf("video/iso.segment", "audio/iso.segment")

    private val VIDEO_EXT = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "3gp", "3g2", "ogv", "wmv", "mpg",
        "mpeg", "m2ts", "mts", "f4v", "divx", "vob",
    )
    private val AUDIO_EXT = setOf("mp3", "m4a", "ogg", "oga", "opus", "flac", "wav", "weba", "wma", "mka", "ac3", "eac3")
    private val SUBTITLE_EXT = setOf("vtt", "webvtt", "srt", "ass", "ssa", "ttml", "dfxp", "sub")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "bmp", "avif", "heic")
    private val ALWAYS_SEGMENT_EXT = setOf("m4s", "fmp4", "cmfv", "cmfa", "cmft", "m4f", "m4v_seg")
    private val NON_MEDIA_EXT = setOf(
        "js", "mjs", "css", "html", "htm", "php", "asp", "aspx", "jsp", "json", "xml", "txt",
        "woff", "woff2", "ttf", "otf", "eot", "map", "wasm", "pdf", "zip", "gz",
    )

    private val FILE_PARAMS = setOf("file", "src", "url", "source", "video", "v", "f", "stream", "path")
    private val SCRIPT_EXT = setOf("php", "asp", "aspx", "jsp", "cgi", "do")

    /** Path tokens that suggest an extension-less URL might serve media. */
    private val MAYBE_TOKENS = setOf(
        "video", "videos", "stream", "streams", "media", "hls", "dash", "manifest", "playlist",
        "videoplayback", "get_file", "getvideo", "get_video", "movie", "movies", "mp4", "m3u8",
        "vod",
    )

    private val SEGMENT_PREFIX = Regex(
        "^(seg|segment|chunk|frag|fragment|filesequence|sq|init)[-_.]?\\d+.*",
    )
    private val ENDS_WITH_NUMBER = Regex(".*[-_.]\\d+$")
    private val PURE_NUMBER = Regex("^\\d+$")
    private val NUMBERED_TOKEN = Regex("(^|[-_.])\\d{2,}([-_.]|$)")
    private val INIT_SEGMENT = Regex("^(init|initialization|header)([-_.].*)?$")
    private val PATH_RANGE = Regex("/range/\\d+-\\d+")
    private val SMOOTH_MANIFEST = Regex("/manifest\\(([^)]*)\\)", RegexOption.IGNORE_CASE)

    /**
     * Classifies [url].
     *
     * @param url absolute URL as requested by the page.
     * @param mimeType optional `Content-Type` (parameters such as `; charset=` are ignored).
     * @param adBlocker requests to blocked hosts are classified [MediaClass.NONE]; pass `null` to
     *   skip the check (the collector does this to mark ads instead of dropping them).
     */
    fun classify(url: String, mimeType: String? = null, adBlocker: AdBlocker? = AdBlocker.default()): MediaClass {
        val u = url.trim()
        if (!WebUrls.isHttp(u)) return MediaClass.NONE
        if (adBlocker != null && adBlocker.isBlocked(u)) return MediaClass.NONE

        val pathClass = classifyByPath(u)
        val mime = normalizeMime(mimeType) ?: return pathClass
        return when {
            mime in HLS_MIMES -> MediaClass.HLS
            mime in DASH_MIMES -> MediaClass.DASH
            mime in SEGMENT_MIMES -> MediaClass.SEGMENT
            mime in SUBTITLE_MIMES -> MediaClass.SUBTITLE
            mime == "video/mp2t" -> if (pathClass == MediaClass.PROGRESSIVE_VIDEO) MediaClass.PROGRESSIVE_VIDEO else MediaClass.SEGMENT
            mime.startsWith("video/") -> when (pathClass) {
                MediaClass.SEGMENT, MediaClass.HLS, MediaClass.DASH -> pathClass
                else -> MediaClass.PROGRESSIVE_VIDEO
            }
            mime.startsWith("audio/") -> when (pathClass) {
                MediaClass.SEGMENT, MediaClass.HLS, MediaClass.DASH, MediaClass.PROGRESSIVE_VIDEO -> pathClass
                else -> MediaClass.AUDIO
            }
            mime.startsWith("image/") -> MediaClass.IMAGE
            mime in UNKNOWN_BINARY_MIMES -> when (pathClass) {
                MediaClass.NONE, MediaClass.IMAGE -> MediaClass.MAYBE
                else -> pathClass
            }
            mime == "text/plain" -> when (pathClass) {
                // Some servers send playlists/subtitles as text/plain.
                MediaClass.HLS, MediaClass.DASH, MediaClass.SUBTITLE -> pathClass
                else -> MediaClass.NONE
            }
            // html, json, javascript, css, fonts...: trust the server
            else -> MediaClass.NONE
        }
    }

    /** Lowercased MIME type without parameters, or `null` when blank. */
    fun normalizeMime(mimeType: String?): String? {
        val m = mimeType?.substringBefore(';')?.trim()?.lowercase()
        return if (m.isNullOrEmpty()) null else m
    }

    /** Returns the MIME type commonly used for [mediaClass] / [url], for the TV player hint. */
    fun guessMime(url: String, mediaClass: MediaClass): String? {
        val ext = extensionOf(WebUrls.pathOf(url))
        return when (mediaClass) {
            MediaClass.HLS -> "application/x-mpegURL"
            MediaClass.DASH -> "application/dash+xml"
            MediaClass.PROGRESSIVE_VIDEO -> when (ext) {
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "ts", "m2ts", "mts" -> "video/mp2t"
                "mov" -> "video/quicktime"
                "mp4", "m4v" -> "video/mp4"
                else -> null
            }
            MediaClass.AUDIO -> when (ext) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "ogg", "oga", "opus" -> "audio/ogg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                else -> null
            }
            MediaClass.SUBTITLE -> when (ext) {
                "vtt", "webvtt" -> "text/vtt"
                "srt" -> "application/x-subrip"
                "ass", "ssa" -> "text/x-ssa"
                "ttml", "dfxp" -> "application/ttml+xml"
                else -> null
            }
            else -> null
        }
    }

    private val SUBTITLE_PARAMS = setOf("srt", "vtt", "sub", "subs", "subtitle", "subtitles", "linksub", "sub1_file", "caption", "captions")
    private val NUMBERED_SUB_PARAM = Regex("^(c|sub|subtitle)[1-9]_file$")
    private val NOT_SUBTITLE_EXT = setOf("m3u8", "m3u", "mpd", "ts", "m4s", "js", "mjs", "css", "html", "htm", "woff", "woff2", "json")

    /**
     * Subtitle URLs passed in the query of [url] (`?sub=https://x/en.vtt`, `srt=`, `vtt=`,
     * `linksub=`, `c1_file`..`c9_file`...). Relative values are resolved against [url]. Any other
     * parameter whose value is an absolute URL to a subtitle file counts too.
     */
    fun subtitleUrlsInQuery(url: String): List<String> {
        val q = WebUrls.queryOf(url)
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase()
        if (!(lower.contains("sub") || lower.contains("srt") || lower.contains("vtt") || lower.contains("_file") ||
                lower.contains("caption") || lower.contains(".ass") || lower.contains(".ttml"))
        ) return emptyList()
        val out = LinkedHashSet<String>()
        for ((rawName, rawValue) in WebUrls.queryParams(url)) {
            val name = rawName.lowercase()
            val value = rawValue.trim()
            if (value.isEmpty()) continue
            val named = name in SUBTITLE_PARAMS || NUMBERED_SUB_PARAM.matches(name)
            val absolute = WebUrls.isHttp(value)
            val ext = extensionOf(if (absolute) WebUrls.pathOf(value) else value.substringBefore('?').substringBefore('#'))
            val mediaExt = ext in VIDEO_EXT || ext in AUDIO_EXT || ext in IMAGE_EXT || ext in NOT_SUBTITLE_EXT
            when {
                named && absolute && !mediaExt -> out += value
                named && ext in SUBTITLE_EXT -> out += WebUrls.resolve(url, value)
                absolute && ext in SUBTITLE_EXT && ext != "sub" -> out += value
            }
        }
        return out.filter { WebUrls.isHttp(it) }
    }

    /** Extension of the last path segment, lowercase, or `""`. */
    internal fun extensionOf(path: String): String {
        val last = path.substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        return if (dot >= 0 && dot < last.length - 1) last.substring(dot + 1).lowercase() else ""
    }

    private fun classifyByPath(url: String): MediaClass {
        val path = WebUrls.pathOf(url)
        val lowerPath = path.lowercase()
        val fileName = lowerPath.substringAfterLast('/')
        val ext = extensionOf(lowerPath)
        val baseName = if (ext.isEmpty()) fileName else fileName.substring(0, fileName.length - ext.length - 1)
        val params by lazy { WebUrls.queryParams(url) }
        val byteRange by lazy { isByteRangeFragment(lowerPath, params) }

        // Smooth Streaming style manifests, e.g. /video.ism/manifest(format=m3u8-aapl)
        SMOOTH_MANIFEST.find(lowerPath)?.let { m ->
            val fmt = m.groupValues[1]
            return when {
                fmt.contains("m3u8") -> MediaClass.HLS
                fmt.contains("mpd") -> MediaClass.DASH
                else -> MediaClass.NONE
            }
        }

        when {
            ext == "m3u8" || ext == "m3u" -> return MediaClass.HLS
            ext == "mpd" -> return MediaClass.DASH
            ext in ALWAYS_SEGMENT_EXT -> return MediaClass.SEGMENT
            ext == "ts" -> return if (looksLikeSegment(baseName, strict = false) || byteRange) MediaClass.SEGMENT else MediaClass.PROGRESSIVE_VIDEO
            ext == "aac" -> return if (looksLikeSegment(baseName, strict = false) || byteRange) MediaClass.SEGMENT else MediaClass.AUDIO
            ext in VIDEO_EXT -> return if (byteRange || looksLikeSegment(baseName, strict = true)) MediaClass.SEGMENT else MediaClass.PROGRESSIVE_VIDEO
            ext in AUDIO_EXT -> return if (byteRange || looksLikeSegment(baseName, strict = true)) MediaClass.SEGMENT else MediaClass.AUDIO
            ext in SUBTITLE_EXT -> return if (SEGMENT_PREFIX.matches(baseName)) MediaClass.SEGMENT else MediaClass.SUBTITLE
            ext in IMAGE_EXT -> return MediaClass.IMAGE
        }

        // Manifest hidden in the query: /play.php?file=movie.m3u8, ?src=https://x/y.mpd
        for ((name, value) in params) {
            val v = value.lowercase()
            val vPath = if (WebUrls.isHttp(v)) WebUrls.pathOf(v) else v.substringBefore('?')
            val vExt = extensionOf(vPath)
            when {
                vExt == "m3u8" -> return MediaClass.HLS
                vExt == "mpd" -> return MediaClass.DASH
                vExt in VIDEO_EXT && name.lowercase() in FILE_PARAMS && (ext.isEmpty() || ext in SCRIPT_EXT) ->
                    return MediaClass.PROGRESSIVE_VIDEO
            }
            if (name.equals("mime", true) || name.equals("mimetype", true) || name.equals("type", true) || name.equals("content_type", true)) {
                val asMime = normalizeMime(v)
                if (asMime != null && asMime.contains('/')) {
                    when {
                        asMime in HLS_MIMES -> return MediaClass.HLS
                        asMime in DASH_MIMES -> return MediaClass.DASH
                        asMime.startsWith("video/") -> return if (byteRange) MediaClass.SEGMENT else MediaClass.PROGRESSIVE_VIDEO
                        asMime.startsWith("audio/") -> return if (byteRange) MediaClass.SEGMENT else MediaClass.AUDIO
                    }
                }
            }
        }

        // .m3u8 somewhere in the path (e.g. /hls/master.m3u8/token/abc)
        if (lowerPath.contains(".m3u8/") || (lowerPath.contains("/m3u8/") && ext.isEmpty())) return MediaClass.HLS
        if (lowerPath.contains(".mpd/")) return MediaClass.DASH

        // Vimeo-style JSON manifests (master.json / playlist.json) are not directly playable but
        // usually sit next to an HLS equivalent: let a probe decide.
        if (fileName == "master.json" || (fileName == "playlist.json" && lowerPath.contains("/video/"))) return MediaClass.MAYBE

        if (ext in NON_MEDIA_EXT) return MediaClass.NONE
        if (ext.isEmpty() || ext.length > 5) {
            if (byteRange) return MediaClass.SEGMENT
            val tokens = lowerPath.split('/', '-', '_', '.', '(', ')').filter { it.isNotEmpty() }
            if (tokens.any { it in MAYBE_TOKENS }) return MediaClass.MAYBE
        }
        return MediaClass.NONE
    }

    private fun isByteRangeFragment(lowerPath: String, params: List<Pair<String, String>>): Boolean {
        if (PATH_RANGE.containsMatchIn(lowerPath)) return true
        for ((name, value) in params) {
            when (name.lowercase()) {
                "range", "byterange", "bytes" -> if (value.matches(Regex("\\d+-\\d*"))) return true
                "bytestart", "byte_start", "byteend", "byte_end" -> if (value.isNotEmpty() && value.all { it.isDigit() }) return true
            }
        }
        return false
    }

    /**
     * `seg-12-v1-a1`, `segment_5`, `chunk-stream0-00012`, `00012`, `index_3_00045`...
     * With [strict] only explicit segment prefixes count (used for mp4/mp3 which are usually
     * whole files); otherwise any numbered chunk name does.
     */
    private fun looksLikeSegment(baseName: String, strict: Boolean): Boolean {
        if (SEGMENT_PREFIX.matches(baseName) || INIT_SEGMENT.matches(baseName)) return true
        if (strict) return false
        return PURE_NUMBER.matches(baseName) || ENDS_WITH_NUMBER.matches(baseName) || NUMBERED_TOKEN.containsMatchIn(baseName)
    }
}
