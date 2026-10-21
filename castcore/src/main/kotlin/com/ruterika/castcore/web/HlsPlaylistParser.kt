package com.ruterika.castcore.web

import kotlinx.serialization.Serializable

/** Kind of an HLS playlist. */
@Serializable
enum class HlsPlaylistType {
    /** Multivariant ("master") playlist listing variants with `#EXT-X-STREAM-INF`. */
    MASTER,

    /** Media playlist listing segments with `#EXTINF`. */
    MEDIA,
}

/** One `#EXT-X-STREAM-INF` entry of a master playlist. */
@Serializable
data class HlsVariant(
    /** Absolute URL of the variant's media playlist. */
    val url: String,
    /** Peak `BANDWIDTH` in bits per second (0 if missing). */
    val bandwidth: Long = 0,
    /** `AVERAGE-BANDWIDTH` in bits per second, if present. */
    val averageBandwidth: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Raw `CODECS` attribute, e.g. `avc1.640028,mp4a.40.2`. */
    val codecs: String? = null,
    val frameRate: Double? = null,
    /** `AUDIO` group id referencing [HlsRendition.groupId]. */
    val audioGroup: String? = null,
    /** `SUBTITLES` group id referencing [HlsRendition.groupId]. */
    val subtitleGroup: String? = null,
    /** `VIDEO-RANGE` (SDR, PQ, HLG), if present. */
    val videoRange: String? = null,
) {
    /** `true` if the variant declares a resolution or a video codec. */
    val hasVideo: Boolean
        get() = height != null || width != null || HlsPlaylistParser.codecsContainVideo(codecs) == true

    /** Human readable label such as `1080p` or `128 kbps`. */
    val label: String
        get() = when {
            height != null -> "${height}p"
            bandwidth > 0 -> "${bandwidth / 1000} kbps"
            else -> "auto"
        }
}

/** Type of an `#EXT-X-MEDIA` rendition. */
@Serializable
enum class HlsRenditionType { AUDIO, VIDEO, SUBTITLES, CLOSED_CAPTIONS }

/** One `#EXT-X-MEDIA` alternative rendition (audio language, subtitle track...). */
@Serializable
data class HlsRendition(
    val type: HlsRenditionType,
    val groupId: String? = null,
    val name: String? = null,
    val language: String? = null,
    /** Absolute URL of the rendition playlist; `null` when muxed into the variant (or for CC). */
    val url: String? = null,
    val isDefault: Boolean = false,
    val autoSelect: Boolean = false,
    val forced: Boolean = false,
    /** `CHANNELS` (audio only), e.g. `2` or `6`. */
    val channels: String? = null,
)

/** Encryption info from `#EXT-X-KEY` / `#EXT-X-SESSION-KEY`. */
@Serializable
data class HlsEncryption(
    /** `AES-128`, `SAMPLE-AES` or `SAMPLE-AES-CTR`. */
    val method: String,
    val keyUrl: String? = null,
    /** `KEYFORMAT`; `identity` (or absent) means clear-key AES which Media3 can play. */
    val keyFormat: String? = null,
) {
    /** `true` for DRM key systems (FairPlay, Widevine, PlayReady), i.e. not plain AES. */
    val isDrm: Boolean
        get() {
            val f = keyFormat?.lowercase()
            return f != null && f != "identity"
        }
}

/** Result of [HlsPlaylistParser.parse]. */
@Serializable
data class HlsPlaylist(
    val type: HlsPlaylistType,
    /** URL the playlist was loaded from (used to resolve relative URIs). */
    val baseUrl: String,
    /** Variants of a master playlist, in document order. Empty for media playlists. */
    val variants: List<HlsVariant> = emptyList(),
    /** `#EXT-X-MEDIA TYPE=AUDIO` renditions. */
    val audioRenditions: List<HlsRendition> = emptyList(),
    /** `#EXT-X-MEDIA TYPE=SUBTITLES` (and CLOSED-CAPTIONS) renditions. */
    val subtitleRenditions: List<HlsRendition> = emptyList(),
    /**
     * `true` for a live/event media playlist without `#EXT-X-ENDLIST`; `false` for VOD;
     * `null` for a master playlist (unknown until a media playlist is loaded).
     */
    val isLive: Boolean? = null,
    /** Sum of `#EXTINF` durations for a VOD media playlist; `null` otherwise. */
    val durationMs: Long? = null,
    /** `#EXT-X-TARGETDURATION` in seconds (media playlists). */
    val targetDurationSec: Int? = null,
    /** Number of media segments (media playlists). */
    val segmentCount: Int = 0,
    /** `#EXT-X-PLAYLIST-TYPE` (`VOD` / `EVENT`), if present. */
    val playlistType: String? = null,
    /** First non-NONE `#EXT-X-KEY` / `#EXT-X-SESSION-KEY`, if any. */
    val encryption: HlsEncryption? = null,
    /** Best guess whether the stream carries video (`false` for audio-only streams). */
    val hasVideo: Boolean = true,
    /** Absolute URLs of the media segments (media playlists only; capped). */
    val segmentUrls: List<String> = emptyList(),
) {
    /** `true` for master playlists. */
    val isMaster: Boolean get() = type == HlsPlaylistType.MASTER

    /** `true` when an `#EXT-X-KEY` with a method other than NONE is present. */
    val isEncrypted: Boolean get() = encryption != null

    /** Highest resolution (then bandwidth) variant, or `null` for media playlists. */
    val bestVariant: HlsVariant?
        get() = variants.maxWithOrNull(compareBy<HlsVariant>({ it.height ?: 0 }, { it.width ?: 0 }, { it.bandwidth }))

    /** All playlist URLs referenced by this master (variants + renditions). */
    val referencedPlaylistUrls: List<String>
        get() = variants.map { it.url } + audioRenditions.mapNotNull { it.url } + subtitleRenditions.mapNotNull { it.url }
}

