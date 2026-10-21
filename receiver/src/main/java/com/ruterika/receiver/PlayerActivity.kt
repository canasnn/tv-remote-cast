package com.ruterika.receiver

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.ruterika.protocol.EdgeType
import com.ruterika.protocol.Enqueue
import com.ruterika.protocol.Load
import com.ruterika.protocol.MediaItem
import com.ruterika.protocol.MediaKind
import com.ruterika.protocol.Message
import com.ruterika.protocol.Next
import com.ruterika.protocol.Pause
import com.ruterika.protocol.Play
import com.ruterika.protocol.PlaybackStatus
import com.ruterika.protocol.PlayerEvent
import com.ruterika.protocol.PlayerEventType
import com.ruterika.protocol.PlayerState
import com.ruterika.protocol.Previous
import com.ruterika.protocol.Protocol
import com.ruterika.protocol.RepeatMode
import com.ruterika.protocol.ResizeMode
import com.ruterika.protocol.Retry
import com.ruterika.protocol.Seek
import com.ruterika.protocol.SeekBy
import com.ruterika.protocol.SelectTrack
import com.ruterika.protocol.SetAutoAdvance
import com.ruterika.protocol.SetRepeatMode
import com.ruterika.protocol.SetResizeMode
import com.ruterika.protocol.SetSlideshow
import com.ruterika.protocol.SetSpeed
import com.ruterika.protocol.SetSubtitleOffset
import com.ruterika.protocol.SetSubtitleStyle
import com.ruterika.protocol.SetVideoTransform
import com.ruterika.protocol.Stop
import com.ruterika.protocol.SubtitleStyle
import com.ruterika.protocol.TrackInfo
import com.ruterika.protocol.TrackType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem as Media3Item

