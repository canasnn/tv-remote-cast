package com.ruterika.castcore.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A page the user saved. */
@Serializable
data class Bookmark(
    val url: String,
    val title: String,
    val addedAt: Long,
    /** Favicon of the page ("https://host/favicon.ico" when the page gave none). */
    val iconUrl: String? = null
)

/** One visit in the browser history. */
@Serializable
data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long
)

/**
 * Visits of one page, keyed by its normalised full URL ([LibraryStore.pageKey]).
 * Older files kept one entry per host (url "https://host"); those
 * entries stay valid and merge with later visits of the site's root page.
 */
@Serializable
data class SiteVisits(
    val host: String,
    val url: String,
    val title: String,
    val count: Int,
    val lastVisitedAt: Long
)

/** Something that can be played on the TV again later. */
@Serializable
data class SavedMedia(
    val url: String,
    val title: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val pageUrl: String? = null,
    val posterUrl: String? = null,
    val subtitles: List<SavedSubtitle> = emptyList(),
    val isLive: Boolean = false,
    val durationMs: Long = 0L,
    /** Last known position, for resume. */
    val positionMs: Long = 0L,
    val addedAt: Long = 0L,
    /** HLS quality picked from the master playlist (the player is held to it). */
    val videoHeight: Int? = null,
    val videoBitrate: Long? = null
)

@Serializable
data class SavedSubtitle(
    val url: String,
    val language: String? = null,
    val label: String? = null,
    val mimeType: String? = null
)

/** A named playlist. */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    /** Old format (plain media, no ids); moved into [entries] when the library loads. */
    @SerialName("items")
    val legacyItems: List<SavedMedia> = emptyList(),
    val entries: List<PlaylistEntry> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** An item is dropped once it played to the end on the TV. */
    val autoRemovePlayed: Boolean = false,
    /** Intro/outro skipping for every item (0 = off). The intro starts at [skipIntroAfterMs]. */
    val skipIntroMs: Long = 0L,
    val skipIntroAfterMs: Long = 0L,
    val skipOutroMs: Long = 0L,
    /** Last order picked in the sort dialog (null = never sorted). */
    val sort: PlaylistSort? = null,
    val sortDescending: Boolean = false
) {
    /** The media in playlist order. */
    val items: List<SavedMedia> get() = entries.map { it.media }

    val hasIntroOutro: Boolean get() = skipIntroMs > 0 || skipOutroMs > 0

    /** First poster of the list, for the cover. */
    val coverUrl: String? get() = entries.firstNotNullOfOrNull { it.media.posterUrl }
}

/** One item of a playlist. The id stays stable when items move, so duplicates are fine. */
@Serializable
data class PlaylistEntry(
    val id: String,
    val media: SavedMedia,
    val addedAt: Long = 0L
)

@Serializable
enum class PlaylistSort { NAME, DATE_ADDED, SHUFFLE }

/** An IPTV source the user added. */
@Serializable
data class IptvSource(
    val id: String,
    val name: String,
    /** http(s) URL or a content:// URI of a picked file. */
    val location: String,
    val userAgent: String? = null,
    val addedAt: Long = 0L,
    val lastLoadedAt: Long = 0L,
    val channelCount: Int = 0,
    /** The user chose "load anyway" for content that does not look like an IPTV list. */
    val skipContentCheck: Boolean = false
)

/** A channel the user starred, kept independently of its source list. */
@Serializable
data class FavoriteChannel(
    val sourceId: String,
    val url: String,
    val title: String,
    val logo: String? = null,
    val group: String? = null,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
enum class DownloadStatus { QUEUED, DOWNLOADING, CONVERTING, PAUSED, COMPLETE, FAILED }

/** How the file is produced: copied as-is, converted (HLS/DASH → MP4), or HLS segments joined into one .ts. */
@Serializable
enum class DownloadFormat { ORIGINAL, MP4_FROM_HLS, MP4_FROM_DASH, TS_FROM_HLS }

@Serializable
data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val format: DownloadFormat = DownloadFormat.ORIGINAL,
    val headers: Map<String, String> = emptyMap(),
    val pageUrl: String? = null,
    val posterUrl: String? = null,
    /** content:// of the saved file (set once it is created). */
    val fileUri: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** Conversion progress 0..100, or -1 when unknown. */
    val convertPercent: Int = -1,
    val attempts: Int = 0,
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Subtitle saved next to the video once it is done ("Download subtitles too"). */
    val subtitleUrl: String? = null
)

@Serializable
data class LibraryData(
    val bookmarks: List<Bookmark> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val sites: List<SiteVisits> = emptyList(),
    val recentMedia: List<SavedMedia> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val iptvSources: List<IptvSource> = emptyList(),
    val favoriteChannels: List<FavoriteChannel> = emptyList(),
    val downloads: List<DownloadItem> = emptyList()
)
