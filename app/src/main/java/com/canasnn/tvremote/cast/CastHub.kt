package com.canasnn.tvremote.cast

import android.content.Context
import com.canasnn.tvremote.cast.subtitles.SubtitlePreparer
import com.canasnn.tvremote.receiver.ReceiverClient
import com.canasnn.tvremote.receiver.ReceiverManager
import com.ruterika.castcore.library.LibraryStore
import com.ruterika.castcore.library.Playlist
import com.ruterika.castcore.library.SavedMedia
import com.ruterika.castcore.library.SavedSubtitle
import com.ruterika.castcore.settings.CastRepeat
import com.ruterika.castcore.settings.CastSettings
import com.ruterika.castcore.settings.CastSettingsStore
import com.ruterika.castcore.settings.SubtitleEdge
import com.ruterika.protocol.AdjustVolume
import com.ruterika.protocol.EdgeType
import com.ruterika.protocol.Enqueue
import com.ruterika.protocol.MediaItem
import com.ruterika.protocol.MediaKind
import com.ruterika.protocol.Next
import com.ruterika.protocol.Pause
import com.ruterika.protocol.Play
import com.ruterika.protocol.PlaybackStatus
import com.ruterika.protocol.PlayerEvent
import com.ruterika.protocol.PlayerEventType
import com.ruterika.protocol.PlayerState
import com.ruterika.protocol.Previous
import com.ruterika.protocol.RepeatMode
import com.ruterika.protocol.ResizeMode
import com.ruterika.protocol.Retry
import com.ruterika.protocol.Seek
import com.ruterika.protocol.SeekBy
import com.ruterika.protocol.SelectTrack
import com.ruterika.protocol.SetAutoAdvance
import com.ruterika.protocol.SetMuted
import com.ruterika.protocol.SetRepeatMode
import com.ruterika.protocol.SetResizeMode
import com.ruterika.protocol.SetSlideshow
import com.ruterika.protocol.SetSpeed
import com.ruterika.protocol.SetSubtitleOffset
import com.ruterika.protocol.SetSubtitleStyle
import com.ruterika.protocol.SetVideoTransform
import com.ruterika.protocol.SetVolume
import com.ruterika.protocol.Stop
import com.ruterika.protocol.SubtitleStyle
import com.ruterika.protocol.SubtitleTrack
import com.ruterika.protocol.TrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Everything the cast side of the app shares: settings, the
 * library (bookmarks, history, recent media, playlists, IPTV sources) and
 * the current cast session on our TV receiver.
 */
object CastHub {

    /** What the phone is casting right now (null when nothing is). */
    data class Session(
        val items: List<SavedMedia>,
        val startIndex: Int,
        /** Timing shift applied to every subtitle of this session. */
        val subtitleOffsetMs: Long = 0L,
        /** The TV player closed or the queue finished. */
        val ended: Boolean = false,
        /** Set when [items] are one of the user's playlists. */
        val playlist: PlaylistOrigin? = null
    ) {
        fun current(status: PlaybackStatus): SavedMedia? = items.getOrNull(status.itemIndex) ?: items.getOrNull(startIndex)
        val isPhotos: Boolean get() = items.all { it.mimeType?.startsWith("image") == true }
    }