/**
 * Full-screen player. Opened by the play deep link (through the remote) or by
 * the service when it may draw overlays; takes its media from [PlayerHub].
 * Leaving the screen stops playback, like closing a video app.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var prefs: ReceiverPrefs
    private lateinit var playerView: PlayerView
    private lateinit var toastView: TextView
    private var player: ExoPlayer? = null

    private var sessionId: String? = null
    private var items: List<MediaItem> = emptyList()

    private var autoAdvance = true
    private var slideshowMs: Long? = SLIDESHOW_MS
    private var rotationDeg = 0
    private var zoom = 1f
    private var resizeMode = ResizeMode.FIT
    private var retries = 0
    private var retryJob: Job? = null
    private var hideToastJob: Job? = null
    /** Per item index: intro / outro skipping already done. */
    private val introDone = mutableSetOf<Int>()
    private val outroDone = mutableSetOf<Int>()
    /** Live subtitle timing of this session (positive = later), read by [SubtitleOffsetRenderer]. */
    @Volatile
    private var subtitleOffsetMs = 0L
    /** Up/Down moved into the on-screen buttons; until they hide, the arrows move between them. */
    private var controlsFocused = false
    /** Relative seeks in a row: where the run started, its total, and the seek waiting to run. */
    private var seekRunStartMs = 0L
    private var seekRunMs = 0L
    private var pendingSeekJob: kotlinx.coroutines.Job? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ReceiverPrefs(this)
        setContentView(R.layout.activity_player)
        playerView = findViewById<PlayerView>(R.id.player_view).apply {
            setShowNextButton(true)
            setShowPreviousButton(true)
            controllerShowTimeoutMs = 3000
            // Controls hidden: the arrows are back to seeking (see dispatchKeyEvent).
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility != View.VISIBLE) controlsFocused = false
            })
            // D-pad on the seek bar moves one seek step per press.
            findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.setKeyTimeIncrement(SEEK_STEP_MS)
        }
        toastView = findViewById(R.id.toast)
        applySubtitleStyle(prefs.subtitleStyle)

        PlayerHub.onPlayerVisible(true)
        lifecycleScope.launch { PlayerHub.commands.collect(::onCommand) }
        lifecycleScope.launch {
            while (true) {
                checkIntroOutro()
                publishStatus()
                delay(STATUS_INTERVAL_MS)
            }
        }
        PlayerHub.takePendingLoad()?.let(::load)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PlayerHub.takePendingLoad()?.let(::load)
    }

    override fun onStop() {
        super.onStop()
        // The user left (Home, another app). Stop like any video app would.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        saveCurrentPosition()
        player?.release()
        player = null
        PlayerHub.onPlayerVisible(false)
        PlayerHub.emit(PlayerEvent(PlayerEventType.PLAYER_CLOSED))
        super.onDestroy()
    }

    /**
     * Remote keys work like YouTube on a TV: Left/Right seek, OK plays/pauses, Up/Down go to the
     * on-screen buttons (only then do the arrows move between them), Back closes the buttons
     * first and the player after.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_BACK && playerView.isControllerFullyVisible) {
            if (event.action == KeyEvent.ACTION_UP) playerView.hideController()
            return true
        }
        if (p != null && !controlsFocused) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (down && event.repeatCount == 0) {
                        if (p.isPlaying) p.pause() else resume(p)
                        playerView.showController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (p.isCurrentMediaItemSeekable) {
                    if (down) {
                        val step = holdSeekStep(event.repeatCount)
                        if (step > 0) seekBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                        playerView.showController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) {
                        controlsFocused = true
                        playerView.showController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP ->
                    if (down && p.hasNextMediaItem()) { p.seekToNextMediaItem(); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN ->
                    if (down && p.hasPreviousMediaItem()) { p.seekToPreviousMediaItem(); return true }
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    /**
     * Seek of one Left/Right key event: a press is one step; held down (key repeats arrive about
     * 20 times a second) it seeks on every other repeat and speeds up after a few seconds.
     */
    private fun holdSeekStep(repeatCount: Int): Long = when {
        repeatCount == 0 -> SEEK_STEP_MS
        repeatCount % 2 == 1 -> 0L
        repeatCount < 40 -> SEEK_STEP_MS
        else -> SEEK_STEP_MS * 3
    }

    // ── Commands ────────────────────────────────────────────────────────────

    private fun onCommand(message: Message) {
        val p = player
        when (message) {
            is Load -> { pendingSeekJob?.cancel(); load(message) }
            is Enqueue -> p?.addMediaSources(message.items.map(::toSource))?.also { items = items + message.items }
            is Play -> p?.let(::resume)
            is Pause -> p?.pause()
            is Stop -> finish()
            is Next -> p?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
            is Previous -> p?.seekToPreviousMediaItem()
            is Seek -> { pendingSeekJob?.cancel(); p?.seekTo(message.positionMs) }
            is SeekBy -> seekBy(message.deltaMs)
            is SetSpeed -> p?.playbackParameters = PlaybackParameters(message.speed.coerceIn(0.25f, 4f))
            is SelectTrack -> selectTrack(message)
            is SetSubtitleStyle -> applySubtitleStyle(message.style)
            is SetSubtitleOffset -> setSubtitleOffset(message.offsetMs)
            is Retry -> p?.let { retry(it, manual = true) }
            is SetRepeatMode -> p?.repeatMode = message.mode.toMedia3()
            is SetAutoAdvance -> { autoAdvance = message.enabled; p?.pauseAtEndOfMediaItems = !message.enabled }
            is SetVideoTransform -> { rotationDeg = ((message.rotationDeg % 360) + 360) % 360; zoom = message.zoom.coerceIn(0.5f, 3f); applyTransform() }
            is SetResizeMode -> { resizeMode = message.mode; playerView.resizeMode = message.mode.toMedia3() }
            is SetSlideshow -> setSlideshow(message.intervalMs)
            else -> Unit
        }
        publishStatus()
    }

    /** Play also restarts a finished queue and recovers from errors. */
    private fun resume(p: ExoPlayer) {
        when {
            p.playbackState == Player.STATE_ENDED -> { p.seekToDefaultPosition(0); p.play() }
            p.playerError != null || p.playbackState == Player.STATE_IDLE -> retry(p, manual = true)
            else -> p.play()
        }
    }

    private fun retry(p: ExoPlayer, manual: Boolean) {
        if (manual) retries = 0
        val position = p.currentPosition
        p.prepare()
        if (!p.isCurrentMediaItemLive && position > 0) p.seekTo(position)
        p.play()
    }

    /**
     * Relative seek (remote arrows, the phone's skip buttons). Presses in a row add up, like
     * YouTube: the total and the target time show on screen right away, and the player seeks
     * once, shortly after the last press, instead of reloading for every press.
     */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (!p.isCurrentMediaItemSeekable) return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val running = pendingSeekJob?.isActive == true && (seekRunMs > 0) == (deltaMs > 0)
        if (!running) {
            pendingSeekJob?.cancel()
            seekRunStartMs = p.currentPosition
            seekRunMs = 0L
        }
        seekRunMs += deltaMs
        val target = (seekRunStartMs + seekRunMs).coerceIn(0L, duration)
        val seconds = seekRunMs / 1000
        showToast((if (seconds > 0) "+${seconds}s" else "${seconds}s") + " · " + formatTime(target))
        pendingSeekJob?.cancel()
        pendingSeekJob = lifecycleScope.launch {
            delay(SEEK_COMMIT_DELAY_MS)
            player?.seekTo(target)
        }
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun load(load: Load) {
        saveCurrentPosition()
        retryJob?.cancel()
        retries = 0
        introDone.clear()
        outroDone.clear()
        headerSteps.clear()
        sessionId = load.sessionId
        items = load.items
        subtitleOffsetMs = load.subtitleOffsetMs
        autoAdvance = load.autoAdvance
        val startIndex = load.startIndex.coerceIn(0, items.lastIndex)

        val p = player ?: createPlayer().also { player = it }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(load.preferredAudioLanguage)
            .setPreferredTextLanguage(load.preferredTextLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverrides()
            .build()
        applyQualityHint(p, items[startIndex])
        p.pauseAtEndOfMediaItems = !load.autoAdvance
        p.repeatMode = load.repeatMode.toMedia3()
        val startPosition = load.startPositionMs ?: prefs.savedPosition(items[startIndex].url)
        p.setMediaSources(items.map(::toSource), startIndex, startPosition)
        p.playWhenReady = load.autoplay
        p.prepare()
    }

    private fun createPlayer(): ExoPlayer {
        val renderers = SubtitleOffsetRenderersFactory(this) { subtitleOffsetMs * 1000L }
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            // Keeps 50 s ahead topped up (min = max), so forward seeks land in the buffer, and
            // starts again quickly after a seek outside it (1 s of data, 2 s after a stall).
            .setBufferDurationsMs(50_000, 50_000, 1_000, 2_000)
            // The last 30 s stay in memory from their keyframe: seeking back within them needs no download.
            .setBackBuffer(30_000, true)
            .build()
        return ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setMediaSourceFactory(sourceFactory(emptyMap()))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: Media3Item?, reason: Int) {
                        retries = 0
                        applyQualityHint(exo, items.getOrNull(exo.currentMediaItemIndex))
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            PlayerHub.emit(PlayerEvent(PlayerEventType.ITEM_ENDED))
                        }
                        publishStatus()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            saveCurrentPosition()
                            PlayerHub.emit(PlayerEvent(PlayerEventType.QUEUE_ENDED))
                        }
                        if (playbackState == Player.STATE_READY) retries = 0
                        updateKeepScreenOn()
                        publishStatus()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateKeepScreenOn()
                        publishStatus()
                    }
                    override fun onTracksChanged(tracks: Tracks) = publishStatus()
                    override fun onRenderedFirstFrame() = applyTransform()

                    override fun onPlayerError(error: PlaybackException) {
                        publishStatus()
                        autoRetry(exo, error)
                    }
                })
            }
    }

    /** Live windows and flaky networks recover by themselves a few times. */
    private fun autoRetry(p: ExoPlayer, error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        // 401/403: the server didn't like our headers. Try other headers before giving up
        // Without Referer/Origin, a desktop browser UA, then no headers.
        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (status == 401 || status == 403) {
            val index = p.currentMediaItemIndex
            val item = items.getOrNull(index)
            val step = (headerSteps[index] ?: 0) + 1
            if (item != null && step <= HEADER_LADDER_STEPS) {
                headerSteps[index] = step
                PlayerHub.emit(PlayerEvent(PlayerEventType.RETRYING, "headers $step"))
                val position = p.currentPosition
                p.removeMediaItem(index)
                p.addMediaSource(index, sourceFactory(laddered(item.headers, step)).createMediaSource(toMedia3(item)))
                p.seekTo(index, if (p.isCurrentMediaItemLive) C.TIME_UNSET else position)
                p.prepare()
                p.play()
                return
            }
        }
        val network = error.cause is HttpDataSource.HttpDataSourceException ||
            error.errorCode in PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (!network || retries >= MAX_RETRIES) return
        retries++
        PlayerHub.emit(PlayerEvent(PlayerEventType.RETRYING, retries.toString()))
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            delay(1000L shl retries)
            retry(p, manual = false)
        }
    }

    /** Item index → header ladder step used for it (see [autoRetry]). */
    private val headerSteps = HashMap<Int, Int>()

    private fun laddered(headers: Map<String, String>, step: Int): Map<String, String> = when (step) {
        1 -> headers.filterKeys { !it.equals("Referer", true) && !it.equals("Origin", true) }
        2 -> headers.filterKeys { !it.equals("User-Agent", true) } + ("User-Agent" to DESKTOP_USER_AGENT)
        else -> emptyMap()
    }

    /**
     * Every item gets its own data source with its own headers, so HLS/DASH
     * segments and a prefetched next item never use another item's Referer/Cookie.
     */
    private fun sourceFactory(headers: Map<String, String>): DefaultMediaSourceFactory {
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DEFAULT_USER_AGENT
        // One shared OkHttp client: the connection stays open between requests, so the request
        // after a seek doesn't pay for a new connection (and TLS handshake) every time.
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(httpClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(LOAD_RETRIES))
    }

    /** rtsp:// gets its own source (rtmp is left out: its native library breaks 16 KB page support); everything else goes through the HTTP factory. */
    private fun toSource(item: MediaItem): MediaSource {
        val media = toMedia3(item)
        return when (Uri.parse(item.url).scheme?.lowercase()) {
            "rtsp" -> RtspMediaSource.Factory().createMediaSource(media)
            else -> sourceFactory(item.headers).createMediaSource(media)
        }
    }

    private fun toMedia3(item: MediaItem): Media3Item {
        val subtitles = item.subtitles.mapIndexed { index, sub ->
            Media3Item.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(sub.mimeType ?: subtitleMimeFor(sub.url))
                .setLanguage(sub.language)
                .setLabel(sub.label ?: sub.language)
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val builder = Media3Item.Builder()
        if (item.kind == MediaKind.IMAGE) builder.setImageDurationMs(imageDuration(item))
        return builder
            .setUri(item.url)
            .setMimeType(mediaMimeFor(item))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
            .setSubtitleConfigurations(subtitles)
            .build()
    }

    private fun imageDuration(item: MediaItem): Long =
        item.imageDurationMs ?: slideshowMs?.takeIf { items.count { it.kind == MediaKind.IMAGE } > 1 } ?: MANUAL_IMAGE_MS

    private fun setSlideshow(intervalMs: Long?) {
        slideshowMs = intervalMs
        val p = player ?: return
        items.forEachIndexed { index, item ->
            if (item.kind == MediaKind.IMAGE && index != p.currentMediaItemIndex) {
                p.removeMediaItem(index)
                p.addMediaSource(index, toSource(item))
            }
        }
    }

    private fun checkIntroOutro() {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val item = items.getOrNull(index) ?: return
        if (p.isCurrentMediaItemLive || !p.isPlaying) return
        val position = p.currentPosition
        if (item.skipIntroMs > 0 && index !in introDone && position >= maxOf(item.skipIntroAfterMs, INTRO_MIN_START_MS)) {
            introDone += index
            val end = item.skipIntroAfterMs + item.skipIntroMs
            if (position < end) {
                p.seekTo(end)
                showToast("Intro skipped")
                PlayerHub.emit(PlayerEvent(PlayerEventType.INTRO_SKIPPED))
            }
        }
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: return
        if (item.skipOutroMs > 0 && index !in outroDone && duration - position <= item.skipOutroMs && p.hasNextMediaItem()) {
            outroDone += index
            p.seekToNextMediaItem()
            showToast("Outro skipped")
            PlayerHub.emit(PlayerEvent(PlayerEventType.OUTRO_SKIPPED))
        }
    }

    private fun selectTrack(command: SelectTrack) {
        val p = player ?: return
        val c3Type = when (command.trackType) {
            TrackType.AUDIO -> C.TRACK_TYPE_AUDIO
            TrackType.TEXT -> C.TRACK_TYPE_TEXT
            TrackType.VIDEO -> C.TRACK_TYPE_VIDEO
        }
        val builder = p.trackSelectionParameters.buildUpon()
        val id = command.trackId
        when {
            // Video "Auto": let adaptive selection pick again.
            id == null && command.trackType == TrackType.VIDEO -> builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            id == null -> builder.setTrackTypeDisabled(c3Type, true)
            else -> {
                val (groupIndex, trackIndex) = id.split(':').map { it.toIntOrNull() ?: -1 }
                val group = p.currentTracks.groups.getOrNull(groupIndex) ?: return
                builder.setTrackTypeDisabled(c3Type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            }
        }
        p.trackSelectionParameters = builder.build()
    }

    /** Holds an adaptive stream at the quality picked on the phone (if any). */
    private fun applyQualityHint(p: ExoPlayer, item: MediaItem?) {
        val b = p.trackSelectionParameters.buildUpon()
        val height = item?.videoHeight?.takeIf { it > 0 }
        val bitrate = item?.videoBitrate?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        when {
            height != null -> b.setMaxVideoSize(Int.MAX_VALUE, height).setMinVideoSize(0, height)
                .setMaxVideoBitrate(Int.MAX_VALUE).setMinVideoBitrate(0)
            bitrate != null -> b.clearVideoSizeConstraints().setMinVideoSize(0, 0)
                .setMaxVideoBitrate(bitrate).setMinVideoBitrate(bitrate)
            else -> b.clearVideoSizeConstraints().setMinVideoSize(0, 0).setMaxVideoBitrate(Int.MAX_VALUE).setMinVideoBitrate(0)
        }
        p.trackSelectionParameters = b.build()
    }

    /** Picked up by the text renderer on its next frame; nothing is reloaded. */
    private fun setSubtitleOffset(offsetMs: Long) {
        if (offsetMs == subtitleOffsetMs) return
        subtitleOffsetMs = offsetMs
        val seconds = "%.1f".format(java.util.Locale.US, offsetMs / 1000.0)
        showToast("Subtitles ${if (offsetMs > 0) "+" else ""}$seconds s")
    }

    private fun applySubtitleStyle(style: SubtitleStyle) {
        val view = playerView.subtitleView ?: return
        val edge = when (style.edgeType) {
            EdgeType.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            EdgeType.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            EdgeType.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            EdgeType.RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
            EdgeType.DEPRESSED -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
        }
        val family = style.fontFamily?.let { Typeface.create(it, if (style.bold) Typeface.BOLD else Typeface.NORMAL) }
            ?: if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.setApplyEmbeddedStyles(style.useEmbeddedStyles)
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(
            CaptionStyleCompat(
                style.foregroundColor.toInt(),
                style.backgroundColor.toInt(),
                style.windowColor.toInt(),
                edge,
                style.edgeColor.toInt(),
                family
            )
        )
        view.setFractionalTextSize(style.textSizeFraction)
        view.setBottomPaddingFraction(style.bottomPaddingFraction.coerceIn(0f, 0.9f))
    }

    /** Rotation and zoom on the video TextureView (fits rotated video inside the screen). */
    private fun applyTransform() {
        val surface = playerView.videoSurfaceView as? TextureView ?: return
        surface.post {
            val w = surface.width.toFloat()
            val h = surface.height.toFloat()
            val fit = if (rotationDeg % 180 != 0 && w > 0 && h > 0) minOf(w / h, h / w) else 1f
            surface.rotation = rotationDeg.toFloat()
            surface.scaleX = fit * zoom
            surface.scaleY = fit * zoom
        }
    }

    /** Keep the TV awake while playing, buffering or showing photos; let it sleep otherwise. */
    private fun updateKeepScreenOn() {
        val p = player
        val image = items.getOrNull(p?.currentMediaItemIndex ?: -1)?.kind == MediaKind.IMAGE
        val keep = p != null && (p.isPlaying || p.playbackState == Player.STATE_BUFFERING || (image && p.playbackState == Player.STATE_READY))
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showToast(text: String) {
        toastView.text = text
        toastView.visibility = View.VISIBLE
        hideToastJob?.cancel()
        hideToastJob = lifecycleScope.launch {
            delay(1500)
            toastView.visibility = View.GONE
        }
    }

    // ── Status ──────────────────────────────────────────────────────────────

    private fun publishStatus() {
        val p = player
        if (p == null) {
            PlayerHub.publish(PlaybackStatus(sessionId = sessionId, state = PlayerState.LOADING))
            return
        }
        val state = when {
            p.playerError != null -> PlayerState.ERROR
            p.playbackState == Player.STATE_BUFFERING -> PlayerState.BUFFERING
            p.playbackState == Player.STATE_ENDED -> PlayerState.ENDED
            p.playbackState == Player.STATE_IDLE -> PlayerState.IDLE
            p.isPlaying -> PlayerState.PLAYING
            else -> PlayerState.PAUSED
        }
        val index = p.currentMediaItemIndex
        PlayerHub.publish(
            PlaybackStatus(
                sessionId = sessionId,
                state = state,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
                bufferedMs = p.bufferedPosition.coerceAtLeast(0),
                speed = p.playbackParameters.speed,
                itemIndex = index,
                itemCount = p.mediaItemCount,
                title = items.getOrNull(index)?.title,
                isLive = p.isCurrentMediaItemLive,
                tracks = trackInfo(p.currentTracks),
                errorMessage = p.playerError?.let { it.message ?: it.errorCodeName },
                errorCode = p.playerError?.errorCodeName,
                seekable = p.isCurrentMediaItemSeekable && !p.isCurrentMediaItemLive,
                repeatMode = when (p.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
                autoAdvance = autoAdvance,
                rotationDeg = rotationDeg,
                zoom = zoom,
                resizeMode = resizeMode,
                slideshowIntervalMs = slideshowMs
            )
        )
    }

    private fun trackInfo(tracks: Tracks): List<TrackInfo> = buildList {
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                C.TRACK_TYPE_TEXT -> TrackType.TEXT
                C.TRACK_TYPE_VIDEO -> TrackType.VIDEO
                else -> return@forEachIndexed
            }
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val label = when (type) {
                    TrackType.VIDEO -> buildString {
                        if (format.height > 0) append("${format.height}p") else append("Video ${i + 1}")
                        if (format.bitrate > 0) append(" · ${format.bitrate / 1000} kbps")
                    }
                    else -> format.label ?: format.language ?: "${type.name.lowercase()} ${size + 1}"
                }
                add(
                    TrackInfo(
                        id = "$groupIndex:$i",
                        type = type,
                        label = label,
                        language = format.language,
                        selected = group.isTrackSelected(i)
                    )
                )
            }
        }
    }

    private fun saveCurrentPosition() {
        val p = player ?: return
        val item = items.getOrNull(p.currentMediaItemIndex) ?: return
        if (p.isCurrentMediaItemLive || item.kind == MediaKind.IMAGE) return
        prefs.savePosition(item.url, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
    }

    private fun RepeatMode.toMedia3() = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

    private fun ResizeMode.toMedia3() = when (this) {
        ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        ResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        ResizeMode.FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        ResizeMode.FIXED_HEIGHT -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    }

    companion object {
        private const val STATUS_INTERVAL_MS = 1000L
        private const val SEEK_STEP_MS = 5_000L

        /** Shared by every stream of the process, so connections are reused. */
        private val httpClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
        /** Seek this long after the last arrow press (presses in between add up). */
        private const val SEEK_COMMIT_DELAY_MS = 450L
        private const val SLIDESHOW_MS = 5_000L
        private const val MANUAL_IMAGE_MS = 6 * 60 * 60 * 1000L
        private const val INTRO_MIN_START_MS = 5_000L
        private const val MAX_RETRIES = 3
        /** Per segment/manifest load retries before an error surfaces. */
        private const val LOAD_RETRIES = 6
        /** Header variants tried after a 401/403 (see [laddered]). */
        private const val HEADER_LADDER_STEPS = 3
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(Uri.parse(Protocol.playLink(sessionId)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun mediaMimeFor(item: MediaItem): String? {
            val mime = item.mimeType?.lowercase()
            val path = Uri.parse(item.url).path.orEmpty().lowercase()
            return when {
                item.kind == MediaKind.IMAGE -> mime?.takeUnless { it.endsWith("/*") } ?: MimeTypes.IMAGE_JPEG
                mime != null && ("mpegurl" in mime || "m3u" in mime) -> MimeTypes.APPLICATION_M3U8
                mime != null && "dash" in mime -> MimeTypes.APPLICATION_MPD
                path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                // Let ExoPlayer sniff progressive containers itself.
                else -> null
            }
        }

        fun subtitleMimeFor(url: String): String {
            val path = Uri.parse(url).path.orEmpty().lowercase()
            return when {
                path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                path.endsWith(".ttml") || path.endsWith(".dfxp") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }
    }
}
