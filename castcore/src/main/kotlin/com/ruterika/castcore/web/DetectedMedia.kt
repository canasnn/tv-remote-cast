package com.ruterika.castcore.web

import kotlinx.serialization.Serializable

/** How a [DetectedMedia] was found. */
@Serializable
enum class DetectionSource {
    /** A network request made by the WebView. */
    REQUEST,

    /** A `<video>`/`<audio>`/`<source>` element reported by the injected script. */
    JS_VIDEO_TAG,

    /** A player configuration (JW Player, video.js, og:video, JSON-LD...) read by the script. */
    JS_PLAYER_CONFIG,

    /** Added by the user (pasted URL). */
    MANUAL,
}

/** A subtitle track that belongs to a [DetectedMedia]. */
@Serializable
data class DetectedSubtitle(
    /** Absolute URL of the subtitle file (or HLS subtitle playlist). */
    val url: String,
    /** BCP-47 / ISO language code if known (`en`, `tr`...). */
    val language: String? = null,
    /** Display label (`English`, `Turkce (forced)`...). */
    val label: String? = null,
    /** MIME type (`text/vtt`, `application/x-subrip`, `text/x-ssa`, `application/x-mpegURL`...). */
    val mime: String? = null,
)

/**
 * A playable item detected on a web page, ready to be sent to the TV.
 *
 * [headers] already contains what the TV must send to fetch [url] (Referer, Origin,
 * User-Agent, Cookie when provided).
 */
@Serializable
data class DetectedMedia(
    /** Stable id: hash of the normalized [url] (see [WebUrls.stableId]). */
    val id: String,
    val url: String,
    val mediaClass: MediaClass,
    val mimeType: String? = null,
    /** Title from the element, player config or page title. */
    val title: String? = null,
    /** Page (or frame) the media was found on. */
    val pageUrl: String? = null,
    /** HTTP headers the player must send. */
    val headers: Map<String, String> = emptyMap(),
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Bits per second (best HLS variant bandwidth for masters). */
    val bitrate: Long? = null,
    /** Size in bytes from a HEAD probe, if known. */
    val contentLength: Long? = null,
    val posterUrl: String? = null,
    val subtitles: List<DetectedSubtitle> = emptyList(),
    /** Variants when this is an HLS master playlist. */
    val variants: List<HlsVariant> = emptyList(),
    /** Alternative audio renditions of an HLS master. */
    val audioTracks: List<HlsRendition> = emptyList(),
    /** HLS: `true` for a master playlist, `false` for a media playlist, `null` if not parsed. */
    val isMasterPlaylist: Boolean? = null,
    /** Epoch millis of the first detection. */
    val detectedAt: Long = 0,
    val source: DetectionSource = DetectionSource.REQUEST,
    /** Ad host or a short (< 30 s) non-live clip. */
    val isLikelyAd: Boolean = false,
    val isLive: Boolean = false,
    /** Encrypted with `AES-128`/`SAMPLE-AES` (clear key) or DRM. */
    val isEncrypted: Boolean = false,
    /** Protected by a DRM key system the TV cannot play without a license. */
    val isDrmProtected: Boolean = false,
    /** Where the page's own player was (its `currentTime`), if the page reported it. */
    val webPositionMs: Long? = null,
    /** This URL failed to play on the TV recently (listed last). */
    val previouslyFailed: Boolean = false,
) {
    companion object {
        /** Computes the [id] for [url]. */
        fun idFor(url: String): String = WebUrls.stableId(url)
    }
}

/** Kind of a [ProbeRequest]. */
@Serializable
enum class ProbeKind {
    /** Send a HEAD (or ranged GET) and report via [PageMediaCollector.onHeadProbeResult]. */
    HEAD,

    /** GET the HLS playlist text and report via [PageMediaCollector.onPlaylistLoaded]. */
    PLAYLIST,

    /** GET the DASH manifest and report via [PageMediaCollector.onDashManifestLoaded]. */
    DASH,
}

/** A network check the collector wants the caller (or [MediaProber]) to perform. */
@Serializable
data class ProbeRequest(
    val url: String,
    /** Headers to send (same as the ones the TV would use). */
    val headers: Map<String, String> = emptyMap(),
    val kind: ProbeKind = ProbeKind.HEAD,
)
