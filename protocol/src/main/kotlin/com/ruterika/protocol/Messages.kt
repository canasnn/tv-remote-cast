package com.ruterika.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Every frame on the phone <-> TV WebSocket is one [Message] as JSON. */
@Serializable
sealed interface Message

// ── Phone -> TV ─────────────────────────────────────────────────────────────

/** First frame from the phone. The token was handed over by the start link. */
@Serializable @SerialName("hello")
data class Hello(
    val token: String,
    val clientName: String,
    val version: Int = Protocol.VERSION
) : Message

@Serializable @SerialName("getApps")
data class GetApps(val withIcons: Boolean = true) : Message

/** Load [items] and start [startIndex] at [startPositionMs]. */
@Serializable @SerialName("load")
data class Load(
    val sessionId: String,
    val items: List<MediaItem>,
    val startIndex: Int = 0,
    /** Where to start; null lets the receiver resume from its own saved position. */
    val startPositionMs: Long? = null,
    val autoplay: Boolean = true,
    /** Continue with the next item when one ends. */
    val autoAdvance: Boolean = true,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val preferredAudioLanguage: String? = null,
    val preferredTextLanguage: String? = null,
    /** Subtitle timing shift for this session, positive = later (see [SetSubtitleOffset]). */
    val subtitleOffsetMs: Long = 0L
) : Message

/** Append items to the queue that is playing. */
@Serializable @SerialName("enqueue")
data class Enqueue(val items: List<MediaItem>) : Message

/** Prepare the current item again (after an error) at the last position. */
@Serializable @SerialName("retry") data object Retry : Message

/** Absolute TV volume, 0..1. */
@Serializable @SerialName("setVolume")
data class SetVolume(val level: Float) : Message

/** One volume step up (+1) or down (-1). */
@Serializable @SerialName("adjustVolume")
data class AdjustVolume(val direction: Int) : Message

@Serializable @SerialName("setMuted")
data class SetMuted(val muted: Boolean) : Message

@Serializable @SerialName("setRepeatMode")
data class SetRepeatMode(val mode: RepeatMode) : Message

@Serializable @SerialName("setAutoAdvance")
data class SetAutoAdvance(val enabled: Boolean) : Message

/** Rotate (multiples of 90) and zoom (1 = fit) the video picture. */
@Serializable @SerialName("setVideoTransform")
data class SetVideoTransform(val rotationDeg: Int, val zoom: Float) : Message

@Serializable @SerialName("setResizeMode")
data class SetResizeMode(val mode: ResizeMode) : Message

/** Slideshow interval for photo queues; null = manual (next/previous only). */
@Serializable @SerialName("setSlideshow")
data class SetSlideshow(val intervalMs: Long?) : Message

@Serializable @SerialName("play") data object Play : Message
@Serializable @SerialName("pause") data object Pause : Message
@Serializable @SerialName("stop") data object Stop : Message
@Serializable @SerialName("next") data object Next : Message
@Serializable @SerialName("previous") data object Previous : Message
@Serializable @SerialName("getStatus") data object GetStatus : Message

@Serializable @SerialName("seek")
data class Seek(val positionMs: Long) : Message

/** Relative seek, e.g. +10 s / -10 s. */
@Serializable @SerialName("seekBy")
data class SeekBy(val deltaMs: Long) : Message

@Serializable @SerialName("setSpeed")
data class SetSpeed(val speed: Float) : Message

/** Select a track by id from [PlaybackStatus.tracks]; null id disables that type. */
@Serializable @SerialName("selectTrack")
data class SelectTrack(val trackType: TrackType, val trackId: String?) : Message

@Serializable @SerialName("subtitleStyle")
data class SetSubtitleStyle(val style: SubtitleStyle) : Message

/**
 * Shift subtitle timing live, without reloading the item. Positive = subtitles later
 * ("If you see subtitles before you hear dialog: add time"). Applies to every item of the session.
 */
@Serializable @SerialName("subtitleOffset")
data class SetSubtitleOffset(val offsetMs: Long) : Message

@Serializable @SerialName("ping")
data class Ping(val time: Long) : Message

// ── TV -> Phone ─────────────────────────────────────────────────────────────

@Serializable @SerialName("welcome")
data class Welcome(
    val deviceName: String,
    val version: Int,
    val appVersion: String,
    val sdkInt: Int,
    /** True when the receiver may start its player by itself (overlay permission). */
    val canSelfLaunch: Boolean
) : Message

@Serializable @SerialName("apps")
data class Apps(val apps: List<TvAppInfo>) : Message

/**
 * Answer to [Load]. When [needsLaunch] is true the player is not on screen
 * and the receiver could not start it; the phone sends [Protocol.playLink]
 * through the remote and the pending load starts as soon as the player opens.
 */