    /**
     * A cast that started from a playlist: its intro/outro skipping goes into
     * every item, and played items can be removed ("Remove played items").
     * [entryIds] line up with the session items.
     */
    data class PlaylistOrigin(
        val playlistId: String,
        val entryIds: List<String>,
        val skipIntroMs: Long = 0L,
        val skipIntroAfterMs: Long = 0L,
        val skipOutroMs: Long = 0L
    ) {
        companion object {
            fun of(playlist: Playlist) = PlaylistOrigin(
                playlistId = playlist.id,
                entryIds = playlist.entries.map { it.id },
                skipIntroMs = playlist.skipIntroMs,
                skipIntroAfterMs = playlist.skipIntroAfterMs,
                skipOutroMs = playlist.skipOutroMs
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context

    /** Intent action of the cast notification: open the now-playing screen. */
    const val ACTION_OPEN_NOW_PLAYING = "com.canasnn.tvremote.OPEN_NOW_PLAYING"

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** User-facing problems with TV commands (shown as a toast). */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private fun command(message: com.ruterika.protocol.Message): Boolean {
        val sent = ReceiverClient.send(message)
        if (!sent) _errors.tryEmit("Not connected to the TV receiver")
        return sent
    }

    private val _openNowPlaying = MutableStateFlow(false)
    /** Set by the notification tap; the navigation consumes it once the home screen is up. */
    val openNowPlayingRequest: StateFlow<Boolean> = _openNowPlaying.asStateFlow()
    fun requestOpenNowPlaying() { _openNowPlaying.value = true }
    fun consumeOpenNowPlaying() { _openNowPlaying.value = false }

    lateinit var settingsStore: CastSettingsStore
        private set
    lateinit var library: LibraryStore
        private set

    val settings: StateFlow<CastSettings> get() = settingsStore.settings
    val status: StateFlow<PlaybackStatus> get() = ReceiverClient.status

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16)
    /** TV-side events (intro skipped, retrying, player closed, …) for toasts. */
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    /** Accumulated skip while the user taps quickly (sent after a short pause). */
    private val _pendingSkipMs = MutableStateFlow(0L)
    val pendingSkipMs: StateFlow<Long> = _pendingSkipMs.asStateFlow()
    private var skipJob: Job? = null

    fun init(context: Context) {
        if (::library.isInitialized) return
        appContext = context.applicationContext
        val dir = File(appContext.filesDir, "cast")
        settingsStore = CastSettingsStore(File(dir, "settings.json"))
        library = LibraryStore(File(dir, "library.json"))
        library.deleteHistoryOlderThan(settings.value.historyAutoDeleteDays)

        // Keep the TV's subtitle look in sync with the phone settings.
        scope.launch {
            combine(
                ReceiverClient.state.map { it is ReceiverClient.State.Connected }.distinctUntilChanged(),
                settings.map { it.subtitleLook }.distinctUntilChanged()
            ) { connected, _ -> connected }.collect { connected ->
                if (connected) ReceiverClient.send(SetSubtitleStyle(currentSubtitleStyle()))
            }
        }
        // Remember playback positions for "continue where you left off".
        scope.launch {
            status.collect { st ->
                val item = _session.value?.current(st) ?: return@collect
                if (st.state == PlayerState.PLAYING && !st.isLive && st.positionMs > 0) {
                    library.updateRecentPosition(item.url, st.positionMs, st.durationMs)
                }
            }
        }
        // Keep the cast on disk: a restarted app takes over what is still playing on the TV.
        sessionFile = File(dir, "session.json")
        saved = com.ruterika.castcore.library.SavedCastSession.read(sessionFile)
        scope.launch {
            combine(_session, status) { session, st -> session to st }.collect { (session, st) ->
                when {
                    session == null || session.ended -> {
                        // Nothing playing here: adopt the TV's playback if it is our last cast.
                        val last = saved
                        if (session == null && last != null && st.playerVisible && st.sessionId == last.sessionId) {
                            val origin = last.playlistId?.let { id ->
                                library.data.value.playlists.firstOrNull { it.id == id }?.let(PlaylistOrigin::of)
                            }
                            _session.value = Session(last.items, last.startIndex, last.subtitleOffsetMs, playlist = origin)
                        } else if (session?.ended == true && last != null) {
                            forgetSavedSession()
                        }
                    }
                    st.sessionId != null && st.playerVisible -> {
                        val next = com.ruterika.castcore.library.SavedCastSession(
                            sessionId = st.sessionId!!,
                            items = session.items,
                            startIndex = session.startIndex,
                            subtitleOffsetMs = session.subtitleOffsetMs,
                            playlistId = session.playlist?.playlistId
                        )
                        if (next != saved) {
                            saved = next
                            com.ruterika.castcore.library.SavedCastSession.write(sessionFile, next)
                        }
                    }
                }
            }
        }
        // Which item played before the current one (for "Remove played items").
        scope.launch {
            status.collect { st ->
                val session = _session.value
                synchronized(transitions) {
                    if (session == null || st.sessionId == null || st.sessionId != trackedSessionId) {
                        trackedSessionId = st.sessionId
                        trackedIndex = st.itemIndex
                        transitions.clear()
                    } else if (st.itemIndex != trackedIndex) {
                        transitions.addLast(trackedIndex to System.currentTimeMillis())
                        while (transitions.size > 8) transitions.removeFirst()
                        trackedIndex = st.itemIndex
                    }
                }
                // Items reached by auto-advance count as played media too (resume, progress).
                val item = session?.current(st)
                if (session != null && item != null && !session.isPhotos && st.state == PlayerState.PLAYING && !st.isLive &&
                    library.data.value.recentMedia.none { it.url == item.url }
                ) library.addRecent(item.copy(positionMs = st.positionMs, durationMs = st.durationMs.coerceAtLeast(item.durationMs)))
            }
        }
        scope.launch {
            ReceiverClient.messages.filterIsInstance<PlayerEvent>().collect { event ->
                when (event.event) {
                    PlayerEventType.PLAYER_CLOSED -> _session.value = _session.value?.copy(ended = true)
                    // The item before the transition played to its end (or its outro was skipped).
                    PlayerEventType.ITEM_ENDED, PlayerEventType.OUTRO_SKIPPED -> scope.launch {
                        delay(TRANSITION_SETTLE_MS) // the status with the new index follows the event
                        val now = System.currentTimeMillis()
                        val from = synchronized(transitions) {
                            transitions.lastOrNull { now - it.second < TRANSITION_WINDOW_MS }?.first.also { transitions.clear() }
                        }
                        if (from != null) onItemPlayed(from)
                    }
                    PlayerEventType.QUEUE_ENDED -> onItemPlayed(status.value.itemIndex)
                    else -> Unit
                }
                _events.tryEmit(event)
            }
        }
        CastSessionService.observe(appContext)
    }

    // ── Playlists ───────────────────────────────────────────────────────────

    private val transitions = ArrayDeque<Pair<Int, Long>>()
    private var trackedSessionId: String? = null
    private var trackedIndex = 0

    @Volatile
    private var pendingPlaylist: Pair<List<SavedMedia>, PlaylistOrigin>? = null

    /**
     * Call right before casting [items] of a playlist (through the usual
     * prompts): the next [play] of exactly these items is tagged with the
     * playlist, so its intro/outro settings and "Remove played items" apply.
     */
    fun markPlaylistCast(items: List<SavedMedia>, playlist: Playlist) {
        pendingPlaylist = items to PlaylistOrigin.of(playlist)
    }

    /** Applies "Remove played items" to the session item at [index]. */
    private fun onItemPlayed(index: Int) {
        val origin = _session.value?.playlist ?: return
        val entryId = origin.entryIds.getOrNull(index) ?: return
        val playlist = library.playlist(origin.playlistId) ?: return
        if (playlist.autoRemovePlayed) library.removeEntries(playlist.id, setOf(entryId))
    }

    private fun MediaItem.withIntroOutro(origin: PlaylistOrigin?): MediaItem =
        if (origin == null || kind == MediaKind.IMAGE || isLive) this
        else copy(skipIntroMs = origin.skipIntroMs, skipIntroAfterMs = origin.skipIntroAfterMs, skipOutroMs = origin.skipOutroMs)

    // ── Starting playback ───────────────────────────────────────────────────

    /** Last saved position of [media], used by the resume prompt. */
    fun savedPosition(media: SavedMedia): Long =
        library.data.value.recentMedia.firstOrNull { it.url == media.url }?.positionMs ?: 0L

    /** True when something is on the TV right now (for the "already playing" dialog). */
    val isPlaying: Boolean
        get() = _session.value?.ended == false &&
            status.value.state in setOf(PlayerState.PLAYING, PlayerState.PAUSED, PlayerState.BUFFERING)

    /**
     * Cast [items] to the TV receiver. Subtitles are normalized on the phone
     * (charset, format) and served from the local server.
     *
     * @param startPositionMs where to start; -1 = decide by the resume setting.
     */
    suspend fun play(items: List<SavedMedia>, startIndex: Int = 0, startPositionMs: Long = -1L): ReceiverManager.PlayOutcome {
        if (items.isEmpty()) return ReceiverManager.PlayOutcome.Failed
        val index = startIndex.coerceIn(0, items.lastIndex)
        val first = items[index]
        val s = settings.value
        val resumeAt = when {
            startPositionMs >= 0 -> startPositionMs
            first.isLive || !s.resumePlayback || s.resumeMode == com.ruterika.castcore.settings.ResumeMode.NEVER -> 0L
            else -> savedPosition(first)
        }
        val photos = items.all { it.mimeType?.startsWith("image") == true }
        val origin = pendingPlaylist?.takeIf { it.first == items }?.second
            ?: _session.value?.takeIf { it.items == items }?.playlist
        pendingPlaylist = null
        val protocolItems = prepareSubtitles(items, 0L).map { it.served().toProtocol(photos).withIntroOutro(origin) }
        val outcome = ReceiverManager.play(
            items = protocolItems,
            startIndex = index,
            startPositionMs = resumeAt,
            autoAdvance = s.autoplayNext || photos,
            repeatMode = s.repeat.toProtocol(),
            preferredAudioLanguage = s.preferredAudioLanguage.ifBlank { null },
            preferredTextLanguage = s.subtitleLanguages.firstOrNull()
        )
        if (outcome == ReceiverManager.PlayOutcome.Started) {
            _session.value = Session(items, index, playlist = origin)
            if (!photos) library.addRecent(first.copy(positionMs = resumeAt))
            if (s.defaultSpeed != 1f && !photos) ReceiverClient.send(SetSpeed(s.defaultSpeed))
            if (photos) ReceiverClient.send(SetSlideshow(s.slideshowSeconds.takeIf { it > 0 }?.times(1000L)))
        }
        return outcome
    }

    /** Adds to the TV queue when something plays, otherwise starts playing. */
    suspend fun enqueue(items: List<SavedMedia>): ReceiverManager.PlayOutcome {
        val current = _session.value
        if (current == null || current.ended || !ReceiverClient.isConnected) return play(items)
        val prepared = prepareSubtitles(items, 0L).map { it.served().toProtocol(false) }
        if (!ReceiverClient.send(Enqueue(prepared))) return ReceiverManager.PlayOutcome.Failed
        _session.value = current.copy(items = current.items + items)
        return ReceiverManager.PlayOutcome.Started
    }

    // ── Subtitles ───────────────────────────────────────────────────────────

    /**
     * Adds [subtitle] (file, URL or OpenSubtitles download) to the item that
     * is playing and reloads it at the current position.
     */
    suspend fun attachSubtitle(subtitle: SavedSubtitle): Boolean {
        val session = _session.value ?: return false
        val st = status.value
        val index = st.itemIndex.coerceIn(0, session.items.lastIndex)
        val item = session.items[index]
        val updated = item.copy(subtitles = listOf(subtitle) + item.subtitles.filterNot { it.url == subtitle.url })
        val items = session.items.toMutableList().also { it[index] = updated }
        return reload(session.copy(items = items), index, st.positionMs)
    }

    /**
     * Shifts all subtitles of the current session (positive = later). The TV
     * re-times them live; nothing is reloaded.
     */
    fun setSubtitleOffset(offsetMs: Long): Boolean {
        val session = _session.value ?: return false
        _session.value = session.copy(subtitleOffsetMs = offsetMs)
        return command(SetSubtitleOffset(offsetMs))
    }

    private suspend fun reload(session: Session, index: Int, positionMs: Long): Boolean {
        val s = settings.value
        val protocolItems = prepareSubtitles(session.items, 0L)
            .map { it.served().toProtocol(session.isPhotos).withIntroOutro(session.playlist) }
        val outcome = ReceiverManager.play(
            items = protocolItems,
            startIndex = index,
            startPositionMs = positionMs,
            autoAdvance = s.autoplayNext,
            repeatMode = status.value.repeatMode,
            preferredAudioLanguage = s.preferredAudioLanguage.ifBlank { null },
            preferredTextLanguage = s.subtitleLanguages.firstOrNull(),
            subtitleOffsetMs = session.subtitleOffsetMs
        )
        if (outcome == ReceiverManager.PlayOutcome.Started) {
            _session.value = session.copy(startIndex = index, ended = false)
            return true
        }
        return false
    }

    private suspend fun prepareSubtitles(items: List<SavedMedia>, offsetMs: Long): List<SavedMedia> = coroutineScope {
        items.map { item ->
            async {
                if (item.subtitles.isEmpty()) item
                else item.copy(subtitles = item.subtitles.map { sub -> SubtitlePreparer.prepare(appContext, sub, item.headers, offsetMs) })
            }
        }.awaitAll()
    }

    // ── Transport ───────────────────────────────────────────────────────────

    /** Play/pause; also restarts after the end and retries after an error (the TV handles both). */
    fun togglePlay() {
        val state = status.value.state
        command(if (state == PlayerState.PLAYING || state == PlayerState.BUFFERING) Pause else Play)
    }

    /** Plain pause / play (phone calls); [togglePlay] is the user's button. */
    fun pause() = command(Pause)
    fun resume() = command(Play)

    fun skipForward() = skip(settings.value.skipForwardSeconds * 1000L)
    fun skipBack() = skip(-settings.value.skipBackSeconds * 1000L)

    /** Quick taps add up ("+0:45") and go out as one seek after 500 ms. */
    private fun skip(deltaMs: Long) {
        if (!status.value.seekable) return
        _pendingSkipMs.value += deltaMs
        skipJob?.cancel()
        skipJob = scope.launch {
            delay(SKIP_DEBOUNCE_MS)
            val total = _pendingSkipMs.value
            _pendingSkipMs.value = 0L
            if (total != 0L) command(SeekBy(total))
        }
    }

    fun seekTo(positionMs: Long) = command(Seek(positionMs))
    fun next() = command(Next)
    fun previous() = command(Previous)
    fun setSpeed(speed: Float) = command(SetSpeed(speed))
    fun selectTrack(type: TrackType, id: String?) = command(SelectTrack(type, id))
    fun retry() = command(Retry)

    fun cycleRepeat() {
        val next = when (status.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        command(SetRepeatMode(next))
        settingsStore.update { it.copy(repeat = CastRepeat.valueOf(next.name)) }
    }

    fun setAutoAdvance(enabled: Boolean) {
        command(SetAutoAdvance(enabled))
        settingsStore.update { it.copy(autoplayNext = enabled) }
    }

    fun setVideoTransform(rotationDeg: Int, zoom: Float) = command(SetVideoTransform(rotationDeg, zoom))
    fun setResizeMode(mode: ResizeMode) = command(SetResizeMode(mode))

    fun setSlideshow(seconds: Int) {
        settingsStore.update { it.copy(slideshowSeconds = seconds) }
        command(SetSlideshow(seconds.takeIf { it > 0 }?.times(1000L)))
    }

    // ── TV volume ───────────────────────────────────────────────────────────

    fun setVolume(level: Float) = command(SetVolume(level))
    fun adjustVolume(direction: Int) = command(AdjustVolume(direction))
    fun toggleMute() = command(SetMuted(!status.value.muted))

    fun stop() {
        forgetSavedSession()
        ReceiverClient.send(Stop)
        _session.value = null
    }

    /** The cast kept on disk for taking over after an app restart. */
    private lateinit var sessionFile: File
    @Volatile private var saved: com.ruterika.castcore.library.SavedCastSession? = null

    private fun forgetSavedSession() {
        saved = null
        if (::sessionFile.isInitialized) com.ruterika.castcore.library.SavedCastSession.write(sessionFile, null)
    }

    // ── Controller hints ────────────────────────────────────────────────────

    private val _helpTipsHidden = MutableStateFlow(false)
    /** "Hide this time" on the help tips banner: hidden until the app restarts. */
    val helpTipsHidden: StateFlow<Boolean> = _helpTipsHidden.asStateFlow()
    fun hideHelpTipsForNow() { _helpTipsHidden.value = true }
    fun showHelpTipsAgain() { _helpTipsHidden.value = false }

    /** Media URLs already asked "Did the video play?" in this run of the app. */
    private val playedAsked = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun shouldAskPlayed(url: String): Boolean = settings.value.askVideoPlayed && url !in playedAsked
    fun markPlayedAsked(url: String) { playedAsked += url }

    /** Forget an ended session (the now-playing screen was closed). */
    fun clearEnded() {
        if (_session.value?.ended == true) _session.value = null
    }

    fun currentSubtitleStyle(): SubtitleStyle {
        val look = settings.value.subtitleLook
        return SubtitleStyle(
            textSizeFraction = 0.0533f * look.sizePercent / 100f,
            foregroundColor = look.textColor,
            backgroundColor = look.lineBackground,
            windowColor = look.windowBackground,
            edgeType = when (look.edge) {
                SubtitleEdge.NONE -> EdgeType.NONE
                SubtitleEdge.OUTLINE -> EdgeType.OUTLINE
                SubtitleEdge.DROP_SHADOW -> EdgeType.DROP_SHADOW
                SubtitleEdge.RAISED -> EdgeType.RAISED
                SubtitleEdge.DEPRESSED -> EdgeType.DEPRESSED
            },
            edgeColor = look.edgeColor,
            bold = look.bold,
            // 90 ≈ media3's 0.08 from the bottom.
            bottomPaddingFraction = ((100 - look.verticalPosition.coerceIn(0, 100)) / 100f * 0.8f).coerceIn(0.02f, 0.9f),
            useEmbeddedStyles = look.useFileStyles,
            fontFamily = look.fontFamily
        )
    }

    /** Phone files (content://) are cast through the local media server. */
    private fun SavedMedia.served(): SavedMedia {
        if (!url.startsWith("content://") && !url.startsWith("file://")) return this
        val served = com.canasnn.tvremote.App.app?.buildCastUrl(android.net.Uri.parse(url), mimeType ?: "video/*", title)
        return if (served != null) copy(url = served) else this
    }

    private fun CastRepeat.toProtocol() = when (this) {
        CastRepeat.OFF -> RepeatMode.OFF
        CastRepeat.ONE -> RepeatMode.ONE
        CastRepeat.ALL -> RepeatMode.ALL
    }

    private fun SavedMedia.toProtocol(photos: Boolean): MediaItem = MediaItem(
        url = url,
        // "Send title to TV" off: the TV shows no name (the phone still does).
        title = title.takeIf { settings.value.sendTitleToTv },
        mimeType = mimeType,
        headers = headers,
        subtitles = subtitles.map { it.toProtocol() },
        posterUrl = posterUrl,
        isLive = isLive,
        videoHeight = videoHeight,
        videoBitrate = videoBitrate,
        kind = when {
            mimeType?.startsWith("audio") == true -> MediaKind.AUDIO
            photos || mimeType?.startsWith("image") == true -> MediaKind.IMAGE
            else -> MediaKind.VIDEO
        }
    )

    private fun SavedSubtitle.toProtocol() = SubtitleTrack(url = url, mimeType = mimeType, language = language, label = label)

    private const val SKIP_DEBOUNCE_MS = 500L
    private const val TRANSITION_SETTLE_MS = 1_500L
    private const val TRANSITION_WINDOW_MS = 6_000L
}
