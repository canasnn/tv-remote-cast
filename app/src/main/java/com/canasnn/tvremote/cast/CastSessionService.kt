package com.canasnn.tvremote.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.canasnn.tvremote.MainActivity
import com.canasnn.tvremote.R
import com.ruterika.protocol.PlaybackStatus
import com.ruterika.protocol.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Lives while something is cast to the TV receiver:
 * - media notification + MediaSession (lockscreen, Bluetooth buttons),
 * - phone volume keys stay with the phone (TV volume is on the controller),
 * - wake + Wi-Fi locks so phone-served media and subtitles keep flowing.
 */
class CastSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null
    private var callMonitor: CallPauseMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "TvCast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = CastHub.togglePlay()
                override fun onPause() = CastHub.togglePlay()
                override fun onStop() { CastHub.stop() }
                override fun onSkipToNext() { CastHub.next() }
                override fun onSkipToPrevious() { CastHub.previous() }
                override fun onFastForward() = CastHub.skipForward()
                override fun onRewind() = CastHub.skipBack()
                override fun onSeekTo(pos: Long) { CastHub.seekTo(pos) }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        acquireLocks()
        startForegroundCompat(buildNotification(CastHub.status.value))
        scope.launch {
            combine(CastHub.status, CastHub.session, CastHub.settings.map { it.lockscreenArtwork }.distinctUntilChanged()) { st, _, _ -> st }
                .collect { st -> update(st) }
        }
        // Pause on phone calls (READ_PHONE_STATE is asked when the setting is turned on).
        val monitor = CallPauseMonitor(this).also { callMonitor = it }
        scope.launch {
            CastHub.settings.map { it.pauseOnCall }.distinctUntilChanged().collect { monitor.update(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> CastHub.togglePlay()
            ACTION_BACK -> CastHub.skipBack()
            ACTION_FORWARD -> CastHub.skipForward()
            ACTION_PREVIOUS -> CastHub.previous()
            ACTION_NEXT -> CastHub.next()
            ACTION_STOP -> CastHub.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        callMonitor?.release()
        session.isActive = false
        session.release()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    private fun update(st: PlaybackStatus) {
        val settings = CastHub.settings.value
        // Photos are not media playback: no lockscreen/media controls for them.
        val photos = CastHub.session.value?.isPhotos == true
        session.isActive = !photos
        val current = CastHub.session.value?.current(st)
        val title = st.title ?: current?.title ?: "Casting to TV"

        // Metadata + artwork (poster) for the lockscreen.
        val poster = current?.posterUrl?.takeIf { settings.lockscreenArtwork }
        if (poster != artworkUrl) {
            artworkUrl = poster
            artwork = null
            if (poster != null) loadArtwork(poster)
        }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "On your TV")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (st.isLive) -1 else st.durationMs)
                .apply { artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        val playing = st.state == PlayerState.PLAYING || st.state == PlayerState.BUFFERING
        var actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP
        if (st.seekable && st.durationMs > 0) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
        }
        if (st.itemCount > 1) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    when (st.state) {
                        PlayerState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
                        PlayerState.BUFFERING, PlayerState.LOADING -> PlaybackStateCompat.STATE_BUFFERING
                        PlayerState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
                        PlayerState.ERROR -> PlaybackStateCompat.STATE_ERROR
                        else -> PlaybackStateCompat.STATE_STOPPED
                    },
                    st.positionMs,
                    if (playing) st.speed else 0f
                )
                .build()
        )

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(st))
    }

    private fun buildNotification(st: PlaybackStatus): Notification {
        createChannel()
        val current = CastHub.session.value?.current(st)
        if (CastHub.session.value?.isPhotos == true) {
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Showing photos on TV")
                .setContentText(current?.title)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .apply {
                    if (st.itemCount > 1) {
                        addAction(android.R.drawable.ic_media_previous, "Previous", serviceIntent(ACTION_PREVIOUS))
                        addAction(android.R.drawable.ic_media_next, "Next", serviceIntent(ACTION_NEXT))
                    }
                }
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceIntent(ACTION_STOP))
                .build()
        }
        val playing = st.state == PlayerState.PLAYING || st.state == PlayerState.BUFFERING
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(st.title ?: current?.title ?: "Casting to TV")
            .setContentText(statusText(st))
            .setContentIntent(openAppIntent())
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLargeIcon(artwork)
        val seekable = st.seekable && st.durationMs > 0
        val queue = st.itemCount > 1
        // Expanded: [previous] [back] play/pause [forward] [next] stop.
        // Compact: back (or previous), play/pause, stop.
        var count = 0
        var compactFirst = -1
        if (queue) {
            builder.addAction(android.R.drawable.ic_media_previous, "Previous", serviceIntent(ACTION_PREVIOUS))
            if (!seekable) compactFirst = count
            count++
        }
        if (seekable) {
            builder.addAction(android.R.drawable.ic_media_rew, "Back", serviceIntent(ACTION_BACK))
            compactFirst = count
            count++
        }
        val toggleIndex = count++
        builder.addAction(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "Pause" else "Play",
            serviceIntent(ACTION_TOGGLE)
        )
        if (seekable) { builder.addAction(android.R.drawable.ic_media_ff, "Forward", serviceIntent(ACTION_FORWARD)); count++ }
        if (queue) { builder.addAction(android.R.drawable.ic_media_next, "Next", serviceIntent(ACTION_NEXT)); count++ }
        val stopIndex = count
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceIntent(ACTION_STOP))
        val compact = listOfNotNull(compactFirst.takeIf { it >= 0 }, toggleIndex, stopIndex).toIntArray()
        builder.setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact))
        return builder.build()
    }

    private fun statusText(st: PlaybackStatus): String = when (st.state) {
        PlayerState.BUFFERING, PlayerState.LOADING -> "Buffering…"
        PlayerState.ERROR -> "Playback error"
        PlayerState.ENDED -> "Finished"
        PlayerState.PAUSED -> "Paused on TV"
        PlayerState.PLAYING -> if (st.isLive) "Live on TV" else "Playing on TV"
        PlayerState.IDLE -> "Starting on TV…"
    }

    private fun loadArtwork(url: String) {
        scope.launch {
            val result = ImageLoader(this@CastSessionService).execute(
                ImageRequest.Builder(this@CastSessionService).data(url).size(512).allowHardware(false).build()
            )
            if (url == artworkUrl && result is SuccessResult) {
                artwork = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                update(CastHub.status.value)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvRemote:cast").apply { acquire(MAX_LOCK_MS) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TvRemote:cast").apply { acquire() }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Casting to TV", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .setAction(CastHub.ACTION_OPEN_NOW_PLAYING)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, CastSessionService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val CHANNEL_ID = "cast_session"
        private const val NOTIFICATION_ID = 2002
        private const val MAX_LOCK_MS = 4 * 60 * 60 * 1000L
        private const val ACTION_TOGGLE = "cast.toggle"
        private const val ACTION_BACK = "cast.back"
        private const val ACTION_FORWARD = "cast.forward"
        private const val ACTION_STOP = "cast.stop"
        private const val ACTION_PREVIOUS = "cast.previous"
        private const val ACTION_NEXT = "cast.next"

        private val observeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** Starts the service while a cast session is active, stops it afterwards. */
        fun observe(context: Context) {
            val app = context.applicationContext
            observeScope.launch {
                CastHub.session.map { it != null && !it.ended }.distinctUntilChanged().collect { active ->
                    val intent = Intent(app, CastSessionService::class.java)
                    if (active) runCatching { ContextCompat.startForegroundService(app, intent) }
                    else app.stopService(intent)
                }
            }
        }
    }
}