@Serializable @SerialName("loadResult")
data class LoadResult(val sessionId: String, val accepted: Boolean, val needsLaunch: Boolean) : Message

@Serializable @SerialName("status")
data class Status(val status: PlaybackStatus) : Message

@Serializable @SerialName("error")
data class ErrorMessage(val code: String, val message: String) : Message

@Serializable @SerialName("pong")
data class Pong(val time: Long) : Message

/** Something happened on the TV the phone may want to tell the user about. */
@Serializable @SerialName("event")
data class PlayerEvent(val event: PlayerEventType, val detail: String? = null) : Message

@Serializable
enum class PlayerEventType { INTRO_SKIPPED, OUTRO_SKIPPED, ITEM_ENDED, QUEUE_ENDED, PLAYER_CLOSED, RETRYING }

@Serializable
enum class RepeatMode { OFF, ONE, ALL }

@Serializable
enum class ResizeMode { FIT, FILL, ZOOM, FIXED_WIDTH, FIXED_HEIGHT }

// ── Shared models ───────────────────────────────────────────────────────────

@Serializable
data class MediaItem(
    val url: String,
    val title: String? = null,
    val mimeType: String? = null,
    /** HTTP headers the TV must send (Referer, User-Agent, Cookie, …). */
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val posterUrl: String? = null,
    val isLive: Boolean = false,
    val kind: MediaKind = MediaKind.VIDEO,
    /** Skip [skipIntroMs] once playback passes [skipIntroAfterMs] (playlist intro skipping). */
    val skipIntroMs: Long = 0L,
    val skipIntroAfterMs: Long = 0L,
    /** Jump to the next item when this much is left. */
    val skipOutroMs: Long = 0L,
    /** Photos: how long to show this one (null = receiver default). */
    val imageDurationMs: Long? = null,
    /** A quality chosen on the phone: keep adaptive streams at this height/bitrate. */
    val videoHeight: Int? = null,
    val videoBitrate: Long? = null
)

@Serializable
enum class MediaKind { VIDEO, AUDIO, IMAGE }

@Serializable
data class SubtitleTrack(
    val url: String,
    val mimeType: String? = null,
    val language: String? = null,
    val label: String? = null
)

@Serializable
data class TvAppInfo(
    /** "package/activity" */
    val component: String,
    val label: String,
    /** PNG, base64 (no wrap). */
    val iconPng: String? = null
)

@Serializable
enum class PlayerState { IDLE, LOADING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

@Serializable
enum class TrackType { AUDIO, TEXT, VIDEO }

@Serializable
data class TrackInfo(
    val id: String,
    val type: TrackType,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false
)

@Serializable
data class PlaybackStatus(
    val sessionId: String? = null,
    val state: PlayerState = PlayerState.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    val title: String? = null,
    val isLive: Boolean = false,
    val tracks: List<TrackInfo> = emptyList(),
    val errorMessage: String? = null,
    /** True while the player screen is on the TV. */
    val playerVisible: Boolean = false,
    val seekable: Boolean = false,
    val errorCode: String? = null,
    /** TV volume 0..1 (-1 when unknown). */
    val volume: Float = -1f,
    val muted: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val autoAdvance: Boolean = true,
    val rotationDeg: Int = 0,
    val zoom: Float = 1f,
    val resizeMode: ResizeMode = ResizeMode.FIT,
    val slideshowIntervalMs: Long? = null
)

@Serializable
data class SubtitleStyle(
    /** Fraction of the view height, e.g. 0.0533 (media3 default). */
    val textSizeFraction: Float = 0.0533f,
    val foregroundColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x00000000,
    val windowColor: Long = 0x00000000,
    val edgeType: EdgeType = EdgeType.OUTLINE,
    val edgeColor: Long = 0xFF000000,
    val bold: Boolean = false,
    /** Unused: timing goes through [SetSubtitleOffset] / [Load.subtitleOffsetMs]. */
    val offsetMs: Long = 0L,
    /** Distance from the bottom, fraction of the view height (media3 default 0.08). */
    val bottomPaddingFraction: Float = 0.08f,
    /** Keep colors/positions defined inside the subtitle file (ASS, VTT styles). */
    val useEmbeddedStyles: Boolean = true,
    /** null = default, otherwise "sans-serif", "serif", "monospace", "sans-serif-condensed". */
    val fontFamily: String? = null
)

@Serializable
enum class EdgeType { NONE, OUTLINE, DROP_SHADOW, RAISED, DEPRESSED }

object ProtocolJson {
    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(message: Message): String = json.encodeToString(Message.serializer(), message)

    /** Returns null for frames we cannot parse (e.g. an unknown type from a newer version). */
    fun decode(text: String): Message? =
        try { json.decodeFromString(Message.serializer(), text) } catch (_: Exception) { null }
}
