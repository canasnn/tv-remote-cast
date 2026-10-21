package com.canasnn.tvremote.cast.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.subtitles.SubtitlePreparer
import com.ruterika.castcore.library.SavedMedia
import com.ruterika.castcore.settings.CastRepeat
import com.ruterika.castcore.settings.PhonePlayerSettings
import com.ruterika.castcore.settings.ResumeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * The in-app (phone) player: an ExoPlayer inside a MediaSessionService, so it
 * keeps playing in the background with notification and lock screen controls.
 */
@OptIn(UnstableApi::class)
class PhonePlayerService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val settings = CastHub.settings.value
        val phone = settings.phonePlayer
        val (minMs, maxMs, playMs, rebufferMs) = phone.bufferDurationsMs.toList()
        val trackSelector = DefaultTrackSelector(this).apply {
            val params = buildUponParameters()
            settings.preferredAudioLanguage.takeIf { it.isNotBlank() }?.let { params.setPreferredAudioLanguage(it) }
            if (phone.allowAllTracks) {
                // Don't drop what the decoder claims it can't do.
                params.setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
            }
            setParameters(params)
        }
        val renderers = SubtitleOffsetRenderersFactory(this) { PhonePlayer.subtitleOffsetMs.value * 1000L }
        val player = ExoPlayer.Builder(this, renderers.setEnableDecoderFallback(true))
            .setTrackSelector(trackSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(minMs, maxMs, playMs, rebufferMs)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, PhonePlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        PhonePlayer.attach(this, player)
        // Remember positions like casting does.
        scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                PhonePlayer.savePosition()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        PhonePlayer.savePosition()
        scope.cancel()
        PhonePlayer.detach()
        session?.run {
            player.release()
            release()
        }
        session = null
        PhoneCache.release()
        super.onDestroy()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 15_000L
    }
}

/**
 * Optional intro/outro skipping for one [PhonePlayer.play] call (per-playlist
 * skip), in seconds. The intro starts [introAfterSec] into each item and lasts
 * [introSec]; the last [outroSec] of each item are skipped to the next one.
 */
data class PhoneSkip(val introAfterSec: Int = 0, val introSec: Int = 0, val outroSec: Int = 0) {
    val isActive: Boolean get() = introSec > 0 || outroSec > 0
}

/** "Continue watching?" asked before the phone player starts (resume mode Ask). */
data class ResumeAsk(val items: List<SavedMedia>, val index: Int, val positionMs: Long, val skip: PhoneSkip?) {
    val media: SavedMedia get() = items[index]
}

/** An intro/outro skip that happens in [secondsLeft] unless cancelled. */
data class SkipNotice(val outro: Boolean, val secondsLeft: Int)

/** Entry points and state of the phone player (the UI and other screens use this). */
@OptIn(UnstableApi::class)
object PhonePlayer {

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private var items: List<SavedMedia> = emptyList()
    private val _current = MutableStateFlow<SavedMedia?>(null)
    val current: StateFlow<SavedMedia?> = _current.asStateFlow()

    private val _resumeAsk = MutableStateFlow<ResumeAsk?>(null)
    val resumeAsk: StateFlow<ResumeAsk?> = _resumeAsk.asStateFlow()

    /** Player volume 0..1, above 1 = boosted (see [PhonePlayerSettings.boostGainMb]). */
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    private val _tracks = MutableStateFlow(Tracks.EMPTY)
    val tracks: StateFlow<Tracks> = _tracks.asStateFlow()

    private val _skipNotice = MutableStateFlow<SkipNotice?>(null)
    val skipNotice: StateFlow<SkipNotice?> = _skipNotice.asStateFlow()

    private var skip: PhoneSkip? = null
    private var introHandled = false
    private var outroHandled = false

    private var enhancer: LoudnessEnhancer? = null
    private var enhancerSession = C.AUDIO_SESSION_ID_UNSET
    private var boostFailed = false

    private var appContext: Context? = null

    private val _subtitleOffsetMs = MutableStateFlow(0L)
    /** Live subtitle timing of the current queue (positive = later); reset for each new queue. */
    val subtitleOffsetMs: StateFlow<Long> = _subtitleOffsetMs.asStateFlow()

    fun setSubtitleOffset(offsetMs: Long) {
        _subtitleOffsetMs.value = offsetMs
    }

    private var pending: (() -> Unit)? = null
    private var pollJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val phoneSettings: PhonePlayerSettings get() = CastHub.settings.value.phonePlayer