/**
 * Lenient parser for HLS playlists (RFC 8216). Only what the detector needs is extracted; unknown
 * tags are ignored. Never needs network access.
 */
object HlsPlaylistParser {

    private const val MAX_SEGMENT_URLS = 2000
    private val VIDEO_CODEC_PREFIXES = listOf("avc", "hvc", "hev", "av01", "vp09", "vp9", "vp8", "dvh", "dva", "mp4v")
    private val AUDIO_CODEC_PREFIXES = listOf("mp4a", "ac-3", "ec-3", "ac-4", "opus", "flac", "mp3", "alac", "dts", "vorbis")
    private val AUDIO_SEGMENT_EXT = setOf("aac", "mp3", "m4a", "ac3", "ec3", "opus", "flac")

    /** `true` when [text] starts (ignoring BOM/whitespace) with `#EXTM3U`. */
    fun isHlsPlaylist(text: String): Boolean = text.trimStart('﻿', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

    /** Like [parse] but returns `null` instead of throwing on invalid input. */
    fun parseOrNull(text: String, baseUrl: String): HlsPlaylist? = try {
        parse(text, baseUrl)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Parses a playlist [text] downloaded from [baseUrl].
     *
     * @throws IllegalArgumentException if [text] is not an HLS playlist.
     */
    fun parse(text: String, baseUrl: String): HlsPlaylist {
        require(isHlsPlaylist(text)) { "Not an HLS playlist (missing #EXTM3U)" }
        val lines = text.trimStart('﻿').lines().map { it.trim() }.filter { it.isNotEmpty() }

        val variants = ArrayList<HlsVariant>()
        val audio = ArrayList<HlsRendition>()
        val subs = ArrayList<HlsRendition>()
        val segmentUrls = ArrayList<String>()
        var pendingStreamInf: Map<String, String>? = null
        var sawExtInf = false
        var sawTargetDuration = false
        var targetDuration: Int? = null
        var endList = false
        var playlistType: String? = null
        var totalSec = 0.0
        var segmentCount = 0
        var encryption: HlsEncryption? = null

        for (line in lines) {
            if (line.startsWith("#")) {
                val tag = line.substringBefore(':')
                val value = if (line.contains(':')) line.substringAfter(':') else ""
                when (tag) {
                    "#EXT-X-STREAM-INF" -> pendingStreamInf = parseAttributes(value)
                    "#EXT-X-MEDIA" -> parseRendition(parseAttributes(value), baseUrl)?.let { r ->
                        when (r.type) {
                            HlsRenditionType.AUDIO -> audio += r
                            HlsRenditionType.SUBTITLES, HlsRenditionType.CLOSED_CAPTIONS -> subs += r
                            HlsRenditionType.VIDEO -> Unit
                        }
                    }
                    "#EXTINF" -> {
                        sawExtInf = true
                        value.substringBefore(',').trim().toDoubleOrNull()?.let { if (it > 0) totalSec += it }
                    }
                    "#EXT-X-TARGETDURATION" -> {
                        sawTargetDuration = true
                        targetDuration = value.trim().toDoubleOrNull()?.toInt()
                    }
                    "#EXT-X-ENDLIST" -> endList = true
                    "#EXT-X-PLAYLIST-TYPE" -> playlistType = value.trim().uppercase()
                    "#EXT-X-KEY", "#EXT-X-SESSION-KEY" -> if (encryption == null) {
                        val attrs = parseAttributes(value)
                        val method = attrs["METHOD"]?.uppercase()
                        if (method != null && method != "NONE") {
                            encryption = HlsEncryption(
                                method = method,
                                keyUrl = attrs["URI"]?.let { uri -> if (uri.startsWith("skd:") || uri.startsWith("data:")) uri else WebUrls.resolve(baseUrl, uri) },
                                keyFormat = attrs["KEYFORMAT"],
                            )
                        }
                    }
                }
            } else {
                val inf = pendingStreamInf
                if (inf != null) {
                    variants += toVariant(inf, WebUrls.resolve(baseUrl, line))
                    pendingStreamInf = null
                } else {
                    segmentCount++
                    if (segmentUrls.size < MAX_SEGMENT_URLS) segmentUrls += WebUrls.resolve(baseUrl, line)
                }
            }
        }

        val isMaster = variants.isNotEmpty() || (!sawExtInf && !sawTargetDuration && (audio.isNotEmpty() || subs.isNotEmpty()))
        if (isMaster) {
            val hasVideo = when {
                variants.isEmpty() -> false
                variants.any { it.hasVideo } -> true
                variants.all { codecsContainVideo(it.codecs) == false } -> false
                else -> true
            }
            return HlsPlaylist(
                type = HlsPlaylistType.MASTER,
                baseUrl = baseUrl,
                variants = variants,
                audioRenditions = audio,
                subtitleRenditions = subs,
                isLive = null,
                encryption = encryption,
                hasVideo = hasVideo,
            )
        }

        val vod = endList || playlistType == "VOD"
        val audioOnly = segmentUrls.isNotEmpty() && segmentUrls.all {
            MediaUrlClassifier.extensionOf(WebUrls.pathOf(it)) in AUDIO_SEGMENT_EXT
        }
        return HlsPlaylist(
            type = HlsPlaylistType.MEDIA,
            baseUrl = baseUrl,
            isLive = !vod,
            durationMs = if (vod) Math.round(totalSec * 1000.0) else null,
            targetDurationSec = targetDuration,
            segmentCount = segmentCount,
            playlistType = playlistType,
            encryption = encryption,
            hasVideo = !audioOnly,
            segmentUrls = segmentUrls,
        )
    }

    /**
     * Returns `true` if [codecs] lists a video codec, `false` if it lists only audio/text codecs,
     * `null` if [codecs] is missing.
     */
    fun codecsContainVideo(codecs: String?): Boolean? {
        if (codecs.isNullOrBlank()) return null
        val list = codecs.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (list.any { c -> VIDEO_CODEC_PREFIXES.any { c.startsWith(it) } }) return true
        return if (list.all { c -> AUDIO_CODEC_PREFIXES.any { c.startsWith(it) } || c.startsWith("stpp") || c.startsWith("wvtt") }) false else null
    }

    /**
     * Parses an HLS attribute list (`KEY=VALUE,KEY="quoted, value"`). Keys are uppercased,
     * quotes are removed.
     */
    fun parseAttributes(list: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = list.length
        while (i < n) {
            while (i < n && (list[i] == ' ' || list[i] == ',')) i++
            val eq = list.indexOf('=', i)
            if (eq < 0) break
            val key = list.substring(i, eq).trim().uppercase()
            i = eq + 1
            val value: String
            if (i < n && list[i] == '"') {
                val close = list.indexOf('"', i + 1)
                if (close < 0) {
                    value = list.substring(i + 1)
                    i = n
                } else {
                    value = list.substring(i + 1, close)
                    i = close + 1
                }
            } else {
                val comma = list.indexOf(',', i)
                val end = if (comma < 0) n else comma
                value = list.substring(i, end).trim()
                i = end
            }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun toVariant(attrs: Map<String, String>, url: String): HlsVariant {
        var w: Int? = null
        var h: Int? = null
        attrs["RESOLUTION"]?.lowercase()?.split('x')?.takeIf { it.size == 2 }?.let {
            w = it[0].trim().toIntOrNull()
            h = it[1].trim().toIntOrNull()
        }
        return HlsVariant(
            url = url,
            bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0,
            averageBandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
            width = w,
            height = h,
            codecs = attrs["CODECS"],
            frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
            audioGroup = attrs["AUDIO"],
            subtitleGroup = attrs["SUBTITLES"],
            videoRange = attrs["VIDEO-RANGE"],
        )
    }

    private fun parseRendition(attrs: Map<String, String>, baseUrl: String): HlsRendition? {
        val type = when (attrs["TYPE"]?.uppercase()) {
            "AUDIO" -> HlsRenditionType.AUDIO
            "VIDEO" -> HlsRenditionType.VIDEO
            "SUBTITLES" -> HlsRenditionType.SUBTITLES
            "CLOSED-CAPTIONS" -> HlsRenditionType.CLOSED_CAPTIONS
            else -> return null
        }
        return HlsRendition(
            type = type,
            groupId = attrs["GROUP-ID"],
            name = attrs["NAME"],
            language = attrs["LANGUAGE"],
            url = attrs["URI"]?.let { WebUrls.resolve(baseUrl, it) },
            isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
            autoSelect = attrs["AUTOSELECT"].equals("YES", ignoreCase = true),
            forced = attrs["FORCED"].equals("YES", ignoreCase = true),
            channels = attrs["CHANNELS"],
        )
    }
}