    internal fun attach(context: Context, player: ExoPlayer) {
        appContext = context.applicationContext
        _player.value = player
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _current.value = items.getOrNull(player.currentMediaItemIndex)
                introHandled = false
                outroHandled = false
                _skipNotice.value = null
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) = applyVolume()

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _speed.value = playbackParameters.speed
            }

            override fun onTracksChanged(tracks: Tracks) {
                _tracks.value = tracks
            }
        })
        _speed.value = player.playbackParameters.speed
        pollJob = scope.launch {
            while (isActive) {
                delay(SKIP_POLL_MS)
                checkSkip()
            }
        }
        pending?.invoke()
        pending = null
    }

    internal fun detach() {
        pollJob?.cancel()
        pollJob = null
        releaseEnhancer()
        _skipNotice.value = null
        _tracks.value = Tracks.EMPTY
        _player.value = null
    }

    /**
     * Plays [items] on the phone from [startIndex]. [startPositionMs] -1 = resume
     * from the saved position (or ask first, when resume mode is "Ask").
     */
    fun play(
        context: Context,
        items: List<SavedMedia>,
        startIndex: Int = 0,
        startPositionMs: Long = -1L,
        skip: PhoneSkip? = null
    ) {
        if (items.isEmpty()) return
        val app = context.applicationContext
        appContext = app
        val index = startIndex.coerceIn(0, items.lastIndex)
        val saved = if (startPositionMs < 0) CastHub.savedPosition(items[index]) else 0L
        if (startPositionMs < 0 && needsResumePrompt(items[index], saved)) {
            _resumeAsk.value = ResumeAsk(items, index, saved, skip)
            ensureService(app)
        } else {
            _resumeAsk.value = null
            start(app, items, index, startPositionMs, skip)
        }
        context.startActivity(Intent(context, PhonePlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Answer to [resumeAsk]: a start position, or null to cancel. */
    fun answerResume(context: Context, positionMs: Long?) {
        val ask = _resumeAsk.value ?: return
        _resumeAsk.value = null
        if (positionMs != null) start(context.applicationContext, ask.items, ask.index, positionMs, ask.skip)
    }

    /** Plays on the phone when "Always play in app" is on, otherwise calls [cast]. */
    fun playOrCast(context: Context, items: List<SavedMedia>, index: Int, cast: (List<SavedMedia>, Int) -> Unit) {
        if (phoneSettings.alwaysPlayInApp) play(context, items, index) else cast(items, index)
    }

    /** A playlist on the phone, with its intro/outro skipping. */
    fun playPlaylist(context: Context, playlist: com.ruterika.castcore.library.Playlist, startIndex: Int = 0) {
        val items = playlist.items
        if (items.isEmpty()) return
        val skip = PhoneSkip(
            introAfterSec = (playlist.skipIntroAfterMs / 1000).toInt(),
            introSec = (playlist.skipIntroMs / 1000).toInt(),
            outroSec = (playlist.skipOutroMs / 1000).toInt()
        ).takeIf { it.isActive }
        play(context, items, startIndex.coerceIn(0, items.lastIndex), skip = skip)
    }

    /** "Always play in app" for a playlist: the phone player, otherwise [cast]. */
    fun playPlaylistOrCast(context: Context, playlist: com.ruterika.castcore.library.Playlist, index: Int, cast: (List<SavedMedia>, Int) -> Unit) {
        if (phoneSettings.alwaysPlayInApp) playPlaylist(context, playlist, index) else cast(playlist.items, index)
    }

    private fun needsResumePrompt(media: SavedMedia, saved: Long): Boolean {
        val s = CastHub.settings.value
        return s.resumePlayback && s.resumeMode == ResumeMode.ASK && !media.isLive && saved > MIN_RESUME_MS
    }

    private fun ensureService(app: Context) {
        if (_player.value == null) runCatching { app.startService(Intent(app, PhonePlayerService::class.java)) }
    }

    private fun start(app: Context, items: List<SavedMedia>, index: Int, startPositionMs: Long, skip: PhoneSkip?) {
        val run = {
            scope.launch { load(app, items, index, startPositionMs, skip) }
            Unit
        }
        if (_player.value != null) run() else {
            pending = run
            // Started from the foreground (user action); MediaSessionService goes
            // foreground by itself once playback starts.
            ensureService(app)
        }
    }

    private suspend fun load(context: Context, list: List<SavedMedia>, index: Int, startPositionMs: Long, skip: PhoneSkip?) {
        val player = _player.value ?: return
        val first = list[index]
        val s = CastHub.settings.value
        val position = when {
            startPositionMs >= 0 -> startPositionMs
            first.isLive || !s.resumePlayback || s.resumeMode != ResumeMode.ALWAYS -> 0L
            else -> CastHub.savedPosition(first)
        }
        // Subtitles go through the same normalizer as casting (charset, format, local URL).
        val (prepared, cache) = withContext(Dispatchers.IO) {
            list.map { item ->
                if (item.subtitles.isEmpty()) item
                else item.copy(subtitles = item.subtitles.map { SubtitlePreparer.prepare(context, it, item.headers) })
            } to PhoneCache.get(context, s.phonePlayer.cacheMb)
        }
        items = prepared
        this.skip = skip?.takeIf { it.isActive }
        introHandled = false
        outroHandled = false
        _subtitleOffsetMs.value = 0L
        _current.value = prepared[index]
        player.setMediaSources(prepared.map { source(context, it, cache) }, index, position)
        player.repeatMode = when (s.repeat) {
            CastRepeat.ONE -> Player.REPEAT_MODE_ONE
            CastRepeat.ALL -> Player.REPEAT_MODE_ALL
            CastRepeat.OFF -> Player.REPEAT_MODE_OFF
        }
        applyStartVolume(first)
        player.prepare()
        player.play()
        if (!first.isLive && first.mimeType?.startsWith("image") != true) CastHub.library.addRecent(first.copy(positionMs = position))
    }

    /** Each item gets its own HTTP headers (Referer/Cookie/User-Agent from detection). */
    private fun source(context: Context, item: SavedMedia, cache: Cache?): MediaSource {
        val headers = item.headers.filterKeys { !it.equals("Range", true) }
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        val path = Uri.parse(item.url).path.orEmpty().lowercase()
        val mime = item.mimeType?.lowercase()
        val mediaMime = when {
            mime != null && ("mpegurl" in mime || "m3u" in mime) -> MimeTypes.APPLICATION_M3U8
            mime != null && "dash" in mime -> MimeTypes.APPLICATION_MPD
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val upstream = DefaultDataSource.Factory(context, http)
        // The disk cache only helps progressive files (seeking back, re-watching); never local or live media.
        val dataSource: DataSource.Factory =
            if (cache != null && mediaMime == null && !item.isLive && item.url.startsWith("http", ignoreCase = true)) {
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            } else upstream
        val factory = DefaultMediaSourceFactory(dataSource)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))
        val subtitles = item.subtitles.mapIndexed { i, sub ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(sub.mimeType ?: MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(sub.language)
                .setLabel(sub.label ?: sub.language)
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val media = MediaItem.Builder()
            .setUri(item.url)
            .setMimeType(mediaMime)
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtworkUri(item.posterUrl?.let(Uri::parse))
                    .build()
            )
            .build()
        return when (Uri.parse(item.url).scheme?.lowercase()) {
            // Streams that don't go over HTTP.
            "rtsp" -> androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory().createMediaSource(media)
            else -> factory.createMediaSource(media)
        }
    }

    // ── Speed ───────────────────────────────────────────────────────────────

    fun setSpeed(speed: Float) {
        _player.value?.setPlaybackSpeed(speed)
    }

    // ── Volume ────

    private fun applyStartVolume(media: SavedMedia) {
        val p = phoneSettings
        val level = when {
            p.lowerVolumeBeforePlay -> PhonePlayerSettings.LOW_VOLUME
            p.rememberVolume -> p.volumeLevel
            else -> _volume.value
        }
        _volume.value = level.coerceIn(0f, p.maxVolume)
        val isAudio = media.mimeType?.startsWith("audio", ignoreCase = true) == true
        _muted.value = p.startMuted && !isAudio
        applyVolume()
        when {
            _muted.value -> toast("Muted · \"Always start muted\" is on")
            p.lowerVolumeBeforePlay -> toast("Volume lowered before playing")
        }
    }

    /** Sets the volume (0..max, max is 2 with boost). [persist] stores it when "Remember volume" is on. */
    fun setVolume(level: Float, persist: Boolean = false) {
        val p = phoneSettings
        _volume.value = level.coerceIn(0f, p.maxVolume)
        if (_volume.value > 0f) _muted.value = false
        applyVolume()
        if (persist) rememberVolume()
    }

    fun rememberVolume() {
        if (!phoneSettings.rememberVolume) return
        val level = _volume.value
        CastHub.settingsStore.update { it.copy(phonePlayer = it.phonePlayer.copy(volumeLevel = level)) }
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        applyVolume()
    }

    /** Pushes the volume to the player: up to 1 directly, above 1 through a LoudnessEnhancer. */
    fun applyVolume() {
        val player = _player.value ?: return
        val p = phoneSettings
        if (_volume.value > p.maxVolume) _volume.value = p.maxVolume
        val level = if (_muted.value) 0f else _volume.value
        player.volume = level.coerceAtMost(1f)
        val gain = p.boostGainMb(level)
        val sessionId = player.audioSessionId
        if (gain <= 0 || sessionId == C.AUDIO_SESSION_ID_UNSET) {
            releaseEnhancer()
            return
        }
        runCatching {
            val fx = enhancer?.takeIf { enhancerSession == sessionId } ?: run {
                releaseEnhancer()
                LoudnessEnhancer(sessionId).also { enhancer = it; enhancerSession = sessionId }
            }
            fx.setTargetGain(gain)
            fx.enabled = true
        }.onFailure {
            releaseEnhancer()
            if (!boostFailed) {
                boostFailed = true
                toast("Volume boost isn't supported on this phone")
            }
        }
    }

    private fun releaseEnhancer() {
        runCatching { enhancer?.release() }
        enhancer = null
        enhancerSession = C.AUDIO_SESSION_ID_UNSET
    }

    // ── Intro / outro skipping ──────────────────────────────────────────────

    private fun checkSkip() {
        val sk = skip
        val player = _player.value
        val item = items.getOrNull(player?.currentMediaItemIndex ?: -1)
        if (sk == null || player == null || item == null || item.isLive || !player.isCurrentMediaItemSeekable ||
            player.playbackState != Player.STATE_READY
        ) {
            _skipNotice.value = null
            return
        }
        val pos = player.currentPosition
        var notice: SkipNotice? = null
        if (sk.introSec > 0 && !introHandled) {
            val at = sk.introAfterSec * 1000L
            val end = at + sk.introSec * 1000L
            when {
                pos >= end -> introHandled = true
                pos >= at -> {
                    introHandled = true
                    player.seekTo(end)
                    toast("Intro skipped")
                }
                at - pos <= NOTICE_MS -> notice = SkipNotice(outro = false, secondsLeft = ceil((at - pos) / 1000.0).toInt())
            }
        }
        val duration = player.duration
        if (sk.outroSec > 0 && !outroHandled && duration != C.TIME_UNSET && duration > sk.outroSec * 1000L) {
            val at = duration - sk.outroSec * 1000L
            when {
                pos >= at -> {
                    outroHandled = true
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(duration)
                    toast("Outro skipped")
                }
                at - pos <= NOTICE_MS -> notice = SkipNotice(outro = true, secondsLeft = ceil((at - pos) / 1000.0).toInt())
            }
        }
        _skipNotice.value = notice
    }

    /** "Cancel skip": keeps playing through the announced intro/outro of this item. */
    fun cancelSkip() {
        when (_skipNotice.value?.outro) {
            true -> outroHandled = true
            false -> introHandled = true
            null -> return
        }
        _skipNotice.value = null
    }

    // ── Queue / position ────────────────────────────────────────────────────

    /** The phone player's queue and position, e.g. to hand it over to the TV. */
    fun snapshot(): Triple<List<SavedMedia>, Int, Long>? {
        val p = _player.value ?: return null
        if (items.isEmpty()) return null
        return Triple(items, p.currentMediaItemIndex.coerceIn(0, items.lastIndex), p.currentPosition)
    }

    fun savePosition() {
        val p = _player.value ?: return
        val item = items.getOrNull(p.currentMediaItemIndex) ?: return
        if (item.isLive || p.currentPosition <= 0) return
        CastHub.library.updateRecentPosition(item.url, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
    }

    fun stop(context: Context) {
        savePosition()
        _resumeAsk.value = null
        _skipNotice.value = null
        skip = null
        _player.value?.run { stop(); clearMediaItems() }
        context.stopService(Intent(context, PhonePlayerService::class.java))
    }

    private fun toast(text: String) {
        appContext?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() }
    }

    private const val MIN_RESUME_MS = 30_000L
    private const val SKIP_POLL_MS = 500L
    private const val NOTICE_MS = 5_000L
}
